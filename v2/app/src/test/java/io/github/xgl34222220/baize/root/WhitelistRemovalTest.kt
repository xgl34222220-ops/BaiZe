package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WhitelistRemovalTest {
    @get:Rule val folder = TemporaryFolder()
    private fun repository() = WhitelistRepository(File(folder.root, "whitelist.conf"), File(folder.root, "whitelist.packages"), emptyList())
    private fun paths(repo: WhitelistRepository): Set<String> = JSONArray(repo.pathsJson()).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    private fun packages(repo: WhitelistRepository): Set<String> = JSONArray(repo.packagesJson()).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    @Test fun removingOnePathDoesNotDeleteFilesOrOtherRules() {
        val repo = repository()
        val parent = folder.newFolder("user-files")
        val target = File(parent, "keep.txt").apply { writeText("valuable user content") }
        repo.savePackages("[\"keep.app\"]")
        repo.addPath(parent.path)
        repo.addPath(target.path)
        assertTrue(JSONObject(repo.removePath(target.path)).getBoolean("removed"))
        assertEquals(setOf(parent.path), paths(repo))
        assertEquals(setOf("keep.app"), packages(repo))
        assertEquals("valuable user content", target.readText())
        assertFalse(JSONObject(repo.removePath(target.path)).getBoolean("removed"))
    }
    @Test fun appRootLookingManualPathRemainsVisibleAndRemovable() {
        val repo = repository()
        repo.addPath("/data/user/0/example.app")
        assertEquals(setOf("/data/user/0/example.app"), paths(repo))
        assertTrue(packages(repo).isEmpty())
        repo.savePackages("[\"other.app\"]")
        assertTrue(JSONObject(repo.removePath("/data/user/0/example.app/")).getBoolean("success"))
        assertTrue(paths(repo).isEmpty())
        assertEquals(setOf("other.app"), packages(repo))
    }
    @Test fun savingApplicationsPreservesManualPathsAndOtherConcurrentAdditions() {
        val repo = repository()
        repo.addPath("/storage/emulated/0/Archive")
        repo.savePackages("[\"one.app\",\"two.app\",\"new.remote\"]")
        assertTrue(JSONObject(repo.updatePackages("[\"add.app\"]", "[\"two.app\"]")).getBoolean("success"))
        assertEquals(setOf("one.app", "new.remote", "add.app"), packages(repo))
        assertEquals(setOf("/storage/emulated/0/Archive"), paths(repo))
        val reloaded = repository()
        assertEquals(packages(repo), packages(reloaded))
        assertEquals(paths(repo), paths(reloaded))
    }
    @Test fun badInputCannotClearAWhitelist() {
        val repo = repository()
        repo.addPath("/storage/emulated/0/Keep")
        repo.savePackages("[\"keep.app\"]")
        for (path in listOf("relative", "/a/../Keep", "/storage/emulated/0/Keep\n")) {
            assertFalse(JSONObject(repo.removePath(path)).optBoolean("success"))
        }
        assertFalse(JSONObject(repo.updatePackages("bad json", "[]")).optBoolean("success"))
        assertEquals(setOf("keep.app"), packages(repo))
        assertEquals(setOf("/storage/emulated/0/Keep"), paths(repo))
    }
    @Test fun parentAndChildProtectionAreNotImplicitlyRemoved() {
        val repo = repository()
        listOf("/sdcard/Download", "/sdcard/Download/keep", "/sdcard/Downloads").forEach { repo.addPath(it) }
        repo.removePath("/sdcard/Download/keep")
        assertEquals(setOf("/sdcard/Download", "/sdcard/Downloads"), paths(repo))
    }
}
