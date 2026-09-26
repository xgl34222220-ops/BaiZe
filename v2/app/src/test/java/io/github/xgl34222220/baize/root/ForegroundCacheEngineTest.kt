package io.github.xgl34222220.baize.root

import android.app.Application
import android.system.ErrnoException
import android.system.OsConstants
import android.system.StructStat
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLinux
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE,
    shadows = [HostCacheFilesystem::class])
class ForegroundCacheEngineTest {
    @get:Rule val folder = TemporaryFolder()
    private val pkg = "com.example.app"

    @After fun resetFilesystemHooks() {
        HostCacheFilesystem.afterRemove = null
        HostCacheFilesystem.failPath = null
    }

    private fun engine(root: File, cancelled: AtomicBoolean = AtomicBoolean()) =
        ForegroundCacheEngine(RuntimeEnvironment.getApplication(), cancelled, root)

    private fun file(root: File, path: String, text: String = "1234") =
        File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }

    @Test fun scanDeduplicatesLegacyAliasesAndCoversExistingUsersAndWebViewRoots() {
        val root = folder.newFolder("data")
        file(root, "user/0/$pkg/cache/a")
        file(root, "user/10/$pkg/cache/b")
        file(root, "user_de/10/$pkg/code_cache/c")
        file(root, "media/10/Android/data/$pkg/cache/d")
        file(root, "user/0/$pkg/app_webview/Cache/e")
        file(root, "user/0/$pkg/app_webview/Default/Code Cache/f")
        file(root, "user/0/$pkg/files/keep")
        Files.createSymbolicLink(File(root, "data").toPath(), File(root, "user/0").toPath())
        val snapshot = engine(root).scan("[]") { _, _, _, _ -> }
        assertEquals(6, snapshot.items.size)
        assertEquals(6L, snapshot.totalFiles)
        assertEquals(24L, snapshot.totalBytes)
        assertEquals(6, snapshot.items.map { it.path }.toSet().size)
        val result = engine(root).clean(snapshot, "[]") { _, _, _, _ -> }
        assertEquals(6L, result.deletedFiles)
        assertEquals(24L, result.deletedBytes)
        assertTrue(result.remainingItems.isEmpty())
        snapshot.items.forEach {
            assertTrue(File(it.path).isDirectory)
            assertTrue(File(it.path).list()!!.isEmpty())
        }
        assertEquals("1234", File(root, "user/0/$pkg/files/keep").readText())
    }

    @Test fun directoryRedirectProtectsAlreadyScannedCacheAndFutureDiscovery() {
        val root = folder.newFolder("redirect-data")
        val keep = file(root, "media/0/Android/data/$pkg/cache/keep")
        val redirects = File(folder.root, "mounts.json")
        val engine = ForegroundCacheEngine(RuntimeEnvironment.getApplication(), AtomicBoolean(), root, redirects)
        val before = engine.scan("[]") { _, _, _, _ -> }
        assertEquals(1, before.items.size)
        redirects.writeText(org.json.JSONObject().put("/data/media/0/Android/data/$pkg/cache", "/data/media/0/Important").toString())
        val result = engine.clean(before, "[]") { _, _, _, _ -> }
        assertEquals(1, result.protectedCandidates)
        assertEquals(0L, result.deletedBytes)
        assertTrue(keep.exists())
        assertTrue(engine.scan("[]") { _, _, _, _ -> }.items.isEmpty())
    }

    @Test fun whitelistSkipsWebViewAtDiscoveryAndProtectsAlreadyScannedCaches() {
        val root = folder.newFolder("data")
        val keep = file(root, "user/0/$pkg/app_webview/Default/Cache/a")
        val engine = engine(root)
        val snapshot = engine.scan("[]") { _, _, _, _ -> }
        assertEquals(1, snapshot.items.size)
        assertTrue(engine.scan("[\"$pkg\"]") { _, _, _, _ -> }.items.isEmpty())
        val result = engine.clean(snapshot, "[\"$pkg\"]") { _, _, _, _ -> }
        assertEquals(1, result.protectedCandidates)
        assertEquals(0L, result.deletedFiles)
        assertEquals(snapshot.items, result.remainingItems)
        assertTrue(keep.exists())
    }

    @Test fun cancellationKeepsEveryUnprocessedRootForRetry() {
        val root = folder.newFolder("data")
        repeat(12) { file(root, "user/0/com.example.app$it/cache/a") }
        val cancelled = AtomicBoolean()
        val engine = engine(root, cancelled)
        val snapshot = engine.scan("[]") { _, _, _, _ -> }
        cancelled.set(true)
        val stopped = engine.clean(snapshot, "[]") { _, _, _, _ -> }
        assertEquals(0, stopped.processedCandidates)
        assertEquals(snapshot.items, stopped.remainingItems)
        cancelled.set(false)
        val retried = engine.clean(snapshot, "[]") { _, _, _, _ -> }
        assertEquals(12L, retried.deletedFiles)
        assertEquals(48L, retried.deletedBytes)
        assertTrue(retried.remainingItems.isEmpty())
        snapshot.items.forEach { assertTrue(File(it.path).list()!!.isEmpty()) }
    }

    @Test fun replacingAncestorWithSymlinkDoesNotDeleteOutsideSnapshot() {
        val root = folder.newFolder("data")
        file(root, "user/0/$pkg/cache/a")
        val engine = engine(root)
        val snapshot = engine.scan("[]") { _, _, _, _ -> }
        val app = File(root, "user/0/$pkg")
        assertTrue(app.renameTo(File(root, "moved")))
        val outside = folder.newFolder("outside")
        val keep = file(outside, "cache/a", "keep")
        Files.createSymbolicLink(app.toPath(), outside.toPath())
        val result = engine.clean(snapshot, "[]") { _, _, _, _ -> }
        assertEquals(0L, result.deletedFiles)
        assertEquals(1, result.changedCandidates)
        assertEquals("keep", keep.readText())
    }

    @Test fun stopDuringDeletionCountsCompletedFilesAndRetainsTheRestForRetry() {
        val root = folder.newFolder("data")
        repeat(20) { file(root, "user/0/$pkg/cache/$it") }
        val cancelled = AtomicBoolean()
        val engine = engine(root, cancelled)
        val snapshot = engine.scan("[]") { _, _, _, _ -> }
        HostCacheFilesystem.afterRemove = { cancelled.set(true) }
        val stopped = engine.clean(snapshot, "[]") { _, _, _, _ -> }
        assertTrue(stopped.cancelled)
        assertEquals(1L, stopped.deletedFiles)
        assertEquals(4L, stopped.deletedBytes)
        assertEquals(19L, stopped.remainingItems.single().files)
        assertFalse(stopped.remainingItems.single().complete)
        val cache = File(snapshot.items.single().path)
        assertEquals(19, cache.list()!!.size)
        HostCacheFilesystem.afterRemove = null
        cancelled.set(false)
        val retry = engine.clean(snapshot.copy(items = stopped.remainingItems), "[]") { _, _, _, _ -> }
        assertEquals(19L, retry.deletedFiles)
        assertTrue(cache.list()!!.isEmpty())
    }

    @Test fun failedDeleteIsNotCountedAndLinksAreNeverFollowed() {
        val root = folder.newFolder("data")
        val keep = file(root, "user/0/$pkg/cache/denied")
        file(root, "user/0/$pkg/cache/nested/deleted")
        val outside = folder.newFolder("outside")
        val outsideFile = file(outside, "keep")
        Files.createSymbolicLink(File(keep.parentFile, "outside-link").toPath(), outside.toPath())
        val engine = engine(root)
        val snapshot = engine.scan("[]") { _, _, _, _ -> }
        assertEquals(2L, snapshot.totalFiles)
        HostCacheFilesystem.failPath = keep.path
        val result = engine.clean(snapshot, "[]") { _, _, _, _ -> }
        assertEquals(1L, result.deletedFiles)
        assertEquals(4L, result.deletedBytes)
        assertEquals(1L, result.deletedDirectories)
        assertEquals(1, result.partialCandidates)
        assertEquals(1, result.failedCandidates)
        assertEquals(1L, result.remainingItems.single().files)
        assertTrue(keep.exists())
        assertTrue(outsideFile.exists())
    }
}

/** Supply actual unlink and no-follow link metadata missing from Robolectric's Linux shadow. */
@Implements(className = "libcore.io.Linux", isInAndroidSdk = false)
class HostCacheFilesystem : ShadowLinux() {
    companion object {
        @Volatile var afterRemove: (() -> Unit)? = null
        @Volatile var failPath: String? = null
    }

    @Implementation
    override fun lstat(path: String): StructStat {
        if (Files.isSymbolicLink(File(path).toPath())) {
            return StructStat(0, 0, OsConstants.S_IFLNK, 1, 0, 0, 0, 0, 0, 0, 0, 4096, 0)
        }
        return super.lstat(path)
    }

    @Implementation
    fun remove(path: String) {
        if (path == failPath) throw ErrnoException("remove", OsConstants.EACCES)
        try {
            Files.delete(File(path).toPath())
            afterRemove?.invoke()
        } catch (_: java.nio.file.NoSuchFileException) {
            throw ErrnoException("remove", OsConstants.ENOENT)
        } catch (_: java.io.IOException) {
            throw ErrnoException("remove", OsConstants.EACCES)
        }
    }
}
