package instagram

import (
	"errors"
	"os"
	"strings"
	"testing"
)

func TestShortcodeAndID(t *testing.T) {
	for u, want := range map[string]string{
		"https://www.instagram.com/p/DeC7YkZJsF0/":          "DeC7YkZJsF0",
		"https://www.instagram.com/reels/Dd97r5jMhUj/":      "Dd97r5jMhUj",
		"https://instagram.com/reel/Abc_def-123/?igsh=xyz":  "Abc_def-123",
		"https://www.instagram.com/someuser/p/DeC7YkZJsF0/": "DeC7YkZJsF0",
	} {
		if got, err := Shortcode(u); err != nil || got != want {
			t.Errorf("Shortcode(%s) = %q, %v", u, got, err)
		}
	}
	if _, err := Shortcode("https://www.instagram.com/stories/user/123/"); !errors.Is(err, ErrNotPost) {
		t.Errorf("story link should be ErrNotPost, got %v", err)
	}
	// Values confirmed by Instagram's response ("pk").
	if MediaID("DeC7YkZJsF0") != "4000020592146694516" || MediaID("Ddcpb5hJAte") != "3989245607035538270" {
		t.Errorf("MediaID mismatch: %s %s", MediaID("DeC7YkZJsF0"), MediaID("Ddcpb5hJAte"))
	}
}

func TestParseReel(t *testing.T) {
	b, err := os.ReadFile("testdata/DeC7YkZJsF0.json")
	if err != nil {
		t.Fatal(err)
	}
	r, err := Parse(b, "")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(r.Title, "@example_store") || r.Thumbnail == "" {
		t.Errorf("title=%q thumb=%q", r.Title, r.Thumbnail)
	}
	for _, o := range r.Options {
		t.Logf("%-32s kind=%-5s h=%d size=%d audio=%v", o.Label, o.Kind, o.Height, o.Size, o.AudioURL != "")
	}
	first := r.Options[0]
	if first.Kind != "merge" || first.Height != 1440 || first.AudioURL == "" || first.DurationSec < 23 {
		t.Errorf("best option = %+v", first)
	}
	if len(r.AudioOptions) != 2 || r.AudioOptions[0].AudioQuality != "copy" || r.Music.Duration < 23 {
		t.Errorf("audio options = %+v music=%+v", r.AudioOptions, r.Music)
	}
	last := r.Options[len(r.Options)-1]
	if last.Kind != "mp4" || !strings.Contains(last.Label, "H.264") {
		t.Errorf("compat option missing: %+v", last)
	}
}

func TestParsePhoto(t *testing.T) {
	b, _ := os.ReadFile("testdata/Ddcpb5hJAte.json")
	r, err := Parse(b, "")
	if err != nil {
		t.Fatal(err)
	}
	if len(r.Options) != 1 || r.Options[0].Kind != "image" || r.Options[0].Label != "Photo · 1720×2293" {
		t.Errorf("options = %+v", r.Options)
	}
}

func TestParseUnavailable(t *testing.T) {
	_, err := Parse([]byte(`{"data":{"xig_polaris_media":null},"extensions":{"is_final":true}}`), "")
	if !errors.Is(err, ErrUnavailable) {
		t.Errorf("err = %v", err)
	}
	if _, err := Parse([]byte(`<!DOCTYPE html><html>`), ""); err == nil {
		t.Error("HTML should fail")
	}
}

func TestParseCarousel(t *testing.T) {
	body := `{"data":{"xig_polaris_media":{"if_not_gated_logged_out":{
	  "media_type":8,"code":"CAR","user":{"username":"u"},
	  "image_versions2":{"candidates":[{"url":"https://cdn/cover.jpg"}]},
	  "carousel_media":[
	    {"media_type":1,"original_width":1080,"original_height":1350,
	     "image_versions2":{"candidates":[{"url":"https://cdn/1.jpg","width":1080,"height":1350}]}},
	    {"media_type":2,"original_width":720,"original_height":1280,
	     "image_versions2":{"candidates":[{"url":"https://cdn/2t.jpg"}]},
	     "video_versions":[{"url":"https://cdn/2.mp4","type":101}]}
	  ]}}}}`
	r, err := Parse([]byte(body), "")
	if err != nil {
		t.Fatal(err)
	}
	if len(r.Options) != 2 {
		t.Fatalf("want one option per item, got %+v", r.Options)
	}
	a, b := r.Options[0], r.Options[1]
	if a.Item != 1 || a.ItemCount != 2 || a.Kind != "image" || a.ItemThumb != "https://cdn/1.jpg" {
		t.Errorf("item 1 = %+v", a)
	}
	if b.Item != 2 || b.Kind != "mp4" || b.ItemThumb != "https://cdn/2t.jpg" || !strings.HasPrefix(b.Label, "2/2 · ") {
		t.Errorf("item 2 = %+v", b)
	}
}

func TestStoryAndLoggedIn(t *testing.T) {
	q, err := NewQuery("https://www.instagram.com/stories/someone.br/3400000000000000001/?igsh=x")
	if err != nil || q.Kind != "story" || q.Username != "someone.br" || q.StoryPK != "3400000000000000001" {
		t.Fatalf("story query = %+v, %v", q, err)
	}
	if q, _ := NewQuery("https://www.instagram.com/reel/DeC7YkZJsF0/?stkn=abc"); q.Kind != "post" || q.MediaID != "4000020592146694516" {
		t.Errorf("post query = %+v", q)
	}

	stories := `{"reels":{"123":{"items":[
	  {"pk":3400000000000000000,"id":"3400000000000000000_123","media_type":1,"user":{"username":"someone.br"},
	   "image_versions2":{"candidates":[{"url":"https://cdn/a.jpg","width":1080,"height":1920}]}},
	  {"pk":"3400000000000000001","id":"3400000000000000001_123","media_type":2,"user":{"username":"someone.br"},
	   "image_versions2":{"candidates":[{"url":"https://cdn/b.jpg"}]},"video_versions":[{"url":"https://cdn/b.mp4"}]}]}}}`
	r, err := Parse([]byte(stories), "3400000000000000001")
	if err != nil || len(r.Options) != 1 || r.Options[0].URL != "https://cdn/b.mp4" {
		t.Fatalf("story = %+v, %v", r, err)
	}
	if _, err := Parse([]byte(stories), "999"); !errors.Is(err, ErrNotFound) {
		t.Errorf("missing story err = %v", err)
	}

	items := `{"items":[{"pk":"1","code":"ABC","media_type":1,"user":{"username":"u"},"original_width":1080,"original_height":1080,
	  "image_versions2":{"candidates":[{"url":"https://cdn/p.jpg"}]}}],"status":"ok"}`
	if r, err := Parse([]byte(items), ""); err != nil || r.Options[0].Kind != "image" {
		t.Errorf("media info = %+v, %v", r, err)
	}
	if _, err := Parse([]byte(`{"message":"login_required","status":"fail"}`), ""); Code(err) != "login" {
		t.Errorf("login err = %v", err)
	}
	if _, err := Parse([]byte(`{"message":"Media not found or unavailable","status":"fail"}`), ""); Code(err) != "notfound" {
		t.Errorf("notfound err = %v", err)
	}
	if _, err := Parse([]byte(`{"data":{"xig_polaris_media":null}}`), ""); Code(err) != "unavailable" {
		t.Errorf("unavailable err = %v", err)
	}
}
