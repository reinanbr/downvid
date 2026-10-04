package hls

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/downvid/core/internal/logx"
	"github.com/downvid/core/internal/media"
	"github.com/downvid/core/internal/netx"
)

const segmentWorkers = 6

// Request describes an HLS download.
type Request struct {
	PlaylistURL string // media playlist, or master (best variant is chosen)
	AudioURL    string // optional separate audio media playlist
	Headers     netx.Headers
	OutPath     string // final .mp4
	TmpDir      string // scratch space for segments (removed afterwards)
	// FFmpeg, when available, assembles the MP4 (robust with every HLS
	// flavor); the pure-Go remux is the fallback.
	FFmpeg media.FFmpeg
}

// Progress is reported while segments download.
type Progress struct {
	SegmentsDone  int
	SegmentsTotal int
	Bytes         int64
	// EstimatedTotal extrapolates the average segment size.
	EstimatedTotal int64
}

type Result struct {
	Path       string `json:"path"`
	Size       int64  `json:"size"`
	DurationMs uint64 `json:"durationMs"`
	Warning    string `json:"warning,omitempty"`
}

// LoadMedia fetches a playlist; when it is a master, the highest-bandwidth
// video variant is followed. Returns the media playlist and, if the chosen
// variant has one, its separate audio playlist URL.
func LoadMedia(ctx context.Context, u string, h netx.Headers) (*Playlist, string, error) {
	pl, err := fetchPlaylist(ctx, u, h)
	if err != nil {
		return nil, "", err
	}
	if !pl.Master {
		return pl, "", nil
	}
	best := BestVariant(pl.Variants)
	if best == nil {
		return nil, "", errors.New("master playlist without variants")
	}
	logx.Infof("hls: master -> variant %s (%s)", best.Label(), best.URL)
	media, err := fetchPlaylist(ctx, best.URL, h)
	if err != nil {
		return nil, "", err
	}
	return media, best.AudioURL, nil
}

func BestVariant(vs []Variant) *Variant {
	var best *Variant
	for i := range vs {
		v := &vs[i]
		if v.IsAudioOnly() {
			continue
		}
		if best == nil || v.Height > best.Height || (v.Height == best.Height && v.Bandwidth > best.Bandwidth) {
			best = v
		}
	}
	if best == nil && len(vs) > 0 {
		best = &vs[0]
	}
	return best
}

func fetchPlaylist(ctx context.Context, u string, h netx.Headers) (*Playlist, error) {
	b, resp, err := netx.GetBytes(ctx, u, h, 16<<20)
	if err != nil {
		return nil, err
	}
	// Resolve relative URIs against the final URL after redirects.
	return Parse(b, resp.Request.URL.String())
}

// Download fetches every segment and assembles an MP4 at req.OutPath.
// Partial segments are kept in TmpDir when the download is interrupted
// (pause, process killed, network down) and reused by the next attempt; the
// directory is removed only after success.
func Download(ctx context.Context, req Request, progress func(Progress)) (Result, error) {
	res, err := download(ctx, req, progress)
	if err == nil {
		os.RemoveAll(req.TmpDir)
	}
	return res, err
}

func download(ctx context.Context, req Request, progress func(Progress)) (Result, error) {
	video, audioURL, err := LoadMedia(ctx, req.PlaylistURL, req.Headers)
	if err != nil {
		return Result{}, err
	}
	if req.AudioURL != "" {
		audioURL = req.AudioURL
	}
	if video.IsLive() {
		return Result{}, errors.New("live streams are not supported (the playlist has no #EXT-X-ENDLIST)")
	}
	if len(video.Segments) == 0 {
		return Result{}, errors.New("playlist without segments")
	}
	var audio *Playlist
	if audioURL != "" {
		if audio, err = fetchPlaylist(ctx, audioURL, req.Headers); err != nil {
			return Result{}, fmt.Errorf("audio playlist: %w", err)
		}
	}
	for _, pl := range []*Playlist{video, audio} {
		if pl == nil {
			continue
		}
		for _, s := range pl.Segments {
			if s.Key != nil && s.Key.Method != "AES-128" {
				return Result{}, fmt.Errorf("protected content (%s/DRM) cannot be downloaded", s.Key.Method)
			}
		}
	}

	if err := os.MkdirAll(req.TmpDir, 0o755); err != nil {
		return Result{}, err
	}

	jobs := buildJobs(video, "v", req.TmpDir)
	var audioJobs []segJob
	if audio != nil {
		audioJobs = buildJobs(audio, "a", req.TmpDir)
		jobs = append(jobs, audioJobs...)
	}
	logx.Infof("hls: %d video segments (%.0fs), %d audio segments, out=%s",
		len(video.Segments), video.TotalDuration, len(audioJobs), req.OutPath)

	if err := fetchAll(ctx, jobs, req.Headers, progress); err != nil {
		return Result{}, err
	}

	vFiles := paths(jobs[:len(video.Segments)])
	aFiles := paths(audioJobs)
	return assemble(ctx, req, video, audio, vFiles, aFiles)
}

type segJob struct {
	seg  Segment
	path string
}

func buildJobs(pl *Playlist, prefix, dir string) []segJob {
	out := make([]segJob, len(pl.Segments))
	for i, s := range pl.Segments {
		out[i] = segJob{seg: s, path: filepath.Join(dir, fmt.Sprintf("%s%06d.seg", prefix, i))}
	}
	return out
}

func paths(js []segJob) []string {
	out := make([]string, len(js))
	for i, j := range js {
		out[i] = j.path
	}
	return out
}

func fetchAll(ctx context.Context, jobs []segJob, h netx.Headers, progress func(Progress)) error {
	ctx, cancel := context.WithCancelCause(ctx)
	defer cancel(nil)

	keys := &keyCache{m: map[string][]byte{}}
	var done atomic.Int64
	var bytes atomic.Int64
	ch := make(chan segJob)
	var wg sync.WaitGroup
	for range segmentWorkers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := range ch {
				n, err := fetchSegment(ctx, j, h, keys)
				if err != nil {
					cancel(fmt.Errorf("segment %d: %w", j.seg.Seq, err))
					return
				}
				d := done.Add(1)
				b := bytes.Add(n)
				if progress != nil {
					progress(Progress{
						SegmentsDone:   int(d),
						SegmentsTotal:  len(jobs),
						Bytes:          b,
						EstimatedTotal: b * int64(len(jobs)) / d,
					})
				}
			}
		}()
	}
	for _, j := range jobs {
		select {
		case ch <- j:
		case <-ctx.Done():
		}
		if ctx.Err() != nil {
			break
		}
	}
	close(ch)
	wg.Wait()
	if err := context.Cause(ctx); err != nil && !errors.Is(err, context.Canceled) {
		return err
	}
	return ctx.Err()
}

func fetchSegment(ctx context.Context, j segJob, h netx.Headers, keys *keyCache) (int64, error) {
	// Already fetched by a previous attempt (written via rename: complete).
	if fi, err := os.Stat(j.path); err == nil && fi.Size() > 0 {
		return fi.Size(), nil
	}
	var data []byte
	err := netx.Retry(ctx, fmt.Sprintf("seg %d", j.seg.Seq), func() error {
		b, err := fetchRange(ctx, j.seg.URL, j.seg.Range, h)
		data = b
		return err
	})
	if err != nil {
		return 0, err
	}
	if k := j.seg.Key; k != nil {
		key, err := keys.get(ctx, k.URI, h)
		if err != nil {
			return 0, fmt.Errorf("AES key: %w", err)
		}
		iv := k.IV
		if iv == nil {
			iv = make([]byte, 16)
			binary.BigEndian.PutUint64(iv[8:], uint64(j.seg.Seq))
		}
		if data, err = decryptAES128(data, key, iv); err != nil {
			return 0, err
		}
	}
	tmp := j.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		return 0, err
	}
	return int64(len(data)), os.Rename(tmp, j.path)
}

func fetchRange(ctx context.Context, u string, r *ByteRange, h netx.Headers) ([]byte, error) {
	var extra map[string]string
	if r != nil {
		extra = map[string]string{"Range": fmt.Sprintf("bytes=%d-%d", r.Offset, r.Offset+r.Length-1)}
	}
	resp, err := netx.Do(ctx, http.MethodGet, u, h, extra)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	return io.ReadAll(resp.Body)
}

type keyCache struct {
	mu sync.Mutex
	m  map[string][]byte
}

func (c *keyCache) get(ctx context.Context, uri string, h netx.Headers) ([]byte, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if k, ok := c.m[uri]; ok {
		return k, nil
	}
	var key []byte
	err := netx.Retry(ctx, "key", func() error {
		b, _, err := netx.GetBytes(ctx, uri, h, 1024)
		key = b
		return err
	})
	if err != nil {
		return nil, err
	}
	if len(key) != 16 {
		return nil, fmt.Errorf("key has %d bytes (expected 16)", len(key))
	}
	c.m[uri] = key
	return key, nil
}

func decryptAES128(data, key, iv []byte) ([]byte, error) {
	if len(data)%aes.BlockSize != 0 {
		return nil, fmt.Errorf("encrypted segment with invalid size (%d)", len(data))
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	cipher.NewCBCDecrypter(block, iv).CryptBlocks(data, data)
	// PKCS#7 padding
	if n := len(data); n > 0 {
		p := int(data[n-1])
		if p > 0 && p <= aes.BlockSize && p <= n {
			data = data[:n-p]
		}
	}
	return data, nil
}

func assemble(ctx context.Context, req Request, video, audio *Playlist, vFiles, aFiles []string) (Result, error) {
	vFmt, err := media.SniffFile(vFiles[0])
	if err != nil {
		return Result{}, err
	}
	aFmt := media.FormatUnknown
	if len(aFiles) > 0 {
		if aFmt, err = media.SniffFile(aFiles[0]); err != nil {
			return Result{}, err
		}
	}
	logx.Infof("hls: assembling video=%v audio=%v", vFmt, aFmt)

	if req.FFmpeg.Available() {
		res, err := assembleFFmpeg(ctx, req, video, audio, vFiles, aFiles, vFmt, aFmt)
		if err == nil {
			return res, nil
		}
		if ctx.Err() != nil {
			return Result{}, ctx.Err()
		}
		logx.Warnf("hls: ffmpeg assembly failed (%v); falling back to Go remux", err)
	}

	res := Result{Path: req.OutPath}
	switch vFmt {
	case media.FormatFMP4:
		// Concatenate each rendition into a fragmented MP4, then remux to a
		// progressive MP4 (best compatibility with galleries/players).
		vTmp := filepath.Join(req.TmpDir, "video.fmp4")
		if err := concatRendition(ctx, req, video, vFiles, vTmp); err != nil {
			return Result{}, err
		}
		aTmp := ""
		if len(aFiles) > 0 {
			if aFmt != media.FormatFMP4 {
				res.Warning = fmt.Sprintf("separate audio in format %v is not supported; saved the video only", aFmt)
			} else {
				aTmp = filepath.Join(req.TmpDir, "audio.fmp4")
				if err := concatRendition(ctx, req, audio, aFiles, aTmp); err != nil {
					return Result{}, err
				}
			}
		}
		st, err := media.RemuxFMP4(req.OutPath, vTmp, aTmp)
		if err != nil {
			// Fallback: keep the fragmented MP4 as is (video track only when
			// audio was separate).
			logx.Warnf("hls: fmp4 remux failed (%v); saving fragmented MP4", err)
			if aTmp != "" {
				res.Warning = "could not merge the separate audio; saved the video only"
			}
			if err := os.Rename(vTmp, req.OutPath); err != nil {
				return Result{}, err
			}
			res.DurationMs = uint64(video.TotalDuration * 1000)
		} else {
			logx.Infof("hls: fmp4 remux ok video=%d audio=%d frames, %dms", st.VideoFrames, st.AudioFrames, st.DurationMs)
			res.DurationMs = st.DurationMs
		}
	case media.FormatTS:
		st, err := media.RemuxTS(req.OutPath, vFiles, aFiles, aFmt)
		if err != nil {
			return Result{}, fmt.Errorf("MP4 conversion: %w", err)
		}
		logx.Infof("hls: remux ok video=%d audio=%d frames, %dms", st.VideoFrames, st.AudioFrames, st.DurationMs)
		res.DurationMs = st.DurationMs
	default:
		return Result{}, fmt.Errorf("unrecognized segment format (%v)", vFmt)
	}

	if fi, err := os.Stat(req.OutPath); err == nil {
		res.Size = fi.Size()
	}
	return res, nil
}

// assembleFFmpeg joins each rendition into one file (TS/ADTS segments are
// concatenable as is; fMP4 needs its init section first) and lets ffmpeg copy
// the streams into an MP4. ffmpeg handles what the Go remux does not: signed
// composition offsets (X/Twitter), edit lists, timestamp discontinuities.
func assembleFFmpeg(ctx context.Context, req Request, video, audio *Playlist, vFiles, aFiles []string,
	vFmt, aFmt media.SegmentFormat) (Result, error) {
	join := func(pl *Playlist, files []string, f media.SegmentFormat, name string) (string, error) {
		out := filepath.Join(req.TmpDir, name)
		if f == media.FormatFMP4 {
			return out, concatRendition(ctx, req, pl, files, out)
		}
		return out, media.ConcatFMP4(out, nil, files) // plain byte concatenation
	}
	v, err := join(video, vFiles, vFmt, "video.join")
	if err != nil {
		return Result{}, err
	}
	inputs := []string{v}
	if len(aFiles) > 0 {
		a, err := join(audio, aFiles, aFmt, "audio.join")
		if err != nil {
			return Result{}, err
		}
		inputs = append(inputs, a)
	}
	if err := req.FFmpeg.CopyToMP4(ctx, inputs, req.OutPath); err != nil {
		return Result{}, err
	}
	fi, err := os.Stat(req.OutPath)
	if err != nil {
		return Result{}, err
	}
	logx.Infof("hls: ffmpeg assembly ok (%d inputs, %d bytes)", len(inputs), fi.Size())
	return Result{Path: req.OutPath, Size: fi.Size(), DurationMs: uint64(video.TotalDuration * 1000)}, nil
}

func concatRendition(ctx context.Context, req Request, pl *Playlist, files []string, out string) error {
	var init []byte
	if m := pl.Segments[0].Map; m != nil {
		var err error
		if init, err = fetchRange(ctx, m.URL, m.Range, req.Headers); err != nil {
			return fmt.Errorf("init segment: %w", err)
		}
	}
	return media.ConcatFMP4(out, init, files)
}

// EstimateSize is bandwidth × duration, for the quality list.
func EstimateSize(bandwidth int64, seconds float64) int64 {
	return int64(float64(bandwidth) / 8 * seconds)
}

// SortVariants orders by height, then H.264 before other codecs (it plays
// everywhere), then bandwidth — best first.
func SortVariants(vs []Variant) {
	sort.SliceStable(vs, func(i, j int) bool {
		if vs[i].Height != vs[j].Height {
			return vs[i].Height > vs[j].Height
		}
		if ai, aj := vs[i].IsAVC(), vs[j].IsAVC(); ai != aj {
			return ai
		}
		if ai, aj := vs[i].HasMuxableAudio(), vs[j].HasMuxableAudio(); ai != aj {
			return ai
		}
		return vs[i].Bandwidth > vs[j].Bandwidth
	})
}

// HasMuxableAudio is false for AC-3/E-AC-3/etc., which the MP4 muxer
// cannot carry; unknown codecs are assumed to be AAC.
func (v Variant) HasMuxableAudio() bool {
	c := strings.ToLower(v.Codecs)
	return !strings.Contains(c, "ac-3") && !strings.Contains(c, "ec-3") &&
		!strings.Contains(c, "ac-4") && !strings.Contains(c, "opus") && !strings.Contains(c, "flac")
}

// IsAVC reports H.264 video (or unknown codecs, which are usually H.264).
func (v Variant) IsAVC() bool {
	return v.Codecs == "" || strings.Contains(v.Codecs, "avc1") || strings.Contains(v.Codecs, "avc3")
}
