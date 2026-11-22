package com.downvid.downvid

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.downvid.downvid.core.JobController
import com.downvid.downvid.core.Settings
import com.downvid.downvid.ui.DisclaimerDialog
import com.downvid.downvid.ui.DownVidTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Share target (ACTION_SEND text/plain) and the "Paste & download" tile.
 * A translucent window shows only the download sheet over the app the link
 * was shared from. A new share while the sheet is open replaces it; a running
 * download is never canceled, it continues in DownloadService.
 */
class ShareActivity : ComponentActivity() {
    private data class Shared(val seq: Int, val text: String?)

    private val shared = MutableStateFlow<Shared?>(null)
    private var seq = 0

    // Paste mode: the clipboard can only be read once this window has focus
    // (Android 10+), so the text waits for it.
    private var pasteMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        JobController.start(this)
        pasteMode = intent?.action == ACTION_PASTE
        if (!pasteMode) post(extractText(intent))
        setContent {
            DownVidTheme {
                val s by shared.collectAsStateWithLifecycle()
                var disclaimer by remember { mutableStateOf(!Settings.current.disclaimerAccepted) }
                if (disclaimer) {
                    DisclaimerDialog { disclaimer = false }
                } else {
                    s?.let { cur -> key(cur.seq) { LinkSheet(cur.text.orEmpty(), ::finish) } }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pasteMode) readClipboard()
    }

    private fun readClipboard() {
        pasteMode = false
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        Log.i(TAG, "paste: clipboard has ${text.length} chars")
        post(text)
    }

    private fun post(text: String?) {
        prefetch(text)
        Log.i(TAG, "share: sheet for ${text?.replace('\n', ' ')}")
        shared.value = Shared(++seq, text)
    }

    /** Starts yt-dlp while the sheet is still being drawn (saves ~1 s). */
    private fun prefetch(text: String?) {
        val url = text?.let { URL_RE.find(it)?.value?.trimEnd(*TRAILING) } ?: return
        YtDlp.warmUp(this)
        // Instagram posts/reels use the public post query (InstagramQuery);
        // yt-dlp is only a fallback there, so don't spend it up front.
        if (INSTAGRAM_POST_RE.containsMatchIn(url)) return
        YtDlp.prefetch(url)
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
        post(extractText(intent))
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
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
        private const val TAG = "DownVid"
        const val ACTION_PASTE = "com.downvid.downvid.PASTE"

        // Same rule as LinkParser (first http(s) URL, trailing punctuation trimmed).
        private val URL_RE = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
        private val INSTAGRAM_POST_RE = Regex("""instagram\.com/(?:(?:[\w.]+/)?(?:p|reel|reels|tv)|stories)/""")
        private val TRAILING = charArrayOf(')', '.', ',', ';', ':', '!', '?', ']', '}', '>')
    }
}
