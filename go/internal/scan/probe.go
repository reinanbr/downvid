package scan

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"path"
	"strings"
	"sync"
	"time"

	"github.com/downvid/core/internal/hls"
	"github.com/downvid/core/internal/logx"
	"github.com/downvid/core/internal/netx"
)

// Request is the DV_Probe input.
type Request struct {
	PageURL    string       `json:"pageUrl"`
	Candidates []Candidate  `json:"candidates"` // extra URLs (WebView sniffer)
	Headers    netx.Headers `json:"headers"`
	SkipPage   bool         `json:"skipPage"` // only resolve Candidates
}

// Option is one downloadable choice shown in the quality list.
type Option struct {
	ID          string  `json:"id"`
	Kind        string  `json:"kind"` // mp4 | hls
	Label       string  `json:"label"`
	URL         string  `json:"url"`
	AudioURL    string  `json:"audioUrl,omitempty"`
	Height      int     `json:"height,omitempty"`
	Bandwidth   int64   `json:"bandwidth,omitempty"`
	Size        int64   `json:"size"` // bytes; -1 unknown
	SizeExact   bool    `json:"sizeExact"`
	DurationSec float64 `json:"durationSec,omitempty"`
	Source      string  `json:"source"`
	Group       string  `json:"group"` // the candidate URL it came from
	Referer     string  `json:"referer,omitempty"`
	Cookie      string  `json:"cookie,omitempty"`
	Small       bool    `json:"small,omitempty"` // < 1 MB: likely a preview/fragment

	// Set for yt-dlp options.
	Headers   map[string]string `json:"headers,omitempty"`   // extra request headers
	ChunkSize int64             `json:"chunkSize,omitempty"` // max bytes per Range request
	VCodec    string            `json:"vcodec,omitempty"`
	FPS       float64           `json:"fps,omitempty"`

	// Audio-only options (Kind "audio").
	AudioFormat  string `json:"audioFormat,omitempty"`  // m4a | mp3
	AudioQuality string `json:"audioQuality,omitempty"` // copy | v0 | 320 | aac256
	SourceHLS    bool   `json:"sourceHls,omitempty"`    // source is an m3u8 (ffmpeg reads it)

	// Carousel posts: one option per item (1-based), shown with checkboxes.
	Item      int    `json:"item,omitempty"`
	ItemCount int    `json:"itemCount,omitempty"`
	ItemThumb string `json:"itemThumb,omitempty"`
}

type Skipped struct {
	URL    string `json:"url"`
	Reason string `json:"reason"`
}

type Result struct {
	PageURL   string    `json:"pageUrl"`
	Title     string    `json:"title,omitempty"`
	Thumbnail string    `json:"thumbnail,omitempty"`
	Options   []Option  `json:"options"`
	Skipped   []Skipped `json:"skipped,omitempty"`
}

const (
	probeTimeout    = 25 * time.Second
	probeWorkers    = 6
	maxIframes      = 6
	maxCandidates   = 40
	maxDocumentSize = 8 << 20
	minVideoSize    = 1 << 20
)

// Probe scans the page (unless SkipPage) and resolves every candidate.
func Probe(ctx context.Context, req Request) (*Result, error) {
	ctx, cancel := context.WithTimeout(ctx, probeTimeout)
	defer cancel()

	res := &Result{PageURL: req.PageURL}
	h := req.Headers
	if h.Referer == "" {
		h.Referer = req.PageURL
	}
	cands := append([]Candidate(nil), req.Candidates...)

	if !req.SkipPage && req.PageURL != "" {
		pageCands, title, thumb, err := scanPage(ctx, req.PageURL, req.Headers)
		if err != nil && len(cands) == 0 {
			return nil, err
		}
		if err != nil {
			logx.Warnf("probe: page scan failed: %v", err)
		}
		res.Title, res.Thumbnail = title, thumb
		cands = append(pageCands, cands...)
	}

	cands = dedupe(cands)
	if len(cands) > maxCandidates {
		logx.Warnf("probe: %d candidates, keeping %d", len(cands), maxCandidates)
		cands = cands[:maxCandidates]
	}
	logx.Infof("probe: resolving %d candidates", len(cands))
	for i, c := range cands {
		logx.Debugf("probe: candidate[%d] %s (%s %s)", i, c.URL, c.Source, c.Hint)
	}

	type out struct {
		opts []Option
		skip *Skipped
	}
	results := make([]out, len(cands))
	sem := make(chan struct{}, probeWorkers)
	var wg sync.WaitGroup
	for i, c := range cands {
		wg.Add(1)
		go func() {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			ch := h
			if c.Referer != "" {
				ch.Referer = c.Referer
			}
			if c.Cookie != "" {
				ch.Cookie = c.Cookie
			}
			opts, err := resolve(ctx, c, ch)
			if err != nil {
				logx.Infof("probe: skip %s: %v", c.URL, err)
				results[i].skip = &Skipped{URL: c.URL, Reason: err.Error()}
				return
			}
			for j := range opts {
				opts[j].Referer = ch.Referer
				opts[j].Cookie = ch.Cookie
			}
			results[i].opts = opts
		}()
	}
	wg.Wait()

	// A media playlist that is also a variant of a master already listed
	// would show twice; keep the master's entry (it has resolution info).
	seen := map[string]bool{}
	for _, r := range results {
		for _, o := range r.opts {
			if o.Kind == "hls" && o.Group != o.URL {
				seen[o.URL] = true
			}
		}
	}
	for _, r := range results {
		if r.skip != nil {
			res.Skipped = append(res.Skipped, *r.skip)
		}
		for _, o := range r.opts {
			if o.Kind == "hls" && o.Group == o.URL && seen[o.URL] {
				continue
			}
			res.Options = append(res.Options, o)
		}
	}
	for i := range res.Options {
		res.Options[i].ID = fmt.Sprintf("o%d", i)
	}
	logx.Infof("probe: %d options, %d skipped", len(res.Options), len(res.Skipped))
	return res, nil
}

func dedupe(cs []Candidate) []Candidate {
	seen := map[string]bool{}
	out := cs[:0]
	for _, c := range cs {
		if seen[c.URL] {
			continue
		}
		seen[c.URL] = true
		out = append(out, c)
	}
	return out
}

// scanPage fetches the page; if the URL itself is media, it is returned as
// the only candidate. Iframes are scanned one level deep.
func scanPage(ctx context.Context, pageURL string, h netx.Headers) ([]Candidate, string, string, error) {
	doc, final, ctype, err := fetchDoc(ctx, pageURL, h)
	if err != nil {
		return nil, "", "", err
	}
	if isMediaType(ctype) || hasMediaExt(final) {
		return []Candidate{{URL: final, Source: "direct"}}, "", "", nil
	}
	base, _ := url.Parse(final)
	info := ExtractPage(doc, base)
	logx.Infof("probe: page %q: %d candidates, %d iframes", info.Title, len(info.Candidates), len(info.Iframes))

	cands := info.Candidates
	var mu sync.Mutex
	var wg sync.WaitGroup
	for i, f := range info.Iframes {
		if i >= maxIframes {
			break
		}
		if skipIframe(f) {
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			ih := h
			ih.Referer = final
			idoc, ifinal, ictype, err := fetchDoc(ctx, f, ih)
			if err != nil {
				logx.Infof("probe: iframe %s: %v", f, err)
				return
			}
			mu.Lock()
			defer mu.Unlock()
			if isMediaType(ictype) {
				cands = append(cands, Candidate{URL: ifinal, Source: "iframe"})
				return
			}
			ib, _ := url.Parse(ifinal)
			ii := ExtractPage(idoc, ib)
			logx.Infof("probe: iframe %s: %d candidates", ifinal, len(ii.Candidates))
			for _, c := range ii.Candidates {
				c.Source = "iframe"
				// Media inside an iframe usually checks the iframe as referer.
				c.Referer = ifinal
				cands = append(cands, c)
			}
		}()
	}
	wg.Wait()
	return cands, info.Title, info.Thumbnail, nil
}

// Ad/analytics/social iframes never hold the page's video.
func skipIframe(u string) bool {
	l := strings.ToLower(u)
	for _, s := range []string{"doubleclick", "googlesyndication", "googletagmanager", "facebook.com/plugins", "platform.twitter", "recaptcha", "disqus", "/ads", "adservice"} {
		if strings.Contains(l, s) {
			return true
		}
	}
	return false
}

func fetchDoc(ctx context.Context, u string, h netx.Headers) (doc, final, ctype string, err error) {
	resp, err := netx.Do(ctx, "GET", u, h, map[string]string{
		"Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
	})
	if err != nil {
		return "", "", "", err
	}
	defer resp.Body.Close()
	final = resp.Request.URL.String()
	ctype = strings.ToLower(resp.Header.Get("Content-Type"))
	if isMediaType(ctype) {
		return "", final, ctype, nil
	}
	b, err := readLimited(resp.Body, maxDocumentSize)
	return string(b), final, ctype, err
}

func isMediaType(ct string) bool {
	return strings.HasPrefix(ct, "video/") || strings.Contains(ct, "mpegurl")
}

// resolve turns a candidate into options: HLS playlists expand into their
// variants, direct files are checked for type and size.
func resolve(ctx context.Context, c Candidate, h netx.Headers) ([]Option, error) {
	p, _ := url.Parse(c.URL)
	ext := strings.ToLower(path.Ext(p.Path))
	if ext == ".m3u8" || strings.Contains(strings.ToLower(c.URL), "m3u8") {
		return resolveHLS(ctx, c, h)
	}

	info, err := netx.Probe(ctx, c.URL, h)
	if err != nil {
		return nil, err
	}
	switch {
	case strings.Contains(info.ContentType, "mpegurl"):
		return resolveHLS(ctx, c, h)
	case strings.HasPrefix(info.ContentType, "text/html"):
		return nil, errors.New("it is a web page, not a video")
	case mp4Types[info.ContentType]:
	case strings.HasPrefix(info.ContentType, "video/"), strings.HasPrefix(info.ContentType, "audio/"),
		incompatibleExt[ext]:
		return nil, fmt.Errorf("format %s cannot be saved as MP4 without re-encoding", firstNonEmpty(info.ContentType, ext))
	case genericTypes[info.ContentType] && (ext == ".mp4" || ext == ".m4v" || ext == ".mov"):
	default:
		return nil, fmt.Errorf("not an MP4 video (%s)", firstNonEmpty(info.ContentType, "unknown type"))
	}
	if info.Size >= 0 && info.Size < 32<<10 {
		return nil, fmt.Errorf("file too small (%d bytes)", info.Size)
	}
	label := "MP4"
	if ext == ".mov" {
		label = "MOV→MP4"
	}
	if name := path.Base(p.Path); name != "" && name != "/" {
		label += " · " + shorten(name, 40)
	}
	return []Option{{
		Kind: "mp4", Label: label, URL: info.FinalURL, Size: info.Size,
		SizeExact: info.Size >= 0, Source: c.Source, Group: c.URL,
		// Previews of related videos and HLS fragments served as .mp4 are
		// tiny; the app hides them when the page has a real video.
		Small: info.Size >= 0 && info.Size < minVideoSize,
	}}, nil
}

func resolveHLS(ctx context.Context, c Candidate, h netx.Headers) ([]Option, error) {
	b, resp, err := netx.GetBytes(ctx, c.URL, h, 16<<20)
	if err != nil {
		return nil, err
	}
	pl, err := hls.Parse(b, resp.Request.URL.String())
	if err != nil {
		return nil, err
	}
	if !pl.Master {
		if pl.IsLive() {
			return nil, errors.New("live stream (not supported)")
		}
		if len(pl.Segments) == 0 {
			return nil, errors.New("empty playlist")
		}
		if err := checkKeys(pl); err != nil {
			return nil, err
		}
		return []Option{{
			Kind: "hls", Label: "HLS", URL: c.URL, Size: -1,
			DurationSec: pl.TotalDuration, Source: c.Source, Group: c.URL,
		}}, nil
	}

	vs := append([]hls.Variant(nil), pl.Variants...)
	hls.SortVariants(vs)
	// Duration/encryption come from one media playlist (all variants share them).
	var dur float64
	if best := hls.BestVariant(vs); best != nil {
		if mb, mresp, err := netx.GetBytes(ctx, best.URL, h, 16<<20); err == nil {
			if mpl, err := hls.Parse(mb, mresp.Request.URL.String()); err == nil {
				if mpl.IsLive() {
					return nil, errors.New("live stream (not supported)")
				}
				if err := checkKeys(mpl); err != nil {
					return nil, err
				}
				dur = mpl.TotalDuration
			}
		}
	}
	var opts []Option
	seen := map[string]bool{}
	for _, v := range vs {
		if v.IsAudioOnly() {
			continue // audio-only extraction comes later (M4A/MP3)
		}
		// One option per resolution: SortVariants puts the preferred
		// (H.264, highest bitrate) variant of each height first.
		key := fmt.Sprintf("%d", v.Height)
		if v.Height == 0 {
			key = fmt.Sprintf("bw%d", v.Bandwidth)
		}
		if seen[key] {
			continue
		}
		seen[key] = true
		size := int64(-1)
		if dur > 0 && v.Bandwidth > 0 {
			size = hls.EstimateSize(v.Bandwidth, dur)
		}
		opts = append(opts, Option{
			Kind: "hls", Label: "HLS " + v.Label(), URL: v.URL, AudioURL: v.AudioURL,
			Height: v.Height, Bandwidth: v.Bandwidth, Size: size, DurationSec: dur,
			Source: c.Source, Group: c.URL,
		})
	}
	if len(opts) == 0 {
		return nil, errors.New("master playlist without video variants")
	}
	return opts, nil
}

func checkKeys(pl *hls.Playlist) error {
	for _, s := range pl.Segments {
		if s.Key != nil && s.Key.Method != "AES-128" {
			return fmt.Errorf("DRM-protected (%s)", s.Key.Method)
		}
	}
	return nil
}

func shorten(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n-1] + "…"
}
