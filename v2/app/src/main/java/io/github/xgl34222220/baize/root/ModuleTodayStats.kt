package io.github.xgl34222220.baize.root

import org.json.JSONObject

/**
 * 模块列表描述里的“今日清理”统计（与 cleaner-compat.sh 的 today/today_* 字段同格式）。
 * 只在清理 / 维护任务结束时累加，跨天自动归零；开机流程不写入。
 */
internal object ModuleTodayStats {
    data class Today(val date: String, val runs: Long, val files: Long, val bytes: Long)

    fun next(previous: JSONObject, date: String, runs: Long, files: Long, bytes: Long): Today {
        val same = previous.optString("today") == date
        fun base(key: String) = if (same) previous.optLong(key, 0L).coerceAtLeast(0L) else 0L
        return Today(
            date = date,
            runs = base("today_runs") + runs.coerceAtLeast(0L),
            files = base("today_files") + files.coerceAtLeast(0L),
            bytes = saturatedAdd(base("today_bytes"), bytes.coerceAtLeast(0L))
        )
    }

    fun summary(today: Today, humanBytes: (Long) -> String): String =
        "今日清理 ${humanBytes(today.bytes)} · ${today.files} 项 · ${today.runs} 次"

    private fun saturatedAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
}
