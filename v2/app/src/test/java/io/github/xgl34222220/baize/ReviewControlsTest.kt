package io.github.xgl34222220.baize

import org.junit.Assert.*
import org.junit.Test

class ReviewControlsTest {
    private fun item(id: String, risk: String, selectable: Boolean = true) = WorkbenchItem(
        id, "profile", "rules", "test.app", "测试", "log", "app:test", "测试", id, risk,
        "/storage/emulated/0/test/$id", 100, 1, 0, if (selectable) "日志" else "白名单保护", selectable)
    private fun ready() = WorkbenchUiState(profileConnected = true, cacheConnected = true,
        scanReady = true, expiresAtRealtime = 1_000L)
    @Test fun mediumOnlyAndBulkNeverSelectBlockedHighOrCritical() {
        val items = listOf(item("low", "low"), item("medium", "medium"), item("high", "high"),
            item("critical", "critical"), item("protected", "medium", false))
        assertEquals(setOf("medium"), reviewRiskSelection(items, setOf("medium")))
        assertEquals(setOf("low", "medium"), reviewRiskSelection(items, setOf("low", "medium", "high", "critical")))
    }
    @Test fun historyExpiryLoadingAndDisconnectHaveDifferentExplanations() {
        assertNull(reviewSelectionBlockReason(ready(), 100))
        assertTrue(reviewSelectionBlockReason(ready().copy(scanReady = false), 100)!!.contains("历史记录"))
        assertTrue(reviewSelectionBlockReason(ready(), 1_000)!!.contains("过期"))
        assertTrue(reviewSelectionBlockReason(ready().copy(loadingResults = true), 100)!!.contains("读取"))
        assertTrue(reviewSelectionBlockReason(ready().copy(profileConnected = false), 100)!!.contains("未连接"))
    }
    @Test fun highRiskIsManualNotAnUnexplainedDisabledGroup() {
        assertNull(reviewItemRestriction(item("offline", "high"), null))
        assertTrue(reviewItemRestriction(item("offline", "high"), "请重新扫描")!!.contains("重新扫描"))
        assertTrue(reviewItemRestriction(item("locked", "high", false), null)!!.contains("白名单保护"))
        assertTrue(reviewItemRestriction(item("account", "critical"), null)!!.contains("取消白名单也不会"))
    }
    @Test fun restoredRecordIsNotPresentedAsUnexplainedRiskWarning() {
        val state = ready().copy(scanReady = false, notice = WorkbenchNotice.WARNING, items = listOf(item("old", "medium")))
        assertEquals("历史记录 · 需重新扫描", reviewRecordTitle(state))
        assertEquals("清理已完成 · 记录已保留", reviewRecordTitle(state.copy(notice = WorkbenchNotice.SUCCESS)))
        assertEquals("部分项目未完成 · 需重新扫描", reviewRecordTitle(state.copy(items = listOf(item("old", "medium").copy(outcome = "未清理：已变化")))))
    }
    @Test fun whitelistDraftMergesOnlyUserChanges() {
        val draft = WhitelistDraft(setOf("keep.app", "remove.app"), setOf("keep.app", "add.app"))
        assertEquals(setOf("remove.app"), draft.removed)
        assertEquals(setOf("keep.app", "add.app", "new.remote"), draft.rebase(setOf("keep.app", "remove.app", "new.remote")).selected)
    }
}
