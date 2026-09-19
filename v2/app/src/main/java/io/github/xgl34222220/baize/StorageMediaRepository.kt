package io.github.xgl34222220.baize

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal data class StorageFileRecord(
    val id: Long, val uri: String, val path: String, val name: String,
    val bytes: Long, val modifiedSeconds: Long, val mime: String, val ownerLabel: String = ""
)
internal data class StorageAnalysisBucket(val key: String, val label: String, val files: Int, val bytes: Long)
internal data class DuplicateFileGroup(val key: String, val bytesEach: Long, val records: List<StorageFileRecord>) {
    val reclaimableBytes: Long get() = bytesEach * (records.size - 1).coerceAtLeast(0)
}
internal data class StorageIndexResult(val records: List<StorageFileRecord>, val elapsedMs: Long, val truncated: Boolean)
internal data class StorageDuplicateResult(val groups: List<DuplicateFileGroup>, val skipped: Int)
internal class StorageScanControl {
    private val stopped = AtomicBoolean(false)
    val signal = CancellationSignal()
    val cancelled: Boolean get() = stopped.get() || Thread.currentThread().isInterrupted
    fun cancel() { stopped.set(true); signal.cancel() }
    fun check() { if (cancelled) throw CancellationException() }
}

internal object StorageMediaRepository {
    private const val MAX_FILES = 120_000
    fun hasAccess(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** Reports limits; a provider failure is an error, never an empty successful scan. */
    @Suppress("DEPRECATION")
    fun scanIndex(context: Context, minimumBytes: Long = 1L, control: StorageScanControl = StorageScanControl(),
                  progress: (StorageScanProgress) -> Unit = {}): StorageIndexResult {
        val started = SystemClock.elapsedRealtime()
        check(hasAccess()) { "需要开启所有文件访问" }
        control.check()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE)
        val result = ArrayList<StorageFileRecord>()
        val seen = HashSet<String>()
        val labels = HashMap<String, String>()
        var truncated = false
        val cursor = context.contentResolver.query(collection, projection, "${MediaStore.MediaColumns.SIZE} >= ?",
            arrayOf(minimumBytes.coerceAtLeast(1L).toString()), "${MediaStore.MediaColumns.SIZE} DESC", control.signal)
            ?: error("系统文件索引暂不可用，请稍后重试")
        cursor.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val pathCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val nameCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modifiedCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val mimeCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (it.moveToNext()) {
                control.check()
                if (result.size >= MAX_FILES) { truncated = true; break }
                val path = it.getString(pathCol)?.trim().orEmpty()
                if (!safeSharedFile(path) || !seen.add(path)) continue
                val bytes = it.getLong(sizeCol).coerceAtLeast(0)
                if (bytes < minimumBytes || bytes == 0L) continue
                val pkg = storageOwnerPackage(path)
                val owner = pkg?.let { packageName -> labels.getOrPut(packageName) {
                    runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString() }
                        .getOrDefault(packageName)
                } }.orEmpty()
                val id = it.getLong(idCol)
                result += StorageFileRecord(id, ContentUris.withAppendedId(collection, id).toString(), path,
                    it.getString(nameCol).orEmpty().ifBlank { path.substringAfterLast('/') }, bytes,
                    it.getLong(modifiedCol).coerceAtLeast(0), it.getString(mimeCol).orEmpty(), owner)
                if (result.size % 128 == 0) progress(StorageScanProgress("读取文件索引", result.size, name = result.last().name))
            }
        }
        control.check()
        return StorageIndexResult(result, (SystemClock.elapsedRealtime() - started).coerceAtLeast(0), truncated)
    }

    fun queryFiles(context: Context, minimumBytes: Long = 1L): Pair<List<StorageFileRecord>, Long> =
        scanIndex(context, minimumBytes).let { it.records to it.elapsedMs }
    fun largeFiles(context: Context, minimumBytes: Long) = queryFiles(context, minimumBytes)
    fun analyze(context: Context) = scanIndex(context).let { storageBuckets(it.records) to it.elapsedMs }
    fun duplicates(context: Context): Pair<List<DuplicateFileGroup>, Long> {
        val start = SystemClock.elapsedRealtime()
        val files = scanIndex(context).records
        return findDuplicates(context, files).groups to (SystemClock.elapsedRealtime() - start)
    }
    fun findDuplicates(context: Context, files: List<StorageFileRecord>, control: StorageScanControl = StorageScanControl(),
                       progress: (StorageScanProgress) -> Unit = {}): StorageDuplicateResult {
        var skipped = 0
        val groups = StorageDuplicateMatcher.match(files, { open(context, it) }, ::unchanged,
            { control.cancelled }, progress, { skipped++ })
        return StorageDuplicateResult(groups, skipped)
    }

    /** Recheck both the selected copy and an unselected survivor before any duplicate deletion. */
    fun duplicateStillSafe(context: Context, record: StorageFileRecord, group: DuplicateFileGroup,
                           selected: Set<String>, control: StorageScanControl): Boolean {
        val retained = group.records.firstOrNull { it.uri !in selected && unchanged(it) } ?: return false
        return unchanged(record) &&
            StorageDuplicateMatcher.digest(retained, false, { open(context, it) }, { control.cancelled }) == group.key &&
            StorageDuplicateMatcher.digest(record, false, { open(context, it) }, { control.cancelled }) == group.key &&
            unchanged(retained) && unchanged(record)
    }

    @Suppress("DEPRECATION")
    fun delete(context: Context, record: StorageFileRecord): Boolean {
        if (!unchanged(record)) return false
        val uri = Uri.parse(record.uri)
        val current = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                Triple(cursor.getString(0).orEmpty(), cursor.getLong(1), cursor.getLong(2))
            }
        }.getOrNull() ?: return false
        if (current.first != record.path || current.second != record.bytes ||
            (record.modifiedSeconds > 0 && current.third != record.modifiedSeconds)) return false
        if (runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)) return true
        return unchanged(record) && runCatching { File(record.path).delete() }.getOrDefault(false)
    }

    /** Reject links, stale sizes and modified files, including stale index fallback deletion. */
    internal fun unchanged(record: StorageFileRecord): Boolean = runCatching {
        val file = File(record.path)
        safeSharedFile(file.path) && file.isFile && file.canonicalFile == file.absoluteFile &&
            file.length() == record.bytes && (record.modifiedSeconds <= 0 || file.lastModified() / 1000 == record.modifiedSeconds)
    }.getOrDefault(false)

    private fun open(context: Context, record: StorageFileRecord) =
        runCatching { context.contentResolver.openInputStream(Uri.parse(record.uri)) }.getOrNull()
            ?: if (unchanged(record)) FileInputStream(record.path) else null

    internal fun safeSharedFile(path: String): Boolean = path.isNotBlank() && !path.contains('\u0000') &&
        path.split('/').none { it == ".." || it == "." } &&
        (path.startsWith("/storage/") || path.startsWith("/sdcard/") || path.startsWith("/mnt/media_rw/"))
}
