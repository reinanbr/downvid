package music

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"regexp"
	"sort"
	"strings"

	"github.com/downvid/core/internal/netx"
)

// Audio from Spotify / Deezer / Apple Music is DRM-protected and cannot be
// downloaded. Their links are used only for metadata; the same recording is
// then found on YouTube Music (checked by duration).

var (
	reSpotify  = regexp.MustCompile(`open\.spotify\.com/(?:intl-[a-z-]+/)?track/([A-Za-z0-9]{22})`)
	reDeezer   = regexp.MustCompile(`deezer\.com/(?:[a-z]{2}/)?track/(\d+)`)
	reApple    = regexp.MustCompile(`music\.apple\.com/.*[?&]i=(\d+)|music\.apple\.com/[a-z]{2}/song/[^/]+/(\d+)`)
	reNextData = regexp.MustCompile(`(?s)<script id="__NEXT_DATA__" type="application/json">(.*?)</script>`)
)

// ErrNotMusicLink means the URL is not a supported music-service track.
var ErrNotMusicLink = errors.New("not a Spotify/Deezer/Apple Music track link")

// IsServiceLink reports Spotify/Deezer/Apple Music track links.
func IsServiceLink(u string) bool {
	return reSpotify.MatchString(u) || reDeezer.MatchString(u) || reApple.MatchString(u)
}

// FromLink reads a track's metadata from its music-service page/API.
// Short links (spotify.link, deezer.page.link) are followed first.
func FromLink(ctx context.Context, link string) (*Meta, error) {
	if strings.Contains(link, "spotify.link") || strings.Contains(link, "page.link") || strings.Contains(link, "dzr.page") {
		resp, err := netx.Do(ctx, "GET", link, netx.Headers{}, nil)
		if err != nil {
			return nil, err
		}
		resp.Body.Close()
		link = resp.Request.URL.String()
	}
	switch {
	case reSpotify.MatchString(link):
		return fromSpotify(ctx, reSpotify.FindStringSubmatch(link)[1])
	case reDeezer.MatchString(link):
		return fromDeezer(ctx, reDeezer.FindStringSubmatch(link)[1])
	case reApple.MatchString(link):
		m := reApple.FindStringSubmatch(link)
		id := m[1]
		if id == "" {
			id = m[2]
		}
		return fromITunes(ctx, id)
	}
	return nil, ErrNotMusicLink
}

func fromSpotify(ctx context.Context, id string) (*Meta, error) {
	b, _, err := netx.GetBytes(ctx, "https://open.spotify.com/embed/track/"+id, netx.Headers{}, 4<<20)
	if err != nil {
		return nil, fmt.Errorf("spotify: %w", err)
	}
	m := reNextData.FindSubmatch(b)
	if m == nil {
		return nil, errors.New("spotify: page without track data")
	}
	var d struct {
		Props struct {
			PageProps struct {
				State struct {
					Data struct {
						Entity struct {
							Name     string `json:"name"`
							Duration int64  `json:"duration"`
							Artists  []struct {
								Name string `json:"name"`
							} `json:"artists"`
							ReleaseDate struct {
								ISO string `json:"isoString"`
							} `json:"releaseDate"`
							Visual struct {
								Image []struct {
									URL   string `json:"url"`
									Width int    `json:"maxWidth"`
								} `json:"image"`
							} `json:"visualIdentity"`
						} `json:"entity"`
					} `json:"data"`
				} `json:"state"`
			} `json:"pageProps"`
		} `json:"props"`
	}
	if err := json.Unmarshal(m[1], &d); err != nil {
		return nil, fmt.Errorf("spotify: %w", err)
	}
	e := d.Props.PageProps.State.Data.Entity
	if e.Name == "" {
		return nil, errors.New("spotify: track without a name")
	}
	meta := &Meta{Title: e.Name, Duration: float64(e.Duration) / 1000, Source: "spotify", CoverSquare: true}
	var artists []string
	for _, a := range e.Artists {
		artists = append(artists, a.Name)
	}
	if len(artists) > 0 {
		meta.Artist = strings.Join(artists, ", ")
		meta.AlbumArtist = artists[0]
	}
	if len(e.ReleaseDate.ISO) >= 4 {
		meta.Year = e.ReleaseDate.ISO[:4]
	}
	best := 0
	for _, img := range e.Visual.Image {
		if img.Width > best {
			best, meta.CoverURL = img.Width, img.URL
		}
	}
	return meta, nil
}

func fromDeezer(ctx context.Context, id string) (*Meta, error) {
	var t deezerTrack
	if err := getJSON(ctx, "https://api.deezer.com/track/"+id, &t); err != nil || t.ID == 0 {
		return nil, fmt.Errorf("deezer: track %s not found", id)
	}
	m := &Meta{
		Title: t.Title, Artist: t.Artist.Name, Album: t.Album.Title, Track: t.Position,
		Duration: float64(t.Duration), ISRC: t.ISRC, CoverURL: t.Album.CoverXL, CoverSquare: true, Source: "deezer",
	}
	if len(t.Release) >= 4 {
		m.Year = t.Release[:4]
	}
	return m, nil
}

func fromITunes(ctx context.Context, id string) (*Meta, error) {
	var r struct {
		Results []struct {
			Kind       string `json:"kind"`
			TrackName  string `json:"trackName"`
			ArtistName string `json:"artistName"`
			Collection string `json:"collectionName"`
			Artwork    string `json:"artworkUrl100"`
			TimeMillis int64  `json:"trackTimeMillis"`
			Release    string `json:"releaseDate"`
			TrackNum   int    `json:"trackNumber"`
		} `json:"results"`
	}
	if err := getJSON(ctx, "https://itunes.apple.com/lookup?id="+id, &r); err != nil {
		return nil, fmt.Errorf("apple music: %w", err)
	}
	for _, t := range r.Results {
		if t.Kind != "song" {
			continue
		}
		m := &Meta{
			Title: t.TrackName, Artist: t.ArtistName, AlbumArtist: t.ArtistName, Album: t.Collection,
			Track: t.TrackNum, Duration: float64(t.TimeMillis) / 1000, Source: "itunes", CoverSquare: true,
			// Artwork URLs encode the size; ask for 1000×1000.
			CoverURL: strings.Replace(t.Artwork, "100x100bb", "1000x1000bb", 1),
		}
		if len(t.Release) >= 4 {
			m.Year = t.Release[:4]
		}
		return m, nil
	}
	return nil, errors.New("apple music: track not found")
}

// SearchURL is a YouTube Music "songs" search for the track (yt-dlp's
// YoutubeMusicSearchURL extractor; run it flat).
func SearchURL(m Meta) string {
	return "https://music.youtube.com/search?q=" + url.QueryEscape(strings.TrimSpace(m.Artist+" "+m.Title)) + "#songs"
}

// Candidates orders the entries of a flat YouTube Music search by title
// similarity, penalizing live/remix/cover versions the user did not ask for.
// The caller verifies the duration after the full extraction.
func Candidates(searchJSON []byte, m Meta, max int) ([]string, error) {
	var r struct {
		Entries []struct {
			ID    string `json:"id"`
			URL   string `json:"url"`
			Title string `json:"title"`
		} `json:"entries"`
	}
	if err := json.Unmarshal(searchJSON, &r); err != nil {
		return nil, err
	}
	type c struct {
		url   string
		score float64
		pos   int
	}
	var cs []c
	for i, e := range r.Entries {
		u := e.URL
		if u == "" && e.ID != "" {
			u = "https://music.youtube.com/watch?v=" + e.ID
		}
		if u == "" {
			continue
		}
		score := similarity(m.Title, e.Title) + similarity(e.Title, m.Title) - variantPenalty(e.Title, m.Title)
		cs = append(cs, c{u, score, i})
	}
	sort.SliceStable(cs, func(i, j int) bool {
		if cs[i].score != cs[j].score {
			return cs[i].score > cs[j].score
		}
		return cs[i].pos < cs[j].pos // YouTube's own ranking breaks ties
	})
	var out []string
	for _, x := range cs {
		if len(out) == max {
			break
		}
		if x.score >= 1 { // at least the title roughly matches
			out = append(out, x.url)
		}
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("no version of %q found on YouTube Music", m.FileName())
	}
	return out, nil
}

// DurationMatches accepts a found recording within ±5 s of the wanted one.
func DurationMatches(want, got float64) bool {
	return want == 0 || got == 0 || (got-want) < 5 && (want-got) < 5
}
