package io.github.xgl34222220.baize

import java.io.File

/** 撤销一批“移入回收站”的结果：恢复成功的文件与未能恢复的数量。 */
internal data class TrashUndoResult(val restored: List<File>, val failed: Int) {
    val restoredCount: Int get() = restored.size
}

/**
 * 删除后 Snackbar「撤销」的唯一恢复入口：只恢复本次刚移入回收站的那一批记录，
 * 逐项走 [OrdinaryFileTrash.restore] 的全部核对（记录未被改写、内容摘要一致、原目录未变化、
 * 不覆盖同名新文件）。任何一项失败都留在回收站，不影响其他项，也不会被描述为已恢复。
 */
internal object TrashUndo {
    const val ACTION_LABEL = "撤销"

    fun restoreBatch(trash: OrdinaryFileTrash, ids: Collection<String>,
        prepare: (TrashEntry) -> Unit = {}): TrashUndoResult {
        val wanted = ids.filter { it.isNotBlank() }.toSet()
        if (wanted.isEmpty()) return TrashUndoResult(emptyList(), 0)
        val entries = runCatching { trash.entries().filter { it.id in wanted } }.getOrDefault(emptyList())
        val restored = ArrayList<File>(entries.size)
        var failed = 0
        for (entry in entries) {
            runCatching { prepare(entry); trash.restore(entry.id, expected = entry) }
                .onSuccess { restored += it }
                .onFailure { failed++ }
        }
        // 记录已不存在（例如已在回收站页恢复或永久删除）的编号计为未恢复。
        return TrashUndoResult(restored.toList(), failed + (wanted.size - entries.size))
    }

    /** Snackbar 文案：说明数量与“尚未释放空间”，与页面状态口径一致。 */
    fun message(count: Int, sizeText: String = ""): String =
        "已移入回收站 $count 个文件" + (if (sizeText.isNotBlank()) "（$sizeText）" else "") + "，尚未释放空间"

    fun resultMessage(result: TrashUndoResult): String =
        "已恢复 ${result.restoredCount} 个文件" + if (result.failed > 0) " · ${result.failed} 个未恢复，可在回收站查看原因" else ""
}
