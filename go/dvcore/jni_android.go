//go:build android

package main

// JNI entry points for com.downvid.downvid.DvCore (Kotlin). They let the
// foreground service observe and cancel downloads without a Flutter engine.
// Kotlin loads the same libdvcore.so as Dart (System.loadLibrary), so both
// share one Go runtime and one job registry.

/*
#include <jni.h>
#include <stdlib.h>

static jstring dv_jstring(JNIEnv* env, const char* s) {
	return (*env)->NewStringUTF(env, s);
}
static const char* dv_chars(JNIEnv* env, jstring s) {
	return (*env)->GetStringUTFChars(env, s, NULL);
}
static void dv_release(JNIEnv* env, jstring s, const char* c) {
	(*env)->ReleaseStringUTFChars(env, s, c);
}
*/
import "C"

import (
	"encoding/json"
	"fmt"
	"strings"
	"unsafe"

	"github.com/downvid/core/internal/jobs"
	"github.com/downvid/core/internal/media"
)

//export Java_com_downvid_downvid_DvCore_nativeStatus
func Java_com_downvid_downvid_DvCore_nativeStatus(env *C.JNIEnv, clazz C.jclass) C.jstring {
	b, err := json.Marshal(jobs.Snapshot())
	if err != nil {
		b = []byte("[]")
	}
	cs := C.CString(asciiJSON(string(b)))
	defer C.free(unsafe.Pointer(cs))
	return C.dv_jstring(env, cs)
}

//export Java_com_downvid_downvid_DvCore_nativeCancel
func Java_com_downvid_downvid_DvCore_nativeCancel(env *C.JNIEnv, clazz C.jclass, id C.jlong) C.jboolean {
	if jobs.Cancel(int64(id)) {
		return C.JNI_TRUE
	}
	return C.JNI_FALSE
}

// nativeInit loads the persisted queue and resumes interrupted downloads.
// configJSON: {"dir","ffmpeg":{"path","libraryPath"},"maxConcurrent"}.
//
//export Java_com_downvid_downvid_DvCore_nativeInit
func Java_com_downvid_downvid_DvCore_nativeInit(env *C.JNIEnv, clazz C.jclass, config C.jstring) C.jstring {
	var cfg struct {
		Dir           string       `json:"dir"`
		FFmpeg        media.FFmpeg `json:"ffmpeg"`
		MaxConcurrent int          `json:"maxConcurrent"`
	}
	_ = json.Unmarshal([]byte(goStringJ(env, config)), &cfg)
	st, _ := jobs.Init(cfg.Dir, cfg.FFmpeg, cfg.MaxConcurrent)
	b, _ := json.Marshal(st)
	return newJString(env, string(b))
}

// nativeMarkSaved records where DownloadService published the file.
//
//export Java_com_downvid_downvid_DvCore_nativeMarkSaved
func Java_com_downvid_downvid_DvCore_nativeMarkSaved(env *C.JNIEnv, clazz C.jclass, id C.jlong, saved C.jstring) {
	var s jobs.Saved
	if json.Unmarshal([]byte(goStringJ(env, saved)), &s) == nil {
		jobs.MarkSaved(int64(id), s)
	}
}

//export Java_com_downvid_downvid_DvCore_nativePause
func Java_com_downvid_downvid_DvCore_nativePause(env *C.JNIEnv, clazz C.jclass, id C.jlong) C.jboolean {
	if jobs.Pause(int64(id)) {
		return C.JNI_TRUE
	}
	return C.JNI_FALSE
}

//export Java_com_downvid_downvid_DvCore_nativeResume
func Java_com_downvid_downvid_DvCore_nativeResume(env *C.JNIEnv, clazz C.jclass, id C.jlong) C.jboolean {
	if jobs.Resume(int64(id)) {
		return C.JNI_TRUE
	}
	return C.JNI_FALSE
}

func newJString(env *C.JNIEnv, s string) C.jstring {
	cs := C.CString(asciiJSON(s))
	defer C.free(unsafe.Pointer(cs))
	return C.dv_jstring(env, cs)
}

func goStringJ(env *C.JNIEnv, s C.jstring) string {
	if s == 0 { // cgo maps JNI object types to uintptr
		return ""
	}
	cs := C.dv_chars(env, s)
	defer C.dv_release(env, s, cs)
	return C.GoString(cs)
}

// asciiJSON escapes non-ASCII runes as \uXXXX: NewStringUTF expects
// "modified UTF-8", which differs from UTF-8 for characters outside the BMP.
func asciiJSON(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	for _, r := range s {
		switch {
		case r < 0x80:
			b.WriteRune(r)
		case r < 0x10000:
			fmt.Fprintf(&b, `\u%04x`, r)
		default:
			r -= 0x10000
			fmt.Fprintf(&b, `\u%04x\u%04x`, 0xD800+(r>>10), 0xDC00+(r&0x3FF))
		}
	}
	return b.String()
}
