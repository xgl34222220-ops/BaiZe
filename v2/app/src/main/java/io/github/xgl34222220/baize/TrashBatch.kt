package io.github.xgl34222220.baize

import kotlinx.coroutines.CancellationException
import java.util.Collections

/** The exact reviewed records, detached from selection and any later repository refresh. */
internal class TrashBatchSnapshot private constructor(val entries: List<TrashEntry>) {
    val size: Int get() = entries.size
    val bytes: Long = entries.fold(0L) { total, entry ->
        if (entry.bytes > Long.MAX_VALUE - total) Long.MAX_VALUE else total + entry.bytes.coerceAtLeast(0L)
    }

    companion object {
        fun capture(entries: Iterable<TrashEntry>, selectedIds: Set<String>? = null): TrashBatchSnapshot {
            val reviewed = entries.filter { selectedIds == null || it.id in selectedIds }
                .distinctBy { it.id }.map { it.copy() }
            return TrashBatchSnapshot(Collections.unmodifiableList(reviewed))
        }
    }
}

internal enum class TrashBatchAction(val label: String) {
    RESTORE("恢复"), PURGE("永久删除"), RESTORE_CHANGED("恢复当前内容"), FORGET_MISSING("清理无内容记录")
}

internal data class TrashItemSuccess(val detail: String)
internal data class TrashBatchItemResult(val entry: TrashEntry, val succeeded: Boolean, val detail: String)
internal data class TrashBatchProgress(val completed: Int, val total: Int, val current: TrashEntry? = null)
internal data class TrashBatchResult(val reviewed: TrashBatchSnapshot, val items: List<TrashBatchItemResult>) {
    val succeeded: Int get() = items.count { it.succeeded }
    val failed: Int get() = items.count { !it.succeeded }
    val remaining: Int get() = reviewed.size - items.size
}

/** Cancellation stops only unstarted items; completed file operations are never described as rolled back. */
internal suspend fun runTrashBatch(
    reviewed: TrashBatchSnapshot,
    cancelled: () -> Boolean,
    perform: suspend (TrashEntry) -> TrashItemSuccess,
    onProgress: (TrashBatchProgress) -> Unit = {}
): TrashBatchResult {
    val results = mutableListOf<TrashBatchItemResult>()
    for (entry in reviewed.entries) {
        if (cancelled()) break
        onProgress(TrashBatchProgress(results.size, reviewed.size, entry))
        if (cancelled()) break
        val outcome = try {
            TrashBatchItemResult(entry, true, perform(entry).detail)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A failed durability check can happen after a file has moved/deleted. Do not claim rollback.
            TrashBatchItemResult(entry, false, failure.message ?: "未确认完成，请核对文件与记录后重试")
        }
        results += outcome
        onProgress(TrashBatchProgress(results.size, reviewed.size))
    }
    return TrashBatchResult(reviewed, results.toList())
}
