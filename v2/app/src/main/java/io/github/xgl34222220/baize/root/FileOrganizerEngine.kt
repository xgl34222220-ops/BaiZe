package io.github.xgl34222220.baize.root

import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit file organizer for download trees.
 *
 * It discovers directories named Download / Downloads / 下载 across public storage,
 * Android/data, Android/media and root-readable app-private storage. A scan only creates an
 * in-memory immutable move plan. Applying a plan revalidates lstat fingerprints and never
 * overwrites an existing destination. The last successful batch can be undone.
 */
class FileOrganizerEngine(
    private val cancelled: AtomicBoolean,
    private val stateDir: File = File("/data/adb/baize-v2")
) {
    data class Progress(
        val phase: String,
        val current: Int = 0,
        val total: Int = 0,
        val path: String = ""
    )

    private data class PlannedMove(
        val id: String,
        val source: String,
        val sourceRoot: String,
        val sourceGroup: String,
        val destination: String,
        val category: String,
        val name: String,
        val bytes: Long,
        val fingerprint: String,
        val sourceUid: Int,
        val sourceGid: Int,
        val sourceMode: Int
    )

    private data class Snapshot(
        val id: String,
        val createdAt: Long,
        val items: List<PlannedMove>
    )

    @Volatile
    private var snapshot: Snapshot? = null

    fun scan(progress: (Progress) -> Unit): String {
        cancelled.set(false)
        val started = SystemClock.elapsedRealtime()
        val roots = discoverDownloadRoots(started, progress)
        if (cancelled.get()) {
            return JSONObject().put("cancelled", true).put("message", "文件归类扫描已停止").toString()
        }

        val items = LinkedHashMap<String, PlannedMove>()
        for ((index, root) in roots.withIndex()) {
            if (cancelled.get() || SystemClock.elapsedRealtime() - started >= SCAN_BUDGET_MS) break
            progress(Progress("正在读取下载目录", index, roots.size, displayPath(root.path)))
            collectFiles(root, started, items, progress)
            if (items.size >= MAX_ITEMS) break
        }

        val id = UUID.randomUUID().toString()
        val immutable = items.values.sortedWith(
            compareBy<PlannedMove> { it.category }.thenBy { it.sourceGroup }.thenBy { it.name.lowercase() }
        )
        snapshot = Snapshot(id, System.currentTimeMillis(), immutable)

        val array = JSONArray()
        immutable.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("category", item.category)
                    .put("bytes", item.bytes)
                    .put("sourceGroup", item.sourceGroup)
                    .put("sourceDisplay", displayPath(item.source))
                    .put("destinationName", File(item.destination).name)
            )
        }
        return JSONObject()
            .put("success", true)
            .put("snapshotId", id)
            .put("expiresInMs", SNAPSHOT_TTL_MS)
            .put("roots", roots.size)
            .put("total", immutable.size)
            .put("truncated", immutable.size >= MAX_ITEMS || SystemClock.elapsedRealtime() - started >= SCAN_BUDGET_MS)
            .put("elapsedMs", SystemClock.elapsedRealtime() - started)
            .put("items", array)
            .toString()
    }

    fun apply(snapshotId: String, selectionJson: String, progress: (Progress) -> Unit): String {
        val current = validSnapshot(snapshotId)
            ?: return error("snapshot_expired", "文件归类计划不存在或已过期，请重新扫描")
        val selectedIds = parseSelection(selectionJson)
        if (selectedIds.isEmpty()) return error("empty_selection", "没有选择需要归类的文件")

        val selected = current.items.filter { it.id in selectedIds }
        if (selected.isEmpty()) return error("empty_selection", "所选文件不属于当前归类计划")

        val moves = JSONArray()
        val details = JSONArray()
        var moved = 0
        var skipped = 0
        var failed = 0
        var bytes = 0L

        for ((index, item) in selected.withIndex()) {
            if (cancelled.get()) break
            progress(Progress("正在归类 ${item.category}", index, selected.size, displayPath(item.source)))
            val source = File(item.source)
            val destination = File(item.destination)
            val reason = validatePlannedMove(item, source, destination)
            if (reason != null) {
                skipped += 1
                details.put(detail(item, "skipped", reason))
                continue
            }

            val result = runCatching {
                destination.parentFile?.mkdirs()
                if (!moveVerified(source, destination)) {
                    false
                } else if (!normalizeSharedOwnership(destination)) {
                    moveVerified(destination, source)
                    false
                } else {
                    true
                }
            }
            if (result.getOrDefault(false)) {
                moved += 1
                bytes += item.bytes
                val destinationFingerprint = fingerprint(destination)
                moves.put(
                    JSONObject()
                        .put("source", item.source)
                        .put("destination", item.destination)
                        .put("destinationFingerprint", destinationFingerprint)
                        .put("sourceUid", item.sourceUid)
                        .put("sourceGid", item.sourceGid)
                        .put("sourceMode", item.sourceMode)
                )
                details.put(detail(item, "moved", ""))
            } else {
                failed += 1
                details.put(detail(item, "failed", result.exceptionOrNull()?.message ?: "移动失败"))
            }
        }

        if (moves.length() > 0) persistUndo(moves) else undoFile().delete()
        snapshot = null
        return JSONObject()
            .put("success", failed == 0)
            .put("cancelled", cancelled.get())
            .put("moved", moved)
            .put("skipped", skipped)
            .put("failed", failed)
            .put("bytes", bytes)
            .put("undoAvailable", moves.length() > 0)
            .put("details", details)
            .toString()
    }

    fun undo(progress: (Progress) -> Unit): String {
        val record = readUndo()
            ?: return error("undo_missing", "没有可撤销的文件归类记录")
        val moves = record.optJSONArray("moves") ?: JSONArray()
        if (moves.length() == 0) return error("undo_missing", "没有可撤销的文件归类记录")

        val remaining = JSONArray()
        val details = JSONArray()
        var restored = 0
        var skipped = 0
        var failed = 0

        for (index in moves.length() - 1 downTo 0) {
            if (cancelled.get()) {
                for (rest in 0..index) remaining.put(moves.optJSONObject(rest))
                break
            }
            val move = moves.optJSONObject(index) ?: continue
            val source = File(move.optString("source"))
            val destination = File(move.optString("destination"))
            progress(Progress("正在撤销文件归类", moves.length() - index, moves.length(), displayPath(destination.path)))

            val expected = move.optString("destinationFingerprint")
            val reason = when {
                source.exists() -> "原位置已存在同名文件"
                !destination.isFile -> "归类后的文件已不存在"
                isSymlink(destination) -> "符号链接不允许撤销"
                expected.isBlank() || fingerprint(destination) != expected -> "归类后的文件已发生变化"
                !allowedDownloadSource(source.path) -> "原路径不再属于允许的下载目录"
                else -> null
            }
            if (reason != null) {
                skipped += 1
                remaining.put(move)
                details.put(JSONObject().put("destination", displayPath(destination.path)).put("action", "skipped").put("reason", reason))
                continue
            }

            val sourceParent = source.parentFile
            if (sourceParent?.isDirectory != true) {
                skipped += 1
                remaining.put(move)
                details.put(JSONObject().put("destination", displayPath(destination.path)).put("action", "skipped").put("reason", "原目录已不存在"))
                continue
            }
            val ok = runCatching {
                if (!moveVerified(destination, source)) {
                    false
                } else if (!restoreOriginalMetadata(source, move)) {
                    moveVerified(source, destination)
                    normalizeSharedOwnership(destination)
                    false
                } else {
                    true
                }
            }.getOrDefault(false)
            if (ok) {
                restored += 1
                details.put(JSONObject().put("source", displayPath(source.path)).put("action", "restored"))
            } else {
                failed += 1
                remaining.put(move)
                details.put(JSONObject().put("destination", displayPath(destination.path)).put("action", "failed").put("reason", "恢复失败"))
            }
        }

        if (remaining.length() == 0) {
            undoFile().delete()
        } else {
            persistUndo(remaining)
        }
        return JSONObject()
            .put("success", failed == 0)
            .put("cancelled", cancelled.get())
            .put("restored", restored)
            .put("skipped", skipped)
            .put("failed", failed)
            .put("undoAvailable", remaining.length() > 0)
            .put("details", details)
            .toString()
    }

    private fun discoverDownloadRoots(started: Long, progress: (Progress) -> Unit): List<File> {
        val roots = LinkedHashMap<String, File>()
        val scanBases = mutableListOf<Pair<File, Int>>()

        File("/data/media").listFiles()
            ?.filter { it.isDirectory && it.name.all { char -> char.isDigit() } }
            ?.forEach { scanBases += it to 7 }

        listOf(File("/data/user"), File("/data/user_de")).forEach { base ->
            base.listFiles()
                ?.filter { it.isDirectory && it.name.all { char -> char.isDigit() } }
                ?.forEach { user ->
                    user.listFiles()?.filter { it.isDirectory }?.forEach { packageDir ->
                        scanBases += packageDir to 6
                    }
                }
        }

        var visited = 0
        for ((base, depthLimit) in scanBases) {
            if (cancelled.get() || SystemClock.elapsedRealtime() - started >= DISCOVERY_BUDGET_MS) break
            val stack = ArrayDeque<Pair<File, Int>>()
            stack.add(base to 0)
            while (stack.isNotEmpty()) {
                if (cancelled.get() || SystemClock.elapsedRealtime() - started >= DISCOVERY_BUDGET_MS) break
                val (dir, depth) = stack.removeLast()
                if (!dir.isDirectory || isSymlink(dir)) continue
                val path = canonical(dir)
                if (path.contains("/BaiZe归类/") || path.endsWith("/BaiZe归类")) continue
                visited += 1
                if (visited % 500 == 0) progress(Progress("正在发现全部下载目录", visited, 0, displayPath(path)))
                if (isDownloadDirectoryName(dir.name) && allowedDownloadRoot(path)) {
                    roots.putIfAbsent(path, dir)
                    continue
                }
                if (depth >= depthLimit || shouldPruneDiscovery(path, dir.name)) continue
                val children = dir.listFiles() ?: continue
                children.filter { it.isDirectory && !isSymlink(it) }.forEach { stack.add(it to depth + 1) }
            }
        }
        return roots.values.sortedBy { it.path }
    }

    private fun collectFiles(
        root: File,
        started: Long,
        out: MutableMap<String, PlannedMove>,
        progress: (Progress) -> Unit
    ) {
        val rootPath = canonical(root)
        val userId = userIdForPath(rootPath) ?: return
        val destinationBase = File("/data/media/$userId/BaiZe归类")
        val sourceGroup = sourceGroup(rootPath)
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.add(root to 0)
        var visited = 0

        while (stack.isNotEmpty() && out.size < MAX_ITEMS) {
            if (cancelled.get() || SystemClock.elapsedRealtime() - started >= SCAN_BUDGET_MS) return
            val (file, depth) = stack.removeLast()
            if (!file.exists() || isSymlink(file)) continue
            val path = canonical(file)
            if (!path.startsWith("$rootPath/") && path != rootPath) continue
            if (file.isDirectory) {
                if (depth >= MAX_FILE_DEPTH) continue
                val children = file.listFiles() ?: continue
                children.forEach { stack.add(it to depth + 1) }
                continue
            }
            if (!file.isFile || skipFile(file)) continue
            visited += 1
            if (visited % 200 == 0) progress(Progress("正在建立不可变归类计划", visited, 0, displayPath(path)))
            val sourceStat = runCatching { Os.lstat(file.path) }.getOrNull() ?: continue
            val statFingerprint = fingerprint(sourceStat)
            val category = category(file.name)
            val destination = File(File(destinationBase, category), file.name)
            val id = sha256Text("$path\u0000$statFingerprint")
            out.putIfAbsent(
                id,
                PlannedMove(
                    id = id,
                    source = path,
                    sourceRoot = rootPath,
                    sourceGroup = sourceGroup,
                    destination = destination.path,
                    category = category,
                    name = file.name,
                    bytes = file.length().coerceAtLeast(0L),
                    fingerprint = statFingerprint,
                    sourceUid = sourceStat.st_uid,
                    sourceGid = sourceStat.st_gid,
                    sourceMode = sourceStat.st_mode
                )
            )
        }
    }

    private fun validatePlannedMove(item: PlannedMove, source: File, destination: File): String? {
        val sourcePath = canonical(source)
        if (sourcePath != item.source) return "源路径已变化"
        if (!source.isFile) return "文件已不存在"
        if (isSymlink(source)) return "符号链接受保护"
        if (!allowedDownloadSource(sourcePath)) return "源文件不再属于下载目录"
        if (fingerprint(source) != item.fingerprint) return "文件在扫描后发生变化"
        if (destination.exists()) return "归类目录已有同名文件"
        if (!destination.path.startsWith("/data/media/")) return "目标路径超出公共归类目录"
        return null
    }

    private fun moveVerified(source: File, destination: File): Boolean {
        if (destination.exists()) return false
        return try {
            Os.rename(source.path, destination.path)
            true
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.EXDEV) throw error
            copyAcrossFilesystems(source, destination)
        }
    }

    private fun copyAcrossFilesystems(source: File, destination: File): Boolean {
        val parent = destination.parentFile ?: return false
        parent.mkdirs()
        val temp = File(parent, ".${destination.name}.baize-${UUID.randomUUID()}.tmp")
        if (temp.exists()) temp.delete()
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output, 1024 * 1024)
                    output.fd.sync()
                }
            }
            if (source.length() != temp.length()) return false
            if (sha256(source) != sha256(temp)) return false
            if (destination.exists()) return false
            Os.rename(temp.path, destination.path)
            if (!source.delete()) {
                destination.delete()
                return false
            }
            return true
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun validSnapshot(id: String): Snapshot? {
        val current = snapshot ?: return null
        if (current.id != id || System.currentTimeMillis() - current.createdAt > SNAPSHOT_TTL_MS) {
            snapshot = null
            return null
        }
        return current
    }

    private fun parseSelection(raw: String): Set<String> {
        val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val array = json.optJSONArray("ids") ?: JSONArray()
        val result = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            val id = array.optString(index)
            if (id.matches(Regex("^[a-f0-9]{64}$"))) result += id
        }
        return result
    }

    private fun persistUndo(moves: JSONArray) {
        stateDir.mkdirs()
        val file = undoFile()
        val temp = File(stateDir, "${file.name}.tmp")
        temp.writeText(JSONObject().put("createdAt", System.currentTimeMillis()).put("moves", moves).toString())
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    private fun readUndo(): JSONObject? = runCatching {
        val file = undoFile()
        if (!file.isFile) null else JSONObject(file.readText())
    }.getOrNull()

    private fun undoFile() = File(stateDir, "organizer-last.json")

    private fun normalizeSharedOwnership(file: File): Boolean = runCatching {
        val root = mediaUserRoot(file.path) ?: return@runCatching false
        val owner = Os.lstat(root.path)
        var parent = file.parentFile
        while (parent != null && canonical(parent).startsWith(root.path + "/")) {
            Os.chown(parent.path, owner.st_uid, owner.st_gid)
            Os.chmod(parent.path, 0x1F8) // 0770
            if (canonical(parent) == root.path) break
            parent = parent.parentFile
        }
        Os.chown(file.path, owner.st_uid, owner.st_gid)
        Os.chmod(file.path, 0x1B0) // 0660
        true
    }.getOrDefault(false)

    private fun restoreOriginalMetadata(file: File, move: JSONObject): Boolean = runCatching {
        val uid = move.optInt("sourceUid", -1)
        val gid = move.optInt("sourceGid", -1)
        val mode = move.optInt("sourceMode", -1)
        if (uid < 0 || gid < 0 || mode < 0) return@runCatching false
        Os.chown(file.path, uid, gid)
        Os.chmod(file.path, mode and 0x1FF)
        true
    }.getOrDefault(false)

    private fun mediaUserRoot(path: String): File? {
        val user = Regex("^/data/media/(\\d+)(?:/|$)").find(path)?.groupValues?.getOrNull(1) ?: return null
        return File("/data/media/$user").takeIf { it.isDirectory }
    }

    private fun allowedDownloadRoot(path: String): Boolean {
        if (!path.startsWith("/data/media/") && !path.startsWith("/data/user/") && !path.startsWith("/data/user_de/")) return false
        if (path.contains("/BaiZe归类/") || path.endsWith("/BaiZe归类")) return false
        return path.split('/').any { segment -> isDownloadDirectoryName(segment) }
    }

    private fun allowedDownloadSource(path: String): Boolean =
        allowedDownloadRoot(path.substringBeforeLast('/', path))

    private fun shouldPruneDiscovery(path: String, name: String): Boolean {
        val lower = name.lowercase()
        if (lower in setOf("cache", "code_cache", "databases", "shared_prefs", "lib", "oat", "no_backup")) return true
        if (path.contains("/Android/obb/")) return true
        return false
    }

    private fun isDownloadDirectoryName(name: String): Boolean {
        val normalized = name.trim().lowercase()
        return normalized == "download" || normalized == "downloads" || normalized == "下载"
    }

    private fun skipFile(file: File): Boolean {
        val name = file.name.lowercase()
        if (name.startsWith(".") && name !in setOf(".epub", ".pdf")) return true
        return name == ".nomedia" || name.endsWith(".lock") || name.endsWith(".lck") ||
            name.endsWith(".db") || name.endsWith(".sqlite") || name.endsWith(".sqlite3") ||
            name.endsWith("-wal") || name.endsWith("-shm") || name.endsWith(".journal") ||
            name.endsWith(".part") || name.endsWith(".partial") || name.endsWith(".crdownload") ||
            name.endsWith(".download") || name.endsWith(".tmp") || name.endsWith(".temp")
    }

    private fun category(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "dng", "raw" -> "图片"
            "mp4", "mkv", "mov", "avi", "webm", "flv", "wmv", "m4v", "3gp", "ts" -> "视频"
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "ape", "wma", "amr" -> "音频"
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv", "md", "odt", "ods", "odp" -> "文档"
            "apk", "apks", "xapk", "apkm", "aab" -> "安装包"
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst", "tgz", "tbz2" -> "压缩包"
            "epub", "mobi", "azw", "azw3", "fb2", "cbz", "cbr", "djvu" -> "电子书"
            else -> "其他"
        }
    }

    private fun sourceGroup(path: String): String {
        val packageName = Regex("/Android/(?:data|media)/([^/]+)/").find("$path/")?.groupValues?.getOrNull(1)
            ?: Regex("/data/(?:user|user_de)/\\d+/([^/]+)/").find("$path/")?.groupValues?.getOrNull(1)
        return packageName ?: "公共下载"
    }

    private fun userIdForPath(path: String): Int? {
        val media = Regex("^/data/media/(\\d+)/").find("$path/")?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (media != null) return media
        return Regex("^/data/(?:user|user_de)/(\\d+)/").find("$path/")?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun fingerprint(file: File): String = runCatching {
        fingerprint(Os.lstat(file.path))
    }.getOrDefault("")

    private fun fingerprint(stat: android.system.StructStat): String =
        "${stat.st_dev}:${stat.st_ino}:${stat.st_size}:${stat.st_mtime}"

    private fun isSymlink(file: File): Boolean = runCatching {
        OsConstants.S_ISLNK(Os.lstat(file.path).st_mode)
    }.getOrDefault(true)

    private fun canonical(file: File): String =
        runCatching { file.canonicalFile.path }.getOrDefault(file.absoluteFile.normalize().path)

    private fun displayPath(path: String): String = path
        .replace(Regex("^/data/media/\\d+"), "内部存储")
        .replace(Regex("^/data/(?:user|user_de)/\\d+/([^/]+)"), "应用私有/$1")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Text(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun detail(item: PlannedMove, action: String, reason: String): JSONObject =
        JSONObject()
            .put("id", item.id)
            .put("name", item.name)
            .put("category", item.category)
            .put("sourceGroup", item.sourceGroup)
            .put("action", action)
            .put("reason", reason)

    private fun error(code: String, message: String): String =
        JSONObject().put("success", false).put("error", code).put("message", message).toString()

    companion object {
        private const val SNAPSHOT_TTL_MS = 30L * 60_000L
        private const val DISCOVERY_BUDGET_MS = 45_000L
        private const val SCAN_BUDGET_MS = 2L * 60_000L
        private const val MAX_ITEMS = 5_000
        private const val MAX_FILE_DEPTH = 12
    }
}
