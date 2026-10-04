package netx

import (
	"bytes"
	"context"
	"crypto/rand"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
)

// slowRangeServer serves data with Range support, throttled so a download
// can be interrupted mid-way.
func slowRangeServer(t *testing.T, data []byte, served *atomic.Int64) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "video/mp4")
		http.ServeContent(&throttled{ResponseWriter: w, served: served}, r, "v.mp4", time.Time{}, bytes.NewReader(data))
	}))
}

type throttled struct {
	http.ResponseWriter
	served *atomic.Int64
}

func (t *throttled) Write(p []byte) (int, error) {
	total := 0
	for len(p) > 0 {
		n := min(len(p), 64<<10)
		w, err := t.ResponseWriter.Write(p[:n])
		total += w
		t.served.Add(int64(w))
		if err != nil {
			return total, err
		}
		if f, ok := t.ResponseWriter.(http.Flusher); ok {
			f.Flush()
		}
		p = p[n:]
		time.Sleep(2 * time.Millisecond)
	}
	return total, nil
}

func TestDownloadResume(t *testing.T) {
	data := make([]byte, 12<<20) // > parallelMin: 4 parallel parts
	rand.Read(data)
	var served atomic.Int64
	srv := slowRangeServer(t, data, &served)
	defer srv.Close()
	dst := filepath.Join(t.TempDir(), "out.mp4")

	// 1st attempt: cancelled after ~40%.
	ctx, cancel := context.WithCancel(context.Background())
	var c1 Counter
	go func() {
		for c1.Load() < int64(len(data))*4/10 {
			time.Sleep(5 * time.Millisecond)
		}
		cancel()
	}()
	if _, err := DownloadFileOpts(ctx, srv.URL, Headers{}, dst, &c1, nil, Options{Resume: true}); err == nil {
		t.Fatal("expected the first attempt to be interrupted")
	}
	if _, err := os.Stat(dst + sidecarExt); err != nil {
		t.Fatalf("sidecar not kept after interruption: %v", err)
	}
	firstServed := served.Load()

	// 2nd attempt resumes: it must not fetch everything again.
	served.Store(0)
	var c2 Counter
	n, err := DownloadFileOpts(context.Background(), srv.URL, Headers{}, dst, &c2, nil, Options{Resume: true})
	if err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(dst)
	if n != int64(len(data)) || !bytes.Equal(got, data) {
		t.Fatalf("resumed file differs (n=%d)", n)
	}
	if served.Load() >= int64(len(data))*9/10 {
		t.Errorf("resume re-downloaded %d of %d bytes (first attempt served %d)", served.Load(), len(data), firstServed)
	}
	if c2.Load() != int64(len(data)) {
		t.Errorf("progress after resume = %d, want %d", c2.Load(), len(data))
	}
	if _, err := os.Stat(dst + sidecarExt); !os.IsNotExist(err) {
		t.Errorf("sidecar not removed after success")
	}
	t.Logf("first attempt served %.1f MB, resume served %.1f MB of %.1f MB",
		float64(firstServed)/1e6, float64(served.Load())/1e6, float64(len(data))/1e6)
}

func TestResumeIgnoresMismatchedState(t *testing.T) {
	data := make([]byte, 2<<20)
	rand.Read(data)
	var served atomic.Int64
	srv := slowRangeServer(t, data, &served)
	defer srv.Close()
	dst := filepath.Join(t.TempDir(), "out.mp4")
	// Stale state for a different file size must be discarded.
	os.WriteFile(dst+sidecarExt, []byte(`{"size":999,"parts":[[0,998,500]]}`), 0o644)
	os.WriteFile(dst, make([]byte, 999), 0o644)
	var c Counter
	if _, err := DownloadFileOpts(context.Background(), srv.URL, Headers{}, dst, &c, nil, Options{Resume: true}); err != nil {
		t.Fatal(err)
	}
	if got, _ := os.ReadFile(dst); !bytes.Equal(got, data) {
		t.Fatal("file differs")
	}
}
