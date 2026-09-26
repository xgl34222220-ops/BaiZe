package io.github.xgl34222220.baize.root

import android.app.Application
import android.content.pm.IPackageDataObserver
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class PackageCacheCleanerTest {
    @get:Rule val folder = TemporaryFolder()
    private val pkg = "com.example.app"
    class CacheApi {
        val calls = mutableListOf<Pair<String, Int>>()
        var response: (String, IPackageDataObserver) -> Unit = { p, observer -> observer.onRemoveCompleted(p, true) }
        fun deleteApplicationCacheFilesAsUser(packageName: String, userId: Int, observer: IPackageDataObserver) {
            calls += packageName to userId
            response(packageName, observer)
        }
    }
    private fun neverShell() = object : ToolboxCommand(AtomicBoolean(), folder.root) {
        override fun run(arguments: List<String>, seconds: Long, honourCancel: Boolean, outputLimit: Int): Result {
            fail("Observer request must not be repeated through shell"); return Result(-1, "", false, false)
        }
    }
    @Test fun usesCacheOnlyObserverForTheSelectedUserAndWaitsForCompletion() {
        val api = CacheApi()
        api.response = { p, observer -> Thread { Thread.sleep(50); observer.onRemoveCompleted(p, true) }.start() }
        val result = PackageCacheCleaner(api, AtomicBoolean(), neverShell()).clear(pkg, 10)
        assertEquals(listOf(pkg to 10), api.calls)
        assertTrue(result.getBoolean("success")); assertTrue(result.getBoolean("callbackConfirmed"))
        assertEquals("package-manager-observer", result.getString("backend"))
        assertFalse(result.has("deletedBytes"))
    }
    @Test fun negativeCallbackDoesNotFallBackOrClaimSuccess() {
        val api = CacheApi().apply { response = { p, observer -> observer.onRemoveCompleted(p, false) } }
        assertFalse(PackageCacheCleaner(api, AtomicBoolean(), neverShell()).clear(pkg, 0).getBoolean("success"))
        assertEquals(1, api.calls.size)
    }
    @Test fun wrongPackageCallbackTimesOutWithoutRepeatingTheMutation() {
        val api = CacheApi().apply { response = { _, observer -> observer.onRemoveCompleted("com.other.app", true) } }
        val result = PackageCacheCleaner(api, AtomicBoolean(), neverShell(), 100).clear(pkg, 0)
        assertFalse(result.getBoolean("success")); assertTrue(result.getBoolean("timeout"))
        assertTrue(result.getBoolean("submitted")); assertEquals(1, api.calls.size)
    }
    @Test fun cancellationBeforeStartNeverInvokesTheSystem() {
        val api = CacheApi()
        assertTrue(PackageCacheCleaner(api, AtomicBoolean(true), neverShell()).clear(pkg, 0).getBoolean("cancelled"))
        assertTrue(api.calls.isEmpty())
    }
    @Test fun oldSystemShellFallbackReadsLargeHelpAndAlwaysRetainsCacheOnlyFlag() {
        val calls = mutableListOf<List<String>>()
        val command = object : ToolboxCommand(AtomicBoolean(), folder.newFolder()) {
            override fun run(arguments: List<String>, seconds: Long, honourCancel: Boolean, outputLimit: Int): Result {
                calls += arguments
                return if (arguments.last() == "help") super.run(
                    listOf("/bin/sh", "-c", "printf '%s\\n' '--cache-only'; head -c 131072 /dev/zero"), seconds, honourCancel, outputLimit)
                else Result(0, "Success", false, false)
            }
        }
        val cleaner = PackageCacheCleaner(Any(), AtomicBoolean(), command)
        assertTrue(cleaner.clear(pkg, 10).getBoolean("success"))
        assertTrue(cleaner.clear("com.other.app", 10).getBoolean("success"))
        assertEquals(1, calls.count { it.last() == "help" })
        assertEquals(listOf("/system/bin/cmd", "package", "clear", "--cache-only", "--user", "10", pkg), calls[1])
    }
    @Test fun zeroExitWithoutSuccessTextIsNotAConfirmedCacheClear() {
        val command = object : ToolboxCommand(AtomicBoolean(), folder.root) {
            override fun run(arguments: List<String>, seconds: Long, honourCancel: Boolean, outputLimit: Int) =
                Result(0, if (arguments.last() == "help") "--cache-only" else "Unknown option", false, false)
        }
        assertFalse(PackageCacheCleaner(Any(), AtomicBoolean(), command).clear(pkg, 0).getBoolean("success"))
    }
}
