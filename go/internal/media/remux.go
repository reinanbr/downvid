// Package media converts downloaded HLS segments into a single MP4 file.
package media

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"

	"github.com/downvid/core/internal/logx"
	mp4 "github.com/yapingcat/gomedia/go-mp4"
	mpeg2 "github.com/yapingcat/gomedia/go-mpeg2"
)

// SegmentFormat is sniffed from the first bytes of a segment.
type SegmentFormat int

const (
	FormatUnknown SegmentFormat = iota
	FormatTS
	FormatADTS // packed audio (.aac), optionally behind an ID3 tag
	FormatFMP4
)

func (f SegmentFormat) String() string {
	return [...]string{"unknown", "ts", "adts", "fmp4"}[f]
}

// Sniff detects the container of a segment. Some sites prepend junk (e.g. a
// fake PNG header) to TS segments, so TS sync is searched beyond offset 0.
func Sniff(b []byte) SegmentFormat {
	if len(b) >= 8 {
		switch string(b[4:8]) {
		case "ftyp", "styp", "moof", "sidx", "moov":
			return FormatFMP4
		}
	}
	start := 0
	if len(b) >= 10 && string(b[:3]) == "ID3" {
		start = 10 + syncsafe(b[6:10])
	}
	if start+2 <= len(b) && b[start] == 0xFF && b[start+1]&0xF6 == 0xF0 {
		return FormatADTS
	}
	if start < len(b) && b[start] == 0x47 && start == 0 {
		return FormatTS
	}
	for i := 0; i+2*188 < len(b) && i < 64<<10; i++ {
		if b[i] == 0x47 && b[i+188] == 0x47 && b[i+376] == 0x47 {
			return FormatTS
		}
	}
	return FormatUnknown
}

func SniffFile(path string) (SegmentFormat, error) {
	f, err := os.Open(path)
	if err != nil {
		return FormatUnknown, err
	}
	defer f.Close()
	buf := make([]byte, 70<<10)
	n, _ := io.ReadFull(f, buf)
	return Sniff(buf[:n]), nil
}

// ConcatFMP4 writes the init section followed by every fragment. The result
// is a valid fragmented MP4.
func ConcatFMP4(out string, init []byte, segs []string) error {
	f, err := os.Create(out)
	if err != nil {
		return err
	}
	defer f.Close()
	if _, err := f.Write(init); err != nil {
		return err
	}
	for _, s := range segs {
		if err := appendFile(f, s); err != nil {
			return err
		}
	}
	return f.Sync()
}

func appendFile(w io.Writer, path string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = io.Copy(w, f)
	return err
}

// RemuxStats summarizes what went into the MP4.
type RemuxStats struct {
	VideoFrames int
	AudioFrames int
	DurationMs  uint64
}

// RemuxTS muxes MPEG-TS video segments (with or without embedded audio) and an
// optional separate audio rendition (TS or ADTS) into a progressive MP4.
//
// HLS requires renditions to share one timeline, so all timestamps are
// shifted by the earliest DTS found across inputs.
func RemuxTS(out string, video []string, audio []string, audioFmt SegmentFormat) (RemuxStats, error) {
	var st RemuxStats
	if len(video) == 0 {
		return st, errors.New("no video segments")
	}

	base, ok := firstTimestamp(video[0], FormatTS)
	if len(audio) > 0 {
		if ab, aok := firstTimestamp(audio[0], audioFmt); aok && (!ok || ab < base) {
			base, ok = ab, true
		}
	}
	if !ok {
		logx.Warnf("remux: no initial timestamp found, using 0")
	}

	f, err := os.Create(out)
	if err != nil {
		return st, err
	}
	defer f.Close()
	muxer, err := mp4.CreateMp4Muxer(f)
	if err != nil {
		return st, err
	}

	m := &muxState{muxer: muxer, base: base, st: &st}
	// Embedded audio is ignored when a separate rendition exists.
	if err := m.demuxTS(video, len(audio) == 0); err != nil {
		return st, fmt.Errorf("video: %w", err)
	}
	switch {
	case len(audio) == 0:
	case audioFmt == FormatTS:
		if err := m.demuxAudioTS(audio); err != nil {
			return st, fmt.Errorf("audio: %w", err)
		}
	case audioFmt == FormatADTS:
		if err := m.demuxADTS(audio); err != nil {
			return st, fmt.Errorf("audio: %w", err)
		}
	default:
		logx.Warnf("remux: audio format %v not supported, saving video only", audioFmt)
	}

	if st.VideoFrames == 0 && st.AudioFrames == 0 {
		return st, errors.New("no H.264/H.265/AAC frames found in the segments")
	}
	if err := muxer.WriteTrailer(); err != nil {
		return st, err
	}
	return st, f.Sync()
}

type muxState struct {
	muxer      *mp4.Movmuxer
	base       uint64
	st         *RemuxStats
	vtid, atid uint32
	hasV, hasA bool
	err        error
}

func (m *muxState) rel(ts uint64) uint64 {
	if ts < m.base {
		return 0
	}
	return ts - m.base
}

func (m *muxState) writeVideo(cid mpeg2.TS_STREAM_TYPE, frame []byte, pts, dts uint64) {
	if !m.hasV {
		codec := mp4.MP4_CODEC_H264
		if cid == mpeg2.TS_STREAM_H265 {
			codec = mp4.MP4_CODEC_H265
		}
		m.vtid = m.muxer.AddVideoTrack(codec)
		m.hasV = true
	}
	p, d := m.rel(pts), m.rel(dts)
	if err := m.muxer.Write(m.vtid, frame, p, d); err != nil && m.err == nil {
		m.err = err
	}
	m.st.VideoFrames++
	m.st.DurationMs = max(m.st.DurationMs, p)
}

func (m *muxState) writeAudio(cid mpeg2.TS_STREAM_TYPE, frame []byte, pts uint64) {
	if !m.hasA {
		codec := mp4.MP4_CODEC_AAC
		if cid != mpeg2.TS_STREAM_AAC {
			codec = mp4.MP4_CODEC_MP3
		}
		m.atid = m.muxer.AddAudioTrack(codec)
		m.hasA = true
	}
	if cid != mpeg2.TS_STREAM_AAC {
		p := m.rel(pts)
		if err := m.muxer.Write(m.atid, frame, p, p); err != nil && m.err == nil {
			m.err = err
		}
		m.st.AudioFrames++
		return
	}
	// One PES often carries several ADTS frames; give each its own timestamp
	// (1024 samples per AAC frame) instead of reusing the PES PTS.
	start := float64(m.rel(pts))
	i := 0
	splitADTS(frame, func(adts []byte, sampleRate int) {
		t := uint64(start + float64(i)*1024*1000/float64(sampleRate) + 0.5)
		if err := m.muxer.Write(m.atid, adts, t, t); err != nil && m.err == nil {
			m.err = err
		}
		i++
		m.st.AudioFrames++
	})
}

func isVideo(cid mpeg2.TS_STREAM_TYPE) bool {
	return cid == mpeg2.TS_STREAM_H264 || cid == mpeg2.TS_STREAM_H265
}

func isAudio(cid mpeg2.TS_STREAM_TYPE) bool {
	return cid == mpeg2.TS_STREAM_AAC || cid == mpeg2.TS_STREAM_AUDIO_MPEG1 || cid == mpeg2.TS_STREAM_AUDIO_MPEG2
}

func (m *muxState) demuxTS(files []string, withAudio bool) error {
	d := mpeg2.NewTSDemuxer()
	d.OnFrame = func(cid mpeg2.TS_STREAM_TYPE, frame []byte, pts, dts uint64) {
		switch {
		case isVideo(cid):
			m.writeVideo(cid, frame, pts, dts)
		case withAudio && isAudio(cid):
			m.writeAudio(cid, frame, pts)
		}
	}
	if err := d.Input(newMultiFileReader(files)); err != nil {
		return err
	}
	return m.err
}

func (m *muxState) demuxAudioTS(files []string) error {
	d := mpeg2.NewTSDemuxer()
	d.OnFrame = func(cid mpeg2.TS_STREAM_TYPE, frame []byte, pts, dts uint64) {
		if isAudio(cid) {
			m.writeAudio(cid, frame, pts)
		}
	}
	if err := d.Input(newMultiFileReader(files)); err != nil {
		return err
	}
	return m.err
}

// demuxADTS handles packed-audio segments. Each segment starts with an ID3
// tag carrying its 90 kHz start timestamp (Apple transportStreamTimestamp).
func (m *muxState) demuxADTS(files []string) error {
	var next uint64 // continuation when a segment has no timestamp
	for _, path := range files {
		b, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		body, ts, ok := stripID3(b)
		pts := next
		if ok {
			pts = ts / 90
		} else if m.st.AudioFrames == 0 {
			pts = m.base
		}
		var frames int
		var rate int
		splitADTS(body, func(_ []byte, sr int) { frames++; rate = sr })
		m.writeAudio(mpeg2.TS_STREAM_AAC, body, pts)
		if rate > 0 {
			next = pts + uint64(float64(frames)*1024*1000/float64(rate))
		}
		if m.err != nil {
			return m.err
		}
	}
	return nil
}

// firstTimestamp returns the first DTS (ms) of a segment.
func firstTimestamp(path string, format SegmentFormat) (uint64, bool) {
	b, err := os.ReadFile(path)
	if err != nil {
		return 0, false
	}
	if format == FormatADTS {
		if _, ts, ok := stripID3(b); ok {
			return ts / 90, true
		}
		return 0, false
	}
	var first uint64
	found := false
	d := mpeg2.NewTSDemuxer()
	d.OnFrame = func(cid mpeg2.TS_STREAM_TYPE, frame []byte, pts, dts uint64) {
		if !isVideo(cid) && !isAudio(cid) {
			return
		}
		if !found || dts < first {
			first, found = dts, true
		}
	}
	_ = d.Input(bytes.NewReader(b))
	return first, found
}

var adtsRates = [...]int{96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350}

// splitADTS calls fn for every complete ADTS frame (header included).
func splitADTS(b []byte, fn func(frame []byte, sampleRate int)) {
	for len(b) >= 7 {
		if b[0] != 0xFF || b[1]&0xF6 != 0xF0 {
			// resync
			i := bytes.IndexByte(b[1:], 0xFF)
			if i < 0 {
				return
			}
			b = b[1+i:]
			continue
		}
		n := int(b[3]&0x03)<<11 | int(b[4])<<3 | int(b[5])>>5
		if n < 7 || n > len(b) {
			return
		}
		idx := int(b[2]>>2) & 0x0F
		rate := 44100
		if idx < len(adtsRates) {
			rate = adtsRates[idx]
		}
		fn(b[:n], rate)
		b = b[n:]
	}
}

func syncsafe(b []byte) int {
	return int(b[0]&0x7F)<<21 | int(b[1]&0x7F)<<14 | int(b[2]&0x7F)<<7 | int(b[3]&0x7F)
}

// stripID3 removes leading ID3v2 tags and returns the PRIV
// "com.apple.streaming.transportStreamTimestamp" value (90 kHz) if present.
func stripID3(b []byte) (body []byte, ts uint64, ok bool) {
	const owner = "com.apple.streaming.transportStreamTimestamp\x00"
	for len(b) >= 10 && string(b[:3]) == "ID3" {
		size := syncsafe(b[6:10])
		end := 10 + size
		if end > len(b) {
			break
		}
		tag := b[10:end]
		if i := bytes.Index(tag, []byte(owner)); i >= 0 && i+len(owner)+8 <= len(tag) {
			raw := binary.BigEndian.Uint64(tag[i+len(owner):])
			ts, ok = raw&0x1FFFFFFFF, true
		}
		b = b[end:]
	}
	return b, ts, ok
}

// multiFileReader streams files back to back, opening one at a time.
type multiFileReader struct {
	files []string
	cur   *os.File
}

func newMultiFileReader(files []string) *multiFileReader { return &multiFileReader{files: files} }

func (r *multiFileReader) Read(p []byte) (int, error) {
	for {
		if r.cur == nil {
			if len(r.files) == 0 {
				return 0, io.EOF
			}
			f, err := os.Open(r.files[0])
			if err != nil {
				return 0, err
			}
			r.cur, r.files = f, r.files[1:]
		}
		n, err := r.cur.Read(p)
		if err == io.EOF {
			r.cur.Close()
			r.cur = nil
			if n > 0 {
				return n, nil
			}
			continue
		}
		return n, err
	}
}

// RemuxFMP4 converts fragmented MP4 inputs (video, plus an optional separate
// audio rendition) into one progressive MP4, interleaving samples by DTS.
// Only H.264/H.265/AAC/MP3 are supported by the muxer; callers fall back to
// plain concatenation when this fails.
func RemuxFMP4(out, videoPath, audioPath string) (st RemuxStats, err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("fmp4 demux: %v", r)
		}
	}()

	open := func(p string) (*fmp4Input, error) {
		f, err := os.Open(p)
		if err != nil {
			return nil, err
		}
		d := mp4.CreateMp4Demuxer(f)
		if _, err := d.ReadHead(); err != nil && !errors.Is(err, io.EOF) {
			f.Close()
			return nil, err
		}
		in := &fmp4Input{f: f, d: d}
		in.advance()
		return in, nil
	}
	var ins []*fmp4Input
	for _, p := range []string{videoPath, audioPath} {
		if p == "" {
			continue
		}
		in, err := open(p)
		if err != nil {
			return st, err
		}
		defer in.f.Close()
		ins = append(ins, in)
	}

	// Shift every track by the earliest DTS (renditions share a timeline).
	var base uint64
	first := true
	for _, in := range ins {
		if in.next != nil && (first || in.next.Dts < base) {
			base, first = in.next.Dts, false
		}
	}

	f, err := os.Create(out)
	if err != nil {
		return st, err
	}
	defer f.Close()
	muxer, err := mp4.CreateMp4Muxer(f)
	if err != nil {
		return st, err
	}
	m := &muxState{muxer: muxer, base: base, st: &st}
	// With a separate rendition, audio embedded in the video file is dropped.
	videoOnlyFromFirst := len(ins) > 1

	for {
		var pick *fmp4Input
		for _, in := range ins {
			if in.next != nil && (pick == nil || in.next.Dts < pick.next.Dts) {
				pick = in
			}
		}
		if pick == nil {
			break
		}
		pkt := pick.next
		pick.advance()
		switch pkt.Cid {
		case mp4.MP4_CODEC_H264:
			m.writeVideo(mpeg2.TS_STREAM_H264, pkt.Data, pkt.Pts, pkt.Dts)
		case mp4.MP4_CODEC_H265:
			m.writeVideo(mpeg2.TS_STREAM_H265, pkt.Data, pkt.Pts, pkt.Dts)
		case mp4.MP4_CODEC_AAC, mp4.MP4_CODEC_MP3:
			if videoOnlyFromFirst && pick == ins[0] {
				continue
			}
			cid := mpeg2.TS_STREAM_AAC
			if pkt.Cid == mp4.MP4_CODEC_MP3 {
				cid = mpeg2.TS_STREAM_AUDIO_MPEG1
			}
			m.writeAudio(cid, pkt.Data, pkt.Pts)
		default:
			return st, fmt.Errorf("codec %v not supported in MP4", pkt.Cid)
		}
		if m.err != nil {
			return st, m.err
		}
	}
	if st.VideoFrames == 0 && st.AudioFrames == 0 {
		return st, errors.New("no frames found in the fMP4")
	}
	if err := muxer.WriteTrailer(); err != nil {
		return st, err
	}
	return st, f.Sync()
}

type fmp4Input struct {
	f    *os.File
	d    *mp4.MovDemuxer
	next *mp4.AVPacket
}

// advance buffers the next packet (nil at EOF or on error).
func (in *fmp4Input) advance() {
	pkt, err := in.d.ReadPacket()
	if err != nil {
		if !errors.Is(err, io.EOF) {
			logx.Warnf("fmp4: read: %v", err)
		}
		in.next = nil
		return
	}
	in.next = pkt
}
