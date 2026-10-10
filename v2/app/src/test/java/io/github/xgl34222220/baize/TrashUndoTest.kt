package io.github.xgl34222220.baize

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 删除后 Snackbar「撤销」的恢复逻辑：只恢复本批、不覆盖新文件、内容变化或记录缺失时保留在回收站。 */
class TrashUndoTest {
    private fun fixture(test: (File, OrdinaryFileTrash) -> Unit) {
        val root = Files.createTempDirectory("baize-undo-test").toFile()
        try { test(root, OrdinaryFileTrash(File(root, "metadata"), listOf(File(root, "payload")), now = { 1234L })) }
        finally { root.deleteRecursively() }
    }
    private fun OrdinaryFileTrash.add(root: File, name: String, text: String = "data-$name"): TrashEntry {
        val source = File(root, name).apply { writeText(text) }
        return move(source, source.length(), OrdinaryFileTrash.digest(source), 10_000) { true }
    }

    @Test fun undoRestoresOnlyTheJustTrashedBatch() = fixture { root, trash ->
        val older = trash.add(root, "older.jpg")
        val a = trash.add(root, "a.mp4")
        val b = trash.add(root, "b.zip")
        val result = TrashUndo.restoreBatch(trash, listOf(a.id, b.id))
        assertEquals(2, result.restoredCount)
        assertEquals(0, result.failed)
        assertEquals("data-a.mp4", File(root, "a.mp4").readText())
        assertEquals("data-b.zip", File(root, "b.zip").readText())
        // 更早移入的文件不属于本次批次，仍留在回收站。
        assertFalse(File(root, "older.jpg").exists())
        assertEquals(listOf(older.id), trash.entries().map { it.id })
    }

    @Test fun undoNeverOverwritesANewFileAtTheOriginalPath() = fixture { root, trash ->
        val entry = trash.add(root, "report.pdf", "original")
        File(root, "report.pdf").writeText("newer")
        val result = TrashUndo.restoreBatch(trash, listOf(entry.id))
        assertEquals(1, result.restoredCount)
        assertEquals("newer", File(root, "report.pdf").readText())
        val restored = result.restored.single()
        assertNotEquals(File(root, "report.pdf").path, restored.path)
        assertTrue(restored.name.startsWith("report.pdf.baize-restored-"))
        assertEquals("original", restored.readText())
        assertTrue(trash.entries().isEmpty())
    }

    @Test fun changedPayloadIsNotRestoredAndStaysInTrash() = fixture { root, trash ->
        val changed = trash.add(root, "changed.bin")
        val intact = trash.add(root, "intact.bin")
        File(changed.stored).writeText("tampered")
        val result = TrashUndo.restoreBatch(trash, listOf(changed.id, intact.id))
        assertEquals(1, result.restoredCount)
        assertEquals(1, result.failed)
        assertFalse(File(root, "changed.bin").exists())
        assertEquals("tampered", File(changed.stored).readText())
        assertEquals("data-intact.bin", File(root, "intact.bin").readText())
        assertEquals(listOf(changed.id), trash.entries().map { it.id })
    }

    @Test fun missingRecordsCountAsNotRestored() = fixture { root, trash ->
        val entry = trash.add(root, "photo.jpg")
        trash.purge(entry.id)
        val result = TrashUndo.restoreBatch(trash, listOf(entry.id, "00000000-0000-0000-0000-000000000000"))
        assertEquals(0, result.restoredCount)
        assertEquals(2, result.failed)
        assertFalse(File(root, "photo.jpg").exists())
        assertTrue(TrashUndo.resultMessage(result).contains("2 个未恢复"))
    }

    @Test fun missingOriginalDirectoryKeepsTheFileInTrash() = fixture { root, trash ->
        val folder = File(root, "Download").apply { mkdirs() }
        val source = File(folder, "setup.apk").apply { writeText("apk") }
        val entry = trash.move(source, source.length(), OrdinaryFileTrash.digest(source), 10_000) { true }
        assertTrue(folder.delete())
        val result = TrashUndo.restoreBatch(trash, listOf(entry.id))
        assertEquals(0, result.restoredCount)
        assertEquals(1, result.failed)
        assertEquals(listOf(entry.id), trash.entries().map { it.id })
    }

    @Test fun prepareFailureKeepsThatItemAndContinuesWithTheRest() = fixture { root, trash ->
        val first = trash.add(root, "first.txt")
        val second = trash.add(root, "second.txt")
        val result = TrashUndo.restoreBatch(trash, listOf(first.id, second.id)) { entry ->
            check(entry.id != first.id) { "blocked" }
        }
        assertEquals(listOf(File(root, "second.txt").path), result.restored.map { it.path })
        assertEquals(1, result.failed)
        assertEquals(listOf(first.id), trash.entries().map { it.id })
    }

    @Test fun emptyBatchIsANoOp() = fixture { root, trash ->
        val entry = trash.add(root, "keep.txt")
        val result = TrashUndo.restoreBatch(trash, listOf("", " "))
        assertEquals(0, result.restoredCount)
        assertEquals(0, result.failed)
        assertEquals(listOf(entry.id), trash.entries().map { it.id })
    }

    @Test fun messagesStateCountAndThatSpaceIsNotYetFreed() {
        assertEquals("已移入回收站 3 个文件（1.2 GB），尚未释放空间", TrashUndo.message(3, "1.2 GB"))
        assertEquals("已移入回收站 1 个文件，尚未释放空间", TrashUndo.message(1))
        assertEquals("已恢复 2 个文件", TrashUndo.resultMessage(TrashUndoResult(listOf(File("a"), File("b")), 0)))
        assertEquals("撤销", TrashUndo.ACTION_LABEL)
    }
}
