package io.github.xgl34222220.baize

import org.junit.Assert.*
import org.junit.Test

class OrganizerReviewTest {
    private val file = OrganizerPreviewItem("id-a", "文档.pdf", "文档", 2048,
        "浏览器下载", "内部存储/Download/文档.pdf", "内部存储/BaiZe归类/文档/文档.pdf")
    private fun ready() = FileOrganizerUiState(connected = true, snapshotId = "snapshot", previewReady = true,
        expiresAtRealtime = 5_000L, items = listOf(file), selectedIds = setOf(file.id), totalFound = 1)

    @Test fun completeReviewRestoresSelectionAndDestinationButWaitsForConnection() {
        val json = organizerReviewJson(ready(), 10_000L, 1_000L)
        val restored = restoreOrganizerReview(json, 11_000L, 2_000L)
        assertEquals(listOf(file), restored.items)
        assertEquals(setOf(file.id), restored.selectedIds)
        assertFalse(restored.canEdit(2_000L))
        assertTrue(restored.copy(connected = true).canEdit(2_000L))
    }

    @Test fun loadingRunningAndExpiredReviewsRemainReadableButCannotMove() {
        val incomplete = ready().copy(totalFound = 2)
        assertFalse(incomplete.canEdit(1_000L))
        val running = restoreOrganizerReview(organizerReviewJson(ready().copy(running = true), 10_000L, 1_000L), 10_001L, 1_001L)
        assertFalse(running.copy(connected = true).canEdit(1_001L))
        val expired = restoreOrganizerReview(organizerReviewJson(ready(), 10_000L, 1_000L), 20_000L, 11_000L)
        assertEquals(listOf(file), expired.items)
        assertFalse(expired.copy(connected = true).canEdit(11_000L))
    }

    @Test fun submittedPlanCannotBeReplayedAfterAnUnconfirmedReply() {
        val submitted = ready().copy(running = true, previewReady = false, snapshotId = "")
        val restored = restoreOrganizerReview(organizerReviewJson(submitted, 10_000L, 1_000L), 10_001L, 1_001L)
        assertFalse(restored.copy(connected = true).canEdit(1_001L))
        assertEquals(listOf(file), restored.items)
    }

    @Test fun nonexistentIdsAndDuplicateRecordsCannotAuthorizeMoves() {
        val restored = restoreOrganizerReview(organizerReviewJson(ready().copy(selectedIds = setOf(file.id, "missing")), 10_000L, 1_000L), 10_001L, 1_001L)
        assertEquals(setOf(file.id), restored.selectedIds)
        val duplicate = ready().copy(items = listOf(file, file), totalFound = 2)
        assertFalse(restoreOrganizerReview(organizerReviewJson(duplicate, 10_000L, 1_000L), 10_001L, 1_001L).previewReady)
    }
}
