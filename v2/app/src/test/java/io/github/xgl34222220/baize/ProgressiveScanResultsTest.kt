package io.github.xgl34222220.baize

import org.junit.Assert.*
import org.junit.Test

class ProgressiveScanResultsTest {
    private data class Item(val id: String, val risk: String = "low", val blocked: String = "", val bytes: Long = 0L)
    private fun results(includeMedium: Boolean = true) = ProgressiveScanResults<Item>({ it.id }) {
        ReviewRiskPolicy.selectable(it.risk, it.blocked) &&
            ReviewRiskPolicy.defaultSelected(it.risk, it.blocked, includeMedium)
    }

    @Test fun firstPageIsImmediatelyPublishedAndEarlierReviewsStayImmutable() {
        val results = results()
        val first = requireNotNull(results.append(listOf(Item("a"), Item("b"))))
        assertNull(results.append(listOf(Item("c"))))
        val second = requireNotNull(results.append(listOf(Item("d"))))
        assertEquals(listOf("a", "b"), first.items.map { it.id })
        assertEquals(setOf("a", "b"), first.selectedIds)
        assertEquals(listOf("a", "b", "c", "d"), second.items.map { it.id })
    }

    @Test fun publicationCopyVolumeIsLinearForLargePagedReviews() {
        val results = results()
        var copied = 0
        var publications = 0
        repeat(1_000) { page ->
            results.append((0 until 60).map { Item("${page * 60 + it}") })?.let {
                copied += it.items.size
                publications++
            }
        }
        val complete = results.finish(compareBy { it.id })
        assertEquals(60_000, complete.items.size)
        assertTrue("copies=$copied", copied < 120_000)
        assertTrue("publications=$publications", publications <= 11)
    }

    @Test fun finalReviewIncludesUnpublishedTailAndSortsOnlyAtCompletion() {
        val results = results()
        val preview = requireNotNull(results.append(listOf(Item("small", bytes = 1), Item("large", bytes = 10))))
        assertEquals("small", preview.items.first().id)
        assertNull(results.append(listOf(Item("tail", bytes = 5))))
        val final = results.finish(compareByDescending { it.bytes })
        assertEquals(listOf("large", "tail", "small"), final.items.map { it.id })
        assertEquals(setOf("small", "large", "tail"), final.selectedIds)
    }

    @Test fun incrementalDefaultsPreserveRiskWhitelistAndConservativePolicy() {
        for (includeMedium in listOf(false, true)) {
            val results = results(includeMedium)
            results.append(listOf(Item("low"), Item("medium", "medium")))
            results.append(listOf(Item("high", "high"), Item("critical", "critical"), Item("protected", blocked = "白名单")))
            assertEquals(if (includeMedium) setOf("low", "medium") else setOf("low"),
                results.finish(compareBy { it.id }).selectedIds)
        }
    }

    @Test fun duplicateIdsDoNotDuplicateRowsOrChangeSelection() {
        val results = results()
        results.append(listOf(Item("same", blocked = "白名单")))
        assertNull(results.append(listOf(Item("same"))))
        val final = results.finish(compareBy { it.id })
        assertEquals(1, final.items.size)
        assertTrue(final.selectedIds.isEmpty())
    }

    @Test fun pageCursorUsesActualReturnedCountIncludingShortPages() {
        val cursor = ScanPageCursor("snapshot")
        assertFalse(cursor.complete)
        cursor.accept("snapshot", 0, 125, 60)
        cursor.accept("snapshot", 60, 125, 10)
        assertEquals(70, cursor.offset)
        cursor.accept("snapshot", 70, 125, 55)
        assertTrue(cursor.complete)
    }

    @Test fun zeroCandidateSnapshotCompletesWithoutAnotherRequest() {
        val cursor = ScanPageCursor("empty")
        cursor.accept("empty", 0, 0, 0)
        assertTrue(cursor.complete)
        assertNull(results().append(emptyList()))
    }

    @Test fun prematureEmptyPageCannotBecomeCleanableReview() {
        val cursor = ScanPageCursor("snapshot")
        cursor.accept("snapshot", 0, 100, 60)
        assertThrows(IllegalStateException::class.java) { cursor.accept("snapshot", 60, 100, 0) }
        assertFalse(cursor.complete)
        assertEquals(60, cursor.offset)
    }

    @Test fun changedSnapshotOffsetTotalAndOversizedPageAreRejected() {
        val cursor = ScanPageCursor("snapshot")
        cursor.accept("snapshot", 0, 100, 60)
        assertThrows(IllegalStateException::class.java) { cursor.accept("obsolete", 60, 100, 40) }
        assertThrows(IllegalStateException::class.java) { cursor.accept("snapshot", 0, 100, 40) }
        assertThrows(IllegalStateException::class.java) { cursor.accept("snapshot", 60, 101, 40) }
        assertThrows(IllegalStateException::class.java) { cursor.accept("snapshot", 60, 100, 41) }
        assertEquals(60, cursor.offset)
        assertFalse(cursor.complete)
    }

    @Test fun cancellationRejectsLateBinderReplyEvenWithoutANewerScan() {
        val generation = ScanLoadGeneration()
        val token = generation.start()
        assertTrue(generation.accepts(token))
        generation.invalidate()
        assertFalse(generation.accepts(token))
    }

    @Test fun obsoleteGenerationCannotPublishIntoRestartedScan() {
        val generation = ScanLoadGeneration()
        val old = generation.start()
        val current = generation.start()
        var displayed = "new scan"
        if (generation.accepts(old)) displayed = "obsolete page"
        assertEquals("new scan", displayed)
        assertTrue(generation.accepts(current))
        generation.invalidate()
        assertFalse(generation.accepts(current))
    }
}
