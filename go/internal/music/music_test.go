package music

import (
	"context"
	"os"
	"testing"
)

func TestCleanAndSplit(t *testing.T) {
	cases := map[string][2]string{
		"Rick Astley - Never Gonna Give You Up (Official Music Video)": {"Rick Astley", "Never Gonna Give You Up"},
		"Anitta – Envolver [Official Video] [4K]":                      {"Anitta", "Envolver"},
		"Coldplay - Yellow (Lyrics)":                                   {"Coldplay", "Yellow"},
		"Marília Mendonça | Infiel - Videoclipe Oficial":               {"Marília Mendonça", "Infiel"},
	}
	for in, want := range cases {
		a, ti, ok := SplitArtistTitle(CleanTitle(in))
		if !ok || a != want[0] || ti != want[1] {
			t.Errorf("%q -> (%q, %q, %v), want %v", in, a, ti, ok, want)
		}
	}
	if CleanTitle("Song (Live at Wembley)") != "Song (Live at Wembley)" {
		t.Error("CleanTitle removed a meaningful suffix")
	}
}

func TestNormalizeSimilarity(t *testing.T) {
	if normalize("Beyoncé (feat. JAY-Z)") != "beyonce" {
		t.Errorf("normalize = %q", normalize("Beyoncé (feat. JAY-Z)"))
	}
	if variantPenalty("Never Gonna Give You Up (Live)", "Never Gonna Give You Up") == 0 {
		t.Error("live version not penalized")
	}
	if variantPenalty("Song (Live)", "Song (Live)") != 0 {
		t.Error("requested live version penalized")
	}
}

func TestCandidates(t *testing.T) {
	js := []byte(`{"entries":[
		{"id":"a","url":"https://music.youtube.com/watch?v=a","title":"Never Gonna Give You Up (Pianoforte)"},
		{"id":"b","url":"https://music.youtube.com/watch?v=b","title":"Never Gonna Give You Up"},
		{"id":"c","url":"https://music.youtube.com/watch?v=c","title":"Together Forever"}]}`)
	got, err := Candidates(js, Meta{Title: "Never Gonna Give You Up", Artist: "Rick Astley"}, 3)
	// The "Pianoforte" rendition is a different recording: excluded.
	if err != nil || len(got) != 1 || got[0] != "https://music.youtube.com/watch?v=b" {
		t.Errorf("Candidates = %v, %v", got, err)
	}
}

// Network: DV_NET=1 go test ./internal/music -run Net -v
func TestNetResolveAndLinks(t *testing.T) {
	if os.Getenv("DV_NET") == "" {
		t.Skip("DV_NET not set")
	}
	ctx := context.Background()
	m := Resolve(ctx, Basic{Title: "Rick Astley - Never Gonna Give You Up (Official Music Video)", Uploader: "Rick Astley", Duration: 213, Thumbnail: "https://i.ytimg.com/vi/x/maxresdefault.jpg"}, nil)
	t.Logf("resolve: %+v", m)
	if m.Album == "" || !m.CoverSquare || m.ISRC == "" {
		t.Errorf("expected album, square cover and ISRC (iTunes + Deezer)")
	}
	for _, link := range []string{
		"https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=x",
		"https://www.deezer.com/br/track/14408104",
	} {
		lm, err := FromLink(ctx, link)
		if err != nil {
			t.Errorf("%s: %v", link, err)
			continue
		}
		t.Logf("%s -> %+v | search %s", link, *lm, SearchURL(*lm))
	}
}

func TestNetNoMatchKeepsFullTitle(t *testing.T) {
	if os.Getenv("DV_NET") == "" {
		t.Skip("DV_NET not set")
	}
	m := Resolve(context.Background(), Basic{Title: "Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film", Uploader: "Blender", Duration: 635}, nil)
	if m.Title != "Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film" || m.Artist != "Blender" {
		t.Errorf("got %q / %q", m.Artist, m.Title)
	}
}

func TestNetMusicContextKeepsSplit(t *testing.T) {
	if os.Getenv("DV_NET") == "" {
		t.Skip("DV_NET not set")
	}
	// A remix uploaded by a non-artist channel: no catalog match (duration).
	b := Basic{Title: "Peter Schilling - Major Tom (Coming Home) (Culture Shock Remix)", Uploader: "Memory Lane", Duration: 372}
	if m := Resolve(context.Background(), b, nil); m.Artist != "Memory Lane" {
		t.Errorf("outside music context the channel should stay the artist: %+v", m)
	}
	b.MusicContext = true
	m := Resolve(context.Background(), b, nil)
	if m.Artist != "Peter Schilling" || m.Title != "Major Tom (Coming Home) (Culture Shock Remix)" {
		t.Errorf("music context: got %q / %q", m.Artist, m.Title)
	}
}
