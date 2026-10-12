package io.github.xgl34222220.baize

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunDetailModelsTest {
    private fun record(errors: Int, skipped: Long) = HistoryUiItem(title = "规则清理", time = "2026-10-13 06:20:00",
        trigger = "定时", result = "完成", bytes = 0, files = 0, emptyDirs = 0, errors = errors, cleaned = true, skipped = skipped)

    @Test fun badgeShowsFailuresInErrorColorSkipsNeutralAndNothingWhenClean() {
        assertEquals(HistoryOutcomeBadge("失败 2 项", error = true), record(2, 5).outcomeBadge)
        assertEquals(HistoryOutcomeBadge("已跳过 5 项", error = false), record(0, 5).outcomeBadge)
        assertNull(record(0, 0).outcomeBadge)
        assertTrue(record(0, 5).hasLeftovers)
    }

    @Test fun parsesItemAndDescribesItInChinese() {
        val item = RunDetailItem.parse(JSONObject().put("index", 3).put("kind", "skipped").put("reason", "protected")
            .put("reasonText", "在你的保护名单中").put("category", "规则垃圾").put("path", "/data/media/0/Keep/a.log")
            .put("exists", true).put("actionable", true).put("protectionKind", "path").put("protectionRule", "/data/media/0/Keep"))
        assertEquals(3, item.index)
        assertEquals("规则垃圾 · 在你的保护名单中", item.statusLine)
        assertEquals("路径保护 /data/media/0/Keep", item.protectionText)
        assertTrue(RunDetailConfirm.body(listOf(item)).contains("临时允许本次"))
        assertEquals("已移入回收站，可在回收站恢复", item.copy(state = "quarantined").statusLine)
    }

    @Test fun protectedSnapshotItemsMapToReasonCodes() {
        assertEquals("protected", RunDetailItems.reasonCode("白名单保护"))
        assertEquals("changed", RunDetailItems.reasonCode("扫描后已变化"))
        assertEquals("review", RunDetailItems.reasonCode("高风险清理未启用"))
        val array = RunDetailItems.fromProtected(listOf(ProtectedUiItem("1", "安全项目", "/data/media/0/x", "白名单保护", "low", false)))
        assertEquals("skipped", array.getJSONObject(0).getString("kind"))
    }

    @Test fun primaryStorageLocationsMapToDocumentPaths() {
        assertEquals("Download/x", RunDetailLocations.primaryRelative("/data/media/0/Download/x"))
        assertEquals("", RunDetailLocations.primaryRelative("/storage/emulated/0"))
        assertNull(RunDetailLocations.primaryRelative("/data/user/0/com.example/cache"))
    }

    @Test fun groupsSurviveTheIntentRoundTrip() {
        val record = record(1, 2).copy(failures = listOf(HistoryFailureUiItem("应用缓存", 1, "没有权限", "/a")),
            skipGroups = listOf(HistorySkipUiItem("legacy", "旧版本记录，未区分原因", 2)))
        val groups = RunDetailGroups.decode(RunDetailGroups.encode(record))
        assertEquals(2, groups.size)
        assertTrue(groups[0].failed)
        assertEquals("旧版本记录，未区分原因", groups[1].label)
    }
}
