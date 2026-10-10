package io.github.xgl34222220.baize

import android.content.Context
import android.media.MediaScannerConnection
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

internal enum class TrashPayloadState(val label: String) {
    READABLE("路径可读，恢复前仍需核对内容"), MISSING("回收内容未找到，恢复记录仍保留"),
    PATH_CHANGED("回收路径已变化或含链接，操作已阻止"), UNAVAILABLE("存储卷或回收目录暂不可用，恢复记录仍保留")
}
/** 列表行模型：只读字段，供 Compose 跳过未变化的行。 */
@androidx.compose.runtime.Immutable
internal data class TrashEntry(val id: String, val original: String, val stored: String, val bytes: Long,
    val hash: String, val created: Long, val expires: Long, val payloadState: TrashPayloadState = TrashPayloadState.READABLE) {
    fun json() = JSONObject().put("id", id).put("original", original).put("stored", stored).put("bytes", bytes)
        .put("hash", hash).put("created", created).put("expires", expires)
}

/** Ordinary-file trash. Metadata precedes a same-volume atomic move; no copy/delete fallback. */
internal class OrdinaryFileTrash(private val metadata: File, private val roots: List<File>,
    private val now: () -> Long = System::currentTimeMillis,
    private val sameFileSystem: (File, File) -> Boolean = { a, b -> Files.getFileStore(a.toPath()) == Files.getFileStore(b.toPath()) },
    private val atomicMove: (File, File) -> Unit = { from, to -> Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE); Unit },
    private val syncDirectory: (File) -> Unit = { directory ->
        java.nio.channels.FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }) {
    fun entries(): List<TrashEntry> = metadata.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
        runCatching { val j = JSONObject(file.readText()); TrashEntry(j.getString("id"), j.getString("original"),
            j.getString("stored"), j.getLong("bytes"), j.getString("hash"), j.getLong("created"), j.getLong("expires"))
            .takeIf { it.id.matches(Regex("[a-f0-9-]{36}")) && file.name == "${it.id}.json" &&
                it.bytes > 0 && it.hash.matches(Regex("[a-f0-9]{64}")) && knownPayloadLocation(it) }
                ?.let { it.copy(payloadState = payloadState(File(it.stored))) }
        }.getOrNull()
    }.sortedByDescending { it.created }

    fun occupiedBytes(): Long = entries().sumOf { entry ->
        if (entry.payloadState == TrashPayloadState.READABLE) maxOf(entry.bytes, File(entry.stored).length()) else entry.bytes
    }
    private fun knownPayloadLocation(entry: TrashEntry): Boolean {
        val payload = File(entry.stored)
        if (payload.name != entry.id) return false
        if (roots.any { File(it, entry.id).absolutePath == entry.stored }) return true
        // Offline removable volumes can disappear from Context.getExternalFilesDirs().
        // Keep their private, previously created journal without following any payload path.
        val volume = storageVolume(entry.original) ?: return false
        val packagePath = "Android/data/${BuildConfig.APPLICATION_ID}/files"
        return listOf("$DIRECTORY/${BuildConfig.APPLICATION_ID}", "$packagePath/$DIRECTORY", "$packagePath/recoverable-trash")
            .any { entry.stored == "$volume/$it/${entry.id}" }
    }
    private fun payloadState(file: File): TrashPayloadState = runCatching {
        if (roots.none { it.absolutePath == file.parentFile?.absolutePath }) return@runCatching TrashPayloadState.UNAVAILABLE
        if (!safePayload(file)) return@runCatching TrashPayloadState.PATH_CHANGED
        if (file.parentFile?.isDirectory != true) return@runCatching TrashPayloadState.UNAVAILABLE
        if (Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return@runCatching TrashPayloadState.MISSING
        if (file.isFile && file.canRead()) TrashPayloadState.READABLE else TrashPayloadState.UNAVAILABLE
    }.getOrDefault(TrashPayloadState.UNAVAILABLE)

    fun move(source: File, expectedBytes: Long, expectedHash: String, budget: Long,
        validate: () -> Boolean): TrashEntry = synchronized(LOCK) {
        require(expectedBytes > 0 && expectedHash.matches(Regex("[0-9a-f]{64}"))) { "文件内容证明无效" }
        check(!isPayloadPath(source.path) && source.isFile && !Files.isSymbolicLink(source.toPath()) && source.canonicalPath == source.absolutePath) { "文件路径已变化" }
        check(occupiedBytes() <= budget - expectedBytes) { "回收站容量不足，请先恢复或手动清空；原文件保留" }
        val targetRoot = roots.firstOrNull { root ->
            runCatching {
                root.canonicalPath == root.absolutePath && (root.isDirectory || root.mkdirs()) &&
                    !Files.isSymbolicLink(root.toPath()) && sameFileSystem(root, source)
            }.getOrDefault(false)
        } ?: error("此存储卷无法安全移动到回收站，原文件保留")
        val marker = File(targetRoot, ".nomedia")
        check(!Files.isSymbolicLink(marker.toPath())) { "回收目录标记异常，原文件保留" }
        if (!marker.exists()) java.io.FileOutputStream(marker).use { it.fd.sync() }
        val id = UUID.randomUUID().toString()
        val payload = File(targetRoot, id)
        val entry = TrashEntry(id, source.path, payload.path, expectedBytes, expectedHash, now(), now() + 30L * 86_400_000)
        check(!payload.exists() && validate()) { "文件或保护规则已变化" }
        metadata.mkdirs()
        val journal = File(metadata, "$id.json")
        FileOutputStream(journal).use { it.write(entry.json().toString().toByteArray()); it.fd.sync() }
        try {
            syncDirectory(metadata.parentFile!!)
            syncDirectory(metadata)
            targetRoot.parentFile?.parentFile?.let(syncDirectory)
            syncDirectory(targetRoot.parentFile!!)
            syncDirectory(targetRoot) // Unsupported volume fails before touching the original.
            check(validate()) { "文件或保护规则已变化" }
            atomicMove(source, payload)
            check(!Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS) && payload.isFile && payload.length() == expectedBytes && digest(payload) == expectedHash) { "移动结果未确认，恢复记录已保留" }
            java.io.FileInputStream(payload).use { it.fd.sync() }
            syncDirectory(targetRoot)
            syncDirectory(source.parentFile!!)
        } catch (error: Exception) {
            // An interrupted successful move must retain its recovery record.
            if (Files.notExists(payload.toPath(), LinkOption.NOFOLLOW_LINKS)) journal.delete()
            throw error
        }
        entry
    }

    /** Exclusive creation prevents overwriting a newly created original. Trash stays until full verification. */
    fun restore(id: String, allowChanged: Boolean = false, expected: TrashEntry? = null): File = synchronized(LOCK) {
        val entry = entries().firstOrNull { it.id == id } ?: error("恢复记录不存在")
        checkReviewedEntry(entry, expected)
        val payload = checkedPayload(entry, allowChanged)
        val restoreBytes = payload.length()
        val restoreHash = digest(payload)
        val original = File(entry.original)
        val parent = original.parentFile ?: error("原目录不可用")
        check(parent.isDirectory && parent.canonicalPath == parent.absolutePath && !Files.isSymbolicLink(parent.toPath())) { "原目录不存在或已变化，请恢复目录后重试" }
        val destination = if (!Files.exists(original.toPath(), LinkOption.NOFOLLOW_LINKS)) original
            else generateSequence { File(parent, "${original.name}.baize-restored-${UUID.randomUUID().toString().take(8)}") }
                .first { !Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
        java.nio.channels.FileChannel.open(destination.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val output = java.nio.channels.Channels.newOutputStream(channel)
            payload.inputStream().use { input -> input.copyTo(output) }
            channel.force(true)
        }
        check(destination.length() == restoreBytes && digest(destination) == restoreHash && payload.length() == restoreBytes && digest(payload) == restoreHash) { "恢复副本核对失败，回收站内容仍保留" }
        syncDirectory(parent) // Destination name and contents must be durable before deleting the backup.
        check(payload.delete()) { "副本已恢复，但回收站内容未移除，请核对后重试" }
        syncDirectory(payload.parentFile!!)
        File(metadata, "$id.json").delete()
        syncDirectory(metadata)
        destination
    }

    fun purge(id: String, expected: TrashEntry? = null): Long = synchronized(LOCK) {
        val entry = entries().firstOrNull { it.id == id } ?: error("回收站记录不存在")
        checkReviewedEntry(entry, expected)
        val payload = checkedPayload(entry)
        check(payload.delete() && !Files.exists(payload.toPath(), LinkOption.NOFOLLOW_LINKS)) { "永久删除未确认" }
        syncDirectory(payload.parentFile!!)
        File(metadata, "$id.json").delete()
        syncDirectory(metadata)
        entry.bytes
    }
    /** Removes only a journal whose payload is conclusively absent on an available volume. */
    fun forgetMissing(id: String, expected: TrashEntry? = null) = synchronized(LOCK) {
        val entry = entries().firstOrNull { it.id == id } ?: error("记录不存在")
        checkReviewedEntry(entry, expected)
        val file = File(entry.stored)
        check(safePayload(file) && file.parentFile?.isDirectory == true && Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "内容仍存在或存储卷不可用，未清理记录" }
        check(File(metadata, "$id.json").delete()) { "无法清理记录" }
        syncDirectory(metadata)
    }
    /** A reviewed batch cannot act on a rewritten journal, even if its ID was reused. */
    private fun checkReviewedEntry(current: TrashEntry, expected: TrashEntry?) {
        check(expected == null || current.copy(payloadState = expected.payloadState) == expected) {
            "回收记录在确认后已变化，已保留，请刷新后重新选择"
        }
    }
    private fun checkedPayload(entry: TrashEntry, allowChanged: Boolean = false): File {
        val file = File(entry.stored)
        check(safePayload(file) && file.isFile && (allowChanged || (file.length() == entry.bytes && digest(file) == entry.hash))) { "回收站内容已变化或不可读取；可选择恢复当前内容副本，或在确认内容不存在时清理记录" }
        return file
    }
    private fun safePayload(file: File) = roots.any { root ->
        file.parentFile?.absolutePath == root.absolutePath && file.canonicalPath == file.absolutePath &&
            root.canonicalPath == root.absolutePath && !Files.isSymbolicLink(file.toPath())
    }
    companion object {
        private val LOCK = Any()
        const val DEFAULT_BUDGET = 5L * 1024 * 1024 * 1024
        const val DIRECTORY = ".baize-file-trash"
        fun isPayloadPath(path: String): Boolean = path.split('/').any { it.equals(DIRECTORY, ignoreCase = true) } ||
            path.contains("/Android/data/${BuildConfig.APPLICATION_ID}/files/recoverable-trash/", ignoreCase = true) ||
            path.endsWith("/Android/data/${BuildConfig.APPLICATION_ID}/files/recoverable-trash", ignoreCase = true)
        fun forContext(context: Context): OrdinaryFileTrash {
            val appDirectories = context.getExternalFilesDirs(null).filterNotNull().map { it.canonicalFile }
            // Android/data can be a separate passthrough mount even on the same storage volume.
            // Keep payloads at the volume's shared mount, independently of the original parent.
            val publicRoots = appDirectories.map { File(it.path.substringBefore("/Android/data/"), "$DIRECTORY/${context.packageName}") }
            val privateRoots = appDirectories.map { File(it, DIRECTORY) }
            val legacyRoots = appDirectories.map { File(it, "recoverable-trash") }
            return OrdinaryFileTrash(File(context.filesDir, "ordinary-trash"), (publicRoots + privateRoots + legacyRoots).distinct(),
                sameFileSystem = { a, b ->
                    val privateSource = b.path.contains("/Android/data/", true) || b.path.contains("/Android/obb/", true)
                    val allowedRoots = if (privateSource) privateRoots else publicRoots
                    val volume = if (a in publicRoots) a.path.substringBefore("/$DIRECTORY/") else a.path.substringBefore("/Android/data/")
                    a in allowedRoots && b.path.startsWith("$volume/") &&
                        android.system.Os.stat(a.path).st_dev == android.system.Os.stat(b.path).st_dev
                },
                syncDirectory = { directory ->
                    val fd = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY or
                        android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_NONBLOCK or IndexedContentReview.closeOnExecFlag(), 0)
                    try { check(android.system.OsConstants.S_ISDIR(android.system.Os.fstat(fd).st_mode)) { "同步目标不是目录" }; android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
                })
        }
        fun budget(context: Context) = context.getSharedPreferences("ordinary-trash", Context.MODE_PRIVATE)
            .getLong("budget", DEFAULT_BUDGET)
        fun digest(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        fun moveReviewed(context: Context, record: StorageFileRecord, proof: IndexedContentProof?,
            protection: () -> ApkProtectionState, cancelled: () -> Boolean): StorageDeleteOutcome {
            val guard = ApkDeletionGuard.forContext(context)
            if (cancelled()) return StorageDeleteOutcome(ApkIndexedDeleteResult.CANCELLED)
            val identity = record.identity ?: return StorageDeleteOutcome(ApkIndexedDeleteResult.UNVERIFIED)
            return try {
                guard.validate(record.uri, record.path, record.bytes, record.modifiedSeconds, identity, protection())?.let { return StorageDeleteOutcome(it) }
                if (!IndexedContentReview.matches(proof, identity, guard, cancelled)) return StorageDeleteOutcome(ApkIndexedDeleteResult.CHANGED)
                val entry = forContext(context).move(File(identity.canonicalPath), record.bytes, requireNotNull(proof).sha256, budget(context)) {
                    !cancelled() && guard.validate(record.uri, record.path, record.bytes, record.modifiedSeconds, identity, protection()) == null
                }
                MediaScannerConnection.scanFile(context, arrayOf(record.path), null, null)
                StorageDeleteOutcome(ApkIndexedDeleteResult.DELETED, "已移入回收站，尚未释放空间", trashed = true, trashId = entry.id)
            } catch (error: Exception) {
                StorageDeleteOutcome(if (cancelled()) ApkIndexedDeleteResult.CANCELLED else ApkIndexedDeleteResult.FAILED,
                    error.message ?: "移动失败，未执行永久删除")
            }
        }
    }
}

