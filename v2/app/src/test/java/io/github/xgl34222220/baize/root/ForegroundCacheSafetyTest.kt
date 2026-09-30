package io.github.xgl34222220.baize.root

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ForegroundCacheSafetyTest {
    private val engine get() = ForegroundCacheEngine(RuntimeEnvironment.getApplication(), AtomicBoolean())
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
