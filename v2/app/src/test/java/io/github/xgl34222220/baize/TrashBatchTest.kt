package io.github.xgl34222220.baize

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TrashBatchTest {
    private fun entry(id: String, bytes: Long = 4) = TrashEntry(id, "/source/$id", "/trash/$id", bytes,
        "a".repeat(64), 1, 2)

    @Test fun reviewedSnapshotDoesNotFollowArrivalsSelectionOrMutableLists() = runTest {
        val records = mutableListOf(entry("one"), entry("two"))
        val selection = mutableSetOf("one")
        val reviewed = TrashBatchSnapshot.capture(records, selection)
        selection += "two"
        records[0] = entry("one").copy(original = "/changed/path")
        records += entry("new-arrival")
        val attempted = mutableListOf<TrashEntry>()
        val result = runTrashBatch(reviewed, { false }, { attempted += it; TrashItemSuccess("done") })
        assertEquals(listOf("one"), attempted.map { it.id })
        assertEquals("/source/one", attempted.single().original)
        assertEquals(1, result.succeeded)
        assertEquals(4L, reviewed.bytes)
    }

    @Test fun clearAllHasOnlyTheItemsPresentAtReviewAndDeduplicatesIds() = runTest {
        val records = mutableListOf(entry("one"), entry("one"), entry("two"))
        val reviewed = TrashBatchSnapshot.capture(records)
        records += entry("arrived-after-confirmation")
        val attempted = mutableListOf<String>()
        runTrashBatch(reviewed, { false }, { attempted += it.id; TrashItemSuccess("deleted") })
        assertEquals(listOf("one", "two"), attempted)
        assertEquals(8L, reviewed.bytes)
    }

    @Test fun cancelBeforeStartNeverRunsAnItem() = runTest {
        val reviewed = TrashBatchSnapshot.capture(listOf(entry("one"), entry("two")))
        val result = runTrashBatch(reviewed, { true }, { fail("Must not execute"); TrashItemSuccess("") })
        assertEquals(0, result.items.size)
        assertEquals(2, result.remaining)
    }

    @Test fun cancellationStopsRemainingButKeepsCompletedOutcome() = runTest {
        val reviewed = TrashBatchSnapshot.capture(listOf(entry("one"), entry("two"), entry("three")))
        var cancel = false
        val attempted = mutableListOf<String>()
        val result = runTrashBatch(reviewed, { cancel }, {
            attempted += it.id
            cancel = true
            TrashItemSuccess("restored without overwriting")
        })
        assertEquals(listOf("one"), attempted)
        assertEquals(1, result.succeeded)
        assertEquals(2, result.remaining)
        assertEquals("restored without overwriting", result.items.single().detail)
    }

    @Test fun cancellationBetweenProgressAndOperationDoesNotStartTheItem() = runTest {
        var cancel = false
        val result = runTrashBatch(TrashBatchSnapshot.capture(listOf(entry("one"))), { cancel },
            { fail("Must not execute"); TrashItemSuccess("") }, onProgress = { cancel = true })
        assertEquals(1, result.remaining)
        assertEquals(0, result.items.size)
    }

    @Test fun failuresArePerItemAndDoNotHideSuccessfulOrLaterWork() = runTest {
        val reviewed = TrashBatchSnapshot.capture(listOf(entry("good"), entry("changed"), entry("later")))
        val progress = mutableListOf<TrashBatchProgress>()
        val result = runTrashBatch(reviewed, { false }, {
            if (it.id == "changed") error("内容变化，未执行永久删除")
            TrashItemSuccess("done ${it.id}")
        }, progress::add)
        assertEquals(2, result.succeeded)
        assertEquals(1, result.failed)
        assertEquals(0, result.remaining)
        assertEquals(listOf("good", "changed", "later"), result.items.map { it.entry.id })
        assertEquals("内容变化，未执行永久删除", result.items[1].detail)
        assertEquals(listOf(0, 1, 1, 2, 2, 3), progress.map { it.completed })
        assertEquals(TrashBatchProgress(3, 3), progress.last())
    }

    @Test fun lifecycleCancellationIsNotConvertedIntoAFileFailure() = runTest {
        val attempted = mutableListOf<String>()
        try {
            runTrashBatch(TrashBatchSnapshot.capture(listOf(entry("one"), entry("two"))), { false }, {
                attempted += it.id
                throw CancellationException("screen finished")
            })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(listOf("one"), attempted)
        }
    }

    @Test fun totalByteCountNeverWrapsIntoANegativeConfirmation() {
        val reviewed = TrashBatchSnapshot.capture(listOf(entry("one", Long.MAX_VALUE), entry("two", 100)))
        assertEquals(Long.MAX_VALUE, reviewed.bytes)
    }

    @Test fun emptySnapshotIsANoOp() = runTest {
        val result = runTrashBatch(TrashBatchSnapshot.capture(emptyList()), { false }, {
            fail("Must not execute"); TrashItemSuccess("")
        })
        assertEquals(0, result.succeeded)
        assertEquals(0, result.failed)
        assertEquals(0, result.remaining)
    }
}
