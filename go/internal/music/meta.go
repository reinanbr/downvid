// Package music resolves song metadata (title, artist, album, year, track,
// square cover) for "audio only" downloads, and maps music-service links
// (Spotify, Deezer, Apple Music) to a YouTube Music search.
package music

import (
	"regexp"
	"strings"
	"unicode"

	"golang.org/x/text/unicode/norm"
)

// Meta is written into the audio file's tags.
type Meta struct {
	Title       string  `json:"title"`
	Artist      string  `json:"artist,omitempty"`
	Album       string  `json:"album,omitempty"`
	AlbumArtist string  `json:"albumArtist,omitempty"`
	Year        string  `json:"year,omitempty"`
	Track       int     `json:"track,omitempty"`
	Duration    float64 `json:"duration,omitempty"` // seconds
	ISRC        string  `json:"isrc,omitempty"`
	CoverURL    string  `json:"coverUrl,omitempty"`
	// CoverSquare is false for video thumbnails (16:9); they get center-cropped.
	CoverSquare bool   `json:"coverSquare,omitempty"`
	Source      string `json:"source,omitempty"` // youtube-music | deezer | spotify | itunes | channel
}

// FileName is "Artist - Title" (or just the title).
func (m Meta) FileName() string {
	if m.Artist != "" && !strings.Contains(strings.ToLower(m.Title), strings.ToLower(m.Artist)) {
		return m.Artist + " - " + m.Title
	}
	return m.Title
}

// Noise that video titles add around the song name.
var reNoise = regexp.MustCompile(`(?i)\s*[\(\[【](?:official\s*)?(?:music\s*)?(?:video|audio|clip|visualizer|lyrics?|lyric\s*video|letra|legendado|tradução|traducao|hd|hq|4k|1080p|720p|remastered\s*\d*|videoclipe|ao vivo oficial|official)[^\)\]】]*[\)\]】]`)
var reTrailNoise = regexp.MustCompile(`(?i)\s*[-–|]\s*(?:official\s*(?:music\s*)?video|lyrics?|audio|videoclipe oficial|clipe oficial)\s*$`)
var reSpaces = regexp.MustCompile(`\s+`)

// CleanTitle removes "(Official Video)", "[Lyrics]", "| Audio" and the like.
func CleanTitle(s string) string {
	s = reNoise.ReplaceAllString(s, "")
	s = reTrailNoise.ReplaceAllString(s, "")
	return strings.TrimSpace(reSpaces.ReplaceAllString(s, " "))
}

// SplitArtistTitle splits "Artist - Title" (also –, —, |). ok is false when
// there is no separator.
func SplitArtistTitle(s string) (artist, title string, ok bool) {
	for _, sep := range []string{" - ", " – ", " — ", " | "} {
		if a, t, found := strings.Cut(s, sep); found && strings.TrimSpace(a) != "" && strings.TrimSpace(t) != "" {
			return strings.TrimSpace(a), strings.TrimSpace(t), true
		}
	}
	return "", "", false
}

// norm lowercases, strips accents/punctuation and "feat." credits, for
// comparisons ("Beyoncé (feat. X)" ~ "beyonce").
func normalize(s string) string {
	s = strings.ToLower(s)
	for _, cut := range []string{" feat.", " feat ", " ft.", " ft ", " featuring ", " (with ", " (feat", " [feat"} {
		if i := strings.Index(s, cut); i > 0 {
			s = s[:i]
		}
	}
	var b strings.Builder
	for _, r := range norm.NFD.String(s) {
		switch {
		case unicode.Is(unicode.Mn, r):
		case unicode.IsLetter(r), unicode.IsDigit(r):
			b.WriteRune(r)
		case unicode.IsSpace(r):
			b.WriteRune(' ')
		}
	}
	return strings.Join(strings.Fields(b.String()), " ")
}

// similarity is the share of a's words found in b (0..1).
func similarity(a, b string) float64 {
	wa, wb := strings.Fields(normalize(a)), strings.Fields(normalize(b))
	if len(wa) == 0 || len(wb) == 0 {
		return 0
	}
	set := map[string]bool{}
	for _, w := range wb {
		set[w] = true
	}
	hit := 0
	for _, w := range wa {
		if set[w] {
			hit++
		}
	}
	return float64(hit) / float64(len(wa))
}

// Versions that are usually not what the user wants unless asked for.
var variantWords = []string{"live", "ao vivo", "en directo", "remix", "cover", "karaoke", "instrumental",
	"acoustic", "acustico", "sped up", "slowed", "nightcore", "8d", "piano", "pianoforte", "reverb", "version"}

func variantPenalty(candidate, wanted string) float64 {
	c, w := normalize(candidate), normalize(wanted)
	p := 0.0
	for _, v := range variantWords {
		if strings.Contains(c, v) && !strings.Contains(w, v) {
			p += 0.5
		}
	}
	return p
}
