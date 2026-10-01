package io.github.xgl34222220.baize

internal data class StorageDeleteOutcome(
    val result: ApkIndexedDeleteResult,
    val detail: String = "",
    val confirmedMissing: Boolean = false
) {
    val deleted: Boolean get() = result == ApkIndexedDeleteResult.DELETED
    val reason: String get() = when {
        deleted -> "已验证删除"
        confirmedMissing -> "文件已不存在 · 旧索引未计入释放空间"
        detail.isNotBlank() -> "已保留 · $detail"
        else -> result.retainedReason()
    }
}

internal fun StorageFileRecord.asIndexedCandidate() = IndexedApkCandidate(id, uri, path, name, bytes, modifiedSeconds, identity)
internal val StorageFileRecord.verifiedBytes: Long get() = if (identity != null && identity.bytes == bytes &&
    identity.modifiedSeconds == modifiedSeconds && modifiedSeconds > 0) bytes else 0L
