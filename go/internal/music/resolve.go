package music

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/downvid/core/internal/logx"
	"github.com/downvid/core/internal/netx"
)

// Basic is what the extractor (yt-dlp) knows about a video.
type Basic struct {
	Title       string  `json:"title"` // video title
	Track       string  `json:"track"` // YouTube Music / SoundCloud fields
	Artist      string  `json:"artist"`
	Album       string  `json:"album"`
	ReleaseYear int     `json:"releaseYear"`
	Uploader    string  `json:"uploader"`
	Duration    float64 `json:"duration"`
	Thumbnail   string  `json:"thumbnail"`
	ThumbSquare bool    `json:"thumbSquare"`
	// MusicContext: the link is a music service (YouTube Music, SoundCloud),
	// so "Artist - Title" in the title is trusted even without a catalog
	// match (remixes, covers and rare uploads are not in iTunes/Deezer).
	MusicContext bool `json:"musicContext"`
}

// Resolve builds the best metadata for a song:
//  1. extractor fields (YouTube Music gives track/artist/album/year);
//  2. "Artist - Title" parsed from the cleaned video title;
//  3. a Deezer match (duration within ±3 s, similar title/artist) fills album,
//     year, track number and a 1000×1000 cover;
//  4. otherwise the channel name and the video thumbnail (center-cropped).
//
// override (from a Spotify/Apple/Deezer link) wins over everything.
func Resolve(ctx context.Context, b Basic, override *Meta) Meta {
	m := Meta{Duration: b.Duration, CoverURL: b.Thumbnail, CoverSquare: b.ThumbSquare, Source: "channel"}
	switch {
	case b.Track != "" && b.Artist != "":
		m.Title, m.Artist, m.Album, m.Source = b.Track, b.Artist, b.Album, "youtube-music"
		if b.ReleaseYear > 0 {
			m.Year = strconv.Itoa(b.ReleaseYear)
		}
	default:
		clean := CleanTitle(b.Title)
		if a, t, ok := SplitArtistTitle(clean); ok {
			m.Artist, m.Title = a, t
		} else {
			m.Title, m.Artist = clean, strings.TrimSuffix(b.Uploader, " - Topic")
		}
	}
	// "Artist - Title" is only a guess; without a catalog match it is undone
	// (e.g. "Big Buck Bunny - Official Short Film" is not artist + song).
	unsplit := Meta{
		Title: CleanTitle(b.Title), Artist: strings.TrimSuffix(b.Uploader, " - Topic"),
		Duration: b.Duration, CoverURL: b.Thumbnail, CoverSquare: b.ThumbSquare, Source: "channel",
	}
	guessed := m.Source == "channel" && m.Title != unsplit.Title
	if override != nil {
		m = merge(*override, m)
	}

	ctx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	// iTunes tends to return the original album (Deezer often a compilation);
	// both are queried in parallel and iTunes wins, Deezer fills the gaps.
	itCh := make(chan *Meta, 1)
	go func() {
		it, err := itunesMatch(ctx, m)
		if err != nil {
			logx.Infof("music: itunes: %v", err)
		}
		itCh <- it
	}()
	d, err := deezerMatch(ctx, m)
	if err != nil {
		logx.Infof("music: deezer: %v", err)
	}
	if it := <-itCh; it != nil {
		logx.Infof("music: itunes match %q - %q (%s %s)", it.Artist, it.Title, it.Album, it.Year)
		if d != nil {
			*it = merge(*it, *d)
		}
		d = it
	}
	if d == nil && guessed && override == nil && !b.MusicContext {
		m = unsplit
	}
	if d != nil {
		logx.Infof("music: match %q - %q (%s, %s) via %s", d.Artist, d.Title, d.Album, d.Year, d.Source)
		if override != nil {
			m = merge(m, *d) // keep the link's names, fill the gaps
			if !m.CoverSquare {
				m.CoverURL, m.CoverSquare = d.CoverURL, true
			}
		} else {
			// Deezer's names are the canonical ones (proper case, no noise).
			m = merge(*d, m)
		}
	}
	if m.AlbumArtist == "" {
		m.AlbumArtist = m.Artist
	}
	return m
}

// merge returns a with empty fields filled from b.
func merge(a, b Meta) Meta {
	if a.Title == "" {
		a.Title = b.Title
	}
	if a.Artist == "" {
		a.Artist = b.Artist
	}
	if a.Album == "" {
		a.Album = b.Album
	}
	if a.AlbumArtist == "" {
		a.AlbumArtist = b.AlbumArtist
	}
	if a.Year == "" {
		a.Year = b.Year
	}
	if a.Track == 0 {
		a.Track = b.Track
	}
	if a.Duration == 0 {
		a.Duration = b.Duration
	}
	if a.ISRC == "" {
		a.ISRC = b.ISRC
	}
	if a.CoverURL == "" {
		a.CoverURL, a.CoverSquare = b.CoverURL, b.CoverSquare
	}
	if a.Source == "" {
		a.Source = b.Source
	}
	return a
}

type deezerTrack struct {
	ID       int64  `json:"id"`
	Title    string `json:"title"`
	Duration int    `json:"duration"`
	ISRC     string `json:"isrc"`
	Position int    `json:"track_position"`
	Release  string `json:"release_date"`
	Artist   struct {
		Name string `json:"name"`
	} `json:"artist"`
	Album struct {
		ID      int64  `json:"id"`
		Title   string `json:"title"`
		CoverXL string `json:"cover_xl"`
		Release string `json:"release_date"`
	} `json:"album"`
}

// deezerMatch searches Deezer's public API and accepts a result only when
// title and artist are similar and the duration agrees (±3 s), so a wrong
// song never gets tagged.
func deezerMatch(ctx context.Context, m Meta) (*Meta, error) {
	if m.Title == "" {
		return nil, nil
	}
	q := strings.TrimSpace(m.Artist + " " + m.Title)
	var res struct {
		Data []deezerTrack `json:"data"`
	}
	if err := getJSON(ctx, "https://api.deezer.com/search?limit=10&q="+url.QueryEscape(q), &res); err != nil {
		return nil, err
	}
	var best *deezerTrack
	bestScore := 0.0
	for i := range res.Data {
		t := &res.Data[i]
		if m.Duration > 0 && math.Abs(float64(t.Duration)-m.Duration) > 3 {
			continue
		}
		score := similarity(m.Title, t.Title) + similarity(t.Title, m.Title)
		if m.Artist != "" {
			score += 2 * math.Max(similarity(t.Artist.Name, m.Artist), similarity(m.Artist, t.Artist.Name))
		}
		score -= variantPenalty(t.Title, m.Title)
		if score > bestScore {
			best, bestScore = t, score
		}
	}
	// Title both ways (2) + artist (2): require a solid match.
	if best == nil || bestScore < 2.5 {
		return nil, nil
	}
	// The search result lacks track number / release date.
	var full deezerTrack
	if err := getJSON(ctx, fmt.Sprintf("https://api.deezer.com/track/%d", best.ID), &full); err == nil && full.ID != 0 {
		best = &full
	}
	d := &Meta{
		Title: best.Title, Artist: best.Artist.Name, Album: best.Album.Title, Track: best.Position,
		Duration: float64(best.Duration), ISRC: best.ISRC, CoverURL: best.Album.CoverXL, CoverSquare: true,
		Source: "deezer",
	}
	for _, r := range []string{best.Release, best.Album.Release} {
		if len(r) >= 4 {
			d.Year = r[:4]
			break
		}
	}
	return d, nil
}

func getJSON(ctx context.Context, u string, out any) error {
	b, _, err := netx.GetBytes(ctx, u, netx.Headers{}, 4<<20)
	if err != nil {
		return err
	}
	return json.Unmarshal(b, out)
}

// itunesMatch searches the iTunes Store (Apple Music catalog) with the same
// acceptance rules as deezerMatch.
func itunesMatch(ctx context.Context, m Meta) (*Meta, error) {
	if m.Title == "" {
		return nil, nil
	}
	var r struct {
		Results []struct {
			TrackName  string `json:"trackName"`
			ArtistName string `json:"artistName"`
			Collection string `json:"collectionName"`
			Artwork    string `json:"artworkUrl100"`
			TimeMillis int64  `json:"trackTimeMillis"`
			Release    string `json:"releaseDate"`
			TrackNum   int    `json:"trackNumber"`
		} `json:"results"`
	}
	q := url.QueryEscape(strings.TrimSpace(m.Artist + " " + m.Title))
	if err := getJSON(ctx, "https://itunes.apple.com/search?entity=song&limit=10&term="+q, &r); err != nil {
		return nil, err
	}
	best, bestScore := -1, 0.0
	for i, t := range r.Results {
		dur := float64(t.TimeMillis) / 1000
		if m.Duration > 0 && math.Abs(dur-m.Duration) > 3 {
			continue
		}
		score := similarity(m.Title, t.TrackName) + similarity(t.TrackName, m.Title)
		if m.Artist != "" {
			score += 2 * math.Max(similarity(t.ArtistName, m.Artist), similarity(m.Artist, t.ArtistName))
		}
		score -= variantPenalty(t.TrackName, m.Title)
		// Same song on several albums: the store lists the original first,
		// so only a strictly better score replaces an earlier result.
		if score > bestScore {
			best, bestScore = i, score
		}
	}
	if best < 0 || bestScore < 2.5 {
		return nil, nil
	}
	t := r.Results[best]
	it := &Meta{
		Title: t.TrackName, Artist: t.ArtistName, AlbumArtist: t.ArtistName, Album: t.Collection,
		Track: t.TrackNum, Duration: float64(t.TimeMillis) / 1000, Source: "itunes", CoverSquare: true,
		CoverURL: strings.Replace(t.Artwork, "100x100bb", "1000x1000bb", 1),
	}
	if len(t.Release) >= 4 {
		it.Year = t.Release[:4]
	}
	return it, nil
}
