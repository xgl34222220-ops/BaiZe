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
class NativeEmptyCoverageRegressionTest {
    @get:Rule val folder = TemporaryFolder()
    private fun engine(root: File) = NativeProfileEngine(RuntimeEnvironment.getApplication(), AtomicBoolean(false),
        ruleDirectory = folder.root, sharedRootOverride = listOf(root))
    private fun scan(engine: NativeProfileEngine) = JSONObject(engine.scan("empty", "{}") {})

    @Test fun exactlyAtTheDepthLimitAnActuallyEmptyDirectoryIsStillFound() {
        val root = folder.newFolder("storage")
        val leaf = File(root, (1..8).joinToString("/") { "level$it" }).apply { mkdirs() }
        val e = engine(root); val result = scan(e)
        assertEquals(1, result.getInt("emptyDirs"))
        assertFalse(result.getBoolean("partial"))
        val items = JSONObject(e.page(result.getString("snapshotId"), 0, 60)).getJSONArray("items")
        assertEquals(leaf.canonicalPath, items.getJSONObject(0).getString("path"))
    }

    @Test fun deeperUnvisitedContentMakesCoverageExplicitlyIncomplete() {
        val root = folder.newFolder("storage")
        val leaf = File(root, (1..9).joinToString("/") { "level$it" }).apply { mkdirs() }
        val result = scan(engine(root))
        assertEquals(0, result.getInt("emptyDirs"))
        assertTrue(result.getBoolean("partial"))
        assertEquals(1, result.optInt("depthLimitedDirectories"))
        assertTrue(leaf.isDirectory)
    }

    @Test fun aFailedDirectoryListingIsNotACompleteCleanResult() {
        val root = folder.newFolder("storage")
        val target = File(root, "keep.txt").apply { writeText("synthetic content") }
        val unreadable = object : File(root.path) { override fun listFiles(): Array<File>? = null }
        val result = scan(engine(unreadable))
        assertTrue(result.getBoolean("partial"))
        assertEquals(1, result.optInt("unreadableDirectories"))
        assertEquals(0, result.getInt("totalCandidates"))
        assertEquals("synthetic content", target.readText())
    }

    @Test fun deniedDirectoryThenSuccessfulRescanDoesNotCarryOldFailureCounters() {
        val root = folder.newFolder("storage")
        File(root, "empty").mkdir()
        var readable = false
        val toggled = object : File(root.path) {
            override fun listFiles(): Array<File>? {
                if (!readable) throw SecurityException("synthetic access denied")
                return super.listFiles()
            }
        }
        val e = engine(toggled)
        assertTrue(scan(e).getBoolean("partial"))
        readable = true
        val recovered = scan(e)
        assertFalse(recovered.getBoolean("partial"))
        assertEquals(0, recovered.getInt("unreadableDirectories"))
        assertEquals(1, recovered.getInt("emptyDirs"))
    }

    @Test fun noAvailableStorageIsReportedAsUnknownCoverage() {
        val e = NativeProfileEngine(RuntimeEnvironment.getApplication(), AtomicBoolean(false),
            ruleDirectory = folder.root, sharedRootOverride = emptyList())
        val result = scan(e)
        assertTrue(result.getBoolean("partial"))
        assertTrue(result.getBoolean("storageUnavailable"))
        assertEquals(0, result.getInt("totalCandidates"))
    }

    @Test fun cleaningReviewedLeafDoesNotSilentlyDeleteUnreviewedParents() {
        val root = folder.newFolder("storage")
        val leaf = File(root, "parent/child/leaf").apply { mkdirs() }
        val e = engine(root); val result = scan(e); val token = result.getString("snapshotId")
        val rows = JSONObject(e.page(token, 0, 60)).getJSONArray("items")
        assertEquals(1, rows.length())
        val cleaned = JSONObject(e.clean(token, JSONObject().put(rows.getJSONObject(0).getString("id"), true).toString(), "{}") {})
        assertEquals(1L, cleaned.getLong("deletedDirectories"))
        assertEquals(0L, cleaned.getLong("deletedFiles"))
        assertEquals(0L, cleaned.getLong("deletedBytes"))
        assertFalse(leaf.exists())
        assertTrue(leaf.parentFile.isDirectory)
    }

    @Test fun hiddenPlaceholderFilesAndSymlinksAreNeverTreatedAsEmptyDirectories() {
        val root = folder.newFolder("storage")
        val marker = File(root, "keep/.nomedia").apply { parentFile.mkdirs(); writeText("") }
        val outside = folder.newFolder("outside")
        Files.createSymbolicLink(File(root, "shortcut").toPath(), outside.toPath())
        val result = scan(engine(root))
        assertEquals(0, result.getInt("totalCandidates"))
        assertTrue(marker.isFile)
        assertTrue(outside.isDirectory)
    }
}
