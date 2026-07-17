package io.github.xgl34222220.baize.root

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Alpha 3：扫描快照、分页延迟统计和安全删除链。
 * 删除只作用于服务端扫描快照中的 cache/code_cache 候选，并始终保留缓存根目录。
 */
class BaiZeRootService : RootService() {
    private val cancelled = AtomicBoolean(false)
    private val taskRunning = AtomicBoolean(false)
    private val resultLock = Any()
    private val measureLock = Any()

    @Volatile
    private var lastResults: MutableList<ScanCandidate> = mutableListOf()

    @Volatile
    private var lastSnapshotId: String = ""

    @Volatile
    private var lastSnapshotCreatedAt: Long = 0L

    @Volatile
    private var taskState: String = JSONObject()
        .put("running", false)
        .put("operation", "idle")
        .put("phase", "等待任务")
        .toString()

    @Volatile
    private var lastReport: String = "{}"

    private val binder = object : IBaiZeRootService.Stub() {
        override fun ping(): String = JSONObject()
            .put("uid", Process.myUid())
            .put("root", Process.myUid() == 0)
            .put("moduleV1", File("/data/adb/modules/safesweep/module.prop").isFile)
            .put("moduleV2", File("/data/adb/modules/baize_v2_alpha/module.prop").isFile)
            .put("engine", "kotlin-nio-snapshot-safe-delete-v3")
            .put("snapshotReady", snapshotIsValid(lastSnapshotId))
            .toString()

        override fun scanCandidates(whitelistJson: String?): String {
            if (!taskRunning.compareAndSet(false, true)) return busyResult("scan")
            cancelled.set(false)
            val started = SystemClock.elapsedRealtime()
            updateTaskState(true, "scan", "正在发现真实缓存目录", 0, 0, "", "", 0, 0, 0, started)
            return try {
                discoverCandidates(parseWhitelist(whitelistJson.orEmpty()), started)
            } finally {
                taskRunning.set(false)
            }
        }

        override fun getResultPage(snapshotId: String?, offset: Int, limit: Int): String {
            if (taskRunning.get()) return busyPage()
            cancelled.set(false)
            return resultPage(snapshotId.orEmpty(), offset, limit)
        }

        override fun cleanSelected(snapshotId: String?, selectionJson: String?, whitelistJson: String?): String {
            if (!taskRunning.compareAndSet(false, true)) return busyResult("clean")
            cancelled.set(false)
            val started = SystemClock.elapsedRealtime()
            updateTaskState(true, "clean", "正在校验扫描快照", 0, 0, "", "", 0, 0, 0, started)
            return try {
                cleanSnapshot(
                    snapshotId = snapshotId.orEmpty(),
                    selection = parseSelection(selectionJson.orEmpty()),
                    whitelist = parseWhitelist(whitelistJson.orEmpty()),
                    started = started
                )
            } finally {
                taskRunning.set(false)
            }
        }

        override fun getTaskState(): String = runCatching {
            JSONObject(taskState)
                .put("cancelRequested", cancelled.get())
                .toString()
        }.getOrDefault(taskState)

        override fun cancelCurrentTask() {
            cancelled.set(true)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private data class CandidateSeed(
        val packageName: String,
        val category: String,
        val categoryLabel: String,
        val path: Path,
        val userId: Int
    )

    private data class DirectoryStats(
        val bytes: Long,
        val files: Long,
        val directories: Long,
        val readable: Boolean,
        val complete: Boolean
    )

    private data class DeleteStats(
        val bytes: Long,
        val files: Long,
        val directories: Long,
        val failures: Int,
        val protectedMounts: Int,
        val complete: Boolean
    )

    private data class Validation(
        val path: Path?,
        val reason: String?
    )

    private data class ScanCandidate(
        val appName: String,
        val packageName: String,
        val category: String,
        val categoryLabel: String,
        val path: String,
        val userId: Int,
        val whitelisted: Boolean,
        val bytes: Long = -1,
        val files: Long = -1,
        val directories: Long = -1,
        val readable: Boolean = true,
        val measured: Boolean = false,
        val complete: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("appName", appName)
            .put("packageName", packageName)
            .put("category", category)
            .put("categoryLabel", categoryLabel)
            .put("path", path)
            .put("userId", userId)
            .put("bytes", bytes)
            .put("files", files)
            .put("directories", directories)
            .put("whitelisted", whitelisted)
            .put("readable", readable)
            .put("measured", measured)
            .put("complete", complete)
    }

    private fun parseWhitelist(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            buildSet {
                for (index in 0 until array.length()) {
                    val value = array.optString(index).trim()
                    if (isSafePackageName(value)) add(value)
                }
            }
        }.getOrDefault(emptySet())
    }

    private fun parseSelection(raw: String): Map<String, Boolean> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val path = keys.next()
                    if (path.startsWith("/") && path.length <= 4096) {
                        put(path, json.optBoolean(path, false))
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun isSafePackageName(value: String): Boolean {
        if (value.isBlank() || value.length > 255 || !value.contains('.')) return false
        return value.all { it.isLetterOrDigit() || it == '.' || it == '_' }
    }

    private fun discoverCandidates(whitelist: Set<String>, started: Long): String {
        val seeds = enumerateExistingCandidates()
        if (cancelled.get()) {
            synchronized(resultLock) { lastResults = mutableListOf() }
            lastSnapshotId = ""
            val result = cancelledSummary(started, seeds.size)
            updateTaskState(false, "scan", "扫描已停止", 0, 0, "", "", 0, 0, 0, started)
            return result
        }

        updateTaskState(true, "scan", "正在读取应用名称", 0, seeds.size, "", "", 0, 0, 0, started)
        val labels = resolveLabels(seeds.map { it.packageName }.toSet())
        val results = seeds.map { seed ->
            ScanCandidate(
                appName = labels[seed.packageName] ?: seed.packageName,
                packageName = seed.packageName,
                category = seed.category,
                categoryLabel = seed.categoryLabel,
                path = seed.path.normalize().toString(),
                userId = seed.userId,
                whitelisted = seed.packageName in whitelist
            )
        }.sortedWith(
            compareBy<ScanCandidate> { it.appName.lowercase(Locale.ROOT) }
                .thenBy { it.packageName }
                .thenBy { it.category }
                .thenBy { it.path }
        ).toMutableList()

        val snapshotId = UUID.randomUUID().toString()
        synchronized(resultLock) { lastResults = results }
        lastSnapshotId = snapshotId
        lastSnapshotCreatedAt = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime() - started
        updateTaskState(false, "scan", "扫描完成", results.size, results.size, "", "", 0, 0, 0, started)
        return JSONObject()
            .put("cancelled", false)
            .put("elapsedMs", elapsed)
            .put("snapshotId", snapshotId)
            .put("snapshotExpiresInMs", SNAPSHOT_MAX_AGE_MS)
            .put("totalCandidates", results.size)
            .put("whitelisted", results.count { it.whitelisted })
            .put("internalCache", results.count { it.category == "internal_cache" })
            .put("internalCodeCache", results.count { it.category == "code_cache" })
            .put("deviceProtectedCache", results.count { it.category.startsWith("device_") })
            .put("externalCache", results.count { it.category == "external_cache" })
            .put("measurement", "lazy-page")
            .toString()
    }

    private fun cancelledSummary(started: Long, discovered: Int): String = JSONObject()
        .put("cancelled", true)
        .put("elapsedMs", SystemClock.elapsedRealtime() - started)
        .put("totalCandidates", 0)
        .put("discoveredBeforeCancel", discovered)
        .toString()

    private fun enumerateExistingCandidates(): List<CandidateSeed> {
        val output = LinkedHashMap<String, CandidateSeed>()

        fun addCandidate(packageName: String, category: String, label: String, path: Path, userId: Int) {
            if (cancelled.get() || !isSafePackageName(packageName)) return
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return
            if (!directoryHasEntries(path)) return
            val normalized = path.normalize().toString()
            output.putIfAbsent(normalized, CandidateSeed(packageName, category, label, path, userId))
        }

        fun scanAppRoot(root: Path, deviceProtected: Boolean) {
            for (userPath in safeListDirectories(root)) {
                if (cancelled.get()) return
                val userId = userPath.fileName.toString().toIntOrNull() ?: continue
                for (packagePath in safeListDirectories(userPath)) {
                    if (cancelled.get()) return
                    val packageName = packagePath.fileName.toString()
                    if (deviceProtected) {
                        addCandidate(packageName, "device_cache", "设备保护缓存", packagePath.resolve("cache"), userId)
                        addCandidate(packageName, "device_code_cache", "设备保护代码缓存", packagePath.resolve("code_cache"), userId)
                    } else {
                        addCandidate(packageName, "internal_cache", "内部缓存", packagePath.resolve("cache"), userId)
                        addCandidate(packageName, "code_cache", "代码缓存", packagePath.resolve("code_cache"), userId)
                    }
                }
            }
        }

        scanAppRoot(Paths.get("/data/user"), false)
        if (!cancelled.get()) scanAppRoot(Paths.get("/data/user_de"), true)

        for (userPath in safeListDirectories(Paths.get("/data/media"))) {
            if (cancelled.get()) break
            val userId = userPath.fileName.toString().toIntOrNull() ?: continue
            for (packagePath in safeListDirectories(userPath.resolve("Android/data"))) {
                if (cancelled.get()) break
                val packageName = packagePath.fileName.toString()
                addCandidate(packageName, "external_cache", "外部缓存", packagePath.resolve("cache"), userId)
            }
        }
        return output.values.toList()
    }

    private fun directoryHasEntries(path: Path): Boolean {
        return try {
            Files.newDirectoryStream(path).use { stream -> stream.iterator().hasNext() }
        } catch (_: IOException) {
            true
        } catch (_: SecurityException) {
            true
        }
    }

    private fun safeListDirectories(path: Path): List<Path> {
        if (cancelled.get() || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return try {
            Files.newDirectoryStream(path).use { stream ->
                stream.asSequence()
                    .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                    .toList()
            }
        } catch (_: IOException) {
            emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun resolveLabels(packages: Set<String>): Map<String, String> {
        val labels = HashMap<String, String>(packages.size)
        packages.forEachIndexed { index, packageName ->
            if (cancelled.get()) return@forEachIndexed
            if (index % 64 == 0) {
                updateTaskState(true, "scan", "正在读取应用名称", index, packages.size, packageName, "", 0, 0, 0, SystemClock.elapsedRealtime())
            }
            val label = runCatching {
                @Suppress("DEPRECATION")
                val info: ApplicationInfo = packageManager.getApplicationInfo(
                    packageName,
                    android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
                )
                packageManager.getApplicationLabel(info).toString().trim()
            }.getOrNull()
            labels[packageName] = label?.takeIf { it.isNotBlank() } ?: packageName
        }
        return labels
    }

    private fun resultPage(snapshotId: String, rawOffset: Int, rawLimit: Int): String = synchronized(measureLock) {
        if (!snapshotIsValid(snapshotId)) return stalePage()
        val size = synchronized(resultLock) { lastResults.size }
        val offset = rawOffset.coerceAtLeast(0).coerceAtMost(size)
        val limit = rawLimit.coerceIn(1, 100)
        val end = min(size, offset + limit)
        measureRange(offset, end)

        val snapshot = synchronized(resultLock) { lastResults.subList(offset, end).toList() }
        val items = JSONArray()
        snapshot.forEach { items.put(it.toJson()) }
        JSONObject()
            .put("offset", offset)
            .put("limit", limit)
            .put("total", size)
            .put("snapshotId", lastSnapshotId)
            .put("measured", snapshot.count { it.measured })
            .put("complete", snapshot.count { it.complete })
            .put("knownBytes", snapshot.filter { it.measured }.sumOf { it.bytes.coerceAtLeast(0) })
            .put("items", items)
            .toString()
    }

    private fun measureRange(start: Int, end: Int) {
        if (start >= end || cancelled.get()) return
        val pending = synchronized(resultLock) {
            (start until end).filter { index -> !lastResults[index].measured }
                .map { index -> index to lastResults[index] }
        }
        if (pending.isEmpty()) return

        val pageDeadline = SystemClock.elapsedRealtime() + PAGE_MEASURE_BUDGET_MS
        val executor = Executors.newFixedThreadPool(min(2, pending.size.coerceAtLeast(1)))
        try {
            val tasks = pending.map { (index, item) ->
                Callable {
                    val directoryDeadline = min(pageDeadline, SystemClock.elapsedRealtime() + DIRECTORY_MEASURE_BUDGET_MS)
                    index to scanDirectory(Paths.get(item.path), directoryDeadline)
                }
            }
            val futures = executor.invokeAll(tasks, PAGE_MEASURE_BUDGET_MS, TimeUnit.MILLISECONDS)
            futures.forEach { future ->
                if (future.isCancelled || cancelled.get()) return@forEach
                val (index, stats) = runCatching { future.get() }.getOrNull() ?: return@forEach
                synchronized(resultLock) {
                    if (index < lastResults.size) {
                        val current = lastResults[index]
                        lastResults[index] = current.copy(
                            bytes = stats.bytes,
                            files = stats.files,
                            directories = stats.directories,
                            readable = stats.readable,
                            measured = true,
                            complete = stats.complete
                        )
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun scanDirectory(root: Path, deadline: Long): DirectoryStats {
        var bytes = 0L
        var files = 0L
        var directories = 0L
        var readable = true
        var complete = true

        try {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                private fun shouldStop(): Boolean {
                    if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) {
                        complete = false
                        return true
                    }
                    return false
                }

                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (shouldStop()) return FileVisitResult.TERMINATE
                    if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE
                    directories++
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (shouldStop()) return FileVisitResult.TERMINATE
                    if (attrs.isRegularFile && !attrs.isSymbolicLink) {
                        files++
                        bytes = safeAdd(bytes, attrs.size())
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    readable = false
                    return if (shouldStop()) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                }
            })
        } catch (_: IOException) {
            readable = false
            complete = false
        } catch (_: SecurityException) {
            readable = false
            complete = false
        }
        return DirectoryStats(bytes, files, directories, readable, complete)
    }

    private fun cleanSnapshot(
        snapshotId: String,
        selection: Map<String, Boolean>,
        whitelist: Set<String>,
        started: Long
    ): String {
        if (!snapshotIsValid(snapshotId)) {
            val report = JSONObject()
                .put("success", false)
                .put("error", "stale_snapshot")
                .put("message", "扫描快照已失效，请重新扫描")
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .toString()
            updateTaskState(false, "clean", "快照已失效", 0, 0, "", "", 0, 0, 1, started)
            return report
        }

        val snapshot = synchronized(resultLock) { lastResults.toList() }
        val selected = snapshot.filter { candidate ->
            !candidate.whitelisted &&
                candidate.packageName !in whitelist &&
                (selection[candidate.path] ?: true)
        }
        val mountPoints = loadMountPoints()
        val details = JSONArray()
        val totalDeadline = SystemClock.elapsedRealtime() + TOTAL_CLEAN_BUDGET_MS
        var processed = 0
        var cleanedCandidates = 0
        var skippedCandidates = 0
        var failedCandidates = 0
        var timedOutCandidates = 0
        var deletedBytes = 0L
        var deletedFiles = 0L
        var deletedDirectories = 0L
        var failures = 0
        var protectedMounts = 0

        selected.forEachIndexed { index, candidate ->
            if (cancelled.get() || SystemClock.elapsedRealtime() >= totalDeadline) return@forEachIndexed
            updateTaskState(
                running = true,
                operation = "clean",
                phase = "正在清理 ${candidate.appName}",
                current = index,
                total = selected.size,
                currentApp = candidate.appName,
                currentPath = candidate.path,
                deletedBytes = deletedBytes,
                deletedFiles = deletedFiles,
                failures = failures,
                started = started
            )

            val validation = validateCandidate(candidate, mountPoints)
            if (validation.path == null) {
                skippedCandidates++
                processed++
                if (details.length() < REPORT_DETAIL_LIMIT) {
                    details.put(detailJson(candidate, "skipped", validation.reason ?: "安全校验失败", 0, 0, 0))
                }
                return@forEachIndexed
            }

            if (!directoryHasEntries(validation.path)) {
                skippedCandidates++
                processed++
                if (details.length() < REPORT_DETAIL_LIMIT) {
                    details.put(detailJson(candidate, "empty", "目录已经为空", 0, 0, 0))
                }
                return@forEachIndexed
            }

            val itemDeadline = min(totalDeadline, SystemClock.elapsedRealtime() + ITEM_CLEAN_BUDGET_MS)
            val stats = clearDirectoryContents(validation.path, mountPoints, itemDeadline)
            deletedBytes = safeAdd(deletedBytes, stats.bytes)
            deletedFiles = safeAdd(deletedFiles, stats.files)
            deletedDirectories = safeAdd(deletedDirectories, stats.directories)
            failures += stats.failures
            protectedMounts += stats.protectedMounts
            processed++

            when {
                !stats.complete -> {
                    timedOutCandidates++
                    failedCandidates++
                }
                stats.failures > 0 -> failedCandidates++
                else -> cleanedCandidates++
            }
            if (details.length() < REPORT_DETAIL_LIMIT) {
                val status = when {
                    !stats.complete -> "partial"
                    stats.failures > 0 -> "failed"
                    else -> "cleaned"
                }
                val message = when {
                    !stats.complete -> "达到时间预算或任务被取消"
                    stats.protectedMounts > 0 -> "已跳过独立挂载点"
                    stats.failures > 0 -> "部分项目删除失败"
                    else -> "清理完成"
                }
                details.put(detailJson(candidate, status, message, stats.bytes, stats.files, stats.failures))
            }
        }

        val wasCancelled = cancelled.get()
        val totalTimedOut = !wasCancelled && processed < selected.size && SystemClock.elapsedRealtime() >= totalDeadline
        val report = JSONObject()
            .put("success", true)
            .put("snapshotId", snapshotId)
            .put("cancelled", wasCancelled)
            .put("totalTimedOut", totalTimedOut)
            .put("elapsedMs", SystemClock.elapsedRealtime() - started)
            .put("selected", selected.size)
            .put("processed", processed)
            .put("cleanedCandidates", cleanedCandidates)
            .put("skippedCandidates", skippedCandidates)
            .put("failedCandidates", failedCandidates)
            .put("timedOutCandidates", timedOutCandidates)
            .put("deletedBytes", deletedBytes)
            .put("deletedFiles", deletedFiles)
            .put("deletedDirectories", deletedDirectories)
            .put("failures", failures)
            .put("protectedMounts", protectedMounts)
            .put("details", details)
            .toString()
        lastReport = report
        persistReport(report)
        updateTaskState(
            running = false,
            operation = "clean",
            phase = when {
                wasCancelled -> "清理已停止"
                totalTimedOut -> "清理达到总时间预算"
                else -> "清理完成"
            },
            current = processed,
            total = selected.size,
            currentApp = "",
            currentPath = "",
            deletedBytes = deletedBytes,
            deletedFiles = deletedFiles,
            failures = failures,
            started = started
        )
        return report
    }

    private fun validateCandidate(candidate: ScanCandidate, mountPoints: Set<String>): Validation {
        if (!isSafePackageName(candidate.packageName)) return Validation(null, "包名不合法")
        val expected = expectedCandidatePath(candidate)?.toAbsolutePath()?.normalize()
            ?: return Validation(null, "未知缓存类别")
        val actual = runCatching { Paths.get(candidate.path).toAbsolutePath().normalize() }.getOrNull()
            ?: return Validation(null, "路径无法解析")
        if (actual != expected) return Validation(null, "路径与扫描快照不一致")
        if (!Files.isDirectory(actual, LinkOption.NOFOLLOW_LINKS)) return Validation(null, "目录已不存在")
        if (Files.isSymbolicLink(actual)) return Validation(null, "拒绝清理符号链接")
        if (actual.toString() in mountPoints) return Validation(null, "缓存根目录是独立挂载点")
        return Validation(actual, null)
    }

    private fun expectedCandidatePath(candidate: ScanCandidate): Path? = when (candidate.category) {
        "internal_cache" -> Paths.get("/data/user", candidate.userId.toString(), candidate.packageName, "cache")
        "code_cache" -> Paths.get("/data/user", candidate.userId.toString(), candidate.packageName, "code_cache")
        "device_cache" -> Paths.get("/data/user_de", candidate.userId.toString(), candidate.packageName, "cache")
        "device_code_cache" -> Paths.get("/data/user_de", candidate.userId.toString(), candidate.packageName, "code_cache")
        "external_cache" -> Paths.get("/data/media", candidate.userId.toString(), "Android/data", candidate.packageName, "cache")
        else -> null
    }

    private fun clearDirectoryContents(root: Path, mountPoints: Set<String>, deadline: Long): DeleteStats {
        var bytes = 0L
        var files = 0L
        var directories = 0L
        var failures = 0
        var protectedMounts = 0
        var complete = true
        try {
            Files.newDirectoryStream(root).use { children ->
                for (child in children) {
                    if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) {
                        complete = false
                        break
                    }
                    val stats = deleteEntry(child, mountPoints, deadline)
                    bytes = safeAdd(bytes, stats.bytes)
                    files = safeAdd(files, stats.files)
                    directories = safeAdd(directories, stats.directories)
                    failures += stats.failures
                    protectedMounts += stats.protectedMounts
                    if (!stats.complete) complete = false
                }
            }
        } catch (_: IOException) {
            failures++
            complete = false
        } catch (_: SecurityException) {
            failures++
            complete = false
        }
        return DeleteStats(bytes, files, directories, failures, protectedMounts, complete)
    }

    private fun deleteEntry(entry: Path, mountPoints: Set<String>, deadline: Long): DeleteStats {
        val normalizedEntry = entry.toAbsolutePath().normalize().toString()
        if (normalizedEntry in mountPoints) return DeleteStats(0, 0, 0, 0, 1, false)

        var bytes = 0L
        var files = 0L
        var directories = 0L
        var failures = 0
        var protectedMounts = 0
        var complete = true

        try {
            if (Files.isSymbolicLink(entry) || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                val size = runCatching {
                    Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size()
                }.getOrDefault(0L)
                if (Files.deleteIfExists(entry)) {
                    files++
                    bytes = safeAdd(bytes, size)
                }
                return DeleteStats(bytes, files, directories, failures, protectedMounts, complete)
            }

            Files.walkFileTree(entry, object : SimpleFileVisitor<Path>() {
                private fun shouldStop(): Boolean {
                    if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) {
                        complete = false
                        return true
                    }
                    return false
                }

                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (shouldStop()) return FileVisitResult.TERMINATE
                    if (dir != entry && dir.toAbsolutePath().normalize().toString() in mountPoints) {
                        protectedMounts++
                        complete = false
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (shouldStop()) return FileVisitResult.TERMINATE
                    return try {
                        val size = if (attrs.isRegularFile) attrs.size() else 0L
                        if (Files.deleteIfExists(file)) {
                            files++
                            bytes = safeAdd(bytes, size)
                        }
                        FileVisitResult.CONTINUE
                    } catch (_: IOException) {
                        failures++
                        FileVisitResult.CONTINUE
                    } catch (_: SecurityException) {
                        failures++
                        FileVisitResult.CONTINUE
                    }
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    failures++
                    return if (shouldStop()) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (shouldStop()) return FileVisitResult.TERMINATE
                    if (exc != null) failures++
                    return try {
                        if (Files.deleteIfExists(dir)) directories++
                        FileVisitResult.CONTINUE
                    } catch (_: IOException) {
                        failures++
                        FileVisitResult.CONTINUE
                    } catch (_: SecurityException) {
                        failures++
                        FileVisitResult.CONTINUE
                    }
                }
            })
        } catch (_: IOException) {
            failures++
            complete = false
        } catch (_: SecurityException) {
            failures++
            complete = false
        }
        return DeleteStats(bytes, files, directories, failures, protectedMounts, complete)
    }

    private fun loadMountPoints(): Set<String> {
        return runCatching {
            Files.readAllLines(Paths.get("/proc/self/mountinfo")).mapNotNull { line ->
                val fields = line.split(' ')
                fields.getOrNull(4)?.let(::decodeMountPath)?.let { raw ->
                    runCatching { Paths.get(raw).toAbsolutePath().normalize().toString() }.getOrNull()
                }
            }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun decodeMountPath(raw: String): String = raw
        .replace("\\040", " ")
        .replace("\\011", "\t")
        .replace("\\012", "\n")
        .replace("\\134", "\\")

    private fun detailJson(
        candidate: ScanCandidate,
        status: String,
        message: String,
        bytes: Long,
        files: Long,
        failures: Int
    ): JSONObject = JSONObject()
        .put("appName", candidate.appName)
        .put("packageName", candidate.packageName)
        .put("categoryLabel", candidate.categoryLabel)
        .put("path", candidate.path)
        .put("status", status)
        .put("message", message)
        .put("deletedBytes", bytes)
        .put("deletedFiles", files)
        .put("failures", failures)

    private fun persistReport(report: String) {
        runCatching {
            val directory = Paths.get("/data/adb/baize-v2")
            Files.createDirectories(directory)
            val target = directory.resolve("last-clean-report.json")
            val temporary = directory.resolve("last-clean-report.json.tmp")
            Files.writeString(temporary, report)
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun snapshotIsValid(snapshotId: String): Boolean {
        if (snapshotId.isBlank() || snapshotId != lastSnapshotId) return false
        val age = System.currentTimeMillis() - lastSnapshotCreatedAt
        return lastSnapshotCreatedAt > 0 && age in 0..SNAPSHOT_MAX_AGE_MS
    }

    private fun busyResult(operation: String): String = JSONObject()
        .put("success", false)
        .put("error", "busy")
        .put("operation", operation)
        .put("message", "已有任务正在运行")
        .toString()

    private fun busyPage(): String = JSONObject()
        .put("error", "busy")
        .put("message", "已有任务正在运行")
        .put("items", JSONArray())
        .toString()

    private fun stalePage(): String = JSONObject()
        .put("error", "stale_snapshot")
        .put("message", "扫描快照已失效，请重新扫描")
        .put("items", JSONArray())
        .toString()

    private fun updateTaskState(
        running: Boolean,
        operation: String,
        phase: String,
        current: Int,
        total: Int,
        currentApp: String,
        currentPath: String,
        deletedBytes: Long,
        deletedFiles: Long,
        failures: Int,
        started: Long
    ) {
        taskState = JSONObject()
            .put("running", running)
            .put("operation", operation)
            .put("phase", phase)
            .put("current", current)
            .put("total", total)
            .put("currentApp", currentApp)
            .put("currentPath", currentPath)
            .put("deletedBytes", deletedBytes)
            .put("deletedFiles", deletedFiles)
            .put("failures", failures)
            .put("elapsedMs", (SystemClock.elapsedRealtime() - started).coerceAtLeast(0))
            .toString()
    }

    private fun safeAdd(left: Long, right: Long): Long {
        if (right <= 0) return left
        return if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }

    companion object {
        private const val PAGE_MEASURE_BUDGET_MS = 8_000L
        private const val DIRECTORY_MEASURE_BUDGET_MS = 1_500L
        private const val ITEM_CLEAN_BUDGET_MS = 20_000L
        private const val TOTAL_CLEAN_BUDGET_MS = 5 * 60_000L
        private const val SNAPSHOT_MAX_AGE_MS = 30 * 60_000L
        private const val REPORT_DETAIL_LIMIT = 100
    }
}
