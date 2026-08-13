package io.github.xgl34222220.baize.root

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

/**
 * Transactional quarantine store for high-risk native-profile candidates.
 *
 * The UI never supplies a filesystem path to this repository. [NativeProfileEngine] resolves a
 * candidate from an unexpired server-side snapshot, validates it again, and only then calls
 * [quarantine]. Metadata is committed before the same-filesystem atomic move. A failed move removes
 * the pending record and leaves the original untouched; a successful move remains recoverable.
 */
internal class QuarantineRepository(
    private val stateDir: File = File(RootPaths.STATE_DIR),
    private val configFile: File = File(RootPaths.CONFIG_FILE)
) {
    data class Result(
        val success: Boolean,
        val id: String = "",
        val bytes: Long = 0L,
        val files: Long = 0L,
        val directories: Long = 0L,
        val message: String = "",
        val restoredPath: String = ""
    ) {
        fun json(): JSONObject = JSONObject()
            .put("success", success)
            .put("id", id)
            .put("bytes", bytes)
            .put("files", files)
            .put("directories", directories)
            .put("message", message)
            .put("restoredPath", restoredPath)
    }

    private data class Stats(
        val bytes: Long,
        val files: Long,
        val directories: Long
    )

    private data class Entry(
        val id: String,
        val originalPath: String,
        val storedPath: String,
        val profile: String,
        val category: String,
        val label: String,
        val risk: String,
        val snapshotId: String,
        val createdAt: Long,
        val expiresAt: Long,
        val bytes: Long,
        val files: Long,
        val directories: Long,
        val state: String = "ready"
    ) {
        fun json(includeStoredPath: Boolean = false): JSONObject = JSONObject()
            .put("id", id)
            .put("originalPath", originalPath)
            .put("profile", profile)
            .put("category", category)
            .put("label", label)
            .put("risk", risk)
            .put("snapshotId", snapshotId)
            .put("createdAt", createdAt)
            .put("expiresAt", expiresAt)
            .put("bytes", bytes)
            .put("files", files)
            .put("directories", directories)
            .put("state", state)
            .apply { if (includeStoredPath) put("storedPath", storedPath) }
    }

    private val rootDir = File(stateDir, "quarantine")
    private val metadataDir = File(rootDir, "metadata")
    private val privateItemsDir = File(rootDir, "items")

    @Synchronized
    fun quarantine(
        snapshotId: String,
        candidateId: String,
        originalPath: String,
        profile: String,
        category: String,
        label: String,
        risk: String
    ): Result {
        purgeExpiredInternal(System.currentTimeMillis())
        if (risk != "high") return Result(false, message = "只有高风险候选可以进入隔离区")
        if (snapshotId.isBlank() || candidateId.isBlank()) return Result(false, message = "隔离授权无效，请重新扫描")

        val source = File(originalPath)
        val sourcePath = canonical(source)
        if (!safeOriginalPath(sourcePath) || !source.exists() || isSymlink(source)) {
            return Result(false, message = "目标已变化或超出隔离安全边界")
        }
        if (isQuarantinePath(sourcePath)) return Result(false, message = "目标已经位于隔离区")

        val stats = runCatching { measure(source, SystemClock.elapsedRealtime() + MEASURE_BUDGET_MS) }
            .getOrElse { return Result(false, message = it.message ?: "无法核对隔离目标") }
        val maxBytes = quarantineMaxBytes()
        var usedBytes = 0L
        for (entry in readEntries().filter { it.state == "ready" || it.state == "pending" }) {
            if (entry.bytes > maxBytes - usedBytes) {
                usedBytes = maxBytes
                break
            }
            usedBytes += entry.bytes
        }
        if (stats.bytes > maxBytes || usedBytes > maxBytes - stats.bytes) {
            return Result(false, message = "隔离区容量上限为 ${humanBytes(maxBytes)}，请先恢复或永久删除旧项目")
        }
        val id = UUID.randomUUID().toString()
        val destinationRoot = quarantineRootFor(sourcePath)
        val destination = File(File(destinationRoot, "items"), "$id-${safeName(source.name)}")
        val destinationPath = canonicalWithoutExistence(destination)
        if (!isQuarantinePath(destinationPath) || destination.exists()) {
            return Result(false, message = "无法创建安全隔离位置")
        }
        destination.parentFile?.mkdirs()
        if (isSymlink(destination.parentFile ?: destination)) return Result(false, message = "隔离目录异常")

        val now = System.currentTimeMillis()
        val retentionDays = retentionDays()
val entry = Entry(
    id = id,
    originalPath = sourcePath,
    storedPath = destinationPath,
    profile = profile.take(MAX_TEXT),
    category = category.take(MAX_TEXT),
    label = label.take(MAX_TEXT),
    risk = risk,
    snapshotId = snapshotId.take(MAX_TEXT),
    createdAt = now,
    expiresAt = now + retentionDays * DAY_MS,
    bytes = stats.bytes,
    files = stats.files,
    directories = stats.directories,
    state = "pending"
)
if (runCatching { writeEntry(entry) }.isFailure) {
    return Result(false, message = "隔离记录写入失败，原文件未移动")
}

val moved = runCatching { source.renameTo(destination) }.getOrDefault(false)
if (!moved || source.exists() || !destination.exists()) {
    if (!destination.exists()) deleteMetadata(id)
    return Result(
        success = false,
        id = if (destination.exists()) id else "",
        bytes = if (destination.exists()) stats.bytes else 0L,
        files = if (destination.exists()) stats.files else 0L,
        directories = if (destination.exists()) stats.directories else 0L,
        message = if (destination.exists()) {
            "隔离移动状态异常，已保留恢复记录"
        } else {
            "无法在同一文件系统原子移动目标；原文件未删除"
        }
    )
}

if (runCatching { writeEntry(entry.copy(state = "ready")) }.isFailure) {
    return Result(false, id, stats.bytes, stats.files, stats.directories, "内容已隔离，但完成记录写入失败；保留待恢复记录")
}

return Result(true, id, stats.bytes, stats.files, stats.directories, "已安全隔离，可在 $retentionDays 天内恢复")
    }

    @Synchronized
    fun page(offset: Int, limit: Int): String {
        reconcilePendingEntries()
        val now = System.currentTimeMillis()
        val expired = purgeExpiredInternal(now)
        val entries = readEntries().sortedByDescending { it.createdAt }
        val start = offset.coerceAtLeast(0).coerceAtMost(entries.size)
        val count = limit.coerceIn(1, MAX_PAGE_SIZE)
        val end = (start + count).coerceAtMost(entries.size)
        return JSONObject()
            .put("success", true)
            .put("total", entries.size)
            .put("offset", start)
            .put("limit", count)
            .put("retentionDays", retentionDays())
            .put("expiredPurged", expired)
            .put("items", JSONArray().apply {
                for (index in start until end) put(entries[index].json())
            })
            .toString()
    }

    @Synchronized
    fun restore(id: String?): String {
        val entry = readEntry(id.orEmpty())
            ?: return errorJson("not_found", "隔离记录不存在或已经过期")
        if (entry.state != "ready") return errorJson("pending_entry", "隔离事务尚未完成，请刷新后重试")
        val payload = File(entry.storedPath)
        if (!isStoredPayload(entry, payload) || !payload.exists() || isSymlink(payload)) {
            deleteMetadata(entry.id)
            return errorJson("payload_missing", "隔离内容已不存在，记录已清理")
        }

        val original = File(entry.originalPath)
        if (!safeOriginalPath(canonicalWithoutExistence(original))) return errorJson("unsafe_restore", "原路径已超出恢复安全边界")
        val destination = if (!original.exists()) {
            original
        } else {
            File(original.parentFile ?: return errorJson("restore_conflict", "原路径已存在且无法创建恢复副本"),
                "${original.name}.baize-restored-${entry.id.take(8)}")
        }
        if (destination.exists()) return errorJson("restore_conflict", "原路径和恢复副本路径都已存在")
        val destinationPath = canonicalWithoutExistence(destination)
        if (!safeOriginalPath(destinationPath)) return errorJson("unsafe_restore", "恢复目标已超出安全边界")
        destination.parentFile?.mkdirs()

        val restored = runCatching { payload.renameTo(destination) }.getOrDefault(false)
        if (!restored || payload.exists() || !destination.exists()) {
            return errorJson("restore_failed", "恢复移动失败，隔离内容保持不变")
        }
        deleteMetadata(entry.id)
        pruneEmptyParents(payload.parentFile)
        return Result(
            success = true,
            id = entry.id,
            bytes = entry.bytes,
            files = entry.files,
            directories = entry.directories,
            message = if (destination.path == original.path) "已恢复到原路径" else "原路径已有内容，已恢复为安全副本",
            restoredPath = destination.path
        ).json().toString()
    }

    @Synchronized
    fun purge(id: String?): String {
        val entry = readEntry(id.orEmpty())
            ?: return errorJson("not_found", "隔离记录不存在")
        val payload = File(entry.storedPath)
        if (payload.exists()) {
            if (!isStoredPayload(entry, payload)) return errorJson("unsafe_payload", "隔离路径校验失败，拒绝永久删除")
            if (!deleteTree(payload)) return errorJson("purge_failed", "部分隔离内容无法永久删除")
        }
        deleteMetadata(entry.id)
        pruneEmptyParents(payload.parentFile)
        return JSONObject().put("success", true).put("id", entry.id).put("message", "隔离内容已永久删除").toString()
    }

    @Synchronized
    fun purgeExpired(): String {
        val count = purgeExpiredInternal(System.currentTimeMillis())
        return JSONObject().put("success", true).put("purged", count).put("message", "已清理 $count 个过期隔离项").toString()
    }

    private fun purgeExpiredInternal(now: Long): Int {
        var purged = 0
        for (entry in readEntries()) {
            if (entry.expiresAt > now) continue
            val payload = File(entry.storedPath)
            if (payload.exists()) {
                if (!isStoredPayload(entry, payload)) continue
                if (!deleteTree(payload)) continue
            }
            deleteMetadata(entry.id)
            pruneEmptyParents(payload.parentFile)
            purged += 1
        }
        return purged
    }

    private fun writeEntry(entry: Entry) {
        metadataDir.mkdirs()
        privateItemsDir.mkdirs()
        val target = File(metadataDir, "${entry.id}.json")
        val temporary = File(metadataDir, "${entry.id}.json.tmp.${android.os.Process.myPid()}")
        temporary.writeText(entry.json(includeStoredPath = true).toString())
        runCatching { java.io.FileOutputStream(temporary, true).use { it.fd.sync() } }
        temporary.setReadable(true, true)
        temporary.setWritable(true, true)
        runCatching {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.recoverCatching {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            temporary.delete()
            error("无法原子发布隔离记录")
        }
    }

    private fun readEntries(): List<Entry> = metadataDir.listFiles()
        ?.asSequence()
        ?.filter { it.isFile && !isSymlink(it) && it.name.endsWith(".json") }
        ?.mapNotNull { parseEntry(runCatching { JSONObject(it.readText()) }.getOrNull()) }
        ?.toList()
        .orEmpty()

    private fun readEntry(id: String): Entry? {
        if (!ID_PATTERN.matches(id)) return null
        val file = File(metadataDir, "$id.json")
        if (!file.isFile || isSymlink(file)) return null
        return parseEntry(runCatching { JSONObject(file.readText()) }.getOrNull())
    }

    private fun parseEntry(json: JSONObject?): Entry? {
        if (json == null) return null
        val id = json.optString("id")
        val original = json.optString("originalPath")
        val stored = json.optString("storedPath")
        if (!ID_PATTERN.matches(id) || !safeOriginalPath(original) || stored.isBlank()) return null
        return Entry(
            id = id,
            originalPath = original,
            storedPath = stored,
            profile = json.optString("profile"),
            category = json.optString("category"),
            label = json.optString("label"),
            risk = json.optString("risk"),
            snapshotId = json.optString("snapshotId"),
            createdAt = json.optLong("createdAt"),
            expiresAt = json.optLong("expiresAt"),
            bytes = json.optLong("bytes").coerceAtLeast(0L),
            files = json.optLong("files").coerceAtLeast(0L),
            directories = json.optLong("directories").coerceAtLeast(0L),
            state = json.optString("state", "ready").takeIf { it == "ready" || it == "pending" } ?: "pending"
        )
    }

    private fun deleteMetadata(id: String) {
        if (ID_PATTERN.matches(id)) File(metadataDir, "$id.json").delete()
    }

    private fun measure(root: File, deadline: Long): Stats {
        val mounts = mountPoints()
        val stack = ArrayDeque<File>()
        stack.add(root)
        var bytes = 0L
        var files = 0L
        var directories = 0L
        var visited = 0
        while (stack.isNotEmpty()) {
            if (SystemClock.elapsedRealtime() >= deadline) error("隔离前核对超时，原文件未移动")
            val file = stack.removeLast()
            val path = canonical(file)
            if (!file.exists() || isSymlink(file)) error("目标包含符号链接或扫描期间发生变化")
            if (file != root && mounts.contains(path)) error("目标包含独立挂载点，拒绝隔离")
            visited += 1
            if (visited > MAX_NODES) error("目标项目过多，拒绝隔离")
            if (file.isFile) {
                files += 1L
                bytes = Math.addExact(bytes, file.length().coerceAtLeast(0L))
            } else if (file.isDirectory) {
                if (file != root) directories += 1L
                val children = file.listFiles() ?: error("无法完整读取目标目录")
                children.forEach(stack::add)
            } else {
                error("目标包含不支持的文件类型")
            }
        }
        return Stats(bytes, files, directories)
    }

    private fun deleteTree(root: File): Boolean {
        if (!root.exists()) return true
        if (!isQuarantinePath(canonical(root)) || isSymlink(root)) return false
        val stack = ArrayDeque<Pair<File, Boolean>>()
        stack.add(root to false)
        var success = true
        while (stack.isNotEmpty()) {
            val (file, post) = stack.removeLast()
            if (!file.exists()) continue
            if (isSymlink(file)) {
                success = false
                continue
            }
            if (post || file.isFile) {
                if (!file.delete()) success = false
            } else if (file.isDirectory) {
                stack.add(file to true)
                val children = file.listFiles()
                if (children == null) success = false else children.forEach { stack.add(it to false) }
            } else {
                success = false
            }
        }
        return success && !root.exists()
    }

    private fun retentionDays(): Int = RootFileStore.readEnv(configFile)
        .optInt("quarantine_retention_days", DEFAULT_RETENTION_DAYS)
        .coerceIn(1, 30)

    private fun quarantineMaxBytes(): Long = RootFileStore.readEnv(configFile)
        .optLong("quarantine_max_bytes", DEFAULT_MAX_BYTES)
        .coerceIn(MIN_MAX_BYTES, ABSOLUTE_MAX_BYTES)

    private fun reconcilePendingEntries() {
        for (entry in readEntries().filter { it.state == "pending" }) {
            val source = File(entry.originalPath)
            val payload = File(entry.storedPath)
            when {
                payload.exists() && !source.exists() && isStoredPayload(entry, payload) ->
                    runCatching { writeEntry(entry.copy(state = "ready")) }
                source.exists() && !payload.exists() -> deleteMetadata(entry.id)
            }
        }
    }

    private fun humanBytes(value: Long): String = when {
        value >= 1_073_741_824L -> String.format(Locale.US, "%.1f GB", value / 1_073_741_824.0)
        value >= 1_048_576L -> String.format(Locale.US, "%.1f MB", value / 1_048_576.0)
        else -> "$value B"
    }

    private fun quarantineRootFor(path: String): File {
        val emulated = Regex("^/storage/emulated/([0-9]+)(?:/.*)?$").find(path)
        if (emulated != null) return File("/storage/emulated/${emulated.groupValues[1]}/.baize-quarantine")
        if (path == "/sdcard" || path.startsWith("/sdcard/")) return File("/sdcard/.baize-quarantine")
        val media = Regex("^/data/media/([0-9]+)(?:/.*)?$").find(path)
        if (media != null) return File("/data/media/${media.groupValues[1]}/.baize-quarantine")
        return rootDir
    }

    private fun isStoredPayload(entry: Entry, payload: File): Boolean {
        val path = canonical(payload)
        return path == canonicalWithoutExistence(File(entry.storedPath)) &&
            isQuarantinePath(path) && path.contains("/${entry.id}-")
    }

    private fun isQuarantinePath(path: String): Boolean {
        val normalized = path.trimEnd('/')
        val privateRoot = canonicalWithoutExistence(rootDir).trimEnd('/')
        if (normalized == privateRoot || normalized.startsWith("$privateRoot/")) return true
        return Regex("^/(?:storage/emulated/[0-9]+|data/media/[0-9]+|sdcard)/\\.baize-quarantine(?:/.*)?$").matches(normalized)
    }

    private fun safeOriginalPath(path: String): Boolean {
        if (!path.startsWith('/') || path.length !in 2..4096) return false
        if (path.contains('\u0000') || path.contains('\n') || path.contains('\r')) return false
        if (path.split('/').any { it == "." || it == ".." }) return false
        if (isQuarantinePath(path)) return false
        if (path in setOf("/data", "/data/user", "/data/user_de", "/data/media", "/storage", "/sdcard")) return false
        val blocked = listOf("/data/adb", "/proc", "/sys", "/dev", "/metadata", "/system", "/vendor", "/product", "/odm", "/apex")
        return blocked.none { path == it || path.startsWith("$it/") }
    }

    private fun pruneEmptyParents(start: File?) {
        var current = start
        repeat(4) {
            val file = current ?: return
            if (!isQuarantinePath(canonicalWithoutExistence(file)) || file.list()?.isNotEmpty() == true) return
            file.delete()
            current = file.parentFile
        }
    }

    private fun safeName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(80)
        .ifBlank { "payload" }

    private fun mountPoints(): Set<String> = runCatching {
        File("/proc/self/mountinfo").useLines { lines ->
            lines.mapNotNull { it.substringBefore(" - ").split(' ').getOrNull(4) }
                .map { it.replace("\\040", " ") }
                .toSet()
        }
    }.getOrDefault(emptySet())

    private fun canonical(file: File): String = runCatching { file.canonicalFile.path }
        .getOrDefault(file.absoluteFile.normalize().path)

    private fun canonicalWithoutExistence(file: File): String = runCatching {
        val parent = file.parentFile?.canonicalFile
        if (parent == null) file.absoluteFile.normalize().path else File(parent, file.name).path
    }.getOrDefault(file.absoluteFile.normalize().path)

    private fun isSymlink(file: File): Boolean = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    private fun errorJson(code: String, message: String): String = JSONObject()
        .put("success", false)
        .put("error", code)
        .put("message", message)
        .toString()

    companion object {
        private const val DEFAULT_RETENTION_DAYS = 7
        private const val DAY_MS = 24L * 60L * 60L * 1_000L
        private const val MEASURE_BUDGET_MS = 30_000L
        private const val MAX_NODES = 200_000
        private const val MAX_TEXT = 512
        private const val MAX_PAGE_SIZE = 200
        private const val DEFAULT_MAX_BYTES = 8L * 1024L * 1024L * 1024L
        private const val MIN_MAX_BYTES = 256L * 1024L * 1024L
        private const val ABSOLUTE_MAX_BYTES = 64L * 1024L * 1024L * 1024L
        private val ID_PATTERN = Regex("^[0-9a-fA-F-]{36}$")
    }
}
