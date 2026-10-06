package instagram

import (
	"encoding/json"
	"errors"
	"regexp"
	"strings"
)

// Threads posts use Instagram's media schema. The post page, as served to a
// browser, embeds the post (and related ones) in its ServerJS data
// (<script type="application/json" data-sjs>); the app's WebView sends those
// scripts, or the raw page, and ParseThreads picks the shared post by code.

var (
	reThreadsPost  = regexp.MustCompile(`threads\.(?:net|com)/(?:@([\w.]+)/post|t)/([A-Za-z0-9_-]{6,})`)
	reThreadsShare = regexp.MustCompile(`threads\.(?:net|com)/share/[\w-]+`)
	reJSONScript   = regexp.MustCompile(`(?s)<script type="application/json"[^>]*>(.*?)</script>`)
)

// ErrNotThreadsPost is returned for Threads links that are not a post.
var ErrNotThreadsPost = errors.New("not a Threads post link")

// ErrNoMedia: a text-only Threads post.
var ErrNoMedia = errors.New("this Threads post has no photo or video")

// NewThreadsQuery: kind "threads", the post code and the page to open.
// Share links (threads.com/share/<id>, from the app's share button) redirect
// to the post: no code yet, the WebView reports the final URL.
func NewThreadsQuery(u string) (*Query, error) {
	m := reThreadsPost.FindStringSubmatch(u)
	if m == nil {
		if reThreadsShare.MatchString(u) {
			return &Query{Kind: "threads", Referer: u}, nil
		}
		return nil, ErrNotThreadsPost
	}
	ref := "https://www.threads.com/t/" + m[2]
	if m[1] != "" {
		ref = "https://www.threads.com/@" + m[1] + "/post/" + m[2]
	}
	return &Query{Kind: "threads", Shortcode: m[2], MediaID: MediaID(m[2]), Referer: ref}, nil
}

// ParseThreads reads the post page data: what the WebView sends
// ({"url","scripts"} or {"url","html"}), a JSON array with the contents of
// the page's data-sjs scripts, or the page's HTML. An empty code is taken
// from the page URL (share links).
func ParseThreads(body []byte, code string) (*Result, error) {
	var blobs []string
	s := strings.TrimSpace(string(body))
	if strings.HasPrefix(s, "{") {
		var page struct {
			URL     string   `json:"url"`
			Scripts []string `json:"scripts"`
			HTML    string   `json:"html"`
		}
		if err := json.Unmarshal([]byte(s), &page); err != nil {
			return nil, err
		}
		if m := reThreadsPost.FindStringSubmatch(page.URL); code == "" && m != nil {
			code = m[2]
		}
		blobs, s = page.Scripts, page.HTML
	}
	if code == "" {
		return nil, ErrNotThreadsPost
	}
	if strings.HasPrefix(s, "[") {
		if err := json.Unmarshal([]byte(s), &blobs); err != nil {
			return nil, err
		}
	} else if s != "" {
		for _, m := range reJSONScript.FindAllStringSubmatch(s, -1) {
			blobs = append(blobs, m[1])
		}
	}

	var post *item
	for _, b := range blobs {
		if !strings.Contains(b, `"`+code+`"`) {
			continue
		}
		for _, v := range decodeAll(b) {
			if p := findPost(v, code); p != nil && (post == nil || !hasMedia(post)) {
				post = p
			}
		}
	}
	if post == nil {
		// Login wall or removed post.
		return nil, ErrUnavailable
	}
	if !hasMedia(post) && post.TextPostAppInfo != nil {
		si := post.TextPostAppInfo.ShareInfo
		for _, p := range []*item{si.RepostedPost, si.QuotedPost, si.Attachment} {
			if p != nil && hasMedia(p) {
				post = p
				break
			}
		}
	}
	if !hasMedia(post) {
		return nil, ErrNoMedia
	}
	pageURL := "https://www.threads.com/@" + post.User.Username + "/post/" + post.Code
	if post.User.Username == "" || post.Code == "" {
		pageURL = "https://www.threads.com/t/" + code
	}
	return build(post, pageURL, "threads")
}

// decodeAll decodes a JSON document, or a GraphQL response streamed as one
// JSON object per line (optionally behind Meta's "for (;;);" guard).
func decodeAll(b string) []any {
	b = strings.TrimPrefix(strings.TrimSpace(b), "for (;;);")
	var v any
	if json.Unmarshal([]byte(b), &v) == nil {
		return []any{v}
	}
	var out []any
	for _, line := range strings.Split(b, "\n") {
		var v any
		if json.Unmarshal([]byte(strings.TrimSpace(line)), &v) == nil {
			out = append(out, v)
		}
	}
	return out
}

// findPost walks decoded JSON for the media object with this code,
// preferring one that has media.
func findPost(v any, code string) *item {
	var found *item
	var walk func(any)
	walk = func(v any) {
		switch o := v.(type) {
		case map[string]any:
			if c, _ := o["code"].(string); c == code {
				if _, ok := o["image_versions2"]; ok {
					if b, err := json.Marshal(o); err == nil {
						var it item
						if json.Unmarshal(b, &it) == nil && (found == nil || (!hasMedia(found) && hasMedia(&it))) {
							found = &it
						}
					}
				}
			}
			for _, c := range o {
				walk(c)
			}
		case []any:
			for _, c := range o {
				walk(c)
			}
		}
	}
	walk(v)
	return found
}

func hasMedia(p *item) bool {
	return len(p.Images.Candidates) > 0 || len(p.VideoVersion) > 0 || p.DashManifest != "" || len(p.Carousel) > 0
}
