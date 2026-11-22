package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/downvid/core/internal/instagram"
	"github.com/downvid/core/internal/jobs"
	"github.com/downvid/core/internal/logx"
	"github.com/downvid/core/internal/media"
	"github.com/downvid/core/internal/music"
	"github.com/downvid/core/internal/netx"
	"github.com/downvid/core/internal/scan"
	"github.com/downvid/core/internal/ytdlp"
)

// The whole API is call(method, request JSON) -> response JSON, reached from
// Kotlin through one JNI function (jni_android.go). Responses are
// {"ok":true,"data":...} or {"ok":false,"error":"...", ...extra fields}.
// Blocking methods (network, await) must be called off the main thread.

type handler func(req []byte) (any, error)

var handlers = map[string]handler{
	"version":      func([]byte) (any, error) { return Version, nil },
	"init":         apiInit,
	"settings":     apiSettings,
	"probe":        apiProbe,
	"download":     apiDownload,
	"await":        apiAwait,
	"status":       func([]byte) (any, error) { return jobs.Snapshot(), nil },
	"pause":        idCall(jobs.Pause),
	"resume":       idCall(jobs.Resume),
	"cancel":       idCall(jobs.Cancel),
	"remove":       idCall(func(id int64) bool { jobs.Remove(id); return true }),
	"replace":      apiReplace,
	"markSaved":    apiMarkSaved,
	"saveFailed":   apiSaveFailed,
	"ytdlp":        apiYtdlp,
	"musicMeta":    apiMusicMeta,
	"musicLink":    apiMusicLink,
	"musicPick":    apiMusicPick,
	"instaQuery":   apiInstaQuery,
	"instaParse":   apiInstaParse,
	"threadsQuery": apiThreadsQuery,
	"threadsParse": apiThreadsParse,
}

// apiError is a failure with extra response fields (e.g. "unsupported").
type apiError struct {
	msg   string
	extra map[string]any
}

func (e *apiError) Error() string { return e.msg }

func call(method string, req []byte) (out []byte) {
	defer func() {
		if r := recover(); r != nil {
			logx.Errorf("%s: panic: %v", method, r)
			out = response(nil, fmt.Errorf("internal error: %v", r))
		}
	}()
	h := handlers[method]
	if h == nil {
		return response(nil, fmt.Errorf("unknown method %q", method))
	}
	return response(h(req))
}

func response(data any, err error) []byte {
	var v any
	if err != nil {
		m := map[string]any{"ok": false, "error": err.Error()}
		var ae *apiError
		if errors.As(err, &ae) {
			for k, x := range ae.extra {
				m[k] = x
			}
		}
		v = m
	} else {
		v = map[string]any{"ok": true, "data": data}
	}
	b, merr := json.Marshal(v)
	if merr != nil {
		b, _ = json.Marshal(map[string]any{"ok": false, "error": merr.Error()})
	}
	return b
}

func decode(req []byte, v any) error {
	if len(req) == 0 {
		return nil
	}
	if err := json.Unmarshal(req, v); err != nil {
		return fmt.Errorf("invalid request: %w", err)
	}
	return nil
}

func idCall(f func(int64) bool) handler {
	return func(req []byte) (any, error) {
		var r struct {
			ID int64 `json:"id"`
		}
		if err := decode(req, &r); err != nil {
			return nil, err
		}
		return f(r.ID), nil
	}
}

// init loads the persisted queue and resumes interrupted downloads.
// {"dir","ffmpeg":{"path","libraryPath"},"maxConcurrent","connections"}
func apiInit(req []byte) (any, error) {
	var cfg struct {
		Dir           string       `json:"dir"`
		FFmpeg        media.FFmpeg `json:"ffmpeg"`
		MaxConcurrent int          `json:"maxConcurrent"`
		Connections   int          `json:"connections"`
	}
	if err := decode(req, &cfg); err != nil {
		return nil, err
	}
	if cfg.Connections > 0 {
		netx.SetConnections(cfg.Connections)
	}
	return jobs.Init(cfg.Dir, cfg.FFmpeg, cfg.MaxConcurrent)
}

// settings changes {"maxConcurrent","connections"} (0 = unchanged).
func apiSettings(req []byte) (any, error) {
	var s struct {
		MaxConcurrent int `json:"maxConcurrent"`
		Connections   int `json:"connections"`
	}
	if err := decode(req, &s); err != nil {
		return nil, err
	}
	if s.MaxConcurrent > 0 {
		jobs.SetMaxConcurrent(s.MaxConcurrent)
	}
	if s.Connections > 0 {
		netx.SetConnections(s.Connections)
	}
	return nil, nil
}

// probe scans a page / resolves candidate URLs into options (scan.Request).
func apiProbe(req []byte) (any, error) {
	var r scan.Request
	if err := decode(req, &r); err != nil {
		return nil, err
	}
	logx.Infof("probe page=%s candidates=%d skipPage=%v", r.PageURL, len(r.Candidates), r.SkipPage)
	res, err := scan.Probe(context.Background(), r)
	if err != nil {
		logx.Warnf("probe failed: %v", err)
	}
	return res, err
}

// download queues a job: jobs.Request plus "meta" (what the downloads
// screen shows) and "musicMeta" (tags of an audio file) -> {"id"}. The tags
// need their own key: jobs.Request's "meta" is shadowed by the outer one.
func apiDownload(req []byte) (any, error) {
	var r struct {
		jobs.Request
		Meta      jobs.Meta   `json:"meta"`
		MusicMeta *music.Meta `json:"musicMeta"`
	}
	if err := decode(req, &r); err != nil {
		return nil, err
	}
	r.Request.Meta = r.MusicMeta
	id, err := jobs.Start(r.Request, r.Meta, nil)
	if err != nil {
		return nil, err
	}
	return map[string]int64{"id": id}, nil
}

// await blocks until a job changes after version "since" (or timeoutMs).
func apiAwait(req []byte) (any, error) {
	var r struct {
		Since     int64 `json:"since"`
		TimeoutMs int64 `json:"timeoutMs"`
	}
	if err := decode(req, &r); err != nil {
		return nil, err
	}
	t := time.Duration(r.TimeoutMs) * time.Millisecond
	if t <= 0 || t > 5*time.Minute {
		t = 30 * time.Second
	}
	return jobs.Await(r.Since, t), nil
}

// replace gives an expired job new sources:
// {"id","url","audioUrl","headers","chunkSize"}.
func apiReplace(req []byte) (any, error) {
	var r struct {
		ID        int64        `json:"id"`
		URL       string       `json:"url"`
		AudioURL  string       `json:"audioUrl"`
		Headers   netx.Headers `json:"headers"`
		ChunkSize int64        `json:"chunkSize"`
	}
	if err := decode(req, &r); err != nil {
		return nil, err
	}
	if !jobs.Replace(r.ID, r.URL, r.AudioURL, r.Headers, r.ChunkSize) {
		return nil, errors.New("download not found")
	}
	return true, nil
}

// markSaved records where the file was published: {"id","saved":{...}}.
func apiMarkSaved(req []byte) (any, error) {
	var r struct {
		ID    int64      `json:"id"`
		Saved jobs.Saved `json:"saved"`
	}
	if err := decode(req, &r); err != nil {
		return nil, err
	}
	jobs.MarkSaved(r.ID, r.Saved)
	return true, nil
}

// saveFailed records that publishing a finished file failed: {"id","message"}.
func apiSaveFailed(req []byte) (any, error) {
	var r struct {
		ID      int64  `json:"id"`
		Message string `json:"message"`
	}
	if err := decode(req, &r); err != nil {
		return nil, err
	}
	return jobs.MarkSaveFailed(r.ID, r.Message), nil
}

type ytdlpResult struct {
	scan.Result
	Extractor    string        `json:"extractor,omitempty"`
	AudioOptions []scan.Option `json:"audioOptions,omitempty"`
	Music        music.Basic   `json:"music"`
}

// ytdlp turns `yt-dlp -J` output into options:
// {"json","stderr","exitCode","platform"}. Failures carry "unsupported".
func apiYtdlp(req []byte) (any, error) {
	var r struct {
		JSON     string `json:"json"`
		Stderr   string `json:"stderr"`
		ExitCode int    `json:"exitCode"`
		Platform string `json:"platform"`
	}
	if err := decode(req, &r); err != nil {
		return nil, err
	}
	failWith := func(msg string, unsupported bool) error {
		logx.Warnf("ytdlp: %s (unsupported=%v)", msg, unsupported)
		return &apiError{msg: msg, extra: map[string]any{"unsupported": unsupported}}
	}
	if r.ExitCode != 0 || strings.TrimSpace(r.JSON) == "" {
		return nil, failWith(ytdlp.FriendlyError(r.Stderr, r.Platform), ytdlp.IsUnsupported(r.Stderr))
	}
	info, err := ytdlp.Parse([]byte(r.JSON))
	if err != nil {
		return nil, failWith(err.Error(), false)
	}
	opts, err := ytdlp.Options(info)
	audio := ytdlp.AudioOptions(info)
	// Audio-only sources (SoundCloud, Bandcamp...) have no video options.
	if err != nil && len(audio) == 0 {
		return nil, failWith(err.Error(), errors.Is(err, ytdlp.ErrNoFormats))
	}
	for i := range opts {
		opts[i].ID = fmt.Sprintf("y%d", i)
	}
	for i := range audio {
		audio[i].ID = fmt.Sprintf("a%d", i)
	}
	logx.Infof("ytdlp: %s %q: %d video, %d audio options", info.Extractor, info.Title, len(opts), len(audio))
	return ytdlpResult{
		Result:       scan.Result{PageURL: info.WebpageURL, Title: info.Title, Thumbnail: info.Thumbnail, Options: opts},
		Extractor:    info.Extractor,
		AudioOptions: audio,
		Music:        ytdlp.MusicBasic(info),
	}, nil
}

// musicMeta resolves song metadata: {"basic","override"}.
func apiMusicMeta(req []byte) (any, error) {
	var r struct {
		Basic    music.Basic `json:"basic"`
		Override *music.Meta `json:"override"`
	}
	if err := decode(req, &r); err != nil {
		return nil, err
	}
	m := music.Resolve(context.Background(), r.Basic, r.Override)
	logx.Infof("musicMeta: %q - %q (%s %s, track %d) source=%s cover=%v", m.Artist, m.Title, m.Album, m.Year, m.Track, m.Source, m.CoverURL != "")
	return m, nil
}

// musicLink reads a Spotify/Deezer/Apple Music link: {"url"} -> {"meta","searchUrl"}.
func apiMusicLink(req []byte) (any, error) {
	var r struct {
		URL string `json:"url"`
	}
	if err := decode(req, &r); err != nil {
		return nil, err
	}
	m, err := music.FromLink(context.Background(), r.URL)
	if err != nil {
		logx.Warnf("musicLink %s: %v", r.URL, err)
		return nil, err
	}
	logx.Infof("musicLink: %q - %q (%.0fs) via %s", m.Artist, m.Title, m.Duration, m.Source)
	return map[string]any{"meta": m, "searchUrl": music.SearchURL(*m)}, nil
}

// musicPick ranks a flat YouTube Music search: {"searchJson","meta","max"}.
func apiMusicPick(req []byte) (any, error) {
	var r struct {
		SearchJSON string     `json:"searchJson"`
		Meta       music.Meta `json:"meta"`
		Max        int        `json:"max"`
	}
	if err := decode(req, &r); err != nil {
		return nil, err
	}
	if r.Max <= 0 {
		r.Max = 3
	}
	c, err := music.Candidates([]byte(r.SearchJSON), r.Meta, r.Max)
	if err != nil {
		return nil, err
	}
	return map[string]any{"candidates": c}, nil
}

func apiInstaQuery(req []byte) (any, error) {
	var r struct {
		URL string `json:"url"`
	}
	if err := decode(req, &r); err != nil {
		return nil, err
	}
	return instagram.NewQuery(r.URL)
}

// instaParse failures carry "code" (unavailable/login) for the login offer.
func apiInstaParse(req []byte) (any, error) {
	var r struct {
		Body    string `json:"body"`
		StoryPK string `json:"storyPk"`
	}
	if err := decode(req, &r); err != nil {
		return nil, err
	}
	res, err := instagram.Parse([]byte(r.Body), r.StoryPK)
	if err != nil {
		logx.Warnf("instaParse: %v (%d bytes)", err, len(r.Body))
		return nil, &apiError{msg: err.Error(), extra: map[string]any{"code": instagram.Code(err)}}
	}
	logx.Infof("instaParse: %q by @%s: %d options", res.Title, res.Uploader, len(res.Options))
	return res, nil
}

func apiThreadsQuery(req []byte) (any, error) {
	var r struct {
		URL string `json:"url"`
	}
	if err := decode(req, &r); err != nil {
		return nil, err
	}
	return instagram.NewThreadsQuery(r.URL)
}

func apiThreadsParse(req []byte) (any, error) {
	var r struct {
		Body      string `json:"body"`
		Shortcode string `json:"shortcode"`
	}
	if err := decode(req, &r); err != nil {
		return nil, err
	}
	res, err := instagram.ParseThreads([]byte(r.Body), r.Shortcode)
	if err != nil {
		logx.Warnf("threadsParse: %v (%d bytes)", err, len(r.Body))
		return nil, &apiError{msg: err.Error(), extra: map[string]any{"code": instagram.Code(err)}}
	}
	logx.Infof("threadsParse: %q by @%s: %d options", res.Title, res.Uploader, len(res.Options))
	return res, nil
}
