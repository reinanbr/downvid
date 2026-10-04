package com.downvid.downvid

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Resident Python process running assets/ytdlp_server.py. Starting Python and
 * importing yt-dlp costs ~7 s on a phone; this pays it once, so extractions
 * only cost the network round-trips. Stops itself after a few idle minutes
 * to give the memory back.
 *
 * Uses the same Python/yt-dlp/env that youtubedl-android sets up.
 */
class YtDlpServer(private val context: Context) {
    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private var ready = CompletableFuture<String>()
    private val pending = ConcurrentHashMap<String, CompletableFuture<YtDlp.InfoResult>>()
    private val seq = AtomicLong()
    private val main = Handler(Looper.getMainLooper())
    private val idleStop = Runnable { stop("idle") }

    @Synchronized
    fun start() {
        if (process?.isAlive == true) return
        val t0 = System.currentTimeMillis()
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val base = File(context.noBackupFilesDir, "youtubedl-android")
        val pythonDir = File(base, "packages/python")
        val script = File(context.filesDir, "ytdlp_server.py")
        context.assets.open("ytdlp_server.py").use { input -> script.outputStream().use { input.copyTo(it) } }

        val pb = ProcessBuilder(
            File(nativeDir, "libpython.so").absolutePath,
            script.absolutePath,
            File(base, "yt-dlp/yt-dlp").absolutePath,
        )
        pb.environment().apply {
            this["LD_LIBRARY_PATH"] = listOf("packages/python", "packages/ffmpeg", "packages/aria2c")
                .joinToString(":") { File(base, "$it/usr/lib").absolutePath }
            this["SSL_CERT_FILE"] = File(pythonDir, "usr/etc/tls/cert.pem").absolutePath
            this["PYTHONHOME"] = File(pythonDir, "usr").absolutePath
            this["HOME"] = File(pythonDir, "usr").absolutePath
            this["TMPDIR"] = context.cacheDir.absolutePath
            this["PATH"] = System.getenv("PATH") + ":" + nativeDir.absolutePath
            this["PYTHONUNBUFFERED"] = "1"
        }
        val p = pb.start()
        process = p
        stdin = p.outputStream.bufferedWriter()
        val readyFuture = CompletableFuture<String>()
        ready = readyFuture

        Thread({
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                    if (o.optBoolean("ready")) {
                        Log.i(TAG, "ytdlp-server: ready in ${System.currentTimeMillis() - t0}ms (yt-dlp ${o.optString("version")})")
                        readyFuture.complete(o.optString("version"))
                        return@forEachLine
                    }
                    pending.remove(o.optString("id"))?.complete(
                        YtDlp.InfoResult(o.optString("json"), o.optString("stderr"), o.optInt("exitCode", 1), 0),
                    )
                }
            } catch (e: java.io.IOException) {
                // Expected when stop() closes the process (idle / update).
                Log.d(TAG, "ytdlp-server: stdout closed (${e.message})")
            } finally {
                val err = IllegalStateException("ytdlp-server exited")
                readyFuture.completeExceptionally(err)
                pending.values.forEach { it.completeExceptionally(err) }
                pending.clear()
                Log.i(TAG, "ytdlp-server: exited (${runCatching { p.exitValue() }.getOrNull()})")
            }
        }, "ytdlp-server-out").start()

        Thread({
            try {
                p.errorStream.bufferedReader().forEachLine { Log.w(TAG, "ytdlp-server: $it") }
            } catch (e: java.io.IOException) {
                Log.d(TAG, "ytdlp-server: stderr closed (${e.message})")
            }
        }, "ytdlp-server-err").start()
    }

    /** Extracts `url`; throws if the server is unavailable (caller falls back). */
    fun info(url: String, cacheDir: String, qjs: String?, flat: Boolean = false, timeoutSec: Long = 120): YtDlp.InfoResult {
        start()
        ready.get(30, TimeUnit.SECONDS)
        main.removeCallbacks(idleStop)
        val id = "r${seq.incrementAndGet()}"
        val f = CompletableFuture<YtDlp.InfoResult>()
        pending[id] = f
        val req = JSONObject().put("id", id).put("url", url).put("cacheDir", cacheDir)
        if (qjs != null) req.put("qjs", qjs)
        if (flat) req.put("flat", true).put("playlistEnd", 6)
        synchronized(this) {
            stdin!!.apply { write(req.toString()); newLine(); flush() }
        }
        val t0 = System.currentTimeMillis()
        try {
            return f.get(timeoutSec, TimeUnit.SECONDS).copy(elapsedMs = System.currentTimeMillis() - t0)
        } finally {
            pending.remove(id)
            if (pending.isEmpty()) main.postDelayed(idleStop, IDLE_STOP_MS)
        }
    }

    @Synchronized
    fun stop(reason: String) {
        process?.let {
            Log.i(TAG, "ytdlp-server: stopping ($reason)")
            it.destroy()
        }
        process = null
        stdin = null
    }

    companion object {
        private const val TAG = "DownVid"
        private const val IDLE_STOP_MS = 5 * 60 * 1000L
    }
}
