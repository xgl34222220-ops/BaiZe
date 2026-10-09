package io.github.xgl34222220.baize

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SwipeReviewTest {
    private fun item(name: String, bytes: Long = 10, modified: Long = 1_000) = SwipeItem("/storage/emulated/0/DCIM/Camera/$name", name, bytes, modified)

    @Test fun decisionsAdvanceAndUndoRestoresThePreviousCard() {
        var session = SwipeReviewSession(listOf(item("a.jpg", 5), item("b.jpg", 7), item("c.jpg", 11)))
        assertEquals("a.jpg", session.current?.name)
        assertFalse(session.canUndo)
        session = session.decide(SwipeDecision.DELETE).decide(SwipeDecision.KEEP)
        assertEquals("c.jpg", session.current?.name)
        assertEquals(listOf("a.jpg"), session.deletions.map { it.name })
        assertEquals(5L, session.deleteBytes)
        assertEquals(1, session.keptCount)
        session = session.undo()
        assertEquals("b.jpg", session.current?.name)
        session = session.decide(SwipeDecision.DELETE).decide(SwipeDecision.DELETE)
        assertTrue(session.finished)
        assertNull(session.current)
        // Deciding past the end is a no-op.
        assertEquals(session, session.decide(SwipeDecision.KEEP))
        assertEquals(23L, session.deleteBytes)
        assertEquals(SwipeReviewSession(), SwipeReviewSession().undo())
    }

    @Test fun appliedDeletionsLeaveTheSessionWhileOtherDecisionsStay() {
        val session = SwipeReviewSession(listOf(item("a.jpg"), item("b.jpg"), item("c.jpg"), item("d.jpg")))
            .decide(SwipeDecision.DELETE).decide(SwipeDecision.KEEP).decide(SwipeDecision.DELETE)
        val remaining = session.withoutApplied(setOf(item("a.jpg").path))
        assertEquals(listOf("b.jpg", "c.jpg", "d.jpg"), remaining.items.map { it.name })
        assertEquals(listOf(SwipeDecision.KEEP, SwipeDecision.DELETE), remaining.decisions)
        assertEquals("d.jpg", remaining.current?.name)
        assertEquals(listOf("c.jpg"), remaining.deletions.map { it.name })
    }

    @Test fun sourceListsOnlyTopLevelRegularVisibleFilesNewestFirst() {
        val root = Files.createTempDirectory("baize-swipe").toFile()
        try {
            File(root, "old.jpg").apply { writeBytes(ByteArray(3)); setLastModified(1_000_000) }
            File(root, "new.mp4").apply { writeBytes(ByteArray(4)); setLastModified(2_000_000) }
            File(root, ".hidden.jpg").writeBytes(ByteArray(2))
            File(root, "empty.bin").createNewFile()
            File(root, "sub").mkdirs(); File(root, "sub/nested.jpg").writeBytes(ByteArray(9))
            Files.createSymbolicLink(File(root, "link.jpg").toPath(), File(root, "old.jpg").toPath())
            val items = SwipeReviewSource.list(root)
            assertEquals(listOf("new.mp4", "old.jpg"), items.map { it.name })
            assertEquals("video", items.first().kind)
            assertEquals(1, SwipeReviewSource.list(root, limit = 1).size)
            assertTrue(SwipeReviewSource.list(File(root, "missing")).isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun applierMovesOnlyUnchangedFilesAndNeverDeletesDirectly() {
        val root = Files.createTempDirectory("baize-swipe-apply").toFile()
        try {
            val keep = File(root, "a.jpg").apply { writeBytes(ByteArray(6)); setLastModified(1_000_000) }
            val changed = File(root, "b.jpg").apply { writeBytes(ByteArray(6)); setLastModified(1_000_000) }
            val items = listOf(SwipeItem(keep.path, keep.name, 6, keep.lastModified()),
                SwipeItem(changed.path, changed.name, 6, changed.lastModified()),
                SwipeItem(File(root, "gone.jpg").path, "gone.jpg", 6, 1_000_000))
            changed.writeBytes(ByteArray(8))
            val moved = mutableListOf<String>()
            val result = SwipeReviewApplier.apply(items, { "0".repeat(64) }, { file, item, hash, validate ->
                assertTrue(validate())
                moved += file.path
                TrashEntry("00000000-0000-0000-0000-000000000000", file.path, "/trash/x", item.bytes, hash, 0, 1)
            })
            assertEquals(listOf(keep.path), moved)
            assertEquals(setOf(keep.path), result.movedPaths)
            assertEquals(6L, result.movedBytes)
            assertEquals(2, result.skipped.size)
            assertTrue("Applier must not delete anything itself", changed.exists() && keep.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun applierKeepsFilesWhenStoppedOrWhenTheTrashRefuses() {
        val root = Files.createTempDirectory("baize-swipe-stop").toFile()
        try {
            val file = File(root, "a.jpg").apply { writeBytes(ByteArray(6)) }
            val items = listOf(SwipeItem(file.path, file.name, 6, file.lastModified()))
            val stopped = SwipeReviewApplier.apply(items, { "0".repeat(64) }, { _, _, _, _ -> error("must not move") }, cancelled = { true })
            assertTrue(stopped.moved.isEmpty()); assertEquals(1, stopped.skipped.size)
            val refused = SwipeReviewApplier.apply(items, { "0".repeat(64) }, { _, _, _, _ -> error("回收站容量不足") })
            assertTrue(refused.moved.isEmpty())
            assertEquals("回收站容量不足", refused.skipped.single().second)
            assertTrue(file.exists())
        } finally { root.deleteRecursively() }
    }
}
