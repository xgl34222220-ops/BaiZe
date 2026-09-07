package io.github.xgl34222220.baize.root

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ManualReviewEngineTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun protectedCandidatesRemainVisibleAndCannotBeDeletedByExplicitSelection() {
        val rules = folder.newFolder("rules")
        val critical = folder.newFolder("databases")
        val keep = File(critical, "account.db").apply { writeText("keep") }
        val logs = folder.newFolder("logs")
        val log = File(logs, "event.txt").apply { writeText("log") }
        File(rules, "deep.rules").writeText("${critical.path}\n${logs.path}\n")
        val engine = NativeProfileEngine(RuntimeEnvironment.getApplication(), AtomicBoolean(false), ruleDirectory = rules)
        val options = JSONObject().put("allowHighRisk", true)
            .put("whitelistPaths", JSONArray().put(logs.path)).toString()
        val scan = JSONObject(engine.scan("deep", options) {})
        assertTrue(scan.optBoolean("success"))
        val page = JSONObject(engine.page(scan.getString("snapshotId"), 0, 20)).getJSONArray("items")
        assertEquals(2, page.length())
        val selected = JSONObject()
        for (i in 0 until page.length()) {
            val item = page.getJSONObject(i)
            assertTrue(item.getString("blockedReason").isNotBlank())
            selected.put(item.getString("id"), true)
        }
        val result = JSONObject(engine.clean(scan.getString("snapshotId"), selected.toString(), options) {})
        assertEquals(0, result.getInt("cleanedCandidates"))
        assertEquals(2, result.getInt("skippedCandidates"))
        assertEquals("keep", keep.readText())
        assertEquals("log", log.readText())
    }

    @Test fun nextScanRefreshesWildcardListingsAndKeepsFileSizes() {
        val rules = folder.newFolder("rules")
        val targets = folder.newFolder("targets")
        val first = File(targets, "first.tmp").apply { writeText("abc") }
        File(rules, "deep.rules").writeText("${targets.path}/*.tmp\n")
        val engine = NativeProfileEngine(RuntimeEnvironment.getApplication(), AtomicBoolean(false), ruleDirectory = rules)
        val scan = JSONObject(engine.scan("deep", "{}") {})
        val page = JSONObject(engine.page(scan.getString("snapshotId"), 0, 20)).getJSONArray("items")
        assertEquals(1, page.length())
        assertEquals(first.length(), page.getJSONObject(0).getLong("bytes"))
        File(targets, "second.tmp").writeText("more")
        val next = JSONObject(engine.scan("deep", "{}") {})
        assertEquals(2, next.getInt("totalCandidates"))
    }
}
