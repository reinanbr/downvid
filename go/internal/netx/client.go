// Package netx holds the shared HTTP client and the parallel range downloader.
package netx

import (
	"bytes"
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/downvid/core/internal/logx"
)

// DefaultUA is used when the caller does not pass the WebView user agent.
const DefaultUA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

// Headers sent with every request of a probe/download. Referer and Cookie
// matter: many CDNs reject media requests without the page's Referer.
type Headers struct {
	UserAgent string `json:"userAgent,omitempty"`
	Referer   string `json:"referer,omitempty"`
	Cookie    string `json:"cookie,omitempty"`
	// Extra headers required by the source (yt-dlp's http_headers); they
	// override the defaults above.
	Extra map[string]string `json:"extra,omitempty"`
}

func (h Headers) apply(req *http.Request) {
	ua := h.UserAgent
	if ua == "" {
		ua = DefaultUA
	}
	req.Header.Set("User-Agent", ua)
	req.Header.Set("Accept", "*/*")
	req.Header.Set("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
	if h.Referer != "" {
		ref, org := browserReferer(h.Referer, req.URL)
		req.Header.Set("Referer", ref)
		if org != "" {
			req.Header.Set("Origin", org)
		}
	}
	if h.Cookie != "" {
		req.Header.Set("Cookie", h.Cookie)
	}
	for k, v := range h.Extra {
		req.Header.Set(k, v)
	}
}

// browserReferer returns the Referer and Origin a browser sends from page
// ref to target. Cross-origin it sends only the page's origin as Referer
// (Chrome's default strict-origin-when-cross-origin policy): some CDNs answer
// 410 to a full page URL next to an Origin header, a combination no browser
// produces.
func browserReferer(ref string, target *url.URL) (referer, org string) {
	o := origin(ref)
	if o == "" || target == nil || o == target.Scheme+"://"+target.Host {
		return ref, o
	}
	return o + "/", o
}

func origin(u string) string {
	i := strings.Index(u, "://")
	if i < 0 {
		return ""
	}
	j := strings.IndexByte(u[i+3:], '/')
	if j < 0 {
		return u
	}
	return u[:i+3+j]
}

// Client is used for pages, playlists, probes and APIs (HTTP/2 when the
// server offers it: many small requests share one connection).
var Client = newClient()

// MediaClient downloads media bytes (file ranges, HLS segments). It speaks
// HTTP/1.1 only, so every concurrent request gets its own TCP connection:
// over HTTP/2 the parallel ranges of a download would be multiplexed on a
// single connection and share its congestion window and any per-connection
// throttling, which defeats splitting the download. Cookies are shared with
// Client (CDNs often set them on the playlist response).
var MediaClient = newMediaClient(Client.Jar)

func dialer() *net.Dialer {
	return &net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}
}

func newClient() *http.Client {
	jar, _ := cookiejar.New(nil)
	tr := &http.Transport{
		Proxy:                 http.ProxyFromEnvironment,
		DialContext:           dialer().DialContext,
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          64,
		MaxIdleConnsPerHost:   16,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   15 * time.Second,
		ResponseHeaderTimeout: 30 * time.Second,
	}
	return &http.Client{Transport: tr, Jar: jar}
}

func newMediaClient(jar http.CookieJar) *http.Client {
	tr := &http.Transport{
		Proxy:       http.ProxyFromEnvironment,
		DialContext: dialer().DialContext,
		// A non-nil empty map disables HTTP/2 (ALPN offers http/1.1 only).
		TLSNextProto:          map[string]func(string, *tls.Conn) http.RoundTripper{},
		MaxIdleConns:          128,
		MaxIdleConnsPerHost:   2 * MaxConnections,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   15 * time.Second,
		ResponseHeaderTimeout: 30 * time.Second,
		// Media is not compressible; never negotiate gzip for segments.
		DisableCompression: true,
		// Fewer, larger reads from the socket.
		ReadBufferSize: 64 << 10,
	}
	return &http.Client{Transport: tr, Jar: jar}
}

// HTTPError is a non-2xx response.
type HTTPError struct {
	Status int
	URL    string
}

func (e *HTTPError) Error() string {
	switch e.Status {
	case 401, 403:
		return fmt.Sprintf("access denied (HTTP %d) — the server requires login/token or blocks external downloads", e.Status)
	case 404, 410:
		return fmt.Sprintf("not found (HTTP %d) — the link may have expired", e.Status)
	case 429:
		return "too many requests (HTTP 429) — try again in a few minutes"
	}
	return fmt.Sprintf("HTTP %d em %s", e.Status, e.URL)
}

// Do performs a request with the given headers; non-2xx responses are
// returned as *HTTPError (body closed).
func Do(ctx context.Context, method, url string, h Headers, extra map[string]string) (*http.Response, error) {
	return doWith(ctx, Client, method, url, h, extra)
}

// DoMedia is Do on MediaClient: for media bytes fetched in parallel.
func DoMedia(ctx context.Context, method, url string, h Headers, extra map[string]string) (*http.Response, error) {
	return doWith(ctx, MediaClient, method, url, h, extra)
}

// Hosts that refuse media requests over HTTP/1.1 (a bot signal to some
// CDNs: browsers speak HTTP/2); their media goes over mediaH2.
var h2Hosts sync.Map

// mediaH2 is a few HTTP/2 clients for those hosts: each keeps its own
// connection, so a split download still spreads over several TCP
// connections instead of multiplexing everything on one.
var (
	mediaH2     = newMediaH2(4)
	mediaH2Next atomic.Uint32
)

func newMediaH2(n int) []*http.Client {
	cs := make([]*http.Client, n)
	for i := range cs {
		tr := &http.Transport{
			Proxy:                 http.ProxyFromEnvironment,
			DialContext:           dialer().DialContext,
			ForceAttemptHTTP2:     true,
			MaxIdleConnsPerHost:   2,
			IdleConnTimeout:       90 * time.Second,
			TLSHandshakeTimeout:   15 * time.Second,
			ResponseHeaderTimeout: 30 * time.Second,
			DisableCompression:    true,
			ReadBufferSize:        64 << 10,
		}
		cs[i] = &http.Client{Transport: tr, Jar: Client.Jar}
	}
	return cs
}

func nextMediaH2() *http.Client {
	return mediaH2[mediaH2Next.Add(1)%uint32(len(mediaH2))]
}

// refusedOverHTTP1 is a status worth one retry over HTTP/2: a client error
// other than a bad range or rate limiting.
func refusedOverHTTP1(status int) bool {
	return status >= 400 && status < 500 && status != 416 && status != 429
}

func doWith(ctx context.Context, c *http.Client, method, rawURL string, h Headers, extra map[string]string) (*http.Response, error) {
	if c != MediaClient {
		return send(ctx, c, method, rawURL, h, extra)
	}
	host := rawURL
	if u, err := url.Parse(rawURL); err == nil {
		host = u.Host
	}
	if _, ok := h2Hosts.Load(host); ok {
		return send(ctx, nextMediaH2(), method, rawURL, h, extra)
	}
	resp, err := send(ctx, MediaClient, method, rawURL, h, extra)
	var he *HTTPError
	if !errors.As(err, &he) || !refusedOverHTTP1(he.Status) {
		return resp, err
	}
	resp, err2 := send(ctx, nextMediaH2(), method, rawURL, h, extra)
	if err2 != nil {
		return nil, err
	}
	if _, loaded := h2Hosts.LoadOrStore(host, struct{}{}); !loaded {
		logx.Infof("netx: %s refuses HTTP/1.1 (HTTP %d); using HTTP/2", host, he.Status)
	}
	return resp, nil
}

func send(ctx context.Context, c *http.Client, method, url string, h Headers, extra map[string]string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, method, url, nil)
	if err != nil {
		return nil, err
	}
	h.apply(req)
	for k, v := range extra {
		req.Header.Set(k, v)
	}
	resp, err := c.Do(req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		resp.Body.Close()
		return nil, &HTTPError{Status: resp.StatusCode, URL: url}
	}
	return resp, nil
}

// idleTimeout cancels a media response that stops delivering data (a
// stalled connection would otherwise block its download forever).
var idleTimeout = 30 * time.Second // var for tests

// ErrStalled is returned when a response delivered nothing for idleTimeout.
// It counts as a network error (retried).
var ErrStalled = errors.New("read timeout: the server stopped sending data")

// stallGuard cancels its context when Touch is not called for idleTimeout.
type stallGuard struct {
	ctx    context.Context
	cancel context.CancelCauseFunc
	t      *time.Timer
}

func newStallGuard(parent context.Context) *stallGuard {
	ctx, cancel := context.WithCancelCause(parent)
	g := &stallGuard{ctx: ctx, cancel: cancel}
	g.t = time.AfterFunc(idleTimeout, func() { cancel(ErrStalled) })
	return g
}

func (g *stallGuard) Touch() { g.t.Reset(idleTimeout) }

func (g *stallGuard) Stop() {
	g.t.Stop()
	g.cancel(nil)
}

// Err maps a read error caused by the guard to ErrStalled.
func (g *stallGuard) Err(err error) error {
	if err != nil && errors.Is(context.Cause(g.ctx), ErrStalled) {
		return ErrStalled
	}
	return err
}

type guardedReader struct {
	r io.Reader
	g *stallGuard
}

func (r guardedReader) Read(p []byte) (int, error) {
	n, err := r.r.Read(p)
	if n > 0 {
		r.g.Touch()
	}
	return n, err
}

// GetMedia downloads a whole media resource (an HLS segment) on
// MediaClient, sizing the buffer from Content-Length.
func GetMedia(ctx context.Context, url string, h Headers, extra map[string]string) ([]byte, error) {
	g := newStallGuard(ctx)
	defer g.Stop()
	resp, err := DoMedia(g.ctx, http.MethodGet, url, h, extra)
	if err != nil {
		return nil, g.Err(err)
	}
	defer resp.Body.Close()
	var buf bytes.Buffer
	if n := resp.ContentLength; n > 0 && n < 256<<20 {
		buf.Grow(int(n) + bytes.MinRead)
	}
	_, err = buf.ReadFrom(guardedReader{resp.Body, g})
	return buf.Bytes(), g.Err(err)
}

// GetBytes downloads a small resource fully (playlists, keys, HTML).
func GetBytes(ctx context.Context, url string, h Headers, limit int64) ([]byte, *http.Response, error) {
	resp, err := Do(ctx, http.MethodGet, url, h, nil)
	if err != nil {
		return nil, nil, err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, limit))
	return b, resp, err
}
