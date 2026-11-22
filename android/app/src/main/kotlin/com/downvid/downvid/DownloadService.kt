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
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.downvid.downvid.core.Job
import com.downvid.downvid.core.JobController
import com.downvid.downvid.core.SavedFile
import com.downvid.downvid.ui.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foreground service that keeps the download queue alive.
 *
 * Downloads run in the Go core (persistent queue, go/internal/jobs). The
 * service follows [JobController.jobs] (pushed by the core's change feed, no
 * polling) and, for every active job, shows a notification (queued /
 * downloading with Pause + Cancel / waiting for network). Finished files are
 * published to MediaStore and recorded in the history. It stops when
 * nothing is active.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val saving = HashSet<Long>()
    /**
     * Jobs whose save already ran, until the feed shows them out of "done":
     * the job list can still be the snapshot from before markSaved, and
     * saving it again found the file already moved and replaced "Download
     * complete" with "Could not save".
     */
    private val saveRan = HashSet<Long>()
    private val shown = HashSet<Long>() // ids with an ongoing notification
    private val reported = HashSet<Long>() // final notifications already posted
    /** Last progress notification per job: (time, content) — at most 1/s. */
    private val lastPosted = HashMap<Long, Pair<Long, String>>()
    private var foregroundId = 0
    private val startedAt = System.currentTimeMillis()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var nm: NotificationManager
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        nm = getSystemService(NotificationManager::class.java)
        createChannels()
        JobController.start(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, -1) ?: -1
        when (intent?.action) {
            ACTION_CANCEL -> JobController.cancel(id)
            ACTION_PAUSE -> JobController.pause(id)
        }
        // startForegroundService requires startForeground promptly.
        if (foregroundId == 0) promote(FG_PLACEHOLDER, placeholderNotification())
        acquireLocks()
        if (!watching) {
            watching = true
            scope.launch { JobController.jobs.collect { onJobs(it) } }
        }
        return START_NOT_STICKY
    }

    private fun onJobs(jobs: List<Job>) {
        if (!JobController.loaded.value) return
        val seen = HashSet<Long>(jobs.size)
        var oldestActive: Job? = null
        for (j in jobs) {
            seen += j.id
            if (j.state != "done") saveRan -= j.id
            if (j.needsService) oldestActive = j // list is newest first
            handle(j)
        }
        // Jobs removed from the queue (deleted in the app).
        for (id in shown.filter { it !in seen }) {
            nm.cancel(notifId(id))
            shown -= id
        }
        if (oldestActive != null) {
            updateForeground(oldestActive)
        } else if (saving.isEmpty()) {
            stopIdle()
        }
    }

    private fun handle(j: Job) {
        val title = j.title?.ifEmpty { null } ?: "Download"
        val fresh = j.updated >= startedAt // don't re-announce old history
        when (j.state) {
            "running", "queued", "waiting" -> {
                val n = progressContent(j)
                val now = SystemClock.elapsedRealtime()
                val last = lastPosted[j.id]
                // Throttled to 1/s unless the state/phase line changed.
                if (last == null || (last.second != n && (now - last.first >= 1000 || last.second.substringBefore('|') != n.substringBefore('|')))) {
                    lastPosted[j.id] = now to n
                    val notification = progressNotification(j, title)
                    if (foregroundId == notifId(j.id)) nm.notify(foregroundId, notification) else nm.notify(notifId(j.id), notification)
                }
                shown += j.id
            }
            "done" -> if (j.id !in saving && j.id !in saveRan) save(j, title)
            "paused", "canceled" -> {
                lastPosted -= j.id
                if (shown.remove(j.id)) nm.cancel(notifId(j.id))
            }
            // A failed save posts its own notification.
            "error", "expired" -> {
                lastPosted -= j.id
                if (fresh && j.id !in saving && !j.saveFailed && reported.add(j.id)) {
                    shown -= j.id
                    val headline = if (j.state == "expired") "Link expired" else "Download failed"
                    val text = if (j.state == "expired") "Open DownVid to continue (the link will be renewed)." else j.message.orEmpty()
                    nm.notify(notifId(j.id), resultNotification(title, headline, text, null))
                }
            }
        }
    }

    private fun save(j: Job, title: String) {
        val id = j.id
        saving += id
        lastPosted -= id
        nm.notify(notifId(id), savingNotification(title))
        shown += id
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val saved = MediaSaver.save(applicationContext, File(j.path.orEmpty()), title)
                    JobController.markSaved(id, SavedFile(saved.uri.toString(), saved.displayName, saved.location, saved.mime))
                    saved
                }.onFailure { e ->
                    Log.e(TAG, "service: save failed for job $id", e)
                    // Take the job out of "done": otherwise it would be saved
                    // again on every update and the service (and its wake
                    // lock) would never stop.
                    runCatching { JobController.saveFailed(id, e.message ?: "$e") }
                }
            }
            saving -= id
            saveRan += id
            shown -= id
            result.onSuccess { saved ->
                val text = saved.displayName + (j.warning?.let { " · $it" } ?: "")
                nm.notify(notifId(id), resultNotification(title, "Download complete", text, saved.uri, saved.mime))
            }.onFailure { e ->
                reported += id
                nm.notify(notifId(id), resultNotification(title, "Could not save", e.message ?: "$e", null))
            }
            // The core's change (markSaved / saveFailed) arrives through the
            // feed; re-check now in case it was the last job.
            onJobs(JobController.jobs.value)
        }
    }

    /** The foreground notification follows the oldest active job. */
    private fun updateForeground(j: Job) {
        if (foregroundId == notifId(j.id)) return
        if (j.state == "done") return // its "Saving…" notification is not ongoing-progress
        promote(notifId(j.id), progressNotification(j, j.title.orEmpty()))
        nm.cancel(FG_PLACEHOLDER)
    }

    private fun stopIdle() {
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
        for (j in JobController.jobs.value) if (j.state in setOf("queued", "running", "waiting")) JobController.pause(j.id)
        stopIdle()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        releaseLocks()
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
        .setSmallIcon(R.drawable.ic_stat_downvid)
        .setColor(BRAND)
        .setContentTitle("DownVid")
        .setContentText("Preparing downloads…")
        .setSilent(true)
        .build()

    /** "state line|details": the first part changing bypasses the throttle. */
    private fun progressContent(j: Job): String {
        val (text, _) = progressText(j)
        return "${j.state}/${j.phase}|$text|${j.percent.toInt()}"
    }

    private fun progressText(j: Job): Pair<String, Boolean> = when {
        j.state == "queued" -> "Queued" to true
        j.state == "waiting" -> "No connection — retrying…" to true
        j.phase == "muxing" -> "Converting to MP4…" to true
        j.phase == "converting" -> "Converting audio… ${j.percent.toInt()}%" to (j.percent <= 0.0)
        else -> bytesText(j) to (j.percent <= 0.0)
    }

    private fun progressNotification(j: Job, title: String): Notification {
        val (text, indeterminate) = progressText(j)
        val b = NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_downvid)
            .setColor(BRAND)
            .setContentTitle(title.ifEmpty { "Download" })
            .setContentText(text)
            .setSubText(if (j.percent > 0) "${j.percent.toInt()}%" else null)
            .setProgress(100, j.percent.toInt(), indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
        if (j.state == "running" || j.state == "waiting") b.addAction(0, "Pause", actionIntent(ACTION_PAUSE, j.id))
        b.addAction(0, "Cancel", actionIntent(ACTION_CANCEL, j.id))
        return b.build()
    }

    private fun savingNotification(title: String) = NotificationCompat.Builder(this, CH_PROGRESS)
        .setSmallIcon(R.drawable.ic_stat_downvid)
        .setColor(BRAND)
        .setContentTitle(title)
        .setContentText("Saving to gallery…")
        .setProgress(100, 0, true)
        .setOngoing(true)
        .setSilent(true)
        .build()

    private fun resultNotification(title: String, headline: String, text: String, uri: Uri?, mime: String = "video/mp4") =
        NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(if (uri != null) R.drawable.ic_stat_downvid else android.R.drawable.stat_notify_error)
            .setColor(BRAND)
            .setContentTitle(headline)
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$text"))
            .setAutoCancel(true)
            .setContentIntent(if (uri != null) openMediaIntent(uri, mime) else openAppIntent())
            .build()

    private fun bytesText(j: Job): String {
        val parts = mutableListOf<String>()
        parts += if (j.total > 0) {
            "${formatBytes(j.bytes)} / ${if (j.totalEstimate) "~" else ""}${formatBytes(j.total)}"
        } else {
            formatBytes(j.bytes)
        }
        if (j.speedBps > 0) parts += "${formatBytes(j.speedBps.toLong())}/s"
        if (j.segmentsTotal > 0) parts += "seg ${j.segmentsDone}/${j.segmentsTotal}"
        return parts.joinToString(" · ")
    }

    private fun actionIntent(action: String, id: Long): PendingIntent = PendingIntent.getService(
        this, (action.hashCode() * 31 + id).toInt(),
        Intent(this, DownloadService::class.java).setAction(action).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_DOWNLOADS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
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
        private const val WAKE_TIMEOUT_MS = 3 * 60 * 60 * 1000L
        private const val FG_PLACEHOLDER = 999
        private const val BRAND = 0xFF7B5CFF.toInt()

        @Volatile var running = false
            private set

        private fun notifId(id: Long) = 1000 + id.toInt()

        /** Makes sure the service is running (it picks up every active job). */
        fun ensureRunning(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, DownloadService::class.java).setAction(ACTION_ENSURE),
                )
            } catch (e: IllegalStateException) {
                // Android 12+: no foreground service starts from the background.
                // The next time the app is opened the queue starts it again.
                Log.w(TAG, "service: cannot start from background: ${e.message}")
            }
        }
    }
}
