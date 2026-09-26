package io.github.xgl34222220.baize.root

import android.content.Context
import android.content.pm.ApplicationInfo
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
    private val cancelled: AtomicBoolean,
    private val dataRoot: File = File("/data"),
    private val redirectFile: File = File(RootPaths.STATE_DIR, "toolbox/mounts.json")
) {
    data class Item(
        val packageName: String,
        val appName: String,
        val category: String,
        val path: String,
        val bytes: Long,
        val files: Long,
        val directories: Long,
        val complete: Boolean = true
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

    private var redirectedRoots = emptySet<String>()

    fun scan(whitelistJson: String, progress: (String, Int, Int, String) -> Unit): Snapshot {
        redirectedRoots = ToolboxRedirect.protectedRoots(redirectFile)
        val started = SystemClock.elapsedRealtime()
        val whitelist = parseWhitelist(whitelistJson)
        val applications = installedApplications()
        val labels = HashMap<String, String>()
        progress("正在发现应用缓存目录", 0, 0, "")
        val roots = discoverCacheRoots(whitelist, applications.keys)
        val items = ArrayList<Item>(roots.size)
        var totalBytes = 0L
        var totalFiles = 0L
        var visitedDirs = 0L

        val executor = Executors.newFixedThreadPool(workerCount(roots.size))
        try {
            val completions = ExecutorCompletionService<MeasuredRoot>(executor)
            roots.forEach { seed ->
                completions.submit(Callable {
                    MeasuredRoot(seed, if (cancelled.get()) Stats(0L, 0L, 0L, false) else measure(seed.file))
                })
            }
            var completed = 0
            while (completed < roots.size && !cancelled.get()) {
                val future = completions.poll(150, TimeUnit.MILLISECONDS) ?: continue
                completed++
                val measured = runCatching { future.get() }.getOrNull() ?: continue
                val seed = measured.seed
                val stats = measured.stats
                progress("正在扫描应用缓存", completed, roots.size, seed.path)
                visitedDirs += stats.directories
                if (stats.files > 0L || stats.directories > 0L || stats.bytes > 0L) {
                    items += Item(
                        packageName = seed.packageName,
                        appName = labels.getOrPut(seed.packageName) {
                            applications[seed.packageName]?.let { info ->
                                runCatching { context.packageManager.getApplicationLabel(info).toString() }.getOrNull()
                            }.orEmpty().ifBlank { seed.packageName }
                        },
                        category = seed.category,
                        path = seed.path,
                        bytes = stats.bytes,
                        files = stats.files,
                        directories = stats.directories,
                        complete = stats.complete
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
        redirectedRoots = ToolboxRedirect.protectedRoots(redirectFile)
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

        val outcomes = arrayOfNulls<CleanOutcome>(snapshot.items.size)
        val groups = snapshot.items.withIndex().groupBy { it.value.packageName }.values.toList()
        progress("正在清理应用缓存", 0, snapshot.items.size, "")
        CacheCleanQueue.run(groups, workerCount(groups.size), cancelled, process = { indexed ->
            cleanItem(indexed.value, whitelist)
        }, completed = { indexed, result ->
            outcomes[indexed.index] = result
            processed += 1
            progress("正在清理应用缓存", processed, snapshot.items.size, indexed.value.path)
        })

        // Aggregate on the caller thread, in snapshot order. Unstarted roots remain available.
        snapshot.items.forEachIndexed { index, item ->
            val outcome = outcomes[index]
            if (outcome == null) {
                remaining += item
                return@forEachIndexed
            }
            val stats = outcome.stats
            deletedBytes += stats.bytes
            deletedFiles += stats.files
            deletedDirs += stats.directories
            when (outcome.action) {
                "protected" -> protected++
                "changed" -> changed++
                "partial" -> { partial++; failed++ }
                "cleaned" -> cleaned++
            }
            if (outcome.keep) {
                remaining += if (outcome.action == "partial") item.copy(
                    bytes = (item.bytes - stats.bytes).coerceAtLeast(0L),
                    files = (item.files - stats.files).coerceAtLeast(0L),
                    directories = (item.directories - stats.directories).coerceAtLeast(0L),
                    complete = false
                ) else item
            }
            if (details.length() < MAX_DETAILS) details.put(detail(
                item, outcome.action, outcome.reason, stats.bytes, stats.files, stats.directories
            ))
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

    private data class CleanOutcome(
        val action: String,
        val reason: String,
        val stats: Stats = Stats(0, 0, 0, true),
        val keep: Boolean = false
    )

    private fun cleanItem(item: Item, whitelist: Set<String>): CleanOutcome {
        if (item.packageName in whitelist || !knownCachePath(item.path, item.packageName)) {
            return CleanOutcome("protected", "白名单或路径保护", keep = true)
        }
        val root = File(item.path)
        val stat = lstat(root)
        if (stat == null || !OsConstants.S_ISDIR(stat.st_mode) || canonical(root) != item.path) {
            return CleanOutcome("changed", "缓存目录已变化", keep = true)
        }
        val result = clearChildren(root)
        return when {
            !result.complete -> CleanOutcome("partial", "部分文件未能删除", result, keep = true)
            result.files > 0 || result.directories > 0 -> CleanOutcome("cleaned", "", result)
            else -> CleanOutcome("changed", "目录已经为空", result)
        }
    }

    private fun workerCount(size: Int): Int = minOf(
        size.coerceAtLeast(1), (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
    )

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

    private fun discoverCacheRoots(whitelist: Set<String>, installed: Set<String>): List<CacheSeed> {
        val result = LinkedHashMap<String, CacheSeed>()
        val packages = installed.toMutableSet()
        val seenApps = HashSet<String>()
        val identity = AndroidPathIdentity(null)

        fun add(packageName: String, category: String, file: File) {
            if (cancelled.get() || packageName in whitelist) return
            val stat = lstat(file) ?: return
            if (!OsConstants.S_ISDIR(stat.st_mode)) return
            val path = canonical(file)
            if (!knownCachePath(path, packageName)) return
            result.putIfAbsent(identity.of(logicalPath(path)), CacheSeed(packageName, category, File(path), path))
        }

        fun scanApp(app: File) {
            if (cancelled.get() || !PACKAGE_NAME.matches(app.name)) return
            val stat = lstat(app) ?: return
            if (!OsConstants.S_ISDIR(stat.st_mode)) return
            val pkg = app.name
            packages += pkg
            if (pkg in whitelist || !seenApps.add(identity.of(logicalPath(canonical(app))))) return
            add(pkg, "应用缓存", File(app, "cache"))
            add(pkg, "代码缓存", File(app, "code_cache"))
            addWebViewCaches(pkg, app, ::add)
        }

        // Enumerate existing app/user directories once instead of probing packages × users.
        for (base in listOf(File(dataRoot, "user"), File(dataRoot, "user_de"))) {
            for (user in base.listFiles().orEmpty()) {
                if (cancelled.get()) return result.values.toList()
                if (!user.name.all(Char::isDigit) || !user.isDirectory || isSymlink(user)) continue
                for (app in user.listFiles().orEmpty()) scanApp(app)
            }
        }
        // Legacy owner CE is a bind/symlink alias on many ROMs; retain it as a fallback only.
        for (app in File(dataRoot, "data").listFiles().orEmpty()) scanApp(app)
        for (user in File(dataRoot, "media").listFiles().orEmpty()) {
            if (cancelled.get()) break
            if (!user.name.all(Char::isDigit) || !user.isDirectory || isSymlink(user)) continue
            for (app in File(user, "Android/data").listFiles().orEmpty()) {
                if (cancelled.get()) break
                if (app.name !in packages || isSymlink(app)) continue
                add(app.name, "外部缓存", File(app, "cache"))
            }
        }
        return result.values.toList()
    }

    private fun addWebViewCaches(packageName: String, appDir: File, add: (String, String, File) -> Unit) {
        for (engineName in listOf("app_webview", "app_hws_webview", "app_x5webview")) {
            val engine = File(appDir, engineName)
            val stack = ArrayDeque<Pair<File, Int>>()
            stack.add(engine to 0)
            while (stack.isNotEmpty()) {
                if (cancelled.get()) return
                val (file, depth) = stack.removeLast()
                if (depth > 3) continue
                val stat = lstat(file) ?: continue
                if (!OsConstants.S_ISDIR(stat.st_mode)) continue
                if (file != engine && file.name in WEBVIEW_CACHE_NAMES) {
                    add(packageName, "WebView 缓存", file)
                    continue
                }
                if (depth < 3) file.listFiles()?.forEach { stack.add(it to depth + 1) }
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
            if (cancelled.get() || Thread.currentThread().isInterrupted) return Stats(bytes, files, dirs, false)
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
            if (cancelled.get() || Thread.currentThread().isInterrupted) return Stats(bytes, files, dirs, false)
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

    private fun logicalPath(path: String): String = when {
        path == dataRoot.path -> "/data"
        path.startsWith("${dataRoot.path}/") -> "/data" + path.removePrefix(dataRoot.path)
        else -> ""
    }

    private fun knownCachePath(path: String, packageName: String): Boolean =
        CachePathPolicy.allows(logicalPath(path), packageName) && !ToolboxRedirect.protects(logicalPath(path), redirectedRoots)

    private fun installedApplications(): Map<String, ApplicationInfo> = runCatching {
        context.packageManager.getInstalledApplications(0).associateBy { it.packageName }
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
        private val PACKAGE_NAME = CachePathPolicy.packageName
        private val WEBVIEW_CACHE_NAMES = CachePathPolicy.webViewCacheNames
        private const val MAX_DETAILS = 200
    }
}
