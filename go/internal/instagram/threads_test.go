package instagram

import (
	"encoding/json"
	"errors"
	"os"
	"regexp"
	"testing"
)

func TestNewThreadsQuery(t *testing.T) {
	for u, want := range map[string]string{
		"https://www.threads.com/@example_user/post/DTvidE0xAmp?xmt=abc": "https://www.threads.com/@example_user/post/DTvidE0xAmp",
		"https://www.threads.net/@a.b_c/post/DTvidE0xAmp/media":          "https://www.threads.com/@a.b_c/post/DTvidE0xAmp",
		"https://threads.net/t/DTvidE0xAmp":                              "https://www.threads.com/t/DTvidE0xAmp",
	} {
		q, err := NewThreadsQuery(u)
		if err != nil || q.Shortcode != "DTvidE0xAmp" || q.Referer != want || q.Kind != "threads" {
			t.Errorf("NewThreadsQuery(%s) = %+v, %v", u, q, err)
		}
	}
	if q, err := NewThreadsQuery("https://www.threads.com/share/_6Jr3daHS/"); err != nil || q.Shortcode != "" ||
		q.Referer != "https://www.threads.com/share/_6Jr3daHS/" {
		t.Errorf("share link: %+v, %v", q, err)
	}
	if _, err := NewThreadsQuery("https://www.threads.com/@example_user"); !errors.Is(err, ErrNotThreadsPost) {
		t.Errorf("profile link should be ErrNotThreadsPost, got %v", err)
	}
}

func TestParseThreadsVideo(t *testing.T) {
	b, err := os.ReadFile("testdata/threads_post.html")
	if err != nil {
		t.Fatal(err)
	}
	r, err := ParseThreads(b, "DTvidE0xAmp")
	if err != nil {
		t.Fatal(err)
	}
	if r.Title != "@example_user - Sunset at the beach" || r.Thumbnail == "" ||
		r.PageURL != "https://www.threads.com/@example_user/post/DTvidE0xAmp" {
		t.Errorf("title=%q thumb=%q page=%q", r.Title, r.Thumbnail, r.PageURL)
	}
	if len(r.Options) < 2 || r.Options[0].Kind != "merge" || r.Options[0].Height != 1080 || r.Options[0].AudioURL == "" {
		t.Fatalf("options: %+v", r.Options)
	}
	if last := r.Options[len(r.Options)-1]; last.Kind != "mp4" {
		t.Errorf("last option should be the progressive MP4: %+v", last)
	}
	for _, o := range r.Options {
		if o.Source != "threads" || o.Group != r.PageURL {
			t.Errorf("source=%q group=%q", o.Source, o.Group)
		}
	}
	if len(r.AudioOptions) != 2 || r.AudioOptions[0].Source != "threads" || r.Music.Duration <= 0 {
		t.Errorf("audio: %+v music=%+v", r.AudioOptions, r.Music)
	}
}

func TestParseThreadsQuoteAndScripts(t *testing.T) {
	b, err := os.ReadFile("testdata/threads_post.html")
	if err != nil {
		t.Fatal(err)
	}
	// What the WebView sends: the data-sjs scripts as a JSON array.
	var scripts []string
	for _, m := range regexp.MustCompile(`(?s)<script[^>]*>(.*?)</script>`).FindAllStringSubmatch(string(b), -1) {
		scripts = append(scripts, m[1])
	}
	arr, _ := json.Marshal(scripts)

	// Text post quoting the video: the quoted media is offered.
	r, err := ParseThreads(arr, "DTtextOnly1")
	if err != nil || len(r.Options) == 0 || r.Options[0].Kind != "merge" {
		t.Fatalf("quote: %v %+v", err, r)
	}
	if _, err := ParseThreads(arr, "DTrelated01"); !errors.Is(err, ErrNoMedia) {
		t.Errorf("text-only post: want ErrNoMedia, got %v", err)
	}
	// Share link: the code comes from the URL the WebView landed on.
	page, _ := json.Marshal(map[string]any{
		"url": "https://www.threads.com/@example_user/post/DTvidE0xAmp?xmt=x&slof=1", "scripts": scripts,
	})
	if r, err := ParseThreads(page, ""); err != nil || r.PageURL != "https://www.threads.com/@example_user/post/DTvidE0xAmp" {
		t.Errorf("share page: %v %+v", err, r)
	}
	page, _ = json.Marshal(map[string]any{"url": "https://www.threads.com/@example_user/post/DTvidE0xAmp", "html": string(b)})
	if _, err := ParseThreads(page, ""); err != nil {
		t.Errorf("share page html: %v", err)
	}
	// GraphQL response fetched by the page, streamed as JSON lines.
	var post any
	for _, v := range decodeAll(scripts[1]) {
		post = v
	}
	line, _ := json.Marshal(post)
	page, _ = json.Marshal(map[string]any{
		"url":     "https://www.threads.com/@example_user/post/DTvidE0xAmp",
		"scripts": []string{"for (;;);" + `{"data":{"x":1}}` + "\n" + string(line) + "\n" + `{"extensions":{}}`},
	})
	if _, err := ParseThreads(page, ""); err != nil {
		t.Errorf("streamed graphql: %v", err)
	}
	if _, err := ParseThreads([]byte("<html>login</html>"), "DTvidE0xAmp"); !errors.Is(err, ErrUnavailable) {
		t.Errorf("no data: want ErrUnavailable, got %v", err)
	}
}
