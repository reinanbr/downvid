// Command dvcli exercises the core on the host (no Android needed):
//
//	go run ./cmd/dvcli probe <page-url>
//	go run ./cmd/dvcli get <page-url> [option-index] [out.mp4]
//	go run ./cmd/dvcli ytdlp <url> [option-index] [out.mp4]   (needs yt-dlp + ffmpeg; DV_YTDLP=path)
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"

	"github.com/downvid/core/internal/jobs"
	"github.com/downvid/core/internal/media"
	"github.com/downvid/core/internal/music"
	"github.com/downvid/core/internal/netx"
	"github.com/downvid/core/internal/scan"
	"github.com/downvid/core/internal/ytdlp"
)

func main() {
	if len(os.Args) < 3 {
		fmt.Fprintln(os.Stderr, "usage: dvcli probe|get <url> [index] [out.mp4]")
		os.Exit(2)
	}
	if os.Args[1] == "ytdlp" || os.Args[1] == "audio" {
		runYtdlp(os.Args[1], os.Args[2:])
		return
	}
	res, err := scan.Probe(context.Background(), scan.Request{PageURL: os.Args[2]})
	if err != nil {
		fmt.Fprintln(os.Stderr, "probe:", err)
		os.Exit(1)
	}
	if os.Args[1] == "probe" {
		b, _ := json.MarshalIndent(res, "", "  ")
		fmt.Println(string(b))
		return
	}
	if len(res.Options) == 0 {
		fmt.Fprintln(os.Stderr, "no options")
		os.Exit(1)
	}
	idx := 0
	if len(os.Args) > 3 {
		idx, _ = strconv.Atoi(os.Args[3])
	}
	out := "out.mp4"
	if len(os.Args) > 4 {
		out = os.Args[4]
	}
	o := res.Options[idx]
	fmt.Fprintf(os.Stderr, "downloading %s %s\n", o.Label, o.URL)
	done := make(chan struct{})
	_, err = jobs.Start(jobs.Request{
		Kind: o.Kind, URL: o.URL, AudioURL: o.AudioURL, OutPath: out,
		Headers: netx.Headers{Referer: o.Referer},
		FFmpeg:  media.FFmpeg{Path: os.Getenv("DV_FFMPEG")}, // empty: Go remux only
	}, jobs.Meta{}, func(ev jobs.Event) {
		b, _ := json.Marshal(ev)
		fmt.Fprintln(os.Stderr, string(b))
		if ev.Type != "progress" {
			close(done)
		}
	})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	<-done
}

func runYtdlp(mode string, args []string) {
	bin := os.Getenv("DV_YTDLP")
	if bin == "" {
		bin = "yt-dlp"
	}
	var stdout, stderr strings.Builder
	cmd := exec.Command(bin, "-J", "--no-playlist", args[0])
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	t0 := time.Now()
	runErr := cmd.Run()
	fmt.Fprintf(os.Stderr, "yt-dlp -J: %v (%s)\n", runErr, time.Since(t0).Round(time.Millisecond))
	if runErr != nil {
		fmt.Fprintln(os.Stderr, "error:", ytdlp.FriendlyError(stderr.String(), ""), "| unsupported:", ytdlp.IsUnsupported(stderr.String()))
		os.Exit(1)
	}
	info, err := ytdlp.Parse([]byte(stdout.String()))
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	opts, err := ytdlp.Options(info)
	var meta *music.Meta
	if mode == "audio" {
		opts, err = ytdlp.AudioOptions(info), nil
		m := music.Resolve(context.Background(), ytdlp.MusicBasic(info), nil)
		meta = &m
		fmt.Fprintf(os.Stderr, "meta: %+v\n", m)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	for i, o := range opts {
		fmt.Printf("[%d] %-20s %-5s %6.1f MB\n", i, o.Label, o.Kind, float64(o.Size)/1e6)
	}
	if len(args) < 2 {
		return
	}
	idx, _ := strconv.Atoi(args[1])
	out := "out.mp4"
	if len(args) > 2 {
		out = args[2]
	}
	o := opts[idx]
	done := make(chan struct{})
	t1 := time.Now()
	_, err = jobs.Start(jobs.Request{
		Kind: o.Kind, URL: o.URL, AudioURL: o.AudioURL, OutPath: out, ChunkSize: o.ChunkSize,
		Headers:     netx.Headers{Extra: o.Headers},
		FFmpeg:      media.FFmpeg{Path: "/usr/bin/ffmpeg"},
		AudioFormat: o.AudioFormat, AudioQuality: o.AudioQuality, SourceHLS: o.SourceHLS, Meta: meta,
	}, jobs.Meta{}, func(ev jobs.Event) {
		if ev.Type == "progress" {
			fmt.Fprintf(os.Stderr, "\r%5.1f%% %6.1f/%6.1f MB %5.1f MB/s %s   ", ev.Percent, float64(ev.Bytes)/1e6, float64(ev.Total)/1e6, ev.SpeedBps/1e6, ev.Phase)
			return
		}
		b, _ := json.Marshal(ev)
		fmt.Fprintf(os.Stderr, "\n%s (%s)\n", b, time.Since(t1).Round(time.Millisecond))
		close(done)
	})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	<-done
}
