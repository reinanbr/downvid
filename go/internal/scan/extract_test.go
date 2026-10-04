package scan

import (
	"net/url"
	"slices"
	"testing"
)

func TestExtractPage(t *testing.T) {
	base, _ := url.Parse("https://site.example/watch/123")
	doc := `<html><head>
<title>Fallback title</title>
<meta property="og:title" content="Meu &amp; vídeo">
<meta property="og:image" content="/thumb.jpg">
<meta property="og:video:secure_url" content="https://cdn.example/og.mp4">
</head><body>
<video src="/media/direct.mp4" poster="/p.jpg"><source src="clip.webm" type="video/webm"></video>
<iframe src="//player.example/embed/9"></iframe>
<iframe src="https://googleads.g.doubleclick.net/x"></iframe>
<script>
var cfg = {"hls":"https:\/\/stream.example\/v\/master.m3u8?token=abc&exp=1","n":1};
player.setup({file: '/hls/rel/index.m3u8'});
var enc = "https%3A%2F%2Fenc.example%2Fa%2Fb.m3u8%3Fx%3D1";
var uni = "https://uni.example/v.mp4";
</script>
<a href="https://site.example/page.html">not media</a>
</body></html>`

	info := ExtractPage(doc, base)
	if info.Title != "Meu & vídeo" {
		t.Errorf("title = %q", info.Title)
	}
	if info.Thumbnail != "https://site.example/thumb.jpg" {
		t.Errorf("thumb = %q", info.Thumbnail)
	}
	var got []string
	for _, c := range info.Candidates {
		got = append(got, c.URL)
	}
	want := []string{
		"https://cdn.example/og.mp4",
		"https://site.example/media/direct.mp4",
		"https://site.example/watch/clip.webm", // filtered later by resolve()
		"https://stream.example/v/master.m3u8?token=abc&exp=1",
		"https://site.example/hls/rel/index.m3u8",
		"https://enc.example/a/b.m3u8?x=1",
		"https://uni.example/v.mp4",
	}
	for _, w := range want {
		if !slices.Contains(got, w) {
			t.Errorf("missing candidate %s\ngot: %v", w, got)
		}
	}
	if slices.Contains(got, "https://site.example/page.html") {
		t.Errorf("non-media link extracted")
	}
	if len(info.Iframes) != 2 || info.Iframes[0] != "https://player.example/embed/9" {
		t.Errorf("iframes = %v", info.Iframes)
	}
	if !skipIframe(info.Iframes[1]) {
		t.Errorf("ad iframe not skipped")
	}
}

func TestLooksLikeMedia(t *testing.T) {
	yes := []string{
		"https://a/b/master.m3u8", "https://a/v.mp4?x=1", "https://a/p?format=m3u8",
		"https://a/x.m3u8/seg", "https://a/V.MP4",
	}
	no := []string{"https://a/app.js", "https://a/img.png", "https://a/mp4-guide.html"}
	for _, u := range yes {
		if !LooksLikeMedia(u) {
			t.Errorf("LooksLikeMedia(%s) = false", u)
		}
	}
	for _, u := range no {
		if LooksLikeMedia(u) {
			t.Errorf("LooksLikeMedia(%s) = true", u)
		}
	}
}
