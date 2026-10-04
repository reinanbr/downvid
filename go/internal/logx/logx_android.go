//go:build android

package logx

/*
#cgo LDFLAGS: -llog
#include <android/log.h>
#include <stdlib.h>
*/
import "C"

import "unsafe"

const (
	prioDebug = C.ANDROID_LOG_DEBUG
	prioInfo  = C.ANDROID_LOG_INFO
	prioWarn  = C.ANDROID_LOG_WARN
	prioError = C.ANDROID_LOG_ERROR
)

var cTag = C.CString(Tag)

func write(prio C.int, msg string) {
	// logcat truncates long lines (~4 KB); split so URLs are never cut.
	for len(msg) > 0 {
		n := min(len(msg), 3000)
		cs := C.CString(msg[:n])
		C.__android_log_write(prio, cTag, cs)
		C.free(unsafe.Pointer(cs))
		msg = msg[n:]
	}
}
