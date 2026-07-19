package io.github.xgl34222220.baize.root

import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Alpha 42.4 cache scanner bridge.
 * Discovery and directory measurement run in the module's arm64 C engine. Cleaning still goes
 * through the mature module cleaner so the existing safety checks and history format remain intact.
 */
class BaiZeRootService : RootService() {
    private data class CacheItem(
        val packageName: String,
        val category: String,
        val files: Long,
        val bytes: Long,
        val directories: Long,
        val path: String
    )

    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val resultLock = Any()

    @Volatile private var snapshotId = ""
    @Volatile private var snapshotCreatedAt = 0L
    @Volatile private var items: List<CacheItem> = emptyList()
    @Volatile private var taskState = idleState()

    private val binder = object : IBaiZeRootService.Stub() {
        override fun ping(): String = JSONObject()
            .put("uid", Process.myUid())
            .put("root", Process.myUid() == 0)
            .put("engine", "native-c-arm64-cache-v42.4")
            .put("available", File(MODULE_DIR, "bin/arm64-v8a/baize_engine").canExecute())
            .put("snapshotReady", snapshotValid(snapshotId))
            .toString()

        override fun scanCandidates(whitelistJson: String?): String {
            if (!running.compareAndSet(false, true)) return busy("cache-scan")
            cancelled.set(false)
            val started = SystemClock.elapsedRealtime()
            return try {
                runNativeScan(whitelistJson.orEmpty(), started)
            } catch (error: Throwable) {
                JSONObject()
                    .put("error", "native_cache_scan_failed")
                    .put("message", error.message ?: error.javaClass.simpleName)
                    .toString()
            } finally {
                running.set(false)
                taskState = idleState()
            }
        }

        override fun getResultPage(snapshotId: String?, offset: Int, limit: Int): String {
            val id = snapshotId.orEmpty()
            if (!snapshotValid(id)) {
                return JSONObject()
                    .put("error", "snapshot_expired")
                    .put("message", "缓存扫描结果已失效，请重新扫描")
                    .toString()
            }
            val safeOffset = offset.coerceAtLeast(0)
            val safeLimit = limit.coerceIn(1, 100)
            val snapshot = synchronized(resultLock) { items }
            val end = (safeOffset + safeLimit).coerceAtMost(snapshot.size)
            val array = JSONArray()
            if (safeOffset < end) {
                snapshot.subList(safeOffset, end).forEach { item ->
                    array.put(
                        JSONObject()
                            .put("appName", item.packageName)
                            .put("packageName", item.packageName)
                            .put("categoryLabel", item.category.substringBeforeLast(':'))
                            .put("path", item.path)
                            .put("bytes", item.bytes)
                            .put("files", item.files)
                            .put("directories", item.directories)
                            .put("measured", true)
                            .put("complete", true)
                    )
                }
            }
            return JSONObject()
                .put("snapshotId", id)
                .put("offset", safeOffset)
                .put("limit", safeLimit)
                .put("total", snapshot.size)
                .put("items", array)
                .toString()
        }

        override fun cleanSelected(snapshotId: String?, selectionJson: String?, whitelistJson: String?): String =
            JSONObject()
                .put("error", "module_clean_required")
                .put("message", "请使用模块一键清理执行二次安全校验")
                .toString()

        override fun getTaskState(): String {
            val runningState = readEnv(File(STATE_DIR, "running.env"))
            if (runningState.length() > 0) {
                return runningState
                    .put("running", true)
                    .put("cancelRequested", cancelled.get())
                    .toString()
            }
            return runCatching {
                JSONObject(taskState).put("cancelRequested", cancelled.get()).toString()
            }.getOrDefault(taskState)
        }

        override fun cancelCurrentTask() {
            cancelled.set(true)
            File(STATE_DIR).mkdirs()
            runCatching { File(STATE_DIR, "stop").writeText("1\n") }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun runNativeScan(whitelistJson: String, started: Long): String {
        val cleaner = File(MODULE_DIR, "cleaner.sh")
        if (!cleaner.isFile) {
            return JSONObject()
                .put("error", "cleaner_missing")
                .put("message", "模块原生扫描入口缺失，请重新刷入完整模块")
                .toString()
        }

        val stateDir = File(STATE_DIR).apply { mkdirs() }
        File(stateDir, "stop").delete()
        writePackageWhitelist(File(stateDir, "native-cache-packages.conf"), whitelistJson)
        val logDir = File(stateDir, "logs").apply { mkdirs() }
        val appLog = File(logDir, "app-cache-scan-${System.currentTimeMillis()}.log")
        taskState = JSONObject()
            .put("running", true)
            .put("operation", "native-cache-scan")
            .put("phase", "正在启动 C 原生缓存扫描")
            .put("elapsedMs", 0)
            .toString()

        val process = ProcessBuilder("/system/bin/sh", cleaner.absolutePath, "cache-scan", "app")
            .redirectErrorStream(true)
            .redirectOutput(appLog)
            .start()

        while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
            if (cancelled.get()) runCatching { File(stateDir, "stop").writeText("1\n") }
            val state = readEnv(File(stateDir, "running.env"))
            taskState = state
                .put("running", true)
                .put("operation", "native-cache-scan")
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .toString()
        }

        val code = process.exitValue()
        val elapsed = SystemClock.elapsedRealtime() - started
        if (code != 0) {
            val message = if (code == 9 || cancelled.get()) "缓存扫描已停止" else tailText(appLog, 4_000).ifBlank { "原生缓存扫描失败（代码 $code）" }
            return JSONObject()
                .put("cancelled", code == 9 || cancelled.get())
                .put("error", if (code == 9) JSONObject.NULL else "native_scan_exit_$code")
                .put("message", message)
                .put("elapsedMs", elapsed)
                .toString()
        }

        val parsed = parseItems(File(stateDir, "cache_scan.items.tsv"))
        val newSnapshot = UUID.randomUUID().toString()
        synchronized(resultLock) { items = parsed }
        snapshotId = newSnapshot
        snapshotCreatedAt = System.currentTimeMillis()
        val latest = readEnv(File(stateDir, "latest.env"))
        return JSONObject()
            .put("cancelled", false)
            .put("elapsedMs", elapsed)
            .put("snapshotId", newSnapshot)
            .put("snapshotExpiresInMs", SNAPSHOT_MAX_AGE_MS)
            .put("totalCandidates", parsed.size)
            .put("whitelisted", latest.optInt("whitelisted", 0))
            .put("totalFiles", latest.optLong("regular_files", 0L))
            .put("totalBytes", latest.optLong("bytes", 0L))
            .put("engine", latest.optString("engine", "native-c-arm64"))
            .toString()
    }

    private fun writePackageWhitelist(file: File, raw: String) {
        val values = linkedSetOf<String>()
        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (PACKAGE_NAME.matches(value)) values += value
            }
        }
        file.parentFile?.mkdirs()
        file.writeText(values.sorted().joinToString(separator = "\n", postfix = if (values.isEmpty()) "" else "\n"))
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    private fun parseItems(file: File): List<CacheItem> {
        if (!file.isFile) return emptyList()
        return buildList {
            file.forEachLine { raw ->
                val columns = raw.split('\t', limit = 6)
                if (columns.size < 6 || columns[0] == "package") return@forEachLine
                val packageName = columns[0].trim()
                val path = columns[5].trim()
                if (!PACKAGE_NAME.matches(packageName) || !path.startsWith("/")) return@forEachLine
                add(
                    CacheItem(
                        packageName = packageName,
                        category = columns[1].trim().take(120),
                        files = columns[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                        bytes = columns[3].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                        directories = columns[4].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                        path = path.take(4096)
                    )
                )
            }
        }.sortedWith(compareByDescending<CacheItem> { it.bytes }.thenBy { it.packageName }.thenBy { it.path })
    }

    private fun snapshotValid(id: String): Boolean =
        id.isNotBlank() && id == snapshotId && System.currentTimeMillis() - snapshotCreatedAt in 0..SNAPSHOT_MAX_AGE_MS

    private fun readEnv(file: File): JSONObject {
        val result = JSONObject()
        if (!file.isFile) return result
        file.forEachLine { raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#") || !line.contains('=')) return@forEachLine
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim()
            result.put(key, value.toLongOrNull() ?: value)
        }
        return result
    }

    private fun tailText(file: File, maxChars: Int): String = runCatching {
        val text = file.readText()
        if (text.length <= maxChars) text else text.takeLast(maxChars)
    }.getOrDefault("")

    private fun busy(operation: String): String = JSONObject()
        .put("error", "busy")
        .put("operation", operation)
        .put("message", "已有任务正在运行")
        .toString()

    private fun idleState(): String = JSONObject()
        .put("running", false)
        .put("operation", "idle")
        .put("phase", "等待任务")
        .toString()

    companion object {
        private const val MODULE_DIR = "/data/adb/modules/baize_v2"
        private const val STATE_DIR = "/data/adb/baize-v2"
        private const val SNAPSHOT_MAX_AGE_MS = 30L * 60L * 1000L
        private val PACKAGE_NAME = Regex("""^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_-]+)+$""")
    }
}
