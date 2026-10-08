package com.walkbuddy.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.walkbuddy.MainActivity
import com.walkbuddy.R
import com.walkbuddy.WalkBuddyApplication
import com.walkbuddy.session.Phase
import com.walkbuddy.session.WalkService

/**
 * Quick Settings tile. Tapping while idle opens the app ready to start a solo walk (a location walk can only be
 * started from the foreground); tapping while a walk is running ends it.
 */
class WalkTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    private fun walking(): Boolean {
        val session = (application as WalkBuddyApplication).container.session
        val p = session.ui.value.phase
        return p == Phase.Walking || p == Phase.Lobby
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = walking()
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = getString(if (on) R.string.tile_stop else R.string.tile_start)
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        if (walking()) {
            startService(Intent(this, WalkService::class.java).setAction(WalkService.ACTION_STOP))
            refresh()
            return
        }
        val open = Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_START_SOLO).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 5, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            startActivityAndCollapse(open)
        }
    }
}
