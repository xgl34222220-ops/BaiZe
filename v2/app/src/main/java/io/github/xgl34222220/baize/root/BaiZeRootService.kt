package io.github.xgl34222220.baize.root

import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * v2.1.0 Alpha 6 performance-panel adaptive-worker path-indexed One-pass cache task bridge.
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
        val path: String,
        val complete: Boolean = true
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

    /**
     * 扫描快照的全部元数据。
     *
     * 此前这是 32 个独立的 `@Volatile var`：restoreSnapshotFromDisk() 逐个赋值，
     * ping() 逐个读取，两者之间没有任何同步。读取方完全可能拿到半新半旧的组合
     * —— 例如 snapshotId 已指向新一轮扫描，而 files 还是上一轮的值，
     * 界面就会显示对不上的统计。改为不可变对象 + AtomicReference 原子替换。
     */
    private data class SnapshotState(
        val id: String = "",
        val createdAt: Long = 0L,
        val files: Long = 0L,
        val bytes: Long = 0L,
        val whitelisted: Int = 0,
        val visitedFiles: Long = 0L,
        val visitedDirs: Long = 0L,
        val firstResultMs: Long = 0L,
        val engineElapsedMs: Long = 0L,
        val itemsPerSecond: Long = 0L,
        val onePassAppDirs: Long = 0L,
        val onePassInstalledDirs: Long = 0L,
        val onePassOrphanDirs: Long = 0L,
        val whitelistIndexEntries: Long = 0L,
        val whitelistIndexQueries: Long = 0L,
        val whitelistAncestorHits: Long = 0L,
        val whitelistDescendantHits: Long = 0L,
        val prunedSubtrees: Long = 0L,
        val rootWorkers: Long = 1L,
        val parallelWallMs: Long = 0L,
        val internalWorkerMs: Long = 0L,
        val externalWorkerMs: Long = 0L,
        val parallelOverlapMilli: Long = 1000L,
        val workerPolicy: String = "auto",
        val workerReason: String = "none",
        val recommendedWorkers: Long = 1L,
        val parallelGainPercent: Long = 0L,
        val workerProfileRuns: Long = 0L,
        val serialProfileRate: Long = 0L,
        val parallelProfileRate: Long = 0L,
        val nextProbeRun: Long = 0L,
        val parallelBlockedUntil: Long = 0L
    ) {
        /** 写入 ping() 响应。ready=false 时统一输出默认值。 */
        fun putInto(json: JSONObject, ready: Boolean, itemCount: Int): JSONObject {
            val s = if (ready) this else EMPTY
            json.put("snapshotReady", ready)
            json.put("snapshotItems", if (ready) itemCount else 0)
            json.put("snapshotId", s.id)
            json.put("snapshotFiles", s.files)
            json.put("snapshotBytes", s.bytes)
            json.put("snapshotWhitelisted", s.whitelisted)
            json.put("visitedFiles", s.visitedFiles)
            json.put("visitedDirs", s.visitedDirs)
            json.put("firstResultMs", s.firstResultMs)
            json.put("engineElapsedMs", s.engineElapsedMs)
            json.put("itemsPerSecond", s.itemsPerSecond)
            json.put("onePassAppDirs", s.onePassAppDirs)
            json.put("onePassInstalledDirs", s.onePassInstalledDirs)
            json.put("onePassOrphanDirs", s.onePassOrphanDirs)
            json.put("whitelistIndexEntries", s.whitelistIndexEntries)
            json.put("whitelistIndexQueries", s.whitelistIndexQueries)
            json.put("whitelistAncestorHits", s.whitelistAncestorHits)
            json.put("whitelistDescendantHits", s.whitelistDescendantHits)
            json.put("prunedSubtrees", s.prunedSubtrees)
            json.put("rootWorkers", s.rootWorkers)
            json.put("parallelWallMs", s.parallelWallMs)
            json.put("internalWorkerMs", s.internalWorkerMs)
            json.put("externalWorkerMs", s.externalWorkerMs)
            json.put("parallelOverlapMilli", s.parallelOverlapMilli)
            json.put("workerPolicy", s.workerPolicy)
            json.put("workerReason", s.workerReason)
            json.put("recommendedWorkers", s.recommendedWorkers)
            json.put("parallelGainPercent", s.parallelGainPercent)
            json.put("workerProfileRuns", s.workerProfileRuns)
            json.put("serialProfileRate", s.serialProfileRate)
            json.put("parallelProfileRate", s.parallelProfileRate)
            json.put("nextProbeRun", s.nextProbeRun)
            json.put("parallelBlockedUntil", s.parallelBlockedUntil)
            json.put("snapshotCreatedAt", s.createdAt)
            return json
        }

        /** 写入一次扫描完成后的汇总响应。 */
        fun putScanSummary(json: JSONObject): JSONObject {
            json.put("whitelisted", whitelisted)
            json.put("totalFiles", files)
            json.put("totalBytes", bytes)
            json.put("visitedFiles", visitedFiles)
            json.put("visitedDirs", visitedDirs)
            json.put("firstResultMs", firstResultMs)
            json.put("engineElapsedMs", engineElapsedMs)
            json.put("itemsPerSecond", itemsPerSecond)
            json.put("onePassAppDirs", onePassAppDirs)
            json.put("onePassInstalledDirs", onePassInstalledDirs)
            json.put("onePassOrphanDirs", onePassOrphanDirs)
            json.put("whitelistIndexEntries", whitelistIndexEntries)
            json.put("whitelistIndexQueries", whitelistIndexQueries)
            json.put("whitelistAncestorHits", whitelistAncestorHits)
            json.put("whitelistDescendantHits", whitelistDescendantHits)
            json.put("prunedSubtrees", prunedSubtrees)
            json.put("rootWorkers", rootWorkers)
            json.put("parallelWallMs", parallelWallMs)
            json.put("internalWorkerMs", internalWorkerMs)
            json.put("externalWorkerMs", externalWorkerMs)
            json.put("parallelOverlapMilli", parallelOverlapMilli)
            json.put("workerPolicy", workerPolicy)
            json.put("workerReason", workerReason)
            json.put("recommendedWorkers", recommendedWorkers)
            json.put("parallelGainPercent", parallelGainPercent)
            json.put("workerProfileRuns", workerProfileRuns)
            json.put("serialProfileRate", serialProfileRate)
            json.put("parallelProfileRate", parallelProfileRate)
            json.put("nextProbeRun", nextProbeRun)
            json.put("parallelBlockedUntil", parallelBlockedUntil)
            return json
        }

        companion object {
            val EMPTY = SnapshotState()
        }
    }

    private val snapshotState = AtomicReference(SnapshotState.EMPTY)
    private val foregroundEngine by lazy { ForegroundCacheEngine(this, cancelled) }
    @Volatile private var foregroundSnapshot: ForegroundCacheEngine.Snapshot? = null
    @Volatile private var items: List<CacheItem> = emptyList()
    @Volatile private var taskStateJson = idleState()

    private val binder = object : IBaiZeRootService.Stub() {

        override fun exchangeJson(operation: String?, request: ParcelFileDescriptor?): ParcelFileDescriptor =
            JsonFileTransport.serve(File(RootPaths.STATE_DIR, "ipc"), request) { dispatchJson(operation, it) }

        override fun exchangeJsonInto(operation: String?, request: ParcelFileDescriptor?, response: ParcelFileDescriptor?): Int =
            JsonFileTransport.serveInto(request, response) { dispatchJson(operation, it) }

        private fun dispatchJson(operation: String?, arguments: JSONArray): String {
            return when (operation) {
                "scanCandidates" -> {
                    require(arguments.length() == 1)
                    scanCandidates(arguments.getString(0))
                }
                "getResultPage" -> {
                    require(arguments.length() == 3)
                    getResultPage(arguments.getString(0), arguments.getInt(1), arguments.getInt(2))
                }
                "cleanSelected" -> {
                    require(arguments.length() == 3)
                    cleanSelected(arguments.getString(0), arguments.getString(1), arguments.getString(2))
                }
                else -> throw IllegalArgumentException("不支持的服务请求")
            }
        }

        override fun ping(): String {
            val ready = restoreForegroundSnapshot()
            return JSONObject()
                .put("uid", Process.myUid())
                .put("root", Process.myUid() == 0)
                .put("engine", "app-root-foreground-cache-v1")
                .put("available", Process.myUid() == 0)
                .put("moduleRequired", false)
                .also { json ->
                    snapshotState.get().putInto(
                        json,
                        ready,
                        synchronized(resultLock) { items.size }
                    )
                }
                .put("snapshotExpiresInMs", SNAPSHOT_MAX_AGE_MS)
                .put("taskRunning", running.get())
                .toString()
        }

        override fun scanCandidates(whitelistJson: String?): String {
            if (!running.compareAndSet(false, true)) return busy("cache-scan")
            cancelled.set(false)
            val started = SystemClock.elapsedRealtime()
            return try {
                runForegroundScan(whitelistJson.orEmpty(), started)
            } catch (error: Throwable) {
                JSONObject()
                    .put("error", "foreground_cache_scan_failed")
                    .put("message", error.message ?: error.javaClass.simpleName)
                    .toString()
            } finally {
                running.set(false)
                taskStateJson = idleState()
            }
        }

        override fun getResultPage(requestedSnapshotId: String?, offset: Int, limit: Int): String {
            restoreForegroundSnapshot()
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
                            .put("complete", item.complete)
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
            if (!restoreForegroundSnapshot() || !snapshotValid(requestedSnapshotId.orEmpty())) {
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
                runForegroundClean(whitelistJson.orEmpty(), started)
            } catch (error: Throwable) {
                JSONObject()
                    .put("error", "foreground_cache_clean_failed")
                    .put("message", error.message ?: error.javaClass.simpleName)
                    .toString()
            } finally {
                running.set(false)
                taskStateJson = idleState()
            }
        }

        override fun getTaskState(): String = runCatching {
            JSONObject(taskStateJson)
                .put("running", running.get())
                .put("cancelRequested", cancelled.get())
                .toString()
        }.getOrDefault(taskStateJson)

        override fun cancelCurrentTask() {
            cancelled.set(true)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun runForegroundScan(whitelistJson: String, started: Long): String {
        taskStateJson = JSONObject()
            .put("running", true)
            .put("operation", "foreground-cache-scan")
            .put("mode", "cache-scan")
            .put("phase", "正在扫描应用缓存")
            .put("elapsedMs", 0L)
            .toString()
        val snapshot = foregroundEngine.scan(whitelistJson) { phase, current, total, path ->
            taskStateJson = JSONObject()
                .put("running", true)
                .put("operation", "foreground-cache-scan")
                .put("mode", "cache-scan")
                .put("phase", phase)
                .put("current", current)
                .put("total", total)
                .put("currentPath", path.takeLast(512))
                .put("elapsedMs", (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L))
                .toString()
        }
        foregroundSnapshot = snapshot
        synchronized(resultLock) { items = snapshot.items.map(::cacheItem) }
        setSnapshotState(snapshot)
        persistForegroundSnapshot(snapshot)
        return JSONObject()
            .put("cancelled", cancelled.get())
            .put("elapsedMs", snapshot.elapsedMs)
            .put("snapshotId", snapshot.id)
            .put("snapshotExpiresInMs", SNAPSHOT_MAX_AGE_MS)
            .put("totalCandidates", snapshot.items.size)
            .put("totalFiles", snapshot.totalFiles)
            .put("totalBytes", snapshot.totalBytes)
            .put("visitedDirs", snapshot.visitedDirs)
            .put("whitelisted", 0)
            .put("engine", "app-root-foreground-cache-v1")
            .toString()
    }

    private fun runForegroundClean(whitelistJson: String, started: Long): String {
        val snapshot = foregroundSnapshot ?: return JSONObject()
            .put("error", "snapshot_expired")
            .put("message", "缓存扫描快照已失效，请重新扫描")
            .toString()
        taskStateJson = JSONObject()
            .put("running", true)
            .put("operation", "foreground-cache-clean")
            .put("mode", "cache-clean")
            .put("phase", "正在清理应用缓存")
            .put("elapsedMs", 0L)
            .toString()
        val result = foregroundEngine.clean(snapshot, whitelistJson) { phase, current, total, path ->
            taskStateJson = JSONObject()
                .put("running", true)
                .put("operation", "foreground-cache-clean")
                .put("mode", "cache-clean")
                .put("phase", phase)
                .put("current", current)
                .put("total", total)
                .put("currentPath", path.takeLast(512))
                .put("elapsedMs", (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L))
                .toString()
        }
        writeForegroundCleanReport(result)
        if (result.remainingItems.isEmpty() && !result.cancelled) {
            clearForegroundSnapshot()
        } else {
            val remaining = ForegroundCacheEngine.Snapshot(
                id = snapshot.id,
                createdAt = snapshot.createdAt,
                items = result.remainingItems,
                totalBytes = result.remainingItems.sumOf { it.bytes },
                totalFiles = result.remainingItems.sumOf { it.files },
                visitedDirs = result.remainingItems.sumOf { it.directories },
                elapsedMs = snapshot.elapsedMs
            )
            foregroundSnapshot = remaining
            synchronized(resultLock) { items = remaining.items.map(::cacheItem) }
            setSnapshotState(remaining)
            persistForegroundSnapshot(remaining)
        }
        return result.json().put("output", "App RootService 直接清理；未调用模块脚本").toString()
    }

    private fun cacheItem(item: ForegroundCacheEngine.Item): CacheItem = CacheItem(
        packageName = item.packageName,
        category = item.category,
        files = item.files,
        bytes = item.bytes,
        directories = item.directories,
        path = item.path,
        complete = item.complete
    )

    private fun setSnapshotState(snapshot: ForegroundCacheEngine.Snapshot) {
        snapshotState.set(
            SnapshotState(
                id = snapshot.id,
                createdAt = snapshot.createdAt,
                files = snapshot.totalFiles,
                bytes = snapshot.totalBytes,
                visitedDirs = snapshot.visitedDirs,
                firstResultMs = snapshot.elapsedMs,
                engineElapsedMs = snapshot.elapsedMs,
                itemsPerSecond = if (snapshot.elapsedMs > 0L) snapshot.items.size * 1000L / snapshot.elapsedMs else snapshot.items.size.toLong(),
                workerPolicy = "app-root",
                workerReason = "foreground-module-independent"
            )
        )
    }

    private fun foregroundStateDir(): File = File(RootPaths.FOREGROUND_STATE_DIR).apply { mkdirs() }

    private fun persistForegroundSnapshot(snapshot: ForegroundCacheEngine.Snapshot) {
        val stateDir = foregroundStateDir()
        val itemsFile = File(stateDir, "cache_scan.items.tsv")
        val targetsFile = File(stateDir, "cache_scan.targets")
        val envFile = File(stateDir, "cache_scan.env")
        val itemsText = buildString {
            append("package\tcategory\tfiles\tbytes\tdirectories\tpath\n")
            snapshot.items.forEach { item ->
                append(item.packageName).append('\t')
                    .append(item.category.replace('\t', ' ')).append('\t')
                    .append(item.files).append('\t')
                    .append(item.bytes).append('\t')
                    .append(item.directories).append('\t')
                    .append(item.path.replace('\t', ' ').replace('\n', ' ')).append('\n')
            }
        }
        val targetsText = snapshot.items.joinToString("\n", postfix = if (snapshot.items.isEmpty()) "" else "\n") { it.path }
        atomicWrite(itemsFile, itemsText)
        atomicWrite(targetsFile, targetsText)
        atomicWrite(envFile, buildString {
            appendLine("snapshot_id=${snapshot.id}")
            appendLine("epoch=${snapshot.createdAt / 1000L}")
            appendLine("items=${snapshot.items.size}")
            appendLine("targets=${snapshot.items.size}")
            appendLine("files=${snapshot.totalFiles}")
            appendLine("bytes=${snapshot.totalBytes}")
            appendLine("visited_dirs=${snapshot.visitedDirs}")
            appendLine("first_result_ms=${snapshot.elapsedMs}")
            appendLine("engine_elapsed_ms=${snapshot.elapsedMs}")
            appendLine("worker_policy=app-root")
            appendLine("worker_reason=foreground-module-independent")
            appendLine("engine=app-root-foreground-cache-v1")
            appendLine("incomplete_paths=${JSONArray(snapshot.items.filterNot { it.complete }.map { it.path })}")
        })
    }

    private fun restoreForegroundSnapshot(): Boolean {
        val current = foregroundSnapshot
        if (current != null && snapshotValid(current.id)) return true
        val stateDir = foregroundStateDir()
        val envFile = File(stateDir, "cache_scan.env")
        val itemsFile = File(stateDir, "cache_scan.items.tsv")
        val targetsFile = File(stateDir, "cache_scan.targets")
        if (!envFile.isFile || !itemsFile.isFile || !targetsFile.isFile) {
            clearSnapshotMemory()
            foregroundSnapshot = null
            return false
        }
        val env = readEnv(envFile)
        val id = env.optString("snapshot_id").trim()
        val createdAt = env.optLong("epoch", 0L) * 1000L
        if (id.isBlank() || createdAt <= 0L || System.currentTimeMillis() - createdAt !in 0..SNAPSHOT_MAX_AGE_MS) {
            clearForegroundSnapshot()
            return false
        }
        val incompleteArray = runCatching { JSONArray(env.optString("incomplete_paths", "[]")) }.getOrNull()
        val incompletePaths = incompleteArray?.let { array -> (0 until array.length()).map { array.optString(it) }.toSet() }.orEmpty()
        val restoredItems = parseItems(itemsFile).map { item ->
            ForegroundCacheEngine.Item(
                packageName = item.packageName,
                appName = item.packageName,
                category = item.category,
                path = item.path,
                bytes = item.bytes,
                files = item.files,
                directories = item.directories,
                complete = incompleteArray != null && item.path !in incompletePaths
            )
        }
        val snapshot = ForegroundCacheEngine.Snapshot(
            id = id,
            createdAt = createdAt,
            items = restoredItems,
            totalBytes = restoredItems.sumOf { it.bytes },
            totalFiles = restoredItems.sumOf { it.files },
            visitedDirs = env.optLong("visited_dirs", restoredItems.sumOf { it.directories }),
            elapsedMs = env.optLong("engine_elapsed_ms", 0L)
        )
        foregroundSnapshot = snapshot
        synchronized(resultLock) { items = snapshot.items.map(::cacheItem) }
        setSnapshotState(snapshot)
        return true
    }

    private fun writeForegroundCleanReport(result: ForegroundCacheEngine.CleanResult) {
        val report = File(foregroundStateDir(), "reports/latest.tsv")
        report.parentFile?.mkdirs()
        val text = buildString {
            append("action\trisk\tcategory\titems\tbytes\tpath\n")
            val details = result.details
            for (index in 0 until details.length()) {
                val detail = details.optJSONObject(index) ?: continue
                append(detail.optString("action", "changed")).append("\tlow\tcache\t")
                    .append(detail.optLong("files", 0L)).append('\t')
                    .append(detail.optLong("bytes", 0L)).append('\t')
                    .append(detail.optString("path").replace('\t', ' ').replace('\n', ' ')).append('\n')
            }
        }
        atomicWrite(report, text)
    }

    private fun clearForegroundSnapshot() {
        foregroundSnapshot = null
        clearSnapshotMemory()
        for (name in listOf("cache_scan.env", "cache_scan.targets", "cache_scan.items.tsv")) {
            File(RootPaths.FOREGROUND_STATE_DIR, name).delete()
        }
    }

    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        temp.writeText(content)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        target.setReadable(true, true)
        target.setWritable(true, true)
    }

    private fun runNativeScan(whitelistJson: String, started: Long): String {
        val cleaner = RootPaths.script("cleaner.sh")
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
            .put("snapshotId", snapshotState.get().id)
            .put("snapshotExpiresInMs", SNAPSHOT_MAX_AGE_MS)
            .put("totalCandidates", synchronized(resultLock) { items.size })
            .also { json -> snapshotState.get().putScanSummary(json) }
            .put("engine", "native-c-adaptive-worker-path-index")
            .toString()
    }

    private fun runSnapshotClean(whitelistJson: String, started: Long): String {
        val cleaner = RootPaths.script("cleaner.sh")
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

        val deletedBytes = latest.optLong("bytes", report.deletedBytes).coerceAtLeast(0L)
        val deletedFiles = latest.optLong("files", report.deletedFiles).coerceAtLeast(0L)
        val cleanedCandidates = latest.optInt("cleaned_candidates", report.cleanedCandidates).coerceAtLeast(0)
        val changedCandidates = latest.optInt("changed_candidates", 0).coerceAtLeast(0)
        val protectedCandidates = latest.optInt("protected_candidates", 0).coerceAtLeast(0)
        val partialCandidates = latest.optInt("partial_candidates", 0).coerceAtLeast(0)
        val failedCandidates = latest.optInt("failed_candidates", report.failures).coerceAtLeast(0)
        val skippedCandidates = latest.optInt(
            "skipped_candidates",
            changedCandidates + protectedCandidates
        ).coerceAtLeast(0)
        val processedCandidates = latest.optInt(
            "processed_candidates",
            cleanedCandidates + changedCandidates + protectedCandidates + partialCandidates + failedCandidates
        ).coerceAtLeast(0)
        val mutated = deletedFiles > 0L || cleanedCandidates > 0
        val honestMessage = when {
            code != 0 || wasCancelled -> message
            mutated -> message
            skippedCandidates > 0 || partialCandidates > 0 ->
                "本次未删除文件：$skippedCandidates 项已变化或受保护，$partialCandidates 项仅部分可处理"
            beforeCount > 0 -> "本次未删除任何缓存文件，请重新扫描查看当前状态"
            else -> message
        }

        val result = JSONObject()
            .put("success", code == 0)
            .put("mutated", mutated)
            .put("cancelled", wasCancelled)
            .put("elapsedMs", elapsed)
            .put("authorizedCandidates", beforeCount)
            .put("processedCandidates", processedCandidates)
            .put("cleanedCandidates", cleanedCandidates)
            .put("changedCandidates", changedCandidates)
            .put("protectedCandidates", protectedCandidates)
            .put("partialCandidates", partialCandidates)
            .put("failedCandidates", failedCandidates)
            .put("skippedCandidates", skippedCandidates)
            .put("deletedBytes", deletedBytes)
            .put("deletedFiles", deletedFiles)
            .put("deletedDirectories", 0L)
            .put("failures", latest.optInt("errors", report.failures).coerceAtLeast(0))
            .put("message", honestMessage)
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
        if (!force && id == snapshotState.get().id && snapshotValid(id)) return true

        val parsed = parseItems(itemFile)
        val expected = state.optInt("items", parsed.size).coerceAtLeast(0)
        if (expected > 0 && parsed.isEmpty()) {
            clearSnapshotMemory()
            return false
        }
        synchronized(resultLock) { items = parsed }
        val latest = readEnv(File(STATE_DIR, "latest.env"))
        fun num(key: String, fallback: Long = 0L): Long =
            state.optLong(key, latest.optLong(key, fallback))
        // 先完整构造，再一次性原子替换
        snapshotState.set(
            SnapshotState(
                id = id,
                createdAt = createdAt,
                files = state.optLong("files", 0L).coerceAtLeast(0L),
                bytes = state.optLong("bytes", 0L).coerceAtLeast(0L),
                whitelisted = latest.optInt("whitelisted", 0).coerceAtLeast(0),
                visitedFiles = num("visited_files").coerceAtLeast(0L),
                visitedDirs = num("visited_dirs").coerceAtLeast(0L),
                firstResultMs = num("first_result_ms").coerceAtLeast(0L),
                engineElapsedMs = num("engine_elapsed_ms").coerceAtLeast(0L),
                itemsPerSecond = num("items_per_second").coerceAtLeast(0L),
                onePassAppDirs = num("one_pass_app_dirs").coerceAtLeast(0L),
                onePassInstalledDirs = num("one_pass_installed_dirs").coerceAtLeast(0L),
                onePassOrphanDirs = num("one_pass_orphan_dirs").coerceAtLeast(0L),
                whitelistIndexEntries = num("whitelist_index_entries").coerceAtLeast(0L),
                whitelistIndexQueries = num("whitelist_index_queries").coerceAtLeast(0L),
                whitelistAncestorHits = num("whitelist_ancestor_hits").coerceAtLeast(0L),
                whitelistDescendantHits = num("whitelist_descendant_hits").coerceAtLeast(0L),
                prunedSubtrees = num("pruned_subtrees").coerceAtLeast(0L),
                rootWorkers = num("root_workers", 1L).coerceIn(1L, 2L),
                parallelWallMs = num("parallel_wall_ms").coerceAtLeast(0L),
                internalWorkerMs = num("internal_worker_ms").coerceAtLeast(0L),
                externalWorkerMs = num("external_worker_ms").coerceAtLeast(0L),
                parallelOverlapMilli = num("parallel_overlap_milli", 1000L).coerceAtLeast(0L),
                workerPolicy = state.optString("worker_policy", latest.optString("worker_policy", "auto")),
                workerReason = state.optString("worker_reason", latest.optString("worker_reason", "none")),
                recommendedWorkers = num("recommended_workers", 1L).coerceIn(1L, 2L),
                parallelGainPercent = num("parallel_gain_percent"),
                workerProfileRuns = num("worker_profile_runs").coerceAtLeast(0L),
                serialProfileRate = num("serial_profile_rate").coerceAtLeast(0L),
                parallelProfileRate = num("parallel_profile_rate").coerceAtLeast(0L),
                nextProbeRun = num("next_probe_run").coerceAtLeast(0L),
                parallelBlockedUntil = num("parallel_blocked_until").coerceAtLeast(0L)
            )
        )
        return true
    }

    private fun clearSnapshotMemory() {
        synchronized(resultLock) { items = emptyList() }
        snapshotState.set(SnapshotState.EMPTY)
    }

    private fun moduleTaskAlive(): Boolean = RuntimeTaskOwnership.isRunning(File(STATE_DIR))

    private fun repairStaleTaskFiles() {
        if (moduleTaskAlive()) return
        runCatching { File(STATE_DIR, "run.lock").deleteRecursively() }
        runCatching { File(STATE_DIR, "running.env").delete() }
        runCatching { File(STATE_DIR, "worker.env").delete() }
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
        id.isNotBlank() && snapshotState.get().let { snap ->
            id == snap.id && System.currentTimeMillis() - snap.createdAt in 0..SNAPSHOT_MAX_AGE_MS
        }

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
