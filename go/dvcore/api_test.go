package main

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"

	"github.com/downvid/core/internal/jobs"
)

func decodeResp(t *testing.T, b []byte) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatalf("bad response %q: %v", b, err)
	}
	return m
}

func TestCallDispatch(t *testing.T) {
	if m := decodeResp(t, call("version", nil)); m["ok"] != true || m["data"] != Version {
		t.Errorf("version: %v", m)
	}
	if m := decodeResp(t, call("nope", nil)); m["ok"] != false || !strings.Contains(m["error"].(string), "unknown method") {
		t.Errorf("unknown method: %v", m)
	}
	if m := decodeResp(t, call("probe", []byte("{"))); m["ok"] != false {
		t.Errorf("invalid JSON must fail: %v", m)
	}
	// yt-dlp failures carry the extra "unsupported" field.
	m := decodeResp(t, call("ytdlp", []byte(`{"exitCode":1,"stderr":"ERROR: Unsupported URL: https://x"}`)))
	if m["ok"] != false || m["unsupported"] != true {
		t.Errorf("ytdlp failure: %v", m)
	}
	// Emoji survive the JSON round trip (the reason for byte arrays in JNI).
	m = decodeResp(t, call("await", []byte(`{"since":0,"timeoutMs":10}`)))
	if m["ok"] != true {
		t.Errorf("await: %v", m)
	}
}

// The audio tags travel as "musicMeta" and reach the job request, while
// "meta" stays the job's display info.
func TestDownloadKeepsMusicTags(t *testing.T) {
	dir := t.TempDir()
	m := decodeResp(t, call("download", []byte(`{"kind":"audio","url":"http://127.0.0.1:1/x","outPath":"`+dir+`/a.m4a",
		"meta":{"title":"Shown title"},"musicMeta":{"title":"Song","artist":"Artist"}}`)))
	if m["ok"] != true {
		t.Fatalf("download: %v", m)
	}
	id := int64(m["data"].(map[string]any)["id"].(float64))
	defer call("remove", []byte(fmt.Sprintf(`{"id":%d}`, id)))
	for _, s := range jobs.Snapshot() {
		if s.ID == id && s.Title != "Shown title" {
			t.Errorf("display title = %q", s.Title)
		}
	}
	if got := jobs.RequestOf(id); got.Meta == nil || got.Meta.Artist != "Artist" {
		t.Errorf("music tags lost: %+v", got.Meta)
	}
}
