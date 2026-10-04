package media

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"

	"github.com/downvid/core/internal/logx"
)

// FFmpeg locates the ffmpeg executable. On Android it is youtubedl-android's
// libffmpeg.so (an executable in nativeLibraryDir) whose shared libraries
// live in LibraryPath.
type FFmpeg struct {
	Path        string `json:"path"`
	LibraryPath string `json:"libraryPath,omitempty"`
}

func (f FFmpeg) Available() bool {
	if f.Path == "" {
		return false
	}
	_, err := os.Stat(f.Path)
	return err == nil
}

// CopyToMP4 joins the inputs (e.g. a video-only and an audio-only stream, or
// a single WebM) into an MP4 without re-encoding.
func (f FFmpeg) CopyToMP4(ctx context.Context, inputs []string, out string) error {
	if !f.Available() {
		return errors.New("ffmpeg unavailable")
	}
	args := []string{"-hide_banner", "-nostdin", "-loglevel", "error", "-y"}
	for _, in := range inputs {
		args = append(args, "-i", in)
	}
	if len(inputs) == 2 {
		args = append(args, "-map", "0:v:0", "-map", "1:a:0")
	} else {
		args = append(args, "-map", "0:v:0?", "-map", "0:a:0?")
	}
	args = append(args, "-c", "copy", "-movflags", "+faststart", "-f", "mp4", out)
	return f.run(ctx, args)
}

func (f FFmpeg) run(ctx context.Context, args []string) error {
	cmd := exec.CommandContext(ctx, f.Path, args...)
	cmd.Env = os.Environ()
	if f.LibraryPath != "" {
		cmd.Env = append(cmd.Env, "LD_LIBRARY_PATH="+f.LibraryPath)
	}
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	logx.Infof("ffmpeg %s", strings.Join(args, " "))
	if err := cmd.Run(); err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		msg := strings.TrimSpace(stderr.String())
		if len(msg) > 500 {
			msg = msg[len(msg)-500:]
		}
		logx.Errorf("ffmpeg failed: %v: %s", err, msg)
		return fmt.Errorf("ffmpeg: %v: %s", err, msg)
	}
	return nil
}

// AudioSpec describes an "audio only" output.
type AudioSpec struct {
	Format    string            // m4a | mp3
	Quality   string            // copy | v0 | 320 | aac256
	Tags      map[string]string // title, artist, album, album_artist, date, track
	CoverPath string            // optional image (jpg/png/webp)
	CropCover bool              // center-crop to a square (video thumbnails)
	Duration  float64           // seconds, for progress
	Headers   map[string]string // when Input is an http(s) URL (HLS)
}

// EncodeAudio converts/copies the audio track of input into an M4A or MP3
// with tags and embedded cover, in a single ffmpeg pass. progress receives
// 0..1 as ffmpeg advances (needs Duration).
func (f FFmpeg) EncodeAudio(ctx context.Context, input string, spec AudioSpec, out string, progress func(float64)) error {
	if !f.Available() {
		return errors.New("ffmpeg unavailable")
	}
	args := []string{"-hide_banner", "-nostdin", "-loglevel", "error", "-y", "-progress", "pipe:1", "-nostats"}
	if strings.HasPrefix(input, "http") && len(spec.Headers) > 0 {
		var h strings.Builder
		for k, v := range spec.Headers {
			h.WriteString(k + ": " + v + "\r\n")
		}
		args = append(args, "-headers", h.String())
	}
	args = append(args, "-i", input)
	if spec.CoverPath != "" {
		args = append(args, "-i", spec.CoverPath)
	}
	args = append(args, "-map", "0:a:0")
	if spec.CoverPath != "" {
		args = append(args, "-map", "1:v:0")
	}

	switch spec.Quality {
	case "copy":
		args = append(args, "-c:a", "copy")
	case "v0":
		args = append(args, "-c:a", "libmp3lame", "-q:a", "0")
	case "320":
		args = append(args, "-c:a", "libmp3lame", "-b:a", "320k")
	default: // aac256
		args = append(args, "-c:a", "aac", "-b:a", "256k")
	}

	if spec.CoverPath != "" {
		vf := "scale='min(1000,iw)':-2"
		if spec.CropCover {
			vf = "crop='min(iw,ih)':'min(iw,ih)'," + vf
		}
		args = append(args, "-c:v", "mjpeg", "-q:v", "2", "-pix_fmt", "yuvj420p", "-filter:v", vf,
			"-disposition:v:0", "attached_pic")
		if spec.Format == "mp3" {
			args = append(args, "-metadata:s:v", "title=Album cover", "-metadata:s:v", "comment=Cover (front)")
		}
	}
	for _, k := range []string{"title", "artist", "album", "album_artist", "date", "track"} {
		if v := spec.Tags[k]; v != "" {
			args = append(args, "-metadata", k+"="+v)
		}
	}
	if spec.Format == "mp3" {
		args = append(args, "-id3v2_version", "3", "-write_id3v1", "1", "-f", "mp3")
	} else {
		args = append(args, "-movflags", "+faststart", "-f", "mp4")
	}
	args = append(args, out)

	cmd := exec.CommandContext(ctx, f.Path, args...)
	cmd.Env = os.Environ()
	if f.LibraryPath != "" {
		cmd.Env = append(cmd.Env, "LD_LIBRARY_PATH="+f.LibraryPath)
	}
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	logx.Infof("ffmpeg %s", strings.Join(args, " "))
	if err := cmd.Start(); err != nil {
		return err
	}
	// -progress writes key=value lines; out_time_us is the position.
	sc := bufio.NewScanner(stdout)
	for sc.Scan() {
		if v, ok := strings.CutPrefix(sc.Text(), "out_time_us="); ok && progress != nil && spec.Duration > 0 {
			if us, err := strconv.ParseInt(v, 10, 64); err == nil && us > 0 {
				progress(min(1, float64(us)/1e6/spec.Duration))
			}
		}
	}
	if err := cmd.Wait(); err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		msg := strings.TrimSpace(stderr.String())
		if len(msg) > 500 {
			msg = msg[len(msg)-500:]
		}
		logx.Errorf("ffmpeg failed: %v: %s", err, msg)
		return fmt.Errorf("ffmpeg: %v: %s", err, msg)
	}
	return nil
}
