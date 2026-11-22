package com.downvid.downvid.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.downvid.downvid.core.Candidate
import com.downvid.downvid.core.ExtractResult
import com.downvid.downvid.core.GoCore
import com.downvid.downvid.core.Headers
import com.downvid.downvid.core.MediaOption
import com.downvid.downvid.core.Skipped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** What a tab's detector found on the current page. */
data class DetectedMedia(
    val pageUrl: String = "",
    val options: List<MediaOption> = emptyList(),
    val skipped: List<Skipped> = emptyList(),
    val pending: Int = 0,
    /** The page asked for DRM (EME): its media is protected, never downloaded. */
    val drm: Boolean = false,
    /** Latest HLS/DASH manifest requested (what a blob:/MSE player plays). */
    val latestManifest: String? = null,
    val latestFile: String? = null,
) {
    /** Real videos (previews under 1 MB are hidden once there is one). */
    val visible = options.filter { !it.small && !it.isAudio }.ifEmpty { options }
    val count get() = visible.size
    fun asResult(title: String?) = ExtractResult(pageUrl = pageUrl, title = title, options = options, skipped = skipped)
}

/**
 * Per-tab media detector: candidates come from the network
 * (shouldInterceptRequest), the injected script and page downloads; they are
 * deduplicated, batched for [DEBOUNCE_MS] and resolved by the Go core
 * (variants, sizes; live/DRM/previews flagged) with the page's session
 * (cookies, Referer, User-Agent). Main thread, except [onNetwork].
 */
class MediaDetector(
    private val scope: CoroutineScope,
    private val headers: () -> Headers,
    private val cookieFor: (String) -> String?,
) {
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(DetectedMedia())
    val state: StateFlow<DetectedMedia> = _state.asStateFlow()
    private val seen = HashSet<String>()
    private val queue = ArrayList<Candidate>()
    private var flush: Job? = null
    private var generation = 0

    /** A new page (main-frame navigation or SPA route): forget the old media. */
    fun reset(pageUrl: String) {
        if (samePage(pageUrl, _state.value.pageUrl)) return
        generation++
        seen.clear()
        queue.clear()
        flush?.cancel()
        _state.value = DetectedMedia(pageUrl = pageUrl)
    }

    /** From WebView IO threads. */
    fun onNetwork(url: String, referer: String?) {
        val kind = MediaUrls.classify(url)
        if (!MediaUrls.isCandidate(kind)) return
        main.post { add(url, kind, referer, "net") }
    }

    /** The page uses DRM (EME requested, or WebView asked for protected media). */
    fun markDrm(what: String) {
        if (_state.value.drm) return
        Log.i(TAG, "detect: DRM requested ($what)")
        _state.value = _state.value.copy(drm = true)
    }

    /** A message of the injected script (JSON). */
    fun onScript(o: JSONObject, reply: ((String) -> Unit)?, onPlay: (PlayEvent) -> Unit) {
        when (o.optString("t")) {
            "url" -> {
                val url = o.optString("url")
                // <video src=""> resolves to the page itself (unless the page is a media file).
                val ct = o.optString("ct").ifEmpty { null }.takeUnless { samePage(url, _state.value.pageUrl) }
                val kind = MediaUrls.classify(url, ct)
                if (MediaUrls.isCandidate(kind)) add(url, kind, o.optString("page").ifEmpty { null }, o.optString("via"))
            }
            "drm" -> markDrm("${o.optString("ks")} on ${o.optString("page")}")
            "play" -> onPlay(
                PlayEvent(
                    src = o.optString("src"),
                    width = o.optInt("w"),
                    height = o.optInt("h"),
                    duration = o.optDouble("dur", 0.0),
                    position = o.optDouble("pos", 0.0),
                    frameUrl = o.optString("page"),
                    takeover = o.optBoolean("take"),
                    drm = o.optBoolean("drm"),
                    id = o.optString("id"),
                    reply = reply,
                ),
            )
        }
    }

    /** A file the page asked to download (DownloadListener). */
    fun onDownload(url: String, mime: String?) {
        val kind = MediaUrls.classify(url, mime).takeIf { MediaUrls.isCandidate(it) } ?: MediaKind.PROGRESSIVE
        add(url, kind, _state.value.pageUrl, "download")
    }

    private fun add(url: String, kind: MediaKind, referer: String?, via: String) {
        if (!seen.add(url)) return
        Log.i(TAG, "detect: [$via] $kind $url")
        val cur = _state.value
        _state.value = cur.copy(
            latestManifest = if (kind == MediaKind.HLS || kind == MediaKind.DASH) url else cur.latestManifest,
            latestFile = if (kind == MediaKind.PROGRESSIVE) url else cur.latestFile,
        )
        queue += Candidate(url, "webview", referer = referer ?: cur.pageUrl.ifEmpty { null }, cookie = cookieFor(url))
        // Only the wait restarts: a probe already running took its batch out
        // of the queue, and cancelling it lost those options (the master
        // playlist of a player that went on to request its variants).
        flush?.cancel()
        flush = scope.launch {
            delay(DEBOUNCE_MS)
            scope.launch { resolve() }
        }
    }

    private suspend fun resolve() {
        if (queue.isEmpty()) return
        val batch = queue.toList()
        queue.clear()
        val gen = generation
        val page = _state.value.pageUrl
        _state.value = _state.value.copy(pending = _state.value.pending + 1)
        try {
            val r = GoCore.probe(page, batch, headers(), skipPage = true)
            if (gen != generation) return
            val cur = _state.value
            val keys = cur.options.mapTo(HashSet()) { it.key }
            val all = cur.options + r.options.filter { keys.add(it.key) }.map { o ->
                // Hover previews / teasers of other videos: listed under "previews".
                if (!o.small && MediaUrls.isPreview(o.url)) o.copy(small = true) else o
            }
            // Variant playlists requested by the page's player after its
            // master: listed once, under the master (it has the resolutions).
            val variants = all.filter { it.isHls && it.group.isNotEmpty() && it.group != it.url }.mapTo(HashSet()) { it.url }
            seen += variants
            val options = all.filter { !(it.isHls && (it.group.isEmpty() || it.group == it.url) && it.url in variants) }
                .sortedWith(COMPARE)
            _state.value = cur.copy(options = options, skipped = cur.skipped + r.skipped)
            Log.i(TAG, "detect: ${batch.size} candidates -> ${r.options.size} options, ${r.skipped.size} skipped")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "detect: probe failed: ${e.message}")
        } finally {
            if (gen == generation) _state.value = _state.value.copy(pending = _state.value.pending - 1)
        }
    }

    companion object {
        private const val TAG = "DownVid"
        private const val DEBOUNCE_MS = 400L

        private val COMPARE = Comparator<MediaOption> { a, b ->
            when {
                a.small != b.small -> if (a.small) 1 else -1
                a.height != b.height -> b.height.compareTo(a.height)
                else -> b.bandwidth.compareTo(a.bandwidth)
            }
        }

        /** Same document for media purposes: fragment changes don't count. */
        fun samePage(a: String, b: String) = a.substringBefore('#') == b.substringBefore('#')
    }
}

/** A page video started playing. */
data class PlayEvent(
    val src: String,
    val width: Int,
    val height: Int,
    val duration: Double,
    val position: Double,
    val frameUrl: String,
    /** The script paused it for DownVid's player (auto mode). */
    val takeover: Boolean = false,
    /** The element's id in its frame ("take:<id>", "rect" messages). */
    val id: String = "",
    /** The page asked for DRM keys (EME): only its own player can play it. */
    val drm: Boolean = false,
    /** Message to the frame that played it ("resume", "pause"). */
    val reply: ((String) -> Unit)? = null,
)
