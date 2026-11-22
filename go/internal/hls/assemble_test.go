package hls

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/downvid/core/internal/media"
)

// makeHLS renders a short test clip into an HLS playlist with ffmpeg.
// segType is "mpegts" or "fmp4".
func makeHLS(t *testing.T, ffmpeg, segType string) string {
	t.Helper()
	dir := t.TempDir()
	args := []string{"-hide_banner", "-loglevel", "error",
		"-f", "lavfi", "-i", "testsrc=size=320x240:rate=25:duration=6",
		"-f", "lavfi", "-i", "sine=frequency=440:duration=6",
		"-c:v", "libx264", "-g", "25", "-c:a", "aac", "-shortest",
		"-f", "hls", "-hls_time", "1", "-hls_playlist_type", "vod", "-hls_segment_type", segType}
	if segType == "fmp4" {
		args = append(args, "-hls_fmp4_init_filename", "init.mp4")
	}
	args = append(args, filepath.Join(dir, "index.m3u8"))
	if out, err := exec.Command(ffmpeg, args...).CombinedOutput(); err != nil {
		t.Skipf("ffmpeg cannot render the test clip: %v: %s", err, out)
	}
	return dir
}

func probeDuration(t *testing.T, path string) float64 {
	t.Helper()
	out, err := exec.Command("ffprobe", "-v", "error", "-show_entries", "format=duration",
		"-of", "default=nw=1:nk=1", path).Output()
	if err != nil {
		t.Fatalf("ffprobe: %v", err)
	}
	d, _ := strconv.ParseFloat(strings.TrimSpace(string(out)), 64)
	return d
}

func TestDownloadAssemblesThroughFFmpegPipes(t *testing.T) {
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	if _, err := exec.LookPath("ffprobe"); err != nil {
		t.Skip("ffprobe not installed")
	}
	for _, segType := range []string{"mpegts", "fmp4"} {
		t.Run(segType, func(t *testing.T) {
			srv := httptest.NewServer(http.FileServer(http.Dir(makeHLS(t, ffmpeg, segType))))
			defer srv.Close()
			work := t.TempDir()
			out := filepath.Join(work, "out.mp4")
			tmp := filepath.Join(work, "parts")
			res, err := Download(context.Background(), Request{
				PlaylistURL: srv.URL + "/index.m3u8", OutPath: out, TmpDir: tmp,
				FFmpeg: media.FFmpeg{Path: ffmpeg},
			}, nil)
			if err != nil {
				t.Fatal(err)
			}
			if res.Size <= 0 {
				t.Fatalf("empty output: %+v", res)
			}
			if d := probeDuration(t, out); d < 5.5 || d > 6.5 {
				t.Errorf("duration %.2fs, want ~6s", d)
			}
			if _, err := os.Stat(tmp); !os.IsNotExist(err) {
				t.Errorf("segment directory not removed")
			}
		})
	}
}
