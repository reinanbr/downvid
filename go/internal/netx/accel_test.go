package netx

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// rateWriter limits one response to `rate` bytes per second, like a server
// or route that caps each connection.
type rateWriter struct {
	http.ResponseWriter
	rate int
}

func (w *rateWriter) Write(p []byte) (int, error) {
	const step = 16 << 10
	total := 0
	for len(p) > 0 {
		n := min(len(p), step)
		m, err := w.ResponseWriter.Write(p[:n])
		total += m
		if err != nil {
			return total, err
		}
		if f, ok := w.ResponseWriter.(http.Flusher); ok {
			f.Flush()
		}
		p = p[n:]
		time.Sleep(time.Duration(float64(n) / float64(w.rate) * float64(time.Second)))
	}
	return total, nil
}

func serveRanges(data []byte, rate func(r *http.Request) int) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "video/mp4")
		var rw http.ResponseWriter = w
		if n := rate(r); n > 0 {
			rw = &rateWriter{ResponseWriter: w, rate: n}
		}
		http.ServeContent(rw, r, "v.mp4", time.Time{}, bytes.NewReader(data))
	}
}

func randomData(t *testing.T, n int) []byte {
	t.Helper()
	b := make([]byte, n)
	rand.Read(b)
	return b
}

func download(t *testing.T, url string, conns int) ([]byte, time.Duration) {
	t.Helper()
	dst := filepath.Join(t.TempDir(), "out.mp4")
	var c Counter
	t0 := time.Now()
	if _, err := DownloadFileOpts(context.Background(), url, Headers{}, dst, &c, nil, Options{Connections: conns}); err != nil {
		t.Fatal(err)
	}
	el := time.Since(t0)
	got, err := os.ReadFile(dst)
	if err != nil {
		t.Fatal(err)
	}
	return got, el
}

// Each connection is capped, so more connections must finish faster.
func TestConnectionsScaleThroughput(t *testing.T) {
	data := randomData(t, 6<<20)
	var conns atomic.Int32
	srv := httptest.NewUnstartedServer(serveRanges(data, func(*http.Request) int { return 3 << 20 }))
	srv.Config.ConnState = func(_ net.Conn, s http.ConnState) {
		if s == http.StateNew {
			conns.Add(1)
		}
	}
	srv.Start()
	defer srv.Close()

	got1, t1 := download(t, srv.URL, 1)
	conns.Store(0)
	got8, t8 := download(t, srv.URL, 8)
	if !bytes.Equal(got1, data) || !bytes.Equal(got8, data) {
		t.Fatal("downloaded data differs")
	}
	t.Logf("1 connection: %s; 8 connections: %s (%d TCP connections)", t1, t8, conns.Load())
	if t8*3 > t1 {
		t.Errorf("8 connections took %s, not much faster than 1 (%s)", t8, t1)
	}
	if conns.Load() < 4 {
		t.Errorf("only %d TCP connections opened", conns.Load())
	}
}

// Against an HTTP/2 server, ranges must still use separate TCP connections
// (HTTP/2 would multiplex them on one).
func TestRangesUseSeparateConnectionsOnHTTP2Servers(t *testing.T) {
	data := randomData(t, 4<<20)
	var conns atomic.Int32
	var h2 atomic.Int32
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.ProtoMajor == 2 {
			h2.Add(1)
		}
		serveRanges(data, func(*http.Request) int { return 8 << 20 })(w, r)
	}))
	srv.EnableHTTP2 = true
	srv.Config.ConnState = func(_ net.Conn, s http.ConnState) {
		if s == http.StateNew {
			conns.Add(1)
		}
	}
	srv.StartTLS()
	defer srv.Close()

	tr := MediaClient.Transport.(*http.Transport)
	old := tr.TLSClientConfig
	tr.TLSClientConfig = &tls.Config{InsecureSkipVerify: true}
	defer func() { tr.TLSClientConfig = old; tr.CloseIdleConnections() }()

	got, _ := download(t, srv.URL, 8)
	if !bytes.Equal(got, data) {
		t.Fatal("downloaded data differs")
	}
	if h2.Load() > 0 {
		t.Errorf("%d requests used HTTP/2", h2.Load())
	}
	if conns.Load() < 4 {
		t.Errorf("only %d TCP connections for 8 parallel ranges", conns.Load())
	}
}

// The range that starts the file is served very slowly on its first
// request: the other connections take over its tail, and the slow
// connection is reopened, so the download does not wait for it.
func TestSlowRangeIsTakenOverAndReopened(t *testing.T) {
	old := slowAfter
	slowAfter = time.Second
	defer func() { slowAfter = old }()

	data := randomData(t, 8<<20)
	var slowServed atomic.Int32
	srv := httptest.NewServer(serveRanges(data, func(r *http.Request) int {
		if strings.HasPrefix(r.Header.Get("Range"), "bytes=0-") && r.Header.Get("Range") != "bytes=0-0" && slowServed.Add(1) == 1 {
			return 64 << 10 // 64 KB/s: the first range alone would take ~30 s
		}
		return 16 << 20
	}))
	defer srv.Close()

	got, el := download(t, srv.URL, 4)
	if !bytes.Equal(got, data) {
		t.Fatal("downloaded data differs")
	}
	t.Logf("finished in %s", el)
	if el > 8*time.Second {
		t.Errorf("slow range held the download for %s", el)
	}
}

// A server that allows only two connections per client answers 429 to the
// others: the download must finish with the connections it accepts.
func TestConnectionLimitReducesConnections(t *testing.T) {
	data := randomData(t, 6<<20)
	var inflight atomic.Int32
	var refused atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if inflight.Add(1) > 2 && r.Header.Get("Range") != "bytes=0-0" {
			inflight.Add(-1)
			refused.Add(1)
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
		defer inflight.Add(-1)
		serveRanges(data, func(*http.Request) int { return 8 << 20 })(w, r)
	}))
	defer srv.Close()

	got, el := download(t, srv.URL, 8)
	if !bytes.Equal(got, data) {
		t.Fatal("downloaded data differs")
	}
	t.Logf("finished in %s; %d requests refused", el, refused.Load())
	if refused.Load() == 0 {
		t.Error("the server never refused a connection (test did not exercise the limit)")
	}
}

// Without Range support the file is streamed (buffered writes) and progress
// counts every byte.
func TestStreamWithoutRanges(t *testing.T) {
	data := randomData(t, 3<<20)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "video/mp4")
		io.Copy(w, bytes.NewReader(data)) // no Accept-Ranges, ignores Range
	}))
	defer srv.Close()
	dst := filepath.Join(t.TempDir(), "out.mp4")
	var c Counter
	n, err := DownloadFileOpts(context.Background(), srv.URL, Headers{}, dst, &c, nil, Options{})
	if err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(dst)
	if n != int64(len(data)) || !bytes.Equal(got, data) || c.Load() != int64(len(data)) {
		t.Fatalf("n=%d progress=%d, file equal=%v", n, c.Load(), bytes.Equal(got, data))
	}
}

// A connection that stops sending data is dropped after idleTimeout and the
// range is fetched again, instead of blocking the download forever.
func TestStalledConnectionIsRetried(t *testing.T) {
	old := idleTimeout
	idleTimeout = 300 * time.Millisecond
	defer func() { idleTimeout = old }()

	data := randomData(t, 3<<20)
	var stalled atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Range") != "bytes=0-0" && stalled.Add(1) == 1 {
			w.Header().Set("Content-Range", "bytes 0-"+strconv.Itoa(len(data)-1)+"/"+strconv.Itoa(len(data)))
			w.WriteHeader(http.StatusPartialContent)
			w.Write(data[:64<<10])
			w.(http.Flusher).Flush()
			<-r.Context().Done() // stall until the client gives up
			return
		}
		serveRanges(data, func(*http.Request) int { return 0 })(w, r)
	}))
	defer srv.Close()

	got, el := download(t, srv.URL, 1)
	if !bytes.Equal(got, data) {
		t.Fatal("downloaded data differs")
	}
	if el > 5*time.Second {
		t.Errorf("took %s", el)
	}
}

// A finished input of a merge/audio job (KeepDone) is not fetched again when
// the job resumes (e.g. the other input was interrupted).
func TestFinishedDownloadIsNotFetchedAgain(t *testing.T) {
	data := randomData(t, 3<<20)
	var ranged atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Range") != "" && r.Header.Get("Range") != "bytes=0-0" {
			ranged.Add(1)
		}
		serveRanges(data, func(*http.Request) int { return 0 })(w, r)
	}))
	defer srv.Close()
	dst := filepath.Join(t.TempDir(), "in0")
	opt := Options{Connections: 4, Resume: true, KeepDone: true}
	var c Counter
	if _, err := DownloadFileOpts(context.Background(), srv.URL, Headers{}, dst, &c, nil, opt); err != nil {
		t.Fatal(err)
	}
	first := ranged.Load()
	if first == 0 {
		t.Fatal("expected ranged requests")
	}
	var c2 Counter
	n, err := DownloadFileOpts(context.Background(), srv.URL, Headers{}, dst, &c2, nil, opt)
	if err != nil {
		t.Fatal(err)
	}
	if n != int64(len(data)) || c2.Load() != int64(len(data)) {
		t.Fatalf("second run: n=%d counted=%d, want %d", n, c2.Load(), len(data))
	}
	if again := ranged.Load() - first; again != 0 {
		t.Fatalf("finished file fetched again (%d range requests)", again)
	}
	got, _ := os.ReadFile(dst)
	if !bytes.Equal(got, data) {
		t.Fatal("content differs")
	}
}

// A CDN that refuses HTTP/1.1 (410, like phncdn): the media requests move to
// HTTP/2 for that host, still over several connections.
func TestHostRefusingHTTP1MovesToHTTP2(t *testing.T) {
	data := randomData(t, 4<<20)
	var h1 atomic.Int32
	var mu sync.Mutex
	h2Conns := map[string]bool{}
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.ProtoMajor == 1 {
			h1.Add(1)
			w.WriteHeader(http.StatusGone)
			return
		}
		mu.Lock()
		h2Conns[r.RemoteAddr] = true
		mu.Unlock()
		serveRanges(data, func(*http.Request) int { return 8 << 20 })(w, r)
	}))
	srv.EnableHTTP2 = true
	srv.StartTLS()
	defer srv.Close()
	host := strings.TrimPrefix(srv.URL, "https://")
	defer h2Hosts.Delete(host)

	trs := []*http.Transport{MediaClient.Transport.(*http.Transport)}
	for _, c := range mediaH2 {
		trs = append(trs, c.Transport.(*http.Transport))
	}
	for _, tr := range trs {
		old := tr.TLSClientConfig
		tr.TLSClientConfig = &tls.Config{InsecureSkipVerify: true}
		defer func() { tr.TLSClientConfig = old; tr.CloseIdleConnections() }()
	}

	got, _ := download(t, srv.URL, 8)
	if !bytes.Equal(got, data) {
		t.Fatal("downloaded data differs")
	}
	if _, ok := h2Hosts.Load(host); !ok {
		t.Error("host not remembered as HTTP/2-only")
	}
	if h1.Load() > 8 {
		t.Errorf("%d HTTP/1.1 attempts: the host should be remembered after the first", h1.Load())
	}
	if len(h2Conns) < 2 {
		t.Errorf("only %d HTTP/2 connections for 8 parallel ranges", len(h2Conns))
	}
}

func TestBrowserRefererCrossOrigin(t *testing.T) {
	page := "https://www.site.com/view?id=1#t=3"
	cdn, _ := url.Parse("https://cdn.site-media.com/v/master.m3u8")
	same, _ := url.Parse("https://www.site.com/api/x")
	if r, o := browserReferer(page, cdn); r != "https://www.site.com/" || o != "https://www.site.com" {
		t.Errorf("cross-origin: %q %q", r, o)
	}
	if r, o := browserReferer(page, same); r != page || o != "https://www.site.com" {
		t.Errorf("same-origin: %q %q", r, o)
	}
}
