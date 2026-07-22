package io.github.xgl34222220.baize.root

import android.os.RemoteCallbackList
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class TaskCoordinator(
    private val stateDir: File = File(RootPaths.STATE_DIR)
) {
    val cancelled = AtomicBoolean(false)
    private val taskRunning = AtomicBoolean(false)
    private val callbacks = RemoteCallbackList<ITaskProgressCallback>()
    @Volatile private var taskStateJson: String = idleState()
    @Volatile private var lastCallbackAt = 0L

    fun runExclusive(
        operation: String,
        phase: String,
        failureCode: String,
        block: (startedRealtime: Long) -> String
    ): String {
        if (!taskRunning.compareAndSet(false, true)) return busy(operation)
        cancelled.set(false)
        val started = SystemClock.elapsedRealtime()
        setState(
            JSONObject()
                .put("running", true)
                .put("operation", operation)
                .put("phase", phase)
                .put("elapsedMs", 0L)
                .toString(),
            force = true
        )
        return try {
            block(started)
        } catch (error: Throwable) {
            failure(failureCode, error)
        } finally {
            taskRunning.set(false)
            taskStateJson = idleState()
            publish(true)
        }
    }

    fun isBusy(): Boolean = taskRunning.get() || RootFileStore.readEnv(File(stateDir, "running.env")).length() > 0

    fun publishExternal(state: JSONObject, force: Boolean = false) {
        setState(state.toString(), force)
    }

    fun setModuleState(operation: String, phase: String, startedRealtime: Long, extras: JSONObject? = null) {
        val state = extras ?: JSONObject()
        state.put("running", true)
            .put("operation", operation)
            .put("phase", phase)
            .put("elapsedMs", (SystemClock.elapsedRealtime() - startedRealtime).coerceAtLeast(0L))
        setState(state.toString())
    }

    fun update(
        operation: String,
        phase: String,
        current: Int,
        total: Int,
        currentPath: String,
        startedRealtime: Long,
        deletedBytes: Long = 0L,
        deletedFiles: Long = 0L,
        failures: Int = 0
    ) {
        setState(
            JSONObject()
                .put("running", true)
                .put("operation", operation)
                .put("phase", phase)
                .put("current", current)
                .put("total", total)
                .put("currentPath", currentPath)
                .put("deletedBytes", deletedBytes)
                .put("deletedFiles", deletedFiles)
                .put("failures", failures)
                .put("elapsedMs", (SystemClock.elapsedRealtime() - startedRealtime).coerceAtLeast(0L))
                .toString()
        )
    }

    fun currentState(): String {
        val running = RootFileStore.readEnv(File(stateDir, "running.env"))
        if (running.length() > 0) {
            running.put("running", true)
            running.put("operation", running.optString("operation", running.optString("mode", "module-task")))
            running.put("cancelRequested", cancelled.get())
            return running.toString()
        }
        return runCatching {
            JSONObject(taskStateJson).put("cancelRequested", cancelled.get()).toString()
        }.getOrDefault(taskStateJson)
    }

    fun cancelCurrentTask() {
        cancelled.set(true)
        runCatching {
            stateDir.mkdirs()
            File(stateDir, "stop").writeText("1\n")
        }
    }

    fun register(callback: ITaskProgressCallback?) {
        if (callback == null) return
        callbacks.register(callback)
        runCatching { callback.onTaskProgress(currentState()) }
    }

    fun unregister(callback: ITaskProgressCallback?) {
        if (callback != null) callbacks.unregister(callback)
    }

    fun busy(operation: String): String = JSONObject()
        .put("success", false)
        .put("error", "busy")
        .put("operation", operation)
        .put("message", "已有扫描、清理或归类任务正在运行")
        .toString()

    private fun setState(state: String, force: Boolean = false) {
        taskStateJson = state
        publish(force)
    }

    private fun publish(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastCallbackAt < 220L) return
        lastCallbackAt = now
        val state = taskStateJson
        val count = callbacks.beginBroadcast()
        try {
            for (index in 0 until count) runCatching { callbacks.getBroadcastItem(index).onTaskProgress(state) }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun failure(code: String, error: Throwable): String = JSONObject()
        .put("success", false)
        .put("error", code)
        .put("message", error.message ?: error.javaClass.simpleName)
        .toString()

    private fun idleState(): String = JSONObject()
        .put("running", false)
        .put("operation", "idle")
        .put("phase", "等待任务")
        .toString()
}
