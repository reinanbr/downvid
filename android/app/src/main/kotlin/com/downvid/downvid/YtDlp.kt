package com.downvid.downvid

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Thin wrapper over youtubedl-android: runs `yt-dlp -J` and exposes the
 * bundled ffmpeg to the Go core. Parsing, format selection and downloads
 * happen in Go.
 */
object YtDlp {
    private const val TAG = "DownVid"
    private val executor = Executors.newCachedThreadPool()
    private var initFuture: Future<*>? = null
    @Volatile private var initError: Throwable? = null
    private lateinit var appContext: Context
    private val server by lazy { YtDlpServer(appContext) }

    /** Starts initialization in background (first run unpacks Python ~ seconds). */
    @Synchronized
    fun warmUp(context: Context) {
        if (initFuture != null) return
        appContext = context.applicationContext
        initFuture = executor.submit {
            val t0 = System.currentTimeMillis()
            try {
                YoutubeDL.getInstance().init(appContext)
                FFmpeg.getInstance().init(appContext)
                Log.i(TAG, "ytdlp: ready in ${System.currentTimeMillis() - t0}ms")
                autoUpdate()
                // Load Python + yt-dlp now, before the first request needs it.
                runCatching { server.start() }.onFailure { Log.w(TAG, "ytdlp-server: start failed: ${it.message}") }
            } catch (e: Throwable) {
                initError = e
                Log.e(TAG, "ytdlp: init failed", e)
            }
        }
    }

    private const val PREFS = "downvid_ytdlp"
    private const val KEY_LAST_UPDATE = "lastUpdateCheck"
    private const val UPDATE_EVERY_MS = 3L * 24 * 60 * 60 * 1000

    /**
     * Keeps yt-dlp current (site extractors break often). Runs inside init,
     * so a rare check delays the first extraction by a few seconds instead
     * of racing with it.
     */
    private fun autoUpdate() {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_UPDATE, 0)
        if (System.currentTimeMillis() - last < UPDATE_EVERY_MS) return
        val t0 = System.currentTimeMillis()
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(appContext, YoutubeDL.UpdateChannel.STABLE)
            prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
            Log.i(TAG, "ytdlp: auto-update $status in ${System.currentTimeMillis() - t0}ms -> ${YoutubeDL.getInstance().versionName(appContext)}")
        } catch (e: Throwable) {
            Log.w(TAG, "ytdlp: auto-update failed (keeping current version): ${e.message}")
        }
    }

    // ---- early start: ShareActivity begins extraction before Flutter boots

    private val prefetched = HashMap<String, Future<InfoResult>>()

    /** Starts `-J` for a shared URL right away; [info] reuses the result. */
    @Synchronized
    fun prefetch(url: String) {
        if (prefetched.containsKey(url)) return
        Log.i(TAG, "ytdlp: prefetch $url")
        prefetched[url] = executor.submit<InfoResult> { runInfo(url, "prefetch-${url.hashCode()}") }
    }

    @Synchronized
    private fun takePrefetched(url: String): Future<InfoResult>? = prefetched.remove(url)

    /** Waits for init (used by UpdateReceiver within its broadcast window). */
    fun awaitReady(timeout: Long, unit: java.util.concurrent.TimeUnit) {
        runCatching { initFuture?.get(timeout, unit) }
    }

    private fun awaitInit() {
        val f = initFuture ?: error("YtDlp.warmUp was not called")
        f.get() // Runnable future: returns null once init finished
        initError?.let { throw it }
    }

    data class InfoResult(val json: String, val stderr: String, val exitCode: Int, val elapsedMs: Long)

    /** Runs `yt-dlp -J` (blocking; call off the main thread). */
    fun info(url: String, processId: String): InfoResult {
        takePrefetched(url)?.let {
            val r = it.get()
            Log.i(TAG, "ytdlp: using prefetched result for $url")
            return r
        }
        return runInfo(url, processId)
    }

    private fun runInfo(url: String, processId: String): InfoResult {
        awaitInit()
        val cacheDir = File(appContext.cacheDir, "yt-dlp").absolutePath
        try {
            val r = server.info(url, cacheDir, quickJs())
            Log.i(TAG, "ytdlp: -J (server) exit=${r.exitCode} in ${r.elapsedMs}ms (${r.json.length} chars) $url")
            if (r.stderr.isNotBlank()) Log.d(TAG, "ytdlp stderr: ${r.stderr.take(1500)}")
            return r
        } catch (e: Exception) {
            Log.w(TAG, "ytdlp: server unavailable (${e.message}); running yt-dlp directly")
        }
        return runInfoProcess(url, processId)
    }

    /** One-shot `yt-dlp -J` process (fallback; pays Python startup each time). */
    private fun runInfoProcess(url: String, processId: String): InfoResult {
        val req = YoutubeDLRequest(url).apply {
            addOption("-J")
            addOption("--no-playlist")
            addOption("--no-check-certificates")
            addOption("--socket-timeout", "20")
            addOption("--no-update")
            // Persist yt-dlp's cache (decoded YouTube player, etc.) between
            // runs; the library disables it by default.
            addOption("--cache-dir", File(appContext.cacheDir, "yt-dlp").absolutePath)
            // YouTube's JS challenges: use the QuickJS shipped with the library
            // (yt-dlp vendors the solver script; it only needs a runtime).
            quickJs()?.let { addOption("--js-runtimes", "quickjs:$it") }
        }
        val t0 = System.currentTimeMillis()
        return try {
            val r = YoutubeDL.getInstance().execute(req, processId)
            Log.i(TAG, "ytdlp: -J ok in ${r.elapsedTime}ms (${r.out.length} chars) $url")
            if (r.err.isNotBlank()) Log.d(TAG, "ytdlp stderr: ${r.err.take(1500)}")
            InfoResult(r.out, r.err, r.exitCode, r.elapsedTime)
        } catch (e: YoutubeDL.CanceledException) {
            InfoResult("", "ERROR: canceled", 130, System.currentTimeMillis() - t0)
        } catch (e: YoutubeDLException) {
            val err = e.message ?: e.toString()
            Log.w(TAG, "ytdlp: -J failed in ${System.currentTimeMillis() - t0}ms: ${err.take(1500)}")
            InfoResult("", err, 1, System.currentTimeMillis() - t0)
        }
    }

    private fun quickJs(): String? =
        File(appContext.applicationInfo.nativeLibraryDir, "libqjs.so").takeIf { it.exists() }?.absolutePath

    /** Flat search (e.g. a YouTube Music search URL): entries only. Server only. */
    fun search(url: String): InfoResult {
        awaitInit()
        val r = server.info(url, File(appContext.cacheDir, "yt-dlp").absolutePath, quickJs(), flat = true)
        Log.i(TAG, "ytdlp: search exit=${r.exitCode} in ${r.elapsedMs}ms $url")
        return r
    }

    fun cancel(processId: String) {
        YoutubeDL.getInstance().destroyProcessById(processId)
    }

    /** ffmpeg executable + its library dir, for the Go core (os/exec). */
    fun ffmpegTools(): Map<String, String> {
        awaitInit()
        return ffmpegPaths(appContext)
    }

    /** Same paths without waiting for init (they are fixed per install). */
    fun ffmpegPaths(context: Context): Map<String, String> {
        val bin = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val base = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val libs = listOf(File(base, "ffmpeg/usr/lib"), File(base, "python/usr/lib"))
            .joinToString(":") { it.absolutePath }
        return mapOf("path" to bin.absolutePath, "libraryPath" to libs)
    }

    fun version(): String? {
        awaitInit()
        return YoutubeDL.getInstance().versionName(appContext) ?: YoutubeDL.getInstance().version(appContext)
    }

    /** Updates yt-dlp to the latest stable release. Returns the new version. */
    fun update(): String? {
        awaitInit()
        val status = YoutubeDL.getInstance().updateYoutubeDL(appContext, YoutubeDL.UpdateChannel.STABLE)
        server.stop("yt-dlp updated") // next request starts it with the new version
        Log.i(TAG, "ytdlp: update -> $status, version=${version()}")
        return version()
    }

    /** Runs [block] on the yt-dlp executor. */
    fun background(block: () -> Unit) = executor.execute(block)
}
