package io.github.xgl34222220.baize

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkbenchPresentationTest {
    private fun item(id: String, risk: String, app: String = "浏览器") = WorkbenchItem(id,
        "profile", "rules", "example.browser", app, "日志", "browser", app, id, risk,
        "/storage/emulated/0/Download/$id", 100, 1, 0, "", true)

    @Test fun filteringNeverHidesTheScopeOfTheExistingSelection() {
        val items = listOf(item("cache", "low"), item("log", "medium"), item("offline", "high"))
        val view = workbenchPresentation(items, setOf("cache", "log"), "medium", setOf("browser"), false)
        assertEquals(1, view.visibleCount)
        assertEquals(1, view.hiddenSelectedCount)
        assertEquals(200L, view.selectedBytes)
        assertEquals(setOf("log"), reviewRiskSelection(view.groups.single().items, setOf("low", "medium")))
        assertEquals(2, view.rows.size)
    }

    @Test fun searchMatchesAppPackagePathAndNameWithoutChangingSelection() {
        val items = listOf(item("report.log", "medium"), item("offline.zip", "high"))
        assertEquals(2, workbenchPresentation(items, emptySet(), "all", emptySet(), false, "EXAMPLE.browser").visibleCount)
        assertEquals(2, workbenchPresentation(items, emptySet(), "all", emptySet(), false, "浏览器").visibleCount)
        assertEquals(1, workbenchPresentation(items, setOf("offline.zip"), "medium", emptySet(), false, "report").visibleCount)
        assertEquals(0, workbenchPresentation(items, emptySet(), "low", emptySet(), false, "Download").visibleCount)
    }

    @Test fun partialCoverageCannotBecomeACleanDeviceClaim() {
        val cache = JSONObject().put("complete", false).put("totalRoots", 6).put("scannedRoots", 4).put("incompleteRoots", 2)
        val result = workbenchScanCoverage(cache, JSONObject(), true, true)
        assertTrue(result.incomplete)
        assertTrue(result.summary.contains("4 / 6"))
        assertTrue(result.summary.contains("2 处未完成"))
        assertTrue(result.summary.contains("不代表没有垃圾"))
    }

    @Test fun legacyCountersRemainUnknownAndNonCacheProfilesDoNotWarnAboutCache() {
        val legacy = workbenchScanCoverage(JSONObject(), JSONObject(), true, true)
        assertFalse(legacy.incomplete)
        assertFalse(legacy.summary.contains("0 / 0"))
        val rulesOnly = workbenchScanCoverage(null, JSONObject().put("partial", true), false, true)
        assertTrue(rulesOnly.incomplete)
        assertFalse(rulesOnly.summary.contains("缓存"))
    }
}
