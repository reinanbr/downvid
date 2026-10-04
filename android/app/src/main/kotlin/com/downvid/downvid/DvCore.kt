package com.downvid.downvid

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * JNI view of the Go core (go/dvcore/jni_android.go). Loads the same
 * libdvcore.so that Dart opens via FFI, so both share one Go runtime and one
 * download queue.
 */
object DvCore {
    init {
        System.loadLibrary("dvcore")
    }

    @Volatile private var initialized = false

    /** JSON array of jobs (newest first): [{"id","state","title","bytes","percent",...}]. */
    @JvmStatic external fun nativeStatus(): String

    @JvmStatic external fun nativeCancel(id: Long): Boolean
    @JvmStatic external fun nativePause(id: Long): Boolean
    @JvmStatic external fun nativeResume(id: Long): Boolean

    /** Records where the file was published (history). */
    @JvmStatic external fun nativeMarkSaved(id: Long, savedJson: String)

    /** Loads the persisted queue and resumes interrupted downloads. */
    @JvmStatic private external fun nativeInit(configJson: String): String

    /**
     * Once per process: loads the queue from files/jobs and resumes what was
     * pending; starts the foreground service when something is active.
     */
    @Synchronized
    fun ensureInit(context: Context) {
        if (initialized) return
        initialized = true
        val dir = File(context.filesDir, "jobs").apply { mkdirs() }
        val cfg = JSONObject()
            .put("dir", dir.absolutePath)
            .put("ffmpeg", JSONObject(YtDlp.ffmpegPaths(context) as Map<*, *>))
            .put("maxConcurrent", Settings.maxConcurrent(context))
        val jobs = JSONArray(nativeInit(cfg.toString()))
        var active = 0
        for (i in 0 until jobs.length()) {
            if (jobs.getJSONObject(i).optString("state") in DownloadService.ACTIVE) active++
        }
        Log.i("DownVid", "queue: ${jobs.length()} jobs, $active active")
        if (active > 0) DownloadService.ensureRunning(context)
    }
}
