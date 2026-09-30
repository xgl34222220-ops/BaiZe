package io.github.xgl34222220.baize

import java.io.File
import java.util.zip.ZipFile

internal enum class ApkArchiveParseStatus { PENDING, PARSED, PARTIAL, FAILED, UNSUPPORTED }

/** Stable UI text only: never expose provider errors or private paths. */
internal enum class ApkArchiveFailure(val label: String) {
    UNSUPPORTED_FORMAT("此格式暂不支持图标预览"),
    INVALID_PATH("文件路径不可读取"),
    INACCESSIBLE("无法读取安装包"),
    FILE_CHANGED("文件已变化，请重新扫描"),
    TOO_LARGE("安装包较大，已跳过预览"),
    INVALID_ARCHIVE("安装包损坏或无法识别"),
    RESOURCE_LIMIT("图标资源超出预览限制"),
    TIME_BUDGET("读取超时，已跳过预览"),
    ICON_UNAVAILABLE("安装包未提供可读取的图标"),
    LABEL_UNAVAILABLE("安装包未提供可读取的名称")
}

internal class ApkArchiveReadException(val reason: ApkArchiveFailure) : Exception()

/** Bounds the metadata we inspect; APK contents are never extracted or executed. */
internal object ApkArchivePolicy {
    const val MAX_APK_BYTES = 256L * 1024 * 1024
    const val MAX_ICON_PX = 192
    const val TIME_BUDGET_MS = 3_000L
    const val MAX_RESOURCE_BYTES = 2L * 1024 * 1024
    const val MAX_RASTER_PIXELS = 4L * 1024 * 1024
    private const val MAX_TABLE_BYTES = 32L * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 50_000

    fun inputFailure(path: String, bytes: Long): ApkArchiveFailure? = when {
        !path.endsWith(".apk", ignoreCase = true) -> ApkArchiveFailure.UNSUPPORTED_FORMAT
        !path.startsWith('/') || '\u0000' in path || path.split('/').any { it == ".." || it == "." } -> ApkArchiveFailure.INVALID_PATH
        bytes > MAX_APK_BYTES -> ApkArchiveFailure.TOO_LARGE
        bytes <= 0 -> ApkArchiveFailure.FILE_CHANGED
        else -> null
    }

    fun snapshotMatches(actualBytes: Long, actualModified: Long, expectedBytes: Long, expectedModified: Long): Boolean =
        actualBytes == expectedBytes && actualBytes > 0 &&
            (expectedModified <= 0 || actualModified == expectedModified)

    fun checkZip(file: File, checkpoint: () -> Unit = {}) {
        ZipFile(file).use { zip ->
            if (zip.size() > MAX_ZIP_ENTRIES) throw ApkArchiveReadException(ApkArchiveFailure.RESOURCE_LIMIT)
            val names = HashSet<String>()
            val entries = zip.entries()
            var manifest = false
            while (entries.hasMoreElements()) {
                checkpoint()
                val entry = entries.nextElement()
                if (!names.add(entry.name)) throw ApkArchiveReadException(ApkArchiveFailure.INVALID_ARCHIVE)
                val limit = when (entry.name) {
                    "AndroidManifest.xml" -> MAX_RESOURCE_BYTES
                    "resources.arsc" -> MAX_TABLE_BYTES
                    else -> continue
                }
                if (entry.isDirectory || entry.size <= 0 || entry.size > limit) {
                    throw ApkArchiveReadException(ApkArchiveFailure.RESOURCE_LIMIT)
                }
                if (entry.name == "AndroidManifest.xml") manifest = true
            }
            if (!manifest) throw ApkArchiveReadException(ApkArchiveFailure.INVALID_ARCHIVE)
        }
    }

    fun checkResource(zip: ZipFile, path: String): Long {
        val entry = zip.getEntry(path) ?: throw ApkArchiveReadException(ApkArchiveFailure.ICON_UNAVAILABLE)
        if (entry.isDirectory || entry.size <= 0 || entry.size > MAX_RESOURCE_BYTES) {
            throw ApkArchiveReadException(ApkArchiveFailure.RESOURCE_LIMIT)
        }
        return entry.size
    }
}
