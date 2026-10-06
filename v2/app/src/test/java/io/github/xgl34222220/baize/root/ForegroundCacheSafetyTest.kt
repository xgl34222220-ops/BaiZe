package io.github.xgl34222220.baize.root

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ForegroundCacheSafetyTest {
    private val engine get() = ForegroundCacheEngine(RuntimeEnvironment.getApplication(), AtomicBoolean())
    @Test fun originalCacheContentsRemainTheBoundaryAfterCaptureAndRestore() {
        val directory = kotlin.io.path.createTempDirectory("baize-foreground-manifest-").toFile()
        try {
            val worker = engine
            val old = java.io.File(directory, "reviewed.bin").apply { writeText("old") }
            val progress: (Long, Long) -> Unit = { _, _ -> }
            val measure = ForegroundCacheEngine::class.java.getDeclaredMethod("measure", java.io.File::class.java, Function2::class.java).apply { isAccessible = true }
            val stats = measure.invoke(worker, directory, progress)
            val tree = stats.javaClass.getDeclaredField("frozenTree").apply { isAccessible = true }.get(stats) as FrozenReviewTree.Snapshot
            val restored = FrozenReviewTree.fromJson(FrozenReviewTree.toJson(tree))
            val added = java.io.File(directory, "after-review.bin").apply { writeText("keep"); setLastModified(1000) }
            val clear = ForegroundCacheEngine::class.java.getDeclaredMethod("clearChildren", java.io.File::class.java,
                FrozenReviewTree.Snapshot::class.java, Function2::class.java).apply { isAccessible = true }
            val result = clear.invoke(worker, directory, restored, progress)
            assertEquals(1L, result.javaClass.getDeclaredField("files").apply { isAccessible = true }.getLong(result))
            assertFalse(old.exists()); assertEquals("keep", added.readText())
            val repeated = clear.invoke(worker, directory, restored, progress)
            assertEquals(0L, repeated.javaClass.getDeclaredField("files").apply { isAccessible = true }.getLong(repeated))
            assertEquals("keep", added.readText())
        } finally { directory.deleteRecursively() }
    }
    @Test fun noPathWhitelistDoesNotRequireFrameworkStorageIdentity() {
        val protected = ForegroundCacheEngine::class.java.getDeclaredMethod("protectedPath", String::class.java, Set::class.java).apply { isAccessible = true }
        assertEquals(false, protected.invoke(engine, "/data/user/0/com.example/cache", emptySet<String>()))
    }
    @Test fun unresolvedPrimaryAliasesAreDetectableWithoutGuessingAnotherUser() {
        val unknown = AndroidPathIdentity(null)
        assertTrue(unknown.unresolvedUserAlias("/sdcard/protected"))
        assertTrue(unknown.unresolvedUserAlias("/storage/self/primary/protected"))
        assertFalse(unknown.unresolvedUserAlias("/storage/emulated/10/protected"))
        assertFalse(AndroidPathIdentity("/data/media/10").unresolvedUserAlias("/sdcard/protected"))
        assertEquals("/storage/emulated/10/protected", AndroidPathIdentity("/data/media/10").of("/sdcard/protected"))
    }
    @Test
    @Config(shadows = [RejectingStorageEnvironment::class])
    fun frameworkPackageUidRejectionCannotBreakCachePathProtection() {
        val protected = ForegroundCacheEngine::class.java.getDeclaredMethod("protectedPath", String::class.java, Set::class.java).apply { isAccessible = true }
        val worker = engine
        assertEquals(false, protected.invoke(worker, "/data/user/0/com.example/cache", emptySet<String>()))
        assertEquals(true, protected.invoke(worker, "/data/data/com.example/cache", setOf("/data/user/0/com.example")))
        assertEquals(false, protected.invoke(worker, "/data/user/10/com.example/cache", setOf("/data/user/0/com.example")))
    }

    @Implements(android.os.Environment::class)
    class RejectingStorageEnvironment {
        companion object {
            @JvmStatic @Implementation
            fun getExternalStorageDirectory(): java.io.File = throw SecurityException("callingPackage does not match UID")
        }
    }
    @Test fun corruptWhitelistNeverBecomesAnUnprotectedScan() {
        val parse = ForegroundCacheEngine::class.java.getDeclaredMethod("parseWhitelist", String::class.java).apply { isAccessible = true }
        for (bad in listOf("", "null", "[123]", "[\"../data\"]", "{}")) {
            try { parse.invoke(engine, bad); fail("Must fail closed for $bad") }
            catch (error: InvocationTargetException) { assertTrue(error.cause is IllegalArgumentException) }
        }
        assertEquals(emptySet<String>(), parse.invoke(engine, "[]"))
        assertEquals(setOf("com.example"), parse.invoke(engine, "[\"com.example\"]"))
    }
    @Test fun webViewPathsAcceptDirectCacheButNeverProfilesOrDatabases() {
        val known = ForegroundCacheEngine::class.java.getDeclaredMethod("knownCachePath", String::class.java, String::class.java).apply { isAccessible = true }
        for (path in listOf("/data/user/0/com.example/app_webview/Cache", "/data/user/10/com.example/app_webview/Default/Code Cache", "/data/data/com.example/app_x5webview/Default/GPUCache")) {
            assertEquals(path, true, known.invoke(engine, path, "com.example"))
        }
        for (path in listOf("/data/user/0/com.example/app_webview/Default", "/data/user/0/com.example/databases", "/data/user/0/com.other/cache", "/data/user/0/com.example/app_webview/Default/Local Storage")) {
            assertEquals(path, false, known.invoke(engine, path, "com.example"))
        }
    }
    @Test fun pathProtectionCoversAliasesAndSelectedParents() {
        val protected = ForegroundCacheEngine::class.java.getDeclaredMethod("protectedPath", String::class.java, Set::class.java).apply { isAccessible = true }
        assertEquals(true, protected.invoke(engine, "/data/media/10/Android/data/com.example/cache", setOf("/storage/emulated/10/Android/data/com.example/cache/keep.txt")))
        assertEquals(true, protected.invoke(engine, "/data/data/com.example/cache", setOf("/data/user/0/com.example")))
        assertEquals(false, protected.invoke(engine, "/data/user/10/com.example/cache", setOf("/data/user/0/com.example")))
    }
    @Test fun cancelledCleanupRetainsEveryUnprocessedCandidate() {
        val cancelled = AtomicBoolean(true)
        val worker = ForegroundCacheEngine(RuntimeEnvironment.getApplication(), cancelled)
        val items = (1..3).map { ForegroundCacheEngine.Item("com.example", "Example", "cache", "/data/user/0/com.example/cache/$it", 4, 1, 0) }
        val result = worker.clean(ForegroundCacheEngine.Snapshot("test", 1, items, 12, 3, 0, 0), "[]") { _, _, _, _ -> }
        assertEquals(items, result.remainingItems)
        assertEquals(0, result.processedCandidates)
        assertFalse(result.json().getBoolean("success"))
        assertEquals(0L, result.deletedFiles)
    }
}
