package io.github.xgl34222220.baize

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal data class DirectoryUsage(val roots: List<String>, val directories: List<StorageDirectory>,
    val inaccessible: Int, val linksSkipped: Int, val limited: Boolean, val backend: String) {
    val bytes: Long get() = directories.filter { it.path in roots }.sumOf { it.bytes }
    /** 层级索引在首次访问时构建；扫描线程会预先触发，界面只做查询。 */
    val tree: DirectoryUsageTree by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { DirectoryUsageTree(roots, directories) }
    fun children(parent: String?): List<StorageDirectory> = tree.children(parent)
    fun json(): String = JSONObject().put("roots", JSONArray(roots)).put("directories", JSONArray().apply {
        directories.forEach { put(JSONObject().put("path", it.path).put("files", it.files).put("bytes", it.bytes)) }
    }).put("inaccessible", inaccessible).put("linksSkipped", linksSkipped).put("limited", limited).put("backend", backend).toString()
    companion object {
        fun parse(text: String): DirectoryUsage {
            val json = JSONObject(text)
            val roots = json.getJSONArray("roots")
            val dirs = json.getJSONArray("directories")
            return DirectoryUsage(List(roots.length()) { roots.getString(it) }, List(dirs.length()) {
                val row = dirs.getJSONObject(it); StorageDirectory(row.getString("path"), row.getInt("files"), row.getLong("bytes"))
            }, json.getInt("inaccessible"), json.getInt("linksSkipped"), json.getBoolean("limited"), json.getString("backend"))
        }
    }
}

/** Read-only. No links, unbounded recursion, or paths from the caller; never authorizes deletion. */
internal object DirectoryUsageScanner {
    fun scan(roots: Map<File, String>, backend: String = "本地", check: () -> Unit = {},
        cancelled: () -> Boolean = { false }, progress: (Int, String) -> Unit = { _, _ -> },
        maxEntries: Int = 120_000, maxDirectories: Int = 20_000, budgetMs: Long = 20_000): DirectoryUsage {
        data class Count(var files: Int = 0, var bytes: Long = 0)
        val totals = linkedMapOf<String, Count>()
        val accepted = mutableListOf<String>()
        var errors = 0; var links = 0; var entries = 0; var limited = false
        val started = System.nanoTime()
        fun stop(): Boolean {
            check()
            if (cancelled() || entries >= maxEntries || totals.size >= maxDirectories ||
                (System.nanoTime() - started) / 1_000_000 >= budgetMs) { limited = true; return true }
            return false
        }
        for ((file, label) in roots) {
            if (stop()) break
            val root = file.canonicalFile.toPath()
            // Roots are trusted, fixed by the local Context or the service's current user.
            if (!Files.isDirectory(root)) { errors++; continue }
            accepted += label
            totals[label] = Count()
            fun display(path: Path) = label + root.relativize(path).toString().takeIf { it.isNotEmpty() }?.let { "/$it" }.orEmpty()
            try { Files.walkFileTree(root, emptySet(), 96, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (stop()) return FileVisitResult.TERMINATE
                    entries++
                    if (dir.toFile().canonicalFile.toPath() != dir) { links++; return FileVisitResult.SKIP_SUBTREE }
                    totals.putIfAbsent(display(dir), Count())
                    if (entries % 100 == 0) progress(entries, display(dir))
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (stop()) return FileVisitResult.TERMINATE
                    entries++
                    if (attrs.isSymbolicLink || path.toFile().canonicalFile.toPath() != path) links++
                    else if (attrs.isRegularFile) {
                        var parent = path.parent
                        while (parent != null && parent.startsWith(root)) {
                            totals[display(parent)]?.let { it.files++; it.bytes += attrs.size().coerceAtLeast(0) }
                            parent = parent.parent
                        }
                    } else if (attrs.isDirectory) { limited = true } // depth limit
                    if (entries % 100 == 0) progress(entries, display(path))
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, error: java.io.IOException): FileVisitResult {
                    if (stop()) return FileVisitResult.TERMINATE
                    entries++; errors++; return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) errors++
                    return if (stop()) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                }
            }) } catch (error: java.io.IOException) { errors++ }
        }
        return DirectoryUsage(accepted, totals.map { (path, count) -> StorageDirectory(path, count.files, count.bytes) }, errors, links, limited, backend)
    }
}
