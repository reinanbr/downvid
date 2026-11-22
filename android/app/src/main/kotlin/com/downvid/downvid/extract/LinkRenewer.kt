package com.downvid.downvid.extract

import android.app.Activity
import android.util.Log
import com.downvid.downvid.core.Job
import com.downvid.downvid.core.JobController

/**
 * Signed CDN links (YouTube, Instagram, X...) expire after a few hours. When
 * a job reports "expired", the original link is extracted again, the same
 * choice (optionKey: quality/format/item) is located and its fresh URLs are
 * handed to the Go job, which continues the partial download.
 */
object LinkRenewer {
    private const val TAG = "DownVid"
    private val running = HashSet<Long>()

    /** Jobs renewed automatically once per process (the Downloads screen). */
    private val autoTried = HashSet<Long>()

    fun shouldAutoRenew(id: Long) = autoTried.add(id)

    fun isRenewing(id: Long) = id in running

    /** Main thread. Returns whether the job got new sources. */
    suspend fun renew(activity: Activity, job: Job): Boolean {
        val id = job.id
        val pageUrl = job.pageUrl ?: return false
        val key = job.optionKey ?: return false
        if (!running.add(id)) return false
        Log.i(TAG, "renew: job $id from $pageUrl")
        val link = LinkParser.parse(pageUrl)
        if (link == null) {
            running -= id
            return false
        }
        val x = Extractor(activity, link)
        try {
            x.start()
            x.settled()
            val o = x.allOptions.firstOrNull { it.optionKey == key }
            if (o == null) {
                Log.i(TAG, "renew: job $id: option not found among ${x.allOptions.size} (${x.state.value.ytdlpError ?: "no error"})")
                return false
            }
            JobController.replace(id, o, x.headersFor(o))
            Log.i(TAG, "renew: job $id renewed")
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "renew: job $id failed", e)
            return false
        } finally {
            x.dispose()
            running -= id
        }
    }
}
