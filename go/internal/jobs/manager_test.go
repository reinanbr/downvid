package jobs

import (
	"bytes"
	"crypto/rand"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/downvid/core/internal/media"
)

func reset(t *testing.T) string {
	t.Helper()
	mu.Lock()
	for _, j := range jobs {
		if j.cancel != nil {
			j.cancel(errCanceled)
		}
	}
	jobs, nextID, stateDir, maxConc, dirty = map[int64]*job{}, 0, "", 2, false
	mu.Unlock()
	return t.TempDir()
}

// slowServer serves `data` with Range support at a limited rate.
func slowServer(data []byte, served *atomic.Int64) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "video/mp4")
		http.ServeContent(&slowWriter{w, served}, r, "v.mp4", time.Time{}, bytes.NewReader(data))
	}))
}

type slowWriter struct {
	http.ResponseWriter
	served *atomic.Int64
}

func (s *slowWriter) Write(p []byte) (int, error) {
	total := 0
	for len(p) > 0 {
		n := min(len(p), 32<<10)
		w, err := s.ResponseWriter.Write(p[:n])
		total += w
		s.served.Add(int64(w))
		if err != nil {
			return total, err
		}
		s.ResponseWriter.(http.Flusher).Flush()
		p = p[n:]
		time.Sleep(10 * time.Millisecond)
	}
	return total, nil
}

func waitState(t *testing.T, id int64, want string, timeout time.Duration) Status {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		for _, s := range Snapshot() {
			if s.ID == id && s.State == want {
				return s
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	for _, s := range Snapshot() {
		if s.ID == id {
			t.Fatalf("job %d: state %s (%s), want %s", id, s.State, s.Message, want)
		}
	}
	t.Fatalf("job %d not found", id)
	return Status{}
}

func TestQueuePauseResumeCancelPersist(t *testing.T) {
	dir := reset(t)
	if _, err := Init(dir, media.FFmpeg{}, 1); err != nil { // one at a time
		t.Fatal(err)
	}
	data := make([]byte, 16<<20)
	rand.Read(data)
	var served atomic.Int64
	srv := slowServer(data, &served)
	defer srv.Close()

	out1, out2 := filepath.Join(dir, "a.mp4"), filepath.Join(dir, "b.mp4")
	id1, _ := Start(Request{Kind: "mp4", URL: srv.URL, OutPath: out1}, Meta{Title: "A", PageURL: "https://x/a"}, nil)
	id2, _ := Start(Request{Kind: "mp4", URL: srv.URL, OutPath: out2}, Meta{Title: "B"}, nil)

	// Concurrency 1: the second waits in the queue.
	waitState(t, id1, StateRunning, time.Second)
	if s := waitState(t, id2, StateQueued, time.Second); s.Title != "B" {
		t.Errorf("meta lost: %+v", s.Meta)
	}

	// Pause mid-way: partial data kept, the queue moves on.
	time.Sleep(300 * time.Millisecond)
	Pause(id1)
	paused := waitState(t, id1, StatePaused, 2*time.Second)
	waitState(t, id2, StateRunning, time.Second)
	if _, err := os.Stat(out1 + ".dvstate"); err != nil {
		t.Errorf("resume state missing after pause: %v", err)
	}
	t.Logf("paused at %d bytes", paused.Bytes)

	// Cancel the second: its partial files are removed.
	Cancel(id2)
	waitState(t, id2, StateCanceled, 2*time.Second)
	if _, err := os.Stat(out2); !os.IsNotExist(err) {
		t.Errorf("partial file kept after cancel")
	}

	// Simulate the app being killed and reopened: reload from jobs.json.
	save()
	mu.Lock()
	jobs, nextID = map[int64]*job{}, 0
	mu.Unlock()
	st, _ := Init(dir, media.FFmpeg{}, 1)
	if len(st) != 2 || st[0].ID != id2 || st[1].State != StatePaused {
		t.Fatalf("reloaded = %+v", st)
	}

	// Resume continues from the partial file.
	served.Store(0)
	Resume(id1)
	waitState(t, id1, StateDone, 10*time.Second)
	got, _ := os.ReadFile(out1)
	if !bytes.Equal(got, data) {
		t.Fatal("resumed file differs")
	}
	if served.Load() >= int64(len(data)) {
		t.Errorf("resume re-downloaded everything (%d bytes)", served.Load())
	}
	MarkSaved(id1, Saved{URI: "content://x/1", Mime: "video/mp4"})
	if s := waitState(t, id1, StateSaved, time.Second); s.Saved == nil || s.Saved.URI != "content://x/1" {
		t.Errorf("saved info = %+v", s.Saved)
	}
	Remove(id2)
	if n := len(Snapshot()); n != 1 {
		t.Errorf("after remove: %d jobs", n)
	}
}

func TestExpiredAndNetworkStates(t *testing.T) {
	reset(t)
	gone := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "expired", http.StatusForbidden)
	}))
	defer gone.Close()
	dir := t.TempDir()
	// 403 with a page URL: re-extractable -> expired.
	id, _ := Start(Request{Kind: "mp4", URL: gone.URL, OutPath: filepath.Join(dir, "x.mp4")}, Meta{PageURL: "https://p/1"}, nil)
	waitState(t, id, StateExpired, 3*time.Second)
	// Without a page URL there is nothing to re-extract -> error.
	id2, _ := Start(Request{Kind: "mp4", URL: gone.URL, OutPath: filepath.Join(dir, "y.mp4")}, Meta{}, nil)
	waitState(t, id2, StateError, 3*time.Second)
	// Unreachable host -> waiting for the network (retried automatically).
	id3, _ := Start(Request{Kind: "mp4", URL: "http://127.0.0.1:1/v.mp4", OutPath: filepath.Join(dir, "z.mp4")}, Meta{}, nil)
	waitState(t, id3, StateWaiting, 3*time.Second)
	Pause(id3)
	waitState(t, id3, StatePaused, time.Second)
}
