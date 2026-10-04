package com.downvid.downvid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * After an app update, unpacks Python/yt-dlp/ffmpeg in the background (~6 s)
 * so the first share after the update does not pay for it.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        Thread {
            try {
                Log.i("DownVid", "update: pre-initializing yt-dlp")
                YtDlp.warmUp(context)
                YtDlp.awaitReady(9, TimeUnit.SECONDS)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
