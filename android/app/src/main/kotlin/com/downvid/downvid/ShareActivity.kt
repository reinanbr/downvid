package com.downvid.downvid

import android.content.Intent
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.FlutterActivityLaunchConfigs.BackgroundMode
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * Share target (ACTION_SEND text/plain). Runs the `shareMain` Dart entrypoint
 * in a transparent window so only the bottom sheet is shown on top of the
 * app that shared the link.
 */
class ShareActivity : FlutterActivity() {
    private var channel: MethodChannel? = null
    private var bridge: NativeBridge? = null
    private var sharedText: String? = null

    // "Paste & download" (Quick Settings tile): the clipboard can only be read
    // once this window has focus (Android 10+), so the text request waits.
    private var pasteMode = false
    private val pendingText = ArrayList<MethodChannel.Result>()

    override fun onCreate(savedInstanceState: Bundle?) {
        pasteMode = intent?.action == ACTION_PASTE
        if (!pasteMode) {
            sharedText = extractText(intent)
            prefetch(sharedText)
        }
        super.onCreate(savedInstanceState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pasteMode) readClipboard()
    }

    private fun readClipboard() {
        pasteMode = false
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        sharedText = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        android.util.Log.i("DownVid", "paste: clipboard has ${sharedText!!.length} chars")
        prefetch(sharedText)
        if (pendingText.isEmpty()) {
            channel?.invokeMethod("onSharedText", sharedText)
        } else {
            pendingText.forEach { it.success(sharedText) }
            pendingText.clear()
        }
    }

    /** Starts yt-dlp before the Flutter engine is up (saves ~1 s). */
    private fun prefetch(text: String?) {
        val url = text?.let { URL_RE.find(it)?.value?.trimEnd(*TRAILING) } ?: return
        YtDlp.warmUp(this)
        // Instagram posts/reels use the public post query (InstagramQuery);
        // yt-dlp is only a fallback there, so don't spend it up front.
        if (INSTAGRAM_POST_RE.containsMatchIn(url)) return
        YtDlp.prefetch(url)
    }

    override fun getDartEntrypointFunctionName(): String = "shareMain"

    override fun getBackgroundMode(): BackgroundMode = BackgroundMode.transparent

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        bridge = NativeBridge(this, flutterEngine)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).apply {
            setMethodCallHandler { call, result ->
                when (call.method) {
                    "getSharedText" -> if (pasteMode) pendingText += result else result.success(sharedText)
                    "close" -> {
                        result.success(null)
                        finish()
                    }
                    else -> result.notImplemented()
                }
            }
        }
    }

    // launchMode="singleTask": a second share while the sheet is open lands here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_PASTE) {
            pasteMode = true
            if (hasWindowFocus()) readClipboard()
            return
        }
        sharedText = extractText(intent)
        prefetch(sharedText)
        channel?.invokeMethod("onSharedText", sharedText)
    }

    override fun onDestroy() {
        bridge?.dispose()
        bridge = null
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    private fun extractText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        // Some apps put the URL in EXTRA_SUBJECT and a caption in EXTRA_TEXT.
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        return listOfNotNull(text, subject).joinToString("\n").ifBlank { null }
    }

    companion object {
        private const val CHANNEL = "downvid/share"
        const val ACTION_PASTE = "com.downvid.downvid.PASTE"

        // Same rule as lib/core/url/link_parser.dart (first http(s) URL,
        // trailing punctuation trimmed), so both sides agree on the key.
        private val URL_RE = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
        private val INSTAGRAM_POST_RE = Regex("""instagram\.com/(?:(?:[\w.]+/)?(?:p|reel|reels|tv)|stories)/""")
        private val TRAILING = charArrayOf(')', '.', ',', ';', ':', '!', '?', ']', '}', '>')
    }
}
