package io.github.xgl34222220.baize

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException

class StorageWorkbenchTest {
    private fun record(id: Long, size: Long = 10, name: String = "文件$id.pdf", modified: Long = id) =
        StorageFileRecord(id, "uri$id", "/storage/emulated/0/Download/$name", name, size, modified, "application/pdf").withVerifiedStorageIdentity()

    @Test fun incrementalScanReusesOnlyUnchangedPreciseIdentity() {
        val cache = StorageDigestCache()
        val files = listOf(record(1), record(2)).map { it.copy(identity = it.identity!!.copy(changedNanos = 10)) }
        var reads = 0
        val open: (StorageFileRecord) -> java.io.InputStream? = { reads++; ByteArrayInputStream(ByteArray(10)) }
        assertEquals(1, StorageDuplicateMatcher.match(files, open, cache = cache).size)
        val initialReads = reads
        assertEquals(1, StorageDuplicateMatcher.match(files, open, cache = cache).size)
        assertEquals(initialReads, reads)
        assertEquals(4, cache.hits)
        val changed = files.map { if (it.id == 1L) it.copy(identity = it.identity!!.copy(changedNanos = 7)) else it }
        StorageDuplicateMatcher.match(changed, open, cache = cache)
        assertEquals(initialReads + 2, reads)
        val coarse = files.map { it.copy(identity = it.identity!!.copy(modifiedNanos = -1, changedNanos = -1)) }
        StorageDuplicateMatcher.match(coarse, open, cache = cache)
        val coarseReads = reads
        StorageDuplicateMatcher.match(coarse, open, cache = cache)
        assertEquals(coarseReads + 4, reads)
    }
    @Test fun completedPartialGroupsRemainInspectableAndSingletonsDisappear() {
        val files = listOf(record(1), record(2), record(3))
        var partial = emptyList<DuplicateFileGroup>()
        val groups = StorageDuplicateMatcher.match(files, { ByteArrayInputStream(ByteArray(10)) }, completed = { partial = it })
        assertEquals(3, partial.single().records.size)
        assertEquals(1, remainingDuplicateGroups(groups, files.take(2)).size)
        assertTrue(remainingDuplicateGroups(groups, files.take(1)).isEmpty())
        assertEquals(1, files.take(1).size)
    }
    @Test fun duplicateKeeperPreferencesAreExplainableAndManualChoiceWins() {
        val camera = record(1, name = "camera.jpg").copy(path = "/storage/emulated/0/DCIM/Camera/camera.jpg")
        val newest = record(2, modified = 99)
        val group = DuplicateFileGroup("hash", 10, listOf(newest, camera))
        assertEquals(camera, preferredDuplicateKeeper(group.records, DuplicateKeeperPreference.CAMERA))
        assertEquals(camera, preferredDuplicateKeeper(group.records, DuplicateKeeperPreference.OLDEST))
        assertEquals(newest, preferredDuplicateKeeper(group.records, DuplicateKeeperPreference.NEWEST))
        assertEquals(newest, preferredDuplicateKeeper(group.records, DuplicateKeeperPreference.DIRECTORY, "/storage/emulated/0/Download"))
        val state = StorageToolsUiState(mode = StorageToolMode.DUPLICATES, duplicateGroups = listOf(group), keeperPreference = DuplicateKeeperPreference.CAMERA)
        assertEquals(setOf(newest.uri), state.toggleAllSelection().selected)
        assertEquals(setOf(camera.uri), state.keepCopy(newest.uri).selected)
        assertFalse(state.keepCopy(newest.uri).toggleSelection(newest.uri).selected.contains(newest.uri))
    }
    @Test fun samePrefixDifferentTailIsNotDuplicate() {
        val a = ByteArray(70_000) { 1 }; val b = a.clone().also { it[it.lastIndex] = 2 }
        val files = listOf(record(1, a.size.toLong()), record(2, b.size.toLong()), record(3, a.size.toLong()))
        val groups = StorageDuplicateMatcher.match(files, { ByteArrayInputStream(if (it.id == 2L) b else a) })
        assertEquals(1, groups.size)
        assertEquals(setOf(1L, 3L), groups.single().records.map { it.id }.toSet())
        assertEquals(70_000L, groups.single().reclaimableBytes)
    }
    @Test fun staleShortOrLongSizeCannotMatch() {
        val files = listOf(record(1, 10), record(2, 10))
        assertTrue(StorageDuplicateMatcher.match(files, { ByteArrayInputStream(ByteArray(9)) }).isEmpty())
        assertTrue(StorageDuplicateMatcher.match(files, { ByteArrayInputStream(ByteArray(11)) }).isEmpty())
    }
    @Test fun samePhysicalPathCannotBeCountedAsTwoCopies() {
        val a = record(1)
        assertTrue(StorageDuplicateMatcher.match(listOf(a, a.copy(id = 2, uri = "uri2")), { ByteArrayInputStream(ByteArray(10)) }).isEmpty())
    }
    @Test fun cancellationClosesStreamAndPropagates() {
        var checks = 0; var closed = false
        val stream = object : ByteArrayInputStream(ByteArray(200_000)) { override fun close() { closed = true; super.close() } }
        try {
            StorageDuplicateMatcher.digest(record(1, 200_000), false, { stream }, { ++checks > 2 })
            fail("Cancellation must not be swallowed as an unreadable file")
        } catch (_: CancellationException) { assertTrue(closed) }
    }
    @Test fun fileChangedDuringHashIsExcluded() {
        var checks = 0
        val files = listOf(record(1), record(2))
        assertTrue(StorageDuplicateMatcher.match(files, { ByteArrayInputStream(ByteArray(10)) },
            unchanged = { if (it.id == 1L) ++checks < 3 else true }).isEmpty())
    }
    @Test fun filteredSelectionNeverIncludesHiddenFiles() {
        val records = listOf(record(1, 100, "保留.pdf"), record(2, 200, "旅行.pdf"), record(3, 300, "旅行笔记.pdf"))
        val state = StorageToolsUiState(records = records, query = "旅行")
        assertEquals(setOf("uri2", "uri3"), state.toggleAllSelection().selected)
        assertEquals(500L, state.toggleAllSelection().selectedBytes)
        assertEquals(emptySet<String>(), state.toggleSelection("uri1").selected)
    }
    @Test fun duplicateSearchKeepsEntireGroupAndCustomSurvivor() {
        val group = DuplicateFileGroup("hash", 10, listOf(record(1, name = "原件.pdf"), record(2, name = "副本.pdf"), record(3)))
        val state = StorageToolsUiState(mode = StorageToolMode.DUPLICATES, query = "副本", duplicateGroups = listOf(group), selected = setOf("uri1"))
        assertEquals(3, state.visibleRecords.size)
        assertEquals(setOf("uri1", "uri3"), state.toggleAllSelection().selected)
        assertFalse(state.toggleAllSelection().toggleSelection("uri2").selected.contains("uri2"))
    }
    @Test fun analysisDrillsIntoActualCategoryAndSorts() {
        val a = record(1, 50); val b = record(2, 80).copy(name = "录像.mp4", mime = "video/mp4")
        val state = StorageToolsUiState(mode = StorageToolMode.ANALYSIS, records = listOf(a, b), category = "document")
        assertEquals(listOf(a), state.visibleRecords)
        assertEquals(setOf(a.uri), state.toggleAllSelection().selected)
        assertEquals(listOf(a, b), filterStorageRecords(listOf(a, b), "", null, 0, StorageSort.OLDEST))
        assertEquals(130L, storageBuckets(listOf(a, b)).sumOf { it.bytes })
    }
    @Test fun ownerAssociationRequiresAndroidPackageDirectory() {
        assertEquals("com.example.app", storageOwnerPackage("/storage/emulated/0/Android/media/com.example.app/shared/file.mp4"))
        assertNull(storageOwnerPackage("/storage/emulated/0/Download/com.example.app.mp4"))
        assertFalse(StorageMediaRepository.safeSharedFile("/storage/emulated/0/../../data/file"))
    }
    @Test fun unknownRecordsStayAvailableForDiagnosisButCannotEnterSelectionOrCapacity() {
        val known = record(1)
        val unknown = record(2).copy(identity = null)
        val state = StorageToolsUiState(records = listOf(known, unknown))
        assertEquals(2, state.visibleRecords.size)
        assertEquals(setOf(known.uri), state.toggleAllSelection().selected)
        assertTrue(state.toggleSelection(unknown.uri).selected.isEmpty())
        assertEquals(known.bytes, storageBuckets(state.records).sumOf { it.bytes })
    }
    @Test fun apkFilterSelectsOnlyMatchingVersionStatus() {
        val older = ApkScanItem("old.apk", 1, 50, 0, "/old.apk", archive = ApkArchiveInfo(status = ApkInstallStatus.OLDER))
        val current = older.copy(name = "new.apk", uri = "/new.apk", archive = ApkArchiveInfo(status = ApkInstallStatus.INSTALLED))
        val state = ApkScanUiState(items = listOf(older, current), filter = ApkInstallStatus.OLDER)
        assertEquals(setOf(older.uri), state.toggleAllSelection().selected)
        assertEquals(ApkInstallStatus.OLDER, ApkArchiveMetadata.compareVersions(1, 2))
        assertEquals(ApkInstallStatus.NEWER, ApkArchiveMetadata.compareVersions(3, 2))
        assertEquals(ApkInstallStatus.NOT_INSTALLED, ApkArchiveMetadata.compareVersions(3, null))
    }
}

internal fun StorageFileRecord.withVerifiedStorageIdentity() = copy(identity = ApkFileIdentity(path, 1, id,
    bytes, modifiedSeconds, modifiedSeconds, 0, 0))
