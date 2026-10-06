package io.github.xgl34222220.baize

import org.junit.Assert.*
import org.junit.Test

class StorageFileIdentityTest {
    private val path = "/storage/emulated/10/Download/synthetic.zip"
    private val stamp = ApkFileIdentity(path, 4, 90, 100, 30, 31, 10, 20)
    private val record = StorageFileRecord(7, "content://media/external/file/7", path, "synthetic.zip", 100, 30,
        "application/zip", identity = stamp)
    private val access = object : ApkFileAccess {
        var current: ApkFileIdentity? = stamp
        var parentSafe = true
        override fun canonical(path: String) = path.replace("/sdcard", "/storage/emulated/10")
        override fun identity(path: String) = current
        override fun isDirectoryWithoutLink(path: String) = parentSafe
    }
    private val guard = ApkDeletionGuard(setOf("/storage/emulated/10", "/sdcard"), "/storage/emulated/10", access)

    @Test fun originalZipUsesTheSameImmutableIdentityAsArchiveCleanup() {
        assertTrue(StorageMediaRepository.unchanged(record, guard))
        assertEquals(100L, record.verifiedBytes)
    }
    @Test fun sameSizeSameMtimeReplacementIsRejected() {
        access.current = stamp.copy(inode = 91)
        assertFalse(StorageMediaRepository.unchanged(record, guard))
    }
    @Test fun nanosAndDeviceMutationAreRejected() {
        for (changed in listOf(stamp.copy(device = 8), stamp.copy(changedNanos = 21), stamp.copy(modifiedNanos = 11))) {
            access.current = changed
            assertFalse(StorageMediaRepository.unchanged(record, guard))
        }
    }
    @Test fun trustedSharedRootAliasWorksWithoutAllowingAParentSymlink() {
        val alias = record.copy(path = "/sdcard/Download/synthetic.zip")
        assertTrue(StorageMediaRepository.unchanged(alias, guard))
        access.parentSafe = false
        assertFalse(StorageMediaRepository.unchanged(alias, guard))
    }
    @Test fun oldOrUnavailableIdentityIsNotInventedAtDeleteTimeOrCountedAsCapacity() {
        assertFalse(StorageMediaRepository.unchanged(record.copy(identity = null), guard))
        assertEquals(0L, record.copy(identity = null).verifiedBytes)
        assertEquals(0L, storageBuckets(listOf(record.copy(identity = null))).single().bytes)
    }
    @Test fun changedIndexAndForeignUserDoNotPass() {
        assertFalse(StorageMediaRepository.unchanged(record.copy(bytes = 101), guard))
        assertFalse(StorageMediaRepository.unchanged(record.copy(modifiedSeconds = 0), guard))
        assertFalse(StorageMediaRepository.unchanged(record.copy(path = path.replace("/10/", "/0/")), guard))
    }
}
