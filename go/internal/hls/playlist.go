// Package hls parses m3u8 playlists and downloads VOD streams as MP4.
package hls

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"
)

var ErrNotPlaylist = errors.New("not an m3u8 playlist")

// Variant is one quality of a master playlist.
type Variant struct {
	URL        string  `json:"url"`
	Bandwidth  int64   `json:"bandwidth"`
	Width      int     `json:"width,omitempty"`
	Height     int     `json:"height,omitempty"`
	Codecs     string  `json:"codecs,omitempty"`
	FrameRate  float64 `json:"frameRate,omitempty"`
	AudioGroup string  `json:"-"`
	AudioURL   string  `json:"audioUrl,omitempty"` // separate audio rendition, if any
}

// Rendition is an EXT-X-MEDIA entry (only TYPE=AUDIO is used).
type Rendition struct {
	Type     string
	GroupID  string
	Name     string
	Language string
	URI      string
	Default  bool
}

type Key struct {
	Method string // NONE, AES-128, SAMPLE-AES...
	URI    string
	IV     []byte // nil: derive from media sequence number
}

type ByteRange struct {
	Length int64
	Offset int64 // -1: continues after the previous range of the same URI
}

type Segment struct {
	URL      string
	Duration float64
	Seq      int64
	Key      *Key
	Range    *ByteRange
	Map      *InitMap // fMP4 init section in effect for this segment
}

type InitMap struct {
	URL   string
	Range *ByteRange
}

type Playlist struct {
	Master     bool
	Variants   []Variant
	Renditions []Rendition

	Segments      []Segment
	TargetDur     float64
	EndList       bool
	PlaylistType  string // VOD / EVENT
	TotalDuration float64
}

// IsLive reports whether the media playlist is still growing.
func (p *Playlist) IsLive() bool {
	return !p.Master && !p.EndList && p.PlaylistType != "VOD"
}

// Parse parses a master or media playlist; relative URIs are resolved
// against base.
func Parse(data []byte, base string) (*Playlist, error) {
	data = bytes.TrimPrefix(data, []byte("\xef\xbb\xbf"))
	if !bytes.HasPrefix(bytes.TrimSpace(data), []byte("#EXTM3U")) {
		return nil, ErrNotPlaylist
	}
	baseURL, err := url.Parse(base)
	if err != nil {
		return nil, err
	}
	resolve := func(ref string) string {
		u, err := baseURL.Parse(strings.TrimSpace(ref))
		if err != nil {
			return ref
		}
		return u.String()
	}

	p := &Playlist{}
	var (
		pendingStream map[string]string
		segDur        float64
		segRange      *ByteRange
		curKey        *Key
		curMap        *InitMap
		seq           int64
		lastRangeEnd  = map[string]int64{}
	)

	sc := bufio.NewScanner(bytes.NewReader(data))
	sc.Buffer(make([]byte, 64<<10), 4<<20)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" {
			continue
		}
		if !strings.HasPrefix(line, "#") {
			uri := resolve(line)
			if pendingStream != nil {
				p.Master = true
				p.Variants = append(p.Variants, variantFrom(pendingStream, uri))
				pendingStream = nil
				continue
			}
			s := Segment{URL: uri, Duration: segDur, Seq: seq, Key: curKey, Map: curMap}
			if segRange != nil {
				r := *segRange
				if r.Offset < 0 {
					r.Offset = lastRangeEnd[uri]
				}
				lastRangeEnd[uri] = r.Offset + r.Length
				s.Range = &r
			}
			p.Segments = append(p.Segments, s)
			p.TotalDuration += segDur
			seq++
			segDur, segRange = 0, nil
			continue
		}

		tag, val, _ := strings.Cut(line, ":")
		switch tag {
		case "#EXT-X-STREAM-INF":
			pendingStream = parseAttrs(val)
		case "#EXT-X-MEDIA":
			a := parseAttrs(val)
			r := Rendition{
				Type: a["TYPE"], GroupID: a["GROUP-ID"], Name: a["NAME"],
				Language: a["LANGUAGE"], Default: a["DEFAULT"] == "YES",
			}
			if a["URI"] != "" {
				r.URI = resolve(a["URI"])
			}
			p.Renditions = append(p.Renditions, r)
			p.Master = true
		case "#EXTINF":
			d, _, _ := strings.Cut(val, ",")
			segDur, _ = strconv.ParseFloat(strings.TrimSpace(d), 64)
		case "#EXT-X-TARGETDURATION":
			p.TargetDur, _ = strconv.ParseFloat(val, 64)
		case "#EXT-X-MEDIA-SEQUENCE":
			seq, _ = strconv.ParseInt(val, 10, 64)
		case "#EXT-X-ENDLIST":
			p.EndList = true
		case "#EXT-X-PLAYLIST-TYPE":
			p.PlaylistType = strings.ToUpper(val)
		case "#EXT-X-BYTERANGE":
			segRange = parseByteRange(val)
		case "#EXT-X-KEY":
			a := parseAttrs(val)
			k := &Key{Method: strings.ToUpper(a["METHOD"])}
			if k.Method == "NONE" || k.Method == "" {
				curKey = nil
				break
			}
			if a["URI"] != "" {
				k.URI = resolve(a["URI"])
			}
			if iv := a["IV"]; iv != "" {
				k.IV = parseIV(iv)
			}
			curKey = k
		case "#EXT-X-MAP":
			a := parseAttrs(val)
			m := &InitMap{URL: resolve(a["URI"])}
			if br := a["BYTERANGE"]; br != "" {
				m.Range = parseByteRange(br)
				if m.Range != nil && m.Range.Offset < 0 {
					m.Range.Offset = 0
				}
			}
			curMap = m
		}
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}

	if p.Master {
		p.linkAudio()
	}
	return p, nil
}

func variantFrom(a map[string]string, uri string) Variant {
	v := Variant{URL: uri, Codecs: a["CODECS"], AudioGroup: a["AUDIO"]}
	v.Bandwidth, _ = strconv.ParseInt(a["BANDWIDTH"], 10, 64)
	if v.Bandwidth == 0 {
		v.Bandwidth, _ = strconv.ParseInt(a["AVERAGE-BANDWIDTH"], 10, 64)
	}
	if w, h, ok := strings.Cut(a["RESOLUTION"], "x"); ok {
		v.Width, _ = strconv.Atoi(w)
		v.Height, _ = strconv.Atoi(h)
	}
	v.FrameRate, _ = strconv.ParseFloat(a["FRAME-RATE"], 64)
	return v
}

// linkAudio attaches the default audio rendition of each variant's group.
func (p *Playlist) linkAudio() {
	for i := range p.Variants {
		g := p.Variants[i].AudioGroup
		if g == "" {
			continue
		}
		var pick *Rendition
		for j := range p.Renditions {
			r := &p.Renditions[j]
			if r.Type != "AUDIO" || r.GroupID != g || r.URI == "" {
				continue
			}
			if pick == nil || (r.Default && !pick.Default) {
				pick = r
			}
		}
		// A rendition without URI means the audio is muxed into the variant.
		if pick != nil {
			p.Variants[i].AudioURL = pick.URI
		}
	}
}

// IsAudioOnly reports variants that carry no video (e.g. "mp4a.40.2" only).
func (v Variant) IsAudioOnly() bool {
	if v.Codecs == "" || v.Height > 0 {
		return false
	}
	for c := range strings.SplitSeq(v.Codecs, ",") {
		c = strings.TrimSpace(c)
		if strings.HasPrefix(c, "avc") || strings.HasPrefix(c, "hvc") || strings.HasPrefix(c, "hev") ||
			strings.HasPrefix(c, "vp") || strings.HasPrefix(c, "av01") || strings.HasPrefix(c, "dvh") {
			return false
		}
	}
	return true
}

// parseAttrs parses an attribute list: KEY=VALUE,KEY="quoted, value",...
func parseAttrs(s string) map[string]string {
	out := map[string]string{}
	for len(s) > 0 {
		eq := strings.IndexByte(s, '=')
		if eq < 0 {
			break
		}
		key := strings.TrimSpace(s[:eq])
		s = s[eq+1:]
		var val string
		if strings.HasPrefix(s, `"`) {
			end := strings.IndexByte(s[1:], '"')
			if end < 0 {
				val, s = s[1:], ""
			} else {
				val, s = s[1:1+end], s[2+end:]
			}
			s = strings.TrimPrefix(s, ",")
		} else {
			comma := strings.IndexByte(s, ',')
			if comma < 0 {
				val, s = s, ""
			} else {
				val, s = s[:comma], s[comma+1:]
			}
		}
		out[strings.ToUpper(key)] = strings.TrimSpace(val)
	}
	return out
}

// parseByteRange parses "<n>[@<o>]".
func parseByteRange(s string) *ByteRange {
	n, o, hasOff := strings.Cut(strings.TrimSpace(s), "@")
	length, err := strconv.ParseInt(n, 10, 64)
	if err != nil {
		return nil
	}
	r := &ByteRange{Length: length, Offset: -1}
	if hasOff {
		r.Offset, _ = strconv.ParseInt(o, 10, 64)
	}
	return r
}

func parseIV(s string) []byte {
	s = strings.TrimPrefix(strings.TrimPrefix(s, "0x"), "0X")
	if len(s)%2 == 1 {
		s = "0" + s
	}
	b := make([]byte, 16)
	raw := make([]byte, len(s)/2)
	for i := range raw {
		v, err := strconv.ParseUint(s[2*i:2*i+2], 16, 8)
		if err != nil {
			return nil
		}
		raw[i] = byte(v)
	}
	if len(raw) > 16 {
		raw = raw[len(raw)-16:]
	}
	copy(b[16-len(raw):], raw)
	return b
}

// Label is a human description of a variant, e.g. "1080p · 5.2 Mbps".
func (v Variant) Label() string {
	var parts []string
	if v.Height > 0 {
		parts = append(parts, fmt.Sprintf("%dp", v.Height))
	} else if v.IsAudioOnly() {
		parts = append(parts, "audio only")
	}
	if v.Bandwidth > 0 {
		parts = append(parts, fmt.Sprintf("%.1f Mbps", float64(v.Bandwidth)/1e6))
	}
	if len(parts) == 0 {
		return "default"
	}
	return strings.Join(parts, " · ")
}
