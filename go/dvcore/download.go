package main

/*
#include <stdint.h>
typedef void (*dv_event_cb)(int64_t id, char* json);
*/
import "C"

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/downvid/core/internal/instagram"
	"github.com/downvid/core/internal/jobs"
	"github.com/downvid/core/internal/logx"
	"github.com/downvid/core/internal/music"
	"github.com/downvid/core/internal/netx"
	"github.com/downvid/core/internal/scan"
	"github.com/downvid/core/internal/ytdlp"
)

// DV_Probe scans a page / resolves candidate URLs into download options.
// Blocking (network): call it off the UI thread. Input: scan.Request JSON.
// Output: {"ok":true,"data":scan.Result} or {"ok":false,"error":...}.
//
//export DV_Probe
func DV_Probe(reqJSON *C.char) (out *C.char) {
	defer func() {
		if r := recover(); r != nil {
			logx.Errorf("DV_Probe panic: %v", r)
			out = fail(fmt.Errorf("internal error: %v", r))
		}
	}()
	var req scan.Request
	if err := json.Unmarshal([]byte(goString(reqJSON)), &req); err != nil {
		return fail(fmt.Errorf("invalid request: %w", err))
	}
	logx.Infof("DV_Probe page=%s candidates=%d skipPage=%v", req.PageURL, len(req.Candidates), req.SkipPage)
	res, err := scan.Probe(context.Background(), req)
	if err != nil {
		logx.Warnf("DV_Probe failed: %v", err)
		return fail(err)
	}
	return ok(res)
}

// DV_Download queues a download (jobs.Request JSON plus optional "meta":
// {"title","pageUrl","optionKey","thumbnail"}). Events (jobs.Event JSON)
// arrive on cb tagged with the job id, until DV_Detach or a terminal event.
// Returns {"ok":true,"data":{"id":N}} immediately.
//
//export DV_Download
func DV_Download(reqJSON *C.char, cb C.dv_event_cb) *C.char {
	var req struct {
		jobs.Request
		Meta jobs.Meta `json:"meta"`
	}
	if err := json.Unmarshal([]byte(goString(reqJSON)), &req); err != nil {
		return fail(fmt.Errorf("invalid request: %w", err))
	}
	var id int64
	idReady := make(chan struct{})
	id, err := jobs.Start(req.Request, req.Meta, func(ev jobs.Event) {
		<-idReady
		emit(cb, C.int64_t(id), ev)
	})
	close(idReady)
	if err != nil {
		return fail(err)
	}
	return ok(map[string]int64{"id": id})
}

// DV_Detach stops event delivery for a download (the UI went away); the
// download continues and stays visible through the job status.
//
//export DV_Detach
func DV_Detach(id C.int64_t) {
	jobs.Detach(int64(id))
}

// DV_Cancel cancels a running download. Returns 1 if it was running.
//
//export DV_Cancel
func DV_Cancel(id C.int64_t) C.int32_t {
	if jobs.Cancel(int64(id)) {
		return 1
	}
	return 0
}

// DV_Status returns {"ok":true,"data":[jobs.Status...]}.
//
//export DV_Status
func DV_Status() *C.char {
	return ok(jobs.Snapshot())
}

type ytdlpRequest struct {
	JSON     string `json:"json"`     // stdout of yt-dlp -J
	Stderr   string `json:"stderr"`   // for error messages
	ExitCode int    `json:"exitCode"` // yt-dlp exit status
	Platform string `json:"platform"` // label used in messages
}

type ytdlpResult struct {
	scan.Result
	Extractor    string        `json:"extractor,omitempty"`
	AudioOptions []scan.Option `json:"audioOptions,omitempty"`
	Music        music.Basic   `json:"music"`
}

// DV_Ytdlp turns `yt-dlp -J` output into download options, or into a
// friendly error ({"ok":false,"error":...,"unsupported":bool}).
//
//export DV_Ytdlp
func DV_Ytdlp(reqJSON *C.char) *C.char {
	var req ytdlpRequest
	if err := json.Unmarshal([]byte(goString(reqJSON)), &req); err != nil {
		return fail(fmt.Errorf("invalid request: %w", err))
	}
	failWith := func(msg string, unsupported bool) *C.char {
		logx.Warnf("DV_Ytdlp: %s (unsupported=%v)", msg, unsupported)
		return cJSON(map[string]any{"ok": false, "error": msg, "unsupported": unsupported})
	}
	if req.ExitCode != 0 || strings.TrimSpace(req.JSON) == "" {
		return failWith(ytdlp.FriendlyError(req.Stderr, req.Platform), ytdlp.IsUnsupported(req.Stderr))
	}
	info, err := ytdlp.Parse([]byte(req.JSON))
	if err != nil {
		return failWith(err.Error(), false)
	}
	opts, err := ytdlp.Options(info)
	audio := ytdlp.AudioOptions(info)
	// Audio-only sources (SoundCloud, Bandcamp...) have no video options.
	if err != nil && len(audio) == 0 {
		return failWith(err.Error(), errors.Is(err, ytdlp.ErrNoFormats))
	}
	for i := range opts {
		opts[i].ID = fmt.Sprintf("y%d", i)
	}
	for i := range audio {
		audio[i].ID = fmt.Sprintf("a%d", i)
	}
	logx.Infof("DV_Ytdlp: %s %q: %d video, %d audio options", info.Extractor, info.Title, len(opts), len(audio))
	return ok(ytdlpResult{
		Result:       scan.Result{PageURL: info.WebpageURL, Title: info.Title, Thumbnail: info.Thumbnail, Options: opts},
		Extractor:    info.Extractor,
		AudioOptions: audio,
		Music:        ytdlp.MusicBasic(info),
	})
}

// DV_MusicMeta resolves song metadata (Deezer/iTunes match, cover).
// Input: {"basic": music.Basic, "override": music.Meta|null}. Blocking.
//
//export DV_MusicMeta
func DV_MusicMeta(reqJSON *C.char) *C.char {
	var req struct {
		Basic    music.Basic `json:"basic"`
		Override *music.Meta `json:"override"`
	}
	if err := json.Unmarshal([]byte(goString(reqJSON)), &req); err != nil {
		return fail(fmt.Errorf("invalid request: %w", err))
	}
	m := music.Resolve(context.Background(), req.Basic, req.Override)
	logx.Infof("DV_MusicMeta: %q - %q (%s %s, track %d) source=%s cover=%v", m.Artist, m.Title, m.Album, m.Year, m.Track, m.Source, m.CoverURL != "")
	return ok(m)
}

// DV_MusicLink reads a Spotify/Deezer/Apple Music track link and returns its
// metadata plus the YouTube Music search URL used to find the audio.
// Input: {"url"}. Output: {"meta", "searchUrl"}. Blocking.
//
//export DV_MusicLink
func DV_MusicLink(reqJSON *C.char) *C.char {
	var req struct {
		URL string `json:"url"`
	}
	if err := json.Unmarshal([]byte(goString(reqJSON)), &req); err != nil {
		return fail(fmt.Errorf("invalid request: %w", err))
	}
	m, err := music.FromLink(context.Background(), req.URL)
	if err != nil {
		logx.Warnf("DV_MusicLink %s: %v", req.URL, err)
		return fail(err)
	}
	logx.Infof("DV_MusicLink: %q - %q (%.0fs) via %s", m.Artist, m.Title, m.Duration, m.Source)
	return ok(map[string]any{"meta": m, "searchUrl": music.SearchURL(*m)})
}

// DV_MusicPick ranks a flat YouTube Music search for the wanted song.
// Input: {"searchJson", "meta", "max"}. Output: {"candidates": [url...]}.
//
//export DV_MusicPick
func DV_MusicPick(reqJSON *C.char) *C.char {
	var req struct {
		SearchJSON string     `json:"searchJson"`
		Meta       music.Meta `json:"meta"`
		Max        int        `json:"max"`
	}
	if err := json.Unmarshal([]byte(goString(reqJSON)), &req); err != nil {
		return fail(fmt.Errorf("invalid request: %w", err))
	}
	if req.Max <= 0 {
		req.Max = 3
	}
	c, err := music.Candidates([]byte(req.SearchJSON), req.Meta, req.Max)
	if err != nil {
		return fail(err)
	}
	return ok(map[string]any{"candidates": c})
}

// DV_InstaQuery returns the parameters of Instagram's logged-out post query
// for a post/reel URL: {"shortcode","appId","docId","friendlyName","variables","referer"}.
//
//export DV_InstaQuery
func DV_InstaQuery(reqJSON *C.char) *C.char {
	var req struct {
		URL string `json:"url"`
	}
	if err := json.Unmarshal([]byte(goString(reqJSON)), &req); err != nil {
		return fail(fmt.Errorf("invalid request: %w", err))
	}
	q, err := instagram.NewQuery(req.URL)
	if err != nil {
		return fail(err)
	}
	return ok(q)
}

// DV_InstaParse converts the query's raw response into options
// ({"title","thumbnail","options",...}) or a friendly error.
//
//export DV_InstaParse
func DV_InstaParse(reqJSON *C.char) *C.char {
	var req struct {
		Body    string `json:"body"`
		StoryPK string `json:"storyPk"`
	}
	if err := json.Unmarshal([]byte(goString(reqJSON)), &req); err != nil {
		return fail(fmt.Errorf("invalid request: %w", err))
	}
	r, err := instagram.Parse([]byte(req.Body), req.StoryPK)
	if err != nil {
		logx.Warnf("DV_InstaParse: %v (%d bytes)", err, len(req.Body))
		// "code" lets the app offer the login ("unavailable") or ask to log
		// in again ("login").
		return cJSON(map[string]any{"ok": false, "error": err.Error(), "code": instagram.Code(err)})
	}
	logx.Infof("DV_InstaParse: %q by @%s: %d options", r.Title, r.Uploader, len(r.Options))
	return ok(r)
}

// DV_Pause / DV_Resume / DV_Remove act on a job; return 1 on success.
//
//export DV_Pause
func DV_Pause(id C.int64_t) C.int32_t { return b2i(jobs.Pause(int64(id))) }

//export DV_Resume
func DV_Resume(id C.int64_t) C.int32_t { return b2i(jobs.Resume(int64(id))) }

//export DV_Remove
func DV_Remove(id C.int64_t) { jobs.Remove(int64(id)) }

// DV_Replace gives an expired job new source URLs (after re-extraction):
// {"id","url","audioUrl","headers","chunkSize"}.
//
//export DV_Replace
func DV_Replace(reqJSON *C.char) *C.char {
	var req struct {
		ID        int64        `json:"id"`
		URL       string       `json:"url"`
		AudioURL  string       `json:"audioUrl"`
		Headers   netx.Headers `json:"headers"`
		ChunkSize int64        `json:"chunkSize"`
	}
	if err := json.Unmarshal([]byte(goString(reqJSON)), &req); err != nil {
		return fail(fmt.Errorf("invalid request: %w", err))
	}
	if !jobs.Replace(req.ID, req.URL, req.AudioURL, req.Headers, req.ChunkSize) {
		return fail(errors.New("download not found"))
	}
	return ok(nil)
}

//export DV_SetMaxConcurrent
func DV_SetMaxConcurrent(n C.int32_t) { jobs.SetMaxConcurrent(int(n)) }

func b2i(b bool) C.int32_t {
	if b {
		return 1
	}
	return 0
}
