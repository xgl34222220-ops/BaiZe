package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject

/**
 * history.tsv column 13 (index 12): items a task could not remove, written by the module as
 * `category|count|reason|samplePath;…`. Empty for runs without real failures; skipped,
 * vanished or protected items never appear here.
 */
internal object HistoryFailureDetails {
    const val COLUMN = 12
    const val SPLIT_LIMIT = COLUMN + 1

    fun toJson(raw: String): JSONArray {
        val result = JSONArray()
        raw.split(';').asSequence().map { it.trim() }.filter { it.isNotBlank() }.take(8).forEach { token ->
            val columns = token.split('|', limit = 4)
            if (columns.size < 3) return@forEach
            val category = columns[0].trim().take(80)
            val count = columns[1].trim().toLongOrNull()?.coerceAtLeast(0L) ?: return@forEach
            val reason = columns[2].trim().take(160)
            if (category.isBlank() && reason.isBlank()) return@forEach
            result.put(JSONObject()
                .put("category", category.ifBlank { "未分类" })
                .put("count", count)
                .put("reason", reason.ifBlank { "未记录原因" })
                .put("samplePath", columns.getOrNull(3).orEmpty().trim().take(300)))
        }
        return result
    }
}
