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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NativeProfileRiskSelectionTest {
    @get:Rule val folder = TemporaryFolder()

    private val engine by lazy {
        NativeProfileEngine(RuntimeEnvironment.getApplication(), AtomicBoolean(false), ruleDirectory = folder.root)
    }

    @Test fun explicitMediumChoiceCleansEvenWhenAutomaticSelectionIsLowOnly() {
        val medium = target("medium", "medium")
        val snapshot = snapshot(listOf(medium), "low")
        val automatic = clean(snapshot, JSONObject().put("__all_safe__", true))
        assertEquals("empty_selection", automatic.getString("error"))
        assertTrue(medium.file.exists())

        val explicit = clean(snapshot, selection(medium), JSONObject().put("maxAutoRisk", "low"))
        assertEquals(1, explicit.getInt("selected"))
        assertEquals(1, explicit.getInt("cleanedCandidates"))
        assertEquals(1L, explicit.getLong("deletedDirectories"))
        assertFalse(medium.file.exists())
    }

    @Test fun bulkSelectionKeepsTheSnapshotCeilingAndNeverIncludesHighRisk() {
        val low = target("low", "low")
        val medium = target("medium", "medium")
        val high = target("high", "high")
        val result = clean(
            snapshot(listOf(low, medium, high), "low"),
            JSONObject().put("__all_safe__", true),
            // Even an explicitly authorized high-risk operation must not widen bulk selection.
            JSONObject().put("allowHighRisk", true).put("maxAutoRisk", "medium")
        )
        assertEquals(1, result.getInt("selected"))
        assertFalse(low.file.exists())
        assertTrue(medium.file.exists())
        assertTrue(high.file.exists())
    }

    @Test fun highRiskRequiresBothExplicitSelectionAndAuthorizationForThisOperation() {
        val high = target("high", "high")
        val denied = clean(snapshot(listOf(high)), selection(high))
        assertEquals(1, denied.getInt("skippedCandidates"))
        assertEquals("高风险清理未启用", denied.getJSONArray("details").getJSONObject(0).getString("reason"))
        assertTrue(high.file.exists())

        val confirmed = clean(snapshot(listOf(high)), selection(high), JSONObject().put("allowHighRisk", true))
        assertEquals(1, confirmed.getInt("cleanedCandidates"))
        assertFalse(high.file.exists())

        val next = target("next-high", "high")
        val nextOperation = clean(snapshot(listOf(next)), selection(next))
        assertEquals(1, nextOperation.getInt("skippedCandidates"))
        assertTrue(next.file.exists())
    }

    @Test fun criticalAndNewlyWhitelistedTargetsRemainProtectedAfterExplicitConfirmation() {
        val critical = target("critical", "critical")
        val pathProtected = target("path-protected", "medium")
        val appProtected = target("app-protected", "high", "com.example.protected")
        val targets = listOf(critical, pathProtected, appProtected)
        val snapshot = snapshot(targets)
        // Protection added after scanning must still apply at deletion time.
        val options = JSONObject().put("allowHighRisk", true)
            .put("whitelistPaths", JSONArray().put(pathProtected.file.path))
            .put("whitelistPackages", JSONArray().put("com.example.protected"))
        val result = clean(snapshot, selection(*targets.toTypedArray()), options)
        assertEquals(3, result.getInt("skippedCandidates"))
        assertEquals(0, result.getInt("cleanedCandidates"))
        assertEquals(0L, result.getLong("deletedDirectories"))
        targets.forEach { assertTrue(it.file.exists()) }
        val details = result.getJSONArray("details")
        assertEquals("关键风险只允许审计", details.getJSONObject(0).getString("reason"))
        assertEquals("白名单保护", details.getJSONObject(1).getString("reason"))
        assertEquals("白名单保护", details.getJSONObject(2).getString("reason"))
    }

    @Test fun manualConfirmationDoesNotBypassAChangedTargetOrAuthorizeANewPath() {
        val medium = target("medium", "medium")
        val outside = target("outside", "low")
        val snapshot = snapshot(listOf(medium), "low")
        val forged = clean(snapshot, selection(outside), JSONObject().put("allowHighRisk", true))
        assertEquals("empty_selection", forged.getString("error"))
        assertTrue(outside.file.exists())

        val newData = File(medium.file, "keep.txt").apply { writeText("new content") }
        val changed = clean(snapshot, selection(medium), JSONObject().put("allowHighRisk", true))
        assertEquals(1, changed.getInt("skippedCandidates"))
        assertEquals("目标不再符合扫描条件", changed.getJSONArray("details").getJSONObject(0).getString("reason"))
        assertEquals("new content", newData.readText())
    }

    @Test fun retainedSnapshotRechecksModificationTimeBeforeActualDeletion() {
        fun retainedFile(name: String) = Target(folder.newFile(name), "medium", retentionDays = 7).also {
            assertTrue(it.file.setLastModified(System.currentTimeMillis() - 10 * 86_400_000L))
        }
        val unchanged = retainedFile("unchanged.tmp")
        val refreshed = retainedFile("refreshed.tmp")
        val snapshot = snapshot(listOf(unchanged, refreshed))
        // Still an empty file, but it was recreated/updated after scanning and now needs retaining.
        refreshed.file.writeText("")
        assertTrue(refreshed.file.setLastModified(System.currentTimeMillis()))
        val result = clean(snapshot, selection(unchanged, refreshed), JSONObject().put("retentionDays", 0))
        assertEquals(1, result.getInt("cleanedCandidates"))
        assertEquals(1, result.getInt("skippedCandidates"))
        assertEquals(1L, result.getLong("deletedFiles"))
        assertFalse(unchanged.file.exists())
        assertTrue(refreshed.file.exists())
        assertEquals("目标不再符合扫描条件", result.getJSONArray("details").getJSONObject(1).getString("reason"))
    }

    @Test fun realRuleScanPersistsRetentionAndKeepsARewrittenFile() {
        val rules = folder.newFolder("rules")
        val log = folder.newFile("old.log").apply {
            writeText("old log")
            assertTrue(setLastModified(System.currentTimeMillis() - 10 * 86_400_000L))
        }
        File(rules, "custom.rules").writeText("${log.path}|7\n")
        val scanner = NativeProfileEngine(RuntimeEnvironment.getApplication(), AtomicBoolean(false),
            ruleDirectory = rules, sharedRootOverride = emptyList())
        val scan = JSONObject(scanner.scan("rules", "{}") {})
        val snapshot = scan.getString("snapshotId")
        val items = JSONObject(scanner.page(snapshot, 0, 20)).getJSONArray("items")
        assertEquals(1, items.length())
        val item = items.getJSONObject(0)
        assertEquals(7, item.getInt("retentionDays"))
        assertEquals(log.canonicalPath, item.getString("path"))

        log.writeText("new log must be retained")
        assertTrue(log.setLastModified(System.currentTimeMillis()))
        val result = JSONObject(scanner.clean(snapshot, JSONObject().put(item.getString("id"), true).toString(), "{}") {})
        assertEquals(1, result.getInt("skippedCandidates"))
        assertEquals("new log must be retained", log.readText())
    }

    @Test fun deviceEncryptedUserDirectoriesUseTheSameMutationBoundaryAsOrdinaryUserDirectories() {
        val method = NativeProfileEngine::class.java.declaredMethods.single { it.name == "mutationRoot" }
            .apply { isAccessible = true }
        for (store in listOf("user", "user_de")) {
            assertEquals(true, method.invoke(engine, "/data/$store/0/com.example.app/files/logs"))
            assertEquals(true, method.invoke(engine, "/data/$store/10/com.example.app/files/logs"))
            assertEquals(false, method.invoke(engine, "/data/$store"))
            assertEquals(false, method.invoke(engine, "/data/${store}_backup/0/com.example.app/files/logs"))
        }
    }

    private data class Target(val file: File, val risk: String, val packageName: String = "", val retentionDays: Int = 0) {
        val id: String get() = "empty:${file.canonicalPath}"
    }

    private fun target(name: String, risk: String, packageName: String = "") =
        Target(folder.newFolder(name), risk, packageName)

    private fun selection(vararg targets: Target) = JSONObject().apply {
        targets.forEach { put(it.id, true) }
    }

    private fun clean(snapshot: String, selection: JSONObject, options: JSONObject = JSONObject()) =
        JSONObject(engine.clean(snapshot, selection.toString(), options.toString()) {})

    /**
     * Inject only a server-side snapshot, then exercise the real clean/validate/delete path.
     * Empty-directory candidates let host tests verify actual mutation without writing to Android
     * storage roots or relaxing the production path boundary for rules and deep cleaning.
     */
    private fun snapshot(targets: List<Target>, automaticRisk: String = "medium"): String {
        fun method(name: String) = NativeProfileEngine::class.java.declaredMethods
            .single { it.name == name }.apply { isAccessible = true }
        val options = method("parseOptions").invoke(engine, JSONObject().put("maxAutoRisk", automaticRisk).toString())
        val candidates = targets.map { target ->
            method("candidate").invoke(
                engine, "empty", if (target.file.isFile) "empty_file" else "empty_dir", "空项目", target.risk, target.file,
                target.packageName, "", true, "", null, target.retentionDays
            )
        }.toMutableList()
        val id = UUID.randomUUID().toString()
        val snapshotClass = NativeProfileEngine::class.java.declaredClasses.single { it.simpleName == "Snapshot" }
        val constructor = snapshotClass.declaredConstructors.single { it.parameterCount == 7 }.apply { isAccessible = true }
        val snapshot = constructor.newInstance(id, "empty", System.currentTimeMillis(), "", options, candidates, 0L)
        val field = NativeProfileEngine::class.java.getDeclaredField("snapshots").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(engine) as MutableMap<String, Any>)[id] = snapshot
        return id
    }
}
