package com.downvid.downvid

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray

/**
 * Loads a page in a hidden WebView and records every media request it makes
 * (.m3u8 / .mp4 / ...), including those issued by JS players (hls.js, video.js,
 * jwplayer) and by cross-origin iframes. This finds videos that are not in the
 * static HTML.
 *
 * The WebView is laid out full-size *behind* the Flutter view with near-zero
 * alpha: players that check visibility/viewport still start, and the user never
 * sees or touches it.
 */
class PageSniffer(
    private val activity: Activity,
    private val onFound: (Found) -> Unit,
    private val onDone: (Result) -> Unit,
) {
    data class Found(val url: String, val referer: String?, val via: String, val cookie: String?)

    data class Result(
        val found: List<Found>,
        val finalUrl: String?,
        val title: String?,
        val userAgent: String,
        val cookies: Map<String, String>,
        val reason: String,
    )

    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, Found>()
    private var webView: WebView? = null
    private var startedAt = 0L
    private var lastFoundAt = 0L
    private var pageHost: String? = null
    private var firstPageFinished = false
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    fun start(url: String) {
        startedAt = System.currentTimeMillis()
        pageHost = Uri.parse(url).host
        val wv = WebView(activity)
        webView = wv
        wv.alpha = 0.01f
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.webViewClient = Client()

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        root.addView(wv, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        Log.i(TAG, "sniff start $url ua=${wv.settings.userAgentString}")
        wv.loadUrl(url)
        main.postDelayed(tick, TICK_MS)
    }

    fun stop(reason: String = "stopped") = finish(reason)

    private val tick = object : Runnable {
        override fun run() {
            if (finished) return
            val now = System.currentTimeMillis()
            val elapsed = now - startedAt
            when {
                elapsed > MAX_MS -> finish("timeout")
                found.isNotEmpty() && now - lastFoundAt > IDLE_AFTER_FOUND_MS && elapsed > MIN_MS -> finish("idle")
                found.isEmpty() && elapsed > NOTHING_FOUND_MS -> finish("nothing-found")
                else -> {
                    if (firstPageFinished) probeDom()
                    main.postDelayed(this, TICK_MS)
                }
            }
        }
    }

    /** Mutes/plays videos, presses common "big play" buttons once, and reads
     *  video sources + Resource Timing entries. */
    private fun probeDom() {
        webView?.evaluateJavascript(DOM_PROBE_JS) { json ->
            if (json == null || json == "null") return@evaluateJavascript
            try {
                val raw = if (json.startsWith("\"")) JSONArray("[$json]").getString(0) else json
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) record(arr.getString(i), webView?.url, "dom")
            } catch (e: Exception) {
                Log.w(TAG, "dom probe parse: ${e.message}")
            }
        }
    }

    private fun record(url: String, referer: String?, via: String) {
        if (!url.startsWith("http")) return
        if (!looksLikeMedia(url)) return
        // Segments of a playlist we already have are noise.
        if (SEGMENT_RE.containsMatchIn(url)) return
        main.post {
            if (finished || found.containsKey(url)) return@post
            val f = Found(url, referer, via, CookieManager.getInstance().getCookie(url))
            found[url] = f
            lastFoundAt = System.currentTimeMillis()
            Log.i(TAG, "sniff found [$via] $url (referer=$referer)")
            onFound(f)
        }
    }

    private fun finish(reason: String) {
        if (finished) return
        finished = true
        main.removeCallbacks(tick)
        val wv = webView
        val cm = CookieManager.getInstance()
        val cookies = HashMap<String, String>()
        val hosts = found.keys.mapNotNull { Uri.parse(it).let { u -> "${u.scheme}://${u.host}" } }.toMutableSet()
        wv?.url?.let { hosts += Uri.parse(it).let { u -> "${u.scheme}://${u.host}" } }
        for (h in hosts) cm.getCookie(h)?.let { cookies[h] = it }
        val result = Result(
            found = found.values.toList(),
            finalUrl = wv?.url,
            title = wv?.title,
            userAgent = wv?.settings?.userAgentString ?: WebSettings.getDefaultUserAgent(activity),
            cookies = cookies,
            reason = reason,
        )
        Log.i(TAG, "sniff done reason=$reason found=${found.size} in ${System.currentTimeMillis() - startedAt}ms title=${result.title}")
        if (wv != null) {
            wv.stopLoading()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        webView = null
        onDone(result)
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            if (looksLikeMedia(url)) {
                record(url, request.requestHeaders["Referer"], "net")
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme ?: return true
            if (scheme != "http" && scheme != "https") return true // intent://, market://...
            if (!request.isForMainFrame) return false
            // Redirects during the initial load are fine; afterwards, block
            // pop-under/ad navigations away from the page.
            if (firstPageFinished && request.url.host != pageHost) {
                Log.i(TAG, "sniff blocked navigation to ${request.url}")
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (!firstPageFinished) pageHost = Uri.parse(url).host
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (!firstPageFinished) Log.i(TAG, "sniff page loaded $url")
            firstPageFinished = true
            probeDom()
        }
    }

    companion object {
        private const val TAG = "DownVid"
        private const val TICK_MS = 1500L
        private const val MIN_MS = 6000L
        private const val IDLE_AFTER_FOUND_MS = 5000L
        private const val NOTHING_FOUND_MS = 18000L
        private const val MAX_MS = 30000L

        private val MEDIA_RE = Regex("""\.(m3u8|mp4|m4v|mov)(\?|#|$|/)""", RegexOption.IGNORE_CASE)
        private val SEGMENT_RE = Regex("""\.(ts|m4s|aac|vtt|webvtt)(\?|$)""", RegexOption.IGNORE_CASE)

        fun looksLikeMedia(url: String): Boolean {
            val path = url.substringBefore('?')
            if (MEDIA_RE.containsMatchIn(path) || MEDIA_RE.containsMatchIn(url)) return true
            val l = url.lowercase()
            return l.contains(".m3u8") || l.contains("mpegurl") || l.contains("format=m3u8")
        }

        private val DOM_PROBE_JS = """
            (function(){
              var out = [];
              function add(u){ if (u && typeof u === 'string' && u.indexOf('http') === 0) out.push(u); }
              try {
                document.querySelectorAll('video').forEach(function(v){
                  v.muted = true;
                  try { var p = v.play(); if (p && p.catch) p.catch(function(){}); } catch(e){}
                  add(v.currentSrc); add(v.src);
                });
                document.querySelectorAll('video source, source[src]').forEach(function(s){ add(s.src); });
                if (!window.__dvClicked) {
                  window.__dvClicked = true;
                  var sel = '.vjs-big-play-button,.jw-display-icon-display,.jw-icon-display,.plyr__control--overlaid,'+
                            '.fp-play,.mejs__overlay-play,.ytp-large-play-button,[class*="play-button"],[aria-label="Play"],[aria-label="Reproduzir"]';
                  document.querySelectorAll(sel).forEach(function(b){ try { b.click(); } catch(e){} });
                }
                (performance.getEntriesByType('resource') || []).forEach(function(e){ add(e.name); });
              } catch (e) {}
              return JSON.stringify(out);
            })();
        """.trimIndent()
    }
}
