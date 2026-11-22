package com.downvid.downvid

import android.app.Application
import com.downvid.downvid.core.GoCore
import com.downvid.downvid.core.Settings
import com.downvid.downvid.ui.ImageCache

/**
 * Process setup that every entry point (activities, DownloadService, the
 * tile) needs. Kept cheap: the Go core and yt-dlp load lazily, off the main
 * thread, when something first uses them.
 */
class DownVidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        GoCore.attach(this)
        ImageCache.init(this)
    }
}
