package com.downvid.downvid

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile "Paste & download". Android 10+ forbids reading the
 * clipboard in the background, so the tile only opens the translucent
 * ShareActivity in paste mode; it reads the link once it has focus and shows
 * the download sheet over whatever is on screen.
 */
class PasteTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_paste_label)
            updateTile()
        }
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { open() } else open()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open() {
        val intent = Intent(this, ShareActivity::class.java)
            .setAction(ShareActivity.ACTION_PASTE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
