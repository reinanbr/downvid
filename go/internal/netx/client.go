// Package netx holds the shared HTTP client and the parallel range downloader.
package netx

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/cookiejar"
	"strings"
	"time"
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
		req.Header.Set("Referer", h.Referer)
		if o := origin(h.Referer); o != "" {
			req.Header.Set("Origin", o)
		}
	}
	if h.Cookie != "" {
		req.Header.Set("Cookie", h.Cookie)
	}
	for k, v := range h.Extra {
		req.Header.Set(k, v)
	}
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

var Client = newClient()

func newClient() *http.Client {
	jar, _ := cookiejar.New(nil)
	tr := &http.Transport{
		Proxy: http.ProxyFromEnvironment,
		DialContext: (&net.Dialer{
			Timeout:   15 * time.Second,
			KeepAlive: 30 * time.Second,
		}).DialContext,
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          64,
		MaxIdleConnsPerHost:   16,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   15 * time.Second,
		ResponseHeaderTimeout: 30 * time.Second,
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
	req, err := http.NewRequestWithContext(ctx, method, url, nil)
	if err != nil {
		return nil, err
	}
	h.apply(req)
	for k, v := range extra {
		req.Header.Set(k, v)
	}
	resp, err := Client.Do(req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		resp.Body.Close()
		return nil, &HTTPError{Status: resp.StatusCode, URL: url}
	}
	return resp, nil
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
