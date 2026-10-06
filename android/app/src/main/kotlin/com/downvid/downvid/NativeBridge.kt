package com.downvid.downvid

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.util.Log
import android.webkit.WebSettings
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Platform channels shared by MainActivity and ShareActivity:
 *  - "downvid/sniffer": hidden-WebView media sniffing
 *  - "downvid/media":   work dir, MediaStore save, open video
 */
class NativeBridge(private val activity: Activity, engine: FlutterEngine) {
    private val sniffChannel = MethodChannel(engine.dartExecutor.binaryMessenger, "downvid/sniffer")
    private val mediaChannel = MethodChannel(engine.dartExecutor.binaryMessenger, "downvid/media")
    private val downloadsChannel = MethodChannel(engine.dartExecutor.binaryMessenger, "downvid/downloads")
    private val ytdlpChannel = MethodChannel(engine.dartExecutor.binaryMessenger, "downvid/ytdlp")
    private val instaChannel = MethodChannel(engine.dartExecutor.binaryMessenger, "downvid/instagram")
    private val clipChannel = MethodChannel(engine.dartExecutor.binaryMessenger, "downvid/clipboard")
    private val settingsChannel = MethodChannel(engine.dartExecutor.binaryMessenger, "downvid/settings")
    private val serviceListener: (Map<String, Any?>) -> Unit = { ev ->
        activity.runOnUiThread { downloadsChannel.invokeMethod("onEvent", ev) }
    }
    private val io = Executors.newSingleThreadExecutor()
    private var sniffer: PageSniffer? = null
    private var sniffSession = 0

    init {
        DvCore.ensureInit(activity)
        initDownloads()
        initYtDlp()
        initInstagram()
        initClipboard()
        initSettings()
        sniffChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "userAgent" -> result.success(WebSettings.getDefaultUserAgent(activity))
                "start" -> {
                    val url = call.argument<String>("url")!!
                    val session = call.argument<Int>("session") ?: 0
                    sniffer?.stop("replaced")
                    var self: PageSniffer? = null
                    self = PageSniffer(
                        activity,
                        onFound = { f ->
                            sniffChannel.invokeMethod("onFound", mapOf("session" to session, "url" to f.url, "referer" to f.referer, "via" to f.via, "cookie" to f.cookie))
                        },
                        onDone = { r ->
                            if (sniffer === self) sniffer = null
                            sniffChannel.invokeMethod(
                                "onDone",
                                mapOf(
                                    "session" to session,
                                    "finalUrl" to r.finalUrl, "title" to r.title, "userAgent" to r.userAgent,
                                    "cookies" to r.cookies, "reason" to r.reason, "count" to r.found.size,
                                ),
                            )
                        },
                    )
                    sniffer = self
                    sniffSession = session
                    self.start(url)
                    result.success(null)
                }
                "stop" -> {
                    val session = call.argument<Int>("session")
                    if (session == null || session == sniffSession) sniffer?.stop("canceled")
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        mediaChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "workDir" -> {
                    val dir = File(activity.noBackupFilesDir, "downloads").apply { mkdirs() }
                    result.success(dir.absolutePath)
                }
                "saveVideo" -> {
                    val path = call.argument<String>("path")!!
                    val title = call.argument<String>("title") ?: ""
                    io.execute {
                        try {
                            val saved = MediaSaver.save(activity.applicationContext, File(path), title)
                            activity.runOnUiThread {
                                result.success(
                                    mapOf(
                                        "uri" to saved.uri.toString(),
                                        "displayName" to saved.displayName,
                                        "location" to saved.location,
                                    ),
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "saveVideo failed", e)
                            activity.runOnUiThread { result.error("save_failed", e.message ?: e.toString(), null) }
                        }
                    }
                }
                "shareMedia" -> {
                    try {
                        val uri = Uri.parse(call.argument<String>("uri")!!)
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = call.argument<String>("mime") ?: "video/mp4"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        activity.startActivity(android.content.Intent.createChooser(send, null))
                        result.success(null)
                    } catch (e: Exception) {
                        result.error("share_failed", e.message, null)
                    }
                }
                "deleteMedia" -> {
                    // Our own MediaStore entries: deletable without user consent.
                    try {
                        val n = activity.contentResolver.delete(Uri.parse(call.argument<String>("uri")!!), null, null)
                        result.success(n > 0)
                    } catch (e: Exception) {
                        Log.w(TAG, "deleteMedia failed", e)
                        result.success(false)
                    }
                }
                "openVideo" -> {
                    try {
                        MediaSaver.open(activity, Uri.parse(call.argument<String>("uri")!!), call.argument<String>("mime") ?: "video/mp4")
                        result.success(null)
                    } catch (e: Exception) {
                        result.error("open_failed", e.message, null)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun initYtDlp() {
        YtDlp.warmUp(activity)
        ytdlpChannel.setMethodCallHandler { call, result ->
            fun reply(block: () -> Any?) = YtDlp.background {
                val r = runCatching(block)
                activity.runOnUiThread {
                    r.fold({ result.success(it) }, { e ->
                        Log.e(TAG, "ytdlp.${call.method} failed", e)
                        result.error("ytdlp", e.message ?: e.toString(), null)
                    })
                }
            }
            when (call.method) {
                "info" -> reply {
                    val r = YtDlp.info(call.argument<String>("url")!!, call.argument<String>("id")!!)
                    mapOf("json" to r.json, "stderr" to r.stderr, "exitCode" to r.exitCode, "elapsedMs" to r.elapsedMs)
                }
                "search" -> reply {
                    val r = YtDlp.search(call.argument<String>("url")!!)
                    mapOf("json" to r.json, "stderr" to r.stderr, "exitCode" to r.exitCode, "elapsedMs" to r.elapsedMs)
                }
                "cancel" -> {
                    YtDlp.cancel(call.argument<String>("id")!!)
                    result.success(null)
                }
                "ffmpeg" -> reply { YtDlp.ffmpegTools() }
                "version" -> reply { YtDlp.version() }
                "update" -> reply { YtDlp.update() }
                else -> result.notImplemented()
            }
        }
    }

    /**
     * "info" (does not read the content, so Android shows no "pasted" toast)
     * tells whether there is text and when it was copied; "read" returns it
     * (only works while the app has focus).
     */
    private fun initSettings() {
        settingsChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "getAll" -> result.success(Settings.all(activity))
                "set" -> {
                    Settings.set(activity, call.argument<String>("key")!!, call.argument<Any?>("value"))
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun initClipboard() {
        clipChannel.setMethodCallHandler { call, result ->
            val cm = activity.getSystemService(android.content.ClipboardManager::class.java)
            when (call.method) {
                "info" -> {
                    val d = cm?.primaryClipDescription
                    val hasText = d != null && (d.hasMimeType("text/*") || d.hasMimeType("text/plain"))
                    val ts = if (d != null && Build.VERSION.SDK_INT >= 26) d.timestamp else 0L
                    result.success(mapOf("hasText" to hasText, "timestamp" to ts))
                }
                "read" -> result.success(
                    cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString(),
                )
                else -> result.notImplemented()
            }
        }
    }

    private fun initInstagram() {
        instaChannel.setMethodCallHandler { call, result ->
            // "site": instagram (default) | threads.
            val site = InstagramLoginActivity.Site.of(call.argument<String>("site"))
            when (call.method) {
                "isLoggedIn" -> result.success(InstagramLoginActivity.isLoggedIn(site))
                "login" -> {
                    InstagramLoginActivity.pending = { ok -> activity.runOnUiThread { result.success(ok) } }
                    activity.startActivity(
                        android.content.Intent(activity, InstagramLoginActivity::class.java)
                            .putExtra(InstagramLoginActivity.EXTRA_SITE, site.id),
                    )
                }
                "logout" -> {
                    InstagramLoginActivity.logout(site)
                    result.success(null)
                }
                "query" -> {
                    @Suppress("UNCHECKED_CAST")
                    val q = JSONObject(call.arguments as Map<String, Any?>)
                    InstagramQuery(activity).run(q) { r ->
                        r.fold({ result.success(it) }, { result.error("instagram", it.message ?: it.toString(), null) })
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun initDownloads() {
        DownloadService.listeners += serviceListener
        downloadsChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "track" -> {
                    val id = call.argument<Number>("id")!!.toLong()
                    val title = call.argument<String>("title") ?: "Video"
                    try {
                        DownloadService.track(activity, id, title)
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "track failed", e)
                        result.success(false)
                    }
                }
                "ensureNotificationPermission" -> {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4201)
                        result.success(false)
                    } else {
                        result.success(true)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    fun dispose() {
        DownloadService.listeners -= serviceListener
        sniffer?.stop("disposed")
        sniffer = null
        io.shutdown()
    }

    companion object {
        private const val TAG = "DownVid"
    }
}
