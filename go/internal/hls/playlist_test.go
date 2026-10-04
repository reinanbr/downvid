package hls

import (
	"bytes"
	"testing"
)

const master = `#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="Eng",DEFAULT=YES,URI="audio/en.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="ac3",NAME="Eng 5.1",DEFAULT=YES,URI="audio/ac3.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2",AUDIO="aac"
v360.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=640x360,CODECS="avc1.4d401e,ac-3",AUDIO="ac3"
v360_ac3.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="hvc1.2.4.L123,mp4a.40.2",AUDIO="aac"
v1080_hevc.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="aac"
https://other.example/v1080.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS="mp4a.40.2"
audio_only.m3u8
`

func TestParseMaster(t *testing.T) {
	p, err := Parse([]byte(master), "https://cdn.example/path/master.m3u8?tok=1")
	if err != nil {
		t.Fatal(err)
	}
	if !p.Master || len(p.Variants) != 5 {
		t.Fatalf("master=%v variants=%d", p.Master, len(p.Variants))
	}
	v := p.Variants[0]
	if v.URL != "https://cdn.example/path/v360.m3u8" || v.Height != 360 || v.Bandwidth != 900000 {
		t.Errorf("variant0 = %+v", v)
	}
	if v.AudioURL != "https://cdn.example/path/audio/en.m3u8" {
		t.Errorf("audio = %s", v.AudioURL)
	}
	if !p.Variants[4].IsAudioOnly() {
		t.Errorf("audio-only not detected")
	}

	vs := append([]Variant(nil), p.Variants...)
	SortVariants(vs)
	// 1080p: H.264 preferred over a higher-bitrate HEVC variant.
	if vs[0].URL != "https://other.example/v1080.m3u8" {
		t.Errorf("best 1080p = %s", vs[0].URL)
	}
	// 360p: AAC group preferred over the higher-bitrate AC-3 one.
	if vs[2].URL != "https://cdn.example/path/v360.m3u8" {
		t.Errorf("best 360p = %s", vs[2].URL)
	}
	if b := BestVariant(p.Variants); b.Height != 1080 {
		t.Errorf("BestVariant = %+v", b)
	}
}

const mediaPlaylist = `#EXTM3U
#EXT-X-VERSION:7
#EXT-X-TARGETDURATION:6
#EXT-X-MEDIA-SEQUENCE:10
#EXT-X-PLAYLIST-TYPE:VOD
#EXT-X-MAP:URI="main.mp4",BYTERANGE="700@0"
#EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x000102030405060708090A0B0C0D0E0F
#EXTINF:6.0,
#EXT-X-BYTERANGE:1000@700
main.mp4
#EXTINF:4.5,title, with comma
#EXT-X-BYTERANGE:500
main.mp4
#EXT-X-KEY:METHOD=NONE
#EXTINF:2,
seg3.ts
#EXT-X-ENDLIST
`

func TestParseMedia(t *testing.T) {
	p, err := Parse([]byte(mediaPlaylist), "https://cdn.example/v/index.m3u8")
	if err != nil {
		t.Fatal(err)
	}
	if p.Master || p.IsLive() || len(p.Segments) != 3 || p.TotalDuration != 12.5 {
		t.Fatalf("master=%v live=%v segs=%d dur=%v", p.Master, p.IsLive(), len(p.Segments), p.TotalDuration)
	}
	s0, s1, s2 := p.Segments[0], p.Segments[1], p.Segments[2]
	if s0.Seq != 10 || s1.Seq != 11 {
		t.Errorf("seq = %d %d", s0.Seq, s1.Seq)
	}
	if s0.Range == nil || s0.Range.Offset != 700 || s0.Range.Length != 1000 {
		t.Errorf("range0 = %+v", s0.Range)
	}
	if s1.Range == nil || s1.Range.Offset != 1700 {
		t.Errorf("range1 continuation = %+v", s1.Range)
	}
	if s0.Key == nil || s0.Key.URI != "https://cdn.example/v/key.bin" ||
		!bytes.Equal(s0.Key.IV, []byte{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}) {
		t.Errorf("key = %+v", s0.Key)
	}
	if s2.Key != nil {
		t.Errorf("METHOD=NONE not applied")
	}
	if s0.Map == nil || s0.Map.URL != "https://cdn.example/v/main.mp4" || s0.Map.Range.Length != 700 {
		t.Errorf("map = %+v", s0.Map)
	}
}

func TestLiveAndInvalid(t *testing.T) {
	p, _ := Parse([]byte("#EXTM3U\n#EXTINF:2,\na.ts\n"), "https://x/")
	if !p.IsLive() {
		t.Error("playlist without ENDLIST should be live")
	}
	if _, err := Parse([]byte("<html>"), "https://x/"); err != ErrNotPlaylist {
		t.Errorf("err = %v", err)
	}
}
