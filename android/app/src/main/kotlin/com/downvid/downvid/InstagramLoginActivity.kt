package com.downvid.downvid

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Instagram's (or Threads') own login page in a WebView. The user signs in on
 * the site (the app never sees the password); once the session cookie exists,
 * the activity closes. The session lives in the app's private WebView cookie
 * store and is used by InstagramQuery for content the user can see.
 * Extra [EXTRA_SITE]: "instagram" (default) or "threads".
 */
class InstagramLoginActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var webView: WebView
    private lateinit var site: Site

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        site = Site.of(intent.getStringExtra(EXTRA_SITE))
        title = "Sign in to ${site.label}"
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        val hint = TextView(this).apply {
            text = "${site.label}'s official page, opened in this phone's own browser engine. " +
                "Your password is not stored and nothing is sent to any server or cloud: the session " +
                "stays on this device, only to download what you can already see. Remove it with \"Sign out\" in DownVid."
            setPadding(32, 24, 32, 16)
            textSize = 13f
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    Log.w(TAG, "login: WebView renderer gone (crashed=${detail.didCrash()})")
                    finishWith(isLoggedIn(site))
                    return true
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    progress.visibility = android.view.View.VISIBLE
                }

                override fun onPageFinished(view: WebView, url: String) {
                    progress.visibility = android.view.View.GONE
                    // Cookie names only (no values), to see which one marks the session.
                    val names = CookieManager.getInstance().getCookie(site.origin)?.split(";")?.map { it.trim().substringBefore("=") }
                    Log.i(TAG, "${site.id}: login page $url, cookies=$names")
                    checkLoggedIn()
                }
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(hint)
                addView(progress, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )
        webView.loadUrl(site.loginUrl)
        main.post(poll)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!checkLoggedIn()) main.postDelayed(this, 1000)
        }
    }

    private fun checkLoggedIn(): Boolean {
        if (isFinishing || !isLoggedIn(site)) return false
        Log.i(TAG, "${site.id}: logged in")
        CookieManager.getInstance().flush()
        finishWith(true)
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else finishWith(isLoggedIn(site))
    }

    private fun finishWith(ok: Boolean) {
        main.removeCallbacksAndMessages(null)
        pending?.invoke(ok)
        pending = null
        finish()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        pending?.invoke(isLoggedIn(site))
        pending = null
        webView.destroy()
        super.onDestroy()
    }

    enum class Site(val id: String, val label: String, val origin: String, val domain: String, val loginUrl: String) {
        INSTAGRAM("instagram", "Instagram", "https://www.instagram.com", "instagram.com", "https://www.instagram.com/accounts/login/"),
        THREADS("threads", "Threads", "https://www.threads.com", "threads.com", "https://www.threads.com/login"),
        ;

        companion object {
            fun of(id: String?) = entries.firstOrNull { it.id == id } ?: INSTAGRAM
        }
    }

    companion object {
        private const val TAG = "DownVid"
        const val EXTRA_SITE = "site"

        /** Result for the caller (NativeBridge); set before starting the activity. */
        var pending: ((Boolean) -> Unit)? = null

        fun isLoggedIn(site: Site = Site.INSTAGRAM): Boolean =
            CookieManager.getInstance().getCookie(site.origin)?.split(";")?.any { it.trim().startsWith("sessionid=") } == true

        /** Removes the site's session (and its other cookies) from this app. */
        fun logout(site: Site = Site.INSTAGRAM) {
            val cm = CookieManager.getInstance()
            val names = cm.getCookie(site.origin)?.split(";")?.mapNotNull { it.trim().substringBefore("=").ifEmpty { null } }.orEmpty()
            for (name in names) {
                for (domain in listOf(".${site.domain}", "www.${site.domain}")) {
                    cm.setCookie(site.origin, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=$domain; Path=/")
                }
                cm.setCookie(site.origin, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
            }
            cm.flush()
            Log.i(TAG, "${site.id}: logged out (${names.size} cookies removed), still logged in=${isLoggedIn(site)}")
        }
    }
}
