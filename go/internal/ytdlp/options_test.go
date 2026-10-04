package ytdlp

import (
	"os"
	"testing"
)

// Real `yt-dlp -J` output can be checked with DV_YTDLP_JSON=/path/file.json.
func TestOptionsFromRealJSON(t *testing.T) {
	path := os.Getenv("DV_YTDLP_JSON")
	if path == "" {
		t.Skip("DV_YTDLP_JSON not set")
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	info, err := Parse(b)
	if err != nil {
		t.Fatal(err)
	}
	opts, err := Options(info)
	if err != nil {
		t.Fatal(err)
	}
	for _, o := range opts {
		t.Logf("%-22s kind=%-5s size=%d audio=%v chunk=%d hdrs=%d", o.Label, o.Kind, o.Size, o.AudioURL != "", o.ChunkSize, len(o.Headers))
	}
}

func TestOptionsSelection(t *testing.T) {
	info := &Info{
		WebpageURL: "https://example.com/v", Duration: 100,
		Formats: []Format{
			{ID: "140", URL: "a140", Ext: "m4a", Protocol: "https", VCodec: "none", ACodec: "mp4a.40.2", ABR: 129},
			{ID: "251", URL: "a251", Ext: "webm", Protocol: "https", VCodec: "none", ACodec: "opus", ABR: 140},
			{ID: "251-drc", URL: "drc", Ext: "webm", Protocol: "https", VCodec: "none", ACodec: "opus", ABR: 150},
			{ID: "137", URL: "v1080avc", Ext: "mp4", Protocol: "https", VCodec: "avc1.640028", ACodec: "none", Height: 1080, FPS: 30, TBR: 4000, Filesize: 50_000_000},
			{ID: "248", URL: "v1080vp9", Ext: "webm", Protocol: "https", VCodec: "vp9", ACodec: "none", Height: 1080, FPS: 30, TBR: 3000},
			{ID: "313", URL: "v2160vp9", Ext: "webm", Protocol: "https", VCodec: "vp9", ACodec: "none", Height: 2160, FPS: 30, TBR: 20000},
			{ID: "401", URL: "v2160av1", Ext: "mp4", Protocol: "https", VCodec: "av01.0.12M.08", ACodec: "none", Height: 2160, FPS: 30, TBR: 18000},
			{ID: "18", URL: "m360", Ext: "mp4", Protocol: "https", VCodec: "avc1.42001E", ACodec: "mp4a.40.2", Height: 360, FPS: 30, TBR: 500},
			{ID: "sb0", URL: "sb", Ext: "mhtml", Protocol: "mhtml", VCodec: "none", ACodec: "none"},
			{ID: "hls-720", URL: "h720", Ext: "mp4", Protocol: "m3u8_native", VCodec: "avc1.4d401f", ACodec: "none", Height: 720, TBR: 2000},
			{ID: "hls-audio", URL: "haudio", Ext: "mp4", Protocol: "m3u8_native", VCodec: "none", ACodec: "mp4a.40.2", ABR: 128},
		},
	}
	opts, err := Options(info)
	if err != nil {
		t.Fatal(err)
	}
	got := map[int]string{}
	for _, o := range opts {
		got[o.Height] = o.Kind + ":" + o.URL + "+" + o.AudioURL
	}
	want := map[int]string{
		2160: "merge:v2160vp9+a140", // VP9 over AV1 above 1080p, AAC audio
		1080: "merge:v1080avc+a140", // H.264 over VP9 up to 1080p
		720:  "hls:h720+haudio",     // HLS video-only paired with HLS audio
		360:  "mp4:m360+",           // progressive muxed MP4
	}
	for h, w := range want {
		if got[h] != w {
			t.Errorf("%dp = %q, want %q", h, got[h], w)
		}
	}
	if opts[0].Height != 2160 {
		t.Errorf("not sorted best first: %+v", opts[0])
	}
}

func TestCookieHeader(t *testing.T) {
	in := "VISITOR=abc; Domain=.youtube.com; Path=/; Secure; Expires=123; PREF=f1=5; HttpOnly"
	if got := cookieHeader(in); got != "VISITOR=abc; PREF=f1=5" {
		t.Errorf("cookieHeader = %q", got)
	}
	// TikTok: quoted value (contains '=') must be sent unquoted.
	tt := `tt_csrf_token=EG4R; Domain=.tiktok.com; Path=/; Secure; tt_chain_token="VG1YLZ3p=="; Domain=.tiktok.com`
	if got := cookieHeader(tt); got != "tt_csrf_token=EG4R; tt_chain_token=VG1YLZ3p==" {
		t.Errorf("cookieHeader(tiktok) = %q", got)
	}
}

func TestFriendlyError(t *testing.T) {
	cases := map[string]string{
		"ERROR: [youtube] abc: Private video. Sign in if you've been granted access":                       "Private video",
		"ERROR: [youtube] abc: Sign in to confirm your age.":                                               "Age-restricted",
		"ERROR: [Instagram] xyz: Requested content is not available, rate-limit reached or login required": "rate-limited",
		"ERROR: Unsupported URL: https://x.example/":                                                       "not supported",
		"ERROR: [generic] weird thing happened; please report this issue":                                  "weird thing happened",
	}
	for in, want := range cases {
		if got := FriendlyError(in, "YouTube"); !contains(got, want) {
			t.Errorf("FriendlyError(%q) = %q, want ~%q", in, got, want)
		}
	}
}

func contains(s, sub string) bool {
	return len(sub) == 0 || (len(s) >= len(sub) && indexOf(s, sub) >= 0)
}

func indexOf(s, sub string) int {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return i
		}
	}
	return -1
}

func TestAudioOptionsFromRealJSON(t *testing.T) {
	path := os.Getenv("DV_YTDLP_JSON")
	if path == "" {
		t.Skip("DV_YTDLP_JSON not set")
	}
	b, _ := os.ReadFile(path)
	info, err := Parse(b)
	if err != nil {
		t.Fatal(err)
	}
	for _, o := range AudioOptions(info) {
		t.Logf("%-32s %s/%s size=%d hls=%v", o.Label, o.AudioFormat, o.AudioQuality, o.Size, o.SourceHLS)
	}
	t.Logf("basic: %+v", MusicBasic(info))
}
