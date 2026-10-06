package io.github.xgl34222220.baize

import android.app.Application
import io.github.xgl34222220.baize.root.ReleaseAmount
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AppHistoryMigrationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val file get() = File(app.filesDir, "app-clean-history.json")
    @Before fun reset() { file.delete(); File(file.path + ".bak").delete(); File(file.path + ".new").delete() }
    private fun row() = JSONObject().put("title", "旧清理").put("time", "2026-10-03 12:00:00")
        .put("trigger", "App 手动").put("result", "未确认容量").put("cleaned", true)
    private fun seed(vararg rows: JSONObject) { file.parentFile!!.mkdirs(); file.writeText(JSONObject().put("entries", JSONArray(rows.toList())).toString()) }

    @Test fun oldZeroAndMissingCapacityStayUnknownWithoutRewritingHistory() {
        seed(row().put("bytes", 0), row(), row().put("bytes", JSONObject.NULL))
        val original = file.readBytes(); val entries = AppTaskHistoryStore.read(app).entries
        assertEquals(3, entries.size); assertTrue(entries.all { it.releaseState == "unknown" && it.capacityText { "${it}B" } == "无法测量" })
        assertArrayEquals(original, file.readBytes())
    }
    @Test fun malformedOldCapacitiesNeverBecomeMeasuredZero() {
        seed(row().put("bytes", -1), row().put("bytes", 1.5), row().put("bytes", true), row().put("bytes", "9223372036854775808"))
        assertTrue(AppTaskHistoryStore.read(app).entries.all { it.releaseState == "unknown" })
    }
    @Test fun oldPositiveContentEvidenceRemainsMeasured() {
        seed(row().put("bytes", 4096))
        val entry = AppTaskHistoryStore.read(app).entries.single()
        assertEquals("measured", entry.releaseState); assertEquals(4096L, entry.bytes)
    }
    @Test fun identicalLegacyRowsAtOneSecondRemainSeparateOccurrences() {
        seed(row().put("bytes", 0), row().put("bytes", 0))
        val first = AppTaskHistoryStore.read(app).entries; val second = AppTaskHistoryStore.read(app).entries
        assertEquals(2, first.distinctBy { it.recordId }.size); assertEquals(first.map { it.recordId }, second.map { it.recordId })
    }
    @Test fun explicitMeasuredZeroAndUnknownHaveDifferentPersistedStates() {
        AppTaskHistoryStore.append(app, "未知", "请求已完成", 0, 0, 0)
        AppTaskHistoryStore.append(app, "真实零", "空目录已移除", 0, 0, 0, release = ReleaseAmount(ReleaseAmount.State.MEASURED, 0))
        val entries = AppTaskHistoryStore.read(app).entries
        assertEquals(listOf("measured", "unknown"), entries.map { it.releaseState })
        assertEquals("0B", entries.first().capacityText { "${it}B" })
        assertEquals("无法测量", entries.last().capacityText { "${it}B" })
    }
    @Test fun retainedAndPartialRecordsOnlyAccumulateConfirmedDeletedBytes() {
        AppTaskHistoryStore.append(app, "回收站", "仍占空间", 2048, 1, 0, release = ReleaseAmount(ReleaseAmount.State.RETAINED, retainedBytes = 2048))
        AppTaskHistoryStore.append(app, "中断", "部分失败", 9999, 1, 0, release = ReleaseAmount(ReleaseAmount.State.PARTIAL, 72))
        val snapshot = AppTaskHistoryStore.read(app)
        assertEquals(72L, snapshot.lifetimeReleased); assertEquals("已确认 72B · 部分未知", snapshot.entries.first().capacityText { "${it}B" })
        assertEquals("尚未释放", snapshot.entries.last().capacityText { "${it}B" })
    }
    @Test fun newIdenticalTasksUsePersistentUniqueIds() {
        repeat(2) { AppTaskHistoryStore.append(app, "相同任务", "相同结果", 0, 0, 0) }
        val entries = AppTaskHistoryStore.read(app).entries
        assertEquals(2, entries.map { it.recordId }.toSet().size)
        assertTrue(entries.all { it.recordId.startsWith("app-") })
        assertEquals(entries.map { it.recordId }, AppTaskHistoryStore.read(app).entries.map { it.recordId })
    }
    @Test fun scanRecordsDoNotCountAReleaseEvenWithAnOldSizeEstimate() {
        seed(row().put("cleaned", false).put("bytes", 4096))
        val entry = AppTaskHistoryStore.read(app).entries.single()
        assertEquals("not_applicable", entry.releaseState); assertEquals("不计释放", entry.capacityText { "${it}B" })
    }
}
