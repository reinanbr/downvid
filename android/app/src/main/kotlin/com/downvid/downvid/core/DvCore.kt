package com.downvid.downvid.core

/**
 * The only JNI entry of the Go core (go/dvcore/jni_android.go, api.go).
 * Requests and responses are UTF-8 JSON byte arrays: JNI strings use
 * modified UTF-8, which mangles emoji in titles.
 */
object DvCore {
    init {
        System.loadLibrary("dvcore")
    }

    /** Response: {"ok":true,"data":...} or {"ok":false,"error":"...",...}. */
    @JvmStatic external fun nativeCall(method: String, request: ByteArray): ByteArray
}
