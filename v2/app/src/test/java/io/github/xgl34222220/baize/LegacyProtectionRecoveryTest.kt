package io.github.xgl34222220.baize

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.xgl34222220.baize.ui.appearance.AppearanceCopyMigration
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LegacyProtectionRecoveryTest {
    @get:Rule val folder = TemporaryFolder()
    private val jobs = mutableListOf<kotlinx.coroutines.Job>()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val packageKey = stringSetPreferencesKey("package_whitelist")
    private val pathKey = stringSetPreferencesKey("path_whitelist")
    private val themeKey = stringPreferencesKey("theme_mode")
    private val unknownKey = stringPreferencesKey("unknown_module_setting")
    private val packages = setOf("keep.app")
    private val paths = setOf("/storage/emulated/0/Keep")

    @After fun stopStores() = runBlocking {
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
    }

    private fun prefs() = context.getSharedPreferences("recovery-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    private fun routed(prefs: SharedPreferences): Context = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "baize_v2") prefs else super.getSharedPreferences(name, mode)
    }
    private fun store(file: File = File(folder.root, "${UUID.randomUUID()}.preferences_pb"),
                      migrations: List<androidx.datastore.core.DataMigration<Preferences>> = emptyList()): DataStore<Preferences> {
        val job = SupervisorJob().also { jobs += it }
        return PreferenceDataStoreFactory.create(migrations = migrations,
            scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
    }
    private suspend fun seed(store: DataStore<Preferences>) {
        store.edit { it[packageKey] = packages; it[pathKey] = paths; it[unknownKey] = "preserve" }
    }

    @Test fun reproducesOldAllKeyMigrationAndKeepsOrphansPendingInsteadOfAutoRestoring() = runBlocking {
        val local = prefs()
        local.edit().putString("theme_mode", "dark").putStringSet("package_whitelist", packages)
            .putStringSet("path_whitelist", paths).putString("unknown_module_setting", "preserve").commit()
        val old = store(migrations = listOf(SharedPreferencesMigration(routed(local), "baize_v2")))
        val moved = old.data.first()
        assertEquals(paths, moved[pathKey])
        assertEquals("preserve", moved[unknownKey])
        assertFalse(local.contains("path_whitelist"))
        assertFalse(local.contains("unknown_module_setting"))
        val snapshot = LegacyProtectionRecovery.Engine(old, local).read()
        assertTrue(snapshot.pending)
        assertEquals(paths, snapshot.pendingPaths)
        assertTrue(snapshot.currentPaths.isEmpty())
        assertFalse(local.contains("path_whitelist"))
    }

    @Test fun repeatedOldMigrationCanEraseNewerSourceWithoutUpdatingDestination() = runBlocking {
        val local = prefs()
        val oldFile = File(folder.root, "repeat.preferences_pb")
        local.edit().putStringSet("path_whitelist", paths).commit()
        val first = store(oldFile, listOf(SharedPreferencesMigration(routed(local), "baize_v2")))
        assertEquals(paths, first.data.first()[pathKey])
        jobs.last().cancel(); jobs.last().join()
        local.edit().putStringSet("path_whitelist", setOf("/storage/emulated/0/Newer")).commit()
        val second = store(oldFile, listOf(SharedPreferencesMigration(routed(local), "baize_v2")))
        assertEquals(paths, second.data.first()[pathKey])
        assertFalse(local.contains("path_whitelist"))
        assertTrue(LegacyProtectionRecovery.Engine(second, local).read().pending)
    }

    @Test fun replacementCopiesOnlyAppearanceAndNeverCleansOrOverwritesSourceOrDestination() = runBlocking {
        val name = "appearance-checked-${UUID.randomUUID()}"
        val local = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        local.edit().putString("theme_mode", "dark").putBoolean("theme_blur", false)
            .putStringSet("package_whitelist", packages).putStringSet("path_whitelist", paths)
            .putString("unknown_module_setting", "source-original").commit()
        val replacement = store(migrations = listOf(AppearanceCopyMigration(context, name)))
        val result = replacement.data.first()
        assertEquals("dark", result[themeKey])
        assertFalse(result.asMap().keys.any { it.name == "path_whitelist" || it.name == "package_whitelist" || it.name == "unknown_module_setting" })
        assertEquals(paths, local.getStringSet("path_whitelist", null))
        assertEquals("source-original", local.getString("unknown_module_setting", null))
        assertEquals("dark", local.getString("theme_mode", null))
        val migration = AppearanceCopyMigration(context, name)
        replacement.edit { it[themeKey] = "light"; it[unknownKey] = "destination-original" }
        val repeat = migration.migrate(replacement.data.first())
        migration.cleanUp()
        assertEquals("light", repeat[themeKey])
        assertEquals("destination-original", repeat[unknownKey])
        assertEquals("dark", local.getString("theme_mode", null))
    }

    @Test fun explicitCurrentEmptyAndNewerFieldsAlwaysWinOverHistoricalCopies() = runBlocking {
        val data = store(); seed(data)
        val local = prefs()
        local.edit().putStringSet("package_whitelist", emptySet()).putStringSet("path_whitelist", setOf("/Newer")).commit()
        val state = LegacyProtectionRecovery.Engine(data, local).read()
        assertFalse(state.pending)
        assertTrue(state.currentPackages.isEmpty())
        assertEquals(setOf("/Newer"), state.currentPaths)
        assertEquals(paths, data.data.first()[pathKey])
    }

    @Test fun absentEmptyHistoricalFieldsStillRequireAcknowledgement() = runBlocking {
        val data = store(); data.edit { it[pathKey] = emptySet() }
        val local = prefs(); val engine = LegacyProtectionRecovery.Engine(data, local)
        val state = engine.read()
        assertTrue(state.pending)
        assertTrue(state.pathReviewRequired)
        assertTrue(state.pendingPaths.isEmpty())
        engine.resolve(state, emptySet(), emptySet())
        assertTrue(local.contains("path_whitelist"))
        assertFalse(engine.read().pending)
    }

    @Test fun recoveryCommitsAndReadbacksThroughTheRealDiskIntegrityCallback() = runBlocking {
        val data = store(); seed(data)
        val name = "recovery-checked-${UUID.randomUUID()}"
        val local = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val engine = LegacyProtectionRecovery.Engine(data, local) { CheckedLegacyPreferences.read(context, name) }
        val resolved = engine.resolve(engine.read(), packages, emptySet())
        assertFalse(resolved.pending)
        val disk = CheckedLegacyPreferences.read(context, name)
        assertEquals(packages, disk["package_whitelist"])
        assertEquals(emptySet<String>(), disk["path_whitelist"])
        assertFalse(engine.read().pending)
        assertEquals(paths, data.data.first()[pathKey])
        assertEquals("preserve", data.data.first()[unknownKey])
    }

    @Test fun explicitSelectionPreservesArchiveUnknownValuesAndLaterRemovalDoesNotResurrect() = runBlocking {
        val data = store(); seed(data)
        val local = prefs(); val engine = LegacyProtectionRecovery.Engine(data, local)
        val resolved = engine.resolve(engine.read(), packages, emptySet())
        assertFalse(resolved.pending)
        assertEquals(packages, resolved.currentPackages)
        assertTrue(resolved.currentPaths.isEmpty())
        assertEquals(paths, data.data.first()[pathKey])
        assertEquals("preserve", data.data.first()[unknownKey])
        local.edit().putStringSet("package_whitelist", emptySet()).commit()
        assertTrue(engine.read().currentPackages.isEmpty())
        assertFalse(engine.read().pending)
        // Even a later absence cannot resurrect this exact reviewed archive.
        local.edit().remove("package_whitelist").remove("path_whitelist").commit()
        assertFalse(engine.read().pending)
    }

    @Test fun newerLocalEditsAndUnknownDataStoreEditsInvalidateReviewSnapshot() = runBlocking {
        val data = store(); seed(data)
        val local = prefs(); val engine = LegacyProtectionRecovery.Engine(data, local)
        val stale = engine.read()
        local.edit().putStringSet("path_whitelist", emptySet()).commit()
        assertTrue(runCatching { engine.resolve(stale, packages, paths) }.isFailure)
        assertEquals(emptySet<String>(), local.getStringSet("path_whitelist", null))
        val next = engine.read()
        data.edit { it[unknownKey] = "changed" }
        assertTrue(runCatching { engine.resolve(next, packages, emptySet()) }.isFailure)
        assertFalse(local.contains("package_whitelist"))
    }

    @Test fun failedCommitKeepsDurablePendingGuardAndRestoresExactAbsence() = runBlocking {
        val data = store(); seed(data)
        val local = prefs()
        val failing = object : SharedPreferences by local {
            override fun edit(): SharedPreferences.Editor {
                val editor = local.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun commit(): Boolean { editor.commit(); return false }
                }
            }
        }
        val engine = LegacyProtectionRecovery.Engine(data, failing)
        assertTrue(runCatching { engine.resolve(engine.read(), packages, paths) }.isFailure)
        assertFalse(local.contains("package_whitelist"))
        assertFalse(local.contains("path_whitelist"))
        assertTrue(LegacyProtectionRecovery.Engine(data, local).read().pending)
        assertEquals(paths, data.data.first()[pathKey])
        val retry = LegacyProtectionRecovery.Engine(data, local)
        assertFalse(retry.resolve(retry.read(), packages, paths).pending)
    }

    @Test fun editAfterJournalNeverGetsRolledBackOverNewerLocalProtection() = runBlocking {
        val data = store(); seed(data)
        val local = prefs()
        var injected = false
        val racing = object : DataStore<Preferences> by data {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                val result = data.updateData(transform)
                if (!injected) {
                    injected = true
                    local.edit().putStringSet("path_whitelist", setOf("/Newer")).commit()
                }
                return result
            }
        }
        val engine = LegacyProtectionRecovery.Engine(racing, local)
        assertTrue(runCatching { engine.resolve(engine.read(), packages, paths) }.isFailure)
        assertEquals(setOf("/Newer"), local.getStringSet("path_whitelist", null))
        assertTrue(LegacyProtectionRecovery.Engine(data, local).read().pending)
    }

    @Test fun reviewedReceiptDoesNotAuthorizeADifferentHistoricalRecordOrStaleReplay() = runBlocking {
        val data = store(); seed(data)
        val local = prefs(); val engine = LegacyProtectionRecovery.Engine(data, local)
        val original = engine.read()
        engine.resolve(original, emptySet(), emptySet())
        assertTrue(runCatching { engine.resolve(original, packages, paths) }.isFailure)
        local.edit().remove("path_whitelist").commit()
        data.edit { it[pathKey] = setOf("/Different") }
        assertTrue(engine.read().pathReviewRequired)
        assertEquals(setOf("/Different"), engine.read().pendingPaths)
        assertFalse(local.contains("path_whitelist"))
    }

    @Test fun failedFinalJournalWriteCannotAuthorizeCleanupAndRetryPreservesNewerLocalEdit() = runBlocking {
        val data = store(); seed(data)
        val local = prefs()
        var writes = 0
        val failing = object : DataStore<Preferences> by data {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                writes++
                if (writes == 2) throw IOException("synthetic final write failure")
                return data.updateData(transform)
            }
        }
        val engine = LegacyProtectionRecovery.Engine(failing, local)
        assertTrue(runCatching { engine.resolve(engine.read(), packages, paths) }.isFailure)
        val retry = LegacyProtectionRecovery.Engine(data, local)
        assertTrue(retry.read().pending)
        local.edit().putStringSet("path_whitelist", emptySet()).commit()
        val interrupted = retry.read()
        assertTrue(interrupted.pathReviewRequired)
        assertTrue(interrupted.pendingPaths.isEmpty())
        assertFalse(retry.resolve(interrupted, emptySet(), emptySet()).pending)
        assertEquals(emptySet<String>(), local.getStringSet("path_whitelist", null))
    }

    @Test fun unknownOnlyDataIsRetainedWithoutInventingProtectionCandidates() = runBlocking {
        val data = store(); data.edit { it[unknownKey] = "preserve" }
        assertFalse(LegacyProtectionRecovery.Engine(data, prefs()).read().pending)
        assertEquals("preserve", data.data.first()[unknownKey])
    }

    @Test fun corruptStoreAndWrongProtectionTypeNeverBecomeEmptyKnownRules() = runBlocking {
        val file = File(folder.root, "corrupt.preferences_pb").apply { writeBytes(byteArrayOf(0x7f, 0x7f, 0x7f)) }
        assertTrue(runCatching { LegacyProtectionRecovery.Engine(store(file), prefs()).read() }.isFailure)
        val wrongType = store()
        wrongType.edit { it[stringPreferencesKey("path_whitelist")] = "not a string set" }
        assertTrue(runCatching { LegacyProtectionRecovery.Engine(wrongType, prefs()).read() }.isFailure)
        val invalid = store(); invalid.edit { it[pathKey] = setOf("/Keep/../Private") }
        assertTrue(runCatching { LegacyProtectionRecovery.Engine(invalid, prefs()).read() }.isFailure)
    }

    @Test fun freshAbsenceIsSafeButPreviouslyObservedMissingStoreBlocksProtection() {
        val root = folder.newFolder("fresh")
        val guard = prefs()
        val local = prefs()
        val wrapped = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getDataDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = when (name) {
                "legacy-protection-recovery-v1" -> guard
                "baize_v2" -> local
                else -> super.getSharedPreferences(name, mode)
            }
        }
        assertFalse(LegacyProtectionRecovery.read(wrapped).pending)
        guard.edit().putBoolean("observed", true).commit()
        File(root, "shared_prefs/legacy-protection-recovery-v1.xml").apply {
            parentFile!!.mkdirs()
            writeText("<map><boolean name=\"observed\" value=\"true\" /></map>")
        }
        assertTrue(runCatching { LegacyProtectionRecovery.read(wrapped) }.isFailure)
        assertTrue(ApkProtectionStore.fallback(wrapped) is ApkProtectionState.Unknown)
        assertTrue(runCatching { ApkProtectionStore.removeLegacyRules(wrapped) }.isFailure)
    }
}
