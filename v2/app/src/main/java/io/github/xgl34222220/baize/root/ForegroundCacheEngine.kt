package io.github.xgl34222220.baize.root

import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground cache engine owned entirely by the App RootService.
 *
 * It deliberately does not call module scripts or module binaries. The Magisk/KernelSU/APatch
 * module remains a background scheduler/automation component only.
 */
internal class ForegroundCacheEngine(
    private val context: Context,
    private val cancelled: AtomicBoolean
) {
    data class Item(
        val packageName: String,
        val appName: String,
        val category: String,
        val path: String,
        val bytes: Long,
        val files: Long,
        val directories: Long,
        val complete: Boolean = true,
        val identity: String = "",
        val incompleteReason: String = ""
    ) {
        fun json(): JSONObject = JSONObject()
            .put("appName", appName)
            .put("packageName", packageName)
            .put("categoryLabel", category)
            .put("path", path)
            .put("bytes", bytes)
            .put("files", files)
            .put("directories", directories)
            .put("measured", true)
            .put("complete", complete)
            .put("incompleteReason", incompleteReason)
    }

    data class Snapshot(
        val id: String,
        val createdAt: Long,
        val items: List<Item>,
        val totalBytes: Long,
        val totalFiles: Long,
        val visitedDirs: Long,
        val elapsedMs: Long,
        val totalRoots: Int = items.size,
        val scannedRoots: Int = items.size,
        val incompleteRoots: Int = items.count { !it.complete },
        val firstResultMs: Long = elapsedMs
    )

    data class CleanResult(
        val authorizedCandidates: Int,
        val processedCandidates: Int,
        val cleanedCandidates: Int,
        val changedCandidates: Int,
        val protectedCandidates: Int,
        val partialCandidates: Int,
        val failedCandidates: Int,
        val deletedBytes: Long,
        val deletedFiles: Long,
        val deletedDirectories: Long,
        val elapsedMs: Long,
        val cancelled: Boolean,
        val details: JSONArray,
        val remainingItems: List<Item>
    ) {
        fun json(): JSONObject {
            val mutated = deletedFiles > 0L || deletedDirectories > 0L || cleanedCandidates > 0
            val skipped = changedCandidates + protectedCandidates
            return JSONObject()
                .put("success", !cancelled && failedCandidates == 0 && partialCandidates == 0)
                .put("mutated", mutated)
                .put("cancelled", cancelled)
                .put("elapsedMs", elapsedMs)
                .put("authorizedCandidates", authorizedCandidates)
                .put("processedCandidates", processedCandidates)
                .put("cleanedCandidates", cleanedCandidates)
                .put("changedCandidates", changedCandidates)
                .put("protectedCandidates", protectedCandidates)
                .put("partialCandidates", partialCandidates)
                .put("failedCandidates", failedCandidates)
                .put("skippedCandidates", skipped)
                .put("deletedBytes", deletedBytes)
                .put("deletedFiles", deletedFiles)
                .put("deletedDirectories", deletedDirectories)
                .put("failures", failedCandidates)
                .put("details", details)
                .put("message", when {
                    cancelled -> "缓存清理已停止"
                    mutated -> "应用缓存清理完成"
                    skipped > 0 -> "本次缓存已变化或受保护，没有删除文件"
                    else -> "本次未删除任何缓存文件"
                })
        }
    }

    private data class Stats(
        val bytes: Long,
        val files: Long,
        val directories: Long,
        val complete: Boolean,
        val identity: String = "",
        val reason: String = ""
    )


    fun scan(whitelistJson: String, progress: (String, Int, Int, String) -> Unit): Snapshot {
        val started = SystemClock.elapsedRealtime()
        val whitelist = parseWhitelist(whitelistJson)
        val labels = installedLabels()
        val roots = discoverCacheRoots(whitelist, labels)
        val items = ArrayList<Item>(roots.size)
        var totalBytes = 0L
        var totalFiles = 0L
        var visitedDirs = 0L
        var scannedRoots = 0
        var incompleteRoots = 0
        var firstResultMs = -1L

        val workerCount = minOf(
            roots.size.coerceAtLeast(1),
            (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        )
        val executor = Executors.newFixedThreadPool(workerCount)
        try {
            val completions = ExecutorCompletionService<MeasuredRoot>(executor)
            roots.forEach { seed ->
                completions.submit(Callable {
                    MeasuredRoot(seed, if (cancelled.get()) Stats(0L, 0L, 0L, false) else measure(seed.file) { files, bytes ->
                        progress("正在扫描 ${labels[seed.packageName] ?: seed.packageName} · $files 个文件", 0, roots.size, seed.path)
                    })
                })
            }
            var completed = 0
            while (completed < roots.size && !cancelled.get()) {
                val future = completions.poll(150, TimeUnit.MILLISECONDS) ?: continue
                completed++
                val measured = runCatching { future.get() }.getOrNull()
                scannedRoots++
                if (measured == null) { incompleteRoots++; continue }
                val seed = measured.seed
                val stats = measured.stats
                if (!stats.complete) incompleteRoots++
                progress("正在扫描应用缓存", completed, roots.size, seed.path)
                visitedDirs += stats.directories
                if (stats.files > 0L || stats.directories > 0L || stats.bytes > 0L || !stats.complete) {
                    if (firstResultMs < 0) firstResultMs = SystemClock.elapsedRealtime() - started
                    items += Item(
                        packageName = seed.packageName,
                        appName = labels[seed.packageName].orEmpty().ifBlank { seed.packageName },
                        category = seed.category,
                        path = seed.path,
                        bytes = stats.bytes,
                        files = stats.files,
                        directories = stats.directories,
                        complete = stats.complete,
                        identity = stats.identity,
                        incompleteReason = stats.reason
                    )
                    totalBytes += stats.bytes
                    totalFiles += stats.files
                }
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }

        return Snapshot(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            items = items.sortedWith(compareByDescending<Item> { it.bytes }.thenBy { it.packageName }),
            totalBytes = totalBytes,
            totalFiles = totalFiles,
            visitedDirs = visitedDirs,
            elapsedMs = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L),
            totalRoots = roots.size, scannedRoots = scannedRoots,
            incompleteRoots = incompleteRoots + (roots.size - scannedRoots),
            firstResultMs = firstResultMs.coerceAtLeast(0L)
        )
    }

    fun clean(
        snapshot: Snapshot,
        whitelistJson: String,
        progress: (String, Int, Int, String) -> Unit
    ): CleanResult {
        val started = SystemClock.elapsedRealtime()
        val whitelist = parseWhitelist(whitelistJson)
        var processed = 0
        var cleaned = 0
        var changed = 0
        var protected = 0
        var partial = 0
        var failed = 0
        var deletedBytes = 0L
        var deletedFiles = 0L
        var deletedDirs = 0L
        val details = JSONArray()
        val remaining = ArrayList<Item>()

        snapshot.items.forEachIndexed { index, item ->
            if (cancelled.get()) { remaining += item; return@forEachIndexed }
            processed += 1
            progress("正在清理应用缓存", index, snapshot.items.size, item.path)
            if (item.packageName in whitelist || !knownCachePath(item.path, item.packageName)) {
                protected += 1
                remaining += item
                if (details.length() < MAX_DETAILS) details.put(detail(item, "protected", "白名单或路径保护", 0, 0, 0))
                return@forEachIndexed
            }
            val root = File(item.path)
            val stat = lstat(root)
            if (stat == null || !OsConstants.S_ISDIR(stat.st_mode) || OsConstants.S_ISLNK(stat.st_mode)) {
                changed += 1
                remaining += item
                if (details.length() < MAX_DETAILS) details.put(detail(item, "changed", "缓存目录已变化", 0, 0, 0))
                return@forEachIndexed
            }
            val result = clearChildren(root, item.identity) { files, bytes ->
                progress("正在清理 ${item.appName} · $files 个文件", index, snapshot.items.size, item.path)
            }
            deletedBytes += result.bytes
            deletedFiles += result.files
            deletedDirs += result.directories
            when {
                !result.complete -> {
                    partial += 1
                    remaining += item.copy(bytes = (item.bytes - result.bytes).coerceAtLeast(0),
                        files = (item.files - result.files).coerceAtLeast(0),
                        directories = (item.directories - result.directories).coerceAtLeast(0), complete = false,
                        incompleteReason = result.reason)
                    if (details.length() < MAX_DETAILS) details.put(detail(item, "partial", "部分文件保留：${result.reason}", result.bytes, result.files, result.directories))
                }
                result.files > 0 || result.directories > 0 -> {
                    cleaned += 1
                    if (details.length() < MAX_DETAILS) details.put(detail(item, "cleaned", "", result.bytes, result.files, result.directories))
                }
                else -> {
                    changed += 1
                    if (details.length() < MAX_DETAILS) details.put(detail(item, "changed", "目录已经为空", 0, 0, 0))
                }
            }
            if (!result.complete) failed += 1
        }

        return CleanResult(
            authorizedCandidates = snapshot.items.size,
            processedCandidates = processed,
            cleanedCandidates = cleaned,
            changedCandidates = changed,
            protectedCandidates = protected,
            partialCandidates = partial,
            failedCandidates = failed,
            deletedBytes = deletedBytes,
            deletedFiles = deletedFiles,
            deletedDirectories = deletedDirs,
            elapsedMs = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L),
            cancelled = cancelled.get(),
            details = details,
            remainingItems = remaining
        )
    }

    private data class CacheSeed(
        val packageName: String,
        val category: String,
        val file: File,
        val path: String
    )

    private data class MeasuredRoot(
        val seed: CacheSeed,
        val stats: Stats
    )

    private fun discoverCacheRoots(whitelist: Set<String>, labels: Map<String, String>): List<CacheSeed> {
        val result = LinkedHashMap<String, CacheSeed>()
        val packageNames = LinkedHashSet<String>()
        packageNames += labels.keys
        for (base in listOf(File("/data/user"), File("/data/user_de"))) {
            base.listFiles()?.filter { it.isDirectory && it.name.all(Char::isDigit) }?.forEach { userDir ->
                userDir.listFiles()?.filter(File::isDirectory)?.forEach { appDir ->
                    if (PACKAGE_NAME.matches(appDir.name)) packageNames += appDir.name
                }
            }
        }
        File("/data/data").listFiles()?.filter(File::isDirectory)?.forEach { appDir ->
            if (PACKAGE_NAME.matches(appDir.name)) packageNames += appDir.name
        }

        fun add(packageName: String, category: String, file: File) {
            if (packageName in whitelist || !file.isDirectory || isSymlink(file)) return
            val path = canonical(file)
            if (!knownCachePath(path, packageName)) return
            result.putIfAbsent(path, CacheSeed(packageName, category, file, path))
        }

        val userIds = linkedSetOf<String>()
        listOf(File("/data/user"), File("/data/user_de"), File("/data/media")).forEach { base ->
            base.listFiles()?.filter { it.isDirectory && it.name.all(Char::isDigit) }?.forEach { userIds += it.name }
        }
        if (userIds.isEmpty()) userIds += "0"

        packageNames.forEach { pkg ->
            if (cancelled.get()) return@forEach
            if (pkg in whitelist) return@forEach
            for (user in userIds) {
                for (base in listOf("/data/user/$user/$pkg", "/data/user_de/$user/$pkg")) {
                    val app = File(base)
                    add(pkg, "应用缓存", File(app, "cache"))
                    add(pkg, "代码缓存", File(app, "code_cache"))
                    addWebViewCaches(pkg, app, result)
                }
                val external = File("/data/media/$user/Android/data/$pkg/cache")
                add(pkg, "外部缓存", external)
            }
            val legacy = File("/data/data/$pkg")
            add(pkg, "应用缓存", File(legacy, "cache"))
            add(pkg, "代码缓存", File(legacy, "code_cache"))
            addWebViewCaches(pkg, legacy, result)
        }
        return result.values.toList()
    }

    private fun addWebViewCaches(packageName: String, appDir: File, out: MutableMap<String, CacheSeed>) {
        for (engineName in listOf("app_webview", "app_hws_webview", "app_x5webview")) {
            val engine = File(appDir, engineName)
            if (!engine.isDirectory || isSymlink(engine)) continue
            val stack = ArrayDeque<Pair<File, Int>>()
            stack.add(engine to 0)
            while (stack.isNotEmpty()) {
                val (file, depth) = stack.removeLast()
                if (!file.isDirectory || isSymlink(file) || depth > 3) continue
                if (file != engine && file.name in WEBVIEW_CACHE_NAMES) {
                    val path = canonical(file)
                    if (!knownCachePath(path, packageName)) continue
                    out.putIfAbsent(path, CacheSeed(packageName, "WebView 缓存", file, path))
                    continue
                }
                file.listFiles()?.filter(File::isDirectory)?.forEach { stack.add(it to depth + 1) }
            }
        }
    }

    private fun measure(root: File, progress: (Long, Long) -> Unit = { _, _ -> }): Stats =
        SecureCacheTree.measure(root.toPath(), cancelled, 15_000L, 1_000_000L, progress).let {
            Stats(it.bytes, it.files, it.directories, it.complete, it.identity, it.reason)
        }

    private fun clearChildren(root: File, identity: String, progress: (Long, Long) -> Unit): Stats =
        SecureCacheTree.clear(root.toPath(), identity, cancelled, progress).let {
            Stats(it.bytes, it.files, it.directories, it.complete, it.identity, it.reason)
        }

    private fun knownCachePath(path: String, packageName: String): Boolean {
        if (!PACKAGE_NAME.matches(packageName)) return false
        val normalized = path.trimEnd('/')
        val pkg = Regex.escape(packageName)
        val internal = Regex("""^/data/(?:user|user_de)/\d+/$pkg/(?:cache|code_cache)(?:/.*)?$""")
        val legacy = Regex("""^/data/data/$pkg/(?:cache|code_cache)(?:/.*)?$""")
        val external = Regex("""^/data/media/\d+/Android/data/$pkg/cache(?:/.*)?$""")
        val webview = Regex("""^/data/(?:user|user_de)/\d+/$pkg/app_(?:webview|hws_webview|x5webview)(?:[^/]*)/(?:[^/]+/){0,3}(?:Cache|Code Cache|GPUCache|GPU Cache)(?:/.*)?$""")
        val legacyWebview = Regex("""^/data/data/$pkg/app_(?:webview|hws_webview|x5webview)(?:[^/]*)/(?:[^/]+/){0,3}(?:Cache|Code Cache|GPUCache|GPU Cache)(?:/.*)?$""")
        return internal.matches(normalized) || legacy.matches(normalized) || external.matches(normalized) ||
            webview.matches(normalized) || legacyWebview.matches(normalized)
    }

    private fun installedLabels(): Map<String, String> = runCatching {
        context.packageManager.getInstalledApplications(0).associate { info ->
            val label = runCatching { context.packageManager.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
            info.packageName to label
        }
    }.getOrDefault(emptyMap())

    private fun parseWhitelist(raw: String): Set<String> {
        val array = try { JSONArray(raw) } catch (error: Exception) {
            throw IllegalArgumentException("白名单无法读取，已停止操作以保护文件", error)
        }
        return buildSet {
            for (index in 0 until array.length()) {
                val value = array.opt(index) as? String
                    ?: throw IllegalArgumentException("白名单格式无效，已停止操作")
                require(PACKAGE_NAME.matches(value.trim())) { "白名单包名无效，已停止操作" }
                add(value.trim())
            }
        }
    }

    private fun detail(item: Item, action: String, reason: String, bytes: Long, files: Long, dirs: Long) =
        JSONObject()
            .put("action", action)
            .put("category", "cache")
            .put("risk", "low")
            .put("packageName", item.packageName)
            .put("path", item.path)
            .put("bytes", bytes)
            .put("files", files)
            .put("directories", dirs)
            .put("reason", reason)

    private fun canonical(file: File): String = runCatching { file.canonicalFile.path }
        .getOrDefault(file.absoluteFile.normalize().path)

    private fun isSymlink(file: File): Boolean = lstat(file)?.let { OsConstants.S_ISLNK(it.st_mode) } ?: false

    private fun lstat(file: File) = runCatching { Os.lstat(file.path) }.getOrNull()

    companion object {
        private val PACKAGE_NAME = Regex("""^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_-]+)+$""")
        private val WEBVIEW_CACHE_NAMES = setOf("Cache", "Code Cache", "GPUCache", "GPU Cache")
        private const val MAX_DETAILS = 200
    }
}
