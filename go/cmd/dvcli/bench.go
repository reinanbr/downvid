package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/downvid/core/internal/hls"
	"github.com/downvid/core/internal/media"
	"github.com/downvid/core/internal/netx"
)

// runBench downloads a direct file or an HLS playlist with the core's
// downloaders (no job queue) and prints how long it took:
//
//	dvcli bench <url> [runs] [connections]
//
// HLS reports the segment download and the MP4 assembly separately.
func runBench(args []string) {
	url := args[0]
	runs := 1
	if len(args) > 1 {
		fmt.Sscan(args[1], &runs)
	}
	if len(args) > 2 {
		var c int
		fmt.Sscan(args[2], &c)
		netx.SetConnections(c)
	}
	fmt.Printf("connections: %d\n", netx.Connections())
	ff := media.FFmpeg{}
	if p, err := lookFFmpeg(); err == nil {
		ff.Path = p
	}
	for r := 1; r <= runs; r++ {
		dir, err := os.MkdirTemp("", "dvbench")
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		ctx := context.Background()
		t0 := time.Now()
		if strings.Contains(strings.ToLower(url), ".m3u8") {
			var fetched time.Duration
			var bytes int64
			res, err := hls.Download(ctx, hls.Request{
				PlaylistURL: url, OutPath: filepath.Join(dir, "out.mp4"),
				TmpDir: filepath.Join(dir, "parts"), FFmpeg: ff,
			}, func(p hls.Progress) {
				bytes = p.Bytes
				if p.SegmentsDone == p.SegmentsTotal {
					fetched = time.Since(t0)
				}
			})
			total := time.Since(t0)
			if err != nil {
				fmt.Fprintln(os.Stderr, "error:", err)
				os.Exit(1)
			}
			fmt.Printf("run %d: hls %.1f MB fetched in %s (%.2f MB/s), assembled in %s, mp4 %.1f MB, total %s\n",
				r, float64(bytes)/1e6, fetched.Round(time.Millisecond), float64(bytes)/1e6/fetched.Seconds(),
				(total - fetched).Round(time.Millisecond), float64(res.Size)/1e6, total.Round(time.Millisecond))
		} else {
			var c netx.Counter
			n, err := netx.DownloadFileOpts(ctx, url, netx.Headers{}, filepath.Join(dir, "out.bin"), &c, nil, netx.Options{})
			el := time.Since(t0)
			if err != nil {
				fmt.Fprintln(os.Stderr, "error:", err)
				os.Exit(1)
			}
			fmt.Printf("run %d: file %.1f MB in %s (%.2f MB/s)\n", r, float64(n)/1e6, el.Round(time.Millisecond), float64(n)/1e6/el.Seconds())
		}
		os.RemoveAll(dir)
	}
}

func lookFFmpeg() (string, error) {
	if p := os.Getenv("DV_FFMPEG"); p != "" {
		return p, nil
	}
	for _, p := range []string{"/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg"} {
		if _, err := os.Stat(p); err == nil {
			return p, nil
		}
	}
	return "", os.ErrNotExist
}
