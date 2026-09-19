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
        val directories: Long
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
            .put("complete", true)
    }

    data class Snapshot(
        val id: String,
        val createdAt: Long,
        val items: List<Item>,
        val totalBytes: Long,
        val totalFiles: Long,
        val visitedDirs: Long,
        val elapsedMs: Long
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
                .put("success", !cancelled)
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
        val complete: Boolean
    )

    private data class Node(val file: File, val post: Boolean)

    fun scan(whitelistJson: String, progress: (String, Int, Int, String) -> Unit): Snapshot {
        val started = SystemClock.elapsedRealtime()
        val whitelist = parseWhitelist(whitelistJson)
        val labels = installedLabels()
        val roots = discoverCacheRoots(whitelist, labels)
        val items = ArrayList<Item>(roots.size)
        var totalBytes = 0L
        var totalFiles = 0L
        var visitedDirs = 0L

        val workerCount = minOf(
            roots.size.coerceAtLeast(1),
            (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        )
        val executor = Executors.newFixedThreadPool(workerCount)
        try {
            val futures = roots.map { seed ->
                executor.submit(Callable {
                    MeasuredRoot(seed, if (cancelled.get()) Stats(0L, 0L, 0L, false) else measure(seed.file))
                })
            }
            futures.forEachIndexed { index, future ->
                if (cancelled.get()) return@forEachIndexed
                val measured = runCatching { future.get() }.getOrNull() ?: return@forEachIndexed
                val seed = measured.seed
                val stats = measured.stats
                progress("正在扫描应用缓存", index + 1, roots.size, seed.path)
                visitedDirs += stats.directories
                if (stats.files > 0L || stats.directories > 0L || stats.bytes > 0L) {
                    items += Item(
                        packageName = seed.packageName,
                        appName = labels[seed.packageName].orEmpty().ifBlank { seed.packageName },
                        category = seed.category,
                        path = seed.path,
                        bytes = stats.bytes,
                        files = stats.files,
                        directories = stats.directories
                    )
                    totalBytes += stats.bytes
                    totalFiles += stats.files
                }
            }
        } finally {
            executor.shutdownNow()
        }

        return Snapshot(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            items = items.sortedWith(compareByDescending<Item> { it.bytes }.thenBy { it.packageName }),
            totalBytes = totalBytes,
            totalFiles = totalFiles,
            visitedDirs = visitedDirs,
            elapsedMs = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
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
            if (cancelled.get()) return@forEachIndexed
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
            val result = clearChildren(root)
            deletedBytes += result.bytes
            deletedFiles += result.files
            deletedDirs += result.directories
            when {
                !result.complete -> {
                    partial += 1
                    remaining += item
                    if (details.length() < MAX_DETAILS) details.put(detail(item, "partial", "部分文件未能删除", result.bytes, result.files, result.directories))
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
                    out.putIfAbsent(path, CacheSeed(packageName, "WebView 缓存", file, path))
                    continue
                }
                file.listFiles()?.filter(File::isDirectory)?.forEach { stack.add(it to depth + 1) }
            }
        }
    }

    private fun measure(root: File): Stats {
        val stack = ArrayDeque<File>()
        stack.add(root)
        var bytes = 0L
        var files = 0L
        var dirs = 0L
        var complete = true
        while (stack.isNotEmpty()) {
            if (cancelled.get()) return Stats(bytes, files, dirs, false)
            val file = stack.removeLast()
            val stat = lstat(file) ?: run { complete = false; continue }
            if (OsConstants.S_ISLNK(stat.st_mode)) continue
            when {
                OsConstants.S_ISREG(stat.st_mode) -> {
                    files += 1
                    bytes += stat.st_size.coerceAtLeast(0L)
                }
                OsConstants.S_ISDIR(stat.st_mode) -> {
                    if (file != root) dirs += 1
                    val children = file.listFiles()
                    if (children == null) complete = false else children.forEach(stack::add)
                }
            }
        }
        return Stats(bytes, files, dirs, complete)
    }

    /** Delete contents, never the app-owned cache root itself. */
    private fun clearChildren(root: File): Stats {
        val children = root.listFiles() ?: return Stats(0, 0, 0, false)
        val stack = ArrayDeque<Node>()
        children.forEach { stack.add(Node(it, false)) }
        var bytes = 0L
        var files = 0L
        var dirs = 0L
        var complete = true
        while (stack.isNotEmpty()) {
            if (cancelled.get()) return Stats(bytes, files, dirs, false)
            val node = stack.removeLast()
            val file = node.file
            val stat = lstat(file) ?: continue
            if (OsConstants.S_ISLNK(stat.st_mode)) continue
            if (node.post) {
                if (runCatching { file.delete() }.getOrDefault(false)) dirs += 1 else complete = false
                continue
            }
            if (OsConstants.S_ISREG(stat.st_mode)) {
                val size = stat.st_size.coerceAtLeast(0L)
                if (runCatching { Os.remove(file.path); true }.getOrDefault(false)) {
                    bytes += size
                    files += 1
                } else complete = false
            } else if (OsConstants.S_ISDIR(stat.st_mode)) {
                stack.add(Node(file, true))
                val nested = file.listFiles()
                if (nested == null) complete = false else nested.forEach { stack.add(Node(it, false)) }
            }
        }
        return Stats(bytes, files, dirs, complete)
    }

    private fun knownCachePath(path: String, packageName: String): Boolean {
        if (!PACKAGE_NAME.matches(packageName)) return false
        val normalized = path.trimEnd('/')
        val pkg = Regex.escape(packageName)
        val internal = Regex("""^/data/(?:user|user_de)/\d+/$pkg/(?:cache|code_cache)(?:/.*)?$""")
        val legacy = Regex("""^/data/data/$pkg/(?:cache|code_cache)(?:/.*)?$""")
        val external = Regex("""^/data/media/\d+/Android/data/$pkg/cache(?:/.*)?$""")
        val webview = Regex("""^/data/(?:user|user_de)/\d+/$pkg/app_(?:webview|hws_webview|x5webview)(?:[^/]*)/.*/(?:Cache|Code Cache|GPUCache|GPU Cache)(?:/.*)?$""")
        val legacyWebview = Regex("""^/data/data/$pkg/app_(?:webview|hws_webview|x5webview)(?:[^/]*)/.*/(?:Cache|Code Cache|GPUCache|GPU Cache)(?:/.*)?$""")
        return internal.matches(normalized) || legacy.matches(normalized) || external.matches(normalized) ||
            webview.matches(normalized) || legacyWebview.matches(normalized)
    }

    private fun installedLabels(): Map<String, String> = runCatching {
        context.packageManager.getInstalledApplications(0).associate { info ->
            val label = runCatching { context.packageManager.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
            info.packageName to label
        }
    }.getOrDefault(emptyMap())

    private fun parseWhitelist(raw: String): Set<String> = runCatching {
        val array = JSONArray(raw)
        buildSet {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (PACKAGE_NAME.matches(value)) add(value)
            }
        }
    }.getOrDefault(emptySet())

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
