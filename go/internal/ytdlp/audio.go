package ytdlp

import (
	"fmt"
	"strings"

	"github.com/downvid/core/internal/music"
	"github.com/downvid/core/internal/scan"
)

// MusicBasic extracts what yt-dlp knows about the song.
func MusicBasic(info *Info) music.Basic {
	b := music.Basic{
		Title: info.Title, Track: info.Track, Album: info.Album, ReleaseYear: info.ReleaseYear,
		Duration: info.Duration, Thumbnail: info.Thumbnail,
	}
	switch {
	case info.Artist != "":
		b.Artist = info.Artist
	case len(info.Artists) > 0:
		b.Artist = strings.Join(info.Artists, ", ")
	case info.Creator != "":
		b.Artist = info.Creator
	}
	b.Uploader = info.Uploader
	if b.Uploader == "" {
		b.Uploader = info.Channel
	}
	// Prefer a square thumbnail (YouTube Music album art) when listed.
	best := 0
	for _, t := range info.Thumbnails {
		if t.Width > 0 && t.Width == t.Height && t.Width > best {
			best, b.Thumbnail, b.ThumbSquare = t.Width, t.URL, true
		}
	}
	return b
}

// AudioOptions builds the "audio only" choices:
//   - M4A: the original AAC stream copied as is (instant, lossless), or AAC
//     256 kbps when the source has no AAC;
//   - MP3 320 kbps and MP3 V0 (~245 kbps VBR) encoded from the best source
//     (Opus usually), or the original MP3 copied when the source is MP3.
func AudioOptions(info *Info) []scan.Option {
	var aac, best *Format
	for i := range info.Formats {
		f := &info.Formats[i]
		if !f.usable() || !f.isAudioOnly() {
			continue
		}
		if strings.HasPrefix(f.ACodec, "mp4a") && (aac == nil || f.ABR > aac.ABR || (aac.isHLS() && !f.isHLS())) {
			aac = f
		}
		if best == nil || scoreOf(*f) > scoreOf(*best) {
			best = f
		}
	}
	if best == nil {
		// No audio-only stream (Instagram, TikTok...): take the audio track of
		// the smallest muxed video.
		for i := range info.Formats {
			f := &info.Formats[i]
			if f.usable() && f.isMuxed() && (best == nil || f.size(info.Duration) < best.size(info.Duration)) {
				best = f
			}
		}
		if best == nil {
			return nil
		}
	}

	mk := func(src *Format, format, quality, label string, kbps float64) scan.Option {
		size := int64(-1)
		exact := false
		switch {
		case quality == "copy":
			size, exact = src.size(info.Duration), src.Filesize > 0
		case info.Duration > 0:
			size = int64(kbps * 1000 / 8 * info.Duration)
		}
		return scan.Option{
			Kind: "audio", AudioFormat: format, AudioQuality: quality, Label: label,
			URL: src.URL, SourceHLS: src.isHLS(), Headers: headersOf(*src), ChunkSize: src.Downloader.ChunkSize,
			Size: size, SizeExact: exact, DurationSec: info.Duration, Source: "ytdlp", Group: info.WebpageURL,
		}
	}

	var opts []scan.Option
	if aac != nil {
		opts = append(opts, mk(aac, "m4a", "copy", fmt.Sprintf("M4A · AAC %.0f kbps · original", aac.ABR), aac.ABR))
	} else {
		opts = append(opts, mk(best, "m4a", "aac256", "M4A · AAC 256 kbps", 256))
	}
	if best.ACodec == "mp3" && best.isAudioOnly() {
		opts = append(opts, mk(best, "mp3", "copy", fmt.Sprintf("MP3 · %.0f kbps · original", best.ABR), best.ABR))
	} else {
		opts = append(opts,
			mk(best, "mp3", "320", "MP3 · 320 kbps", 320),
			mk(best, "mp3", "v0", "MP3 · V0 (~245 kbps, smaller)", 245))
	}
	return opts
}

// scoreOf ranks audio sources: Opus/Vorbis sound better than AAC/MP3 at the
// same bitrate; direct files beat HLS.
func scoreOf(f Format) float64 {
	s := f.ABR
	if f.ACodec == "opus" || f.ACodec == "vorbis" {
		s *= 1.25
	}
	if !f.isHLS() {
		s += 0.5
	}
	return s
}
