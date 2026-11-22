package com.downvid.downvid.core

import android.content.Context
import android.util.Log
import com.downvid.downvid.DownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The single owner of the download queue on the app side.
 *
 * Every mutation (start, pause, resume, retry, save again, cancel, remove,
 * replace) goes through here, and every one that can leave a job active
 * starts [DownloadService], which publishes finished files. Before this, UI
 * code resumed jobs in Go directly and nothing saved them (commit 9a9fa64).
 *
 * [jobs] is fed by one long-poll loop over the core's change feed
 * (jobs.Await): it runs only while someone collects (UI or the service),
 * receives only the jobs that changed, and blocks in Go when nothing moves,
 * so an idle queue costs no wakeups.
 */
object JobController {
    private const val TAG = "DownVid"
    private const val AWAIT_MS = 30_000L
    /** Coalesces progress of several jobs into one UI update. */
    private const val MIN_INTERVAL_MS = 250L

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    private val _loaded = MutableStateFlow(false)
    @Volatile private var version = 0L
    @Volatile private var started = false

    /** Every job, newest first. */
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    /** The first job list arrived (the UI can tell "empty" from "loading"). */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /**
     * Idempotent: loads the queue (resuming interrupted downloads) and starts
     * the change feed. Called by the activities and the service.
     */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            val initial = try {
                GoCore.ensureInit()
            } catch (e: Exception) {
                Log.e(TAG, "core init failed", e)
                null
            }
            if (initial != null && version == 0L) {
                publish(initial)
            }
        }
        scope.launch {
            _jobs.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { watching ->
                if (watching) feed()
            }
        }
    }

    private suspend fun feed() {
        while (scope.isActive) {
            val changes = try {
                // Not cancellable mid-call: when nobody watches any more, the
                // result of the last (≤30 s) wait is discarded by withContext.
                withContext(Dispatchers.IO) { GoCore.awaitBlocking(version, AWAIT_MS) }
            } catch (e: GoError) {
                Log.e(TAG, "job feed: ${e.message}")
                delay(2000)
                continue
            }
            apply(changes)
            delay(MIN_INTERVAL_MS)
        }
    }

    private fun apply(ch: JobChanges) {
        if (ch.version == version && !ch.full) return
        val list = if (ch.full) {
            ch.jobs
        } else {
            val byId = LinkedHashMap<Long, Job>(_jobs.value.size + ch.jobs.size)
            for (j in _jobs.value) byId[j.id] = j
            for (id in ch.removed) byId.remove(id)
            for (j in ch.jobs) byId[j.id] = j
            byId.values.sortedByDescending { it.id }
        }
        version = ch.version
        publish(list)
    }

    private fun publish(list: List<Job>) {
        _jobs.value = list
        _loaded.value = true
        // Transitions inside the core (waiting -> queued, a resumed queue)
        // also need the service.
        if (!DownloadService.running && list.any { it.needsService }) DownloadService.ensureRunning(app)
    }

    fun job(id: Long): Job? = _jobs.value.firstOrNull { it.id == id }

    // ------------------------------------------------------------ mutations

    /** Queues a download; returns its id. */
    suspend fun download(req: DownloadRequest): Long {
        val id = GoCore.download(req)
        Log.i(TAG, "job $id queued: ${req.kind} ${req.meta.title}")
        DownloadService.ensureRunning(app)
        return id
    }

    private fun mutate(what: String, id: Long, needsService: Boolean, block: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                block()
                Log.i(TAG, "job $id: $what")
                if (needsService) DownloadService.ensureRunning(app)
            } catch (e: Exception) {
                Log.e(TAG, "job $id: $what failed", e)
            }
        }
    }

    fun pause(id: Long) = mutate("pause", id, false) { GoCore.pause(id) }

    /** Resumes a paused job, retries a failed one, or saves again a file whose save failed. */
    fun resume(id: Long) = mutate("resume", id, true) { GoCore.resume(id) }

    fun cancel(id: Long) = mutate("cancel", id, false) { GoCore.cancel(id) }

    fun remove(id: Long) = mutate("remove", id, false) { GoCore.remove(id) }

    /** New sources for an expired job (renewed link); it is queued again. */
    fun replace(id: Long, option: MediaOption, headers: Headers) =
        mutate("replace", id, true) { GoCore.replace(id, option.url, option.audioUrl, headers, option.chunkSize) }

    /** Called by DownloadService once the file is published (or failed). */
    fun markSaved(id: Long, saved: SavedFile) = GoCore.markSaved(id, saved)

    fun saveFailed(id: Long, message: String) = GoCore.saveFailed(id, message)
}
