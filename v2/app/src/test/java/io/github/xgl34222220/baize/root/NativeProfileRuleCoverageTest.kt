package io.github.xgl34222220.baize.root

import android.app.Application
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
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NativeProfileRuleCoverageTest {
    @get:Rule val folder = TemporaryFolder()

    private fun config(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "config") }.first { File(it, "app.rules").isFile }

    private fun file(root: File, path: String, age: Int = 0): File = File(root, path).apply {
        parentFile!!.mkdirs()
        writeText("sample")
        if (age > 0) setLastModified(System.currentTimeMillis() - age * 86_400_000L - 2_000L)
    }

    private fun scan(engine: NativeProfileEngine, profile: String = "rules"): Pair<String, List<JSONObject>> {
        val scan = JSONObject(engine.scan(profile, JSONObject().put("includeReviewRules", true).toString()) {})
        assertTrue(scan.toString(), scan.getBoolean("success"))
        val result = mutableListOf<JSONObject>()
        for (offset in 0 until scan.getInt("totalCandidates") step 50) {
            val page = JSONObject(engine.page(scan.getString("snapshotId"), offset, 50)).getJSONArray("items")
            for (index in 0 until page.length()) result += page.getJSONObject(index)
        }
        return scan.getString("snapshotId") to result
    }

    private fun items(engine: NativeProfileEngine, profile: String = "rules") = scan(engine, profile).second

    /** Exercise the actual deletion implementation with candidates from a real scan. The host
     * fixtures are outside Android roots, so this does not weaken the production mutation gate. */
    private fun deleteSnapshotCandidates(engine: NativeProfileEngine, snapshotId: String, ids: Set<String>) {
        fun method(name: String) = NativeProfileEngine::class.java.declaredMethods.single { it.name == name }
            .apply { isAccessible = true }
        val field = NativeProfileEngine::class.java.getDeclaredField("snapshots").apply { isAccessible = true }
        val snapshot = (field.get(engine) as Map<*, *>)[snapshotId]!!
        val candidates = snapshot.javaClass.getDeclaredField("candidates").apply { isAccessible = true }.get(snapshot) as List<*>
        val policy = method("hiddenRules").invoke(engine)
        for (candidate in candidates.filterNotNull()) {
            fun value(name: String) = candidate.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(candidate) as String
            if (value("id") !in ids) continue
            method("deleteCandidate").invoke(engine, candidate, File(value("path")), Long.MAX_VALUE,
                emptySet<String>(), Long.MAX_VALUE, policy)
        }
    }

    @Test fun shippedPackageRulesReachInternalDeviceProtectedExternalAndWebViewTargets() {
        val rules = folder.newFolder("rules")
        for (name in listOf("app.rules", "external.rules")) File(config(), name).copyTo(File(rules, name))
        val data = folder.newFolder("data")
        val expected = listOf(
            file(data, "user/0/com.coolapk.market/files/log/entry"),
            file(data, "user/10/com.coolapk.market/files/log/entry"),
            file(data, "user_de/10/com.coolapk.market/files/log/entry"),
            file(data, "media/10/Android/data/com.coolapk.market/files/log/entry"),
            file(data, "user/0/com.example.app/app_webview_remote/Default/Code Cache/js"),
            file(data, "user_de/10/com.example.app/app_hws_webview7/GPUCache/gpu")
        ).map { it.parentFile!!.canonicalPath }.toSet()
        val cookies = file(data, "user/0/com.example.app/app_webview_remote/Default/Cookies")
        val localStorage = file(data, "user_de/10/com.example.app/app_hws_webview7/Local Storage/session")
        val engine = NativeProfileEngine(RuntimeEnvironment.getApplication(), AtomicBoolean(false),
            ruleDirectory = rules,
            ruleRoots = ReviewRuleCatalog.Roots(data.path, listOf("${data.path}/media/*/Android/data")),
            sharedRootOverride = emptyList())
        val result = items(engine)
        assertEquals(expected, result.map { it.getString("path") }.toSet())
        assertTrue(result.all { it.getString("risk") == "medium" })
        assertTrue(result.all { it.getString("blockedReason").isBlank() })
        assertTrue(cookies.exists())
        assertTrue(localStorage.exists())
    }

    @Test fun invalidRelativeRulesAndEscapingIntermediateSymlinksNeverBecomeCandidates() {
        val rules = folder.newFolder("rules")
        val data = folder.newFolder("data")
        val base = File(data, "user/0/com.example.app").apply { mkdirs() }
        val outside = file(data, "user/0/com.other.app/files/log/keep")
        Files.createSymbolicLink(File(base, "redirect").toPath(), outside.parentFile!!.parentFile!!.toPath())
        File(rules, "app.rules").writeText("""
            com.example.app|redirect/log|0
            com.example.app|../com.other.app/files/log|0
            com.example.app|files//log|0
            com.example.app|files/*|0
            com.example.app|files/log|366
        """.trimIndent())
        val engine = NativeProfileEngine(RuntimeEnvironment.getApplication(), AtomicBoolean(false),
            ruleDirectory = rules, ruleRoots = ReviewRuleCatalog.Roots(data.path, emptyList()), sharedRootOverride = emptyList())
        assertTrue(items(engine).isEmpty())
        assertEquals("sample", outside.readText())
    }

    @Test fun hiddenCatalogIncludesMetadataAndThumbnailsButRetainsRecentRecycleAndLogs() {
        val rules = folder.newFolder("rules")
        File(config(), "hidden.rules").copyTo(File(rules, "hidden.rules"))
        val storage = folder.newFolder("storage")
        val thumbnail = file(storage, "Pictures/.thumbnails/preview")
        val metadata = file(storage, "ordinary/.DS_Store")
        val log = file(storage, "ordinary/.logs/old.log", 8)
        val recentLog = file(storage, "ordinary/.logs/recent.tmp", 1)
        val oldTrash = file(storage, "ordinary/.Trash/old.tmp", 31)
        val recentTrash = file(storage, "ordinary/.Trash/recent.tmp", 8)
        val document = file(storage, "Documents/do-not-touch.tmp", 31)
        val marker = file(storage, "ordinary/.logs/.nomedia", 31)
        val engine = NativeProfileEngine(RuntimeEnvironment.getApplication(), AtomicBoolean(false),
            ruleDirectory = rules, ruleRoots = ReviewRuleCatalog.Roots(folder.newFolder("empty-data").path, emptyList()),
            sharedRootOverride = listOf(storage))
        val result = items(engine, "safe")
        val paths = result.map { it.getString("path") }.toSet()
        assertTrue(paths.containsAll(setOf(thumbnail.parentFile!!.canonicalPath, metadata.canonicalPath, log.canonicalPath, oldTrash.canonicalPath)))
        for (keep in listOf(recentLog, recentTrash, document, marker)) assertFalse(keep.path, paths.contains(keep.canonicalPath))
        assertFalse(paths.contains(oldTrash.parentFile!!.canonicalPath))
        assertEquals(30, result.single { it.getString("path") == oldTrash.canonicalPath }.getInt("retentionDays"))
        assertEquals(7, result.single { it.getString("path") == log.canonicalPath }.getInt("retentionDays"))
    }

    @Test fun nonzeroPackageRetentionAppliesPerFileAndDoesNotHideOldSiblings() {
        val rules = folder.newFolder("rules")
        File(rules, "app.rules").writeText("com.example.app|files/log|1\n")
        val data = folder.newFolder("data")
        val old = file(data, "user/0/com.example.app/files/log/old", 1)
        val recent = file(data, "user/0/com.example.app/files/log/recent")
        val engine = NativeProfileEngine(RuntimeEnvironment.getApplication(), AtomicBoolean(false),
            ruleDirectory = rules, ruleRoots = ReviewRuleCatalog.Roots(data.path, emptyList()), sharedRootOverride = emptyList())
        val result = items(engine)
        assertEquals(setOf(old.canonicalPath), result.map { it.getString("path") }.toSet())
        assertEquals(1, result.single().getInt("retentionDays"))
        assertTrue(recent.exists())
    }

    @Test fun reviewCatalogSpellsPushLogsCorrectlyAndCoversDeviceProtectedStorage() {
        val catalog = ReviewRuleCatalog.reviewRules(File(config(), "review.rules"))
        val paths = catalog.map { it.pattern }.toSet()
        assertTrue("/data/user/*/*/files/MiPushLog" in paths)
        assertTrue("/data/user/*/*/files/mipushlog" in paths)
        assertTrue("/data/user_de/*/*/files/.com.google.firebase.crashlytics.files.v2*" in paths)
        assertTrue("/storage/emulated/*/Android/data/*/files/log" in paths)
        assertTrue(catalog.all { it.risk == "medium" })
        assertFalse(paths.any { it.endsWith("/files") || it.contains("/Download") || it.contains("/databases") })
    }

    @Test fun legacyPrimaryUserSymlinkStillScansAndCleansPackageAndWebViewCaches() {
        val rules = folder.newFolder("rules")
        File(config(), "app.rules").copyTo(File(rules, "app.rules"))
        val data = folder.newFolder("data")
        val log = file(data, "data/com.coolapk.market/files/log/entry")
        val cache = file(data, "data/com.example.app/app_webview_remote/Default/Code Cache/js")
        val cookies = file(data, "data/com.example.app/app_webview_remote/Default/Cookies")
        File(data, "user").mkdirs()
        Files.createSymbolicLink(File(data, "user/0").toPath(), File(data, "data").toPath())
        val engine = NativeProfileEngine(RuntimeEnvironment.getApplication(), AtomicBoolean(false),
            ruleDirectory = rules, ruleRoots = ReviewRuleCatalog.Roots(data.path, emptyList()), sharedRootOverride = emptyList())
        val (id, found) = scan(engine)
        assertEquals(setOf(log.parentFile!!.canonicalPath, cache.parentFile!!.canonicalPath), found.map { it.getString("path") }.toSet())
        deleteSnapshotCandidates(engine, id, found.map { it.getString("id") }.toSet())
        assertFalse(log.exists())
        assertFalse(cache.exists())
        assertEquals("sample", cookies.readText())
    }

    @Test fun nestedHiddenCandidatesAreFilesAndKeepYoungTrashInBothNestingOrders() {
        val rules = folder.newFolder("rules")
        File(config(), "hidden.rules").copyTo(File(rules, "hidden.rules"))
        val storage = folder.newFolder("storage")
        val old = listOf(file(storage, ".Trash/.cache/old.tmp", 31), file(storage, ".cache/.Trash/old.tmp", 31))
        val recent = listOf(file(storage, ".Trash/.cache/recent.tmp", 8), file(storage, ".cache/.Trash/recent.tmp", 8))
        val normalCache = file(storage, ".cache/image")
        val engine = NativeProfileEngine(RuntimeEnvironment.getApplication(), AtomicBoolean(false),
            ruleDirectory = rules, ruleRoots = ReviewRuleCatalog.Roots(folder.newFolder("data").path, emptyList()),
            sharedRootOverride = listOf(storage))
        val (id, found) = scan(engine, "safe")
        assertEquals((old + normalCache).map { it.canonicalPath }.toSet(), found.map { it.getString("path") }.toSet())
        assertTrue(found.all { File(it.getString("path")).isFile })
        deleteSnapshotCandidates(engine, id, found.map { it.getString("id") }.toSet())
        (old + normalCache).forEach { assertFalse(it.exists()) }
        recent.forEach { assertEquals("sample", it.readText()) }
    }

    @Test fun broadDirectoryRuleCannotBypassNestedHiddenRetentionOrDeleteMarkers() {
        val rules = folder.newFolder("rules")
        File(config(), "hidden.rules").copyTo(File(rules, "hidden.rules"))
        val broad = folder.newFolder("broad")
        File(rules, "custom.rules").writeText("${broad.path}|0\n")
        val old = listOf(file(broad, ".Trash/.cache/old.tmp", 31), file(broad, ".cache/.Trash/old.tmp", 31))
        val recent = listOf(file(broad, ".Trash/.cache/recent.tmp", 8), file(broad, ".cache/.Trash/recent.tmp", 8))
        val rewritten = file(broad, ".Trash/rewritten.tmp", 31)
        val marker = file(broad, ".cache/.nomedia")
        val engine = NativeProfileEngine(RuntimeEnvironment.getApplication(), AtomicBoolean(false),
            ruleDirectory = rules, ruleRoots = ReviewRuleCatalog.Roots(folder.newFolder("data").path, emptyList()), sharedRootOverride = emptyList())
        val (id, found) = scan(engine)
        assertEquals(listOf(broad.canonicalPath), found.map { it.getString("path") })
        rewritten.writeText("new content")
        rewritten.setLastModified(System.currentTimeMillis())
        deleteSnapshotCandidates(engine, id, found.map { it.getString("id") }.toSet())
        old.forEach { assertFalse(it.exists()) }
        recent.forEach { assertEquals("sample", it.readText()) }
        assertEquals("new content", rewritten.readText())
        assertTrue(marker.exists())
        assertTrue(broad.isDirectory)
    }

    @Test fun explicitZeroDayFileRuleStillRetainsYoungHiddenContent() {
        val rules = folder.newFolder("rules")
        File(config(), "hidden.rules").copyTo(File(rules, "hidden.rules"))
        val target = file(folder.newFolder("public"), ".Trash/.cache/recent.tmp", 8)
        File(rules, "custom.rules").writeText("${target.path}|0\n")
        val engine = NativeProfileEngine(RuntimeEnvironment.getApplication(), AtomicBoolean(false),
            ruleDirectory = rules, ruleRoots = ReviewRuleCatalog.Roots(folder.newFolder("data").path, emptyList()), sharedRootOverride = emptyList())
        val (id, found) = scan(engine)
        assertEquals(listOf(target.canonicalPath), found.map { it.getString("path") })
        deleteSnapshotCandidates(engine, id, found.map { it.getString("id") }.toSet())
        assertEquals("sample", target.readText())
    }

    @Test fun exactPathsRejectIntermediateSymlinksInLegacyAndCustomRules() {
        val rules = folder.newFolder("rules")
        val data = folder.newFolder("data")
        val base = File(data, "data/com.example.app").apply { mkdirs() }
        val outside = file(data, "data/com.other.app/files/log/keep")
        Files.createSymbolicLink(File(base, "redirect").toPath(), outside.parentFile!!.parentFile!!.toPath())
        File(rules, "app.rules").writeText("com.example.app|redirect/log|0\n")
        File(rules, "custom.rules").writeText("${base.path}/redirect/log|0\n${base.path}/missing/log|0\n")
        val engine = NativeProfileEngine(RuntimeEnvironment.getApplication(), AtomicBoolean(false),
            ruleDirectory = rules, ruleRoots = ReviewRuleCatalog.Roots(data.path, emptyList()), sharedRootOverride = emptyList())
        assertTrue(engine.expand("${base.path}/redirect/log").isEmpty())
        assertTrue(items(engine).isEmpty())
        assertEquals("sample", outside.readText())
        File(rules, "custom.rules").writeText("${base.path}/logs/|7\n/|7\n${base.path}/../other/|7\n")
        val custom = ReviewRuleCatalog.customRules(File(rules, "custom.rules"))
        assertEquals(listOf("${base.path}/logs"), custom.map { it.pattern })
        assertEquals(7, custom.single().days)
    }
}
