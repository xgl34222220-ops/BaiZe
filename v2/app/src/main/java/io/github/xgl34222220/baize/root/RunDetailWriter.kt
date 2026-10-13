package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * App 端任务（快照清理、工作台清理…）写入记录详情：与模块 run-details.sh 同一格式、同一上限，
 * 返回 history.tsv 第 13 列的 ~v2 标记。只在任务结束时写一次，不在开机时运行。
 */
internal class RunDetailWriter(private val stateDir: File) {
    fun writeNative(mode: String, input: JSONObject, errors: Long): String {
        val rows = ArrayList<List<String>>()
        val items = input.optJSONArray("detailItems") ?: JSONArray()
        for (index in 0 until minOf(items.length(), MAX_ITEMS)) {
            val item = items.optJSONObject(index) ?: continue
            val kind = item.optString("kind").takeIf { it == "failed" || it == "skipped" } ?: continue
            val path = clean(item.optString("path"), 4096)
            rows += listOf(kind, clean(item.optString("reason", if (kind == "failed") "delete_failed" else "safety"), 40),
                clean(item.optString("category"), 120), item.optLong("count", 1L).coerceAtLeast(1L).toString(),
                item.optLong("bytes", 0L).coerceAtLeast(0L).toString(), path, clean(item.optString("note"), 300), "")
        }
        val listedSkipped = rows.filter { it[0] == "skipped" }.sumOf { it[3].toLong() }
        val listedFailed = rows.filter { it[0] == "failed" }.sumOf { it[3].toLong() }
        val protectedCount = input.optLong("protectedCandidates", 0L).coerceAtLeast(0L)
        val changedCount = (input.optLong("skippedCandidates", 0L) - protectedCount).coerceAtLeast(0L)
        val skippedTotal = maxOf(listedSkipped, protectedCount + changedCount)
        val tokens = StringBuilder("~v2")
        // Failed groups and skip reasons summarised from the listed rows.
        rows.filter { it[0] == "failed" }.groupBy { it[2] to it[6] }.entries.take(8).forEach { (key, group) ->
            tokens.append(';').append(token(key.first.ifBlank { "未分类" })).append('|').append(group.sumOf { it[3].toLong() })
                .append('|').append(token(key.second.ifBlank { "系统拒绝删除" })).append('|').append(token(group.first()[5]))
        }
        val skipCounts = linkedMapOf<String, Pair<Long, String>>()
        rows.filter { it[0] == "skipped" }.forEach { row ->
            val previous = skipCounts[row[1]]
            skipCounts[row[1]] = ((previous?.first ?: 0L) + row[3].toLong()) to (previous?.second ?: row[5])
        }
        if (listedSkipped == 0L) {
            if (protectedCount > 0) skipCounts["safety"] = protectedCount to ""
            if (changedCount > 0) skipCounts["changed"] = changedCount to ""
        }
        skipCounts.entries.take(12).forEach { (code, value) ->
            tokens.append(";~skip|").append(token(code)).append('|').append(value.first).append('|').append(token(value.second))
        }
        if (rows.isNotEmpty()) {
            val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-" +
                mode.replace(Regex("[^A-Za-z0-9._-]"), "_").take(32) + "-" + UUID.randomUUID().toString().take(8)
            val dir = File(stateDir, "run-details")
            val written = runCatching {
                dir.mkdirs()
                val ordered = rows.sortedBy { if (it[0] == "failed") 0 else 1 }
                val text = buildString {
                    append("#baize-run-details\tv1\t").append(id).append('\t').append(System.currentTimeMillis() / 1000L).append('\n')
                    append("#counts\tfailed=").append(maxOf(errors, listedFailed)).append("\tskipped=").append(skippedTotal)
                        .append("\tlisted=").append(ordered.size).append('\n')
                    ordered.forEach { append(it.joinToString("\t")).append('\n') }
                }
                RootFileStore.writeAtomic(File(dir, "$id.tsv"), text)
                prune(dir)
            }.isSuccess
            if (written) tokens.append(";~detail|").append(id)
        }
        return tokens.toString().replace('\t', ' ').replace('\n', ' ').take(4000)
    }

    private fun prune(dir: File) {
        dir.listFiles { file -> file.isFile && file.name.endsWith(".tsv") && !file.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
    }

    private fun clean(value: String, max: Int) = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').take(max)
    private fun token(value: String) = value.replace('|', ' ').replace(';', ' ').replace('\t', ' ').take(300)

    companion object {
        const val MAX_ITEMS = 200
        const val KEEP = 30
    }
}
