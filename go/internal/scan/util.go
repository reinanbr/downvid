package scan

import "io"

func readLimited(r io.Reader, n int64) ([]byte, error) {
	return io.ReadAll(io.LimitReader(r, n))
}

// Containers that are already MP4 (ISO BMFF) and can be saved as .mp4.
var mp4Types = map[string]bool{
	"video/mp4": true, "video/quicktime": true, "video/x-m4v": true,
	"video/iso.segment": true, "application/mp4": true,
}

// Servers that do not label the content; trusted only with an mp4 extension.
var genericTypes = map[string]bool{
	"": true, "application/octet-stream": true, "binary/octet-stream": true,
	"application/force-download": true, "application/download": true,
}

var incompatibleExt = map[string]bool{
	".webm": true, ".ogg": true, ".ogv": true, ".flv": true, ".mkv": true, ".avi": true, ".wmv": true,
}

func firstNonEmpty(s ...string) string {
	for _, v := range s {
		if v != "" {
			return v
		}
	}
	return ""
}
