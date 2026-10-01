package io.github.xgl34222220.baize

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** The provider records mutations; it never removes a real file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ApkDeletionGuardTest {
    private val path = "/storage/emulated/10/Download/test.apk"
    private val uri = "content://media/external/file/7"
    private val stamp = ApkFileIdentity(path, 4, 90, 100, 30, 31, 10, 20)
    private val safe = ApkProtectionState.KnownRoot(ApkProtectionRules(emptySet(), emptySet()))
    private lateinit var access: FakeApkFiles
    private lateinit var guard: ApkDeletionGuard
    private lateinit var provider: RecordingApkDeleteProvider

    @Before fun setup() {
        access = FakeApkFiles(stamp)
        guard = ApkDeletionGuard(setOf("/storage/emulated/10", "/storage/ABCD-1234", "/sdcard"), "/storage/emulated/10", access)
        provider = RecordingApkDeleteProvider(path)
        provider.onDelete = { access.missing = true; access.stamp = null }
        provider.attachInfo(RuntimeEnvironment.getApplication(), ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    private fun delete(rules: ApkProtectionState = safe, target: String = path, itemUri: String = uri,
                       identity: ApkFileIdentity? = stamp, cancel: () -> Boolean = { false }) =
        ApkMediaStoreIndex.deleteIfUnchanged(RuntimeEnvironment.getApplication(), itemUri, target, 100, 30,
            identity, { rules }, cancel, guard)

    @Test fun validIdentityUsesConditionalProviderDelete() {
        assertEquals(ApkIndexedDeleteResult.DELETED, delete())
        assertEquals(1, provider.deletes)
        assertArrayEquals(arrayOf(path, "100", "30"), provider.deleteArgs)
        assertTrue(provider.selection.orEmpty().contains(MediaStore.MediaColumns.DATE_MODIFIED))
    }
    @Test fun deletingOnlyAnIndexRowCannotBeReportedAsDeletingTheFile() {
        provider.onDelete = {}
        assertEquals(ApkIndexedDeleteResult.FAILED, delete())
        assertEquals(1, provider.deletes)
        assertEquals(stamp, access.stamp)
    }
    @Test fun inaccessiblePathAfterProviderDeleteIsNotConfirmedAbsence() {
        provider.onDelete = { access.stamp = null; access.missing = false }
        assertEquals(ApkIndexedDeleteResult.FAILED, delete())
    }
    @Test fun replacementAppearingAfterProviderMutationIsNotReportedAsFreedSpace() {
        provider.onDelete = { access.stamp = stamp.copy(inode = 500); access.missing = false }
        assertEquals(ApkIndexedDeleteResult.FAILED, delete())
    }
    @Test fun freshProtectionAddedAfterScanStopsMutation() {
        assertEquals(ApkIndexedDeleteResult.PROTECTED, delete(ApkProtectionState.KnownRoot(
            ApkProtectionRules(emptySet(), setOf("/data/media/10/Download")))))
        assertEquals(0, provider.deletes)
    }
    @Test fun failedProtectionReadCannotUseAnEmptyCachedListAsPermission() {
        assertEquals(ApkIndexedDeleteResult.PROTECTION_UNAVAILABLE,
            delete(ApkProtectionState.Unknown("unavailable", safe.rules)))
        assertEquals(0, provider.deletes)
    }
    @Test fun pathSegmentBoundaryAndOtherUsersAreNotMistakenForAncestors() {
        assertEquals(ApkIndexedDeleteResult.DELETED, delete(ApkProtectionState.KnownRoot(
            ApkProtectionRules(emptySet(), setOf("/data/media/0/Download", "/storage/emulated/10/Down")))))
    }
    @Test fun primaryUserAliasProtectsUserTenWithoutGuessingUserZero() {
        assertEquals(ApkIndexedDeleteResult.PROTECTED, delete(ApkProtectionState.KnownRoot(
            ApkProtectionRules(emptySet(), setOf("/sdcard/Download")))))
        val unknown = ApkDeletionGuard(setOf("/storage/emulated/10"), null, access)
        assertEquals(ApkIndexedDeleteResult.PROTECTION_UNAVAILABLE,
            unknown.validate(uri, path, 100, 30, stamp, ApkProtectionState.KnownRoot(
                ApkProtectionRules(emptySet(), setOf("/sdcard/Download")))))
    }
    @Test fun rootProtectionCoversEveryCandidate() {
        assertEquals(ApkIndexedDeleteResult.PROTECTED, delete(ApkProtectionState.KnownRoot(
            ApkProtectionRules(emptySet(), setOf("/")))))
    }
    @Test fun protectedApplicationDirectoryIsNotArchivePackageNameGuessing() {
        val rules = ApkProtectionState.KnownRoot(ApkProtectionRules(setOf("example.app"), emptySet()))
        assertEquals(ApkIndexedDeleteResult.PROTECTED, guard.validate(uri,
            "/storage/emulated/10/Android/data/example.app/cache/test.apk", 100, 30, null, rules))
        assertEquals(ApkIndexedDeleteResult.DELETED, delete(rules))
    }
    @Test fun secondaryStorageUsesTheSameAncestorProtection() {
        assertEquals(ApkIndexedDeleteResult.PROTECTED, guard.validate(uri,
            "/storage/ABCD-1234/Keep/test.apk", 100, 30, null,
            ApkProtectionState.KnownRoot(ApkProtectionRules(emptySet(), setOf("/storage/ABCD-1234/Keep")))))
    }
    @Test fun systemReportedSdVolumeRespectsItsRootMediaRwAliasWithoutGuessingOtherVolumes() {
        val target = "/storage/ABCD-1234/Keep/test.apk"
        val identity = stamp.copy(canonicalPath = target)
        access.stamp = identity
        for (protected in listOf("/mnt/media_rw/ABCD-1234/Keep", "/mnt/media_rw/ABCD-1234", "/mnt/media_rw")) {
            assertEquals(protected, ApkIndexedDeleteResult.PROTECTED,
                guard.validate(uri, target, 100, 30, identity,
                    ApkProtectionState.KnownRoot(ApkProtectionRules(emptySet(), setOf(protected)))))
        }
        for (unrelated in listOf("/mnt/media_rw/OTHER-5678/Keep", "/mnt/media_rw/ABCD-1234/Keeper")) {
            assertNull(unrelated, guard.validate(uri, target, 100, 30, identity,
                ApkProtectionState.KnownRoot(ApkProtectionRules(emptySet(), setOf(unrelated)))))
        }
    }
    @Test fun invalidUrisNeverReachTheProvider() {
        for (bad in listOf("file://$path", "content://other/external/file/7", "$uri?x=1", "$uri#part",
            "content://media/external/file/07", "content://media/external/file/-1", "content://media/external/file/0",
            "content://media/external/file/9223372036854775808", "content://media/external/file/%37")) {
            assertEquals(bad, ApkIndexedDeleteResult.INVALID, delete(itemUri = bad))
        }
        assertEquals(0, provider.queries)
        assertEquals(0, provider.deletes)
    }
    @Test fun invalidAndPrivatePathsNeverReachDelete() {
        for (bad in listOf("relative.apk", "$path/../test.apk", "$path\n", "/storage//emulated/10/test.apk")) {
            assertEquals(ApkIndexedDeleteResult.INVALID, delete(target = bad))
        }
        assertNull(guard.capture("/data/user/10/example.app/test.apk"))
        assertNull(guard.capture("/storage/emulated/0/Download/test.apk"))
        assertEquals(0, provider.deletes)
    }
    @Test fun missingOldSnapshotIdentityCannotBeInventedAtDeleteTime() {
        assertNull(ApkFileIdentity.read(null))
        assertEquals(ApkIndexedDeleteResult.UNVERIFIED, delete(identity = null))
        assertEquals(0, provider.deletes)
    }
    @Test fun physicalReplacementOrMutationIsRejectedEvenWhenIndexIsUnchanged() {
        for (changed in listOf(stamp.copy(inode = 91), stamp.copy(bytes = 101), stamp.copy(changedNanos = 21),
            stamp.copy(modifiedNanos = 11), stamp.copy(device = 5))) {
            access.stamp = changed
            assertEquals(ApkIndexedDeleteResult.CHANGED, delete())
        }
        assertEquals(0, provider.deletes)
    }
    @Test fun disappearanceLeafLinkAndParentLinkAreRejected() {
        access.stamp = null
        assertEquals(ApkIndexedDeleteResult.CHANGED, delete())
        access.stamp = stamp
        access.canonicalOverride = "/storage/emulated/10/Private/test.apk"
        assertEquals(ApkIndexedDeleteResult.CHANGED, delete())
        access.canonicalOverride = null
        access.linkedParent = true
        assertEquals(ApkIndexedDeleteResult.CHANGED, delete())
        assertEquals(0, provider.deletes)
    }
    @Test fun changedIndexPreventsDeletionEvenWithMatchingPhysicalIdentity() {
        provider.bytes = 101
        assertEquals(ApkIndexedDeleteResult.CHANGED, delete())
        assertEquals(0, provider.deletes)
    }
    @Test fun mutationDuringProviderReadIsCaughtBySecondPhysicalCheck() {
        provider.onQuery = { access.stamp = stamp.copy(inode = 123) }
        assertEquals(ApkIndexedDeleteResult.CHANGED, delete())
        assertEquals(0, provider.deletes)
    }
    @Test fun cancelDuringProviderReadPreventsThePendingDelete() {
        var cancelled = false
        provider.onQuery = { cancelled = true }
        assertEquals(ApkIndexedDeleteResult.CANCELLED, delete(cancel = { cancelled }))
        assertEquals(0, provider.deletes)
    }
    @Test fun localOnlyStillRequiresFullFileIdentityAndConditionalDelete() {
        assertEquals(ApkIndexedDeleteResult.DELETED, delete(ApkProtectionState.LocalOnly(safe.rules)))
        assertEquals(stamp, ApkFileIdentity.read(stamp.json()))
    }
}

private class FakeApkFiles(var stamp: ApkFileIdentity?) : ApkFileAccess {
    var missing = false
    override fun definitelyMissing(path: String) = missing
    var canonicalOverride: String? = null
    var linkedParent = false
    override fun canonical(path: String): String = if (path.endsWith(".apk")) canonicalOverride ?: path else path
    override fun isDirectoryWithoutLink(path: String) = !linkedParent
    override fun identity(path: String) = stamp
}

private class RecordingApkDeleteProvider(val path: String) : ContentProvider() {
    var bytes = 100L
    var queries = 0
    var deletes = 0
    var selection: String? = null
    var deleteArgs: Array<out String>? = null
    var onQuery: () -> Unit = {}
    var onDelete: () -> Unit = {}
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        queries++; onQuery()
        return MatrixCursor(arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED))
            .apply { addRow(arrayOf<Any>(path, bytes, 30L)) }
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        deletes++; this.selection = selection; deleteArgs = selectionArgs; onDelete(); return 1
    }
    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("No real mutation")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("No real mutation")
}
