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
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Alpha 2：发现阶段只验证真实且非空的缓存目录，不递归读取文件。
 * 文件数量和大小按页延迟统计，并设置硬性时间预算，避免再次出现长时间阻塞。
 */
class BaiZeRootService : RootService() {
    private val cancelled = AtomicBoolean(false)
    private val resultLock = Any()
    private val measureLock = Any()

    @Volatile
    private var lastResults: MutableList<ScanCandidate> = mutableListOf()

    private val binder = object : IBaiZeRootService.Stub() {
        override fun ping(): String = JSONObject()
            .put("uid", Process.myUid())
            .put("root", Process.myUid() == 0)
            .put("moduleV1", File("/data/adb/modules/safesweep/module.prop").isFile)
            .put("moduleV2", File("/data/adb/modules/baize_v2_alpha/module.prop").isFile)
            .put("engine", "kotlin-nio-lazy-readonly-v2")
            .toString()

        override fun scanCandidates(whitelistJson: String?): String {
            cancelled.set(false)
            return discoverCandidates(parseWhitelist(whitelistJson.orEmpty()))
        }

        override fun getResultPage(offset: Int, limit: Int): String {
            cancelled.set(false)
            return resultPage(offset, limit)
        }

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

    private fun isSafePackageName(value: String): Boolean {
        if (value.isBlank() || value.length > 255 || !value.contains('.')) return false
        return value.all { it.isLetterOrDigit() || it == '.' || it == '_' }
    }

    private fun discoverCandidates(whitelist: Set<String>): String {
        val started = SystemClock.elapsedRealtime()
        val seeds = enumerateExistingCandidates()
        if (cancelled.get()) {
            synchronized(resultLock) { lastResults = mutableListOf() }
            return cancelledSummary(started, seeds.size)
        }

        val labels = resolveLabels(seeds.map { it.packageName }.toSet())
        val results = seeds.map { seed ->
            ScanCandidate(
                appName = labels[seed.packageName] ?: seed.packageName,
                packageName = seed.packageName,
                category = seed.category,
                categoryLabel = seed.categoryLabel,
                path = seed.path.toString(),
                userId = seed.userId,
                whitelisted = seed.packageName in whitelist
            )
        }.sortedWith(
            compareBy<ScanCandidate> { it.appName.lowercase(Locale.ROOT) }
                .thenBy { it.packageName }
                .thenBy { it.category }
                .thenBy { it.path }
        ).toMutableList()

        synchronized(resultLock) { lastResults = results }
        return JSONObject()
            .put("cancelled", false)
            .put("elapsedMs", SystemClock.elapsedRealtime() - started)
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
        for (packageName in packages) {
            if (cancelled.get()) break
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

    private fun resultPage(rawOffset: Int, rawLimit: Int): String = synchronized(measureLock) {
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
                    val directoryDeadline = min(pageDeadline, SystemClock.elapsedRealtime() + DIRECTORY_BUDGET_MS)
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

    private fun safeAdd(left: Long, right: Long): Long {
        if (right <= 0) return left
        return if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }

    companion object {
        private const val PAGE_MEASURE_BUDGET_MS = 8_000L
        private const val DIRECTORY_BUDGET_MS = 1_500L
    }
}
