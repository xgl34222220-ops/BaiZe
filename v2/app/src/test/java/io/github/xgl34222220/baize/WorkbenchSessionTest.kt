package io.github.xgl34222220.baize

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.MutableState
import io.github.xgl34222220.baize.root.IProfileRootService
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkbenchSessionTest {
    private val app get() = RuntimeEnvironment.getApplication<Application>()

    @Test fun repeatedScanAndCleanKeepOneSnapshotAndNeverStartAnotherPage() = withSession { session, dispatcher ->
        val scans = AtomicInteger()
        val cleanups = AtomicInteger()
        val scanEntered = CountDownLatch(1)
        val releaseScan = CountDownLatch(1)
        val cleanEntered = CountDownLatch(1)
        val releaseClean = CountDownLatch(1)
        set(session, "scanProfile", "rules")
        set(session, "profileBound", true)
        field<ReviewHydrationGate>(session, "reviewHydration").finish(true)
        val service = Proxy.newProxyInstance(IProfileRootService::class.java.classLoader,
            arrayOf(IProfileRootService::class.java)) { _, method, _ ->
            when (method.name) {
                "getSchedulerConfig" -> "{}"
                "getWhitelistPackages", "getWhitelistPaths" -> "[]"
                "getTaskState" -> "{}"
                "scanProfile" -> {
                    scans.incrementAndGet(); scanEntered.countDown()
                    check(releaseScan.await(5, TimeUnit.SECONDS))
                    """{"success":true,"snapshotId":"profile-fixture","snapshotExpiresInMs":1800000}"""
                }
                "getProfilePage" -> """{"snapshotId":"profile-fixture","offset":0,"total":1,"items":[{"id":"sample","profile":"rules","category":"rule_trash","risk":"low","path":"/synthetic/log","bytes":512,"files":1}]}"""
                "cleanProfileSelected" -> {
                    cleanups.incrementAndGet(); cleanEntered.countDown()
                    check(releaseClean.await(5, TimeUnit.SECONDS))
                    """{"success":true,"deletedBytes":512,"deletedFiles":1,"cleanedCandidates":1,"details":[{"id":"sample","action":"cleaned","bytes":512,"files":1}]}"""
                }
                "recordNativeTask" -> "{}"
                else -> null
            }
        } as IProfileRootService
        set(session, "profileService", service)
        state(session, WorkbenchUiState(profileConnected = true, cacheRequired = false, scanProfile = "rules"))
        try {
            session.runScan(); session.runScan()
            await(dispatcher) { scanEntered.count == 0L }
            assertTrue(session.screenState.running)
            releaseScan.countDown()
            await(dispatcher) { session.screenState.scanReady }
            assertEquals(1, scans.get())
            assertEquals(setOf("profile:sample"), session.screenState.selectedIds)
            session.cleanSelection(); session.cleanSelection()
            await(dispatcher) { cleanEntered.count == 0L }
            releaseClean.countDown()
            await(dispatcher) { session.screenState.cleanupCompleted }
            assertEquals(1, scans.get())
            assertEquals(1, cleanups.get())
            assertEquals(512L, session.screenState.cleanedBytes)
            assertFalse(session.screenState.scanReady)
            assertEquals("已清理", session.screenState.items.single().outcome)
            session.cleanSelection()
            dispatcher.scheduler.runCurrent()
            assertEquals(1, cleanups.get())
            session.saveReview()
            val saved = ScanReviewStore.read(app, "rules")!!
            assertTrue(saved.getBoolean("cleanupCompleted"))
            assertEquals(512L, saved.getLong("cleanedBytes"))
        } finally { releaseScan.countDown(); releaseClean.countDown() }
    }

    @Test fun emptyCompletedScanRestoresWithoutAutomaticRescan() = withSession { session, dispatcher ->
        ScanReviewStore.save(app, "empty") { JSONObject().put("items", JSONArray()).put("selected", JSONArray())
            .put("phase", "扫描完成，没有发现垃圾项目").put("notice", "SUCCESS").put("scanReady", false) }
        set(session, "profileBound", true)
        session.initialize("empty")
        await(dispatcher) { !session.screenState.restoringReview }
        assertTrue(field(session, "restoredReview"))
        assertFalse(field(session, "autoScanStarted"))
        assertTrue(session.screenState.items.isEmpty())
    }

    @Test fun completedCleanupRestoresActualBytesAndSuccessfulOutcome() = withSession { session, dispatcher ->
        ScanReviewStore.save(app, "fragments") { JSONObject().put("items", JSONArray()).put("selected", JSONArray())
            .put("phase", "已完成所选项目清理").put("notice", "SUCCESS").put("cleanupCompleted", true)
            .put("cleanedBytes", 2048).put("cleanedFiles", 7) }
        set(session, "profileBound", true)
        session.initialize("fragments")
        await(dispatcher) { !session.screenState.restoringReview }
        assertTrue(session.screenState.cleanupCompleted)
        assertEquals(2048L, session.screenState.cleanedBytes)
        assertEquals(WorkbenchNotice.SUCCESS, session.screenState.notice)
        assertEquals("已完成所选项目清理", session.screenState.phase)
    }

    @Test fun interruptionCannotRestoreDeleteAuthorization() = withSession { session, dispatcher ->
        ScanReviewStore.save(app, "deep") { JSONObject().put("items", JSONArray()).put("selected", JSONArray())
            .put("running", true).put("scanReady", true).put("expiresAt", System.currentTimeMillis() + 100000) }
        set(session, "profileBound", true)
        session.initialize("deep")
        await(dispatcher) { !session.screenState.restoringReview }
        assertFalse(session.screenState.scanReady)
        assertFalse(session.screenState.cleanupCompleted)
        assertTrue(session.screenState.phase.contains("结果未确认"))
    }

    private fun withSession(block: (ScanWorkbenchSession, TestDispatcher) -> Unit) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = ScanWorkbenchSession(app, scope)
        try { block(session, dispatcher) }
        finally { session.close(); scope.cancel(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }
    }
    private fun await(dispatcher: TestDispatcher, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < deadline) {
            dispatcher.scheduler.runCurrent(); Thread.sleep(10)
        }
        assertTrue("Session did not reach the expected state", condition())
    }
    private fun set(session: ScanWorkbenchSession, name: String, value: Any) {
        ScanWorkbenchSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
    }
    @Suppress("UNCHECKED_CAST") private fun <T> field(session: ScanWorkbenchSession, name: String): T =
        ScanWorkbenchSession::class.java.getDeclaredField(name).apply { isAccessible = true }.get(session) as T
    private fun state(session: ScanWorkbenchSession, value: WorkbenchUiState) {
        field<MutableState<WorkbenchUiState>>(session, "screenState\$delegate").value = value
    }
}
