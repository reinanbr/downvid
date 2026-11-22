package netx

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/downvid/core/internal/logx"
)

const (
	// DefaultConnections and MaxConnections bound the parallel connections
	// of one download (file ranges and HLS segments).
	DefaultConnections = 8
	MaxConnections     = 16

	parallelMin = 2 << 20   // files smaller than this are fetched in one stream
	minSplit    = 512 << 10 // a range is split only if both halves get at least this
	maxRetries  = 4
	writeBufLen = 512 << 10 // per-connection write coalescing (TLS reads are ~16 KB)

	// A connection much slower than the others is reopened (often a better
	// route or server): below 1/slowFactor of the typical connection speed
	// for slowAfter.
	slowFactor  = 4
	maxRestarts = 3
)

var slowAfter = 5 * time.Second // var for tests

var connections atomic.Int32

func init() { connections.Store(DefaultConnections) }

// SetConnections sets how many parallel connections a download uses,
// clamped to 1..MaxConnections.
func SetConnections(n int) { connections.Store(int32(clampConns(n))) }

// Connections returns the current parallel connection setting.
func Connections() int { return int(connections.Load()) }

func clampConns(n int) int { return max(1, min(n, MaxConnections)) }

var bufPool = sync.Pool{New: func() any { b := make([]byte, writeBufLen); return &b }}

// Counter aggregates bytes written by concurrent workers.
type Counter struct{ n atomic.Int64 }

func (c *Counter) Add(n int64)   { c.n.Add(n) }
func (c *Counter) Load() int64   { return c.n.Load() }
func (c *Counter) Store(n int64) { c.n.Store(n) }

// RemoteInfo is what a probe request learns about a resource.
type RemoteInfo struct {
	Size         int64 // -1 when unknown
	ContentType  string
	AcceptRanges bool
	FinalURL     string
}

// Probe asks for the first byte with a Range request, which works on servers
// that reject HEAD and also tells whether ranges are supported.
func Probe(ctx context.Context, url string, h Headers) (RemoteInfo, error) {
	return probe(ctx, Client, url, h)
}

func probe(ctx context.Context, c *http.Client, url string, h Headers) (RemoteInfo, error) {
	resp, err := doWith(ctx, c, http.MethodGet, url, h, map[string]string{"Range": "bytes=0-0"})
	if err != nil {
		return RemoteInfo{}, err
	}
	defer resp.Body.Close()
	info := RemoteInfo{
		Size:        -1,
		ContentType: strings.ToLower(strings.TrimSpace(strings.Split(resp.Header.Get("Content-Type"), ";")[0])),
		FinalURL:    resp.Request.URL.String(),
	}
	if resp.StatusCode == http.StatusPartialContent {
		info.AcceptRanges = true
		// Content-Range: bytes 0-0/12345
		if cr := resp.Header.Get("Content-Range"); cr != "" {
			if i := strings.LastIndexByte(cr, '/'); i >= 0 {
				if n, err := strconv.ParseInt(cr[i+1:], 10, 64); err == nil {
					info.Size = n
				}
			}
		}
	} else if resp.ContentLength >= 0 {
		info.Size = resp.ContentLength
	}
	return info, nil
}

// DownloadFile fetches url into dst. See DownloadFileOpts.
func DownloadFile(ctx context.Context, url string, h Headers, dst string, done *Counter, onSize func(int64)) (int64, error) {
	return DownloadFileOpts(ctx, url, h, dst, done, onSize, Options{})
}

// Options tune DownloadFileOpts.
type Options struct {
	// ChunkSize caps each Range request (YouTube serves at most ~10 MB per
	// request at full speed; yt-dlp reports it as http_chunk_size). 0 = no cap.
	ChunkSize int64
	// Resume continues a previous partial download of dst (<dst>.dvstate).
	Resume bool
	// Connections overrides the global setting (0 = Connections()).
	Connections int
	// KeepDone records a finished download in its sidecar instead of
	// deleting it, so a resumed job does not fetch a finished input again
	// (inputs of merge/audio jobs, whose temp dir is removed on success).
	KeepDone bool
}

// DownloadFileOpts fetches url into dst. On range-capable servers the file
// is split across parallel connections (one TCP connection each); when a
// connection finishes its range it takes over half of the largest range
// still pending, so all connections stay busy until the end. Connections
// that fall far behind are reopened, and connections the server refuses
// (429/503) are dropped instead of failing the download. Every range
// retries and resumes from the exact byte. Progress is reported through
// `done` (bytes received) and the size, once known, through onSize.
func DownloadFileOpts(ctx context.Context, url string, h Headers, dst string, done *Counter, onSize func(int64), opt Options) (int64, error) {
	info, err := probe(ctx, MediaClient, url, h)
	if err != nil {
		return 0, err
	}
	if onSize != nil && info.Size > 0 {
		onSize(info.Size)
	}
	logx.Infof("download: size=%d ranges=%v type=%s url=%s", info.Size, info.AcceptRanges, info.ContentType, info.FinalURL)

	if !info.AcceptRanges || info.Size <= 0 {
		// No ranges: nothing to resume, always from the start.
		os.Remove(dst + sidecarExt)
		f, err := os.Create(dst)
		if err != nil {
			return 0, err
		}
		defer f.Close()
		return streamWithRetry(ctx, url, h, f, done)
	}

	conns := opt.Connections
	if conns <= 0 {
		conns = Connections()
	}
	conns = clampConns(conns)
	if info.Size < parallelMin {
		conns = 1
	}
	conns = min(conns, max(1, int(info.Size/minSplit))) // parts of at least minSplit
	parts, resumed := loadSidecar(dst, info.Size, conns, opt.Resume)
	var f *os.File
	if resumed {
		f, err = os.OpenFile(dst, os.O_RDWR, 0o644)
	} else {
		f, err = os.Create(dst)
		if err == nil {
			err = f.Truncate(info.Size)
		}
	}
	if err != nil {
		return 0, err
	}
	defer f.Close()

	var have int64
	for _, p := range parts {
		have += p.pos.Load() - p.start
	}
	if resumed {
		logx.Infof("download: resuming %s at %d/%d bytes", dst, have, info.Size)
	}
	done.Add(have)
	if resumed && have == info.Size {
		return info.Size, nil // finished in an earlier attempt
	}

	d := &ranged{url: url, h: h, f: f, dst: dst, size: info.Size, chunk: opt.ChunkSize, done: done, parts: parts}
	t0 := time.Now()
	if err := d.run(ctx, conns); err != nil {
		d.saveSidecar() // keep progress for the next attempt
		return 0, err
	}
	if opt.KeepDone {
		f.Sync() // the sidecar must not claim data the disk does not have
		saveSidecar(dst, info.Size, []*part{donePart(info.Size)})
	} else {
		os.Remove(dst + sidecarExt)
	}
	logx.Infof("download: %d bytes in %s with %d connections (%d ranges)",
		info.Size, time.Since(t0).Round(time.Millisecond), conns, len(d.parts))
	return info.Size, nil
}

// ---- dynamic ranges

// part is a byte range [start, end] of the file. Its owner reads it from
// one connection; another connection may take over its tail (end shrinks).
type part struct {
	start int64
	mu    sync.Mutex   // makes claiming bytes and taking the tail atomic
	end   atomic.Int64 // last byte, inclusive (written under mu)
	next  atomic.Int64 // next byte to receive (written under mu)
	pos   atomic.Int64 // bytes before pos are on disk (resume state)
	got   atomic.Int64 // bytes received, for the speed monitor
	// reopened counts how often the monitor dropped a slow request.
	reopened atomic.Int32
	stop     context.CancelFunc
	owned    bool // guarded by ranged.mu
}

func newPart(start, end int64) *part {
	p := &part{start: start}
	p.end.Store(end)
	p.next.Store(start)
	p.pos.Store(start)
	return p
}

func (p *part) remaining() int64 { return max(0, p.end.Load()-p.next.Load()+1) }

// claim accepts n received bytes at offset off; it returns how many belong
// to this part (fewer once another connection took over the tail).
func (p *part) claim(off int64, n int) int {
	p.mu.Lock()
	defer p.mu.Unlock()
	end := p.end.Load()
	if off > end {
		return 0
	}
	n = int(min(int64(n), end-off+1))
	p.next.Store(off + int64(n))
	return n
}

// split gives away the second half of what is left, if both halves are big
// enough. Returns the range for the new part.
func (p *part) split() (int64, int64, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	next, end := p.next.Load(), p.end.Load()
	rem := end - next + 1
	if rem < 2*minSplit {
		return 0, 0, false
	}
	mid := next + rem/2
	p.end.Store(mid - 1)
	return mid, end, true
}

func (p *part) setStop(c context.CancelFunc) {
	p.mu.Lock()
	p.stop = c
	p.mu.Unlock()
}

// reopen drops the current request; the owner continues on a new one.
func (p *part) reopen() {
	p.mu.Lock()
	c := p.stop
	p.mu.Unlock()
	if c != nil && p.reopened.Add(1) <= maxRestarts {
		c()
	}
}

type ranged struct {
	url   string
	h     Headers
	f     *os.File
	dst   string
	size  int64
	chunk int64
	done  *Counter

	mu      sync.Mutex
	parts   []*part
	limited error // last "too many connections" refusal

	okResp atomic.Int32 // ranges the server accepted
	active atomic.Int32 // running connections
	rate   atomic.Int64 // typical speed of one connection (bytes/s, EWMA of finished requests)
}

// noteRate records the speed of a finished request.
func (d *ranged) noteRate(n int64, el time.Duration) {
	if n < 256<<10 || el <= 0 {
		return
	}
	r := int64(float64(n) / el.Seconds())
	for {
		old := d.rate.Load()
		nw := r
		if old > 0 {
			nw = (3*old + r) / 4
		}
		if d.rate.CompareAndSwap(old, nw) {
			return
		}
	}
}

// run downloads every pending range with up to conns connections. If the
// server refused some connections and work is left when the others end,
// it tries again with fewer.
func (d *ranged) run(ctx context.Context, conns int) error {
	ctx, cancel := context.WithCancelCause(ctx)
	defer cancel(nil)

	stop := make(chan struct{})
	var bg sync.WaitGroup
	bg.Add(2)
	go func() { defer bg.Done(); d.persistLoop(stop) }()
	go func() { defer bg.Done(); d.monitor(stop) }()
	defer func() { close(stop); bg.Wait() }()

	for round := 0; ; round++ {
		var wg sync.WaitGroup
		d.active.Store(int32(conns))
		for range conns {
			wg.Add(1)
			go func() {
				defer wg.Done()
				d.worker(ctx, cancel)
			}()
		}
		wg.Wait()
		if err := context.Cause(ctx); err != nil {
			return err
		}
		if d.pending() == 0 {
			return nil
		}
		d.mu.Lock()
		lim := d.limited
		d.mu.Unlock()
		if lim == nil || round >= 2 {
			if lim == nil {
				lim = errors.New("download ended with ranges missing")
			}
			return lim
		}
		conns = max(1, conns/2)
		logx.Warnf("download: server limits connections (%v); continuing with %d", lim, conns)
	}
}

func (d *ranged) pending() int64 {
	d.mu.Lock()
	defer d.mu.Unlock()
	var n int64
	for _, p := range d.parts {
		n += p.remaining()
	}
	return n
}

// take returns a range to download: a pending one nobody owns, or the
// second half of the largest range in progress. nil when nothing is left
// worth splitting.
func (d *ranged) take() *part {
	d.mu.Lock()
	defer d.mu.Unlock()
	for _, p := range d.parts {
		if !p.owned && p.remaining() > 0 {
			p.owned = true
			return p
		}
	}
	var big *part
	var bigRem int64
	for _, p := range d.parts {
		if r := p.remaining(); p.owned && r > bigRem {
			big, bigRem = p, r
		}
	}
	if big == nil {
		return nil
	}
	start, end, ok := big.split()
	if !ok {
		return nil
	}
	np := newPart(start, end)
	np.owned = true
	d.parts = append(d.parts, np)
	return np
}

func (d *ranged) release(p *part) {
	d.mu.Lock()
	p.owned = false
	d.mu.Unlock()
}

func (d *ranged) worker(ctx context.Context, cancel context.CancelCauseFunc) {
	defer d.active.Add(-1)
	bp := bufPool.Get().(*[]byte)
	defer bufPool.Put(bp)
	for {
		p := d.take()
		if p == nil {
			return
		}
		err := d.fetchPart(ctx, p, *bp)
		d.release(p)
		if err == nil {
			continue
		}
		if ctx.Err() != nil {
			return
		}
		if d.canDrop(err) {
			// The server refuses this extra connection: stop it and let the
			// others take its range.
			d.mu.Lock()
			d.limited = err
			d.mu.Unlock()
			logx.Warnf("download: dropping a connection: %v", err)
			return
		}
		cancel(err)
		return
	}
}

// canDrop: err looks like a per-client connection limit and other
// connections are still downloading.
func (d *ranged) canDrop(err error) bool {
	var he *HTTPError
	if !errors.As(err, &he) || d.active.Load() <= 1 || d.okResp.Load() == 0 {
		return false
	}
	return he.Status == 429 || he.Status == 503 || he.Status == 403
}

// fetchPart downloads p until it is complete (its end may shrink while it
// runs), in requests of at most d.chunk bytes, retrying transient errors.
func (d *ranged) fetchPart(ctx context.Context, p *part, buf []byte) error {
	attempt := 0
	var lastErr error
	for {
		off, end := p.next.Load(), p.end.Load()
		if off > end {
			return nil
		}
		if d.chunk > 0 {
			end = min(end, off+d.chunk-1)
		}
		rctx, rcancel := context.WithCancel(ctx)
		p.setStop(rcancel)
		n, err := d.fetchRange(rctx, p, off, end, buf)
		reopened := rctx.Err() != nil && ctx.Err() == nil
		p.setStop(nil)
		rcancel()
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if reopened {
			logx.Infof("download: reopening slow connection at %d", p.next.Load())
			continue
		}
		if err == nil {
			attempt = 0
			continue
		}
		if n > 0 {
			attempt = 0 // it was moving: the budget is for consecutive failures
		}
		if !retryable(err) || d.canDrop(err) {
			return err
		}
		lastErr = err
		attempt++
		if attempt > maxRetries {
			return fmt.Errorf("failed after %d attempts: %w", maxRetries, lastErr)
		}
		logx.Warnf("range %d-%d: retry %d: %v", off, end, attempt, err)
		if err := sleepCtx(ctx, backoff(attempt)); err != nil {
			return err
		}
	}
}

var errNoRange = errors.New("server ignored Range")

// fetchRange requests [off, end] and writes the bytes that still belong to
// p, coalescing writes in buf. Returns the bytes received.
func (d *ranged) fetchRange(ctx context.Context, p *part, off, end int64, buf []byte) (n int64, err error) {
	g := newStallGuard(ctx)
	defer g.Stop()
	defer func() { err = g.Err(err) }()
	resp, err := DoMedia(g.ctx, http.MethodGet, d.url, d.h, map[string]string{
		"Range": fmt.Sprintf("bytes=%d-%d", off, end),
	})
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusPartialContent {
		return 0, fmt.Errorf("%w (HTTP %d)", errNoRange, resp.StatusCode)
	}
	d.okResp.Add(1)

	cur, bufOff, fill := off, off, 0
	t0 := time.Now()
	defer func() { d.noteRate(cur-off, time.Since(t0)) }()
	flush := func() error {
		if fill == 0 {
			return nil
		}
		if _, err := d.f.WriteAt(buf[:fill], bufOff); err != nil {
			return err
		}
		bufOff += int64(fill)
		fill = 0
		p.pos.Store(bufOff)
		return nil
	}
	for {
		n, rerr := resp.Body.Read(buf[fill:])
		if n > 0 {
			g.Touch()
			k := p.claim(cur, n)
			fill += k
			cur += int64(k)
			d.done.Add(int64(k))
			p.got.Add(int64(k))
			if k < n || cur > p.end.Load() {
				// The rest of this response now belongs to another connection.
				return cur - off, flush()
			}
			if fill == len(buf) {
				if err := flush(); err != nil {
					return cur - off, err
				}
			}
		}
		if rerr != nil {
			if err := flush(); err != nil {
				return cur - off, err
			}
			if rerr == io.EOF {
				if cur > end {
					return cur - off, nil
				}
				rerr = io.ErrUnexpectedEOF
			}
			return cur - off, rerr
		}
	}
}

// persistLoop saves the resume state every second.
func (d *ranged) persistLoop(stop chan struct{}) {
	t := time.NewTicker(time.Second)
	defer t.Stop()
	for {
		select {
		case <-stop:
			return
		case <-t.C:
			d.saveSidecar()
		}
	}
}

// monitor reopens connections that stay far slower than the others. The
// reference is the median connection speed while at least three ranges are
// running, otherwise the typical speed of finished requests, so a slow
// connection holding the last range (the classic slow tail) is reopened too.
func (d *ranged) monitor(stop chan struct{}) {
	t := time.NewTicker(time.Second)
	defer t.Stop()
	last := map[*part]int64{}
	slow := map[*part]int{}
	for {
		select {
		case <-stop:
			return
		case <-t.C:
		}
		d.mu.Lock()
		var live []*part
		for _, p := range d.parts {
			if p.owned && p.remaining() > 0 {
				live = append(live, p)
			}
		}
		d.mu.Unlock()
		speeds := make([]int64, len(live))
		for i, p := range live {
			g := p.got.Load()
			speeds[i] = g - last[p]
			last[p] = g
		}
		ref := d.rate.Load()
		if len(live) >= 3 {
			sorted := append([]int64(nil), speeds...)
			sort.Slice(sorted, func(a, b int) bool { return sorted[a] < sorted[b] })
			ref = sorted[len(sorted)/2]
		}
		if ref <= 0 {
			continue
		}
		for i, p := range live {
			if speeds[i]*slowFactor < ref && p.remaining() > 64<<10 {
				slow[p]++
			} else {
				slow[p] = 0
			}
			if time.Duration(slow[p])*time.Second >= slowAfter {
				slow[p] = 0
				p.reopen()
			}
		}
	}
}

// ---- resume state: <dst>.dvstate = {"size":N,"parts":[[start,end,pos],...]}

const sidecarExt = ".dvstate"

func newParts(size int64, n int) []*part {
	parts := make([]*part, n)
	l := size / int64(n)
	for i := range n {
		end := int64(i)*l + l - 1
		if i == n-1 {
			end = size - 1
		}
		parts[i] = newPart(int64(i)*l, end)
	}
	return parts
}

// loadSidecar returns saved parts when they match the remote size and the
// partial file is intact; otherwise fresh parts.
func loadSidecar(dst string, size int64, n int, resume bool) ([]*part, bool) {
	if resume {
		var st struct {
			Size  int64      `json:"size"`
			Parts [][3]int64 `json:"parts"`
		}
		b, err := os.ReadFile(dst + sidecarExt)
		fi, ferr := os.Stat(dst)
		if err == nil && ferr == nil && fi.Size() == size && json.Unmarshal(b, &st) == nil && st.Size == size && len(st.Parts) > 0 {
			parts := make([]*part, len(st.Parts))
			ok := true
			for i, v := range st.Parts {
				if v[2] < v[0] || v[2] > v[1]+1 || v[1] >= size {
					ok = false
					break
				}
				p := newPart(v[0], v[1])
				p.next.Store(v[2])
				p.pos.Store(v[2])
				parts[i] = p
			}
			if ok {
				return parts, true
			}
		}
	}
	return newParts(size, n), false
}

// donePart is a part covering the whole file, fully downloaded.
func donePart(size int64) *part {
	p := newPart(0, size-1)
	p.next.Store(size)
	p.pos.Store(size)
	return p
}

func (d *ranged) saveSidecar() {
	d.mu.Lock()
	parts := append([]*part(nil), d.parts...)
	d.mu.Unlock()
	saveSidecar(d.dst, d.size, parts)
}

func saveSidecar(dst string, size int64, parts []*part) {
	st := struct {
		Size  int64      `json:"size"`
		Parts [][3]int64 `json:"parts"`
	}{Size: size}
	for _, p := range parts {
		st.Parts = append(st.Parts, [3]int64{p.start, p.end.Load(), p.pos.Load()})
	}
	b, _ := json.Marshal(st)
	tmp := dst + sidecarExt + ".tmp"
	if os.WriteFile(tmp, b, 0o644) == nil {
		os.Rename(tmp, dst+sidecarExt)
	}
}

// ---- single stream (no Range support)

// streamWithRetry fetches a resource without range support, restarting from
// the beginning after transient errors.
func streamWithRetry(ctx context.Context, url string, h Headers, f *os.File, done *Counter) (int64, error) {
	var lastErr error
	// writerOnly hides os.File.ReadFrom, which bufio would otherwise call
	// directly, writing every small network read separately.
	bw := bufio.NewWriterSize(writerOnly{f}, writeBufLen)
	for attempt := 0; attempt <= maxRetries; attempt++ {
		if attempt > 0 {
			logx.Warnf("stream retry %d: %v", attempt, lastErr)
			if err := sleepCtx(ctx, backoff(attempt)); err != nil {
				return 0, err
			}
			done.Store(0)
			bw.Reset(writerOnly{f})
			if _, err := f.Seek(0, io.SeekStart); err != nil {
				return 0, err
			}
			if err := f.Truncate(0); err != nil {
				return 0, err
			}
		}
		g := newStallGuard(ctx)
		resp, err := DoMedia(g.ctx, http.MethodGet, url, h, nil)
		if err != nil {
			g.Stop()
			if err = g.Err(err); !retryable(err) {
				return 0, err
			}
			lastErr = err
			continue
		}
		n, err := bw.ReadFrom(&countReader{r: guardedReader{resp.Body, g}, done: done})
		resp.Body.Close()
		err = g.Err(err)
		g.Stop()
		if err == nil {
			err = bw.Flush()
		}
		if err == nil {
			return n, nil
		}
		if ctx.Err() != nil {
			return 0, ctx.Err()
		}
		lastErr = err
	}
	return 0, fmt.Errorf("failed after %d attempts: %w", maxRetries, lastErr)
}

type writerOnly struct{ io.Writer }

type countReader struct {
	r    io.Reader
	done *Counter
}

func (c *countReader) Read(p []byte) (int, error) {
	n, err := c.r.Read(p)
	c.done.Add(int64(n))
	return n, err
}

func retryable(err error) bool {
	var he *HTTPError
	if errors.As(err, &he) {
		return he.Status == 408 || he.Status == 429 || he.Status >= 500
	}
	if errors.Is(err, errNoRange) {
		return false
	}
	return !errors.Is(err, context.Canceled)
}

func backoff(attempt int) time.Duration {
	return time.Duration(attempt*attempt) * 500 * time.Millisecond
}

func sleepCtx(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

// Retry runs fn with the package's retry policy (used for HLS segments).
func Retry(ctx context.Context, what string, fn func() error) error {
	var err error
	for attempt := 0; attempt <= maxRetries; attempt++ {
		if attempt > 0 {
			logx.Warnf("%s: retry %d: %v", what, attempt, err)
			if e := sleepCtx(ctx, backoff(attempt)); e != nil {
				return e
			}
		}
		if err = fn(); err == nil || !retryable(err) || ctx.Err() != nil {
			return err
		}
	}
	return err
}
