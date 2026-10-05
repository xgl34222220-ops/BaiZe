package io.github.xgl34222220.baize

import android.app.Application
import android.content.Context
import io.github.xgl34222220.baize.ui.appearance.AppearanceCopyMigration
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

/** Seed real app files before actual BaiZeApplication.attachBaseContext/onCreate, not a helper-only read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [DirectorySyncLinuxShadow::class])
class LegacyPreferencesStartupTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
    private var originalOs: Any? = null

    @Before fun installFreshLinuxForThisShadowConfiguration() {
        // Robolectric initializes Libcore.os before applying a class's custom shadow mapping.
        // Reusing that Linux instance retains its original ShadowLinux association and causes
        // ClassCastException at the first custom method. Bind a fresh instance for this test only.
        DirectorySyncLinuxShadow.reset()
        val linux = Class.forName("libcore.io.Linux").getDeclaredConstructor().newInstance()
        assertTrue("Fresh Linux must bind the directory-aware shadow",
            Shadow.extract<Any>(linux) is DirectorySyncLinuxShadow)
        val guarded = Class.forName("libcore.io.BlockGuardOs")
            .getDeclaredConstructor(Class.forName("libcore.io.Os")).newInstance(linux)
        val field = Class.forName("libcore.io.Libcore").getDeclaredField("os").apply { isAccessible = true }
        originalOs = field.get(null)
        field.set(null, guarded)
    }

    @After fun restoreHandler() {
        Thread.setDefaultUncaughtExceptionHandler(oldHandler)
        try {
            assertEquals("Directory descriptors must close even when preservation fails", 0, DirectorySyncLinuxShadow.openDirectoryCount)
        } finally {
            DirectorySyncLinuxShadow.reset()
            originalOs?.let {
                Class.forName("libcore.io.Libcore").getDeclaredField("os").apply { isAccessible = true }.set(null, it)
            }
        }
    }
    private fun source(suffix: String = "") = File(context.dataDir, "shared_prefs/baize_v2.xml$suffix")
    private fun seed(bytes: ByteArray, suffix: String = "") = source(suffix).apply {
        parentFile!!.mkdirs(); writeBytes(bytes)
    }
    private fun startup(): BaiZeApplication {
        val app = BaiZeApplication()
        BaiZeApplication::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true
        }.invoke(app, context)
        app.onCreate()
        return app
    }
    private fun archive(name: String = "baize_v2.xml") = File(LegacyPreferencesAccess.quarantineDirectory(context), name)

    @Test fun corruptColdSourceSurvivesActualStartupQueuedUiWritesAndRelaunch() = runBlocking {
        val original = "<map><set name=\"path_whitelist\"><string>/Keep</string>".toByteArray()
        seed(original) // No getSharedPreferences call before actual application initialization.
        val first = startup()
        assertTrue(LegacyPreferencesAccess.isBlocked(first))
        assertArrayEquals(original, source().readBytes())
        assertArrayEquals(original, archive().readBytes())
        ThemeManager.setMode(first, ThemeManager.MODE_DARK)
        LegacyPreferencesAccess.preferences(first).edit().putString("plan_metadata", "queued").apply()
        assertFalse(LegacyPreferencesAccess.preferences(first).edit().putString("last_report_text", "later").commit())
        assertFalse(AppearanceCopyMigration(first).shouldMigrate(emptyPreferences()))
        assertTrue(runCatching { ApkProtectionStore.legacyRules(first) }.isFailure)
        assertArrayEquals(original, source().readBytes())
        val relaunched = startup()
        assertTrue(runCatching { LegacyProtectionRecovery.read(relaunched) }.isFailure)
        assertArrayEquals(original, source().readBytes())
        assertArrayEquals(original, archive().readBytes())
    }

    @Test fun sanitizedCanonicalFileCannotHideDurableQuarantineOnRelaunch() {
        val original = "<map><broken".toByteArray()
        seed(original); startup()
        source().writeText("<map />") // Simulates a legacy/external writer, not an authorized repair.
        val relaunched = startup()
        assertTrue(LegacyPreferencesAccess.isBlocked(relaunched))
        assertTrue(runCatching { ApkProtectionStore.legacyRules(relaunched) }.isFailure)
        assertArrayEquals(original, archive().readBytes())
    }

    @Test fun coldBackupAndTemporaryBytesArePreservedBeforeAndroidCanRestoreThem() {
        val primary = "<map />".toByteArray()
        val backup = "<map><set name=\"path_whitelist\"><string>/Keep</string></set></map>".toByteArray()
        val temporary = byteArrayOf(0, 1, 2, 3, 127)
        seed(primary); seed(backup, ".bak"); seed(temporary, ".tmp")
        startup()
        assertArrayEquals(primary, source().readBytes())
        assertArrayEquals(backup, source(".bak").readBytes())
        assertArrayEquals(temporary, source(".tmp").readBytes())
        assertArrayEquals(primary, archive().readBytes())
        assertArrayEquals(backup, archive("baize_v2.xml.bak").readBytes())
        assertArrayEquals(temporary, archive("baize_v2.xml.tmp").readBytes())
    }

    @Test fun editorCapturedBeforeCorruptionCannotCommitOrApplyOverEvidence() {
        val prefs = LegacyPreferencesAccess.preferences(context)
        assertTrue(prefs.edit().putStringSet("path_whitelist", mutableSetOf("/Keep")).commit())
        val commitEditor = prefs.edit().putString("theme_mode", "dark")
        val applyEditor = prefs.edit().putString("plan_metadata", "later")
        val original = "<map><broken".toByteArray()
        source().writeBytes(original)
        assertFalse(commitEditor.commit())
        applyEditor.apply()
        assertArrayEquals(original, source().readBytes())
        assertArrayEquals(original, archive().readBytes())
        assertTrue(runCatching { LegacyProtectionRecovery.read(context) }.isFailure)
    }

    @Test fun incompleteArchiveAndArchiveVerificationFailureStopActualStartup() {
        val original = "<map><broken".toByteArray()
        seed(original)
        LegacyPreferencesAccess.quarantineDirectory(context).mkdirs()
        assertTrue(runCatching { startup() }.isFailure)
        assertArrayEquals(original, source().readBytes())
    }

    @Test fun completedArchiveCannotBeTrustedAfterItsEvidenceIsChanged() {
        seed("<map><broken".toByteArray()); startup()
        archive().writeText("tampered")
        assertTrue(runCatching { startup() }.isFailure)
        assertTrue(runCatching { LegacyProtectionRecovery.read(context) }.isFailure)
    }

    @Test fun unarchivableSourceHaltsBeforeThemeOrAnyOrdinaryWriterCanStart() {
        source().mkdirs() // A nonregular source cannot be safely copied as a preferences file.
        assertTrue(runCatching { startup() }.isFailure)
        assertTrue(source().isDirectory)
        assertFalse(File(LegacyPreferencesAccess.quarantineDirectory(context), "manifest.json").exists())
        assertTrue(runCatching { startup() }.isFailure)
        assertTrue(source().isDirectory)
    }

    @Test fun validStartupStillMigratesThemeAndPreservesProtectionAndUnknownFields() {
        seed(("<map><string name=\"theme_mode\">dark</string>" +
            "<set name=\"path_whitelist\"><string>/Keep</string></set>" +
            "<string name=\"unknown_setting\">keep me</string></map>").toByteArray())
        val app = startup()
        assertFalse(LegacyPreferencesAccess.isBlocked(app))
        val prefs = LegacyPreferencesAccess.preferences(app)
        assertTrue(prefs.getBoolean("theme_alpha17_migrated", false))
        assertEquals("dark", ThemeManager.currentMode(app))
        assertEquals(setOf("/Keep"), ApkProtectionStore.legacyRules(app).paths)
        assertEquals("keep me", prefs.getString("unknown_setting", null))
        assertTrue(prefs.edit().putString("theme_mode", "light").commit())
        assertEquals("light", ThemeManager.currentMode(app))
    }

    @Test fun temporaryPendingWriteDoesNotFreezeALazyAccessorAtDisplayDefaults() {
        val original = "<map><string name=\"theme_mode\">dark</string></map>".toByteArray()
        seed(original)
        val prefs = LegacyPreferencesAccess.preferences(context)
        seed(original, ".bak")
        assertEquals("system", prefs.getString("theme_mode", "system"))
        assertFalse(LegacyPreferencesAccess.isBlocked(context))
        assertTrue(source(".bak").delete())
        assertEquals("dark", prefs.getString("theme_mode", "system"))
    }

    @Test fun stableGuardedReadHandleIsReusedButWritersStillRevalidateDisk() {
        seed("<map><string name=\"theme_mode\">dark</string></map>".toByteArray())
        val first = LegacyPreferencesAccess.preferences(context)
        assertEquals("dark", first.getString("theme_mode", null))
        assertSame(first, LegacyPreferencesAccess.preferences(context))
        val original = "<map><broken".toByteArray()
        source().writeBytes(original)
        assertFalse(first.edit().putString("theme_mode", "light").commit())
        assertArrayEquals(original, source().readBytes())
        assertTrue(LegacyPreferencesAccess.isBlocked(context))
    }
    @Test fun directoryOpenFailureHaltsStartupWithoutOverwritingOriginalEvidence() {
        val original = "<map><broken".toByteArray()
        seed(original)
        DirectorySyncLinuxShadow.failOpen = true
        val failure = runCatching { startup() }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(causes(failure!!).any { it is android.system.ErrnoException && it.message.orEmpty().startsWith("open failed") })
        assertArrayEquals(original, source().readBytes())
        assertTrue(LegacyPreferencesAccess.isBlocked(context))
        assertFalse(File(LegacyPreferencesAccess.quarantineDirectory(context), "manifest.json").exists())
        DirectorySyncLinuxShadow.failOpen = false
        assertTrue(runCatching { startup() }.isFailure)
    }

    @Test fun parentDirectoryFsyncFailureHaltsStartupAndClosesItsDescriptor() {
        val original = "<map><broken".toByteArray()
        seed(original)
        DirectorySyncLinuxShadow.failSyncAt = 1
        val failure = runCatching { startup() }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(causes(failure!!).any { it is android.system.ErrnoException && it.message.orEmpty().startsWith("fsync failed") })
        assertArrayEquals(original, source().readBytes())
        assertEquals(0, DirectorySyncLinuxShadow.openDirectoryCount)
        assertFalse(File(LegacyPreferencesAccess.quarantineDirectory(context), "manifest.json").exists())
        assertTrue(runCatching { LegacyProtectionRecovery.read(context) }.isFailure)
    }

    @Test fun finalDirectoryFsyncFailureStillStopsStartupAndKeepsExactArchive() {
        val original = "<map><broken".toByteArray()
        seed(original)
        DirectorySyncLinuxShadow.failSyncAt = 2
        val failure = runCatching { startup() }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(causes(failure!!).any { it is android.system.ErrnoException && it.message.orEmpty().startsWith("fsync failed") })
        assertArrayEquals(original, source().readBytes())
        assertArrayEquals(original, archive().readBytes())
        assertEquals(0, DirectorySyncLinuxShadow.openDirectoryCount)
        assertTrue(runCatching { LegacyProtectionRecovery.read(context) }.isFailure)
    }

    private fun causes(failure: Throwable): Sequence<Throwable> = generateSequence(failure) { it.cause }

}
