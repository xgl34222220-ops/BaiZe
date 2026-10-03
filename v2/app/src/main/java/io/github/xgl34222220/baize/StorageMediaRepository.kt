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
import io.github.xgl34222220.baize.root.IProfileRootService
import org.json.JSONObject

internal data class StorageFileRecord(
    val id: Long, val uri: String, val path: String, val name: String,
    val bytes: Long, val modifiedSeconds: Long, val mime: String, val ownerLabel: String = "",
    val identity: ApkFileIdentity? = null
)
internal data class StorageAnalysisBucket(val key: String, val label: String, val files: Int, val bytes: Long)
internal data class DuplicateFileGroup(val key: String, val bytesEach: Long, val records: List<StorageFileRecord>) {
    val reclaimableBytes: Long get() = bytesEach * (records.count { it.verifiedBytes > 0 } - 1).coerceAtLeast(0)
}
internal data class StorageIndexResult(val records: List<StorageFileRecord>, val elapsedMs: Long, val truncated: Boolean,
    val confirmedMissing: Int = 0, val presenceIncomplete: Boolean = false)
internal data class StorageDuplicateResult(val groups: List<DuplicateFileGroup>, val skipped: Int, val reusedHashes: Int = 0)
internal class StorageScanControl {
    private val stopped = AtomicBoolean(false)
    val signal = CancellationSignal()
    val cancelled: Boolean get() = stopped.get() || Thread.currentThread().isInterrupted
    fun cancel() { stopped.set(true); signal.cancel() }
    fun check() { if (cancelled) throw CancellationException() }
}

internal object StorageMediaRepository {
    private const val MAX_FILES = 120_000
    fun hasAccess(context: Context): Boolean = SharedStorageAccess.granted(context)

    /** Reports limits; a provider failure is an error, never an empty successful scan. */
    @Suppress("DEPRECATION")
    fun scanIndex(context: Context, minimumBytes: Long = 1L, control: StorageScanControl = StorageScanControl(),
                  progress: (StorageScanProgress) -> Unit = {},
                  guard: ApkDeletionGuard = ApkDeletionGuard.forContext(context)): StorageIndexResult {
        val started = SystemClock.elapsedRealtime()
        check(hasAccess(context)) { "需要开启${SharedStorageAccess.label}" }
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
                val path = it.getString(pathCol).orEmpty()
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
                    it.getLong(modifiedCol).coerceAtLeast(0), it.getString(mimeCol).orEmpty(), owner, guard.capture(path))
                if (result.size % 128 == 0) progress(StorageScanProgress("读取文件索引", result.size, name = result.last().name))
            }
        }
        control.check()
        return StorageIndexResult(result, (SystemClock.elapsedRealtime() - started).coerceAtLeast(0), truncated)
    }

    /** An unavailable file stays visible for diagnosis but cannot be selected or counted as reclaimable. */
    fun reviewPresence(context: Context, index: StorageIndexResult, remote: IProfileRootService?, control: StorageScanControl): StorageIndexResult {
        if (remote == null) return index.copy(presenceIncomplete = index.records.any { it.identity == null })
        return reviewPresence(index, control, inspect = {
            JSONObject(ApkFileReadDiagnostics.collect(context, it.uri, it.path, remote, it.identity, control.signal, indexedFile = true))
        }, appUid = android.os.Process.myUid())
    }

    internal fun reviewPresence(index: StorageIndexResult, control: StorageScanControl,
        inspect: (IndexedApkCandidate) -> JSONObject, appUid: Int): StorageIndexResult {
        val result = ApkIndexPresenceReview.review(ApkMediaStoreResult(index.records.map { it.asIndexedCandidate() }, index.elapsedMs,
            truncated = index.truncated), control.signal, inspect = inspect, appUid = appUid)
        control.check()
        val kept = result.candidates.mapTo(HashSet()) { it.uri }
        return index.copy(records = index.records.filter { it.uri in kept }, elapsedMs = result.elapsedMs,
            confirmedMissing = result.confirmedMissingRecords, presenceIncomplete = result.missingCheckIncomplete)
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
                       progress: (StorageScanProgress) -> Unit = {}, cache: StorageDigestCache = StorageDigestCache(),
                       completed: (List<DuplicateFileGroup>) -> Unit = {}): StorageDuplicateResult {
        var skipped = 0
        val guard = ApkDeletionGuard.forContext(context)
        val groups = StorageDuplicateMatcher.match(files, { open(context, it, guard) }, { unchanged(it, guard) },
            { control.cancelled }, progress, { skipped++ }, cache, completed)
        return StorageDuplicateResult(groups, skipped, cache.hits)
    }

    /** Recheck both the selected copy and an unselected survivor before any duplicate deletion. */
    fun duplicateStillSafe(context: Context, record: StorageFileRecord, group: DuplicateFileGroup,
                           selected: Set<String>, control: StorageScanControl,
                           keeperProofs: MutableMap<String, Pair<ApkFileIdentity, String>> = mutableMapOf()): Boolean {
        val guard = ApkDeletionGuard.forContext(context)
        val retained = group.records.firstOrNull { it.uri !in selected && unchanged(it, guard) } ?: return false
        val identity = retained.identity ?: return false
        // Only this operation's fresh proof is shared. Nanosecond-less systems rehash every time.
        val cached = keeperProofs[retained.uri]?.takeIf { it.first == identity && identity.hasPreciseStorageClock() }
        val hash = cached?.second ?: StorageDuplicateMatcher.digest(retained, false, { open(context, it, guard) }, { control.cancelled })
        if (hash != group.key || !unchanged(retained, guard)) return false
        keeperProofs[retained.uri] = identity to hash
        return unchanged(record, guard) &&
            StorageDuplicateMatcher.digest(record, false, { open(context, it, guard) }, { control.cancelled }) == group.key &&
            unchanged(retained, guard) && unchanged(record, guard)
    }

    fun delete(context: Context, record: StorageFileRecord, protection: () -> ApkProtectionState,
        cancelled: () -> Boolean = { false }, guard: ApkDeletionGuard = ApkDeletionGuard.forContext(context),
        contentProof: IndexedContentProof? = null): StorageDeleteOutcome {
        var failure: Throwable? = null
        val result = ApkMediaStoreIndex.deleteIfUnchanged(context, record.uri, record.path, record.bytes,
            record.modifiedSeconds, record.identity, protection, cancelled, guard, onFailure = { failure = it }, contentProof = contentProof)
        return StorageDeleteOutcome(result, when {
            failure is SecurityException -> "系统拒绝访问此文件；请核对文件访问权限"
            failure != null -> "系统文件操作失败：${failure.javaClass.simpleName}"
            result == ApkIndexedDeleteResult.CHANGED || result == ApkIndexedDeleteResult.UNVERIFIED -> when (
                ApkFileReadDiagnostics.stat(record.path).optInt("errno", -1)) {
                    2 -> "当前路径未找到文件；请查看读取诊断核对旧索引"
                    13, 1 -> "系统拒绝读取文件身份；请查看读取诊断"
                    else -> if (record.identity == null) "扫描时未取得文件身份，不能安全删除" else "文件或索引已变化，请重新扫描"
                }
            else -> ""
        })
    }

    /** Reject links, stale sizes and modified files, including stale index fallback deletion. */
    internal fun unchanged(record: StorageFileRecord, guard: ApkDeletionGuard): Boolean = record.identity != null &&
        guard.capture(record.path) == record.identity && record.identity.bytes == record.bytes &&
        record.modifiedSeconds > 0

    private fun open(context: Context, record: StorageFileRecord, guard: ApkDeletionGuard) =
        if (!unchanged(record, guard)) null else
            runCatching { context.contentResolver.openInputStream(Uri.parse(record.uri)) }.getOrNull()
                ?: if (unchanged(record, guard)) runCatching { FileInputStream(record.path) }.getOrNull() else null

    internal fun safeSharedFile(path: String): Boolean = !OrdinaryFileTrash.isPayloadPath(path) && path.isNotBlank() && !path.contains('\u0000') &&
        path.split('/').none { it == ".." || it == "." } &&
        (path.startsWith("/storage/") || path.startsWith("/sdcard/") || path.startsWith("/mnt/media_rw/"))
}
