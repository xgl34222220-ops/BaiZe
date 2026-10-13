package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 3.3.4：跳过 ≠ 异常。只有真实删除失败计入失败；旧记录按读取时重新分类，不再全部显示「有异常」。 */
class HistoryOutcomeTest {
    @get:Rule val folder = TemporaryFolder()

    private fun codes(groups: JSONArray) = (0 until groups.length()).map { groups.getJSONObject(it).getString("code") }

    @Test fun v2RowsCountOnlyRealFailuresAndListSkipReasons() {
        val outcome = HistoryFailureDetails.outcome("rules-clean", 1,
            "~v2;规则垃圾|1|Permission denied|/data/media/0/a.log;~skip|protected|3|/data/media/0/keep;~skip|slow|2|/data/media/0/big;~detail|20261013-062600-rules-clean-77")
        assertEquals(1L, outcome.failed)
        assertEquals(5L, outcome.skipped)
        assertEquals(listOf("protected", "slow"), codes(outcome.skipGroups))
        assertEquals("在你的保护名单中", outcome.skipGroups.getJSONObject(0).getString("label"))
        assertEquals("20261013-062600-rules-clean-77", outcome.detailId)
        assertEquals("没有权限（Permission denied）", outcome.failureGroups.getJSONObject(0).getString("reasonText"))
    }

    @Test fun v2RowWithOnlySkipsHasNoFailure() {
        val outcome = HistoryFailureDetails.outcome("cache-clean", 0, "~v2;~skip|changed|4|")
        assertEquals(0L, outcome.failed)
        assertEquals(4L, outcome.skipped)
    }

    @Test fun legacyScanErrorsAreSkippedNotFailures() {
        val outcome = HistoryFailureDetails.outcome("deep-scan", 7, "")
        assertEquals(0L, outcome.failed)
        assertEquals(7L, outcome.skipped)
        assertEquals(listOf("scan_unreadable"), codes(outcome.skipGroups))
    }

    @Test fun legacyShellCleanWithoutEvidenceIsNeutral() {
        val outcome = HistoryFailureDetails.outcome("rules-clean", 3, "")
        assertEquals(0L, outcome.failed)
        assertEquals(3L, outcome.skipped)
        assertEquals(listOf("legacy"), codes(outcome.skipGroups))
    }

    @Test fun testBuildReadFailureGroupsBecomeSkipsAndRealOnesStay() {
        val outcome = HistoryFailureDetails.outcome("rules-clean", 3,
            "规则垃圾|2|目录无法完整读取（代码 1）|/data/media/0/x;应用缓存|1|Operation not permitted|/data/user/0/p/cache/a")
        assertEquals(1L, outcome.failed)
        assertEquals(2L, outcome.skipped)
        assertEquals(1, outcome.failureGroups.length())
        assertEquals(listOf("unreadable"), codes(outcome.skipGroups))
    }

    @Test fun appNativeAndOrganizerErrorsStayReal() {
        assertEquals(2L, HistoryFailureDetails.outcome("snapshot-clean", 2, "", "app-native").failed)
        assertEquals(1L, HistoryFailureDetails.outcome("organize", 1, "").failed)
        assertEquals(1L, HistoryFailureDetails.outcome("性能工具·Dex2oat编译", 1, "").failed)
    }

    @Test fun maliciousDetailIdIsDropped() {
        assertEquals("", HistoryFailureDetails.outcome("rules-clean", 0, "~v2;~detail|../../etc/passwd").detailId)
    }

    @Test fun historyJsonExposesFailedSkippedAndDetail() {
        val row = listOf("2026-10-13 06:20:00", "rules-clean", "100", "1", "0", "0", "规则清理完成", "scheduler:interval",
            "规则垃圾|100|1", "", "", "", "~v2;~skip|protected|2|/data/media/0/k;~detail|20261013-062000-rules-clean-1").joinToString("\t")
        File(folder.root, "history.tsv").writeText(row + "\n")
        val entry = JSONObject(HistoryRepository(folder.newFolder("module"), folder.root).taskHistoryJson(10))
            .getJSONArray("entries").getJSONObject(0)
        assertEquals(0, entry.getInt("errors"))
        assertEquals(0L, entry.getLong("failed"))
        assertEquals(2L, entry.getLong("skipped"))
        assertEquals("20261013-062000-rules-clean-1", entry.getString("detailId"))
        assertEquals(0, entry.getJSONArray("failureDetails").length())
        // The audit timeline counts the same row as success, never partial.
        val event = JSONObject(AuditRepository(folder.root).timelinePageJson(0, 10)).getJSONArray("events").getJSONObject(0)
        assertEquals(0L, event.getLong("errors"))
        assertTrue(event.getString("status") != "partial")
    }

    @Test fun legacyRowsNoLongerAllShowErrors() {
        val rows = listOf(
            listOf("2026-10-12 07:38:00", "rules-clean", "0", "0", "0", "2", "任务未完成，已处理 0 项，失败 2 项", "scheduler:interval", "", ""),
            listOf("2026-10-12 07:39:00", "deep-scan", "0", "0", "0", "4", "深度扫描完成", "scheduler:interval", "", ""),
            listOf("2026-10-12 07:40:00", "cache-clean", "0", "0", "0", "1", "应用缓存清理未完成", "scheduler:interval", "", "")
        ).joinToString("\n") { it.joinToString("\t") }
        File(folder.root, "history.tsv").writeText(rows + "\n")
        val entries = JSONObject(HistoryRepository(folder.newFolder("module"), folder.root).taskHistoryJson(10)).getJSONArray("entries")
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            assertEquals(entry.toString(), 0, entry.getInt("errors"))
            assertTrue(entry.toString(), entry.getLong("skipped") > 0L)
        }
    }

    @Test fun nativeTaskRecordsSkippedItemsWithDetailFile() {
        val repository = HistoryRepository(folder.newFolder("module"), folder.root)
        val input = JSONObject().put("mode", "snapshot-clean").put("success", true).put("bytes", 10).put("files", 1)
            .put("errors", 0).put("protectedCandidates", 1).put("skippedCandidates", 1)
            .put("detailItems", JSONArray().put(JSONObject().put("kind", "skipped").put("reason", "protected")
                .put("category", "安全项目").put("path", "/data/media/0/Keep/a.log").put("note", "白名单保护")))
        assertTrue(JSONObject(repository.recordNativeTaskJson(input.toString())).getBoolean("success"))
        val entry = JSONObject(repository.taskHistoryJson(10)).getJSONArray("entries").getJSONObject(0)
        assertEquals(0, entry.getInt("errors"))
        assertEquals(1L, entry.getLong("skipped"))
        val id = entry.getString("detailId")
        assertTrue(id, id.isNotBlank())
        val detail = JSONObject(RunDetailRepository(folder.root, QuarantineRepository(folder.root, File(folder.root, "config.conf")),
            WhitelistRepository(File(folder.root, "whitelist.conf"), File(folder.root, "whitelist.packages"), emptyList())).readJson(id))
        assertTrue(detail.toString(), detail.getBoolean("success"))
        assertEquals("/data/media/0/Keep/a.log", detail.getJSONArray("items").getJSONObject(0).getString("path"))
    }
}
