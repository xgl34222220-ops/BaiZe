package io.github.xgl34222220.baize

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.MutableState
import io.github.xgl34222220.baize.root.IProfileRootService
import io.github.xgl34222220.baize.root.IBaiZeRootService
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
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkbenchSessionTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun zeroByteDirectoryCleanupKeepsDirectoryCountSeparateAndPersistsIt() = withSession { session, dispatcher ->
        val history = AtomicReference<JSONObject>()
        val service = Proxy.newProxyInstance(IProfileRootService::class.java.classLoader,
            arrayOf(IProfileRootService::class.java)) { _, method, args -> when (method.name) {
                "getWhitelistPackages", "getWhitelistPaths" -> "[]"
                "cleanProfileSelected" -> """{"success":true,"deletedBytes":0,"deletedFiles":0,"deletedDirectories":1,"cleanedCandidates":1,"remainingCandidates":0,"details":[{"id":"sample","action":"cleaned","bytes":0,"files":0,"directories":1}]}"""
                "recordNativeTask" -> { history.set(JSONObject(args!![0] as String)); "{}" }
                else -> "{}"
            } } as IProfileRootService
        val original = ready(session, service)
        state(session, session.screenState.copy(items = listOf(original.copy(profile = "empty", category = "empty_dir",
            title = "空目录", bytes = 0, files = 0, directories = 0))))
        session.cleanSelection()
        await(dispatcher) { session.screenState.cleanupCompleted }
        assertEquals(0L, session.screenState.cleanedBytes)
        assertEquals(0L, session.screenState.cleanedFiles)
        val saved = ScanReviewStore.read(app, "rules")!!
        assertEquals(1L, saved.optLong("cleanedDirectories"))
        assertEquals(1L, history.get().optLong("emptyDirs"))
        assertTrue(session.screenState.resultText.contains("目录 1"))
    }

    @Test fun lowRiskBatchKeepsHighRiskAvailableWithoutRescanningOrReplayingLowRisk() = batchRetainsUnselected(failedFirst = false)

    @Test fun failedBatchLocksAttemptedItemsButKeepsUnselectedHighRiskAvailable() = batchRetainsUnselected(failedFirst = true)

    private fun batchRetainsUnselected(failedFirst: Boolean) = withSession { session, dispatcher ->
        val selections = java.util.concurrent.CopyOnWriteArrayList<Set<String>>()
        val highRiskOptions = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val service = Proxy.newProxyInstance(IProfileRootService::class.java.classLoader,
            arrayOf(IProfileRootService::class.java)) { _, method, args -> when (method.name) {
                "getWhitelistPackages", "getWhitelistPaths" -> "[]"
                "cleanProfileSelected" -> {
                    assertEquals("old-snapshot", args!![0])
                    selections += JSONObject(args[1] as String).keys().asSequence().toSet()
                    highRiskOptions += JSONObject(args[2] as String).getBoolean("allowHighRisk")
                    if (selections.size == 1) JSONObject().put("success", !failedFirst)
                        .put("deletedBytes", if (failedFirst) 0 else 512).put("deletedFiles", if (failedFirst) 0 else 1)
                        .put("failures", if (failedFirst) 1 else 0).put("remainingSnapshotId", "old-snapshot")
                        .put("remainingCandidates", 1).put("snapshotExpiresInMs", 300_000)
                        .put("details", JSONArray().put(JSONObject().put("id", "sample")
                            .put("action", if (failedFirst) "failed" else "cleaned").put("bytes", if (failedFirst) 0 else 512).put("files", if (failedFirst) 0 else 1))).toString()
                    else """{"success":true,"deletedBytes":1024,"deletedFiles":1,"remainingCandidates":0,"details":[{"id":"high","action":"cleaned","bytes":1024,"files":1}]}"""
                }
                "scanProfile" -> error("A second batch must use the reviewed snapshot")
                else -> "{}"
            } } as IProfileRootService
        val low = ready(session, service)
        val high = low.copy(id = "profile:high", title = "high.tmp", path = "/synthetic/high.tmp", risk = "high", bytes = 1024)
        val originalExpiry = session.screenState.expiresAtRealtime
        state(session, session.screenState.copy(items = listOf(low, high)))
        session.cleanSelection()
        await(dispatcher) { session.screenState.cleanupCompleted }
        assertTrue(session.screenState.scanReady)
        assertFalse(session.screenState.items.first().selectable)
        assertTrue(session.screenState.items.last().selectable)
        assertTrue(session.screenState.selectedIds.isEmpty())
        assertTrue("A batch must never extend the original authorization lifetime", session.screenState.expiresAtRealtime <= originalExpiry)
        assertEquals(if (failedFirst) 0L else 512L, session.screenState.cleanedBytes)
        session.toggleItem(low.id)
        assertTrue(session.screenState.selectedIds.isEmpty())
        val saved = ScanReviewStore.read(app, "rules")!!
        assertTrue(saved.getBoolean("scanReady"))
        assertTrue(saved.getBoolean("cleanupCompleted"))
        assertFalse(saved.getJSONArray("items").getJSONObject(0).getBoolean("selectable"))
        session.toggleItem(high.id)
        session.cleanSelection()
        await(dispatcher) { selections.size == 2 && !session.screenState.running }
        assertEquals(listOf(setOf("sample"), setOf("high")), selections.toList())
        assertEquals(listOf(false, true), highRiskOptions.toList())
        assertEquals(1024L, session.screenState.cleanedBytes)
        assertFalse(session.screenState.scanReady)
        assertTrue(session.screenState.items.none { it.selectable })
    }

    @Test fun cacheReviewCleansOnlySelectedPathsFromItsOwnForegroundSnapshot() = withSession { session, dispatcher ->
        val submittedSnapshot = AtomicReference<String>()
        val submittedSelection = AtomicReference<String>()
        val cleanups = AtomicInteger()
        val profile = profileService { method ->
            if (method == "prepareCacheSelection") error("Foreground review must not use the module snapshot repository")
            null
        }
        val cache = Proxy.newProxyInstance(IBaiZeRootService::class.java.classLoader,
            arrayOf(IBaiZeRootService::class.java)) { _, method, args -> when (method.name) {
                "scanCandidates" -> """{"snapshotId":"foreground-cache","snapshotExpiresInMs":1800000}"""
                "getResultPage" -> """{"snapshotId":"foreground-cache","offset":0,"total":3,"items":[{"packageName":"com.example.fixture","categoryLabel":"应用缓存","path":"/synthetic/selected","bytes":512,"files":1,"complete":true},{"packageName":"com.example.fixture","categoryLabel":"应用缓存","path":"/synthetic/retained","bytes":1024,"files":1,"complete":true},{"packageName":"com.example.fixture","categoryLabel":"应用缓存","path":"/synthetic/incomplete","bytes":4096,"files":2,"complete":false}]}"""
                "cleanSelected" -> {
                    cleanups.incrementAndGet()
                    submittedSnapshot.set(args!![0] as String)
                    submittedSelection.set(args[1] as String)
                    """{"success":true,"mutated":true,"deletedBytes":512,"deletedFiles":1,"cleanedCandidates":1}"""
                }
                else -> "{}"
            } } as IBaiZeRootService
        set(session, "scanProfile", "cache")
        set(session, "profileService", profile)
        set(session, "cacheService", cache)
        field<ReviewHydrationGate>(session, "reviewHydration").finish(true)
        state(session, WorkbenchUiState(profileConnected = true, cacheConnected = true, scanProfile = "cache"))
        session.runScan()
        await(dispatcher) { session.screenState.scanReady }
        assertEquals(3, session.screenState.items.size)
        val incomplete = session.screenState.items.single { it.path == "/synthetic/incomplete" }
        assertFalse(incomplete.selectable)
        assertEquals(0L, incomplete.bytes)
        assertTrue(incomplete.reason.contains("不计入可释放容量"))
        session.toggleItem(incomplete.id)
        assertFalse(incomplete.id in session.screenState.selectedIds)
        session.clearSelection()
        session.toggleItem(session.screenState.items.single { it.path == "/synthetic/selected" }.id)
        session.cleanSelection()
        await(dispatcher) { session.screenState.cleanupCompleted }
        assertEquals(1, cleanups.get())
        assertEquals("foreground-cache", submittedSnapshot.get())
        val selection = JSONObject(submittedSelection.get())
        assertEquals(setOf("/synthetic/selected"), selection.keys().asSequence().toSet())
        assertTrue(selection.getBoolean("/synthetic/selected"))
        assertEquals(512L, session.screenState.cleanedBytes)
        assertEquals("未勾选，保留", session.screenState.items.single { it.path == "/synthetic/retained" }.outcome)
        assertEquals(512L, LastCleanupStore.read(app).first.single().bytes)
    }

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
            assertTrue(field<Boolean>(session, "autoScanStarted"))
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

    @Test fun lateCleanupReplyCannotReplaceAReviewAfterDisconnection() = withSession { session, dispatcher ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val histories = AtomicInteger()
        val item = WorkbenchItem("profile:old", "profile", "rules", "", "", "rule_trash", "old", "旧记录",
            "old.log", "low", "/synthetic/old.log", 512, 1, 0, "仅测试", true)
        val service = Proxy.newProxyInstance(IProfileRootService::class.java.classLoader,
            arrayOf(IProfileRootService::class.java)) { _, method, _ -> when(method.name) {
                "getWhitelistPackages", "getWhitelistPaths" -> "[]"
                "cleanProfileSelected" -> {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    """{"success":true,"deletedBytes":512,"deletedFiles":1,"cleanedCandidates":1}"""
                }
                "recordNativeTask" -> { histories.incrementAndGet(); "{}" }
                else -> "{}"
            } } as IProfileRootService
        set(session, "profileService", service)
        set(session, "profileSnapshotId", "old-snapshot")
        set(session, "snapshotExpiresAtRealtime", SystemClock.elapsedRealtime() + 100_000L)
        field<ReviewHydrationGate>(session, "reviewHydration").finish(true)
        state(session, WorkbenchUiState(profileConnected = true, cacheRequired = false, scanReady = true,
            expiresAtRealtime = SystemClock.elapsedRealtime() + 100_000L, items = listOf(item), selectedIds = setOf(item.id)))
        try {
            session.cleanSelection()
            await(dispatcher) { entered.count == 0L }
            field<android.content.ServiceConnection>(session, "profileConnection").onServiceDisconnected(null)
            state(session, session.screenState.copy(phase = "较新的扫描记录", items = listOf(item.copy(id = "profile:new"))))
            LastCleanupStore.save(app, emptyList(), listOf(GeneralJunkUiItem("较新的清理记录", 2, 1024, 0, "/synthetic/new.log")))
            release.countDown()
            await(dispatcher) { field<CoroutineScope>(session, "lifecycleScope").coroutineContext[Job]!!.children.none { !it.isCompleted } }
            assertEquals("较新的扫描记录", session.screenState.phase)
            assertEquals("profile:new", session.screenState.items.single().id)
            assertFalse(session.screenState.cleanupCompleted)
            assertEquals(0, histories.get())
            assertEquals("较新的清理记录", LastCleanupStore.read(app).second.single().name)
        } finally { release.countDown() }
    }

    @Test fun disconnectedPreflightCannotSubmitAnOldSelectionToANewSnapshot() = withSession { session, dispatcher ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanups = AtomicInteger()
        val service = profileService { method -> when (method) {
            "getWhitelistPackages" -> {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); "[]"
            }
            "cleanProfileSelected" -> { cleanups.incrementAndGet(); "{\"success\":true}" }
            else -> null
        } }
        ready(session, service)
        try {
            session.cleanSelection()
            await(dispatcher) { entered.count == 0L }
            field<android.content.ServiceConnection>(session, "profileConnection").onServiceDisconnected(null)
            set(session, "profileSnapshotId", "new-snapshot")
            state(session, session.screenState.copy(phase = "较新的扫描记录"))
            release.countDown()
            await(dispatcher) { field<CoroutineScope>(session, "lifecycleScope").coroutineContext[Job]!!.children.none { !it.isCompleted } }
            assertEquals("A disconnected request must not send a later destructive RPC", 0, cleanups.get())
            assertEquals("较新的扫描记录", session.screenState.phase)
        } finally { release.countDown() }
    }

    @Test fun cleanupWaitsUntilItsInterruptedReviewIsDurable() = pendingMutationIsDurable(quarantine = false)

    @Test fun quarantineWaitsUntilItsInterruptedReviewIsDurable() = pendingMutationIsDurable(quarantine = true)

    @Test fun stoppingPreflightPreventsCleanupAfterTheRootCancelHasReturned() = withSession { session, dispatcher ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanups = AtomicInteger()
        val service = profileService { method -> when (method) {
            "getWhitelistPackages" -> {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); "[]"
            }
            "cleanProfileSelected" -> { cleanups.incrementAndGet(); "{\"success\":true}" }
            else -> null
        } }
        val item = ready(session, service)
        try {
            session.cleanSelection()
            await(dispatcher) { entered.count == 0L }
            session.stopTask()
            await(dispatcher) { field<Job?>(session, "stopJob")?.isActive == false }
            release.countDown()
            await(dispatcher) { !session.screenState.running }
            assertEquals(0, cleanups.get())
            assertFalse(session.screenState.scanReady)
            assertEquals(setOf(item.id), session.screenState.selectedIds)
            assertTrue(session.screenState.phase.contains("已停止"))
            assertFalse(ScanReviewStore.read(app, "rules")!!.getBoolean("scanReady"))
        } finally { release.countDown() }
    }

    private fun pendingMutationIsDurable(quarantine: Boolean) = withSession { session, dispatcher ->
        val diskEntered = CountDownLatch(1)
        val releaseDisk = CountDownLatch(1)
        val mutationEntered = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val service = profileService { method -> when (method) {
            "cleanProfileSelected", "quarantineProfileSelected" -> {
                mutationEntered.countDown(); check(releaseMutation.await(5, TimeUnit.SECONDS))
                "{\"success\":true,\"quarantinedCandidates\":1}"
            }
            else -> null
        } }
        val item = ready(session, service, risk = if (quarantine) "high" else "low")
        session.saveReview()
        assertTrue(ScanReviewStore.read(app, "rules")!!.getBoolean("scanReady"))
        ScanReviewStore.save(app, "disk-barrier") {
            diskEntered.countDown(); check(releaseDisk.await(5, TimeUnit.SECONDS)); JSONObject()
        }
        assertTrue(diskEntered.await(5, TimeUnit.SECONDS))
        try {
            if (quarantine) session.quarantineItem(item) else session.cleanSelection()
            dispatcher.scheduler.runCurrent()
            assertFalse("Root mutation must wait for durable interruption state", mutationEntered.await(150, TimeUnit.MILLISECONDS))
            releaseDisk.countDown()
            await(dispatcher) { mutationEntered.count == 0L }
            val saved = ScanReviewStore.read(app, "rules")!!
            assertTrue(saved.getBoolean("running"))
            assertFalse(saved.getBoolean("scanReady"))
            val restoredScope = CoroutineScope(SupervisorJob() + dispatcher)
            val restored = ScanWorkbenchSession(app, restoredScope)
            try {
                set(restored, "profileBound", true)
                restored.initialize("rules")
                await(dispatcher) { !restored.screenState.restoringReview }
                assertFalse(restored.screenState.scanReady)
                assertEquals(setOf(item.id), restored.screenState.selectedIds)
                assertTrue(restored.screenState.phase.contains("结果未确认"))
            } finally { restored.close(); restoredScope.cancel() }
        } finally { releaseDisk.countDown(); releaseMutation.countDown() }
    }

    private fun profileService(reply: (String) -> String?): IProfileRootService =
        Proxy.newProxyInstance(IProfileRootService::class.java.classLoader,
            arrayOf(IProfileRootService::class.java)) { _, method, _ ->
            reply(method.name) ?: when (method.name) {
                "getWhitelistPackages", "getWhitelistPaths" -> "[]"
                else -> "{}"
            }
        } as IProfileRootService

    private fun ready(session: ScanWorkbenchSession, service: IProfileRootService, risk: String = "low"): WorkbenchItem {
        val item = WorkbenchItem("profile:sample", "profile", "rules", "", "", "rule_trash", "sample", "测试记录",
            "sample.log", risk, "/synthetic/sample.log", 512, 1, 0, "仅测试", true)
        val expiry = SystemClock.elapsedRealtime() + 100_000L
        set(session, "scanProfile", "rules")
        set(session, "profileService", service)
        set(session, "profileSnapshotId", "old-snapshot")
        set(session, "snapshotExpiresAtRealtime", expiry)
        field<ReviewHydrationGate>(session, "reviewHydration").finish(true)
        state(session, WorkbenchUiState(profileConnected = true, cacheRequired = false, scanReady = true,
            expiresAtRealtime = expiry, items = listOf(item), selectedIds = setOf(item.id)))
        return item
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
            .put("cleanedBytes", 2048).put("cleanedFiles", 7).put("cleanedDirectories", 3) }
        set(session, "profileBound", true)
        session.initialize("fragments")
        await(dispatcher) { !session.screenState.restoringReview }
        assertTrue(session.screenState.cleanupCompleted)
        assertEquals(2048L, session.screenState.cleanedBytes)
        assertEquals(3L, session.screenState.cleanedDirectories)
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
