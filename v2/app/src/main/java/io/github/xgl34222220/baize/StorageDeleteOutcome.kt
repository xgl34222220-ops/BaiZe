package io.github.xgl34222220.baize

internal data class StorageDeleteOutcome(
    val result: ApkIndexedDeleteResult,
    val detail: String = "",
    val confirmedMissing: Boolean = false, val trashed: Boolean = false,
    /** 移入回收站后的记录编号，用于“撤销本次”只恢复这一批。 */
    val trashId: String = ""
) {
    val deleted: Boolean get() = result == ApkIndexedDeleteResult.DELETED && !trashed
    val reason: String get() = when {
        trashed -> "已移入回收站 · 尚未释放空间"
        deleted -> "已验证删除"
        confirmedMissing -> "文件已不存在 · 旧索引未计入释放空间"
        result == ApkIndexedDeleteResult.FAILED -> if (detail.isNotBlank()) "未确认 · $detail" else result.retainedReason()
        detail.isNotBlank() -> "已保留 · $detail"
        else -> result.retainedReason()
    }
}

internal fun StorageFileRecord.asIndexedCandidate() = IndexedApkCandidate(id, uri, path, name, bytes, modifiedSeconds, identity)
internal val StorageFileRecord.verifiedBytes: Long get() = if (identity != null && identity.bytes == bytes && modifiedSeconds > 0) bytes else 0L
