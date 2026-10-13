package io.github.xgl34222220.baize.root

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HistoryFailureDetailsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun parsesCategoryCountReasonAndSample() {
        val json = HistoryFailureDetails.toJson("规则清理|2|Permission denied|/data/media/0/a.log;深度清理|1|目录无法完整读取（代码 5）|")
        assertEquals(2, json.length())
        val first = json.getJSONObject(0)
        assertEquals("规则清理", first.getString("category"))
        assertEquals(2L, first.getLong("count"))
        assertEquals("Permission denied", first.getString("reason"))
        assertEquals("/data/media/0/a.log", first.getString("samplePath"))
        assertEquals("", json.getJSONObject(1).getString("samplePath"))
    }

    @Test fun malformedOrEmptyTokensAreIgnored() {
        assertEquals(0, HistoryFailureDetails.toJson("").length())
        assertEquals(0, HistoryFailureDetails.toJson("garbage;a|x|y").length())
    }

    @Test fun moduleHistoryRowWithFailureColumnReachesTheRecordAndKeepsOtherColumns() {
        val history = File(folder.root, "history.tsv")
        val row = listOf("2026-10-12 07:41:09", "rules-clean", "572416", "5", "0", "1",
            "任务未完成，已处理 5 项，失败 1 项，释放 559 KB", "scheduler:interval", "规则清理|572416|5", "",
            "", "", "规则清理|1|Permission denied|/data/media/0/tencent/x.log").joinToString("\t")
        history.writeText(row + "\n")
        val entry = JSONObject(HistoryRepository(folder.newFolder("module"), folder.root).taskHistoryJson(10))
            .getJSONArray("entries").getJSONObject(0)
        assertEquals(1, entry.getInt("errors"))
        assertEquals("", entry.getString("recordId"))
        val failure = entry.getJSONArray("failureDetails").getJSONObject(0)
        assertEquals("Permission denied", failure.getString("reason"))
        assertEquals("/data/media/0/tencent/x.log", failure.getString("samplePath"))
        // The audit timeline must not mistake the failure column for a record id.
        val audit = JSONObject(AuditRepository(folder.root).timelinePageJson(0, 10))
        val id = audit.getJSONArray("events").getJSONObject(0).getString("id")
        assertTrue(id, !id.contains("Permission"))
    }

    @Test fun oldTenColumnRowsHaveNoFailureDetails() {
        File(folder.root, "history.tsv").writeText("2026-10-12 07:39:06\trules-clean\t315392\t3\t2\t0\t规则清理完成\tscheduler:interval\t规则清理|315392|3\t\n")
        val entry = JSONObject(HistoryRepository(folder.newFolder("module"), folder.root).taskHistoryJson(10))
            .getJSONArray("entries").getJSONObject(0)
        assertEquals(0, entry.getJSONArray("failureDetails").length())
    }
}
