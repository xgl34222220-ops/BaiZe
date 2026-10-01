package io.github.xgl34222220.baize

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WhitelistManagerRepositoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val empty = ApkProtectionRules(emptySet(), emptySet())
    private val download = "/storage/emulated/0/Download"
    private val remote = FakeAccess()
    private fun repo() = WhitelistManagerRepository(context, remote, "/storage/emulated/0")
    @Before fun clear() {
        context.getSharedPreferences("apk-protection-v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("baize_v2", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun local(paths: Set<String> = emptySet(), packages: Set<String> = emptySet()) {
        context.getSharedPreferences("baize_v2", Context.MODE_PRIVATE).edit()
            .putStringSet("path_whitelist", paths).putStringSet("package_whitelist", packages).commit()
    }
    private inner class FakeAccess : WhitelistProtectionAccess {
        var rules = empty
        var failRead = false
        var failWrite = false
        var ignoreWrites = false
        var writes = 0
        override fun read(): ApkProtectionRules { check(!failRead) { "synthetic read failure" }; return rules }
        override fun updatePackages(added: Set<String>, removed: Set<String>) {
            writes++; check(!failWrite) { "synthetic write failure" }
            if (!ignoreWrites) rules = rules.copy(packages = rules.packages - removed + added)
        }
        override fun addPath(path: String) {
            writes++; check(!failWrite) { "synthetic write failure" }
            if (!ignoreWrites) rules = rules.copy(paths = rules.paths + path)
        }
        override fun removePath(path: String) {
            writes++; check(!failWrite) { "synthetic write failure" }
            if (!ignoreWrites) rules = rules.copy(paths = rules.paths - path)
        }
    }
    @Test fun effectiveLegacyRulesAreVisibleEvenWhenRootHasNoRules() {
        local(setOf(download), setOf("old.app"))
        val snapshot = repo().read()
        assertEquals(setOf(download), snapshot.effective.paths)
        assertEquals(setOf("old.app"), snapshot.effective.packages)
        assertEquals(setOf(download), snapshot.pathEntries.single().localRecords)
        assertEquals(empty, remote.rules)
        assertEquals(0, remote.writes)
    }
    @Test fun explicitLegacyRemovalStopsReappearingWithoutClearingOtherRecords() {
        local(setOf(download, "$download/Keep"), setOf("keep.app"))
        val after = repo().removePath(download)
        assertEquals(setOf("$download/Keep"), after.effective.paths)
        assertEquals(setOf("keep.app"), ApkProtectionStore.legacyRules(context).packages)
        assertEquals(0, remote.writes)
        assertEquals(after.effective, repo().read().effective)
    }
    @Test fun equivalentRootAndLocalPathsHaveOneRowAndOneExplicitRemoval() {
        remote.rules = empty.copy(paths = setOf("/data/media/0/Download", "$download/Keep"))
        local(setOf("/sdcard/Download", "/storage/emulated/0/Documents"))
        val row = repo().read().pathEntries.first { it.path == download }
        assertEquals(setOf("/data/media/0/Download"), row.rootRecords)
        assertEquals(setOf("/sdcard/Download"), row.localRecords)
        val after = repo().removePath(download)
        assertEquals(setOf("$download/Keep", "/storage/emulated/0/Documents"), after.effective.paths)
    }
    @Test fun removingChildDoesNotRemoveParentOrOtherUsers() {
        remote.rules = empty.copy(paths = setOf(download, "$download/Keep", "/storage/emulated/10/Download/Keep"))
        repo().removePath("$download/Keep")
        assertEquals(setOf(download, "/storage/emulated/10/Download/Keep"), remote.rules.paths)
    }
    @Test fun failedRootRemovalKeepsLegacyProtection() {
        remote.rules = empty.copy(paths = setOf(download))
        local(setOf(download)); remote.failWrite = true
        assertTrue(runCatching { repo().removePath(download) }.isFailure)
        assertEquals(setOf(download), ApkProtectionStore.legacyRules(context).paths)
        assertEquals(setOf(download), remote.rules.paths)
    }
    @Test fun unconfirmedRootRemovalKeepsLegacyProtection() {
        remote.rules = empty.copy(paths = setOf(download))
        local(setOf(download)); remote.ignoreWrites = true
        assertTrue(runCatching { repo().removePath(download) }.isFailure)
        assertEquals(setOf(download), ApkProtectionStore.legacyRules(context).paths)
    }
    @Test fun unreadableRootCannotAuthorizeLegacyRemovalOrPathAddition() {
        local(setOf(download)); remote.failRead = true
        assertTrue(runCatching { repo().removePath(download) }.isFailure)
        assertTrue(runCatching { repo().addPath("$download/New") }.isFailure)
        assertEquals(setOf(download), ApkProtectionStore.legacyRules(context).paths)
        assertEquals(0, remote.writes)
    }
    @Test fun malformedLegacyStoreCannotBeReplacedByEmptyRoot() {
        local(setOf("relative/path"))
        assertTrue(runCatching { repo().read() }.isFailure)
        assertTrue(runCatching { repo().addPath(download) }.isFailure)
        assertEquals(0, remote.writes)
        assertEquals(setOf("relative/path"), context.getSharedPreferences("baize_v2", 0).getStringSet("path_whitelist", emptySet()))
    }
    @Test fun typedPathNormalizesSeparatorsAndPreservesExistingRules() {
        remote.rules = empty.copy(paths = setOf("$download/Keep"))
        val result = repo().addPath(" /storage//emulated/0/Download/文件夹/ ")
        assertFalse(result.alreadyProtected)
        assertEquals(setOf("$download/Keep", "$download/文件夹"), result.snapshot.effective.paths)
    }
    @Test fun equivalentPathAdditionDoesNotDuplicateOrMigrateLegacyRecord() {
        local(setOf("/sdcard/Download"))
        val result = repo().addPath("/data/media/0/Download/")
        assertTrue(result.alreadyProtected)
        assertEquals(0, remote.writes)
        assertEquals(setOf("/sdcard/Download"), ApkProtectionStore.legacyRules(context).paths)
        assertEquals(empty, remote.rules)
    }
    @Test fun ownerAliasNeverDeduplicatesAnotherUsersDirectory() {
        remote.rules = empty.copy(paths = setOf("/storage/emulated/10/Download"))
        assertFalse(repo().addPath("/sdcard/Download").alreadyProtected)
        assertEquals(2, remote.rules.paths.size)
    }
    @Test fun failedAdditionKeepsPreviousRecords() {
        remote.rules = empty.copy(paths = setOf(download)); local(setOf("$download/Keep"))
        remote.failWrite = true
        assertTrue(runCatching { repo().addPath("$download/New") }.isFailure)
        assertEquals(setOf(download), remote.rules.paths)
        assertEquals(setOf("$download/Keep"), ApkProtectionStore.legacyRules(context).paths)
    }
    @Test fun packageRemovalUsesBothStoresWithoutMigratingOtherLegacyPackages() {
        remote.rules = ApkProtectionRules(setOf("remove.app", "remote.keep"), setOf(download))
        local(setOf("$download/Keep"), setOf("remove.app", "local.keep"))
        val result = repo().updatePackages(setOf("new.app"), setOf("remove.app"))
        assertEquals(setOf("remote.keep", "local.keep", "new.app"), result.effective.packages)
        assertEquals(setOf("local.keep"), result.local.packages)
        assertEquals(setOf(download, "$download/Keep"), result.effective.paths)
    }
    @Test fun failedPackageUpdateDoesNotClearLegacyPackages() {
        remote.rules = empty.copy(packages = setOf("keep.app"))
        local(packages = setOf("keep.app")); remote.failWrite = true
        assertTrue(runCatching { repo().updatePackages(emptySet(), setOf("keep.app")) }.isFailure)
        assertEquals(setOf("keep.app"), ApkProtectionStore.legacyRules(context).packages)
    }
    @Test fun invalidTypedPathsNeverReachRemoteMutation() {
        for (raw in listOf("", "/", "relative", "content://media/7", "/a/../b", "/a/./b", "/data", "/storage/emulated", "/sdcard/*", "/sdcard/a\nb")) {
            assertTrue(raw, runCatching { repo().addPath(raw) }.isFailure)
        }
        assertEquals(0, remote.writes)
    }
    @Test fun storageRootRequiresAdditionalScopeAcknowledgmentInUiModel() {
        for (raw in listOf("/sdcard", "/storage/emulated/0", "/data/media/10", "/storage/ABCD-1234")) {
            assertNotNull(raw, WhitelistPathInput.parse(raw).path)
            assertTrue(raw, WhitelistPathInput.parse(raw).broad)
        }
        assertFalse(WhitelistPathInput.parse(download).broad)
    }
}
