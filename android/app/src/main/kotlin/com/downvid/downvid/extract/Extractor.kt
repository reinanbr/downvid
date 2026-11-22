package com.downvid.downvid.extract

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.webkit.WebSettings
import androidx.compose.runtime.Immutable
import com.downvid.downvid.InstagramLoginActivity
import com.downvid.downvid.InstagramQuery
import com.downvid.downvid.PageSniffer
import com.downvid.downvid.YtDlp
import com.downvid.downvid.core.Candidate
import com.downvid.downvid.core.DownloadRequest
import com.downvid.downvid.core.ExtractResult
import com.downvid.downvid.core.FFmpegPaths
import com.downvid.downvid.core.GoCore
import com.downvid.downvid.core.GoError
import com.downvid.downvid.core.Headers
import com.downvid.downvid.core.Job
import com.downvid.downvid.core.JobController
import com.downvid.downvid.core.JobMeta
import com.downvid.downvid.core.MediaOption
import com.downvid.downvid.core.MusicBasic
import com.downvid.downvid.core.MusicMeta
import com.downvid.downvid.core.SavedFile
import com.downvid.downvid.core.Settings
import com.downvid.downvid.core.Skipped
import com.downvid.downvid.ui.formatBytes
import com.downvid.downvid.ui.nonBlank
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.abs

enum class DownloadPhase { IDLE, DOWNLOADING, MUXING, CONVERTING, SAVING, SAVED, FAILED, CANCELED }

enum class MediaMode { VIDEO, AUDIO }

/** Immutable snapshot of an extraction, for the UI. */
@Immutable
data class ExtractState(
    val title: String? = null,
    val thumbnail: String? = null,
    val mode: MediaMode = MediaMode.VIDEO,
    val options: List<MediaOption> = emptyList(),
    val audioOptions: List<MediaOption> = emptyList(),
    val skipped: List<Skipped> = emptyList(),
    val selected: MediaOption? = null,
    val musicMeta: MusicMeta? = null,
    val resolvingMeta: Boolean = false,
    val matchStatus: String? = null,
    val ytdlpRunning: Boolean = false,
    val ytdlpError: String? = null,
    val genericStarted: Boolean = false,
    val staticScanning: Boolean = false,
    val sniffing: Boolean = false,
    val sniffedCount: Int = 0,
    val pendingProbes: Int = 0,
    val scanError: String? = null,
    val needsInstagramLogin: Boolean = false,
    val needsThreadsLogin: Boolean = false,
    val checkedItems: Set<Int> = emptySet(),
    // Download (followed through JobController).
    val phase: DownloadPhase = DownloadPhase.IDLE,
    val percent: Double? = null,
    val bytes: Long = 0,
    val total: Long = -1,
    val totalEstimate: Boolean = false,
    val speedBps: Double = 0.0,
    val segmentsDone: Int = 0,
    val segmentsTotal: Int = 0,
    val error: String? = null,
    val warning: String? = null,
    val saved: SavedFile? = null,
    val itemsTotal: Int = 0,
    val itemsSaved: Int = 0,
    val itemsFailed: Int = 0,
) {
    val scanning get() = ytdlpRunning || staticScanning || sniffing || pendingProbes > 0

    /** Tiny files (previews of related videos, HLS fragments) are hidden as
     *  soon as the page has a real video. */
    val visibleOptions: List<MediaOption> = options.filter { !it.small }.ifEmpty { options }
    val hiddenPreviews get() = options.size - visibleOptions.size
    val modeOptions get() = if (mode == MediaMode.AUDIO) audioOptions else visibleOptions
    val hasAudio get() = audioOptions.isNotEmpty()
    val hasVideo get() = options.isNotEmpty()
    val busy
        get() = phase == DownloadPhase.DOWNLOADING || phase == DownloadPhase.MUXING ||
            phase == DownloadPhase.CONVERTING || phase == DownloadPhase.SAVING
    val isCarousel get() = options.any { it.itemCount > 1 }
    val carouselItems get() = options.filter { it.item > 0 }.sortedBy { it.item }
}

/** The WebView user agent (cached: the first call loads the WebView provider). */
object WebUa {
    @Volatile private var ua: String? = null
    fun get(activity: Activity): String = ua ?: runCatching { WebSettings.getDefaultUserAgent(activity) }.getOrDefault("").also { ua = it }
}

/**
 * Extraction and download for one link (port of the Flutter
 * ExtractController):
 *  - platform links (YouTube, Instagram...): yt-dlp first; when it does not
 *    support the link, the generic scan runs automatically;
 *  - generic links: static scan in Go + hidden-WebView sniffing, with yt-dlp
 *    in parallel (its site extractors find embedded players);
 *  - every URL found is resolved by Go into options. Downloads are jobs of
 *    [JobController]: they continue (and are saved by DownloadService) when
 *    the sheet is closed; this class only follows their progress.
 *
 * Main thread only. [activity] hosts the hidden WebViews.
 */
class Extractor(
    private val activity: Activity,
    val pageUrl: String,
    /** Display name of the platform (YouTube, Instagram...), for messages. */
    val platform: String? = null,
    /** Platform link: yt-dlp is the primary extractor. */
    val isPlatform: Boolean = false,
    /** Spotify/Deezer/Apple Music: metadata from the link, audio from YouTube Music. */
    val isMusicService: Boolean = false,
    preferAudio: Boolean = false,
    musicContext: Boolean = false,
    /** Headers of the page the link came from (the in-app browser). */
    private val pageHeaders: Headers? = null,
) {
    constructor(activity: Activity, link: SharedLink, preferAudio: Boolean = false) : this(
        activity, link.url,
        platform = if (link.isPlatform) link.platform.label else null,
        isPlatform = link.isPlatform,
        isMusicService = link.isMusicService,
        preferAudio = preferAudio,
        musicContext = link.prefersAudio,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ExtractState(mode = if (preferAudio) MediaMode.AUDIO else MediaMode.VIDEO))
    val state: StateFlow<ExtractState> = _state.asStateFlow()

    /** Music link: metadata trusts "Artist - Title" in the title even without a catalog match. */
    private val musicContext = musicContext || preferAudio

    // Working copies of the state fields (published by notifyChanged()).
    private var mode = _state.value.mode
    private var title: String? = null
    private var thumbnail: String? = null
    private val options = ArrayList<MediaOption>()
    private val audioOptions = ArrayList<MediaOption>()
    private val skipped = ArrayList<Skipped>()
    private var selected: MediaOption? = null
    private var userPicked = false
    private var musicBasic: MusicBasic? = null
    private var linkMeta: MusicMeta? = null
    private var musicMeta: MusicMeta? = null
    private var metaJob: Deferred<Unit>? = null
    private var matchStatus: String? = null
    private var ytdlpRunning = false
    private var ytdlpError: String? = null
    private var ytdlpUnsupported = false
    private var genericStarted = false
    private var staticScanning = false
    private var sniffing = false
    private var sniffedCount = 0
    private var pendingProbes = 0
    private var scanError: String? = null
    private var needsInstagramLogin = false
    private var needsThreadsLogin = false
    private val checkedItems = LinkedHashSet<Int>()

    private val ytdlpId = "x${System.nanoTime()}"
    private var userAgent = ""
    private val known = HashSet<String>()
    private val queue = ArrayList<PageSniffer.Found>()
    private var flushJob: kotlinx.coroutines.Job? = null
    private var sniffer: PageSniffer? = null
    private var startJob: kotlinx.coroutines.Job? = null
    private var trackJob: kotlinx.coroutines.Job? = null
    private var jobIds: List<Long> = emptyList()
    private var disposed = false

    private val headers get() = Headers(userAgent = userAgent.ifEmpty { null })

    /** All options (video + audio), for matching an optionKey. */
    val allOptions get() = options + audioOptions

    private fun notifyChanged() {
        val d = _state.value
        _state.value = d.copy(
            title = title, thumbnail = thumbnail, mode = mode,
            options = options.toList(), audioOptions = audioOptions.toList(), skipped = skipped.toList(),
            selected = selected, musicMeta = musicMeta, resolvingMeta = metaJob?.isActive == true,
            matchStatus = matchStatus, ytdlpRunning = ytdlpRunning, ytdlpError = ytdlpError,
            genericStarted = genericStarted, staticScanning = staticScanning, sniffing = sniffing,
            sniffedCount = sniffedCount, pendingProbes = pendingProbes, scanError = scanError,
            needsInstagramLogin = needsInstagramLogin, needsThreadsLogin = needsThreadsLogin,
            checkedItems = checkedItems.toSet(),
        )
    }

    private inline fun setDl(change: ExtractState.() -> ExtractState) {
        _state.value = _state.value.change()
    }

    private val busy get() = _state.value.busy

    // ------------------------------------------------------------ extraction

    fun start() {
        if (startJob != null) return
        Log.i(TAG, "extract: start $pageUrl platform=$platform")
        known += pageUrl
        startJob = scope.launch {
            userAgent = pageHeaders?.userAgent ?: WebUa.get(activity)
            when {
                isMusicService -> runMusicLink()
                platform == SourcePlatform.INSTAGRAM.label -> if (!runInstagram()) runYtdlp()
                // Post read from the Threads page. Otherwise (login wall,
                // profile links): yt-dlp, then the page scan.
                platform == SourcePlatform.THREADS.label && runThreads() -> Unit
                isPlatform -> {
                    val ok = runYtdlp()
                    // Link not handled by yt-dlp (e.g. Kwai): look for videos in the page.
                    if (!ok && ytdlpUnsupported) startGeneric()
                }
                else -> {
                    launch { runYtdlp() }
                    startGeneric()
                }
            }
        }
    }

    /** Generic page scan (static + WebView). Also offered as a button after a platform error. */
    /**
     * From the in-app browser: the page is already loaded and its media
     * detected ([detected]), so there is no hidden-WebView scan; yt-dlp
     * runs alongside.
     */
    fun startDetected(detected: ExtractResult) {
        if (startJob != null) return
        Log.i(TAG, "extract: browser page $pageUrl platform=$platform (${detected.options.size} detected)")
        known += pageUrl
        userAgent = pageHeaders?.userAgent ?: WebUa.get(activity)
        addResult(detected)
        pickDefault()
        notifyChanged()
        // yt-dlp knows many more sites than the platform list (and their
        // full-quality formats); for other pages it fails quietly.
        startJob = scope.launch {
            runYtdlp()
            pickDefault()
            notifyChanged()
        }
    }

    fun startGeneric() {
        if (genericStarted || disposed) return
        genericStarted = true
        scope.launch { staticScan() }
        startSniffer()
        notifyChanged()
    }

    /** Runs yt-dlp -J and converts its output into options (Go). */
    private suspend fun runYtdlp(url: String = pageUrl, wantDuration: Double? = null): Boolean {
        ytdlpRunning = true
        notifyChanged()
        val t0 = SystemClock.elapsedRealtime()
        try {
            // Browser pages: the tab's cookies (age gates, sign-ins). Not its
            // User-Agent: extractors pick their own.
            val session = pageHeaders?.let { h ->
                buildMap {
                    h.cookie?.takeIf { it.isNotEmpty() }?.let { put("Cookie", it) }
                    h.referer?.let { put("Referer", it) }
                }
            }.orEmpty()
            val r = withContext(Dispatchers.IO) { YtDlp.info(url, ytdlpId, session) }
            if (disposed) return false
            val data = GoCore.ytdlp(r.json, r.stderr, r.exitCode, platform ?: LinkParser.hostOf(pageUrl).orEmpty())
            Log.i(TAG, "extract: yt-dlp ${SystemClock.elapsedRealtime() - t0}ms (-J ${r.elapsedMs}ms) extractor=${data.extractor} options=${data.options.size}")
            val basic = data.music
            if (wantDuration != null && wantDuration > 0) {
                val got = basic?.duration ?: 0.0
                if (got > 0 && abs(got - wantDuration) >= 5) {
                    Log.i(TAG, "extract: candidate rejected: duration ${got}s vs ${wantDuration}s")
                    return false
                }
            }
            // yt-dlp's metadata beats the page's (real title, HD thumb), but a
            // music-service link keeps its own.
            if (linkMeta == null) {
                title = data.title.nonBlank() ?: title
                thumbnail = data.thumbnail.nonBlank() ?: thumbnail
            }
            musicBasic = basic
            merge(data)
            val existing = audioOptions.mapTo(HashSet()) { it.key }
            for (o in data.audioOptions) if (existing.add(o.key)) audioOptions += o
            if (options.isEmpty() && audioOptions.isNotEmpty()) mode = MediaMode.AUDIO
            if (mode == MediaMode.AUDIO) {
                pickDefault()
                scope.launch { resolveMeta() }
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: GoError) {
            Log.i(TAG, "extract: yt-dlp failed in ${SystemClock.elapsedRealtime() - t0}ms: ${e.message} unsupported=${e.unsupported}")
            ytdlpUnsupported = e.unsupported
            // For generic links the page scan is the main path: stay quiet.
            if (isPlatform) ytdlpError = e.message
            return false
        } catch (e: Exception) {
            Log.w(TAG, "extract: yt-dlp error", e)
            if (isPlatform) ytdlpError = "Extractor failed: ${e.message ?: e}"
            return false
        } finally {
            ytdlpRunning = false
            notifyChanged()
        }
    }

    private suspend fun staticScan() {
        staticScanning = true
        notifyChanged()
        val t0 = SystemClock.elapsedRealtime()
        try {
            val r = GoCore.probe(pageUrl, emptyList(), pageHeaders ?: headers, skipPage = false)
            Log.i(TAG, "extract: static scan ${SystemClock.elapsedRealtime() - t0}ms options=${r.options.size} skipped=${r.skipped.size}")
            title = title ?: r.title.nonBlank()
            thumbnail = thumbnail ?: r.thumbnail.nonBlank()
            merge(r)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "extract: static scan failed: ${e.message}")
            scanError = e.message ?: "$e"
        } finally {
            staticScanning = false
            notifyChanged()
        }
    }

    private fun startSniffer() {
        sniffing = true
        val s = PageSniffer(activity, onFound = ::onSniffed, onDone = ::onSniffDone)
        sniffer = s
        try {
            s.start(pageUrl)
        } catch (e: Exception) {
            Log.w(TAG, "extract: sniffer start failed", e)
            sniffer = null
            sniffing = false
        }
    }

    private fun onSniffed(f: PageSniffer.Found) {
        if (disposed) return
        sniffedCount++
        if (known.add(f.url)) {
            queue += f
            // Batch URLs that arrive together (master + variants...).
            flushJob?.cancel()
            flushJob = scope.launch {
                delay(FLUSH_MS)
                flushQueue()
            }
        }
        notifyChanged()
    }

    private fun onSniffDone(r: PageSniffer.Result) {
        if (disposed) return
        Log.i(TAG, "extract: sniff done reason=${r.reason} count=${r.found.size} title=${r.title}")
        title = title ?: r.title.nonBlank()
        if (r.userAgent.isNotEmpty()) userAgent = r.userAgent
        sniffing = false
        sniffer = null
        flushJob?.cancel()
        scope.launch { flushQueue() }
        notifyChanged()
    }

    private suspend fun flushQueue() {
        if (queue.isEmpty() || disposed) return
        val batch = queue.toList()
        queue.clear()
        pendingProbes++
        notifyChanged()
        try {
            val r = GoCore.probe(
                pageUrl,
                batch.map { Candidate(it.url, "webview", referer = it.referer, cookie = it.cookie) },
                headers,
                skipPage = true,
            )
            merge(r)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "extract: probe of sniffed urls failed: ${e.message}")
        } finally {
            pendingProbes--
            notifyChanged()
        }
    }

    /** Adds options found elsewhere (the browser's media detector). */
    fun addResult(r: ExtractResult) {
        title = title ?: r.title.nonBlank()
        thumbnail = thumbnail ?: r.thumbnail.nonBlank()
        merge(r)
        notifyChanged()
    }

    private fun merge(r: ExtractResult) {
        val existing = options.mapTo(HashSet()) { it.key }
        for (o in r.options) {
            known += o.group
            known += o.url
            if (existing.add(o.key)) {
                options += o
                Log.i(TAG, "extract: option ${o.kind} \"${o.label}\" size=${o.size} small=${o.small} src=${o.source} ${o.url}")
            }
        }
        for (s in r.skipped) {
            known += s.url
            skipped += s
        }
        options.sortWith(COMPARE)
        // Carousel: every item starts ticked.
        if (checkedItems.isEmpty() && options.any { it.itemCount > 1 }) {
            options.filter { it.item > 0 }.mapTo(checkedItems) { it.item }
        }
        // Re-pick when the current choice became hidden (a real video showed up).
        if (mode == MediaMode.VIDEO && !busy && (!userPicked || selected !in visibleOptions())) {
            selected = defaultOption()
        }
    }

    private fun visibleOptions() = options.filter { !it.small }.ifEmpty { options }

    /** The option matching the user's defaults: the closest quality not above
     *  the chosen maximum, or the chosen audio format. */
    private fun defaultOption(): MediaOption? {
        val s = Settings.current
        if (mode == MediaMode.AUDIO) {
            if (audioOptions.isEmpty()) return null
            val (fmt, q) = when (s.audioFormat) {
                "mp3_v0" -> "mp3" to "v0"
                "mp3_320" -> "mp3" to "320"
                else -> "m4a" to null
            }
            return audioOptions.firstOrNull { it.audioFormat == fmt && (q == null || it.audioQuality == q) }
                ?: audioOptions.firstOrNull { it.audioFormat == fmt }
                ?: audioOptions.first()
        }
        val v = visibleOptions()
        if (v.isEmpty()) return null
        if (s.videoMaxHeight > 0) {
            v.firstOrNull { it.height in 1..s.videoMaxHeight }?.let { return it }
        }
        return v.first()
    }

    private fun pickDefault() {
        if (!userPicked && !busy) selected = defaultOption()
    }

    fun setMode(m: MediaMode) {
        if (busy || mode == m) return
        mode = m
        userPicked = false
        selected = defaultOption()
        if (m == MediaMode.AUDIO) scope.launch { resolveMeta() }
        notifyChanged()
    }

    fun select(o: MediaOption) {
        if (busy) return
        userPicked = true
        selected = o
        notifyChanged()
    }

    // ------------------------------------------------------------ Instagram / Threads

    /**
     * Instagram post/reel/story, queried by the app's WebView: posts try the
     * public (logged-out) query first; when Instagram refuses it, or for
     * stories, the user's own session (signed in once in the app).
     * Returns whether Instagram gave a definitive answer (else: yt-dlp).
     */
    private suspend fun runInstagram(): Boolean {
        val q = try {
            JSONObject(GoCore.instaQuery(pageUrl, threads = false))
        } catch (e: GoError) {
            Log.i(TAG, "extract: instagram: ${e.message}")
            return false
        }
        val story = q.optString("kind") == "story"
        ytdlpRunning = true
        needsInstagramLogin = false
        ytdlpError = null
        matchStatus = "Querying Instagram…"
        notifyChanged()
        val t0 = SystemClock.elapsedRealtime()
        var loggedIn = false
        try {
            loggedIn = InstagramLoginActivity.isLoggedIn(InstagramLoginActivity.Site.INSTAGRAM)
            var data: ExtractResult? = null
            if (!story) {
                try {
                    data = instaQuery(q, "public")
                } catch (e: GoError) {
                    // Not public, or blocked (HTML instead of JSON): the session may still work.
                    if (!loggedIn || e.code == "notfound") throw e
                    Log.i(TAG, "extract: instagram: public query failed (${e.code}), retrying with the session")
                }
            } else if (!loggedIn) {
                throw GoError("Stories are only visible when signed in to Instagram.", code = "unavailable")
            }
            if (data == null) {
                matchStatus = "Querying Instagram with your account…"
                notifyChanged()
                data = instaQuery(q, if (story) "story" else "media")
            }
            Log.i(TAG, "extract: instagram ${SystemClock.elapsedRealtime() - t0}ms options=${data.options.size}")
            takePost(data)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: GoError) {
            Log.i(TAG, "extract: instagram failed in ${SystemClock.elapsedRealtime() - t0}ms: ${e.message} (${e.code})")
            ytdlpError = e.message
            // Not public / expired session: signing in (again) can solve it.
            needsInstagramLogin = e.code == "unavailable" || e.code == "login" || (!loggedIn && e.code != "notfound")
            return true
        } catch (e: Exception) {
            Log.i(TAG, "extract: instagram query error: ${e.message}")
            return false
        } finally {
            matchStatus = null
            ytdlpRunning = false
            notifyChanged()
        }
    }

    private fun takePost(data: ExtractResult) {
        title = data.title.nonBlank() ?: title
        thumbnail = data.thumbnail.nonBlank() ?: thumbnail
        data.music?.takeIf { it.duration > 0 }?.let { musicBasic = it }
        merge(data)
        audioOptions += data.audioOptions
    }

    private suspend fun instaQuery(q: JSONObject, mode: String): ExtractResult {
        val body = webQuery(JSONObject(q.toString()).put("mode", mode))
        return GoCore.instaParse(body, q.optString("storyPk").ifEmpty { null })
    }

    /** Runs InstagramQuery (a hidden WebView in [activity]). */
    private suspend fun webQuery(q: JSONObject): String = suspendCancellableCoroutine { cont ->
        InstagramQuery(activity).run(q) { r -> if (cont.isActive) cont.resume(r) }
    }.getOrThrow()

    /**
     * Threads post: the WebView opens the post page and Go reads the post from
     * its embedded data. Returns whether Threads gave a definitive answer.
     */
    private suspend fun runThreads(): Boolean {
        val q = try {
            JSONObject(GoCore.instaQuery(pageUrl, threads = true))
        } catch (e: GoError) {
            Log.i(TAG, "extract: threads: ${e.message}")
            return false
        }
        ytdlpRunning = true
        ytdlpError = null
        needsThreadsLogin = false
        matchStatus = "Querying Threads…"
        notifyChanged()
        val t0 = SystemClock.elapsedRealtime()
        var loggedIn = false
        try {
            // The query's WebView shares the app's cookies: with a session,
            // the page shows what the user's account can see.
            loggedIn = InstagramLoginActivity.isLoggedIn(InstagramLoginActivity.Site.THREADS)
            val body = webQuery(JSONObject(q.toString()).put("mode", "threads"))
            val data = GoCore.threadsParse(body, q.optString("shortcode"))
            Log.i(TAG, "extract: threads ${SystemClock.elapsedRealtime() - t0}ms options=${data.options.size}")
            takePost(data)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: GoError) {
            Log.i(TAG, "extract: threads failed in ${SystemClock.elapsedRealtime() - t0}ms: ${e.message} (${e.code})")
            // Text-only post, or one Threads only shows to signed-in users.
            when (e.code) {
                "nomedia" -> ytdlpError = e.message
                "unavailable" -> {
                    ytdlpError = if (loggedIn) {
                        "Threads did not show this post to your account either (private profile you don't follow, or removed)."
                    } else {
                        "This post is only visible to people signed in to Threads (restricted profile or content)."
                    }
                    needsThreadsLogin = !loggedIn
                }
                else -> return false
            }
            return true
        } catch (e: Exception) {
            Log.i(TAG, "extract: threads query error: ${e.message}")
            return false
        } finally {
            matchStatus = null
            ytdlpRunning = false
            notifyChanged()
        }
    }

    private suspend fun login(site: InstagramLoginActivity.Site): Boolean = suspendCancellableCoroutine { cont ->
        InstagramLoginActivity.pending = { ok -> if (cont.isActive) cont.resume(ok) }
        activity.startActivity(
            Intent(activity, InstagramLoginActivity::class.java).putExtra(InstagramLoginActivity.EXTRA_SITE, site.id),
        )
    }

    /** "Sign in to Instagram": opens the login page, then retries this link. */
    fun loginToInstagram() {
        scope.launch {
            val ok = login(InstagramLoginActivity.Site.INSTAGRAM)
            Log.i(TAG, "extract: instagram login -> $ok")
            if (ok && !disposed) runInstagram()
        }
    }

    fun loginToThreads() {
        scope.launch {
            val ok = login(InstagramLoginActivity.Site.THREADS)
            Log.i(TAG, "extract: threads login -> $ok")
            if (ok && !disposed) runThreads()
        }
    }

    // ------------------------------------------------------------ music

    /** Resolves tags + cover (Deezer/iTunes in Go) for the audio file; joins a running resolution. */
    private suspend fun resolveMeta() {
        if (metaJob == null) {
            val basic = musicBasic ?: return
            if (musicMeta != null) return
            metaJob = scope.async {
                try {
                    val m = GoCore.musicMeta(basic.copy(musicContext = musicContext), linkMeta)
                    musicMeta = m
                    Log.i(TAG, "extract: music meta ${m.artist} - ${m.title} (${m.album} ${m.year}) via ${m.source}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.i(TAG, "extract: music meta failed: ${e.message}")
                }
            }
            notifyChanged()
            metaJob?.invokeOnCompletion { scope.launch { notifyChanged() } }
        }
        metaJob?.await()
    }

    /** Spotify/Deezer/Apple Music: read the track, search YouTube Music and
     *  take the first candidate whose duration matches (±5 s). */
    private suspend fun runMusicLink() {
        mode = MediaMode.AUDIO
        ytdlpRunning = true
        matchStatus = "Reading the track on ${platform ?: "the service"}…"
        notifyChanged()
        try {
            val r = GoCore.musicLink(pageUrl)
            val meta = r.meta
            linkMeta = meta
            title = if (!meta.artist.isNullOrEmpty()) "${meta.artist} - ${meta.title}" else meta.title
            thumbnail = meta.coverUrl
            matchStatus = "Looking for the same track on YouTube Music…"
            notifyChanged()

            val search = withContext(Dispatchers.IO) { YtDlp.search(r.searchUrl) }
            if (search.exitCode != 0) throw GoError("YouTube Music search failed")
            val picked = GoCore.musicPick(search.json, meta, 3)
            for (c in picked) {
                Log.i(TAG, "extract: trying candidate $c")
                matchStatus = "Checking the version found…"
                notifyChanged()
                if (runYtdlp(c, meta.duration)) {
                    matchStatus = null
                    return
                }
            }
            ytdlpError = "Could not find this track (same duration) on YouTube Music."
        } catch (e: CancellationException) {
            throw e
        } catch (e: GoError) {
            ytdlpError = e.message
            Log.i(TAG, "extract: music link failed: ${e.message}")
        } catch (e: Exception) {
            ytdlpError = "Could not read the link: ${e.message ?: e}"
            Log.w(TAG, "extract: music link error", e)
        } finally {
            matchStatus = null
            ytdlpRunning = false
            notifyChanged()
        }
    }

    /** Tags for the audio file (resolved, or the extractor's basics). */
    private val audioMeta: MusicMeta
        get() = musicMeta ?: linkMeta ?: MusicMeta(
            title = title ?: musicBasic?.title.nonBlank() ?: "Audio",
            artist = musicBasic?.artist.nonBlank() ?: musicBasic?.uploader.nonBlank(),
            duration = musicBasic?.duration ?: 0.0,
            coverUrl = musicBasic?.thumbnail.nonBlank(),
            coverSquare = musicBasic?.thumbSquare ?: false,
        )

    // ------------------------------------------------------------ download

    /** Go job request + gallery title for an option. */
    private suspend fun buildRequest(o: MediaOption): DownloadRequest {
        val dir = File(activity.noBackupFilesDir, "downloads")
        val ext = when {
            o.isAudio -> o.audioFormat ?: "m4a"
            o.isImage -> "jpg"
            else -> "mp4"
        }
        if (o.isAudio && musicMeta == null) resolveMeta()
        val out = File(dir, "dl_${stamp()}.$ext").absolutePath
        // HLS too: ffmpeg assembles segments far more robustly than the Go
        // remux (e.g. X/Twitter's negative composition offsets).
        val ffmpeg = if (o.kind == "merge" || o.isAudio || o.isHls) {
            withContext(Dispatchers.IO) { dir.mkdirs(); YtDlp.ffmpegTools() }.let { FFmpegPaths(it["path"].orEmpty(), it["libraryPath"]) }
        } else {
            withContext(Dispatchers.IO) { dir.mkdirs() }
            null
        }
        val m = audioMeta
        var name = if (o.isAudio) {
            listOfNotNull(m.artist, m.title).filter { it.isNotEmpty() }.joinToString(" - ")
        } else {
            title ?: LinkParser.hostOf(pageUrl).orEmpty()
        }
        if (name.isEmpty()) name = title ?: LinkParser.hostOf(pageUrl) ?: "Download"
        if (o.item > 0) name = "$name (${o.item} of ${o.itemCount})"
        return DownloadRequest(
            kind = o.kind,
            url = o.url,
            audioUrl = o.audioUrl,
            headers = headersFor(o),
            outPath = out,
            tmpDir = "$out.parts",
            chunkSize = o.chunkSize,
            ffmpeg = ffmpeg,
            audioFormat = if (o.isAudio) o.audioFormat else null,
            audioQuality = if (o.isAudio) o.audioQuality else null,
            sourceHls = o.isAudio && o.sourceHls,
            musicMeta = if (o.isAudio) m else null,
            // Shown in the downloads screen; pageUrl + optionKey renew expired links.
            meta = JobMeta(
                title = name,
                pageUrl = pageUrl,
                optionKey = o.optionKey,
                thumbnail = o.itemThumb ?: if (o.isAudio) musicMeta?.coverUrl ?: thumbnail else thumbnail,
            ),
        )
    }

    /** Request headers for [o]; also used to renew an expired job. */
    fun headersFor(o: MediaOption): Headers {
        val base = pageHeaders ?: headers
        return Headers(
            userAgent = base.userAgent ?: userAgent.ifEmpty { null },
            // yt-dlp formats carry their own headers (UA, cookies) and must
            // not get the page referer added.
            referer = if (o.headers.isEmpty()) o.referer ?: base.referer ?: pageUrl else null,
            cookie = o.cookie ?: base.cookie,
            extra = o.headers.ifEmpty { null },
        )
    }

    fun download(onStart: () -> Unit = {}) {
        val o = selected ?: return
        if (busy) return
        // Stop sniffing: the page keeps playing video and competes for bandwidth.
        sniffer?.stop("download")
        onStart()
        setDl {
            copy(
                phase = DownloadPhase.DOWNLOADING, error = null, warning = null, saved = null, percent = null,
                bytes = 0, total = o.size, totalEstimate = false, speedBps = 0.0, segmentsDone = 0, segmentsTotal = 0,
                itemsTotal = 0, itemsSaved = 0, itemsFailed = 0,
            )
        }
        scope.launch {
            try {
                val req = buildRequest(o)
                Log.i(TAG, "extract: download ${o.kind} \"${o.label}\" -> ${req.outPath}")
                follow(listOf(JobController.download(req)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "extract: download failed to start", e)
                setDl { copy(phase = DownloadPhase.FAILED, error = e.message ?: "$e") }
            }
        }
    }

    fun toggleItem(item: Int) {
        if (busy) return
        if (!checkedItems.remove(item)) checkedItems += item
        notifyChanged()
    }

    fun setAllItems(checked: Boolean) {
        if (busy) return
        checkedItems.clear()
        if (checked) options.filter { it.item > 0 }.mapTo(checkedItems) { it.item }
        notifyChanged()
    }

    /** Downloads the ticked carousel items, each its own job (own notification). */
    fun downloadItems(onStart: () -> Unit = {}) {
        val items = options.filter { it.item > 0 && it.item in checkedItems }.sortedBy { it.item }
        if (items.isEmpty() || busy) return
        onStart()
        setDl {
            copy(
                phase = DownloadPhase.DOWNLOADING, error = null, warning = null, saved = null, percent = 0.0,
                itemsTotal = items.size, itemsSaved = 0, itemsFailed = 0,
            )
        }
        Log.i(TAG, "extract: downloading ${items.size} carousel items")
        scope.launch {
            val ids = ArrayList<Long>()
            for (o in items) {
                try {
                    ids += JobController.download(buildRequest(o))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "extract: item ${o.item} failed to start", e)
                }
            }
            if (ids.isEmpty()) {
                setDl { copy(phase = DownloadPhase.FAILED, error = "No item was downloaded.") }
            } else {
                follow(ids, failedToStart = items.size - ids.size)
            }
        }
    }

    /** Follows the jobs' progress in the queue's change feed. */
    private fun follow(ids: List<Long>, failedToStart: Int = 0) {
        jobIds = ids
        trackJob?.cancel()
        trackJob = scope.launch {
            JobController.jobs.collect { all ->
                val mine = all.filter { it.id in ids }
                if (mine.isEmpty()) return@collect // not in the feed yet
                if (ids.size == 1 && failedToStart == 0) showJob(mine[0]) else showItems(mine, ids.size + failedToStart, failedToStart)
            }
        }
    }

    private fun showJob(j: Job) = setDl {
        when (j.state) {
            "queued" -> copy(phase = DownloadPhase.DOWNLOADING, error = null)
            "running" -> copy(
                phase = when (j.phase) {
                    "muxing" -> DownloadPhase.MUXING
                    "converting" -> DownloadPhase.CONVERTING
                    else -> DownloadPhase.DOWNLOADING
                },
                bytes = j.bytes,
                total = if (j.total > 0) j.total else total,
                totalEstimate = j.totalEstimate,
                percent = j.percent.takeIf { it > 0 },
                speedBps = j.speedBps,
                segmentsDone = j.segmentsDone,
                segmentsTotal = j.segmentsTotal,
                error = null,
            )
            "waiting" -> copy(phase = DownloadPhase.DOWNLOADING, speedBps = 0.0, error = "No connection — retrying automatically…")
            "done" -> copy(phase = DownloadPhase.SAVING, percent = 100.0, bytes = j.size, total = j.size, warning = j.warning.nonBlank())
            "saved" -> copy(phase = DownloadPhase.SAVED, saved = j.saved, bytes = if (j.size > 0) j.size else bytes, percent = 100.0, warning = j.warning.nonBlank())
            "error" -> copy(
                phase = DownloadPhase.FAILED,
                error = if (j.saveFailed) "Could not save to the gallery: ${j.message.orEmpty()}" else j.message ?: "unknown error",
            )
            "canceled" -> copy(phase = DownloadPhase.CANCELED)
            "paused" -> copy(phase = DownloadPhase.FAILED, error = "Download paused. Resume it from the Downloads screen.")
            "expired" -> copy(phase = DownloadPhase.FAILED, error = "The link expired. Open Downloads to renew it and continue.")
            else -> this
        }
    }

    private fun showItems(jobs: List<Job>, total: Int, failedToStart: Int) = setDl {
        val saved = jobs.filter { it.state == "saved" }
        val failed = failedToStart + jobs.count { it.state in setOf("error", "canceled", "expired", "paused") }
        val pct = jobs.sumOf { if (it.state == "saved" || it.state == "done") 100.0 else it.percent } / total
        val finished = saved.size + failed >= total
        when {
            !finished -> copy(phase = DownloadPhase.DOWNLOADING, percent = pct, itemsSaved = saved.size, itemsFailed = failed)
            saved.isEmpty() -> copy(phase = DownloadPhase.FAILED, error = error ?: "No item was downloaded.", itemsSaved = 0, itemsFailed = failed)
            else -> copy(
                phase = DownloadPhase.SAVED, percent = 100.0, saved = saved.first().saved,
                bytes = saved.sumOf { it.size }, itemsSaved = saved.size, itemsFailed = failed,
                warning = if (failed > 0) "$failed of $total items failed." else null,
            )
        }
    }

    fun cancelDownload() {
        Log.i(TAG, "extract: cancel requested")
        for (id in jobIds) JobController.cancel(id)
    }

    /** Waits until extraction finished (options listed or error). */
    suspend fun settled(timeoutMs: Long = 60_000) {
        withTimeoutOrNull(timeoutMs) {
            startJob?.join()
            state.first { !it.scanning && !it.resolvingMeta }
        }
    }

    /** Closing the sheet never cancels a download: it continues in the background. */
    fun dispose() {
        if (disposed) return
        disposed = true
        flushJob?.cancel()
        sniffer?.stop("disposed")
        sniffer = null
        if (ytdlpRunning) YtDlp.cancel(ytdlpId)
        scope.cancel()
    }

    companion object {
        private const val TAG = "DownVid"
        private const val FLUSH_MS = 700L
        private val seq = AtomicLong()

        /** Unique per process even for items started in the same millisecond. */
        private fun stamp() = System.currentTimeMillis() * 1000 + seq.incrementAndGet() % 1000

        // Real videos first, then resolution, bitrate and size; best first.
        private val COMPARE = Comparator<MediaOption> { a, b ->
            when {
                a.small != b.small -> if (a.small) 1 else -1
                a.height != b.height -> b.height.compareTo(a.height)
                a.bandwidth != b.bandwidth -> b.bandwidth.compareTo(a.bandwidth)
                else -> b.size.compareTo(a.size)
            }
        }

        /** Size label "~12 MB" / "12 MB" of an option. */
        fun sizeLabel(o: MediaOption) = if (o.size > 0) "${if (o.sizeExact) "" else "~"}${formatBytes(o.size)}" else ""
    }
}
