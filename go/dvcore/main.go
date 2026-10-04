// Package main is the downvid native core, built as a c-shared library
// (libdvcore.so) and loaded from Dart through dart:ffi.
//
// ABI rules (apply to every exported function):
//   - Strings crossing the boundary are NUL-terminated UTF-8.
//   - Every char* returned by Go was allocated with C.malloc and MUST be
//     released by the caller with DV_Free.
//   - Structured results are JSON: {"ok":true,"data":...} or
//     {"ok":false,"error":"..."}.
//   - Callbacks may be invoked from any OS thread (Go goroutines), so on the
//     Dart side they must be NativeCallable.listener.
package main

/*
#include <stdint.h>
#include <stdlib.h>

// Callback signature shared with Dart: (request id, json event).
// The json pointer is only valid for the duration of the call; the Dart
// listener receives it asynchronously, so Go hands over ownership and Dart
// must DV_Free it after reading.
typedef void (*dv_event_cb)(int64_t id, char* json);

static inline void dv_call_event(dv_event_cb cb, int64_t id, char* json) {
	cb(id, json);
}
*/
import "C"

import (
	"encoding/json"
	"runtime"
	"time"
	"unsafe"
)

// Version is overridden at build time via -ldflags "-X main.Version=...".
var Version = "0.1.0-dev"

type result struct {
	OK    bool   `json:"ok"`
	Data  any    `json:"data,omitempty"`
	Error string `json:"error,omitempty"`
}

func cJSON(v any) *C.char {
	b, err := json.Marshal(v)
	if err != nil {
		b, _ = json.Marshal(result{OK: false, Error: err.Error()})
	}
	return C.CString(string(b))
}

func ok(data any) *C.char       { return cJSON(result{OK: true, Data: data}) }
func fail(err error) *C.char    { return cJSON(result{OK: false, Error: err.Error()}) }
func goString(s *C.char) string { return C.GoString(s) }

//export DV_Free
func DV_Free(p *C.char) {
	if p != nil {
		C.free(unsafe.Pointer(p))
	}
}

//export DV_Version
func DV_Version() *C.char {
	return C.CString(Version)
}

// DV_Ping is a synchronous round-trip used to validate the FFI pipeline.
//
//export DV_Ping
func DV_Ping(msg *C.char) *C.char {
	return ok(map[string]any{
		"echo":    goString(msg),
		"version": Version,
		"goos":    runtime.GOOS,
		"goarch":  runtime.GOARCH,
		"go":      runtime.Version(),
		"time":    time.Now().UTC().Format(time.RFC3339Nano),
	})
}

// DV_PingAsync validates the Go -> Dart callback path: it emits `count`
// progress events from a goroutine (a non-Dart thread) and then a "done"
// event. Returns immediately.
//
//export DV_PingAsync
func DV_PingAsync(id C.int64_t, count C.int32_t, cb C.dv_event_cb) {
	go func() {
		n := int(count)
		for i := 1; i <= n; i++ {
			time.Sleep(100 * time.Millisecond)
			emit(cb, id, map[string]any{"type": "progress", "current": i, "total": n})
		}
		emit(cb, id, map[string]any{"type": "done", "goarch": runtime.GOARCH})
	}()
}

func emit(cb C.dv_event_cb, id C.int64_t, ev any) {
	b, _ := json.Marshal(ev)
	C.dv_call_event(cb, id, C.CString(string(b)))
}

func main() {}
