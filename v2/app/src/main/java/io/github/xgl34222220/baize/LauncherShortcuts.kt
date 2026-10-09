package io.github.xgl34222220.baize

import android.content.Context
import android.content.Intent

/** Entry points shared by launcher shortcuts and the Quick Settings tile. */
internal enum class LauncherShortcut(val id: String) {
    SCAN("scan"),
    LARGE_FILES("large_files"),
    STORAGE_ANALYSIS("storage_analysis"),
    FILE_TRASH("file_trash");

    companion object {
        fun fromId(id: String?): LauncherShortcut? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Shortcuts only ask the dashboard to navigate; every destination keeps its own busy checks.
 * The request is consumed once, so recreation or a launch from Recents cannot reopen a tool.
 */
internal object LauncherShortcuts {
    const val ACTION = "io.github.xgl34222220.baize.action.SHORTCUT"
    const val EXTRA_SHORTCUT = "io.github.xgl34222220.baize.SHORTCUT"

    fun intent(context: Context, shortcut: LauncherShortcut): Intent =
        Intent(context, MiuixDashboardActivity::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA_SHORTCUT, shortcut.id)

    fun consume(intent: Intent?, restored: Boolean = false): LauncherShortcut? {
        if (intent == null || !intent.hasExtra(EXTRA_SHORTCUT)) return null
        val requested = LauncherShortcut.fromId(intent.getStringExtra(EXTRA_SHORTCUT))
        intent.removeExtra(EXTRA_SHORTCUT)
        val fromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        return requested.takeUnless { restored || fromHistory }
    }
}
