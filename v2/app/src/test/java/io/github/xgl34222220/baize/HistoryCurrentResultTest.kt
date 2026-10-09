package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.ui.history.toHistoryUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "最近结果" card must describe one run: its record and its own per-app result, never another run's. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28], application = android.app.Application::class)
class HistoryCurrentResultTest {
    private val format: (Long) -> String = { "$it B" }
    private val apps = listOf(
        AppJunkUiItem("com.google.android.gms", "Google Play 服务", "opentype", 9, 25_360),
        AppJunkUiItem("com.tencent.androidqqmail", "QQ邮箱", "app_bugly", 19, 22_440_000),
        AppJunkUiItem("com.tencent.tmgp.sgame", "王者荣耀", "app_crashSight", 1, 24)
    )
    private val confirmed = 25_360L + 22_440_000L + 24L

    private fun record(id: String, state: String, bytes: Long = 0L) = HistoryUiItem(
        title = "工作台所选清理", time = "2026-10-10 00:43:21", trigger = "App", result = "工作台清理完成，处理 3 个候选",
        bytes = bytes, files = 29, emptyDirs = 0, errors = 0, cleaned = true, releaseState = state, recordId = id)

    @Test fun unmeasuredRunShowsItsOwnConfirmedDeletionAsPartial() {
        val state = DashboardUiState(history = listOf(record("audit-run", "unknown")), recentApps = apps,
            recentRecordId = "audit-run", recentDeletedEvidence = true).toHistoryUiState()
        assertEquals("已确认 $confirmed B · 部分无法测量", state.currentCapacityText(format))
        assertEquals(confirmed, state.confirmedCurrentBytes)
    }

    @Test fun anOlderRunsAppListIsNeverPresentedAsTheLatestRun() {
        val state = DashboardUiState(history = listOf(record("audit-new", "unknown"), record("audit-old", "measured", confirmed)),
            recentApps = apps, recentRecordId = "audit-old", recentDeletedEvidence = true).toHistoryUiState()
        assertEquals("无法测量", state.currentCapacityText(format))
        assertTrue(state.recentApps.isEmpty())
        assertFalse(state.hasCurrentResult)
        assertEquals(0L, state.confirmedCurrentBytes)
    }

    @Test fun unlinkedOrEstimatedListsNeverBecomeConfirmedCapacity() {
        // Module/legacy lists have no record id and may contain scan estimates.
        val legacy = DashboardUiState(history = listOf(record("audit-run", "unknown")), recentApps = apps).toHistoryUiState()
        assertEquals("无法测量", legacy.currentCapacityText(format))
        assertEquals(apps, legacy.recentApps)
        val estimated = DashboardUiState(history = listOf(record("audit-run", "unknown")), recentApps = apps,
            recentRecordId = "audit-run", recentDeletedEvidence = false).toHistoryUiState()
        assertEquals("无法测量", estimated.currentCapacityText(format))
    }

    @Test fun measuredPartialAndZeroRecordsKeepTheirOwnText() {
        fun text(state: String, bytes: Long) = DashboardUiState(history = listOf(record("audit-run", state, bytes)),
            recentApps = apps, recentRecordId = "audit-run", recentDeletedEvidence = true).toHistoryUiState().currentCapacityText(format)
        assertEquals("$confirmed B", text("measured", confirmed))
        assertEquals("已确认 2048 B · 部分未知", text("partial", 2048))
        assertEquals("尚未释放", text("retained", 0))
        val nothing = DashboardUiState(history = listOf(record("audit-run", "unknown")), recentRecordId = "audit-run",
            recentDeletedEvidence = true).toHistoryUiState()
        assertEquals("无法测量", nothing.currentCapacityText(format))
    }
}
