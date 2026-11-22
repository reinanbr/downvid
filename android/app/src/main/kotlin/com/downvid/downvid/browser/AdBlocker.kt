package com.downvid.downvid.browser

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** JNI bindings of libdvadblock.so (adblock/: Brave's adblock-rust engine). */
internal object AdEngine {
    @JvmStatic external fun nativeLoad(dat: ByteBuffer, resources: ByteBuffer?): Boolean

    /** null = allow, "" = block, otherwise the `data:` URL to serve instead. */
    @JvmStatic external fun nativeMatch(url: String, source: String, type: Int): String?

    /** JSON {css, script, generichide} for a page about to load. */
    @JvmStatic external fun nativeCosmetic(url: String): String?

    /** CSS of the generic rules matching [tokens] (".class" / "#id" per line). */
    @JvmStatic external fun nativeHidden(url: String, tokens: String): String?
}

/** Request types, encoded as adblock/src/lib.rs `TYPES`. */
object RequestType {
    const val OTHER = 0
    const val DOCUMENT = 1
    const val SUBDOCUMENT = 2
    const val STYLESHEET = 3
    const val SCRIPT = 4
    const val IMAGE = 5
    const val FONT = 6
    const val MEDIA = 7
    const val XHR = 8
    const val WEBSOCKET = 9
    const val PING = 10

    private val EXT = Regex("""\.([a-z0-9]{2,5})$""")

    /**
     * Type of a WebView request, which does not say it: Sec-Fetch-Dest when
     * present, else the Accept header (navigations, styles, images), a
     * Range header (media) or the file extension.
     */
    fun of(isMainFrame: Boolean, headers: Map<String, String>, path: String?): Int {
        if (isMainFrame) return DOCUMENT
        val h = headers.entries.associate { it.key.lowercase() to it.value }
        h["sec-fetch-dest"]?.let { dest ->
            return when (dest) {
                "iframe", "frame", "document" -> SUBDOCUMENT
                "script", "worker", "sharedworker", "serviceworker", "audioworklet", "paintworklet" -> SCRIPT
                "style" -> STYLESHEET
                "image" -> IMAGE
                "font" -> FONT
                "video", "audio", "track" -> MEDIA
                "empty" -> XHR
                else -> OTHER
            }
        }
        val accept = h["accept"].orEmpty()
        when {
            accept.startsWith("text/html") -> return SUBDOCUMENT
            accept.startsWith("text/css") -> return STYLESHEET
            accept.startsWith("image/") -> return IMAGE
        }
        if ("range" in h) return MEDIA
        return when (EXT.find(path.orEmpty().lowercase())?.groupValues?.get(1)) {
            "js", "mjs" -> SCRIPT
            "css" -> STYLESHEET
            "png", "jpg", "jpeg", "gif", "webp", "svg", "avif", "ico", "bmp" -> IMAGE
            "woff", "woff2", "ttf", "otf", "eot" -> FONT
            "mp4", "webm", "m3u8", "mpd", "ts", "m4s", "mp3", "aac", "m4a", "ogg", "vtt" -> MEDIA
            "json", "xml" -> XHR
            else -> OTHER
        }
    }
}

/** What a page gets before it loads: CSS hiding its ad elements and the scriptlets. */
data class Cosmetic(val css: String, val script: String, val genericHide: Boolean) {
    val isEmpty get() = css.isEmpty() && script.isEmpty()
}

/**
 * The browser's ad blocker: Brave's adblock-rust engine with Brave's
 * default lists (scripts/build_adblock_lists.py). Network requests are
 * blocked or redirected to uBlock Origin's neutral resources (an empty
 * video for video ads, no-op scripts); pages get cosmetic filters and
 * scriptlets (see BrowserModel).
 */
object AdBlocker {
    private const val TAG = "DownVid"
    private val started = AtomicBoolean(false)
    private val ready = CountDownLatch(1)
    @Volatile private var loaded = false

    /** Loads the engine off the main thread ahead of the first page. */
    fun preload(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({ load(app) }, "adblock-load").start()
    }

    private fun load(context: Context) {
        try {
            val t0 = System.nanoTime()
            System.loadLibrary("dvadblock")
            val dat = asset(context.assets, "adblock/engine.dat")
            val res = asset(context.assets, "adblock/resources.json")
            loaded = AdEngine.nativeLoad(dat, res)
            Log.i(TAG, "adblock: engine ${if (loaded) "loaded" else "FAILED"} in ${(System.nanoTime() - t0) / 1_000_000} ms (${dat.capacity() / 1024} KB)")
        } catch (e: Throwable) {
            Log.e(TAG, "adblock: cannot load the engine", e)
        } finally {
            ready.countDown()
        }
    }

    /** A (compressed) asset inflated into native memory; the engine copies it. */
    private fun asset(am: AssetManager, name: String): ByteBuffer =
        am.open(name, AssetManager.ACCESS_STREAMING).use { input ->
            val buf = ByteBuffer.allocateDirect(input.available())
            val ch = Channels.newChannel(input)
            while (buf.hasRemaining() && ch.read(buf) >= 0) Unit
            buf
        }

    /** Waits for the engine (IO threads only); false if it is unavailable. */
    private fun awaitEngine(): Boolean {
        if (!loaded && ready.count > 0) ready.await(2, TimeUnit.SECONDS)
        return loaded
    }

    /**
     * The response replacing [request] (empty, or the redirect resource), or
     * null to let it through. [pageUrl]: the tab's page.
     */
    fun intercept(request: WebResourceRequest, pageUrl: String): WebResourceResponse? {
        if (!awaitEngine()) return null
        val url = request.url.toString()
        val type = RequestType.of(request.isForMainFrame, request.requestHeaders, request.url.path)
        val verdict = AdEngine.nativeMatch(url, pageUrl.ifEmpty { url }, type) ?: return null
        if (verdict.isEmpty()) return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        val (mime, body) = decodeDataUrl(verdict) ?: return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        return WebResourceResponse(mime, null, ByteArrayInputStream(body))
    }

    /** Cosmetic filters and scriptlets for [url]; null while the engine loads. */
    fun cosmetic(url: String): Cosmetic? {
        if (!loaded) return null
        val json = AdEngine.nativeCosmetic(url) ?: return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return Cosmetic(o.optString("css"), o.optString("script"), o.optBoolean("generichide"))
    }

    /** CSS of the generic rules for classes/ids of a page in [frameUrl]. */
    fun hidden(frameUrl: String, tokens: String): String? {
        if (!loaded || tokens.isEmpty()) return null
        return AdEngine.nativeHidden(frameUrl, tokens)?.ifEmpty { null }
    }

    /** "data:mime;base64,..." (adblock-rust redirects) to mime and bytes. */
    fun decodeDataUrl(url: String): Pair<String, ByteArray>? {
        if (!url.startsWith("data:")) return null
        val comma = url.indexOf(',').takeIf { it > 0 } ?: return null
        val meta = url.substring(5, comma)
        val mime = meta.substringBefore(';').ifEmpty { "text/plain" }
        val data = url.substring(comma + 1)
        val bytes = if (meta.endsWith(";base64")) {
            runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull() ?: return null
        } else {
            Uri.decode(data).toByteArray()
        }
        return mime to bytes
    }
}
