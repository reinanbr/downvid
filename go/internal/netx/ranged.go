package netx

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/downvid/core/internal/logx"
)

const (
	parallelMin   = 4 << 20 // files smaller than this are fetched in one stream
	partCount     = 4
	maxRetries    = 4
	copyBufferLen = 256 << 10
)

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
	resp, err := Do(ctx, http.MethodGet, url, h, map[string]string{"Range": "bytes=0-0"})
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

// DownloadFile fetches url into dst. Large files on range-capable servers are
// split into parallel parts; every part retries and resumes from the byte it
// stopped at. Progress is reported through `done` (bytes written) and the
// size, once known, through onSize.
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
}

func DownloadFileOpts(ctx context.Context, url string, h Headers, dst string, done *Counter, onSize func(int64), opt Options) (int64, error) {
	info, err := Probe(ctx, url, h)
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
		return streamWithRetry(ctx, url, h, f, info, done)
	}

	n := partCount
	if info.Size < parallelMin {
		n = 1
	}
	parts, resumed := loadSidecar(dst, info.Size, n, opt.Resume)
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

	// Persist part positions while downloading so a killed process resumes.
	stop := make(chan struct{})
	saved := make(chan struct{})
	go func() {
		defer close(saved)
		t := time.NewTicker(time.Second)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				saveSidecar(dst, info.Size, parts)
			}
		}
	}()

	var wg sync.WaitGroup
	errs := make([]error, len(parts))
	for i, p := range parts {
		wg.Add(1)
		go func() {
			defer wg.Done()
			errs[i] = fetchChunked(ctx, url, h, f, p.pos.Load(), p.end, done, opt.ChunkSize, &p.pos)
		}()
	}
	wg.Wait()
	close(stop)
	<-saved
	if err := errors.Join(errs...); err != nil {
		saveSidecar(dst, info.Size, parts) // keep progress for the next attempt
		return 0, err
	}
	os.Remove(dst + sidecarExt)
	return info.Size, nil
}

// ---- resume state: <dst>.dvstate = {"size":N,"parts":[[start,end,pos],...]}

const sidecarExt = ".dvstate"

type part struct {
	start, end int64
	pos        atomic.Int64 // next byte to fetch (end+1 when complete)
}

func newParts(size int64, n int) []*part {
	parts := make([]*part, n)
	l := size / int64(n)
	for i := range n {
		p := &part{start: int64(i) * l, end: int64(i)*l + l - 1}
		if i == n-1 {
			p.end = size - 1
		}
		p.pos.Store(p.start)
		parts[i] = p
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
				p := &part{start: v[0], end: v[1]}
				if v[2] < v[0] || v[2] > v[1]+1 || v[1] >= size {
					ok = false
					break
				}
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

func saveSidecar(dst string, size int64, parts []*part) {
	st := struct {
		Size  int64      `json:"size"`
		Parts [][3]int64 `json:"parts"`
	}{Size: size}
	for _, p := range parts {
		st.Parts = append(st.Parts, [3]int64{p.start, p.end, p.pos.Load()})
	}
	b, _ := json.Marshal(st)
	tmp := dst + sidecarExt + ".tmp"
	if os.WriteFile(tmp, b, 0o644) == nil {
		os.Rename(tmp, dst+sidecarExt)
	}
}

// fetchChunked downloads [start, end] as consecutive requests of at most
// chunk bytes (chunk <= 0: a single request). track, if set, follows the
// next byte to fetch.
func fetchChunked(ctx context.Context, url string, h Headers, f *os.File, start, end int64, done *Counter, chunk int64, track *atomic.Int64) error {
	if start > end {
		return nil
	}
	if chunk <= 0 {
		return fetchRange(ctx, url, h, f, start, end, done, track)
	}
	for pos := start; pos <= end; pos += chunk {
		if err := fetchRange(ctx, url, h, f, pos, min(pos+chunk-1, end), done, track); err != nil {
			return err
		}
	}
	return nil
}

// fetchRange downloads [start, end] into f, resuming after transient errors.
func fetchRange(ctx context.Context, url string, h Headers, f *os.File, start, end int64, done *Counter, track *atomic.Int64) error {
	pos := start
	var lastErr error
	for attempt := 0; attempt <= maxRetries && pos <= end; attempt++ {
		if attempt > 0 {
			logx.Warnf("range %d-%d: retry %d from %d: %v", start, end, attempt, pos, lastErr)
			if err := sleepCtx(ctx, backoff(attempt)); err != nil {
				return err
			}
		}
		resp, err := Do(ctx, http.MethodGet, url, h, map[string]string{
			"Range": fmt.Sprintf("bytes=%d-%d", pos, end),
		})
		if err != nil {
			if !retryable(err) {
				return err
			}
			lastErr = err
			continue
		}
		if resp.StatusCode != http.StatusPartialContent {
			resp.Body.Close()
			return fmt.Errorf("server ignored Range (HTTP %d)", resp.StatusCode)
		}
		w := &offsetWriter{f: f, off: pos, done: done, track: track}
		_, err = io.CopyBuffer(w, resp.Body, make([]byte, copyBufferLen))
		resp.Body.Close()
		pos = w.off
		if err == nil && pos > end {
			return nil
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if err == nil {
			err = io.ErrUnexpectedEOF
		}
		lastErr = err
	}
	if pos > end {
		return nil
	}
	return fmt.Errorf("failed after %d attempts: %w", maxRetries, lastErr)
}

// streamWithRetry fetches a resource without range support, restarting from
// the beginning after transient errors.
func streamWithRetry(ctx context.Context, url string, h Headers, f *os.File, info RemoteInfo, done *Counter) (int64, error) {
	var lastErr error
	for attempt := 0; attempt <= maxRetries; attempt++ {
		if attempt > 0 {
			logx.Warnf("stream retry %d: %v", attempt, lastErr)
			if err := sleepCtx(ctx, backoff(attempt)); err != nil {
				return 0, err
			}
			done.Store(0)
			if _, err := f.Seek(0, io.SeekStart); err != nil {
				return 0, err
			}
			if err := f.Truncate(0); err != nil {
				return 0, err
			}
		}
		resp, err := Do(ctx, http.MethodGet, url, h, nil)
		if err != nil {
			if !retryable(err) {
				return 0, err
			}
			lastErr = err
			continue
		}
		n, err := io.CopyBuffer(&countWriter{w: f, done: done}, resp.Body, make([]byte, copyBufferLen))
		resp.Body.Close()
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

type offsetWriter struct {
	f     *os.File
	off   int64
	done  *Counter
	track *atomic.Int64
}

func (w *offsetWriter) Write(p []byte) (int, error) {
	n, err := w.f.WriteAt(p, w.off)
	w.off += int64(n)
	w.done.Add(int64(n))
	if w.track != nil {
		w.track.Store(w.off)
	}
	return n, err
}

type countWriter struct {
	w    io.Writer
	done *Counter
}

func (w *countWriter) Write(p []byte) (int, error) {
	n, err := w.w.Write(p)
	w.done.Add(int64(n))
	return n, err
}

func retryable(err error) bool {
	var he *HTTPError
	if errors.As(err, &he) {
		return he.Status == 408 || he.Status == 429 || he.Status >= 500
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
