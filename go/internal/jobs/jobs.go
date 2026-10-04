// Package jobs runs downloads in the background and reports their progress.
package jobs

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"github.com/downvid/core/internal/hls"
	"github.com/downvid/core/internal/logx"
	"github.com/downvid/core/internal/media"
	"github.com/downvid/core/internal/music"
	"github.com/downvid/core/internal/netx"
)

// Request is the DV_Download input.
type Request struct {
	Kind     string       `json:"kind"` // mp4 | hls | merge | audio | image
	URL      string       `json:"url"`
	AudioURL string       `json:"audioUrl,omitempty"`
	Headers  netx.Headers `json:"headers"`
	OutPath  string       `json:"outPath"`
	TmpDir   string       `json:"tmpDir"`
	// ChunkSize caps each Range request (yt-dlp http_chunk_size).
	ChunkSize int64 `json:"chunkSize,omitempty"`
	// FFmpeg is used by "merge" (separate video/audio or non-MP4 containers)
	// and "audio".
	FFmpeg media.FFmpeg `json:"ffmpeg"`

	// "audio" jobs: output format/quality and the tags to write.
	AudioFormat  string      `json:"audioFormat,omitempty"`  // m4a | mp3
	AudioQuality string      `json:"audioQuality,omitempty"` // copy | v0 | 320 | aac256
	SourceHLS    bool        `json:"sourceHls,omitempty"`
	Meta         *music.Meta `json:"meta,omitempty"`
}

// Event is sent to the host (JSON). Type: progress | done | error | canceled.
type Event struct {
	Type          string  `json:"type"`
	Bytes         int64   `json:"bytes,omitempty"`
	Total         int64   `json:"total,omitempty"` // -1 / 0 unknown
	TotalEstimate bool    `json:"totalEstimate,omitempty"`
	Percent       float64 `json:"percent,omitempty"`
	SpeedBps      float64 `json:"speedBps,omitempty"`
	SegmentsDone  int     `json:"segmentsDone,omitempty"`
	SegmentsTotal int     `json:"segmentsTotal,omitempty"`
	Phase         string  `json:"phase,omitempty"` // downloading | muxing

	Path       string `json:"path,omitempty"`
	Size       int64  `json:"size,omitempty"`
	DurationMs uint64 `json:"durationMs,omitempty"`
	Warning    string `json:"warning,omitempty"`
	Message    string `json:"message,omitempty"`
}

// run performs one download attempt, emitting progress events, and returns
// its outcome; the manager turns it into the job state.
func run(ctx context.Context, id int64, req Request, emit func(Event)) (hls.Result, error) {
	started := time.Now()
	logx.Infof("job %d: start kind=%s url=%s referer=%s", id, req.Kind, req.URL, req.Headers.Referer)
	if err := os.MkdirAll(filepath.Dir(req.OutPath), 0o755); err != nil {
		return hls.Result{}, err
	}

	// Progress state shared with the ticker.
	var (
		bytes    netx.Counter
		total    atomic.Int64
		segDone  atomic.Int64
		segTotal atomic.Int64
		phase    atomic.Value
		estimate = req.Kind == "hls"
		convPct  atomic.Int64 // converting phase, 0..1000
	)
	total.Store(-1)
	phase.Store("downloading")

	stop := make(chan struct{})
	tickerDone := make(chan struct{})
	go func() {
		defer close(tickerDone)
		t := time.NewTicker(400 * time.Millisecond)
		defer t.Stop()
		var lastBytes int64
		lastT := time.Now()
		var speed float64
		for {
			select {
			case <-stop:
				return
			case now := <-t.C:
				b := bytes.Load()
				dt := now.Sub(lastT).Seconds()
				if dt > 0 {
					inst := float64(b-lastBytes) / dt
					speed = 0.7*speed + 0.3*inst // smoothed
				}
				lastBytes, lastT = b, now
				ev := Event{
					Type: "progress", Bytes: b, Total: total.Load(), TotalEstimate: estimate,
					SpeedBps: speed, SegmentsDone: int(segDone.Load()), SegmentsTotal: int(segTotal.Load()),
					Phase: phase.Load().(string),
				}
				switch {
				case ev.Phase == "converting":
					ev.Percent = float64(convPct.Load()) / 10
				case ev.SegmentsTotal > 0:
					ev.Percent = 100 * float64(ev.SegmentsDone) / float64(ev.SegmentsTotal)
				case ev.Total > 0:
					ev.Percent = 100 * float64(b) / float64(ev.Total)
				}
				emit(ev)
			}
		}
	}()

	var (
		res hls.Result
		err error
	)
	switch req.Kind {
	case "mp4", "image":
		var n int64
		n, err = netx.DownloadFileOpts(ctx, req.URL, req.Headers, req.OutPath, &bytes, total.Store,
			netx.Options{ChunkSize: req.ChunkSize, Resume: true})
		res = hls.Result{Path: req.OutPath, Size: n}
	case "merge":
		res, err = runMerge(ctx, req, &bytes, &total, func(p string) { phase.Store(p) })
	case "audio":
		res, err = runAudio(ctx, req, &bytes, &total, func(p string) { phase.Store(p) },
			func(f float64) { convPct.Store(int64(f * 1000)) })
	case "hls":
		tmp := req.TmpDir
		if tmp == "" {
			tmp = req.OutPath + ".parts"
		}
		res, err = hls.Download(ctx, hls.Request{
			PlaylistURL: req.URL, AudioURL: req.AudioURL, Headers: req.Headers,
			OutPath: req.OutPath, TmpDir: tmp, FFmpeg: req.FFmpeg,
		}, func(p hls.Progress) {
			bytes.Store(p.Bytes)
			total.Store(p.EstimatedTotal)
			segDone.Store(int64(p.SegmentsDone))
			segTotal.Store(int64(p.SegmentsTotal))
			if p.SegmentsDone == p.SegmentsTotal {
				phase.Store("muxing")
			}
		})
	default:
		err = fmt.Errorf("unknown kind: %q", req.Kind)
	}
	close(stop)
	<-tickerDone
	// Final snapshot, so a paused/failed job shows how far it got.
	final := Event{Type: "progress", Bytes: bytes.Load(), Total: total.Load(), TotalEstimate: estimate,
		SegmentsDone: int(segDone.Load()), SegmentsTotal: int(segTotal.Load()), Phase: phase.Load().(string)}
	if final.SegmentsTotal > 0 {
		final.Percent = 100 * float64(final.SegmentsDone) / float64(final.SegmentsTotal)
	} else if final.Total > 0 {
		final.Percent = 100 * float64(final.Bytes) / float64(final.Total)
	}
	emit(final)

	elapsed := time.Since(started).Round(time.Millisecond)
	switch {
	case ctx.Err() != nil:
		logx.Infof("job %d: stopped after %s (%v)", id, elapsed, context.Cause(ctx))
		return hls.Result{}, context.Cause(ctx)
	case err != nil:
		logx.Errorf("job %d: failed after %s: %v", id, elapsed, err)
		return hls.Result{}, err
	}
	logx.Infof("job %d: done in %s size=%d path=%s", id, elapsed, res.Size, res.Path)
	return res, nil
}

// runMerge downloads the video and (optional) audio streams in parallel and
// joins them into an MP4 with ffmpeg -c copy (no re-encoding).
func runMerge(ctx context.Context, req Request, bytes *netx.Counter, total *atomic.Int64, setPhase func(string)) (res hls.Result, err error) {
	tmp := req.TmpDir
	if tmp == "" {
		tmp = req.OutPath + ".parts"
	}
	if err := os.MkdirAll(tmp, 0o755); err != nil {
		return hls.Result{}, err
	}
	// Removed only on success: partial inputs are resumed next time.
	defer func() {
		if err == nil {
			os.RemoveAll(tmp)
		}
	}()

	urls := []string{req.URL}
	if req.AudioURL != "" {
		urls = append(urls, req.AudioURL)
	}
	inputs := make([]string, len(urls))
	errs := make([]error, len(urls))
	var sizes [2]atomic.Int64
	var wg sync.WaitGroup
	for i, u := range urls {
		inputs[i] = filepath.Join(tmp, fmt.Sprintf("in%d", i))
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, errs[i] = netx.DownloadFileOpts(ctx, u, req.Headers, inputs[i], bytes, func(n int64) {
				sizes[i].Store(n)
				if t := sizes[0].Load(); t > 0 && (len(urls) == 1 || sizes[1].Load() > 0) {
					total.Store(t + sizes[1].Load())
				}
			}, netx.Options{ChunkSize: req.ChunkSize, Resume: true})
		}()
	}
	wg.Wait()
	if err := errors.Join(errs...); err != nil {
		return hls.Result{}, err
	}
	setPhase("muxing")
	if err := req.FFmpeg.CopyToMP4(ctx, inputs, req.OutPath); err != nil {
		return hls.Result{}, fmt.Errorf("MP4 conversion: %w", err)
	}
	fi, err := os.Stat(req.OutPath)
	if err != nil {
		return hls.Result{}, err
	}
	return hls.Result{Path: req.OutPath, Size: fi.Size()}, nil
}

// runAudio downloads the audio source and the cover in parallel, then makes
// the M4A/MP3 (copy or encode + tags + square cover) in one ffmpeg pass.
// HLS sources are read by ffmpeg directly.
func runAudio(ctx context.Context, req Request, bytes *netx.Counter, total *atomic.Int64,
	setPhase func(string), convProgress func(float64)) (res hls.Result, err error) {
	tmp := req.TmpDir
	if tmp == "" {
		tmp = req.OutPath + ".parts"
	}
	if err := os.MkdirAll(tmp, 0o755); err != nil {
		return hls.Result{}, err
	}
	defer func() {
		if err == nil {
			os.RemoveAll(tmp)
		}
	}()

	meta := music.Meta{}
	if req.Meta != nil {
		meta = *req.Meta
	}

	coverCh := make(chan string, 1)
	go func() {
		if meta.CoverURL == "" {
			coverCh <- ""
			return
		}
		b, _, err := netx.GetBytes(ctx, meta.CoverURL, netx.Headers{}, 10<<20)
		if err != nil || len(b) < 100 {
			logx.Warnf("audio: cover download failed: %v", err)
			coverCh <- ""
			return
		}
		p := filepath.Join(tmp, "cover")
		if err := os.WriteFile(p, b, 0o644); err != nil {
			coverCh <- ""
			return
		}
		coverCh <- p
	}()

	input := req.URL
	if !req.SourceHLS {
		input = filepath.Join(tmp, "src")
		if _, err := netx.DownloadFileOpts(ctx, req.URL, req.Headers, input, bytes, total.Store,
			netx.Options{ChunkSize: req.ChunkSize, Resume: true}); err != nil {
			return hls.Result{}, err
		}
	}
	cover := <-coverCh

	setPhase("converting")
	tags := map[string]string{
		"title": meta.Title, "artist": meta.Artist, "album": meta.Album,
		"album_artist": meta.AlbumArtist, "date": meta.Year,
	}
	if meta.Track > 0 {
		tags["track"] = fmt.Sprint(meta.Track)
	}
	spec := media.AudioSpec{
		Format: req.AudioFormat, Quality: req.AudioQuality, Tags: tags,
		CoverPath: cover, CropCover: !meta.CoverSquare, Duration: meta.Duration,
		Headers: req.Headers.Extra,
	}
	if err := req.FFmpeg.EncodeAudio(ctx, input, spec, req.OutPath, convProgress); err != nil {
		return hls.Result{}, fmt.Errorf("audio conversion: %w", err)
	}
	fi, err := os.Stat(req.OutPath)
	if err != nil {
		return hls.Result{}, err
	}
	return hls.Result{Path: req.OutPath, Size: fi.Size(), DurationMs: uint64(meta.Duration * 1000)}, nil
}
