package com.downvid.downvid

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import org.json.JSONObject

/**
 * Fetches a public Instagram post the way instagram.com does for visitors
 * who are not logged in: opens the site in a hidden WebView (a real Chrome),
 * reads the page's session token and runs the site's own post query
 * (/api/graphql). Only public content is returned; the response is parsed by
 * the Go core (internal/instagram). Media files are then downloaded by Go
 * from Instagram's CDN, which needs no session.
 */
class InstagramQuery(private val activity: Activity) {
    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var done = false
    private lateinit var callback: (Result<String>) -> Unit

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun run(query: JSONObject, onResult: (Result<String>) -> Unit) {
        callback = onResult
        val t0 = System.currentTimeMillis()
        val wv = WebView(activity)
        webView = wv
        wv.alpha = 0f
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        // Instagram serves desktop browsers more renditions (VP9 up to
        // 1440p) than mobile ones (H.264 720p): present the WebView as the
        // desktop build of the same Chrome version.
        wv.settings.userAgentString = desktopUserAgent(WebSettings.getDefaultUserAgent(activity))
        wv.addJavascriptInterface(Bridge(t0), "DVBridge")
        var started = false
        wv.webViewClient = object : WebViewClient() {
            // Without this the system kills the whole app when the renderer dies.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "instagram: WebView renderer gone (crashed=${detail.didCrash()})")
                main.post { finish(Result.failure(IllegalStateException("Instagram WebView crashed"))) }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (started || done) return
                started = true
                val mode = query.optString("mode", "public")
                Log.i(TAG, "instagram: page ready in ${System.currentTimeMillis() - t0}ms, $mode query ${query.optString("shortcode").ifEmpty { query.optString("storyPk") }}")
                view.evaluateJavascript(if (mode == "public") script(query) else loggedInScript(query), null)
            }
        }
        activity.findViewById<ViewGroup>(android.R.id.content)
            .addView(wv, 0, ViewGroup.LayoutParams(1, 1))
        main.postDelayed({ finish(Result.failure(IllegalStateException("timed out querying Instagram"))) }, TIMEOUT_MS)
        // "public": the post page itself (session token + current id of the
        // post query, which Instagram rotates). "media"/"story" (logged in):
        // any instagram.com page, for the origin and the csrftoken cookie.
        val mode = query.optString("mode", "public")
        wv.loadUrl(
            if (mode == "public") query.optString("referer").ifEmpty { "https://www.instagram.com/" }
            else "https://www.instagram.com/",
        )
    }

    private inner class Bridge(private val t0: Long) {
        @JavascriptInterface
        fun result(status: Int, body: String) {
            Log.i(TAG, "instagram: HTTP $status, ${body.length} chars in ${System.currentTimeMillis() - t0}ms")
            main.post { finish(Result.success(body)) }
        }

        @JavascriptInterface
        fun log(message: String) = Log.i(TAG, "instagram: $message")

        @JavascriptInterface
        fun error(message: String) {
            Log.w(TAG, "instagram: query failed: $message")
            main.post { finish(Result.failure(IllegalStateException(message))) }
        }
    }

    private fun finish(r: Result<String>) {
        if (done) return
        done = true
        main.removeCallbacksAndMessages(null)
        webView?.let {
            it.stopLoading()
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        callback(r)
    }

    /** The site's own request: session token from the page, csrftoken cookie. */
    private fun script(q: JSONObject) = """
        (async function () {
          const Q = $q;
          try {
            const html = document.documentElement.innerHTML;
            let lsd = (html.match(/"LSD",\[\],\{"token":"([^"]+)"/) || [])[1];
            if (!lsd) {
              const e = document.getElementById('__eqmc');
              if (e) { try { lsd = JSON.parse(e.textContent).l; } catch (_) {} }
            }
            const csrf = (document.cookie.match(/(?:^|; )csrftoken=([^;]+)/) || [])[1] || '';
            // Current query id, as preloaded by the page; Go's value is the fallback.
            const qid = (html.match(new RegExp('"queryID":"(\\d+)","variables":\\{[^}]*\\},"queryName":"' + Q.friendlyName + '"')) || [])[1];
            const docId = qid || Q.docId;
            const body = new URLSearchParams({
              lsd: lsd || '', fb_api_caller_class: 'RelayModern',
              fb_api_req_friendly_name: Q.friendlyName, server_timestamps: 'true',
              variables: Q.variables, doc_id: docId,
            });
            const r = await fetch('/api/graphql', {
              method: 'POST', credentials: 'include', referrer: Q.referer, body: body,
              headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
                'X-IG-App-ID': Q.appId, 'X-FB-LSD': lsd || '', 'X-ASBD-ID': '129477',
                'X-FB-Friendly-Name': Q.friendlyName, 'X-Requested-With': 'XMLHttpRequest',
                'X-CSRFToken': csrf,
              },
            });
            DVBridge.log('doc_id=' + docId + (qid ? ' (from page)' : ' (default)'));
            DVBridge.result(r.status, await r.text());
          } catch (e) {
            DVBridge.error(String(e));
          }
        })();
    """.trimIndent()

    /**
     * Logged-in requests (the user's own session): media info for a post, or
     * the user's current stories (Go picks the shared one).
     */
    private fun loggedInScript(q: JSONObject) = """
        (async function () {
          const Q = $q;
          try {
            const csrf = (document.cookie.match(/(?:^|; )csrftoken=([^;]+)/) || [])[1] || '';
            const headers = {
              'X-IG-App-ID': Q.appId, 'X-ASBD-ID': '129477', 'X-CSRFToken': csrf,
              'X-Requested-With': 'XMLHttpRequest',
            };
            let r;
            if (Q.mode === 'story') {
              const pr = await fetch('/api/v1/users/web_profile_info/?username=' + encodeURIComponent(Q.username),
                                     { credentials: 'include', headers: headers });
              const pt = await pr.text();
              let uid = null;
              try { uid = JSON.parse(pt).data.user.id; } catch (_) {}
              if (!uid) { DVBridge.result(pr.status, pt.startsWith('{') ? pt : '{"status":"fail","message":"user not found"}'); return; }
              r = await fetch('/api/v1/feed/reels_media/?reel_ids=' + uid, { credentials: 'include', headers: headers });
            } else {
              r = await fetch('/api/v1/media/' + Q.mediaId + '/info/', { credentials: 'include', headers: headers });
            }
            const t = await r.text();
            // A redirect to the login page means the session expired.
            DVBridge.result(r.status, t.startsWith('{') ? t : '{"status":"fail","message":"login_required"}');
          } catch (e) {
            DVBridge.error(String(e));
          }
        })();
    """.trimIndent()

    companion object {
        private const val TAG = "DownVid"
        private const val TIMEOUT_MS = 25_000L

        private fun desktopUserAgent(mobile: String): String {
            val chrome = Regex("""Chrome/([\d.]+)""").find(mobile)?.groupValues?.get(1) ?: "140.0.0.0"
            return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
        }
    }
}
