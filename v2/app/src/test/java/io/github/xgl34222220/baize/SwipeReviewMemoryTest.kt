package io.github.xgl34222220.baize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 滑动整理「已看过」：跨会话排除已处理的文件，文件变化后重新出现，记录有上限且可重置。 */
class SwipeReviewMemoryTest {
    private fun tempDir(): File = Files.createTempDirectory("swipe-memory").toFile()

    @Test fun keptAndMovedFilesStayHiddenInTheNextSessionUntilTheyChange() {
        val root = tempDir()
        val folder = File(root, "Camera").apply { mkdirs() }
        val kept = File(folder, "kept.jpg").apply { writeBytes(ByteArray(10)) }
        val fresh = File(folder, "fresh.jpg").apply { writeBytes(ByteArray(20)) }
        val store = File(root, SwipeReviewMemory.FILE_NAME)

        val first = SwipeReviewMemory(store)
        val (initial, seenBefore) = SwipeReviewSource.listUnseen(folder, first)
        assertEquals(setOf("kept.jpg", "fresh.jpg"), initial.map { it.name }.toSet())
        assertEquals(0, seenBefore)
        first.remember(initial.filter { it.name == "kept.jpg" }, SwipeDecision.KEEP)

        // 新会话（新实例，同一文件）：保留过的文件不再出现。
        val second = SwipeReviewMemory(store)
        val (next, seen) = SwipeReviewSource.listUnseen(folder, second)
        assertEquals(listOf("fresh.jpg"), next.map { it.name })
        assertEquals(1, seen)

        // 文件被改写（大小/时间变化）后视为新文件，重新出现。
        kept.writeBytes(ByteArray(11))
        kept.setLastModified(kept.lastModified() + 5_000)
        val (changed, seenAfterChange) = SwipeReviewSource.listUnseen(folder, SwipeReviewMemory(store))
        assertTrue(changed.any { it.name == "kept.jpg" })
        assertEquals(0, seenAfterChange)
        assertTrue(fresh.isFile)
    }

    @Test fun movedFilesAreRecordedThenPrunedOnceGoneAndResetOnlyClearsThatFolder() {
        val root = tempDir()
        val camera = File(root, "Camera").apply { mkdirs() }
        val download = File(root, "Download").apply { mkdirs() }
        val moved = File(camera, "moved.jpg").apply { writeBytes(ByteArray(5)) }
        val keep = File(download, "keep.zip").apply { writeBytes(ByteArray(7)) }
        val store = File(root, SwipeReviewMemory.FILE_NAME)
        val memory = SwipeReviewMemory(store)
        val cameraItems = SwipeReviewSource.list(camera)
        val downloadItems = SwipeReviewSource.list(download)
        memory.remember(cameraItems, SwipeDecision.DELETE)
        memory.remember(downloadItems, SwipeDecision.KEEP)
        assertTrue(memory.isHandled(cameraItems.single()))

        // 文件已移入回收站（源文件不在了）：不会再出现，记录也会被清理。
        assertTrue(moved.delete())
        val (afterMove, seenCamera) = SwipeReviewSource.listUnseen(camera, SwipeReviewMemory(store))
        assertTrue(afterMove.isEmpty())
        assertEquals(0, seenCamera)

        val reloaded = SwipeReviewMemory(store)
        assertEquals(1, reloaded.count(download.absolutePath))
        assertEquals(1, reloaded.reset(download.absolutePath))
        assertEquals(listOf("keep.zip"), SwipeReviewSource.listUnseen(download, SwipeReviewMemory(store)).first.map { it.name })
        assertTrue("重置只清记录，不动文件", keep.isFile)
    }

    @Test fun undoForgetsAndStorageIsBounded() {
        val root = tempDir()
        val store = File(root, SwipeReviewMemory.FILE_NAME)
        val memory = SwipeReviewMemory(store, maxEntries = 3)
        val items = (1..5).map { SwipeItem("/storage/emulated/0/DCIM/Camera/$it.jpg", "$it.jpg", it.toLong(), 1_000L + it) }
        items.forEach { memory.remember(listOf(it), SwipeDecision.KEEP) }
        val reloaded = SwipeReviewMemory(store, maxEntries = 3)
        assertEquals(3, reloaded.count("/storage/emulated/0/DCIM/Camera"))
        assertFalse("最早的记录被淘汰", reloaded.isHandled(items.first()))
        assertTrue(reloaded.isHandled(items.last()))
        reloaded.forget(listOf(items.last().path))
        assertFalse(SwipeReviewMemory(store, maxEntries = 3).isHandled(items.last()))
        // 含制表符/换行的路径不写入，避免破坏记录格式。
        reloaded.remember(listOf(SwipeItem("/x/a\tb.jpg", "a\tb.jpg", 1, 1)), SwipeDecision.KEEP)
        assertFalse(SwipeReviewMemory(store, maxEntries = 3).isHandled(SwipeItem("/x/a\tb.jpg", "a\tb.jpg", 1, 1)))
    }
}
