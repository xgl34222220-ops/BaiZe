package io.github.xgl34222220.baize

import java.nio.file.Files
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class StorageGrowthTest {
    private fun record(id: Long, path: String, bytes: Long) = StorageFileRecord(id, "uri$id", path, path.substringAfterLast('/'), bytes, 1, "image/jpeg").withVerifiedStorageIdentity()
    @Test fun directoryDrilldownCountsChildrenAndShowsOnlyDirectFiles() {
        val files = listOf(record(1, "/storage/emulated/0/DCIM/Camera/a.jpg", 10), record(2, "/storage/emulated/0/DCIM/b.jpg", 20))
        assertEquals(30L, storageDirectories(files, null).single().bytes)
        assertEquals("/storage/emulated/0/DCIM", storageDirectories(files, "/storage/emulated/0").single().path)
        assertEquals(10L, storageDirectories(files, "/storage/emulated/0/DCIM").single().bytes)
        assertEquals(listOf(files[1]), StorageToolsUiState(mode = StorageToolMode.ANALYSIS, records = files, directory = "/storage/emulated/0/DCIM").visibleRecords)
    }
    @Test fun incompleteOrDifferentVolumeScanNeverPretendsComparableGrowth() {
        val dir = Files.createTempDirectory("baize-growth-test").toFile()
        try {
            val store = StorageGrowthStore(File(dir, "baseline.json"))
            val first = record(1, "/storage/emulated/0/Pictures/a.jpg", 10)
            assertTrue(store.record(StorageIndexResult(listOf(first), 0, false), 1).changes.isEmpty())
            assertTrue(store.record(StorageIndexResult(listOf(first.copy(bytes = 30)), 0, true), 2).changes.isEmpty())
            val grown = first.copy(bytes = 20, identity = first.identity!!.copy(bytes = 20))
            assertEquals(10L, store.record(StorageIndexResult(listOf(grown), 0, false), 3).changes.first { it.label.endsWith("Pictures") }.delta)
            val removedVolume = record(2, "/storage/ABCD-1234/a.jpg", 50)
            assertTrue(store.record(StorageIndexResult(listOf(removedVolume), 0, false), 4).changes.isEmpty())
        } finally { dir.deleteRecursively() }
    }
}
