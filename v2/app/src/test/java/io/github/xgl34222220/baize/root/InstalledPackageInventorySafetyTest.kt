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
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class InstalledPackageInventorySafetyTest {
    @get:Rule val folder = TemporaryFolder()
    private val known = InstalledPackageInventory.fromEntries(listOf("android" to 1000, "example.keep" to 10123))
    private var inventory: () -> InstalledPackageInventory = { known }
    private var user: Int? = 0
    private fun fixture(): Pair<NativeProfileEngine, File> {
        val root = folder.newFolder()
        File(root, "Android/data/example.keep/keep.txt").apply { parentFile.mkdirs(); writeText("installed data") }
        val gone = File(root, "Android/data/example.gone/remnant.txt").apply { parentFile.mkdirs(); writeText("synthetic leftover") }
        val engine = NativeProfileEngine(RuntimeEnvironment.getApplication(), AtomicBoolean(false),
            ruleDirectory = folder.root, sharedRootOverride = listOf(root), packageInventory = { inventory() }, corpseStorageUser = { user })
        return engine to gone
    }
    private fun scan(engine: NativeProfileEngine) = JSONObject(engine.scan("corpses", "{}") {})
    private fun select(engine: NativeProfileEngine, token: String): String = JSONObject().apply {
        val items = JSONObject(engine.page(token, 0, 60)).getJSONArray("items")
        for (i in 0 until items.length()) put(items.getJSONObject(i).getString("id"), true)
    }.toString()

    @Test fun emptyIncompleteMixedUserAndInvalidUidInventoriesAreRejected() {
        for (entries in listOf(emptyList(), listOf("example.only" to 12345), listOf("android" to -1),
            listOf("android" to 1000, "other.user" to 1012345), listOf("android" to 1000, "bad/path" to 12345))) {
            assertTrue(entries.toString(), runCatching { InstalledPackageInventory.fromEntries(entries) }.isFailure)
        }
    }
    @Test fun nonemptyVerifiedInventoryDistinguishesInstalledAndActuallyAbsentPackages() {
        val (engine, gone) = fixture()
        val result = scan(engine)
        assertTrue(result.getBoolean("success"))
        assertEquals(1, result.getInt("totalCandidates"))
        val items = JSONObject(engine.page(result.getString("snapshotId"), 0, 60)).getJSONArray("items")
        assertEquals("example.gone", items.getJSONObject(0).getString("packageName"))
        assertTrue(gone.isFile)
    }
    @Test fun inventoryFailureCannotCreateAnUninstalledSnapshot() {
        val (engine, gone) = fixture()
        inventory = { error("synthetic package service failed") }
        val result = scan(engine)
        assertEquals("package_inventory_unavailable", result.getString("error"))
        assertFalse(result.has("snapshotId"))
        assertEquals("synthetic leftover", gone.readText())
    }
    @Test fun emptyInventoryCannotBeTreatedAsEveryPackageBeingUninstalled() {
        val (engine, gone) = fixture()
        inventory = { InstalledPackageInventory(0, emptySet()) }
        assertEquals("package_inventory_unavailable", scan(engine).getString("error"))
        assertTrue(gone.isFile)
    }
    @Test fun ownerInventoryCannotClassifyOtherOrUnknownStorageUsers() {
        for (unknown in listOf(10, null)) {
            val (engine, gone) = fixture(); user = unknown
            assertEquals("package_inventory_unavailable", scan(engine).getString("error"))
            assertTrue(gone.isFile)
        }
        val work = InstalledPackageInventory.fromEntries(listOf("android" to 1001000, "work.app" to 1012345))
        work.requireUser(10)
        assertTrue(runCatching { work.requireUser(0) }.isFailure)
    }
    @Test fun failureAfterScanStopsDeleteAndQuarantineWithoutConsumingTheReview() {
        val (engine, gone) = fixture()
        val token = scan(engine).getString("snapshotId")
        val selected = select(engine, token)
        inventory = { error("disconnected after preview") }
        for (result in listOf(JSONObject(engine.clean(token, selected, "{\"allowHighRisk\":true}") {}),
            JSONObject(engine.quarantine(token, selected, "{}") {}))) {
            assertEquals("package_inventory_unavailable", result.getString("error"))
        }
        assertEquals(1, JSONObject(engine.page(token, 0, 60)).getJSONArray("items").length())
        assertEquals("synthetic leftover", gone.readText())
    }
    @Test fun changedUserScopeAfterScanCannotReuseTheOwnersPackageList() {
        val (engine, gone) = fixture()
        val token = scan(engine).getString("snapshotId"); val selected = select(engine, token)
        inventory = { InstalledPackageInventory(10, known.packages) }
        assertEquals("package_inventory_unavailable", JSONObject(engine.clean(token, selected, "{\"allowHighRisk\":true}") {}).getString("error"))
        assertTrue(gone.isFile)
    }
    @Test fun inventoryLostAfterPreflightRetainsUnprocessedCandidates() {
        val (engine, gone) = fixture()
        val token = scan(engine).getString("snapshotId"); val selected = select(engine, token)
        var reads = 0
        inventory = { if (++reads == 1) known else error("lost after preflight") }
        val result = JSONObject(engine.clean(token, selected, "{\"allowHighRisk\":true}") {})
        assertTrue(result.getBoolean("inventoryUnavailable"))
        assertTrue(result.getString("message").contains("清理已停止"))
        assertEquals(0L, result.getLong("deletedBytes"))
        assertEquals(1, result.getInt("remainingCandidates"))
        assertEquals(token, result.getString("remainingSnapshotId"))
        assertTrue(gone.isFile)
    }
    @Test fun reinstallAfterPreviewIsProtectedAndRetainsTheCandidate() {
        val (engine, gone) = fixture()
        val token = scan(engine).getString("snapshotId"); val selected = select(engine, token)
        inventory = { known.copy(packages = known.packages + "example.gone") }
        val result = JSONObject(engine.clean(token, selected, "{\"allowHighRisk\":true}") {})
        assertEquals("应用已重新安装", result.getJSONArray("details").getJSONObject(0).getString("reason"))
        assertEquals(1, result.getInt("remainingCandidates"))
        assertTrue(gone.isFile)
    }
    @Test fun storageUserParsingNeverGuessesForRemovableOrUnresolvedAliases() {
        assertEquals(0, InstalledPackageInventory.storageUser("/data/media/0/Android/data/example.gone"))
        assertEquals(10, InstalledPackageInventory.storageUser("/storage/emulated/10/Android/obb/example.gone"))
        for (path in listOf("/sdcard/Android/data/example.gone", "/mnt/media_rw/ABCD/Android/data/example.gone", "/storage/emulated/0suffix")) {
            assertNull(path, InstalledPackageInventory.storageUser(path))
        }
    }
}
