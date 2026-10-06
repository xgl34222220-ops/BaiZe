package io.github.xgl34222220.baize

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.MutableState
import androidx.lifecycle.lifecycleScope
import io.github.xgl34222220.baize.root.IBaiZeRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Executes the dashboard's actual cleanup callbacks; Root replies are the only substituted boundary. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DashboardCleanupAccountingTest {
    @Test fun missingSnapshotCapacityNeverBecomesZeroInUiPreferencesOrRecordedTask() = fixture(
        JSONObject().put("success", true).put("deletedFiles", 1).put("cleanedCandidates", 1)
    ) { activity, record ->
        assertFalse(activity.state().lastReleasedKnown)
        assertTrue(activity.state().taskPhase.contains("释放量无法测量"))
        assertFalse(activity.state().taskPhase.contains("实际释放"))
        assertEquals("unknown", record.getString("releaseState")); assertTrue(record.isNull("bytes"))
        assertTrue(record.isNull("releasedBytes"))
        assertFalse(activity.getSharedPreferences("baize_v2", 0).getBoolean("last_clean_bytes_known", true))
    }

    @Test fun measuredDirectoryOnlyZeroRemainsSuccessfulAndReferencesItsAuditChild() = fixture(
        JSONObject().put("success", true).put("deletedBytes", 0).put("deletedDirectories", 2)
            .put("cleanedCandidates", 1).put("auditEventId", "owned-child")
    ) { activity, record ->
        assertTrue(activity.state().lastReleasedKnown)
        assertTrue(activity.state().taskPhase.contains("目录 2"))
        assertEquals("measured", record.getString("releaseState")); assertEquals(0L, record.getLong("releasedBytes"))
        assertTrue(record.getBoolean("success"))
        assertEquals("owned-child", record.getJSONArray("includedAuditEventIds").getString(0))
    }

    @Test fun aLaterUnmeasuredFailurePreservesKnownCacheBytesAsPartial() = fixture(
        JSONObject().put("success", false).put("error", "profile_unavailable").put("auditEventId", "failed-child"),
        cache = JSONObject().put("success", true).put("deletedBytes", 4096).put("deletedFiles", 1)
    ) { activity, record ->
        assertFalse(activity.state().lastReleasedKnown); assertEquals(4096L, activity.state().lastReleased)
        assertTrue(activity.state().taskPhase.contains("部分释放量无法测量"))
        assertEquals("partial", record.getString("releaseState")); assertEquals(4096L, record.getLong("releasedBytes"))
        assertFalse(record.getBoolean("success")); assertEquals(1, record.getInt("errors"))
    }

    @Test fun cancelledFailedReplyKeepsItsConfirmedBytesAndDoesNotClaimZero() = fixture(
        JSONObject().put("success", false).put("cancelled", true).put("error", "stopped").put("deletedBytes", 72)
    ) { activity, record ->
        assertTrue(activity.state().lastReleasedKnown); assertEquals(72L, activity.state().lastReleased)
        assertTrue(activity.state().taskPhase.contains("已安全停止"))
        assertTrue(record.getBoolean("cancelled")); assertEquals(72L, record.getLong("releasedBytes"))
    }

    @Test fun moduleCompletionWithoutBytesDoesNotPersistAKnownZero() = fixture(
        JSONObject().put("success", true).put("latest", JSONObject().put("files", 0).put("result", "任务完成")),
        module = true
    ) { activity, _ ->
        assertFalse(activity.state().lastReleasedKnown)
        assertTrue(activity.state().taskPhase.contains("释放量无法测量"))
        assertFalse(activity.getSharedPreferences("baize_v2", 0).getBoolean("last_clean_bytes_known", true))
    }

    private fun fixture(profile: JSONObject, cache: JSONObject? = null, module: Boolean = false,
        block: (MiuixDashboardActivity, JSONObject) -> Unit) {
        val dispatcher = StandardTestDispatcher(); Dispatchers.setMain(dispatcher)
        val activity = Robolectric.buildActivity(MiuixDashboardActivity::class.java).get()
        val records = CopyOnWriteArrayList<String>()
        val service = Proxy.newProxyInstance(IProfileRootService::class.java.classLoader,
            arrayOf(IProfileRootService::class.java)) { _, method, arguments -> when (method.name) {
                "cleanProfileSelected", "runModuleTask" -> profile.toString()
                "recordNativeTask" -> { records += arguments[0] as String; "{\"success\":true}" }
                "getTaskState" -> "{\"running\":true}"
                else -> if (method.returnType == String::class.java) "" else null
            }
        } as IProfileRootService
        try {
            activity.getSharedPreferences("baize_v2", 0).edit().clear().commit()
            activity.set("rootService", service)
            activity.set("schedulerState", androidx.compose.runtime.mutableStateOf(SchedulerUiState(notifyOnComplete = false)))
            activity.set("safeSnapshotId", "owned-snapshot"); activity.set("safeSnapshotCount", 1)
            activity.set("snapshotExpiresAtElapsed", SystemClock.elapsedRealtime() + 60_000L)
            if (cache != null) {
                val cacheService = Proxy.newProxyInstance(IBaiZeRootService::class.java.classLoader,
                    arrayOf(IBaiZeRootService::class.java)) { _, method, _ -> when (method.name) {
                        "cleanSelected" -> cache.toString()
                        "getTaskState" -> "{\"running\":true}"
                        else -> if (method.returnType == String::class.java) "" else null
                    }
                } as IBaiZeRootService
                activity.set("cacheService", cacheService)
                activity.set("cacheSnapshotId", "owned-cache-snapshot"); activity.set("cacheSnapshotCount", 1)
            }
            if (module) activity.javaClass.getDeclaredMethod("runModuleClean", IProfileRootService::class.java)
                .apply { isAccessible = true }.invoke(activity, service)
            else activity.javaClass.getDeclaredMethod("cleanNativeSnapshots").apply { isAccessible = true }.invoke(activity)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while ((activity.state().running || (!module && records.isEmpty())) && System.nanoTime() < deadline) {
                dispatcher.scheduler.runCurrent(); Thread.sleep(10)
            }
            assertFalse("Dashboard cleanup did not finish", activity.state().running)
            if (!module) assertEquals(1, records.size)
            block(activity, if (module) JSONObject() else JSONObject(records.single()))
        } finally { activity.lifecycleScope.cancel(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }
    }
    private fun MiuixDashboardActivity.set(name: String, value: Any) = javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(this, value)
    @Suppress("UNCHECKED_CAST") private fun MiuixDashboardActivity.state() =
        (javaClass.getDeclaredField("dashboardState").apply { isAccessible = true }.get(this) as MutableState<DashboardUiState>).value
}
