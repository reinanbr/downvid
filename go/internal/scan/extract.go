// Package scan finds downloadable video URLs in web pages and resolves them
// into download options (direct MP4 or HLS variants).
package scan

import (
	"html"
	"net/url"
	"regexp"
	"strings"
)

// PageInfo is what static HTML analysis yields.
type PageInfo struct {
	Title      string
	Thumbnail  string
	Candidates []Candidate
	Iframes    []string
}

// Candidate is a URL that may point to a video.
type Candidate struct {
	URL     string `json:"url"`
	Source  string `json:"source"` // html | meta | jsonld | iframe | webview | direct
	Hint    string `json:"hint,omitempty"`
	Referer string `json:"referer,omitempty"` // overrides the page URL
	Cookie  string `json:"cookie,omitempty"`  // cookies for the media host (WebView)
}

const mediaExt = `m3u8|mp4|m4v|mov`

var (
	// Absolute URLs with a media extension anywhere in the document
	// (attributes, inline JSON, JS strings).
	reAbsMedia = regexp.MustCompile(`(?i)https?://[^\s"'<>()\\{}|^` + "`" + `]+?\.(?:` + mediaExt + `)(?:[?#][^\s"'<>()\\{}|^` + "`" + `]*)?`)

	// Quoted relative paths with a media extension, e.g. src="/v/a.m3u8".
	reRelMedia = regexp.MustCompile(`(?i)["']((?:/|\./|\.\./)[^"'\s<>]+?\.(?:` + mediaExt + `)(?:\?[^"'\s<>]*)?)["']`)

	// Percent-encoded URLs inside query strings: ?file=https%3A%2F%2F...m3u8
	reEncMedia = regexp.MustCompile(`(?i)https?%3A%2F%2F[^\s"'<>&]+?(?:\.|%2E)(?:` + mediaExt + `)[^\s"'<>&]*`)

	reTag      = regexp.MustCompile(`(?is)<(video|source|meta|iframe|embed|link)\b([^>]*)>`)
	reAttr     = regexp.MustCompile(`(?is)([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*("([^"]*)"|'([^']*)'|([^\s"'>]+))`)
	reTitle    = regexp.MustCompile(`(?is)<title[^>]*>(.*?)</title>`)
	reJSONLDCU = regexp.MustCompile(`(?i)"(?:contentUrl|embedUrl)"\s*:\s*"([^"]+)"`)
)

// unescapeJS normalizes the escapes in which URLs hide inside scripts/JSON.
var jsUnescaper = strings.NewReplacer(
	`\/`, `/`,
	`/`, `/`, `/`, `/`,
	`&`, `&`, `=`, `=`, `=`, `=`,
	`\x2F`, `/`, `\x2f`, `/`,
	`&amp;`, `&`, `&#x2F;`, `/`, `&#47;`, `/`,
)

// ExtractPage scans an HTML document. base resolves relative URLs.
func ExtractPage(doc string, base *url.URL) PageInfo {
	var info PageInfo
	seen := map[string]bool{}
	add := func(raw, source, hint string) {
		raw = strings.TrimSpace(html.UnescapeString(raw))
		if raw == "" || strings.HasPrefix(raw, "blob:") || strings.HasPrefix(raw, "data:") {
			return
		}
		u, err := base.Parse(raw)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") {
			return
		}
		u.Fragment = ""
		s := u.String()
		if seen[s] {
			return
		}
		seen[s] = true
		info.Candidates = append(info.Candidates, Candidate{URL: s, Source: source, Hint: hint})
	}

	// 1. Structural tags: <video src>, <source src>, og:video, iframes.
	for _, m := range reTag.FindAllStringSubmatch(doc, -1) {
		tag := strings.ToLower(m[1])
		attrs := parseAttrs(m[2])
		switch tag {
		case "video", "source", "embed":
			for _, k := range []string{"src", "data-src", "data-video-src", "data-hls", "data-mp4"} {
				if v := attrs[k]; v != "" {
					add(v, "html", tag)
				}
			}
		case "meta":
			key := strings.ToLower(attrs["property"] + attrs["name"] + attrs["itemprop"])
			val := attrs["content"]
			switch key {
			case "og:video", "og:video:url", "og:video:secure_url", "twitter:player:stream", "contenturl":
				add(val, "meta", key)
			case "og:title", "twitter:title":
				if info.Title == "" {
					info.Title = html.UnescapeString(val)
				}
			case "og:image", "og:image:url", "og:image:secure_url", "twitter:image", "thumbnailurl":
				if info.Thumbnail == "" {
					if u, err := base.Parse(html.UnescapeString(val)); err == nil {
						info.Thumbnail = u.String()
					}
				}
			}
		case "iframe":
			src := attrs["src"]
			if src == "" {
				src = attrs["data-src"]
			}
			if u, err := base.Parse(html.UnescapeString(src)); err == nil && (u.Scheme == "http" || u.Scheme == "https") {
				info.Iframes = append(info.Iframes, u.String())
			}
		case "link":
			if strings.EqualFold(attrs["rel"], "preload") && strings.EqualFold(attrs["as"], "video") {
				add(attrs["href"], "html", "preload")
			}
		}
	}

	if info.Title == "" {
		if m := reTitle.FindStringSubmatch(doc); m != nil {
			info.Title = strings.TrimSpace(html.UnescapeString(m[1]))
		}
	}

	// 2. URLs embedded anywhere (scripts, JSON state, data attributes).
	flat := jsUnescaper.Replace(doc)
	for _, m := range reJSONLDCU.FindAllStringSubmatch(flat, -1) {
		if hasMediaExt(m[1]) {
			add(m[1], "jsonld", "")
		}
	}
	for _, s := range reAbsMedia.FindAllString(flat, -1) {
		add(trimTrailing(s), "html", "")
	}
	for _, m := range reRelMedia.FindAllStringSubmatch(flat, -1) {
		add(m[1], "html", "")
	}
	for _, s := range reEncMedia.FindAllString(flat, -1) {
		if d, err := url.QueryUnescape(s); err == nil {
			add(trimTrailing(d), "html", "encoded")
		}
	}
	return info
}

func parseAttrs(s string) map[string]string {
	out := map[string]string{}
	for _, m := range reAttr.FindAllStringSubmatch(s, -1) {
		v := m[3] + m[4] + m[5]
		out[strings.ToLower(m[1])] = v
	}
	return out
}

func trimTrailing(s string) string {
	return strings.TrimRight(s, `.,;:!?)]}'"\`)
}

var reExt = regexp.MustCompile(`(?i)\.(` + mediaExt + `)$`)

func hasMediaExt(u string) bool {
	p, err := url.Parse(u)
	if err != nil {
		return false
	}
	return reExt.MatchString(p.Path)
}

// LooksLikeMedia is the filter for URLs observed by the WebView sniffer.
func LooksLikeMedia(u string) bool {
	l := strings.ToLower(u)
	if hasMediaExt(u) {
		return true
	}
	return strings.Contains(l, ".m3u8") || strings.Contains(l, "mpegurl") ||
		strings.Contains(l, "/manifest/hls") || strings.Contains(l, "format=m3u8")
}
