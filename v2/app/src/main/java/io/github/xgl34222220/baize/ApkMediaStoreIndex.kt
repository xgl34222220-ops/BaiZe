package io.github.xgl34222220.baize

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore

internal data class IndexedApkCandidate(
    val id: Long,
    val uri: String,
    val path: String,
    val name: String,
    val bytes: Long,
    val modifiedSeconds: Long,
    val identity: ApkFileIdentity? = null
)

internal data class ApkMediaStoreResult(
    val candidates: List<IndexedApkCandidate>,
    val elapsedMs: Long,
    val error: String? = null,
    val truncated: Boolean = false,
    val cancelled: Boolean = false,
    val confirmedMissingRecords: Int = 0,
    val missingCheckIncomplete: Boolean = false
)

internal enum class ApkIndexedDeleteResult { DELETED, CHANGED, FAILED, PROTECTED, PROTECTION_UNAVAILABLE, UNVERIFIED, INVALID, CANCELLED }

internal fun ApkIndexedDeleteResult.retainedReason(): String = when (this) {
    ApkIndexedDeleteResult.DELETED -> ""
    ApkIndexedDeleteResult.PROTECTED -> "已保留 · 命中保护名单"
    ApkIndexedDeleteResult.PROTECTION_UNAVAILABLE -> "已保留 · 保护名单尚未核对"
    ApkIndexedDeleteResult.CHANGED -> "已保留 · 文件已变化或不可读取，请重新扫描"
    ApkIndexedDeleteResult.UNVERIFIED -> "已保留 · 文件身份尚未核对，可在详情复制读取诊断"
    ApkIndexedDeleteResult.INVALID -> "已保留 · 文件索引或路径无效"
    ApkIndexedDeleteResult.FAILED -> "已保留 · 系统未确认删除，请检查权限后重试"
    ApkIndexedDeleteResult.CANCELLED -> "已保留 · 已停止清理"
}

internal object ApkMediaStoreIndex {
    private const val APK_MIME = "application/vnd.android.package-archive"
    private val extensions = setOf("apk", "apks", "xapk", "apkm", "aab")

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    @Suppress("DEPRECATION")
    fun query(context: Context, cancellationSignal: CancellationSignal = CancellationSignal(),
        guard: ApkDeletionGuard = ApkDeletionGuard.forContext(context)): ApkMediaStoreResult {
        val started = SystemClock.elapsedRealtime()
        if (!hasAllFilesAccess()) {
            return ApkMediaStoreResult(
                candidates = emptyList(),
                elapsedMs = SystemClock.elapsedRealtime() - started,
                error = "all_files_access_required"
            )
        }

        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE
        )
        val selection = buildString {
            append("(")
            append(MediaStore.MediaColumns.MIME_TYPE).append(" = ?")
            repeat(extensions.size) {
                append(" OR ").append(MediaStore.MediaColumns.DISPLAY_NAME).append(" LIKE ?")
            }
            append(") AND ").append(MediaStore.MediaColumns.SIZE).append(" > 0")
        }
        val args = buildList {
            add(APK_MIME)
            extensions.forEach { add("%.$it") }
        }.toTypedArray()

        val byPath = LinkedHashMap<String, IndexedApkCandidate>()
        var truncated = false
        var cancelled = false
        val error = runCatching {
            cancellationSignal.throwIfCanceled()
            context.contentResolver.query(
                collectionUri,
                projection,
                selection,
                args,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
                cancellationSignal
            ).let { it ?: kotlin.error("系统文件索引暂不可用，请稍后重试") }.use { cursor ->
                val idColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dataColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val modifiedColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                check(idColumn >= 0 && dataColumn >= 0 && sizeColumn >= 0 && modifiedColumn >= 0) { "系统文件索引缺少必要字段" }
                while (cursor.moveToNext()) {
                    cancellationSignal.throwIfCanceled()
                    val id = cursor.getLong(idColumn)
                    val path = cursor.getString(dataColumn)?.trim().orEmpty()
                    val name = if (nameColumn >= 0) cursor.getString(nameColumn)?.trim().orEmpty() else ""
                    val safeName = name.ifBlank { path.substringAfterLast('/') }
                    val extension = safeName.substringAfterLast('.', "").lowercase()
                    if (path.isBlank() || extension !in extensions) continue
                    if (path.startsWith("/data/app/") || path.startsWith("/system/") ||
                        path.startsWith("/vendor/") || path.startsWith("/product/")) continue
                    val bytes = if (sizeColumn >= 0) cursor.getLong(sizeColumn).coerceAtLeast(0L) else 0L
                    val modified = if (modifiedColumn >= 0) cursor.getLong(modifiedColumn).coerceAtLeast(0L) else 0L
                    if (path !in byPath && byPath.size >= 10_000) { truncated = true; break }
                    byPath[path] = IndexedApkCandidate(
                        id = id,
                        uri = ContentUris.withAppendedId(collectionUri, id).toString(),
                        path = path,
                        name = safeName,
                        bytes = bytes,
                        modifiedSeconds = modified,
                        identity = guard.capture(path)
                    )
                }
            }
        }.exceptionOrNull()?.let {
            if (it is OperationCanceledException) { cancelled = true; null }
            else "${it::class.java.simpleName}: ${it.message.orEmpty()}"
        }
        if (cancellationSignal.isCanceled) cancelled = true

        return ApkMediaStoreResult(
            candidates = if (cancelled || error != null) emptyList() else byPath.values.toList(),
            elapsedMs = SystemClock.elapsedRealtime() - started,
            error = error,
            truncated = truncated && !cancelled && error == null,
            cancelled = cancelled
        )
    }

    @Suppress("DEPRECATION")
    fun deleteIfUnchanged(
        context: Context,
        uriString: String,
        expectedPath: String,
        expectedBytes: Long,
        expectedModifiedSeconds: Long,
        expectedIdentity: ApkFileIdentity?,
        protection: () -> ApkProtectionState,
        isCancelled: () -> Boolean = { false },
        guard: ApkDeletionGuard = ApkDeletionGuard.forContext(context)
    ): ApkIndexedDeleteResult {
        if (isCancelled()) return ApkIndexedDeleteResult.CANCELLED
        val currentProtection = try { protection() } catch (_: Exception) {
            return ApkIndexedDeleteResult.PROTECTION_UNAVAILABLE
        }
        guard.validate(uriString, expectedPath, expectedBytes, expectedModifiedSeconds,
            expectedIdentity, currentProtection)?.let { return it }
        val itemUri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return ApkIndexedDeleteResult.FAILED
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val current = runCatching {
            context.contentResolver.query(itemUri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)).orEmpty()
                val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)).coerceAtLeast(0L)
                val modified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)).coerceAtLeast(0L)
                Triple(path, bytes, modified)
            }
        }.getOrNull() ?: return ApkIndexedDeleteResult.CHANGED
        if (current.first != expectedPath || current.second != expectedBytes ||
            current.third != expectedModifiedSeconds
        ) return ApkIndexedDeleteResult.CHANGED

        return runCatching {
            if (isCancelled()) return ApkIndexedDeleteResult.CANCELLED
            // Recheck after the provider query, and constrain the provider mutation to that row.
            guard.validate(uriString, expectedPath, expectedBytes, expectedModifiedSeconds,
                expectedIdentity, currentProtection)?.let { return it }
            val selection = "${MediaStore.MediaColumns.DATA} = ? AND ${MediaStore.MediaColumns.SIZE} = ? AND ${MediaStore.MediaColumns.DATE_MODIFIED} = ?"
            if (context.contentResolver.delete(itemUri, selection,
                    arrayOf(expectedPath, expectedBytes.toString(), expectedModifiedSeconds.toString())) > 0) ApkIndexedDeleteResult.DELETED
            else ApkIndexedDeleteResult.FAILED
        }.getOrDefault(ApkIndexedDeleteResult.FAILED)
    }
}
