package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Read-only aggregation of scheduler transitions and exact worker result files. */
internal object SchedulerRunLedger {
    fun read(stateDir: File): JSONArray {
        val rows = mutableListOf<JSONObject>()
        File(stateDir, "auto-run-ledger").listFiles().orEmpty().filter { it.extension == "env" }
            .sortedByDescending { it.lastModified() }.take(60).forEach { file ->
                if (file.length() > 16384 || !file.isFile) return@forEach
                val env = RootFileStore.readEnv(file)
                rows += JSONObject().put("epoch", env.optLong("epoch")).put("state", env.optString("state"))
                    .put("group", label(env.optString("group"))).put("reason", env.optString("reason").take(400))
                    .put("nextCheckEpoch", env.optLong("next_check_epoch"))
            }
        File(stateDir, "task-results").listFiles().orEmpty().filter { it.extension == "env" }
            .sortedByDescending { it.lastModified() }.take(60).forEach { file ->
                if (file.length() > 16384 || !file.isFile) return@forEach
                val env = RootFileStore.readEnv(file)
                if (!env.optString("trigger").startsWith("scheduler:")) return@forEach
                val code = env.optInt("exit_code", -1)
                rows += JSONObject().put("epoch", env.optLong("ended")).put("exitCode", code)
                    .put("group", label(env.optString("mode").substringBefore('-'))).put("reason", when (code) {
                        0 -> "执行结束；逐文件结果以清理记录为准"
                        3 -> "任务锁占用，等待重试"
                        9 -> "任务已停止，已完成结果保留"
                        4, 5, 6, 7, 8, 127 -> "引擎或服务不可用，等待恢复重试"
                        else -> "执行未成功，等待恢复；未确认结果不能计入释放空间"
                    })
            }
        return JSONArray(rows.sortedByDescending { it.optLong("epoch") }.take(30))
    }
    private fun label(group: String): String = group.split('+').joinToString("＋") { when (it) {
        "apk" -> "安装包"; "cache" -> "缓存"; "empty" -> "空文件"; "rules" -> "规则清理"
        "fragment" -> "残留"; "deep" -> "深度清理"; "organize" -> "文件归类"; "" -> "调度检查"; else -> it.take(40)
    } }
}
