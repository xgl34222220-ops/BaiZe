package io.github.xgl34222220.baize.root

import android.app.Application
import android.os.SystemClock
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
class NativeProfileTraversalTest {
    @get:Rule val folder = TemporaryFolder()

    private fun engine(cancelled: AtomicBoolean = AtomicBoolean(false), rules: File = folder.root) =
        NativeProfileEngine(RuntimeEnvironment.getApplication(), cancelled, ruleDirectory = rules)

    @Test fun protectedTreesExposeOnlyDescendantShellsAndKeepOrdinaryCoverage() {
        val root = folder.newFolder("storage")
        fun dir(path: String) = File(root, path).apply { mkdirs() }
        fun file(path: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText("") }
        dir("ordinary/empty")
        file("ordinary/zero.tmp")
        file("ordinary/.keep")
        dir(".cache/empty")
        file(".cache/old.tmp")
        for (protected in listOf("Download", "Android", ".git")) {
            dir("$protected/empty")
            dir("$protected/nonempty/nested")
            file("$protected/nonempty/zero.tmp")
            file("$protected/ignored.tmp")
        }
        val external = folder.newFolder("external")
        File(external, "never.tmp").writeText("")
        Files.createSymbolicLink(File(root, "linked").toPath(), external.toPath())
        Files.createSymbolicLink(File(root, "Download/link").toPath(), external.toPath())
        Files.createSymbolicLink(File(root, "dangling").toPath(), File(root, "absent").toPath())

        val pre = mutableSetOf<String>()
        val post = mutableSetOf<String>()
        val empty = mutableSetOf<String>()
        engine().walk(root, 9, Long.MAX_VALUE, true) { entry, isPost ->
            val path = entry.file.relativeTo(root).path
            if (isPost) {
                post += path
                if (entry.emptyDirectory) empty += path
            } else pre += path
        }
        assertEquals(setOf("", "ordinary", "ordinary/empty", "ordinary/zero.tmp", "ordinary/.keep",
            ".cache", ".cache/empty", ".cache/old.tmp", "Download", "Android", ".git"), pre)
        val shellPosts = listOf("Download", "Android", ".git").flatMap {
            listOf("$it/empty", "$it/nonempty", "$it/nonempty/nested")
        }.toSet()
        assertEquals(setOf("", "ordinary", "ordinary/empty", ".cache", ".cache/empty") + shellPosts, post)
        assertEquals(setOf("ordinary/empty", ".cache/empty") + listOf("Download", "Android", ".git").flatMap {
            listOf("$it/empty", "$it/nonempty/nested")
        }, empty)
        assertTrue(File(external, "never.tmp").exists())
    }

    @Test fun depthBoundaryRetainsProtectedShellPostsButNotOrdinaryDirectoryPosts() {
        val root = folder.newFolder("storage")
        File(root, "ordinary/boundary/deeper").mkdirs()
        File(root, "Download/empty").mkdirs()
        File(root, "Download/full/deeper").mkdirs()
        val visits = mutableSetOf<String>()
        val empty = mutableSetOf<String>()
        engine().walk(root, 2, Long.MAX_VALUE, true) { entry, post ->
            val path = entry.file.relativeTo(root).path
            visits += "$post:$path"
            if (post && entry.emptyDirectory) empty += path
        }
        assertEquals(setOf("false:", "true:", "false:ordinary", "true:ordinary", "false:ordinary/boundary",
            "false:Download", "true:Download/empty", "true:Download/full"), visits)
        assertEquals(setOf("Download/empty"), empty)
    }

    @Test fun unprunedLogWalkStillVisitsFilesInsideProtectedNames() {
        val root = folder.newFolder("logs")
        val nested = File(root, "Download/old.tmp").apply { parentFile!!.mkdirs(); writeText("old") }
        val visited = mutableSetOf<File>()
        engine().walk(root, 9, Long.MAX_VALUE, false) { entry, post -> if (!post && entry.isFile) visited += entry.file }
        assertEquals(setOf(nested), visited)
    }

    @Test fun failedListingsAreNotEmptyAndSymlinkRootsAreNotTraversed() {
        val root = folder.newFolder("unreadable")
        val unreadable = object : File(root.path) {
            override fun listFiles(): Array<File>? = null
        }
        var postSeen = false
        engine().walk(unreadable, 9, Long.MAX_VALUE, true) { entry, post ->
            if (post) {
                postSeen = true
                assertFalse(entry.emptyDirectory)
            }
        }
        assertTrue(postSeen)
        val link = File(folder.root, "root-link")
        Files.createSymbolicLink(link.toPath(), root.toPath())
        engine().walk(link, 9, Long.MAX_VALUE, true) { _, _ -> fail("symlink root was visited") }
    }

    @Test fun cancellationAndDeadlineDoNotEmitPendingPostVisits() {
        val root = folder.newFolder("storage")
        File(root, "Download/empty").mkdirs()
        val cancelled = AtomicBoolean(false)
        val engine = engine(cancelled)
        var visits = 0
        engine.walk(root, 9, Long.MAX_VALUE, true) { _, _ -> visits++; cancelled.set(true) }
        assertEquals(1, visits)
        cancelled.set(false)
        engine.walk(root, 9, SystemClock.elapsedRealtime(), true) { _, _ -> fail("expired walk was visited") }
        engine.walk(root, 9, Long.MAX_VALUE, true) { entry, _ ->
            if (entry.file.name == "Download") cancelled.set(true)
            assertNotEquals("empty", entry.file.name)
        }
    }

    @Test fun syntheticWalkListsEachDirectoryOnceAndReusesMetadataAcrossVisitors() {
        val root = folder.newFolder("synthetic")
        repeat(24) { directory ->
            val branch = File(root, "branch$directory").apply { mkdirs() }
            File(branch, "empty").mkdir()
            repeat(12) { File(branch, "file$it.tmp").writeText("123") }
        }
        val counts = Counts()
        var files = 0
        var empty = 0
        engine().walk(CountingFile(root, counts), 9, Long.MAX_VALUE, true) { entry, post ->
            // Multiple profile consumers must share the same per-node observations.
            repeat(3) { entry.path; entry.isDirectory; entry.isFile; if (entry.isFile) entry.length }
            if (!post && entry.isFile) files++
            if (post && entry.emptyDirectory) empty++
        }
        assertEquals(288, files)
        assertEquals(24, empty)
        assertEquals(49, counts.listings.values.sum())
        assertTrue(counts.listings.values.all { it == 1 })
        assertEquals(337, counts.canonical.values.sum())
        assertTrue(counts.canonical.values.all { it == 1 })
        assertEquals(337, counts.directory.values.sum())
        assertEquals(288, counts.regular.values.sum())
        assertEquals(288, counts.length.values.sum())
    }

    @Test fun wildcardCacheReusesPatternsAndListingsButRechecksSymlinkBases() {
        val targets = folder.newFolder("targets")
        val first = File(targets, "first.tmp").apply { writeText("first") }
        val engine = engine()
        val cache = NativeProfileEngine.RuleExpansionCache()
        val rule = "${targets.path}/*.tmp"
        repeat(100) { assertEquals(listOf(first), engine.expand(rule, cache)) }
        assertEquals(setOf("*.tmp"), cache.patterns.keys)
        assertEquals(setOf(targets.path), cache.listings.keys)
        val listing = cache.listings.getValue(targets.path)
        File(targets, "second.tmp").writeText("second")
        assertEquals(listOf(first), engine.expand(rule, cache))
        assertSame(listing, cache.listings.getValue(targets.path))
        assertEquals(2, engine.expand(rule).size)

        val moved = File(folder.root, "moved")
        assertTrue(targets.renameTo(moved))
        Files.createSymbolicLink(targets.toPath(), moved.toPath())
        assertTrue(engine.expand(rule, cache).isEmpty())
        assertTrue(engine.expand("${folder.root.path}/../*", cache).isEmpty())
        assertTrue(engine.expand("/data/*/anything", cache).isEmpty())
    }

    @Test fun deepSnapshotDeduplicatesCanonicalIdentityAndRevalidatesReplacedSymlinks() {
        val rules = folder.newFolder("rules")
        val targets = folder.newFolder("targets")
        val target = File(targets, "old.tmp").apply { writeText("old") }
        File(rules, "deep.rules").writeText("${targets.path}/*.tmp\n${target.path}\n${targets.path}/./old.tmp\n")
        val engine = engine(rules = rules)
        val scan = JSONObject(engine.scan("deep", "{}") {})
        assertEquals(1, scan.getInt("totalCandidates"))
        val snapshotId = scan.getString("snapshotId")
        val item = JSONObject(engine.page(snapshotId, 0, 20)).getJSONArray("items").getJSONObject(0)
        assertEquals("deep:${target.canonicalPath}", item.getString("id"))
        assertEquals(target.canonicalPath, item.getString("path"))
        assertEquals("medium", item.getString("risk"))
        assertEquals(3L, item.getLong("bytes"))
        assertTrue(item.getBoolean("deleteRoot"))
        val outside = folder.newFile("keep").apply { writeText("untouched") }
        assertTrue(target.delete())
        Files.createSymbolicLink(target.toPath(), outside.toPath())
        val result = JSONObject(engine.clean(snapshotId, JSONObject().put(item.getString("id"), true).toString(), "{}") {})
        assertEquals(1, result.getInt("skippedCandidates"))
        assertEquals(0L, result.getLong("deletedFiles"))
        assertEquals("untouched", outside.readText())
        assertEquals("snapshot_expired", JSONObject(engine.page(snapshotId, 0, 20)).getString("error"))
    }

    @Test fun changedDeepRulesInvalidateSnapshotBeforeMutation() {
        val rules = folder.newFolder("rules")
        val target = folder.newFile("old.tmp").apply { writeText("old") }
        val source = File(rules, "deep.rules").apply { writeText("${target.path}\n") }
        val engine = engine(rules = rules)
        val scan = JSONObject(engine.scan("deep", "{}") {})
        source.appendText("# changed\n")
        val result = JSONObject(engine.clean(scan.getString("snapshotId"), "{\"__all_safe__\":true}", "{}") {})
        assertEquals("rules_changed", result.getString("error"))
        assertEquals("old", target.readText())
    }

    @Test fun cachedEmptyObservationNeverAuthorizesChangedDirectoryOrMount() {
        val target = folder.newFolder("empty")
        val entry = NativeProfileEngine.ScanEntry(target)
        entry.listChildren()
        assertTrue(entry.emptyDirectory)
        val engine = engine()
        fun method(name: String) = NativeProfileEngine::class.java.declaredMethods.single { it.name == name }
            .apply { isAccessible = true }
        val candidate = method("candidate").invoke(engine, "empty", "empty_dir", "empty", "low", target,
            "", "", true, "", entry, 0)
        val options = method("parseOptions").invoke(engine, "{}")
        val validate = method("validate")
        assertNull(validate.invoke(engine, candidate, options, emptySet<String>()))
        assertEquals("挂载点受保护", validate.invoke(engine, candidate, options, setOf(target.canonicalPath)))
        val keep = File(target, "keep").apply { writeText("new data") }
        assertTrue(entry.emptyDirectory)
        assertEquals("目标不再符合扫描条件", validate.invoke(engine, candidate, options, emptySet<String>()))
        val whitelist = method("parseOptions").invoke(engine,
            JSONObject().put("whitelistPaths", org.json.JSONArray().put(target.path)).toString())
        assertEquals("白名单保护", validate.invoke(engine, candidate, whitelist, emptySet<String>()))
        assertEquals("new data", keep.readText())
    }

    @Test fun defaultSelectionStillUsesSnapshotRiskCeilingAndExcludesHighRisk() {
        val rules = folder.newFolder("rules")
        val medium = folder.newFile("old.tmp").apply { writeText("old") }
        val high = folder.newFolder("files")
        File(high, "account.txt").writeText("keep")
        File(rules, "deep.rules").writeText("${medium.path}\n${high.path}\n")
        val engine = engine(rules = rules)
        val conservative = JSONObject(engine.scan("deep", "{\"maxAutoRisk\":\"low\"}") {})
        assertEquals("empty_selection", JSONObject(engine.clean(conservative.getString("snapshotId"),
            "{\"__all_safe__\":true}", "{}") {}).getString("error"))
        val ordinary = JSONObject(engine.scan("deep", "{}") {})
        assertEquals(1, ordinary.getInt("medium"))
        assertEquals(1, ordinary.getInt("high"))
        val result = JSONObject(engine.clean(ordinary.getString("snapshotId"), "{\"__all_safe__\":true}", "{}") {})
        assertEquals(1, result.getInt("selected"))
        // Host temporary paths are deliberately outside rule mutation roots.
        assertEquals(0L, result.getLong("deletedFiles"))
        assertEquals("keep", File(high, "account.txt").readText())
    }

    private class Counts {
        val listings = HashMap<String, Int>()
        val canonical = HashMap<String, Int>()
        val directory = HashMap<String, Int>()
        val regular = HashMap<String, Int>()
        val length = HashMap<String, Int>()
    }

    private class CountingFile(file: File, private val counts: Counts) : File(file.path) {
        private fun count(values: MutableMap<String, Int>) { values[path] = (values[path] ?: 0) + 1 }
        override fun listFiles(): Array<File>? {
            count(counts.listings)
            return super.listFiles()?.map { CountingFile(it, counts) }?.toTypedArray()
        }
        override fun getCanonicalFile(): File { count(counts.canonical); return super.getCanonicalFile() }
        override fun isDirectory(): Boolean { count(counts.directory); return super.isDirectory() }
        override fun isFile(): Boolean { count(counts.regular); return super.isFile() }
        override fun length(): Long { count(counts.length); return super.length() }
    }
}
