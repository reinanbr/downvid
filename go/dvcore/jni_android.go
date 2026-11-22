//go:build android

package main

// The single JNI entry point, for com.downvid.downvid.core.DvCore (Kotlin):
//
//	external fun nativeCall(method: String, request: ByteArray): ByteArray
//
// Request and response are UTF-8 JSON passed as byte arrays, which avoids
// JNI's "modified UTF-8" strings (they mangle characters outside the BMP,
// such as emoji in titles).

/*
#include <jni.h>
#include <stdlib.h>

static const char* dv_chars(JNIEnv* env, jstring s) {
	return (*env)->GetStringUTFChars(env, s, NULL);
}
static void dv_release(JNIEnv* env, jstring s, const char* c) {
	(*env)->ReleaseStringUTFChars(env, s, c);
}
static jsize dv_len(JNIEnv* env, jbyteArray a) {
	return a == NULL ? 0 : (*env)->GetArrayLength(env, a);
}
static void dv_read(JNIEnv* env, jbyteArray a, jsize n, void* dst) {
	(*env)->GetByteArrayRegion(env, a, 0, n, (jbyte*)dst);
}
static jbyteArray dv_bytes(JNIEnv* env, const void* p, jsize n) {
	jbyteArray a = (*env)->NewByteArray(env, n);
	if (a != NULL && n > 0) {
		(*env)->SetByteArrayRegion(env, a, 0, n, (const jbyte*)p);
	}
	return a;
}
*/
import "C"

import "unsafe"

//export Java_com_downvid_downvid_core_DvCore_nativeCall
func Java_com_downvid_downvid_core_DvCore_nativeCall(env *C.JNIEnv, clazz C.jclass, method C.jstring, request C.jbyteArray) C.jbyteArray {
	m := goStringJ(env, method)
	n := C.dv_len(env, request)
	req := make([]byte, int(n))
	if n > 0 {
		C.dv_read(env, request, n, unsafe.Pointer(&req[0]))
	}
	out := call(m, req)
	if len(out) == 0 {
		return C.dv_bytes(env, nil, 0)
	}
	return C.dv_bytes(env, unsafe.Pointer(&out[0]), C.jsize(len(out)))
}

func goStringJ(env *C.JNIEnv, s C.jstring) string {
	if s == 0 { // cgo maps JNI object types to uintptr
		return ""
	}
	cs := C.dv_chars(env, s)
	defer C.dv_release(env, s, cs)
	return C.GoString(cs)
}
