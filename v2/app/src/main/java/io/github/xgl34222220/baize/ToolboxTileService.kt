package io.github.xgl34222220.baize

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class ToolboxTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply { label = "白泽功能控制台"; state = Tile.STATE_ACTIVE; updateTile() }
    }
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ToolboxActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 303, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
