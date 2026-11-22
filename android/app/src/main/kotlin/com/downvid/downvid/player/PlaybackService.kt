package com.downvid.downvid.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Media session around PlayerHub's player: system media controls
 * (notification, lock screen, headset, PiP buttons) and playback that goes
 * on with the screen off or in another app ("Background playback").
 * Started by PlayerActivity; media3 promotes it to the foreground while
 * playing.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, PlayerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, PlayerHub.player(this)).setId("downvid").setSessionActivity(open).build()
            .also { addSession(it) }
        Log.i(TAG, "playback: session up")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Nothing to resume after the process dies: the page session is gone.
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaybackOngoing) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        // The activity is gone too (it stops this service when it closes).
        if (!PlayerActivity.visible && !PlayerHub.inPage) PlayerHub.release(this)
        Log.i(TAG, "playback: session down")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DownVid"

        fun start(context: Context) {
            runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
                .onFailure { Log.w(TAG, "playback: cannot start the service: ${it.message}") }
        }

        fun stop(context: Context) = context.stopService(Intent(context, PlaybackService::class.java))
    }
}
