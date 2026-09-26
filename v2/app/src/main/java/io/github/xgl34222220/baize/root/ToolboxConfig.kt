package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Public task IDs follow the reference's function registry; implementation is BaiZe-owned. */
internal object ToolboxCatalog {
    data class Task(val id: String, val title: String, val description: String, val group: String)
    val tasks = listOf(
        Task("scan", "碎片文件清理", "过期临时文件、旋转日志与中断下载", "文件清理"),
        Task("empty", "空文件与空目录", "保留占位文件及受保护目录", "文件清理"),
        Task("regular", "规则垃圾清理", "白泽规则库与自定义清理规则", "文件清理"),
        Task("app", "应用缓存清理", "内部缓存、外部缓存与 WebView 缓存", "应用专项"),
        Task("system", "系统缓存清理", "仅处理系统应用的缓存目录", "应用专项"),
        Task("wechat", "微信专项", "微信缓存和低风险深度规则，保留聊天数据", "应用专项"),
        Task("qq", "QQ / TIM 专项", "QQ、TIM 缓存和低风险深度规则", "应用专项"),
        Task("dy", "短视频专项", "抖音、极速版、快手的缓存与低风险规则", "应用专项"),
        Task("wyy", "网易云音乐专项", "缓存与低风险规则，保留下载音乐", "应用专项"),
        Task("guilei", "文件归类", "按文件类型归类，支持自定义目标与撤销", "文件管理"),
        Task("mounter", "下载转移", "按规则转移，可选目录重定向，支持解除与撤销", "文件管理"),
        Task("logcat", "清空系统日志缓冲", "清除 logcat 环形缓冲区", "系统维护"),
        Task("memory", "释放文件缓存内存", "同步写入后回收可重建的页缓存", "系统维护"),
        Task("process", "后台进程管理", "按阈值结束、冻结或调整所选后台进程", "系统维护"),
        Task("database", "数据库优化", "优化所选应用的空闲 SQLite 数据库，可选 VACUUM", "系统维护"),
        Task("dex2", "应用编译", "调用系统 ART 编译所选应用", "系统维护"),
        Task("dirty", "F2FS 脏段维护", "按阈值触发限时 GC，结束后恢复内核参数", "系统维护")
    )
    val ids = tasks.map { it.id }.toSet()
    fun task(id: String) = tasks.first { it.id == id }
    val appGroups = mapOf(
        "wechat" to setOf("com.tencent.mm"),
        "qq" to setOf("com.tencent.mobileqq", "com.tencent.tim"),
        "dy" to setOf("com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite", "com.smile.gifmaker", "com.kuaishou.nebula"),
        "wyy" to setOf("com.netease.cloudmusic")
    )
}

internal class ToolboxConfig(private val directory: File = File(RootPaths.STATE_DIR, "toolbox")) {
    @Synchronized fun load(): JSONObject = normalize(read(File(directory, "config.json")))
    @Synchronized fun save(raw: String): JSONObject {
        require(raw.length <= 128_000) { "配置过大" }
        val value = normalize(JSONObject(raw))
        RootFileStore.writeAtomic(File(directory, "config.json"), value.toString())
        return value
    }
    @Synchronized fun history(): JSONArray = read(File(directory, "history.json")).optJSONArray("items") ?: JSONArray()
    @Synchronized fun record(result: JSONObject, statistics: Boolean) {
        val items = history()
        val next = JSONArray().put(result)
        for (i in 0 until minOf(items.length(), 99)) next.put(items.getJSONObject(i))
        RootFileStore.writeAtomic(File(directory, "history.json"), JSONObject().put("items", next).toString())
        if (!statistics) return
        val stats = stats()
        val day = Instant.ofEpochMilli(result.optLong("finishedAt")).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val bytes = result.optLong("deletedBytes").coerceAtLeast(0)
        stats.put("todayBytes", (if (stats.optString("day") == day) stats.optLong("todayBytes") else 0L) + bytes)
            .put("day", day).put("totalBytes", stats.optLong("totalBytes") + bytes)
            .put("totalFiles", stats.optLong("totalFiles") + result.optLong("deletedFiles").coerceAtLeast(0))
            .put("runs", stats.optLong("runs") + 1)
        val limit = load().optLong("counterReset", 0)
        if (limit > 0 && stats.optLong("runs") >= limit) stats.put("runs", 0)
        RootFileStore.writeAtomic(File(directory, "stats.json"), stats.toString())
    }
    @Synchronized fun stats(): JSONObject = read(File(directory, "stats.json")).also {
        val day = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate().toString()
        if (it.optString("day") != day) it.put("todayBytes", 0).put("day", day)
    }
    @Synchronized fun resetStats() { RootFileStore.writeAtomic(File(directory, "stats.json"), "{}") }
    @Synchronized fun clearHistory() { RootFileStore.writeAtomic(File(directory, "history.json"), "{}") }
    @Synchronized fun lastRuns(): JSONObject = read(File(directory, "schedule.json"))
    @Synchronized fun markRun(id: String, time: Long) {
        val value = lastRuns().put(id, time)
        RootFileStore.writeAtomic(File(directory, "schedule.json"), value.toString())
    }
    companion object {
        fun read(file: File): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        fun packages(raw: String): Set<String> {
            val values = raw.split(Regex("[\\s,，;；]+")).filter(String::isNotBlank).toSet()
            require(values.size <= 300 && values.all { RootValidation.packageName.matches(it) }) { "包名格式无效，每行一个，最多 300 个" }
            return values
        }
        fun normalize(input: JSONObject): JSONObject {
            val result = JSONObject().put("schema", 1)
            val tasks = JSONObject()
            ToolboxCatalog.tasks.forEach { task ->
                val raw = input.optJSONObject("tasks")?.optJSONObject(task.id) ?: JSONObject()
                val time = raw.optString("time", "02:30")
                require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(time)) { "时间格式应为 HH:mm" }
                tasks.put(task.id, JSONObject().put("enabled", raw.optBoolean("enabled", false))
                    .put("scheduled", raw.optBoolean("scheduled", false)).put("time", time)
                    .put("intervalDays", raw.optInt("intervalDays", 1).coerceIn(1, 30)))
            }
            result.put("tasks", tasks)
            listOf("statistics" to true, "notifications" to true, "chargingOnly" to false,
                "screenOffOnly" to true, "skipFrozen" to true, "processContinuous" to false, "bindRedirect" to false, "vacuum" to false, "forceCompile" to false).forEach { (key, default) ->
                result.put(key, input.optBoolean(key, default))
            }
            for (key in listOf("processPackages", "databasePackages", "compilePackages", "processWhitelist")) {
                result.put(key, packages(input.optString(key)).sorted().joinToString("\n"))
            }
            val processMode = input.optString("processMode", "kill")
            require(processMode in setOf("kill", "freeze", "oom")) { "进程模式应为 kill、freeze 或 oom" }
            result.put("processMode", processMode).put("pressureIntervalSeconds", input.optInt("pressureIntervalSeconds", 60).coerceIn(30, 3600))
            val filter = input.optString("compilerFilter", "speed-profile")
            require(filter in setOf("verify", "speed-profile", "speed", "everything")) { "编译模式无效" }
            result.put("compilerFilter", filter)
                .put("memoryThreshold", input.optInt("memoryThreshold", 80).coerceIn(10, 99))
                .put("dirtyThreshold", input.optInt("dirtyThreshold", 1000).coerceIn(0, 1_000_000))
                .put("gcSeconds", input.optInt("gcSeconds", 15).coerceIn(1, 60))
                .put("fragmentDays", input.optInt("fragmentDays", 7).coerceIn(1, 365))
                .put("counterReset", input.optLong("counterReset", 0).coerceIn(0, 10_000_000))
            for (key in listOf("organizerRules", "downloadRules")) {
                val text = input.optString(key).trim()
                ToolboxFileRules.parse(text)
                result.put(key, text)
            }
            if (result.optBoolean("bindRedirect")) ToolboxRedirect.directoryRules(result.optString("downloadRules"))
            return result
        }
        /** Local civil days, with a six-hour catch-up window; wall-clock rollback cannot double-run. */
        fun due(task: JSONObject, nowMs: Long, lastMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
            if (!task.optBoolean("scheduled") || lastMs > nowMs) return false
            val now = Instant.ofEpochMilli(nowMs).atZone(zone)
            val time = task.optString("time", "02:30").split(':').map { it.toInt() }
            val scheduled = now.toLocalDate().atTime(time[0], time[1]).atZone(zone)
            if (now.isBefore(scheduled) || now.isAfter(scheduled.plusHours(6))) return false
            if (lastMs <= 0) return true
            val lastDate = Instant.ofEpochMilli(lastMs).atZone(zone).toLocalDate()
            return java.time.temporal.ChronoUnit.DAYS.between(lastDate, now.toLocalDate()) >= task.optInt("intervalDays", 1)
        }
    }
}
