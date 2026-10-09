package io.github.xgl34222220.baize

import android.app.Application
import android.content.Intent
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LauncherShortcutsTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test fun requestIsConsumedOnlyOnce() {
        val intent = LauncherShortcuts.intent(context, LauncherShortcut.LARGE_FILES)
        assertEquals(MiuixDashboardActivity::class.java.name, intent.component!!.className)
        assertEquals(LauncherShortcut.LARGE_FILES, LauncherShortcuts.consume(intent))
        assertFalse(intent.hasExtra(LauncherShortcuts.EXTRA_SHORTCUT))
        assertNull(LauncherShortcuts.consume(intent))
    }

    @Test fun recreationAndRecentsNeverReopenATool() {
        val restored = LauncherShortcuts.intent(context, LauncherShortcut.SCAN)
        assertNull(LauncherShortcuts.consume(restored, restored = true))
        assertFalse(restored.hasExtra(LauncherShortcuts.EXTRA_SHORTCUT))
        val fromHistory = LauncherShortcuts.intent(context, LauncherShortcut.SCAN)
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(LauncherShortcuts.consume(fromHistory))
    }

    @Test fun unknownOrMissingRequestIsIgnored() {
        assertNull(LauncherShortcuts.consume(null))
        assertNull(LauncherShortcuts.consume(Intent()))
        assertNull(LauncherShortcuts.consume(Intent().putExtra(LauncherShortcuts.EXTRA_SHORTCUT, "rm -rf")))
    }

    @Test fun staticShortcutsRouteThroughTheDashboard() {
        val xml = listOf(File("src/main/res/xml/shortcuts.xml"), File("app/src/main/res/xml/shortcuts.xml"))
            .first { it.isFile }.readText()
        val ids = Regex("android:shortcutId=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toList()
        val extras = Regex("android:name=\"${Regex.escape(LauncherShortcuts.EXTRA_SHORTCUT)}\" android:value=\"([^\"]+)\"")
            .findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(LauncherShortcut.entries.map { it.id }, ids)
        assertEquals(ids, extras)
        assertTrue(xml.contains("android:action=\"${LauncherShortcuts.ACTION}\""))
        assertEquals(ids.size, Regex("android:targetClass=\"${Regex.escape(MiuixDashboardActivity::class.java.name)}\"")
            .findAll(xml).count())
    }
}
