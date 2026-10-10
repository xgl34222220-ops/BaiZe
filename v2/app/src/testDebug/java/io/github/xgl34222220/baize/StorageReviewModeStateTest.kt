package io.github.xgl34222220.baize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 新增工具复用存储工具页状态：时间筛选、勾选与撤销状态互不串扰。 */
class StorageReviewModeStateTest {
    private val now = 1_800_000_000L
    private fun record(id: Long, path: String, ageDays: Int, mime: String = "image/png") =
        StorageFileRecord(id, "uri$id", path, path.substringAfterLast('/'), 1_000L * id, now - ageDays * 86_400L, mime)
            .withVerifiedStorageIdentity()

    @Test fun screenshotModeHidesRecentCapturesAndSelectsOnlyVisible() {
        val old = record(1, "/storage/emulated/0/DCIM/Screenshots/Screenshot_old.png", 60)
        val recent = record(2, "/storage/emulated/0/DCIM/Screenshots/Screenshot_new.png", 2)
        val state = StorageToolsUiState(mode = StorageToolMode.SCREENSHOTS, records = listOf(old, recent),
            minimumAgeDays = 30, nowSeconds = now)
        assertEquals(listOf(old), state.visibleRecords)
        assertEquals(setOf("uri1"), state.toggleAllSelection().selected)
        assertEquals(state, state.toggleSelection("uri2").copy(selected = state.selected))
        assertTrue(state.toggleSelection("uri2").selected.isEmpty())
        val all = state.copy(minimumAgeDays = 0)
        assertEquals(2, all.visibleRecords.size)
    }

    @Test fun customModeFollowsSelectedRule() {
        val zip = StorageReviewFilters.validate("zip", "", "Download/*.zip", 0, 0).filter!!
        val mp4 = StorageReviewFilters.validate("mp4", "", "Movies/*.mp4", 0, 0).filter!!
        val a = record(1, "/storage/emulated/0/Download/a.zip", 5, "application/zip")
        val b = record(2, "/storage/emulated/0/Movies/b.mp4", 5, "video/mp4")
        val state = StorageToolsUiState(mode = StorageToolMode.CUSTOM, records = listOf(a, b), customFilters = listOf(zip, mp4), nowSeconds = now)
        assertEquals(2, state.visibleRecords.size)
        assertEquals(listOf(b), state.copy(activeFilterId = "mp4").visibleRecords)
    }

    @Test fun trashedOutcomeCarriesRecordIdForUndo() {
        val outcome = StorageDeleteOutcome(ApkIndexedDeleteResult.DELETED, trashed = true, trashId = "id-1")
        assertTrue(outcome.trashed)
        assertEquals("id-1", outcome.trashId)
        assertEquals("已移入回收站 · 尚未释放空间", outcome.reason)
    }
}
