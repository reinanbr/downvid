package com.downvid.downvid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Foreground service that keeps the download queue alive.
 *
 * Downloads run in the Go core (persistent queue, see go/internal/jobs). This
 * service polls their status (DvCore.nativeStatus) and, for every active job,
 * shows a notification (queued / downloading with Pause + Cancel / waiting
 * for network). Finished files are published to MediaStore and recorded in
 * the history (nativeMarkSaved). It stops when nothing is active.
 */
class DownloadService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val saving = HashSet<Long>()
    private val shown = HashSet<Long>() // ids with an ongoing notification
    private val reported = HashSet<Long>() // final notifications already posted
    private var foregroundId = 0
    private val startedAt = System.currentTimeMillis()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        createChannels()
        DvCore.ensureInit(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, -1) ?: -1
        when (intent?.action) {
            ACTION_CANCEL -> DvCore.nativeCancel(id).also { Log.i(TAG, "service: cancel job $id from notification") }
            ACTION_PAUSE -> DvCore.nativePause(id).also { Log.i(TAG, "service: pause job $id from notification") }
        }
        // startForegroundService requires startForeground promptly.
        if (foregroundId == 0) promote(FG_PLACEHOLDER, placeholderNotification())
        acquireLocks()
        main.removeCallbacks(poll)
        main.post(poll)
        return START_NOT_STICKY
    }

    private val poll = object : Runnable {
        override fun run() {
            val jobs = try {
                JSONArray(DvCore.nativeStatus())
            } catch (e: Exception) {
                Log.e(TAG, "service: status failed", e)
                JSONArray()
            }
            val activeIds = ArrayList<Long>()
            val seen = HashSet<Long>()
            for (i in 0 until jobs.length()) {
                val s = jobs.getJSONObject(i)
                val id = s.getLong("id")
                seen += id
                val state = s.optString("state")
                if (state in ACTIVE) activeIds += id
                handle(id, state, s)
            }
            // Jobs removed from the queue (deleted in the app).
            for (id in shown.filter { it !in seen }) {
                nm.cancel(notifId(id))
                shown -= id
            }
            updateForeground(activeIds, jobs)
            if (activeIds.isNotEmpty() || saving.isNotEmpty()) main.postDelayed(this, POLL_MS) else stopIdle()
        }
    }

    private fun handle(id: Long, state: String, s: JSONObject) {
        val title = s.optString("title").ifEmpty { "Download" }
        val fresh = s.optLong("updated") >= startedAt // don't re-announce old history
        when (state) {
            "running", "queued", "waiting" -> {
                nm.notify(notifId(id), progressNotification(id, title, state, s))
                shown += id
            }
            "done" -> if (id !in saving) save(id, title, s)
            "paused", "canceled" -> if (shown.remove(id)) nm.cancel(notifId(id))
            "error", "expired" -> if (fresh && reported.add(id)) {
                shown -= id
                val headline = if (state == "expired") "Link expired" else "Download failed"
                val text = if (state == "expired") "Open DownVid to continue (the link will be renewed)." else s.optString("message")
                nm.notify(notifId(id), resultNotification(title, headline, text, null))
            }
        }
    }

    private fun save(id: Long, title: String, s: JSONObject) {
        saving += id
        val path = s.optString("path")
        val warning = s.optString("warning").ifEmpty { null }
        nm.notify(notifId(id), savingNotification(title))
        shown += id
        io.execute {
            try {
                val saved = MediaSaver.save(applicationContext, File(path), title)
                DvCore.nativeMarkSaved(
                    id,
                    JSONObject().put("uri", saved.uri.toString()).put("displayName", saved.displayName)
                        .put("location", saved.location).put("mime", saved.mime).toString(),
                )
                main.post {
                    saving -= id
                    shown -= id
                    val text = saved.displayName + (warning?.let { " · $it" } ?: "")
                    nm.notify(notifId(id), resultNotification(title, "Download complete", text, saved.uri, saved.mime))
                    broadcast(
                        mapOf(
                            "event" to "saved", "id" to id, "uri" to saved.uri.toString(),
                            "displayName" to saved.displayName, "location" to saved.location, "mime" to saved.mime,
                        ),
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "service: save failed for job $id", e)
                main.post {
                    saving -= id
                    shown -= id
                    nm.notify(notifId(id), resultNotification(title, "Could not save", e.message ?: "$e", null))
                    broadcast(mapOf("event" to "saveFailed", "id" to id, "message" to (e.message ?: "$e")))
                }
            }
        }
    }

    /** The foreground notification follows the first active job. */
    private fun updateForeground(activeIds: List<Long>, jobs: JSONArray) {
        val first = activeIds.lastOrNull() ?: return // oldest active (list is newest first)
        if (foregroundId == notifId(first)) return
        for (i in 0 until jobs.length()) {
            val s = jobs.getJSONObject(i)
            if (s.getLong("id") == first) {
                promote(notifId(first), progressNotification(first, s.optString("title"), s.optString("state"), s))
                nm.cancel(FG_PLACEHOLDER)
                return
            }
        }
    }

    private fun stopIdle() {
        main.removeCallbacks(poll)
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        nm.cancel(FG_PLACEHOLDER)
        foregroundId = 0
        stopSelf()
    }

    private fun promote(id: Int, n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, id, n, type)
        foregroundId = id
    }

    // Android 15: dataSync services get a daily time budget.
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "service: foreground time limit reached; pausing downloads")
        val jobs = JSONArray(DvCore.nativeStatus())
        for (i in 0 until jobs.length()) {
            val s = jobs.getJSONObject(i)
            if (s.optString("state") in ACTIVE) DvCore.nativePause(s.getLong("id"))
        }
        stopIdle()
    }

    override fun onDestroy() {
        main.removeCallbacks(poll)
        releaseLocks()
        io.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notifications

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Downloads in progress", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Completed downloads", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun placeholderNotification() = NotificationCompat.Builder(this, CH_PROGRESS)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("DownVid")
        .setContentText("Preparing downloads…")
        .setSilent(true)
        .build()

    private fun progressNotification(id: Long, title: String, state: String, s: JSONObject): Notification {
        val phase = s.optString("phase")
        val percent = s.optDouble("percent", 0.0)
        val (text, indeterminate) = when {
            state == "queued" -> "Queued" to true
            state == "waiting" -> "No connection — retrying…" to true
            phase == "muxing" -> "Converting to MP4…" to true
            phase == "converting" -> "Converting audio… ${percent.toInt()}%" to (percent <= 0.0)
            else -> progressText(s) to (percent <= 0.0)
        }
        val b = NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(if (percent > 0) "${percent.toInt()}%" else null)
            .setProgress(100, percent.toInt(), indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
        if (state == "running" || state == "waiting") b.addAction(0, "Pause", actionIntent(ACTION_PAUSE, id))
        b.addAction(0, "Cancel", actionIntent(ACTION_CANCEL, id))
        return b.build()
    }

    private fun savingNotification(title: String) = NotificationCompat.Builder(this, CH_PROGRESS)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setContentText("Saving to gallery…")
        .setProgress(100, 0, true)
        .setOngoing(true)
        .setSilent(true)
        .build()

    private fun resultNotification(title: String, headline: String, text: String, uri: Uri?, mime: String = "video/mp4") =
        NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(if (uri != null) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(headline)
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$text"))
            .setAutoCancel(true)
            .setContentIntent(if (uri != null) openMediaIntent(uri, mime) else openAppIntent())
            .build()

    private fun progressText(s: JSONObject): String {
        val bytes = s.optLong("bytes")
        val total = s.optLong("total", -1)
        val speed = s.optDouble("speedBps", 0.0)
        val segs = s.optInt("segmentsTotal")
        val parts = mutableListOf<String>()
        parts += if (total > 0) {
            "${fmt(bytes)} / ${if (s.optBoolean("totalEstimate")) "~" else ""}${fmt(total)}"
        } else {
            fmt(bytes)
        }
        if (speed > 0) parts += "${fmt(speed.toLong())}/s"
        if (segs > 0) parts += "seg ${s.optInt("segmentsDone")}/$segs"
        return parts.joinToString(" · ")
    }

    private fun fmt(b: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return if (i == 0 || v >= 100) "${v.toInt()} ${units[i]}" else String.format("%.1f %s", v, units[i])
    }

    private fun actionIntent(action: String, id: Long): PendingIntent = PendingIntent.getService(
        this, (action.hashCode() * 31 + id).toInt(),
        Intent(this, DownloadService::class.java).setAction(action).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openMediaIntent(uri: Uri, mime: String): PendingIntent = PendingIntent.getActivity(
        this, uri.hashCode(),
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ---------------------------------------------------------------- locks

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DownVid:download")
                .apply { setReferenceCounted(false); acquire(WAKE_TIMEOUT_MS) }
        }
        if (wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            } else {
                WifiManager.WIFI_MODE_FULL
            }
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(mode, "DownVid:download")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    companion object {
        private const val TAG = "DownVid"
        private const val ACTION_ENSURE = "com.downvid.downvid.ENSURE"
        private const val ACTION_CANCEL = "com.downvid.downvid.CANCEL"
        private const val ACTION_PAUSE = "com.downvid.downvid.PAUSE"
        private const val EXTRA_ID = "id"
        private const val CH_PROGRESS = "downloads"
        private const val CH_DONE = "downloads_done"
        private const val POLL_MS = 700L
        private const val WAKE_TIMEOUT_MS = 3 * 60 * 60 * 1000L
        private const val FG_PLACEHOLDER = 999

        /** States that keep the service (and its notification) alive. */
        val ACTIVE = setOf("queued", "running", "waiting", "done")

        private fun notifId(id: Long) = 1000 + id.toInt()

        /** UI engines subscribe to save results (NativeBridge forwards to Dart). */
        val listeners = CopyOnWriteArraySet<(Map<String, Any?>) -> Unit>()

        private fun broadcast(event: Map<String, Any?>) {
            for (l in listeners) l(event)
        }

        /** Makes sure the service is running (it picks up every active job). */
        fun ensureRunning(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, DownloadService::class.java).setAction(ACTION_ENSURE),
            )
        }

        /** Compatibility with the sheet's flow: a new job was queued. */
        fun track(context: Context, id: Long, title: String) = ensureRunning(context)
    }
}
