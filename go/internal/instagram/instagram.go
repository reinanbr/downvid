// Package instagram turns Instagram's logged-out post data (the
// "PolarisLoggedOutDesktopWWWPostRootContentQuery" GraphQL response, the
// same request instagram.com makes for visitors) into download options.
//
// Only public posts are available this way; the query itself runs inside
// the app's WebView (InstagramQuery.kt), and this package only builds its
// parameters and parses the answer.
package instagram

import (
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/downvid/core/internal/music"
	"github.com/downvid/core/internal/scan"
)

const (
	AppID = "936619743392459"
	// Fallback only: the WebView reads the current id from the post page
	// (Instagram rotates it; 27130156389949648 was the previous one).
	DocID        = "28256812867323632"
	FriendlyName = "PolarisLoggedOutDesktopWWWPostRootContentQuery"
)

var (
	reShortcode = regexp.MustCompile(`instagram\.com/(?:[\w.]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]{6,})`)
	reStory     = regexp.MustCompile(`instagram\.com/stories/([\w.]+)/(\d+)`)
)

// ErrNotPost is returned for profile/story links (not handled here).
var ErrNotPost = errors.New("not an Instagram post/reel link")

// ErrUnavailable means Instagram did not serve the post to a logged-out
// visitor: private account, restricted (age/sensitive) or removed.
var ErrUnavailable = errors.New("this content is only visible to people signed in to Instagram")

// ErrLoginRequired: the logged-in session is missing or expired.
var ErrLoginRequired = errors.New("the Instagram session expired — sign in again")

// ErrNotFound: not visible even logged in (private account you don't follow,
// removed post, expired story).
var ErrNotFound = errors.New("content not found: a private profile you don't follow, a removed post or an expired story")

// Code classifies errors for the app ("unavailable" offers the login).
func Code(err error) string {
	switch {
	case errors.Is(err, ErrUnavailable):
		return "unavailable"
	case errors.Is(err, ErrLoginRequired):
		return "login"
	case errors.Is(err, ErrNotFound):
		return "notfound"
	}
	return ""
}

// Shortcode extracts the post code from a post/reel URL.
func Shortcode(u string) (string, error) {
	m := reShortcode.FindStringSubmatch(u)
	if m == nil {
		return "", ErrNotPost
	}
	return m[1], nil
}

// MediaID converts a shortcode to the numeric media id (base64 alphabet;
// shortcodes of private posts carry extra characters after the first 11).
func MediaID(code string) string {
	const abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
	var n uint64
	for i, c := range code {
		if i == 11 {
			break
		}
		n = n*64 + uint64(strings.IndexRune(abc, c))
	}
	return strconv.FormatUint(n, 10)
}

// Query is what the WebView needs to make the request. Kind "post" (post,
// reel, carousel) or "story" (needs the logged-in session).
type Query struct {
	Kind         string `json:"kind"`
	MediaID      string `json:"mediaId,omitempty"`
	Username     string `json:"username,omitempty"`
	StoryPK      string `json:"storyPk,omitempty"`
	Shortcode    string `json:"shortcode"`
	AppID        string `json:"appId"`
	DocID        string `json:"docId"`
	FriendlyName string `json:"friendlyName"`
	Variables    string `json:"variables"`
	Referer      string `json:"referer"`
}

func NewQuery(u string) (*Query, error) {
	if m := reStory.FindStringSubmatch(u); m != nil {
		return &Query{
			Kind: "story", Username: m[1], StoryPK: m[2], AppID: AppID,
			Referer: "https://www.instagram.com/stories/" + m[1] + "/" + m[2] + "/",
		}, nil
	}
	code, err := Shortcode(u)
	if err != nil {
		return nil, err
	}
	id := MediaID(code)
	vars, _ := json.Marshal(map[string]string{"media_id": id})
	return &Query{
		Kind: "post", MediaID: id, Shortcode: code, AppID: AppID, DocID: DocID, FriendlyName: FriendlyName,
		Variables: string(vars), Referer: "https://www.instagram.com/p/" + code + "/",
	}, nil
}

// ---- response

type candidate struct {
	URL    string `json:"url"`
	Width  int    `json:"width"`
	Height int    `json:"height"`
}

type item struct {
	MediaType    int         `json:"media_type"` // 1 photo, 2 video, 8 carousel
	Code         string      `json:"code"`
	PK           pkString    `json:"pk"`
	ID           string      `json:"id"`
	OrigWidth    int         `json:"original_width"`
	OrigHeight   int         `json:"original_height"`
	VideoVersion []candidate `json:"video_versions"`
	DashManifest string      `json:"video_dash_manifest"`
	Images       struct {
		Candidates []candidate `json:"candidates"`
	} `json:"image_versions2"`
	Carousel []item `json:"carousel_media"`
	User     struct {
		Username string `json:"username"`
		FullName string `json:"full_name"`
	} `json:"user"`
	Caption *struct {
		Text string `json:"text"`
	} `json:"caption"`
}

// Result mirrors scan.Result plus Instagram-specific bits; AudioOptions and
// Music feed the "audio only" mode like a yt-dlp result.
type Result struct {
	scan.Result
	Uploader     string        `json:"uploader,omitempty"`
	AudioOptions []scan.Option `json:"audioOptions,omitempty"`
	Music        music.Basic   `json:"music"`
}

// Parse reads a raw response: the logged-out GraphQL query, the logged-in
// media info API ({"items":[...]}) or the stories API ({"reels":{...}}),
// in which case storyPK selects the story.
func Parse(body []byte, storyPK string) (*Result, error) {
	s := strings.TrimPrefix(strings.TrimSpace(string(body)), "for (;;);")
	if !strings.HasPrefix(s, "{") {
		return nil, errors.New("Instagram returned no data (temporary block?)")
	}
	var resp struct {
		Data struct {
			Media *struct {
				Product *item `json:"if_not_gated_logged_out"`
			} `json:"xig_polaris_media"`
		} `json:"data"`
		Errors []struct {
			Message string `json:"message"`
		} `json:"errors"`
		// Logged-in REST APIs.
		Items []item `json:"items"`
		Reels map[string]struct {
			Items []item `json:"items"`
		} `json:"reels"`
		Message string `json:"message"`
		Status  string `json:"status"`
	}
	if err := json.Unmarshal([]byte(s), &resp); err != nil {
		return nil, fmt.Errorf("invalid Instagram response: %w", err)
	}

	var p *item
	switch {
	case resp.Status == "fail":
		switch m := strings.ToLower(resp.Message); {
		case strings.Contains(m, "login"), strings.Contains(m, "checkpoint"):
			return nil, ErrLoginRequired
		case m != "":
			return nil, fmt.Errorf("%w (%s)", ErrNotFound, resp.Message)
		}
		return nil, ErrNotFound
	case resp.Reels != nil:
		for _, r := range resp.Reels {
			for i := range r.Items {
				if r.Items[i].PK.String() == storyPK || r.Items[i].ID == storyPK || strings.HasPrefix(r.Items[i].ID, storyPK+"_") {
					p = &r.Items[i]
				}
			}
		}
		if p == nil {
			return nil, ErrNotFound
		}
	case len(resp.Items) > 0:
		p = &resp.Items[0]
	case len(resp.Errors) > 0 && resp.Data.Media == nil:
		return nil, fmt.Errorf("Instagram: %s", resp.Errors[0].Message)
	case resp.Data.Media == nil || resp.Data.Media.Product == nil:
		return nil, ErrUnavailable
	default:
		p = resp.Data.Media.Product
	}
	res := &Result{Uploader: p.User.Username}
	res.PageURL = "https://www.instagram.com/p/" + p.Code + "/"
	if storyPK != "" {
		res.PageURL = "https://www.instagram.com/stories/" + p.User.Username + "/" + storyPK + "/"
	}
	res.Title = title(p)
	res.Thumbnail = bestImage(p.Images.Candidates)

	switch {
	case p.MediaType == 8 || len(p.Carousel) > 0:
		// One option per item, in the best quality; the sheet lets the user
		// tick which items to download.
		n := len(p.Carousel)
		for i, it := range p.Carousel {
			opts := itemOptions(it, fmt.Sprintf("%d/%d · ", i+1, n), false)
			if len(opts) == 0 {
				continue
			}
			o := opts[0]
			o.Item, o.ItemCount, o.ItemThumb = i+1, n, bestImage(it.Images.Candidates)
			res.Options = append(res.Options, o)
		}
	default:
		res.Options = itemOptions(*p, "", true)
		if d := parseDash(p.DashManifest); d.audio != "" {
			res.AudioOptions = audioOptions(d)
			res.Music = music.Basic{
				Title: res.Title, Uploader: p.User.Username, Duration: d.duration, Thumbnail: res.Thumbnail,
			}
		}
	}
	if len(res.Options) == 0 {
		return nil, errors.New("no media found in the post")
	}
	for i := range res.Options {
		res.Options[i].ID = fmt.Sprintf("i%d", i)
		res.Options[i].Source = "instagram"
		res.Options[i].Group = res.PageURL
	}
	return res, nil
}

// itemOptions: a photo gives one image option; a video gives the DASH
// renditions (best quality; video + audio merged by ffmpeg) and the
// progressive MP4 (H.264, plays everywhere).
func itemOptions(it item, prefix string, all bool) []scan.Option {
	if it.MediaType == 1 || (len(it.VideoVersion) == 0 && it.DashManifest == "") {
		u := bestImage(it.Images.Candidates)
		if u == "" {
			return nil
		}
		label := prefix + "Photo"
		if it.OrigWidth > 0 {
			label += fmt.Sprintf(" · %d×%d", it.OrigWidth, it.OrigHeight)
		}
		return []scan.Option{{Kind: "image", Label: label, URL: u, Height: it.OrigHeight, Size: -1}}
	}

	var opts []scan.Option
	dash := parseDash(it.DashManifest)
	if dash.audio != "" {
		reps := dash.video
		if !all && len(reps) > 1 {
			reps = reps[:1] // carousel items: only the best of each video
		}
		for _, r := range reps {
			opts = append(opts, scan.Option{
				Kind: "merge", Label: fmt.Sprintf("%s%dp · %s", prefix, r.short(), codecName(r.codecs)),
				URL: r.url, AudioURL: dash.audio, Height: r.short(), Bandwidth: r.bandwidth,
				Size: sizeOf(r.bandwidth+dash.audioBandwidth, dash.duration), DurationSec: dash.duration,
				VCodec: codecName(r.codecs),
			})
		}
	}
	if len(it.VideoVersion) > 0 {
		// Progressive MP4 (H.264 + AAC, usually 720p): no merge needed and
		// plays on any device. Height 0 keeps it after the DASH renditions.
		opts = append(opts, scan.Option{
			Kind: "mp4", Label: prefix + "MP4 · H.264 (compatible)", URL: it.VideoVersion[0].URL,
			Size: -1, DurationSec: dash.duration,
		})
	}
	return opts
}

// audioOptions: the DASH audio track copied as M4A, or encoded to MP3.
func audioOptions(d dashInfo) []scan.Option {
	kbps := float64(d.audioBandwidth) / 1000
	mk := func(format, quality, label string, k float64) scan.Option {
		return scan.Option{
			Kind: "audio", AudioFormat: format, AudioQuality: quality, Label: label, URL: d.audio,
			Size: sizeOf(int64(k*1000), d.duration), DurationSec: d.duration, Source: "instagram",
		}
	}
	return []scan.Option{
		mk("m4a", "copy", fmt.Sprintf("M4A · AAC %.0f kbps · original", kbps), kbps),
		// The source is ~64 kbps: 320 kbps would only waste space.
		mk("mp3", "v0", "MP3 · V0", min(245, kbps*2)),
	}
}

func bestImage(cs []candidate) string {
	best, bw := "", -1
	for i, c := range cs {
		// Candidates are listed largest first; widths may be missing.
		w := c.Width
		if w == 0 {
			w = 1_000_000 - i
		}
		if w > bw {
			best, bw = c.URL, w
		}
	}
	return best
}

func title(p *item) string {
	t := ""
	if p.Caption != nil {
		t = strings.TrimSpace(strings.SplitN(p.Caption.Text, "\n", 2)[0])
	}
	if utf8.RuneCountInString(t) > 80 {
		t = string([]rune(t)[:79]) + "…"
	}
	user := "@" + p.User.Username
	switch {
	case t == "" && p.User.Username == "":
		return "Instagram"
	case t == "":
		return user
	case p.User.Username == "":
		return t
	}
	return user + " - " + t
}

func sizeOf(bandwidth int64, dur float64) int64 {
	if bandwidth <= 0 || dur <= 0 {
		return -1
	}
	return int64(float64(bandwidth) / 8 * dur)
}

func codecName(c string) string {
	switch {
	case strings.HasPrefix(c, "vp09"):
		return "VP9"
	case strings.HasPrefix(c, "avc"):
		return "H.264"
	case strings.HasPrefix(c, "av01"):
		return "AV1"
	case strings.HasPrefix(c, "hvc"), strings.HasPrefix(c, "hev"):
		return "H.265"
	}
	return strings.ToUpper(strings.SplitN(c, ".", 2)[0])
}

// ---- DASH manifest (one BaseURL per Representation, no segments)

type rep struct {
	url           string
	width, height int
	bandwidth     int64
	codecs        string
}

// short is the smaller side: "1080p" for a 1080×1920 portrait reel.
func (r rep) short() int { return min(r.width, r.height) }

type dashInfo struct {
	video          []rep // best first, one per resolution
	audio          string
	audioBandwidth int64
	duration       float64
}

var (
	reRep      = regexp.MustCompile(`(?s)<Representation\b([^>]*)>(.*?)</Representation>`)
	reAttr     = regexp.MustCompile(`(\w+)="([^"]*)"`)
	reBaseURL  = regexp.MustCompile(`(?s)<BaseURL>(.*?)</BaseURL>`)
	reDuration = regexp.MustCompile(`mediaPresentationDuration="PT(?:(\d+)H)?(?:(\d+)M)?([\d.]+)S"`)
)

func parseDash(m string) dashInfo {
	var d dashInfo
	if m == "" {
		return d
	}
	if dm := reDuration.FindStringSubmatch(m); dm != nil {
		h, _ := strconv.ParseFloat(dm[1], 64)
		mi, _ := strconv.ParseFloat(dm[2], 64)
		s, _ := strconv.ParseFloat(dm[3], 64)
		d.duration = h*3600 + mi*60 + s
	}
	best := map[int]rep{}
	for _, rm := range reRep.FindAllStringSubmatch(m, -1) {
		a := map[string]string{}
		for _, kv := range reAttr.FindAllStringSubmatch(rm[1], -1) {
			a[kv[1]] = kv[2]
		}
		bu := reBaseURL.FindStringSubmatch(rm[2])
		if bu == nil {
			continue
		}
		r := rep{url: html.UnescapeString(strings.TrimSpace(bu[1])), codecs: a["codecs"]}
		r.width, _ = strconv.Atoi(a["width"])
		r.height, _ = strconv.Atoi(a["height"])
		r.bandwidth, _ = strconv.ParseInt(a["bandwidth"], 10, 64)
		// Instagram labels renditions (FBQualityLabel="720p") but reports
		// the source dimensions; the label is the real resolution.
		if q := strings.TrimSuffix(a["FBQualityLabel"], "p"); q != "" {
			if n, err := strconv.Atoi(q); err == nil && n > 0 {
				if r.width <= r.height {
					r.height = r.height * n / max(r.width, 1)
					r.width = n
				} else {
					r.width = r.width * n / max(r.height, 1)
					r.height = n
				}
			}
		}
		switch {
		case strings.HasPrefix(a["mimeType"], "audio"):
			if r.bandwidth > d.audioBandwidth || d.audio == "" {
				d.audio, d.audioBandwidth = r.url, r.bandwidth
			}
		case strings.HasPrefix(a["mimeType"], "video"):
			k := r.short()
			if b, ok := best[k]; !ok || r.bandwidth > b.bandwidth {
				best[k] = r
			}
		}
	}
	for _, r := range best {
		d.video = append(d.video, r)
	}
	sort.Slice(d.video, func(i, j int) bool { return d.video[i].short() > d.video[j].short() })
	return d
}

// pkString accepts Instagram's pk as JSON number or string.
type pkString string

func (p *pkString) UnmarshalJSON(b []byte) error {
	*p = pkString(strings.Trim(string(b), `"`))
	return nil
}

func (p pkString) String() string { return string(p) }
