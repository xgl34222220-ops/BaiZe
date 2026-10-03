package io.github.xgl34222220.baize

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException

class DirectoryUsageTest {
    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("baize-directory-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    @Test fun unindexedFilesAppearInNestedDirectoryTotalsWithoutBecomingSelectable() = fixture { root ->
        File(root, "a/b").mkdirs(); File(root, "a/unindexed.bin").writeBytes(ByteArray(7))
        File(root, "a/b/deeper.bin").writeBytes(ByteArray(11)); File(root, "top.bin").writeBytes(ByteArray(5))
        val usage = DirectoryUsageScanner.scan(mapOf(root to "/storage/emulated/0"))
        assertEquals(23L, usage.bytes)
        assertEquals(3, usage.children(null).single().files)
        assertEquals(18L, usage.children("/storage/emulated/0").single().bytes)
        assertEquals(11L, usage.children("/storage/emulated/0/a").single().bytes)
        val state = StorageToolsUiState(mode = StorageToolMode.ANALYSIS, directoryUsage = usage)
        assertTrue(state.recommended.isEmpty()); assertTrue(state.visibleRecords.isEmpty())
        assertEquals(usage, DirectoryUsage.parse(usage.json()))
    }
    @Test fun outsideLinksAndLoopsAreNeverTraversed() = fixture { root -> fixture { outside ->
        File(outside, "private.bin").writeBytes(ByteArray(99)); File(root, "real.bin").writeBytes(ByteArray(4))
        Files.createSymbolicLink(File(root, "outside").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())
        val usage = DirectoryUsageScanner.scan(mapOf(root to root.path))
        assertEquals(4L, usage.bytes); assertEquals(2, usage.linksSkipped)
        assertFalse(usage.limited)
    } }
    @Test fun entryLimitAndCancellationAreVisibleRatherThanCompleteEmptySuccess() = fixture { root ->
        repeat(12) { File(root, "$it.bin").writeBytes(ByteArray(2)) }
        val partial = DirectoryUsageScanner.scan(mapOf(root to root.path), maxEntries = 4)
        assertTrue(partial.limited); assertTrue(partial.bytes < 24)
        assertTrue(runCatching { DirectoryUsageScanner.scan(mapOf(root to root.path), check = { throw CancellationException() }) }.exceptionOrNull() is CancellationException)
    }
    @Test fun missingRootsAreReportedAndNotCounted() = fixture { root ->
        val usage = DirectoryUsageScanner.scan(mapOf(File(root, "missing") to "/data/user/0"))
        assertEquals(1, usage.inaccessible); assertEquals(0L, usage.bytes); assertTrue(usage.roots.isEmpty())
    }
}
