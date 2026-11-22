package com.downvid.downvid.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.downvid.downvid.InstagramQuery
import com.downvid.downvid.core.Headers
import com.downvid.downvid.core.MediaOption
import com.downvid.downvid.player.PlayItem
import com.downvid.downvid.player.PlayerHub
import com.downvid.downvid.core.Settings
import com.downvid.downvid.extract.LinkParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** A request to open the native player. */
data class PlayRequest(
    val url: String,
    val title: String?,
    val pageUrl: String,
    val headers: Headers,
    val incognito: Boolean,
    val position: Double = 0.0,
)

/** One browser tab. Its WebView exists only while the tab is among the most recently used. */
class BrowserTab(
    val id: Long,
    val incognito: Boolean,
    initialUrl: String,
    /** WebView profile (private tabs); null = the default one. */
    internal val profile: String? = null,
) {
    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var desktop by mutableStateOf(Settings.current.desktopMode)
    var blocked by mutableIntStateOf(0)
    /** "Open in player" offer for a video that started playing in the page. */
    var playOffer by mutableStateOf<PlayRequest?>(null)
    internal var offerEvent: PlayEvent? = null
    /** DownVid's player in place of a page video. */
    var inline by mutableStateOf<InlineVideo?>(null)
    /** WebView scroll (device px), for the in-page player's position. */
    var scrollX by mutableIntStateOf(0)
    var scrollY by mutableIntStateOf(0)

    internal var webView: WebView? = null
    internal var saved: Bundle? = null
    internal var lastUsed = 0L
    internal val blockedCounter = AtomicInteger()
    @Volatile internal var pageHost: String? = LinkParser.hostOf(initialUrl)
    internal lateinit var detector: MediaDetector
    internal var autoScript: ScriptHandler? = null
    /** The page, for the ad blocker on WebView IO threads. */
    @Volatile internal var pageUrl: String = initialUrl
    /** Cosmetic filter scripts registered per origin (null: nothing to inject). */
    internal val cosmetics = LinkedHashMap<String, ScriptHandler?>()

    val isBlank get() = url.isEmpty()

    /**
     * Title for downloads and the player. A media URL opened directly has
     * its address as title: the file's name (without extension) is used.
     */
    val displayTitle: String?
        get() {
            val t = title.trim()
            if (t.isNotEmpty() && (t.contains(' ') || !t.contains('/'))) return t
            if (!MediaUrls.isCandidate(MediaUrls.classify(url))) return t.ifEmpty { null }
            return url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
                .substringBeforeLast('.').ifEmpty { null }
        }
}

/**
 * The browser: tabs, their WebViews and clients. Owned by MainActivity (the
 * WebViews need its context for dialogs and pickers) and destroyed with it;
 * open tabs are restored from BrowserStore.
 */
class BrowserModel(private val activity: Activity) {
    private val main = Handler(Looper.getMainLooper())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val tabs = mutableStateListOf<BrowserTab>()
    var current by mutableStateOf<BrowserTab?>(null)
        private set

    /** A page element in fullscreen (video), shown over everything. */
    var fullscreen by mutableStateOf<View?>(null)
        private set
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    /** Pending <input type=file> request; BrowserScreen launches the picker. */
    var fileChooser by mutableStateOf<Pair<ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams>?>(null)

    /** Called when a page offers a media download or the player opens. */
    var onPlay: (PlayRequest) -> Unit = {}
    var onDownloadLink: (url: String, headers: Headers) -> Unit = { _, _ -> }

    private var nextId = 1L
    private val multiProfile = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)
    val incognitoSupported get() = multiProfile
    private val docStart = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }.getOrDefault(false)
    private val msgListener = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }.getOrDefault(false)
    private val defaultUa by lazy { WebSettings.getDefaultUserAgent(activity) }

    init {
        BrowserStore.load(activity)
        AdBlocker.preload(activity)
        // Profiles of earlier private sessions: Chromium deletes a profile only
        // before this process has loaded it, i.e. now.
        if (multiProfile) dropIncognitoProfiles()
        val saved = BrowserStore.tabs()
        for (u in saved.urls) tabs += newTab(u, incognito = false)
        current = tabs.getOrNull(saved.current.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)))
        Log.i(TAG, "browser: ${tabs.size} tabs restored; docStart=$docStart listener=$msgListener profiles=$multiProfile")
        scope.launch {
            Settings.state.map { it.autoPlayer }.distinctUntilChanged().drop(1).collect(::applyAutoPlayer)
        }
        scope.launch {
            // Ad blocking turned off, or a site allowed: drop the cosmetic scripts.
            Settings.state.map { it.adBlock to it.adBlockAllow }.distinctUntilChanged().drop(1).collect {
                tabs.forEach(::clearCosmetics)
            }
        }
    }

    /** Profile of the current private session; each session gets a fresh one. */
    private var incognitoProfile: String? = null

    private fun newTab(url: String, incognito: Boolean): BrowserTab {
        val profile = if (incognito && multiProfile) {
            incognitoProfile ?: "$INCOGNITO-${System.currentTimeMillis()}".also { incognitoProfile = it }
        } else {
            null
        }
        val tab = BrowserTab(nextId++, incognito, url, profile)
        tab.detector = MediaDetector(
            scope,
            headers = { headersFor(tab, null) },
            cookieFor = { cookieManager(tab).getCookie(it) },
        )
        tab.detector.reset(url)
        return tab
    }

    // ------------------------------------------------------------ tabs

    fun open(url: String?, incognito: Boolean = current?.incognito == true, inNewTab: Boolean = false): BrowserTab {
        val target = url?.let(::normalize).orEmpty()
        val cur = current
        if (!inNewTab && cur != null && cur.incognito == incognito) {
            if (target.isNotEmpty()) load(cur, target)
            return cur
        }
        val tab = newTab(target, incognito && multiProfile)
        tabs += tab
        select(tab)
        return tab
    }

    fun select(tab: BrowserTab) {
        val prev = current
        if (prev !== tab) {
            prev?.let { closeInline(it) }
            prev?.webView?.onPause()
        }
        current = tab
        tab.lastUsed = System.nanoTime()
        if (!tab.isBlank) ensureWebView(tab)?.onResume()
        persist()
    }

    fun close(tab: BrowserTab) {
        val i = tabs.indexOf(tab)
        if (i < 0) return
        tabs.removeAt(i)
        closeInline(tab, notify = false)
        val lastPrivate = tab.incognito && tabs.none { it.incognito }
        // The private session's HTTP cache, through its last WebView.
        if (lastPrivate) tab.webView?.clearCache(true)
        destroyWebView(tab)
        if (current === tab) {
            current = null
            tabs.getOrNull(i.coerceAtMost(tabs.size - 1))?.let(::select)
        }
        if (lastPrivate) endIncognito()
        persist()
    }

    fun closeAll(incognito: Boolean) {
        for (t in tabs.filter { it.incognito == incognito }) close(t)
    }

    fun load(tab: BrowserTab, url: String) {
        tab.url = url
        tab.pageHost = LinkParser.hostOf(url)
        tab.pageUrl = url
        tab.detector.reset(url)
        tab.playOffer = null
        val wv = ensureWebView(tab) ?: return
        prepareCosmetics(tab, url)
        wv.loadUrl(url)
        persist()
    }

    /** Address bar input: a URL, a host, or a search. */
    fun normalize(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        if (Regex("""^(https?|about|data|file):""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return t
        if (!t.contains(' ') && Regex("""^[\w-]+(\.[\w-]+)+(:\d+)?(/.*)?$""").matches(t)) return "https://$t"
        return Settings.current.searchUrl(t)
    }

    fun webViewOf(tab: BrowserTab): WebView? = if (tab.isBlank) null else ensureWebView(tab)

    private fun ensureWebView(tab: BrowserTab): WebView? {
        tab.webView?.let { return it }
        evictIfNeeded(keep = tab)
        val wv = createWebView(tab)
        tab.webView = wv
        if (tab.url.isNotEmpty()) prepareCosmetics(tab, tab.url)
        val state = tab.saved
        if (state != null && wv.restoreState(state) != null) {
            tab.saved = null
        } else if (tab.url.isNotEmpty()) {
            wv.loadUrl(tab.url)
        }
        return wv
    }

    /** At most [LIVE_MAX] WebViews: the least recently used keep only their saved state. */
    private fun evictIfNeeded(keep: BrowserTab) {
        val live = tabs.filter { it.webView != null && it !== keep }
        if (live.size < LIVE_MAX) return
        val victim = live.minByOrNull { it.lastUsed } ?: return
        Log.i(TAG, "browser: tab ${victim.id} unloaded (only $LIVE_MAX pages stay in memory)")
        victim.saved = Bundle().also { victim.webView?.saveState(it) }
        destroyWebView(victim)
    }

    private fun destroyWebView(tab: BrowserTab) {
        val wv = tab.webView ?: return
        tab.webView = null
        // Script handlers belong to the WebView.
        tab.cosmetics.clear()
        tab.autoScript = null
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.stopLoading()
        wv.destroy()
    }

    private fun persist() {
        val normal = tabs.filter { !it.incognito && !it.isBlank }
        val cur = current
        BrowserStore.saveTabs(SavedTabs(normal.map { it.url }, normal.indexOf(cur).coerceAtLeast(0)))
    }

    fun cookieManager(tab: BrowserTab): CookieManager =
        if (tab.profile != null) ProfileStore.getInstance().getOrCreateProfile(tab.profile).cookieManager
        else CookieManager.getInstance()

    /** Session headers for requests made outside the WebView (probe, downloads, player). */
    fun headersFor(tab: BrowserTab, mediaUrl: String?): Headers = Headers(
        userAgent = tab.webView?.settings?.userAgentString ?: defaultUa,
        referer = tab.url.ifEmpty { null },
        cookie = (mediaUrl ?: tab.url).takeIf { it.isNotEmpty() }?.let { cookieManager(tab).getCookie(it) },
    )

    /**
     * The last private tab closed: the session's cookies and storage are
     * wiped now; its (empty) profile folder at the next start. The next
     * private session uses a new profile.
     */
    private fun endIncognito() {
        val name = incognitoProfile ?: return
        incognitoProfile = null
        runCatching {
            ProfileStore.getInstance().getProfile(name)?.let {
                it.cookieManager.removeAllCookies(null)
                it.webStorage.deleteAllData()
            }
        }.onFailure { Log.w(TAG, "browser: cannot wipe the private session: ${it.message}") }
        Log.i(TAG, "browser: private session ended")
    }

    private fun dropIncognitoProfiles() {
        runCatching {
            val store = ProfileStore.getInstance()
            for (name in store.getAllProfileNames()) {
                if (name.startsWith(INCOGNITO)) Log.i(TAG, "browser: old private profile deleted=${store.deleteProfile(name)}")
            }
        }.onFailure { Log.w(TAG, "browser: cannot delete old private profiles: ${it.message}") }
    }

    fun clearBrowsingData() {
        for (t in tabs.toList()) close(t)
        BrowserStore.saveTabs(SavedTabs())
        CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()
        WebView(activity).apply { clearCache(true); destroy() }
        BrowserStore.clearHistory()
    }

    // ------------------------------------------------------------ lifecycle

    fun onPause() {
        // The in-page player belongs to the page: it stops with it.
        if (current?.inline != null) PlayerHub.playerOrNull?.pause()
        current?.webView?.onPause()
    }

    fun onResume() = current?.webView?.onResume()

    fun destroy() {
        persist()
        for (t in tabs) closeInline(t, notify = false)
        for (t in tabs) destroyWebView(t)
        scope.cancel()
    }

    fun exitFullscreen() {
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        fullscreen = null
    }

    /** "Open in player" (button or automatic): the media the page is playing. */
    fun playRequestFor(tab: BrowserTab, ev: PlayEvent): PlayRequest? {
        val d = tab.detector.state.value
        // Encrypted streams: only the page's player (with its keys) plays them.
        if (d.drm || ev.drm) return null
        val src = ev.src
        val url = when {
            src.startsWith("http") -> src
            // blob:/MSE: the manifest the page loaded; its master playlist
            // when it was a variant (all qualities, adaptive).
            else -> d.latestManifest?.let { m -> masterOf(d.options, m) } ?: d.latestFile
        } ?: return null
        return PlayRequest(url, tab.displayTitle, tab.url, headersFor(tab, url), tab.incognito, ev.position)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: BrowserTab): WebView {
        val wv = WebView(activity)
        tab.profile?.let { WebViewCompat.setProfile(wv, it) }
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = if (tab.desktop) InstagramQuery.desktopUserAgent(defaultUa) else null
            if (tab.incognito) cacheMode = WebSettings.LOAD_NO_CACHE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        installScript(wv, tab)
        wv.webViewClient = Client(tab)
        wv.webChromeClient = Chrome(tab)
        wv.setOnScrollChangeListener { _, x, y, _, _ ->
            tab.scrollX = x
            tab.scrollY = y
        }
        wv.setDownloadListener { url, ua, disposition, mime, length ->
            Log.i(TAG, "browser: download $mime $length $url ($disposition)")
            val media = mime?.startsWith("video/") == true || mime?.startsWith("audio/") == true ||
                MediaUrls.isCandidate(MediaUrls.classify(url, mime))
            if (media) {
                tab.detector.onDownload(url, mime)
                onDownloadLink(url, headersFor(tab, url).copy(userAgent = ua))
            } else {
                openExternally(Uri.parse(url))
            }
        }
        return wv
    }

    private fun installScript(wv: WebView, tab: BrowserTab) {
        val onMessage: (String, ((String) -> Unit)?) -> Unit = handler@{ json, reply ->
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return@handler
            when (o.optString("t")) {
                "cls" -> if (reply != null) genericCosmetics(tab, o.optString("page"), o.optString("v"), reply)
                "rect" -> tab.inline?.takeIf { it.id == o.optString("id") }?.let {
                    val x = o.optDouble("x").toFloat()
                    val y = o.optDouble("y").toFloat()
                    val w = o.optDouble("w").toFloat()
                    val h = o.optDouble("h").toFloat()
                    val r = VideoRect(
                        x, y, w, h, o.optDouble("k", 1.0).toFloat(),
                        o.optDouble("cx", x.toDouble()).toFloat(), o.optDouble("cy", y.toDouble()).toFloat(),
                        o.optDouble("cw", w.toDouble()).toFloat(), o.optDouble("ch", h.toDouble()).toFloat(),
                    )
                    if (it.rect == null) {
                        Log.i(TAG, "browser: in-page player at ${r.x.toInt()},${r.y.toInt()} ${r.w.toInt()}x${r.h.toInt()} css px (×${r.k})" +
                            if (r.cropped) ", shown ${r.cx.toInt()},${r.cy.toInt()} ${r.cw.toInt()}x${r.ch.toInt()}" else "")
                    }
                    it.rect = r
                }
                "gone" -> if (tab.inline?.id == o.optString("id")) closeInline(tab, notify = false)
                "at" -> tab.inline?.takeIf { it.id == o.optString("id") }?.let {
                    val ms = (o.optDouble("pos", 0.0) * 1000).toLong()
                    val p = PlayerHub.playerOrNull
                    if (p != null && PlayerHub.item.value === it.item && ms - p.currentPosition > 3000) {
                        Log.i(TAG, "browser: in-page player follows the page to ${ms / 1000}s")
                        p.seekTo(ms)
                    }
                }
                else -> tab.detector.onScript(o, reply) { ev -> onPagePlay(tab, ev) }
            }
        }
        if (msgListener) {
            WebViewCompat.addWebMessageListener(wv, "dvMedia", setOf("*")) { _, message, _, _, replyProxy ->
                message.data?.let { onMessage(it) { m -> replyProxy.postMessage(m) } }
            }
        } else {
            wv.addJavascriptInterface(
                object {
                    @android.webkit.JavascriptInterface
                    fun postMessage(json: String) = main.post { onMessage(json, null) }
                },
                "dvMedia",
            )
        }
        if (docStart) {
            tab.autoScript = WebViewCompat.addDocumentStartJavaScript(wv, autoPlayerJs(Settings.current.autoPlayer), setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(wv, INJECTED_JS, setOf("*"))
        }
    }

    private fun adBlockOn(tab: BrowserTab): Boolean {
        val s = Settings.current
        val host = tab.pageHost
        return s.adBlock && (host == null || host !in s.adBlockAllow)
    }

    /**
     * Registers the cosmetic filters and scriptlets of [url]'s site to run
     * at document start in its frames (as Brave injects them). Called before
     * a navigation commits; returns the script when it was missing, so a
     * page already loading can still get it.
     */
    private fun prepareCosmetics(tab: BrowserTab, url: String): String? {
        val wv = tab.webView ?: return null
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (!docStart || (scheme != "http" && scheme != "https")) return null
        val host = uri.host ?: return null
        val s = Settings.current
        if (!s.adBlock || LinkParser.hostOf(url) in s.adBlockAllow) return null
        val origin = if (uri.port == -1) "$scheme://$host" else "$scheme://$host:${uri.port}"
        if (tab.cosmetics.containsKey(origin)) return null
        val c = AdBlocker.cosmetic(url) ?: return null
        val js = if (c.isEmpty && !c.genericHide) null else cosmeticJs(c)
        tab.cosmetics[origin] = js?.let { WebViewCompat.addDocumentStartJavaScript(wv, it, setOf(origin)) }
        if (tab.cosmetics.size > COSMETIC_ORIGINS) {
            val eldest = tab.cosmetics.keys.first()
            tab.cosmetics.remove(eldest)?.remove()
        }
        Log.i(TAG, "adblock: $host: ${c.css.count { it == '\n' }} hidden selectors, ${c.script.length} chars of scriptlets${if (c.genericHide) ", generichide" else ""}")
        return js
    }

    private fun clearCosmetics(tab: BrowserTab) {
        for (h in tab.cosmetics.values) h?.remove()
        tab.cosmetics.clear()
    }

    /** Generic cosmetic filters for the classes/ids a frame reported. */
    private fun genericCosmetics(tab: BrowserTab, frameUrl: String, tokens: String, reply: (String) -> Unit) {
        if (!adBlockOn(tab) || frameUrl.isEmpty()) return
        scope.launch {
            val css = withContext(Dispatchers.Default) { AdBlocker.hidden(frameUrl, tokens) } ?: return@launch
            reply("css:$css")
        }
    }

    /** The "Open videos in the player" setting changed: tell live pages. */
    private fun applyAutoPlayer(on: Boolean) {
        for (tab in tabs) {
            val wv = tab.webView ?: continue
            if (docStart) {
                tab.autoScript?.remove()
                tab.autoScript = WebViewCompat.addDocumentStartJavaScript(wv, autoPlayerJs(on), setOf("*"))
            }
            wv.evaluateJavascript(autoPlayerJs(on), null)
        }
    }

    private var takeover: Job? = null

    private fun onPagePlay(tab: BrowserTab, ev: PlayEvent) {
        if (ev.drm) tab.detector.markDrm("play on ${tab.url}")
        if (ev.takeover) {
            takeOver(tab, ev)
            return
        }
        val req = playRequestFor(tab, ev) ?: return
        Log.i(TAG, "browser: page plays ${ev.src.take(80)} -> ${req.url.take(120)}")
        tab.playOffer = req
        tab.offerEvent = ev
    }

    /** "Open in player": DownVid's player takes the page video's place. */
    fun playOffered(tab: BrowserTab) {
        val offered = tab.playOffer ?: return
        val ev = tab.offerEvent
        tab.playOffer = null
        tab.offerEvent = null
        // Built when the page started playing: its master playlist may have
        // been resolved since.
        val req = ev?.let { playRequestFor(tab, it) } ?: offered
        val reply = ev?.reply
        if (ev == null || reply == null || ev.id.isEmpty()) {
            pausePage(tab)
            onPlay(req)
            return
        }
        startInline(tab, ev, req)
    }

    /** Plays [req] in place of the page's video [ev] (paused by the script). */
    private fun startInline(tab: BrowserTab, ev: PlayEvent, req: PlayRequest) {
        val reply = ev.reply ?: return
        closeInline(tab)
        val item = PlayItem(
            url = req.url,
            title = req.title,
            pageUrl = req.pageUrl,
            headers = req.headers,
            streamType = when (MediaUrls.classify(req.url)) {
                MediaKind.HLS -> "hls"
                MediaKind.DASH -> "dash"
                else -> null
            },
            incognito = req.incognito,
            startSec = req.position,
            detected = tab.detector.state.value.asResult(tab.displayTitle),
        )
        tab.inline = InlineVideo(ev.id, item, reply)
        // (Re)starts the script's tracking: the position comes right after.
        reply("take:${ev.id}")
        PlayerHub.play(activity, item, inPage = true)
        Log.i(TAG, "browser: in-page player for ${ev.id} -> ${req.url.take(120)}")
    }

    /** Back to the page's own player (paused where it was). */
    fun closeInline(tab: BrowserTab, notify: Boolean = true) {
        val v = tab.inline ?: return
        tab.inline = null
        if (PlayerHub.item.value === v.item) PlayerHub.release(activity)
        if (notify) runCatching { v.reply("release") }
        Log.i(TAG, "browser: in-page player closed")
    }

    /**
     * The page's player was paused as it started (auto mode): DownVid's
     * player takes over. A blob:/MSE player may still be loading its
     * manifest, so wait for it a moment; without a playable URL (or with
     * DRM) the page's video resumes.
     */
    private fun takeOver(tab: BrowserTab, ev: PlayEvent) {
        takeover?.cancel()
        takeover = scope.launch {
            val req = withTimeoutOrNull(TAKEOVER_WAIT_MS) {
                var r = playRequestFor(tab, ev)
                while (r == null) {
                    delay(100)
                    r = playRequestFor(tab, ev)
                }
                r
            }
            val usable = req != null && tab === current && !tab.detector.state.value.drm && !MediaUrls.isPreview(req.url)
            if (!usable) {
                Log.i(TAG, "browser: takeover declined (${if (req == null) "no playable URL" else "drm/preview/background"}) for ${ev.src.take(80)}")
                ev.reply?.invoke("resume")
                if (req != null) tab.playOffer = req
                return@launch
            }
            Log.i(TAG, "browser: takeover ${ev.src.take(60)} -> ${req!!.url.take(120)}")
            tab.playOffer = null
            if (ev.reply != null && ev.id.isNotEmpty()) {
                startInline(tab, ev, req)
            } else {
                // Without a channel to the frame (old WebView): the full player.
                tab.webView?.evaluateJavascript(PAUSE_JS, null)
                onPlay(req)
            }
        }
    }

    private fun openExternally(uri: Uri): Boolean = try {
        val intent = if (uri.scheme == "intent") {
            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                // Only to apps that declare themselves browsable; no explicit components.
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }
        } else {
            Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        }
        activity.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: Exception) {
        Log.w(TAG, "browser: cannot open $uri: ${e.message}")
        false
    }

    private fun syncNav(tab: BrowserTab, view: WebView) {
        tab.canGoBack = view.canGoBack()
        tab.canGoForward = view.canGoForward()
        tab.blocked = tab.blockedCounter.get()
    }

    private inner class Client(private val tab: BrowserTab) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (!request.isForMainFrame && adBlockOn(tab)) {
                AdBlocker.intercept(request, tab.pageUrl)?.let {
                    tab.blockedCounter.incrementAndGet()
                    return it
                }
            }
            tab.detector.onNetwork(url.toString(), request.requestHeaders["Referer"])
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase() ?: return true
            if (scheme == "http" || scheme == "https") {
                if (request.isForMainFrame) prepareCosmetics(tab, request.url.toString())
                return false
            }
            if (scheme == "about" || scheme == "data" || scheme == "blob") return false
            // intent://, market://, tel:... only when the user tapped something.
            if (request.hasGesture()) openExternally(request.url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            closeInline(tab, notify = false)
            tab.url = url
            tab.pageHost = LinkParser.hostOf(url)
            tab.pageUrl = url
            // History navigations and restored tabs skip shouldOverrideUrlLoading.
            prepareCosmetics(tab, url)?.let { view.evaluateJavascript(it, null) }
            tab.detector.reset(url)
            tab.playOffer = null
            tab.blockedCounter.set(0)
            syncNav(tab, view)
        }

        override fun onPageFinished(view: WebView, url: String) {
            syncNav(tab, view)
            if (!tab.incognito) BrowserStore.retitle(url, view.title.orEmpty())
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (url != tab.url) {
                tab.url = url
                tab.pageHost = LinkParser.hostOf(url)
                tab.pageUrl = url
                tab.detector.reset(url)
            }
            syncNav(tab, view)
            if (!tab.incognito && !isReload) BrowserStore.visit(url, view.title.orEmpty())
            if (!tab.incognito) persist()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Without this the system kills the whole app when a renderer dies.
            Log.w(TAG, "browser: renderer gone in tab ${tab.id} (crashed=${detail.didCrash()})")
            if (tab.webView === view) {
                tab.saved = null
                destroyWebView(tab)
                if (current === tab) main.post { select(tab) } // reloads the page
            }
            return true
        }
    }

    private inner class Chrome(private val tab: BrowserTab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tab.progress = newProgress
            if (newProgress % 25 == 0) syncNav(tab, view)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            tab.title = title.orEmpty()
            if (!tab.incognito) BrowserStore.retitle(view.url.orEmpty(), title.orEmpty())
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            // Pop-ups only from a tap; the rest are ads.
            if (!isUserGesture) return false
            val tab2 = newTab("", tab.incognito)
            tab2.url = "about:blank"
            tabs += tab2
            val wv = ensureWebView(tab2) ?: return false
            (resultMsg.obj as WebView.WebViewTransport).webView = wv
            resultMsg.sendToTarget()
            select(tab2)
            return true
        }

        override fun onCloseWindow(window: WebView) {
            tabs.firstOrNull { it.webView === window }?.let(::close)
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            fullscreenCallback?.onCustomViewHidden()
            fullscreenCallback = callback
            fullscreen = view
        }

        override fun onHideCustomView() {
            fullscreenCallback = null
            fullscreen = null
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            fileChooser?.first?.onReceiveValue(null)
            fileChooser = filePathCallback to fileChooserParams
            return true
        }

        /**
         * Only protected media (Widevine): DRM sites (Vimeo, streaming
         * services) play in their own player, as in Chrome. DownVid never
         * downloads or takes over such videos. Camera, microphone, MIDI: no.
         */
        override fun onPermissionRequest(request: PermissionRequest) {
            val drm = PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
            if (drm in request.resources) {
                tab.detector.markDrm("protected media for ${request.origin}")
                tab.playOffer = null
                tab.offerEvent = null
                request.grant(arrayOf(drm))
            } else {
                request.deny()
            }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
            callback.invoke(origin, false, false)
    }

    /** Desktop / mobile site for the tab (reloads). */
    fun setDesktop(tab: BrowserTab, desktop: Boolean) {
        tab.desktop = desktop
        tab.webView?.let {
            it.settings.userAgentString = if (desktop) InstagramQuery.desktopUserAgent(defaultUa) else null
            it.reload()
        }
    }

    /** Waits a moment, then lets the page's own player go (after opening ours). */
    fun pausePage(tab: BrowserTab) {
        scope.launch {
            tab.webView?.evaluateJavascript(PAUSE_JS, null)
            delay(300)
            tab.webView?.evaluateJavascript(PAUSE_JS, null)
        }
    }

    companion object {
        private const val TAG = "DownVid"
        private const val LIVE_MAX = 6
        private const val TAKEOVER_WAIT_MS = 4000L
        private const val COSMETIC_ORIGINS = 24
        private const val INCOGNITO = "incognito"

        /**
         * The master playlist of manifest [m]: [m] itself when it is one, the
         * master listing it, or the page's only master (a master keeps one
         * variant per height, so the one the player picked may be unlisted).
         */
        internal fun masterOf(options: List<MediaOption>, m: String): String {
            val masters = options.filter { it.isHls && it.group.isNotEmpty() && it.group != it.url }.map { it.group }.distinct()
            if (m in masters) return m
            options.firstOrNull { it.url == m }?.group?.takeIf { it.isNotEmpty() && it != m }?.let { return it }
            return masters.singleOrNull() ?: m
        }
    }
}
