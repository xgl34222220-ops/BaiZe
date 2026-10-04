package io.github.xgl34222220.baize

import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal enum class StorageSort(val label: String) {
    SIZE("大小"), NEWEST("最新"), OLDEST("最早"), NAME("名称")
}

internal data class StorageScanProgress(
    val phase: String, val completed: Int = 0, val total: Int = 0, val name: String = ""
)

internal fun storageCategory(record: StorageFileRecord): String {
    val mime = record.mime.lowercase()
    val ext = record.name.substringAfterLast('.', "").lowercase()
    return when {
        mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "dng") -> "image"
        mime.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp") -> "video"
        mime.startsWith("audio/") || ext in setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "ape") -> "audio"
        ext in setOf("apk", "apks", "xapk", "apkm", "aab") -> "apk"
        ext in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst") -> "archive"
        ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "rtf", "epub") -> "document"
        else -> "other"
    }
}

internal fun storageCategoryLabel(key: String): String = when (key) {
    "image" -> "图片"; "video" -> "视频"; "audio" -> "音频"; "apk" -> "安装包"
    "archive" -> "压缩包"; "document" -> "文档"; "other" -> "其他文件"; else -> "全部"
}

internal fun storageBuckets(files: List<StorageFileRecord>): List<StorageAnalysisBucket> =
    files.groupBy(::storageCategory).map { (key, records) ->
        StorageAnalysisBucket(key, storageCategoryLabel(key), records.size, records.sumOf { it.verifiedBytes })
    }.sortedByDescending { it.bytes }

/** Only Android's package-owned directory convention establishes an app association. */
internal fun storageOwnerPackage(path: String): String? =
    Regex("/Android/(?:data|media|obb)/([^/]+)/").find(path)?.groupValues?.get(1)
        ?.takeIf { it.contains('.') && it.matches(Regex("[A-Za-z0-9_.]+")) }

internal fun storageSource(record: StorageFileRecord): String = record.ownerLabel.ifBlank {
    storageOwnerPackage(record.path) ?: when {
        record.path.contains("/Download/", true) -> "下载目录"
        record.path.contains("/DCIM/", true) -> "相机目录"
        record.path.contains("/Pictures/", true) -> "图片目录"
        else -> "共享存储"
    }
}

internal fun filterStorageRecords(
    records: List<StorageFileRecord>, query: String, category: String?, minimumBytes: Long, sort: StorageSort
): List<StorageFileRecord> {
    val term = query.trim()
    val filtered = records.filter { record ->
        record.bytes >= minimumBytes && (category == null || storageCategory(record) == category) &&
            (term.isEmpty() || record.name.contains(term, true) || record.path.contains(term, true) || storageSource(record).contains(term, true))
    }
    val comparator = when (sort) {
        StorageSort.SIZE -> compareByDescending<StorageFileRecord> { it.bytes }
        StorageSort.NEWEST -> compareByDescending { it.modifiedSeconds }
        StorageSort.OLDEST -> compareBy { it.modifiedSeconds }
        StorageSort.NAME -> compareBy { it.name.lowercase() }
    }
    return filtered.sortedWith(comparator.thenBy { it.uri })
}

internal fun ApkFileIdentity.hasPreciseStorageClock(): Boolean = modifiedNanos >= 0 && changedNanos >= 0 &&
    (modifiedNanos > 0 || changedNanos > 0)

/** Paths and hard links to one object are not independent copies that can survive cleanup. */
internal fun ApkFileIdentity.sameStorageObject(other: ApkFileIdentity): Boolean =
    canonicalPath == other.canonicalPath || (device == other.device && inode == other.inode)

/** Hashes belong to one scan. A nanosecond field does not prove fresh shared-storage content. */
internal class StorageDigestCache(private val limit: Int = 120_000) {
    private data class Key(val identity: ApkFileIdentity, val prefix: Boolean)
    private val values = object : LinkedHashMap<Key, String>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>?) = size > limit
    }
    var hits: Int = 0; private set
    fun begin(files: List<StorageFileRecord>) {
        hits = 0
        // FUSE/provider metadata can collide or be cached, even with nonzero nanoseconds.
        // Re-read content on every requested scan; metadata alone cannot refresh a SHA proof.
        values.clear()
    }
    fun get(record: StorageFileRecord, prefix: Boolean): String? = record.identity?.takeIf {
        it.hasPreciseStorageClock()
    }?.let { values[Key(it, prefix)]?.also { hits++ } }
    fun put(record: StorageFileRecord, prefix: Boolean, hash: String) {
        val identity = record.identity ?: return
        if (identity.hasPreciseStorageClock()) values[Key(identity, prefix)] = hash
    }
}

internal fun remainingDuplicateGroups(groups: List<DuplicateFileGroup>, records: List<StorageFileRecord>): List<DuplicateFileGroup> {
    val byUri = records.associateBy { it.uri }
    return groups.map { it.copy(records = it.records.mapNotNull { r -> byUri[r.uri] }) }.filter { it.records.size >= 2 }
}

/** Content comparison has no Android dependency, so cancellation and exact matching can be tested. */
internal object StorageDuplicateMatcher {
    fun digest(
        record: StorageFileRecord, prefixOnly: Boolean, open: (StorageFileRecord) -> InputStream?,
        cancelled: () -> Boolean = { false }
    ): String? {
        fun checkCancelled() { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException() }
        checkCancelled()
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val expected = if (prefixOnly) minOf(record.bytes, 64 * 1024L) else record.bytes
            var readBytes = 0L
            var emptyReads = 0
            (open(record) ?: return null).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (readBytes < expected) {
                    checkCancelled()
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), expected - readBytes).toInt())
                    if (read < 0) return null
                    if (read == 0) {
                        checkCancelled()
                        // A broken provider must not keep a scan spinning without evidence.
                        if (++emptyReads >= 3) return null
                        continue
                    }
                    emptyReads = 0
                    md.update(buffer, 0, read)
                    readBytes += read
                }
                checkCancelled()
                // A stale MediaStore size must never turn a prefix into a full-file match.
                if (!prefixOnly && input.read() != -1) return null
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) { null }
    }

    fun match(
        files: List<StorageFileRecord>, open: (StorageFileRecord) -> InputStream?,
        unchanged: (StorageFileRecord) -> Boolean = { true }, cancelled: () -> Boolean = { false },
        progress: (StorageScanProgress) -> Unit = {}, unreadable: () -> Unit = {},
        cache: StorageDigestCache = StorageDigestCache(), completed: (List<DuplicateFileGroup>) -> Unit = {}
    ): List<DuplicateFileGroup> {
        cache.begin(files)
        fun hash(record: StorageFileRecord, prefix: Boolean): String? {
            if (!unchanged(record)) return null
            val hash = cache.get(record, prefix) ?: digest(record, prefix, open, cancelled) ?: return null
            if (!unchanged(record)) return null
            cache.put(record, prefix, hash)
            return hash
        }
        val paths = HashSet<String>()
        val objects = HashSet<Pair<Long, Long>>()
        val candidates = files.filter { record ->
            val identity = record.identity
            val newPath = paths.add(identity?.canonicalPath ?: record.path)
            // Inode numbers only identify an object within their device, not across volumes.
            val newObject = identity?.let { objects.add(it.device to it.inode) } ?: true
            newPath && newObject
        }.groupBy { it.bytes }.values.filter { it.size > 1 }.flatten()
        val prefixes = LinkedHashMap<String, MutableList<StorageFileRecord>>()
        candidates.forEachIndexed { index, record ->
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException()
            val hash = hash(record, true)
            if (hash != null) prefixes.getOrPut("${record.bytes}:$hash") { ArrayList() }.add(record) else unreadable()
            progress(StorageScanProgress("快速比对", index + 1, candidates.size, record.name))
        }
        val fullCandidates = prefixes.values.filter { it.size > 1 }.flatten()
        val matches = LinkedHashMap<String, MutableList<StorageFileRecord>>()
        fullCandidates.forEachIndexed { index, record ->
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException()
            val hash = hash(record, false)
            if (hash != null && unchanged(record)) matches.getOrPut("${record.bytes}:$hash") { ArrayList() }.add(record) else unreadable()
            progress(StorageScanProgress("完整内容校验", index + 1, fullCandidates.size, record.name))
            if (index % 32 == 0 || index == fullCandidates.lastIndex) completed(matches.filterValues { it.size > 1 }.map { (key, records) ->
                DuplicateFileGroup(key.substringAfter(':'), records.first().bytes, records.toList())
            })
        }
        return matches.filterValues { it.size > 1 }.map { (key, records) ->
            DuplicateFileGroup(key.substringAfter(':'), records.first().bytes,
                records.sortedWith(compareByDescending<StorageFileRecord> { it.modifiedSeconds }.thenBy { it.uri }))
        }.sortedByDescending { it.reclaimableBytes }
    }
}
