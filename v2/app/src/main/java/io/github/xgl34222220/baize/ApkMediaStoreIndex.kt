package io.github.xgl34222220.baize

import android.content.ContentUris
import android.content.Context
import android.net.Uri
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
    val modifiedSeconds: Long
)

internal data class ApkMediaStoreResult(
    val candidates: List<IndexedApkCandidate>,
    val elapsedMs: Long,
    val error: String? = null
)

/**
 * Fast path for installation archives.
 *
 * MediaProvider already maintains a database for shared-storage files. Reusing that index is
 * dramatically cheaper than recursively walking /data/media or /storage on every tap.
 */
internal object ApkMediaStoreIndex {
    private const val APK_MIME = "application/vnd.android.package-archive"
    private val extensions = setOf("apk", "apks", "xapk", "apkm", "aab")

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    @Suppress("DEPRECATION")
    fun query(context: Context): ApkMediaStoreResult {
        val started = SystemClock.elapsedRealtime()
        if (!hasAllFilesAccess()) {
            return ApkMediaStoreResult(
                candidates = emptyList(),
                elapsedMs = SystemClock.elapsedRealtime() - started,
                error = "all_files_access_required"
            )
        }

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
        val error = runCatching {
            context.contentResolver.query(
                uri,
                projection,
                selection,
                args,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dataColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val modifiedColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext() && byPath.size < 10_000) {
                    if (idColumn < 0 || dataColumn < 0) continue
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
                    byPath[path] = IndexedApkCandidate(id, ContentUris.withAppendedId(uri, id).toString(), path, safeName, bytes, modified)
                }
            }
        }.exceptionOrNull()?.let { "${it::class.java.simpleName}: ${it.message.orEmpty()}" }

        return ApkMediaStoreResult(
            candidates = byPath.values.toList(),
            elapsedMs = SystemClock.elapsedRealtime() - started,
            error = error
        )
    @Suppress("DEPRECATION")
    fun deleteIfUnchanged(
        context: Context,
        uriString: String,
        expectedPath: String,
        expectedBytes: Long,
        expectedModifiedSeconds: Long
    ): ApkIndexedDeleteResult {
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
            (expectedModifiedSeconds > 0L && current.third != expectedModifiedSeconds)
        ) return ApkIndexedDeleteResult.CHANGED
        return runCatching {
            if (context.contentResolver.delete(itemUri, null, null) > 0) ApkIndexedDeleteResult.DELETED
            else ApkIndexedDeleteResult.FAILED
        }.getOrDefault(ApkIndexedDeleteResult.FAILED)
    }
    }
}

internal enum class ApkIndexedDeleteResult { DELETED, CHANGED, FAILED }
