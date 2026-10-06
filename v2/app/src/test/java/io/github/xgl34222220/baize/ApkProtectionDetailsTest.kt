package io.github.xgl34222220.baize

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ApkProtectionDetailsTest {
    private val guard = ApkDeletionGuard(setOf("/storage/emulated/10", "/storage/ABCD-1234"), "/storage/emulated/10")
    private val file = "/storage/emulated/10/Download/Keep/app.apk"
    private fun known(paths: Set<String> = emptySet(), packages: Set<String> = emptySet()) =
        ApkProtectionState.KnownRoot(ApkProtectionRules(packages, paths))

    @Test fun reportsEveryActualAncestorAndAliasButNotSiblingsOrOtherUsers() {
        val rules = known(setOf("/sdcard/Download", "/data/media/10/Download/Keep", file,
            "/storage/emulated/10/Down", "/storage/emulated/0/Download", "$file/child"))
        val details = guard.protectionDetails(file, rules)
        assertEquals(setOf(file, "/data/media/10/Download/Keep", "/sdcard/Download"), details.paths.map { it.rule }.toSet())
        assertFalse(details.paths.first { it.rule == file }.ancestor)
        assertTrue(details.paths.first { it.rule == "/sdcard/Download" }.ancestor)
        assertEquals("/storage/emulated/10/Download", details.paths.first { it.rule == "/sdcard/Download" }.identity)
        assertTrue(details.manageable)
        assertEquals(ApkIndexedDeleteResult.PROTECTED, guard.validate("content://media/external/file/1", file, 4, 3, null, rules))
    }

    @Test fun publicVolumeUsesGuardMappingAndRetainsTheOriginalManageableRecord() {
        val details = guard.protectionDetails("/storage/ABCD-1234/Keep/app.apk",
            known(setOf("/mnt/media_rw/ABCD-1234/Keep", "/mnt/media_rw/OTHER-1234/Keep")))
        assertEquals(listOf("/mnt/media_rw/ABCD-1234/Keep"), details.paths.map { it.rule })
        assertTrue(details.paths.single().ancestor)
        val unknownVolume = guard.protectionDetails("/storage/OTHER-1234/Keep/app.apk",
            known(setOf("/mnt/media_rw/OTHER-1234/Keep")))
        assertFalse(unknownVolume.isProtected)
    }

    @Test fun filesystemRootRuleIsExplainedAsAnAncestorAndNotLostDuringFocus() {
        val rules = known(setOf("/"))
        val details = guard.protectionDetails(file, rules)
        assertEquals("/", details.paths.single().rule)
        assertTrue(details.paths.single().ancestor)
        assertTrue(details.manageable)
        assertEquals(ApkIndexedDeleteResult.PROTECTED, guard.validate("content://media/external/file/1", file, 4, 3, null, rules))
        val focused = WhitelistUiState(focusFile = file).withProtection(
            WhitelistProtectionSnapshot(rules.rules, ApkProtectionRules(emptySet(), emptySet()), "/storage/emulated/10"), guard)
        assertEquals(listOf("/"), focused.paths)
        assertEquals("/", focused.focusDetails!!.paths.single().rule)
    }

    @Test fun reportsAppAndPathTogetherWithoutUsingArchivePackageGuessing() {
        val target = "/storage/emulated/10/Android/data/example.app/cache/app.apk"
        val details = guard.protectionDetails(target, known(setOf("/sdcard/Android/data"), setOf("example.app")))
        assertEquals(setOf("example.app"), details.packages)
        assertEquals(1, details.paths.size)
        assertTrue(details.explanation.contains("Android/data"))
        assertTrue(guard.protectionDetails(file, known(packages = setOf("example.app"))).packages.isEmpty())
    }

    @Test fun internalTrashCannotBePresentedAsRemovableWhitelistProtection() {
        for (target in listOf("/storage/emulated/10/.baize-file-trash/${BuildConfig.APPLICATION_ID}/payload.apk",
            "/storage/emulated/10/Android/data/${BuildConfig.APPLICATION_ID}/files/recoverable-trash/payload.apk")) {
            val details = guard.protectionDetails(target, ApkProtectionState.Unknown("offline"))
            assertTrue(details.internalTrash)
            assertTrue(details.isProtected)
            assertFalse(details.manageable)
            assertTrue(details.explanation.contains("回收站"))
            assertEquals(ApkIndexedDeleteResult.PROTECTED, guard.validate("content://media/external/file/1", target, 4, 3, null,
                ApkProtectionState.Unknown("offline")))
        }
    }

    @Test fun unknownAndUnresolvedAliasesNeverBecomeEmptyEditableRules() {
        val state = ApkProtectionState.Unknown("断开连接", known(setOf(file)).rules)
        val details = guard.protectionDetails(file, state)
        assertEquals("断开连接", details.unavailableReason)
        assertFalse(details.manageable)
        assertTrue(details.paths.isEmpty())
        assertEquals(ApkIndexedDeleteResult.PROTECTION_UNAVAILABLE, guard.validate("content://media/external/file/1", file, 4, 3, null, state))
        val unresolved = ApkDeletionGuard(setOf("/storage/emulated/10"), null)
            .protectionDetails(file, known(setOf("/sdcard/Download")))
        assertNotNull(unresolved.unavailableReason)
        assertFalse(unresolved.manageable)
    }

    @Test fun returningFromManagementPreservesHistoryButDisallowsOldSelection() {
        val details = guard.protectionDetails(file, known(setOf("/sdcard/Download")))
        val item = ApkScanItem("app.apk", 1, 4, 0, file, "content://media/external/file/1",
            retainedReason = "已保留", protectionDetails = details)
        val old = ApkScanUiState(items = listOf(item), cleanReady = true, selected = setOf(item.uri), reviewRequested = true)
        val after = old.afterProtectionManagement()
        assertTrue(after.protectionReviewRequired)
        assertFalse(after.cleanReady)
        assertFalse(after.reviewRequested)
        assertTrue(after.selected.isEmpty())
        assertTrue(after.selectableVisibleItems.isEmpty())
        assertTrue(after.toggleAllSelection().selected.isEmpty())
        assertEquals(details, after.items.single().protectionDetails)
    }

    @Test fun focusedManagerRecomputesRemainingRulesAfterEachExactSnapshot() {
        val parent = "/storage/emulated/10/Download"
        val child = "$parent/Keep"
        val empty = ApkProtectionRules(emptySet(), emptySet())
        val before = WhitelistUiState(focusFile = file).withProtection(
            WhitelistProtectionSnapshot(empty.copy(paths = setOf(parent, child)), empty, "/storage/emulated/10"), guard)
        assertEquals(2, before.focusDetails!!.paths.size)
        val after = before.withProtection(
            WhitelistProtectionSnapshot(empty.copy(paths = setOf(parent)), empty, "/storage/emulated/10"), guard)
        assertEquals(parent, after.focusDetails!!.paths.single().rule)
        assertTrue(after.focusDetails!!.isProtected)
        val done = after.withProtection(WhitelistProtectionSnapshot(empty, empty, "/storage/emulated/10"), guard)
        assertFalse(done.focusDetails!!.isProtected)
    }
}
