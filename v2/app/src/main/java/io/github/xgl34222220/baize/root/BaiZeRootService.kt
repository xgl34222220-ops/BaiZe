package io.github.xgl34222220.baize.root

import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v2.1.0 indexed cache task bridge.
 *
 * The module owns the persistent task lock, progress file and scan snapshot. The RootService only
 * launches the task and exposes those files to every UI entry, so leaving a page never loses the
 * real progress and every clean action consumes the same on-disk snapshot without rediscovery.
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

    private data class CleanReport(
        val cleanedCandidates: Int,
        val deletedFiles: Long,
        val deletedBytes: Long,
        val failures: Int
    )

    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val resultLock = Any()

    @Volatile private var snapshotId = ""
    @Volatile private var snapshotCreatedAt = 0L
    @Volatile private var snapshotFiles = 0L
    @Volatile private var snapshotBytes = 0L
    @Volatile private var snapshotWhitelisted = 0
    @Volatile private var snapshotVisitedFiles = 0L
    @Volatile private var snapshotVisitedDirs = 0L
    @Volatile private var snapshotFirstResultMs = 0L
    @Volatile private var snapshotEngineElapsedMs = 0L
    @Volatile private var snapshotItemsPerSecond = 0L
    @Volatile private var items: List<CacheItem> = emptyList()
    @Volatile private var taskStateJson = idleState()

    private val binder = object : IBaiZeRootService.Stub() {
        override fun ping(): String {
            val ready = restoreSnapshotFromDisk()
            return JSONObject()
                .put("uid", Process.myUid())
                .put("root", Process.myUid() == 0)
                .put("engine", "native-c-arm64-cache-v43.0-alpha1-index")
                .put("available", File(MODULE_DIR, "bin/arm64-v8a/baize_engine").canExecute())
                .put("snapshotReady", ready)
                .put("snapshotId", if (ready) snapshotId else "")
                .put("snapshotItems", if (ready) synchronized(resultLock) { items.size } else 0)
                .put("snapshotFiles", if (ready) snapshotFiles else 0L)
                .put("snapshotBytes", if (ready) snapshotBytes else 0L)
                .put("snapshotWhitelisted", if (ready) snapshotWhitelisted else 0)
                .put("visitedFiles", if (ready) snapshotVisitedFiles else 0L)
                .put("visitedDirs", if (ready) snapshotVisitedDirs else 0L)
                .put("firstResultMs", if (ready) snapshotFirstResultMs else 0L)
                .put("engineElapsedMs", if (ready) snapshotEngineElapsedMs else 0L)
                .put("itemsPerSecond", if (ready) snapshotItemsPerSecond else 0L)
                .put("snapshotCreatedAt", if (ready) snapshotCreatedAt else 0L)
                .put("snapshotExpiresInMs", SNAPSHOT_MAX_AGE_MS)
                .put("taskRunning", moduleTaskAlive())
                .toString()
        }

        override fun scanCandidates(whitelistJson: String?): String {
            if (moduleTaskAlive()) return busy("cache-scan")
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
                taskStateJson = idleState()
            }
        }

        override fun getResultPage(requestedSnapshotId: String?, offset: Int, limit: Int): String {
            restoreSnapshotFromDisk()
            val id = requestedSnapshotId.orEmpty()
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

        override fun cleanSelected(
            requestedSnapshotId: String?,
            selectionJson: String?,
            whitelistJson: String?
        ): String {
            if (moduleTaskAlive()) return busy("cache-clean")
            if (!restoreSnapshotFromDisk() || !snapshotValid(requestedSnapshotId.orEmpty())) {
                return JSONObject()
                    .put("error", "snapshot_expired")
                    .put("message", "缓存扫描快照已失效，不会自动重新扫描")
                    .toString()
            }
            val allSafe = runCatching {
                JSONObject(selectionJson.orEmpty()).optBoolean("__all_safe__", false)
            }.getOrDefault(false)
            if (!allSafe) {
                return JSONObject()
                    .put("error", "selection_required")
                    .put("message", "没有授权清理当前缓存快照")
                    .toString()
            }
            if (!running.compareAndSet(false, true)) return busy("cache-clean")
            cancelled.set(false)
            val started = SystemClock.elapsedRealtime()
            return try {
                runSnapshotClean(whitelistJson.orEmpty(), started)
            } catch (error: Throwable) {
                JSONObject()
                    .put("error", "cache_snapshot_clean_failed")
                    .put("message", error.message ?: error.javaClass.simpleName)
                    .toString()
            } finally {
                running.set(false)
                taskStateJson = idleState()
            }
        }

        override fun getTaskState(): String {
            val alive = moduleTaskAlive()
            if (alive) {
                val runningState = readEnv(File(STATE_DIR, "running.env"))
                if (runningState.length() > 0) {
                    return runningState
                        .put("running", true)
                        .put("cancelRequested", cancelled.get())
                        .toString()
                }
            } else {
                repairStaleTaskFiles()
            }
            return runCatching {
                JSONObject(taskStateJson)
                    .put("running", running.get())
                    .put("cancelRequested", cancelled.get())
                    .toString()
            }.getOrDefault(taskStateJson)
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
        taskStateJson = JSONObject()
            .put("running", true)
            .put("operation", "native-cache-scan")
            .put("mode", "cache-scan")
            .put("phase", "正在启动 C 原生缓存扫描")
            .put("elapsedMs", 0)
            .toString()

        val process = ProcessBuilder("/system/bin/sh", cleaner.absolutePath, "cache-scan", "app")
            .redirectErrorStream(true)
            .redirectOutput(appLog)
            .start()

        pollModuleProcess(process, stateDir, started, "native-cache-scan")
        val code = process.exitValue()
        val elapsed = SystemClock.elapsedRealtime() - started
        if (code != 0) {
            val wasCancelled = code == 9 || cancelled.get()
            if (code == 3) return busy("cache-scan")
            val result = JSONObject()
                .put("cancelled", wasCancelled)
                .put(
                    "message",
                    if (wasCancelled) "缓存扫描已停止"
                    else tailText(appLog, 4_000).ifBlank { "原生缓存扫描失败（代码 $code）" }
                )
                .put("elapsedMs", elapsed)
            if (!wasCancelled) result.put("error", "native_scan_exit_$code")
            return result.toString()
        }

        if (!restoreSnapshotFromDisk(force = true)) {
            return JSONObject()
                .put("error", "snapshot_missing")
                .put("message", "扫描完成但快照未生成，请查看原始日志")
                .put("elapsedMs", elapsed)
                .toString()
        }
        return JSONObject()
            .put("cancelled", false)
            .put("elapsedMs", elapsed)
            .put("snapshotId", snapshotId)
            .put("snapshotExpiresInMs", SNAPSHOT_MAX_AGE_MS)
            .put("totalCandidates", synchronized(resultLock) { items.size })
            .put("whitelisted", snapshotWhitelisted)
            .put("totalFiles", snapshotFiles)
            .put("totalBytes", snapshotBytes)
            .put("visitedFiles", snapshotVisitedFiles)
            .put("visitedDirs", snapshotVisitedDirs)
            .put("firstResultMs", snapshotFirstResultMs)
            .put("engineElapsedMs", snapshotEngineElapsedMs)
            .put("itemsPerSecond", snapshotItemsPerSecond)
            .put("engine", "native-c-arm64-indexed")
            .toString()
    }

    private fun runSnapshotClean(whitelistJson: String, started: Long): String {
        val cleaner = File(MODULE_DIR, "cleaner.sh")
        if (!cleaner.isFile) {
            return JSONObject()
                .put("error", "cleaner_missing")
                .put("message", "缓存快照清理入口缺失，请重新刷入完整模块")
                .toString()
        }

        val stateDir = File(STATE_DIR).apply { mkdirs() }
        File(stateDir, "stop").delete()
        writePackageWhitelist(File(stateDir, "native-cache-packages.conf"), whitelistJson)
        val beforeCount = synchronized(resultLock) { items.size }
        val logDir = File(stateDir, "logs").apply { mkdirs() }
        val appLog = File(logDir, "app-cache-clean-${System.currentTimeMillis()}.log")
        taskStateJson = JSONObject()
            .put("running", true)
            .put("operation", "cache-snapshot-clean")
            .put("mode", "cache-clean")
            .put("phase", "正在清理刚才的缓存扫描快照")
            .put("elapsedMs", 0)
            .toString()

        val process = ProcessBuilder("/system/bin/sh", cleaner.absolutePath, "cache-clean", "app")
            .redirectErrorStream(true)
            .redirectOutput(appLog)
            .start()

        pollModuleProcess(process, stateDir, started, "cache-snapshot-clean")
        val code = process.exitValue()
        val elapsed = SystemClock.elapsedRealtime() - started
        val wasCancelled = code == 9 || cancelled.get()
        val latest = readEnv(File(stateDir, "latest.env"))
        val report = parseCleanReport(File(stateDir, "reports/latest.tsv"))
        val output = tailText(appLog, 6_000)
        val message = latest.optString("result").ifBlank {
            when {
                wasCancelled -> "缓存快照清理已停止"
                code == 0 -> "缓存快照清理完成"
                else -> output.lineSequence().filter { it.isNotBlank() }.lastOrNull()
                    ?: "缓存快照清理失败（代码 $code）"
            }
        }

        if (code == 3) return busy("cache-clean")
        if (code == 0 && !wasCancelled) clearSnapshotMemory()
        val result = JSONObject()
            .put("success", code == 0)
            .put("cancelled", wasCancelled)
            .put("elapsedMs", elapsed)
            .put("deletedBytes", latest.optLong("bytes", report.deletedBytes).coerceAtLeast(0L))
            .put("deletedFiles", latest.optLong("files", report.deletedFiles).coerceAtLeast(0L))
            .put("deletedDirectories", 0L)
            .put(
                "cleanedCandidates",
                if (report.cleanedCandidates > 0) report.cleanedCandidates
                else if (code == 0) beforeCount else 0
            )
            .put("failures", latest.optInt("errors", report.failures).coerceAtLeast(0))
            .put("message", message)
            .put("output", output)
        if (code != 0 && !wasCancelled) result.put("error", "cache_clean_exit_$code")
        return result.toString()
    }

    private fun pollModuleProcess(
        process: java.lang.Process,
        stateDir: File,
        started: Long,
        operation: String
    ) {
        while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
            if (cancelled.get()) runCatching { File(stateDir, "stop").writeText("1\n") }
            val state = readEnv(File(stateDir, "running.env"))
            taskStateJson = state
                .put("running", true)
                .put("operation", operation)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .toString()
        }
    }

    private fun restoreSnapshotFromDisk(force: Boolean = false): Boolean {
        val stateFile = File(STATE_DIR, "cache_scan.env")
        val itemFile = File(STATE_DIR, "cache_scan.items.tsv")
        val targetFile = File(STATE_DIR, "cache_scan.targets")
        if (!stateFile.isFile || !itemFile.isFile || !targetFile.isFile) {
            clearSnapshotMemory()
            return false
        }
        val state = readEnv(stateFile)
        val id = state.optString("snapshot_id").trim()
        val epochSeconds = state.optLong("epoch", 0L)
        val createdAt = epochSeconds * 1_000L
        val age = System.currentTimeMillis() - createdAt
        if (id.isBlank() || createdAt <= 0L || age !in 0..SNAPSHOT_MAX_AGE_MS) {
            clearSnapshotMemory()
            return false
        }
        if (!force && id == snapshotId && snapshotValid(id)) return true

        val parsed = parseItems(itemFile)
        val expected = state.optInt("items", parsed.size).coerceAtLeast(0)
        if (expected > 0 && parsed.isEmpty()) {
            clearSnapshotMemory()
            return false
        }
        synchronized(resultLock) { items = parsed }
        snapshotId = id
        snapshotCreatedAt = createdAt
        snapshotFiles = state.optLong("files", 0L).coerceAtLeast(0L)
        snapshotBytes = state.optLong("bytes", 0L).coerceAtLeast(0L)
        val latest = readEnv(File(STATE_DIR, "latest.env"))
        snapshotWhitelisted = latest.optInt("whitelisted", 0).coerceAtLeast(0)
        snapshotVisitedFiles = state.optLong("visited_files", latest.optLong("visited_files", 0L)).coerceAtLeast(0L)
        snapshotVisitedDirs = state.optLong("visited_dirs", latest.optLong("visited_dirs", 0L)).coerceAtLeast(0L)
        snapshotFirstResultMs = state.optLong("first_result_ms", latest.optLong("first_result_ms", 0L)).coerceAtLeast(0L)
        snapshotEngineElapsedMs = state.optLong("engine_elapsed_ms", latest.optLong("engine_elapsed_ms", 0L)).coerceAtLeast(0L)
        snapshotItemsPerSecond = state.optLong("items_per_second", latest.optLong("items_per_second", 0L)).coerceAtLeast(0L)
        return true
    }

    private fun clearSnapshotMemory() {
        synchronized(resultLock) { items = emptyList() }
        snapshotId = ""
        snapshotCreatedAt = 0L
        snapshotFiles = 0L
        snapshotBytes = 0L
        snapshotWhitelisted = 0
    }

    private fun moduleTaskAlive(): Boolean {
        val lockDir = File(STATE_DIR, "run.lock")
        val pid = File(lockDir, "pid").takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.toIntOrNull()
            ?: return false
        if (pid <= 1) return false
        val proc = File("/proc/$pid")
        if (!proc.exists()) return false
        val cmdline = runCatching {
            File(proc, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
        }.getOrDefault("")
        return cmdline.contains("baize_v2") && (
            cmdline.contains("cleaner.sh") ||
                cmdline.contains("cleaner.native.sh") ||
                cmdline.contains("cache-snapshot-clean.sh") ||
                cmdline.contains("baize_engine")
            )
    }

    private fun repairStaleTaskFiles() {
        val lockDir = File(STATE_DIR, "run.lock")
        if (lockDir.exists() && !moduleTaskAlive()) runCatching { lockDir.deleteRecursively() }
        runCatching { File(STATE_DIR, "running.env").delete() }
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

    private fun parseCleanReport(file: File): CleanReport {
        if (!file.isFile) return CleanReport(0, 0L, 0L, 0)
        var candidates = 0
        var files = 0L
        var bytes = 0L
        var failures = 0
        runCatching {
            file.forEachLine { raw ->
                val columns = raw.split('\t', limit = 6)
                if (columns.size < 6 || columns[0] == "action") return@forEachLine
                val items = columns[3].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                val itemBytes = columns[4].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                when (columns[0]) {
                    "cleaned" -> {
                        if (items > 0L) candidates += 1
                        files += items
                        bytes += itemBytes
                    }
                    "failed" -> failures += items.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
            }
        }
        return CleanReport(candidates, files, bytes, failures)
    }

    private fun snapshotValid(id: String): Boolean =
        id.isNotBlank() && id == snapshotId &&
            System.currentTimeMillis() - snapshotCreatedAt in 0..SNAPSHOT_MAX_AGE_MS

    private fun readEnv(file: File): JSONObject {
        val result = JSONObject()
        if (!file.isFile) return result
        runCatching {
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isBlank() || line.startsWith("#") || !line.contains('=')) return@forEachLine
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=').trim()
                result.put(key, value.toLongOrNull() ?: value)
            }
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
        .put("message", "已有扫描或清理任务正在运行")
        .toString()

    private fun idleState(): String = JSONObject()
        .put("running", false)
        .put("operation", "idle")
        .put("phase", "等待任务")
        .toString()

    companion object {
        private const val MODULE_DIR = "/data/adb/modules/baize_v2"
        private const val STATE_DIR = "/data/adb/baize-v2"
        private const val SNAPSHOT_MAX_AGE_MS = 30L * 60L * 1_000L
        private val PACKAGE_NAME = Regex("""^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_-]+)+$""")
    }
}
