package io.github.xgl34222220.baize

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class LastCleanupStoreTest {
    @Test fun scanEstimatesAndWorkbenchReportsCannotOverwriteLastCleanup() {
        for (mode in listOf("", "scan", "cache-scan", "deep-scan", "apk-scan", "workbench-clean")) {
            assertFalse(mode, LastCleanupStore.acceptsModuleDetails(mode))
        }
        for (mode in listOf("clean", "cache-clean", "deep-clean", "apk-clean", "apk-auto")) {
            assertTrue(mode, LastCleanupStore.acceptsModuleDetails(mode))
        }
    }

    @Test fun aNewResultReplacesBothListsWithoutKeepingUnrelatedOldItems() {
        val app = AppJunkUiItem("com.example.app", "测试应用", "日志", 1, 64, 0, emptyList())
        val oldJunk = GeneralJunkUiItem("旧日志", 1, 64, 0, "")
        val newJunk = GeneralJunkUiItem("新日志", 2, 128, 0, "")
        val previous = listOf(app) to listOf(oldJunk)
        assertEquals(emptyList<AppJunkUiItem>() to listOf(newJunk),
            LastCleanupStore.mergeModuleDetails(previous, emptyList(), listOf(newJunk)))
        assertEquals(listOf(app) to emptyList<GeneralJunkUiItem>(),
            LastCleanupStore.mergeModuleDetails(previous, listOf(app), emptyList()))
        assertEquals(previous, LastCleanupStore.mergeModuleDetails(previous, emptyList(), emptyList()))
    }

    @Test fun reopeningRetainsApplicationIdentityAndActualCategoryCounts() {
        val apps = listOf(AppJunkUiItem("com.example.app", "测试应用", "日志", 3, 1048576, 1,
            listOf(AppJunkCategoryUiItem("日志", 3, 1048576, 1, "/data/user/0/com.example.app/files/logs"))))
        val junk = listOf(GeneralJunkUiItem("系统日志", 2, 128, 0, "/data/anr/example"))
        val restored = LastCleanupStore.decode(JSONObject(LastCleanupStore.encode(apps, junk).toString()))
        assertEquals(apps, restored.first)
        assertEquals(junk, restored.second)
        assertEquals(emptyList<AppJunkUiItem>(), LastCleanupStore.decode(JSONObject()).first)
    }

    @Test fun runIdentityAndDeletionEvidenceSurviveReopenAndLegacyRecordsStayUnlinked() {
        val apps = listOf(AppJunkUiItem("com.example.app", "测试应用", "app_bugly", 19, 22_440_000))
        val run = LastCleanupStore.decodeRun(JSONObject(
            LastCleanupStore.encode(apps, emptyList(), "audit-123", deletedEvidence = true).toString()))
        assertEquals(LastCleanupStore.Run(apps, emptyList(), "audit-123", true), run)
        val legacy = LastCleanupStore.decodeRun(JSONObject(LastCleanupStore.encode(apps, emptyList()).toString()))
        assertEquals("", legacy.recordId)
        assertFalse(legacy.deletedEvidence)
        val old = LastCleanupStore.decodeRun(JSONObject().put("apps", org.json.JSONArray()))
        assertEquals("", old.recordId)
        assertFalse(old.deletedEvidence)
    }
}
