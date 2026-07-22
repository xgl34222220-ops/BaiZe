package io.github.xgl34222220.baize.ui.settings

private val schedulerTaskNames = mapOf(
    "cache" to "应用缓存清理",
    "empty" to "空文件与空目录清理",
    "rules" to "规则垃圾清理",
    "fragment" to "残留碎片清理",
    "deep" to "深度清理",
    "organize" to "文件自动归类",
    "organizer" to "文件自动归类",
    "all" to "清理与文件归类"
)

fun schedulerTaskLabel(raw: String): String {
    val value = raw.trim()
    if (value.isBlank()) return "后台任务"
    val key = value.lowercase()
    schedulerTaskNames[key]?.let { return it }
    return when {
        key.startsWith("cache-") -> "应用缓存清理"
        key.startsWith("empty-") -> "空文件与空目录清理"
        key.startsWith("rules-") -> "规则垃圾清理"
        key.startsWith("fragment-") -> "残留碎片清理"
        key.startsWith("deep-") -> "深度清理"
        key.startsWith("organize-") || key.startsWith("organizer-") -> "文件自动归类"
        else -> value
    }
}

fun schedulerQueueLabel(raw: String): String {
    val counts = linkedMapOf<String, Int>()
    raw.split(',', ';', '\n')
        .map(String::trim)
        .filter(String::isNotBlank)
        .forEach { task ->
            val label = schedulerTaskLabel(task)
            counts[label] = (counts[label] ?: 0) + 1
        }
    return counts.entries.joinToString("、") { (label, count) ->
        if (count > 1) "$label ×$count" else label
    }
}

fun schedulerBlockedGroupsLabel(raw: String): String = raw
    .split(',', ';', '\n')
    .map(String::trim)
    .filter(String::isNotBlank)
    .joinToString("；") { item ->
        val parts = item.split(':', limit = 2)
        val task = schedulerTaskLabel(parts.firstOrNull().orEmpty())
        val reason = schedulerReasonLabel(parts.getOrNull(1).orEmpty())
            .replace(Regex("熔断\\s*(\\d+)\\s*分钟"), "连续失败，暂停 $1 分钟")
        if (reason.isBlank()) task else "$task：$reason"
    }

fun schedulerRuntimeStateLabel(raw: String): String = when (raw.trim().lowercase()) {
    "running" -> "正在执行"
    "completed" -> "最近任务已完成"
    "failed" -> "调度异常"
    "paused" -> "连续失败，暂时暂停"
    "disabled" -> "自动任务已关闭"
    "waiting" -> "等待执行"
    else -> "等待状态"
}

fun schedulerSupervisorStatusLabel(raw: String): String = when (raw.trim().lowercase()) {
    "alive", "running", "healthy" -> "后台守护正常"
    "recovering", "starting" -> "后台守护正在恢复"
    "failed", "error" -> "后台守护异常"
    "stale" -> "后台守护心跳过期"
    "stopped", "missing" -> "后台守护未运行"
    else -> "等待后台守护状态"
}

fun schedulerReasonLabel(raw: String): String {
    var text = raw.trim()
    if (text.isBlank()) return "等待下一次调度"
    text = text.replace("Supervisor", "后台守护进程", ignoreCase = true)
    text = text.replace("Worker", "后台任务", ignoreCase = true)
    val taskRegex = Regex(
        "(?<![A-Za-z])(cache|empty|rules|fragment|deep|organize|organizer|all)(?![A-Za-z])",
        RegexOption.IGNORE_CASE
    )
    return taskRegex.replace(text) { schedulerTaskLabel(it.value) }
}
