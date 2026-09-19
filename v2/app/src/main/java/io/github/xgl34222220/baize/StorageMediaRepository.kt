package io.github.xgl34222220.baize

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal data class StorageFileRecord(
    val id: Long,
    val uri: String,
    val path: String,
    val name: String,
    val bytes: Long,
    val modifiedSeconds: Long,
    val mime: String
)

internal data class StorageAnalysisBucket(
    val key: String,
    val label: String,
    val files: Int,
    val bytes: Long
)

internal data class DuplicateFileGroup(
    val key: String,
    val bytesEach: Long,
    val records: List<StorageFileRecord>
) {
    val reclaimableBytes: Long get() = bytesEach * (records.size - 1).coerceAtLeast(0)
}

internal object StorageMediaRepository {
    private const val MAX_FILES = 120_000
    private const val MAX_DUPLICATE_CANDIDATES = 12_000

    fun hasAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    @Suppress("DEPRECATION")
    fun queryFiles(context: Context, minimumBytes: Long = 1L): Pair<List<StorageFileRecord>, Long> {
        val started = SystemClock.elapsedRealtime()
        if (!hasAccess()) return emptyList<StorageFileRecord>() to 0L
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE
        )
        val result = ArrayList<StorageFileRecord>()
        runCatching {
            context.contentResolver.query(
                collection, projection, "${MediaStore.MediaColumns.SIZE} >= ?",
                arrayOf(minimumBytes.coerceAtLeast(1L).toString()),
                "${MediaStore.MediaColumns.SIZE} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val modifiedCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                while (cursor.moveToNext() && result.size < MAX_FILES) {
                    val path = cursor.getString(pathCol)?.trim().orEmpty()
                    if (!safeSharedFile(path)) continue
                    val id = cursor.getLong(idCol)
                    val name = if (nameCol >= 0) cursor.getString(nameCol)?.trim().orEmpty() else ""
                    val bytes = if (sizeCol >= 0) cursor.getLong(sizeCol).coerceAtLeast(0L) else 0L
                    if (bytes < minimumBytes || bytes <= 0L) continue
                    result += StorageFileRecord(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        path = path,
                        name = name.ifBlank { path.substringAfterLast('/') },
                        bytes = bytes,
                        modifiedSeconds = if (modifiedCol >= 0) cursor.getLong(modifiedCol).coerceAtLeast(0L) else 0L,
                        mime = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty() else ""
                    )
                }
            }
        }
        return result to (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
    }

    fun largeFiles(context: Context, minimumBytes: Long): Pair<List<StorageFileRecord>, Long> =
        queryFiles(context, minimumBytes)

    fun analyze(context: Context): Pair<List<StorageAnalysisBucket>, Long> {
        val (files, elapsed) = queryFiles(context)
        val grouped = LinkedHashMap<String, Pair<Int, Long>>()
        files.forEach { record ->
            val key = storageCategory(record)
            val current = grouped[key] ?: (0 to 0L)
            grouped[key] = (current.first + 1) to (current.second + record.bytes)
        }
        val buckets = grouped.map { (key, stats) ->
            StorageAnalysisBucket(key, storageCategoryLabel(key), stats.first, stats.second)
        }.sortedByDescending { it.bytes }
        return buckets to elapsed
    }

    fun duplicates(context: Context): Pair<List<DuplicateFileGroup>, Long> {
        val started = SystemClock.elapsedRealtime()
        val (files, _) = queryFiles(context)
        val candidates = files.groupBy { it.bytes }
            .asSequence()
            .filter { it.value.size > 1 }
            .flatMap { it.value.asSequence() }
            .take(MAX_DUPLICATE_CANDIDATES)
            .toList()
        if (candidates.isEmpty()) return emptyList<DuplicateFileGroup>() to
            (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)

        val prefixGroups = LinkedHashMap<String, MutableList<StorageFileRecord>>()
        candidates.forEach { record ->
            val prefix = digest(context, record, prefixOnly = true) ?: return@forEach
            prefixGroups.getOrPut("${record.bytes}:$prefix") { ArrayList() }.add(record)
        }
        val groups = ArrayList<DuplicateFileGroup>()
        prefixGroups.values.filter { it.size > 1 }.forEach { prefixed ->
            val full = LinkedHashMap<String, MutableList<StorageFileRecord>>()
            prefixed.forEach { record ->
                val digest = digest(context, record, prefixOnly = false) ?: return@forEach
                full.getOrPut(digest) { ArrayList() }.add(record)
            }
            full.filterValues { it.size > 1 }.forEach { (hash, records) ->
                groups += DuplicateFileGroup(hash, records.first().bytes, records.sortedByDescending { it.modifiedSeconds })
            }
        }
        return groups.sortedByDescending { it.reclaimableBytes } to
            (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
    }

    fun delete(context: Context, record: StorageFileRecord): Boolean {
        val uri = runCatching { Uri.parse(record.uri) }.getOrNull() ?: return false
        val current = runCatching {
            val projection = arrayOf(
                MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                Triple(
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)).orEmpty(),
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)).coerceAtLeast(0L),
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)).coerceAtLeast(0L)
                )
            }
        }.getOrNull() ?: return false
        if (current.first != record.path || current.second != record.bytes ||
            (record.modifiedSeconds > 0L && current.third != record.modifiedSeconds)
        ) return false
        if (runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)) return true
        val file = File(record.path)
        return safeSharedFile(file.path) && file.isFile && runCatching { file.delete() }.getOrDefault(false)
    }

    private fun digest(context: Context, record: StorageFileRecord, prefixOnly: Boolean): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        val uri = Uri.parse(record.uri)
        val stream = context.contentResolver.openInputStream(uri) ?: FileInputStream(record.path)
        stream.use { input ->
            val buffer = ByteArray(64 * 1024)
            var remaining = if (prefixOnly) buffer.size.toLong() else Long.MAX_VALUE
            while (remaining > 0L) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                md.update(buffer, 0, read)
                remaining -= read
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun storageCategory(record: StorageFileRecord): String {
        val mime = record.mime.lowercase()
        val ext = record.name.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("image/") || ext in setOf("jpg","jpeg","png","gif","webp","heic","heif","avif","dng") -> "image"
            mime.startsWith("video/") || ext in setOf("mp4","mkv","mov","avi","webm","m4v","3gp") -> "video"
            mime.startsWith("audio/") || ext in setOf("mp3","flac","wav","m4a","aac","ogg","opus","ape") -> "audio"
            ext in setOf("apk","apks","xapk","apkm","aab") -> "apk"
            ext in setOf("zip","rar","7z","tar","gz","bz2","xz","zst") -> "archive"
            ext in setOf("pdf","doc","docx","xls","xlsx","ppt","pptx","txt","md","csv","rtf","epub") -> "document"
            else -> "other"
        }
    }

    private fun storageCategoryLabel(key: String): String = when (key) {
        "image" -> "图片"
        "video" -> "视频"
        "audio" -> "音频"
        "apk" -> "安装包"
        "archive" -> "压缩包"
        "document" -> "文档"
        else -> "其他文件"
    }

    private fun safeSharedFile(path: String): Boolean {
        if (path.isBlank() || path.contains('\u0000')) return false
        return path.startsWith("/storage/") || path.startsWith("/sdcard/") || path.startsWith("/mnt/media_rw/")
    }
}
