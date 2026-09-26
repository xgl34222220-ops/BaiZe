package io.github.xgl34222220.baize.root

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneId

class ToolboxConfigTest {
    @get:Rule val folder = TemporaryFolder()
    private fun time(value: String) = Instant.parse(value).toEpochMilli()
    @Test fun defaultsDoNotEnableDestructiveTasksAndSaveRoundTripsSchedules() {
        val store = ToolboxConfig(folder.newFolder())
        val config = store.load()
        ToolboxCatalog.tasks.forEach { assertFalse(config.getJSONObject("tasks").getJSONObject(it.id).getBoolean("enabled")) }
        config.getJSONObject("tasks").getJSONObject("wechat").put("scheduled", true).put("time", "06:45").put("intervalDays", 3)
        store.save(config.toString())
        assertEquals("06:45", store.load().getJSONObject("tasks").getJSONObject("wechat").getString("time"))
        assertEquals(3, store.load().getJSONObject("tasks").getJSONObject("wechat").getInt("intervalDays"))
    }
    @Test fun invalidConfigurationLeavesPreviousFileIntact() {
        val store = ToolboxConfig(folder.newFolder())
        val original = store.save("{}").toString()
        val bad = store.load().put("compilePackages", "com.good.app;touch /data/owned")
        assertThrows(IllegalArgumentException::class.java) { store.save(bad.toString()) }
        assertEquals(original, store.load().toString())
        bad.remove("compilePackages"); bad.getJSONObject("tasks").getJSONObject("empty").put("time", "25:00")
        assertThrows(IllegalArgumentException::class.java) { store.save(bad.toString()) }
        assertEquals(original, store.load().toString())
    }
    @Test fun civilDaySchedulesDeduplicateCatchUpAndRejectClockRollback() {
        val task = JSONObject().put("scheduled", true).put("time", "02:30").put("intervalDays", 1)
        val zone = ZoneId.of("Asia/Shanghai")
        val first = time("2026-09-27T18:31:00Z")
        assertTrue(ToolboxConfig.due(task, first, 0, zone))
        assertFalse(ToolboxConfig.due(task, first + 60_000, first, zone))
        assertFalse(ToolboxConfig.due(task, first - 60_000, first, zone))
        assertFalse(ToolboxConfig.due(task, first + 7 * 3600_000, 0, zone))
        task.put("intervalDays", 3)
        assertFalse(ToolboxConfig.due(task, first + 86400_000L, first, zone))
        assertTrue(ToolboxConfig.due(task, first + 3 * 86400_000L, first, zone))
    }
    @Test fun statisticsCountOnlyActualDeletedFilesAndKeepFailedResults() {
        val store = ToolboxConfig(folder.newFolder())
        store.record(JSONObject().put("finishedAt", System.currentTimeMillis()).put("deletedBytes", 15).put("deletedFiles", 2), true)
        store.record(JSONObject().put("finishedAt", System.currentTimeMillis()).put("success", false).put("memoryDeltaKb", 50000).put("compactedBytes", 100000), true)
        assertEquals(15, store.stats().getLong("totalBytes"))
        assertEquals(2, store.stats().getLong("totalFiles"))
        assertEquals(2, store.history().length())
        store.resetStats()
        assertEquals(2, store.history().length())
        assertFalse(store.stats().has("totalBytes"))
    }
    @Test fun disabledStatisticsStillRecordResultsAndHistoryIsBounded() {
        val store = ToolboxConfig(folder.newFolder())
        repeat(105) { store.record(JSONObject().put("taskId", "$it").put("deletedBytes", 99), false) }
        assertEquals(100, store.history().length())
        assertEquals("104", store.history().getJSONObject(0).getString("taskId"))
        assertEquals(0, store.stats().optLong("totalBytes"))
    }
}
