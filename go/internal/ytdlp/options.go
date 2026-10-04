// Package ytdlp turns `yt-dlp -J` output into download options. yt-dlp only
// extracts (it runs in Kotlin via youtubedl-android); downloading and muxing
// stay in the Go core.
package ytdlp

import (
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"

	"github.com/downvid/core/internal/scan"
)

// Info is the subset of yt-dlp's info dict that DownVid uses.
type Info struct {
	ID          string            `json:"id"`
	Title       string            `json:"title"`
	Thumbnail   string            `json:"thumbnail"`
	Duration    float64           `json:"duration"`
	Uploader    string            `json:"uploader"`
	Extractor   string            `json:"extractor_key"`
	WebpageURL  string            `json:"webpage_url"`
	Type        string            `json:"_type"` // video | playlist
	Formats     []Format          `json:"formats"`
	Entries     []Info            `json:"entries"`
	IsLive      bool              `json:"is_live"`
	LiveStatus  string            `json:"live_status"`
	DirectURL   string            `json:"url"` // single-format results
	DirectExt   string            `json:"ext"`
	HTTPHeaders map[string]string `json:"http_headers"`

	// Music fields (YouTube Music, SoundCloud, Bandcamp...).
	Track       string      `json:"track"`
	Artist      string      `json:"artist"`
	Artists     []string    `json:"artists"`
	Creator     string      `json:"creator"`
	Album       string      `json:"album"`
	ReleaseYear int         `json:"release_year"`
	Channel     string      `json:"channel"`
	Thumbnails  []Thumbnail `json:"thumbnails"`
}

type Thumbnail struct {
	URL    string `json:"url"`
	Width  int    `json:"width"`
	Height int    `json:"height"`
}

type Format struct {
	ID         string            `json:"format_id"`
	URL        string            `json:"url"`
	Ext        string            `json:"ext"`
	Protocol   string            `json:"protocol"`
	VCodec     string            `json:"vcodec"`
	ACodec     string            `json:"acodec"`
	Height     int               `json:"height"`
	Width      int               `json:"width"`
	FPS        float64           `json:"fps"`
	TBR        float64           `json:"tbr"`
	ABR        float64           `json:"abr"`
	Filesize   int64             `json:"filesize"`
	FilesizeA  int64             `json:"filesize_approx"`
	Headers    map[string]string `json:"http_headers"`
	Cookies    string            `json:"cookies"`
	HasDRM     any               `json:"has_drm"`
	FormatNote string            `json:"format_note"`
	Language   string            `json:"language"`
	Downloader struct {
		ChunkSize int64 `json:"http_chunk_size"`
	} `json:"downloader_options"`
}

func (f Format) isAudioOnly() bool { return f.VCodec == "none" && f.ACodec != "none" }
func (f Format) isVideoOnly() bool { return f.ACodec == "none" && f.VCodec != "none" }
func (f Format) isMuxed() bool     { return f.VCodec != "none" && f.ACodec != "none" }

func (f Format) size(dur float64) int64 {
	switch {
	case f.Filesize > 0:
		return f.Filesize
	case f.FilesizeA > 0:
		return f.FilesizeA
	case f.TBR > 0 && dur > 0:
		return int64(f.TBR * 1000 / 8 * dur)
	}
	return -1
}

// usable filters out what DownVid cannot fetch: DRM, storyboards, segment
// lists (http_dash_segments) and dynamic-range-compressed audio duplicates.
func (f Format) usable() bool {
	if f.URL == "" || f.HasDRM == true || f.Ext == "mhtml" || strings.HasSuffix(f.ID, "-drc") {
		return false
	}
	switch f.Protocol {
	case "https", "http", "m3u8", "m3u8_native":
		return true
	}
	return false
}

func (f Format) isHLS() bool { return strings.HasPrefix(f.Protocol, "m3u8") }

// Parse decodes yt-dlp -J output. Playlists yield their first entry (the
// sheet handles single videos for now).
func Parse(data []byte) (*Info, error) {
	var info Info
	if err := json.Unmarshal(data, &info); err != nil {
		return nil, fmt.Errorf("invalid yt-dlp output: %w", err)
	}
	if info.Type == "playlist" && len(info.Entries) > 0 {
		first := info.Entries[0]
		if first.Title == "" {
			first.Title = info.Title
		}
		return &first, nil
	}
	return &info, nil
}

// codecName shortens yt-dlp codec strings for labels.
func codecName(c string) string {
	switch {
	case strings.HasPrefix(c, "avc"), c == "h264":
		return "H.264"
	case strings.HasPrefix(c, "vp09"), c == "vp9":
		return "VP9"
	case strings.HasPrefix(c, "av01"):
		return "AV1"
	case strings.HasPrefix(c, "hvc"), strings.HasPrefix(c, "hev"), c == "h265":
		return "H.265"
	case c == "" || c == "none":
		return ""
	}
	return strings.ToUpper(strings.SplitN(c, ".", 2)[0])
}

// codecRank orders video codecs for a given height: H.264 plays everywhere
// and is preferred up to 1080p; above that only VP9/AV1 exist, and VP9 has
// wider hardware decoding on Android.
func codecRank(c string, height int) int {
	switch codecName(c) {
	case "H.264":
		return 3
	case "VP9":
		if height > 1080 {
			return 4
		}
		return 2
	case "AV1":
		return 1
	case "H.265":
		return 2
	case "":
		return 2 // unknown (generic sites): usually H.264
	}
	return 0
}

// ErrNoFormats is returned when nothing is downloadable.
var ErrNoFormats = errors.New("no video format available")

// Options builds the quality list: one option per height, choosing the best
// codec/fps and pairing video-only streams with the best compatible audio.
// Kinds: mp4 (single file), hls (playlist, optional separate audio),
// merge (separate/non-MP4 streams joined with ffmpeg -c copy).
func Options(info *Info) ([]scan.Option, error) {
	if info.IsLive || info.LiveStatus == "is_live" {
		return nil, errors.New("live streams are not supported")
	}
	formats := info.Formats
	if len(formats) == 0 && info.DirectURL != "" {
		formats = []Format{{ID: "0", URL: info.DirectURL, Ext: info.DirectExt, Protocol: "https", Headers: info.HTTPHeaders}}
	}

	// Drop watermarked copies (TikTok "download") when clean ones exist.
	clean := 0
	for _, f := range formats {
		if f.usable() && !f.isAudioOnly() && !isWatermarked(f) {
			clean++
		}
	}

	var audio []Format
	var video []Format
	for _, f := range formats {
		if !f.usable() || (clean > 0 && isWatermarked(f)) {
			continue
		}
		if f.isAudioOnly() {
			audio = append(audio, f)
		} else {
			video = append(video, f)
		}
	}

	bestAudio := func(hls bool) *Format {
		var best *Format
		score := func(f Format) float64 {
			s := f.ABR
			if strings.HasPrefix(f.ACodec, "mp4a") {
				s += 1000 // AAC: native in MP4, plays everywhere
			}
			return s
		}
		for i := range audio {
			a := audio[i]
			if a.isHLS() != hls {
				continue
			}
			if best == nil || score(a) > score(*best) {
				best = &audio[i]
			}
		}
		return best
	}

	type cand struct {
		opt   scan.Option
		score float64
	}
	// Options are grouped by height; formats without a declared height
	// (Facebook "hd"/"sd" progressive MP4s) are kept apart by their id.
	byHeight := map[string]cand{}
	for _, f := range video {
		o := scan.Option{
			URL: f.URL, Height: f.Height, FPS: f.FPS, VCodec: codecName(f.VCodec),
			Headers: headersOf(f), ChunkSize: f.Downloader.ChunkSize,
			Source: "ytdlp", Group: info.WebpageURL, DurationSec: info.Duration,
			Bandwidth: int64(f.TBR * 1000),
		}
		size := f.size(info.Duration)
		var a *Format
		switch {
		case f.isHLS():
			o.Kind = "hls"
			if f.isVideoOnly() {
				if a = bestAudio(true); a == nil {
					continue
				}
				o.AudioURL = a.URL
			}
		case f.isVideoOnly():
			if a = bestAudio(false); a == nil {
				continue
			}
			o.Kind, o.AudioURL = "merge", a.URL
		case f.Ext == "mp4" || f.Ext == "m4v" || f.Ext == "mov":
			o.Kind = "mp4"
		default:
			o.Kind = "merge" // e.g. muxed webm: copy into an MP4 container
		}
		if a != nil {
			if as := a.size(info.Duration); size > 0 && as > 0 {
				size += as
			} else {
				size = -1
			}
		}
		o.Size = size
		o.SizeExact = f.Filesize > 0 && (a == nil || a.Filesize > 0)
		o.Label = label(f)

		// Prefer codec rank, then fps, then direct over HLS (parallel Range
		// download, no segment overhead), then bitrate (tbr < 1e6 kbps).
		score := float64(codecRank(f.VCodec, f.Height))*1e10 + f.FPS*1e7
		if !f.isHLS() {
			score += 1e6
		}
		score += f.TBR
		key := fmt.Sprint(f.Height)
		if f.Height == 0 {
			key = "id:" + f.ID
		}
		if c, ok := byHeight[key]; !ok || score > c.score {
			byHeight[key] = cand{o, score}
		}
	}

	opts := make([]scan.Option, 0, len(byHeight))
	for _, c := range byHeight {
		opts = append(opts, c.opt)
	}
	sort.SliceStable(opts, func(i, j int) bool {
		if opts[i].Height != opts[j].Height {
			return opts[i].Height > opts[j].Height
		}
		return opts[i].Label < opts[j].Label // "HD · MP4" before "SD · MP4"
	})
	if len(opts) == 0 {
		return nil, ErrNoFormats
	}
	return opts, nil
}

func label(f Format) string {
	var b strings.Builder
	if f.Height > 0 {
		fmt.Fprintf(&b, "%dp", f.Height)
		if f.FPS > 30 {
			fmt.Fprintf(&b, "%.0f", f.FPS)
		}
	} else if id := strings.ToLower(f.ID); id == "hd" || id == "sd" {
		b.WriteString(strings.ToUpper(id) + " · MP4")
	} else if f.FormatNote != "" {
		b.WriteString(f.FormatNote)
	} else {
		b.WriteString("Video")
	}
	if c := codecName(f.VCodec); c != "" {
		b.WriteString(" · " + c)
	}
	return b.String()
}

// headersOf merges yt-dlp's per-format headers and cookies.
func headersOf(f Format) map[string]string {
	h := map[string]string{}
	for k, v := range f.Headers {
		h[k] = v
	}
	if c := cookieHeader(f.Cookies); c != "" {
		h["Cookie"] = c
	}
	return h
}

// cookieHeader converts yt-dlp's cookie string ("a=1; Domain=..; Path=/;
// b=2; ...") into a Cookie header value ("a=1; b=2").
func cookieHeader(s string) string {
	attrs := map[string]bool{"domain": true, "path": true, "expires": true, "max-age": true,
		"secure": true, "httponly": true, "samesite": true}
	var out []string
	for part := range strings.SplitSeq(s, ";") {
		k, v, ok := strings.Cut(strings.TrimSpace(part), "=")
		if !ok || attrs[strings.ToLower(k)] || k == "" {
			continue
		}
		// yt-dlp quotes values containing '=' (tt_chain_token="abc=="); the
		// quotes are not part of the value (TikTok answers 403 with them).
		if len(v) >= 2 && v[0] == '"' && v[len(v)-1] == '"' {
			v = v[1 : len(v)-1]
		}
		out = append(out, k+"="+v)
	}
	return strings.Join(out, "; ")
}

func isWatermarked(f Format) bool {
	return strings.Contains(strings.ToLower(f.FormatNote), "watermark")
}
