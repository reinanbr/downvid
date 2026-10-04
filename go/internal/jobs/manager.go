package jobs

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/downvid/core/internal/hls"
	"github.com/downvid/core/internal/logx"
	"github.com/downvid/core/internal/media"
	"github.com/downvid/core/internal/netx"
)

// Job states (persisted).
const (
	StateQueued   = "queued"   // waiting for a free slot
	StateRunning  = "running"  // downloading / converting
	StatePaused   = "paused"   // by the user; partial data kept
	StateWaiting  = "waiting"  // network error: retried automatically
	StateDone     = "done"     // file ready, waiting to be saved to the gallery
	StateSaved    = "saved"    // in the gallery (history)
	StateError    = "error"    // failed; "retry" restarts it
	StateExpired  = "expired"  // source URL expired: needs a new extraction
	StateCanceled = "canceled" // by the user; partial data removed
)

// Meta is what the downloads screen shows; also what re-extraction needs.
type Meta struct {
	Title     string `json:"title,omitempty"`
	PageURL   string `json:"pageUrl,omitempty"`   // shared link (for re-extraction)
	OptionKey string `json:"optionKey,omitempty"` // which option of the link
	Thumbnail string `json:"thumbnail,omitempty"`
}

// Saved describes the published file (MediaStore).
type Saved struct {
	URI         string `json:"uri"`
	DisplayName string `json:"displayName"`
	Location    string `json:"location"`
	Mime        string `json:"mime"`
}

// Record is a persisted job.
type Record struct {
	ID      int64   `json:"id"`
	State   string  `json:"state"`
	Req     Request `json:"req"`
	Meta    Meta    `json:"meta"`
	Last    Event   `json:"last"` // latest progress / outcome
	Saved   *Saved  `json:"saved,omitempty"`
	Created int64   `json:"created"` // unix ms
	Updated int64   `json:"updated"`
	Retries int     `json:"retries,omitempty"`
}

// Status is a job as seen by the app and the foreground service.
type Status struct {
	ID    int64  `json:"id"`
	State string `json:"state"`
	Meta
	Event
	Saved   *Saved `json:"saved,omitempty"`
	Kind    string `json:"kind"`
	Created int64  `json:"created"`
	Updated int64  `json:"updated"`
}

var (
	errPaused   = errors.New("pausado")
	errCanceled = errors.New("cancelado")
)

const (
	maxNetworkRetries = 60
	historyLimit      = 300
)

type job struct {
	mu       sync.Mutex
	rec      Record
	listener func(Event) // the sheet that started it; nil once detached
	cancel   context.CancelCauseFunc
	retry    *time.Timer
}

var (
	mu       sync.Mutex
	jobs     = map[int64]*job{}
	nextID   int64
	stateDir string // "" = not persisted (host tools)
	maxConc  = 2
	ffmpeg   media.FFmpeg // current paths (they change with app updates)
	dirty    bool
	saverOn  bool
)

func now() int64 { return time.Now().UnixMilli() }

// Init loads the persisted jobs and resumes the interrupted ones. dir holds
// jobs.json; ff overrides the stored ffmpeg location.
func Init(dir string, ff media.FFmpeg, maxConcurrent int) ([]Status, error) {
	mu.Lock()
	stateDir, ffmpeg = dir, ff
	if maxConcurrent > 0 {
		maxConc = maxConcurrent
	}
	var recs []Record
	if b, err := os.ReadFile(filepath.Join(dir, "jobs.json")); err == nil {
		if err := json.Unmarshal(b, &recs); err != nil {
			logx.Warnf("jobs: unreadable jobs.json (%v); starting empty", err)
		}
	}
	resumed := 0
	for _, r := range recs {
		if _, ok := jobs[r.ID]; ok {
			continue
		}
		switch r.State {
		case StateRunning, StateWaiting:
			r.State = StateQueued // interrupted by the process exit
			resumed++
		case StateQueued:
			resumed++
		case StateDone:
			if _, err := os.Stat(r.Req.OutPath); err != nil {
				r.State, r.Last = StateError, Event{Type: "error", Message: "downloaded file not found; try again"}
			}
		}
		jobs[r.ID] = &job{rec: r}
		nextID = max(nextID, r.ID)
	}
	startSaver()
	mu.Unlock()
	logx.Infof("jobs: loaded %d records from %s, %d to resume", len(recs), dir, resumed)
	schedule()
	return Snapshot(), nil
}

// SetMaxConcurrent changes the number of simultaneous downloads.
func SetMaxConcurrent(n int) {
	if n < 1 {
		n = 1
	}
	mu.Lock()
	maxConc = n
	mu.Unlock()
	schedule()
}

// Start queues a download and returns its id. listener receives its events
// until Detach or a terminal event (done/error/canceled/paused/expired).
func Start(req Request, meta Meta, listener func(Event)) (int64, error) {
	if req.URL == "" || req.OutPath == "" {
		return 0, errors.New("url and outPath are required")
	}
	mu.Lock()
	nextID++
	j := &job{listener: listener, rec: Record{
		ID: nextID, State: StateQueued, Req: req, Meta: meta, Created: now(), Updated: now(),
		Last: Event{Type: "progress", Phase: "queued", Total: -1},
	}}
	jobs[j.rec.ID] = j
	dirty = true
	mu.Unlock()
	persist()
	schedule()
	return j.rec.ID, nil
}

// schedule starts queued jobs (oldest first) while slots are free.
func schedule() {
	mu.Lock()
	defer mu.Unlock()
	running := 0
	var queued []*job
	for _, j := range jobs {
		j.mu.Lock()
		switch j.rec.State {
		case StateRunning:
			running++
		case StateQueued:
			queued = append(queued, j)
		}
		j.mu.Unlock()
	}
	sort.Slice(queued, func(a, b int) bool { return queued[a].rec.ID < queued[b].rec.ID })
	for _, j := range queued {
		if running >= maxConc {
			break
		}
		running++
		ctx, cancel := context.WithCancelCause(context.Background())
		j.mu.Lock()
		j.rec.State, j.cancel = StateRunning, cancel
		if ffmpeg.Path != "" {
			j.rec.Req.FFmpeg = ffmpeg
		}
		j.rec.Updated = now()
		req, id := j.rec.Req, j.rec.ID
		j.mu.Unlock()
		dirty = true
		go j.execute(ctx, id, req)
	}
}

func (j *job) execute(ctx context.Context, id int64, req Request) {
	defer func() {
		if r := recover(); r != nil {
			logx.Errorf("job %d: panic: %v", id, r)
			j.finish(hls.Result{}, fmt.Errorf("internal error: %v", r))
		}
	}()
	res, err := run(ctx, id, req, j.emit)
	j.finish(res, err)
}

// finish turns an attempt's outcome into the job state.
func (j *job) finish(res hls.Result, err error) {
	j.mu.Lock()
	r := &j.rec
	var ev Event
	switch {
	case err == nil:
		r.State, r.Retries = StateDone, 0
		ev = Event{Type: "done", Path: res.Path, Size: res.Size, DurationMs: res.DurationMs, Warning: res.Warning, Percent: 100}
	case errors.Is(err, errPaused):
		r.State = StatePaused
		ev = r.Last
		ev.Type, ev.SpeedBps = "paused", 0
	case errors.Is(err, errCanceled):
		r.State = StateCanceled
		ev = Event{Type: "canceled"}
		cleanup(r.Req)
	case isExpired(err) && r.Meta.PageURL != "":
		r.State = StateExpired
		ev = Event{Type: "expired", Message: "the video link expired; renewing it…"}
	case isNetwork(err) && r.Retries < maxNetworkRetries:
		r.State = StateWaiting
		r.Retries++
		ev = r.Last
		ev.Type, ev.SpeedBps, ev.Message = "waiting", 0, "no connection — retrying"
		delay := time.Duration(min(r.Retries, 6)) * 10 * time.Second
		id := r.ID
		j.retry = time.AfterFunc(delay, func() { requeue(id, StateWaiting) })
		logx.Warnf("job %d: network error, retry %d in %s: %v", r.ID, r.Retries, delay, err)
	default:
		r.State = StateError
		ev = Event{Type: "error", Message: err.Error()}
	}
	r.Last, r.Updated, j.cancel = ev, now(), nil
	j.deliver(ev)
	j.mu.Unlock()
	persist()
	schedule()
}

// emit records a progress event and forwards it to the listener.
// Lock order everywhere: mu before j.mu (never mu while holding j.mu).
func (j *job) emit(ev Event) {
	j.mu.Lock()
	j.rec.Last = ev
	j.deliver(ev)
	j.mu.Unlock()
	mu.Lock()
	dirty = true
	mu.Unlock()
}

// deliver calls the listener (j.mu held, so Detach can guarantee no call
// happens after it returns). Terminal events detach it.
func (j *job) deliver(ev Event) {
	if l := j.listener; l != nil {
		l(ev)
		if ev.Type != "progress" && ev.Type != "waiting" {
			j.listener = nil
		}
	}
}

func get(id int64) *job {
	mu.Lock()
	defer mu.Unlock()
	return jobs[id]
}

// requeue moves a job from one of `from` states back to the queue.
func requeue(id int64, from ...string) bool {
	j := get(id)
	if j == nil {
		return false
	}
	j.mu.Lock()
	ok := false
	for _, f := range from {
		ok = ok || j.rec.State == f
	}
	if ok {
		j.rec.State, j.rec.Updated = StateQueued, now()
		j.rec.Last.Type, j.rec.Last.Phase, j.rec.Last.Message = "progress", "queued", ""
		if j.retry != nil {
			j.retry.Stop()
			j.retry = nil
		}
	}
	j.mu.Unlock()
	if ok {
		persist()
		schedule()
	}
	return ok
}

// Pause stops a job keeping its partial data.
func Pause(id int64) bool {
	j := get(id)
	if j == nil {
		return false
	}
	j.mu.Lock()
	switch j.rec.State {
	case StateRunning:
		cancel := j.cancel
		j.mu.Unlock()
		if cancel != nil {
			logx.Infof("job %d: pause requested", id)
			cancel(errPaused)
		}
		return true
	case StateQueued, StateWaiting:
		if j.retry != nil {
			j.retry.Stop()
			j.retry = nil
		}
		j.rec.State, j.rec.Updated = StatePaused, now()
		j.rec.Last.Type, j.rec.Last.SpeedBps = "paused", 0
		j.deliver(j.rec.Last)
		j.mu.Unlock()
		persist()
		return true
	}
	j.mu.Unlock()
	return false
}

// Resume restarts a paused/failed job; downloads continue from partial data.
func Resume(id int64) bool {
	if j := get(id); j != nil {
		j.mu.Lock()
		j.rec.Retries = 0
		j.mu.Unlock()
	}
	return requeue(id, StatePaused, StateError, StateWaiting, StateExpired)
}

// Cancel stops a job and removes its partial data. Returns false when it
// was already finished.
func Cancel(id int64) bool {
	j := get(id)
	if j == nil {
		return false
	}
	j.mu.Lock()
	switch j.rec.State {
	case StateRunning:
		cancel := j.cancel
		j.mu.Unlock()
		if cancel != nil {
			logx.Infof("job %d: cancel requested", id)
			cancel(errCanceled)
		}
		return true
	case StateQueued, StatePaused, StateWaiting, StateError, StateExpired:
		if j.retry != nil {
			j.retry.Stop()
			j.retry = nil
		}
		cleanup(j.rec.Req)
		j.rec.State, j.rec.Updated = StateCanceled, now()
		j.rec.Last = Event{Type: "canceled"}
		j.deliver(j.rec.Last)
		j.mu.Unlock()
		persist()
		return true
	}
	j.mu.Unlock()
	return false
}

// Detach stops event delivery to the listener; the download continues.
// After it returns the listener is never called again.
func Detach(id int64) {
	if j := get(id); j != nil {
		j.mu.Lock()
		j.listener = nil
		j.mu.Unlock()
		logx.Infof("job %d: detached from UI", id)
	}
}

// Remove deletes a record (cancelling it first). The published file, if
// any, is the app's to delete (MediaStore).
func Remove(id int64) {
	Cancel(id)
	mu.Lock()
	if j := jobs[id]; j != nil {
		j.mu.Lock()
		if j.rec.State != StateSaved {
			cleanup(j.rec.Req)
		}
		j.mu.Unlock()
		delete(jobs, id)
		dirty = true
	}
	mu.Unlock()
	persist()
}

// Forget is kept for API compatibility: finished jobs now stay as history.
func Forget(id int64) {}

// MarkSaved records where the file was published.
func MarkSaved(id int64, s Saved) {
	if j := get(id); j != nil {
		j.mu.Lock()
		j.rec.State, j.rec.Saved, j.rec.Updated = StateSaved, &s, now()
		j.mu.Unlock()
		persist()
	}
}

// Replace gives a job new source URLs (after re-extraction of an expired
// link) and queues it; partial data is reused.
func Replace(id int64, url, audioURL string, headers netx.Headers, chunkSize int64) bool {
	j := get(id)
	if j == nil {
		return false
	}
	j.mu.Lock()
	j.rec.Req.URL, j.rec.Req.AudioURL, j.rec.Req.Headers = url, audioURL, headers
	if chunkSize > 0 {
		j.rec.Req.ChunkSize = chunkSize
	}
	j.rec.Retries = 0
	j.mu.Unlock()
	logx.Infof("job %d: source replaced after re-extraction", id)
	return requeue(id, StateExpired, StateError, StatePaused, StateWaiting)
}

// Snapshot returns every job, newest first.
func Snapshot() []Status {
	mu.Lock()
	list := make([]*job, 0, len(jobs))
	for _, j := range jobs {
		list = append(list, j)
	}
	mu.Unlock()
	out := make([]Status, 0, len(list))
	for _, j := range list {
		j.mu.Lock()
		r := j.rec
		out = append(out, Status{
			ID: r.ID, State: r.State, Meta: r.Meta, Event: r.Last, Saved: r.Saved,
			Kind: r.Req.Kind, Created: r.Created, Updated: r.Updated,
		})
		j.mu.Unlock()
	}
	sort.Slice(out, func(a, b int) bool { return out[a].ID > out[b].ID })
	return out
}

// ---- persistence

func persist() {
	mu.Lock()
	dirty = true
	mu.Unlock()
	save()
}

// save writes jobs.json atomically (when persistence is on and something
// changed), trimming old history.
func save() {
	mu.Lock()
	if stateDir == "" || !dirty {
		mu.Unlock()
		return
	}
	dirty = false
	recs := make([]Record, 0, len(jobs))
	for _, j := range jobs {
		j.mu.Lock()
		recs = append(recs, j.rec)
		j.mu.Unlock()
	}
	sort.Slice(recs, func(a, b int) bool { return recs[a].ID > recs[b].ID })
	var keep []Record
	finished := 0
	for _, r := range recs {
		if r.State == StateSaved || r.State == StateCanceled {
			finished++
			if finished > historyLimit {
				delete(jobs, r.ID)
				continue
			}
		}
		keep = append(keep, r)
	}
	dir := stateDir
	mu.Unlock()

	b, err := json.Marshal(keep)
	if err == nil {
		tmp := filepath.Join(dir, "jobs.json.tmp")
		if err = os.WriteFile(tmp, b, 0o644); err == nil {
			err = os.Rename(tmp, filepath.Join(dir, "jobs.json"))
		}
	}
	if err != nil {
		logx.Warnf("jobs: save failed: %v", err)
	}
}

// startSaver flushes progress to disk every few seconds (mu held).
func startSaver() {
	if saverOn || stateDir == "" {
		return
	}
	saverOn = true
	go func() {
		for range time.Tick(3 * time.Second) {
			save()
		}
	}()
}

// cleanup removes a job's partial data (output, resume state, temp dir).
func cleanup(req Request) {
	if req.OutPath == "" {
		return
	}
	os.Remove(req.OutPath)
	os.Remove(req.OutPath + ".dvstate")
	tmp := req.TmpDir
	if tmp == "" {
		tmp = req.OutPath + ".parts"
	}
	os.RemoveAll(tmp)
}

// isExpired: the source refuses the URL (signed CDN links expire).
func isExpired(err error) bool {
	var he *netx.HTTPError
	if errors.As(err, &he) {
		return he.Status == 401 || he.Status == 403 || he.Status == 404 || he.Status == 410
	}
	return false
}

// isNetwork: connectivity problems worth retrying later.
func isNetwork(err error) bool {
	var he *netx.HTTPError
	if errors.As(err, &he) {
		return he.Status >= 500 || he.Status == 429 || he.Status == 408
	}
	var ne net.Error
	if errors.As(err, &ne) || errors.Is(err, io.ErrUnexpectedEOF) {
		return true
	}
	l := strings.ToLower(err.Error())
	for _, s := range []string{"no such host", "connection reset", "connection refused", "network is unreachable",
		"timeout", "broken pipe", "tls handshake", "failed after"} {
		if strings.Contains(l, s) {
			return true
		}
	}
	return false
}
