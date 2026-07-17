package io.github.xgl34222220.baize.root

import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Persistent root process for non-cache cleaning profiles. */
class BaiZeProfileRootService : RootService() {
    private val cancelled = AtomicBoolean(false)
    private val taskRunning = AtomicBoolean(false)
    private val engine by lazy { NativeProfileEngine(this, cancelled) }

    @Volatile
    private var taskState: String = idleState()

    private val binder = object : IProfileRootService.Stub() {
        override fun ping(): String = JSONObject()
            .put("uid", Process.myUid())
            .put("root", Process.myUid() == 0)
            .put("module", File("/data/adb/modules/baize_v2/module.prop").isFile)
            .put("deepRules", File("/data/adb/modules/baize_v2/config/deep.rules").isFile)
            .put("engine", "native-profile-engine-v5")
            .toString()

        override fun getProfileCatalog(): String = engine.catalog()

        override fun scanProfile(profile: String?, optionsJson: String?): String {
            if (!taskRunning.compareAndSet(false, true)) return busy("profile-scan")
            cancelled.set(false)
            val started = SystemClock.elapsedRealtime()
            return try {
                engine.scan(profile.orEmpty(), optionsJson.orEmpty()) { progress ->
                    updateState("profile-scan", progress, started)
                }
            } catch (error: Throwable) {
                JSONObject()
                    .put("error", "profile_scan_failed")
                    .put("message", error.message ?: error.javaClass.simpleName)
                    .toString()
            } finally {
                taskRunning.set(false)
                taskState = idleState()
            }
        }

        override fun getProfilePage(snapshotId: String?, offset: Int, limit: Int): String {
            if (taskRunning.get()) return busy("profile-page")
            cancelled.set(false)
            return engine.page(snapshotId.orEmpty(), offset, limit)
        }

        override fun cleanProfileSelected(snapshotId: String?, selectionJson: String?, optionsJson: String?): String {
            if (!taskRunning.compareAndSet(false, true)) return busy("profile-clean")
            cancelled.set(false)
            val started = SystemClock.elapsedRealtime()
            return try {
                engine.clean(snapshotId.orEmpty(), selectionJson.orEmpty(), optionsJson.orEmpty()) { progress ->
                    updateState("profile-clean", progress, started)
                }
            } catch (error: Throwable) {
                JSONObject()
                    .put("error", "profile_clean_failed")
                    .put("message", error.message ?: error.javaClass.simpleName)
                    .toString()
            } finally {
                taskRunning.set(false)
                taskState = idleState()
            }
        }

        override fun getTaskState(): String = runCatching {
            JSONObject(taskState).put("cancelRequested", cancelled.get()).toString()
        }.getOrDefault(taskState)

        override fun cancelCurrentTask() {
            cancelled.set(true)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun updateState(operation: String, progress: NativeProfileEngine.Progress, started: Long) {
        taskState = JSONObject()
            .put("running", true)
            .put("operation", operation)
            .put("phase", progress.phase)
            .put("current", progress.current)
            .put("total", progress.total)
            .put("currentPath", progress.path)
            .put("deletedBytes", progress.bytes)
            .put("deletedFiles", progress.files)
            .put("failures", progress.failures)
            .put("elapsedMs", (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L))
            .toString()
    }

    private fun busy(operation: String): String = JSONObject()
        .put("success", false)
        .put("error", "busy")
        .put("operation", operation)
        .put("message", "已有任务正在运行")
        .toString()

    private fun idleState(): String = JSONObject()
        .put("running", false)
        .put("operation", "idle")
        .put("phase", "等待任务")
        .toString()
}
