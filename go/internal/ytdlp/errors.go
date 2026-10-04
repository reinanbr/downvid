package ytdlp

import (
	"regexp"
	"strings"
)

// FriendlyError maps yt-dlp's stderr to a short message in Portuguese.
// platform is the extractor/site name used in the message.
func FriendlyError(stderr, platform string) string {
	l := strings.ToLower(stderr)
	// "O YouTube exige..." / "O site exige...": the article goes in the
	// messages, the name here.
	if platform == "" {
		platform = "The site"
	}
	rules := []struct {
		any []string
		msg string
	}{
		// Instagram's deliberately ambiguous message.
		{[]string{"rate-limit reached or login required"}, platform + " rate-limited the access or requires login for this content. Try again in a few minutes."},
		{[]string{"private video", "this video is private", "is private"}, "Private video — only the owner (or people they allowed) can watch it."},
		{[]string{"sign in to confirm your age", "age-restricted", "age restricted", "inappropriate for some users"}, "Age-restricted video — requires signing in to " + platform + "."},
		{[]string{"sign in to confirm you", "not a bot"}, platform + " asked for a bot check for this IP. Try later or on another network."},
		{[]string{"login required", "requires authentication", "log in", "login_required", "you need to log in", "cookies"}, platform + " requires login for this content."},
		{[]string{"rate-limit", "rate limit", "too many requests", "http error 429"}, platform + " is rate-limiting requests. Try again in a few minutes."},
		{[]string{"not available in your country", "geo restrict", "geo-restrict", "not available in your location"}, "Content blocked in your region."},
		{[]string{"members-only", "join this channel", "premium", "requires payment", "paid"}, "Content for subscribers/members only."},
		{[]string{"drm"}, "DRM-protected content — it cannot be downloaded."},
		{[]string{"live event will begin", "premieres in", "is upcoming"}, "The live stream/premiere has not started yet."},
		{[]string{"video unavailable", "this video is unavailable", "has been removed", "does not exist", "http error 404", "no longer available", "account has been terminated"}, "Video unavailable or removed."},
		{[]string{"there is no video in this", "no video formats found", "no media found", "requested format is not available"}, "No video found at this link."},
		{[]string{"unsupported url"}, "Link not supported by the extractor."},
		{[]string{"unable to download webpage", "connection", "timed out", "name or service not known", "network is unreachable"}, "Network error while accessing " + platform + "."},
	}
	for _, r := range rules {
		for _, k := range r.any {
			if strings.Contains(l, k) {
				return r.msg
			}
		}
	}
	if m := lastError(stderr); m != "" {
		return m
	}
	return "Failed to extract the video (" + platform + ")."
}

var reErrorLine = regexp.MustCompile(`(?m)^ERROR: (?:\[[^\]]+\] )?(?:[\w-]+: )?(.+)$`)

// lastError returns the last "ERROR:" line without yt-dlp's prefixes.
func lastError(stderr string) string {
	m := reErrorLine.FindAllStringSubmatch(stderr, -1)
	if len(m) == 0 {
		return ""
	}
	msg := strings.TrimSpace(m[len(m)-1][1])
	if i := strings.Index(msg, "; please report"); i > 0 {
		msg = msg[:i]
	}
	return msg
}

// IsUnsupported reports errors that mean "try the generic page scan".
func IsUnsupported(stderr string) bool {
	l := strings.ToLower(stderr)
	return strings.Contains(l, "unsupported url") || strings.Contains(l, "no video formats found") ||
		strings.Contains(l, "no media found") || strings.Contains(l, "there is no video")
}
