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
    .map { schedulerTaskLabel(it.substringBefore(':').trim()) }
    .filter(String::isNotBlank)
    .distinct()
    .joinToString("、")

fun schedulerRuntimeStateLabel(raw: String): String = when (raw.trim().lowercase()) {
    "running" -> "正在执行"
    "disabled" -> "自动任务已关闭"
    else -> "待执行"
}

fun schedulerSupervisorStatusLabel(raw: String): String = when (raw.trim().lowercase()) {
    "alive", "running", "healthy" -> "自动运行正常"
    else -> "自动恢复中"
}

fun schedulerReasonLabel(raw: String): String = if (raw.trim().equals("执行中", ignoreCase = true)) {
    "执行中"
} else {
    "待执行"
}

fun schedulerCountdownLabel(
    enabled: Boolean,
    nextEpoch: Long,
    running: Boolean,
    nowEpoch: Long
): String {
    if (!enabled) return "已关闭"
    if (running) return "执行中"
    val remaining = nextEpoch - nowEpoch
    if (nextEpoch <= 0L || remaining <= 30L) return "待执行"
    val minutes = (remaining + 59L) / 60L
    if (minutes < 60L) return "还有 ${minutes} 分钟执行"
    val hours = minutes / 60L
    val minutePart = minutes % 60L
    if (hours < 24L) return if (minutePart > 0L) {
        "还有 ${hours} 小时 ${minutePart} 分钟执行"
    } else {
        "还有 ${hours} 小时执行"
    }
    val days = hours / 24L
    val hourPart = hours % 24L
    return if (hourPart > 0L) "还有 ${days} 天 ${hourPart} 小时执行" else "还有 ${days} 天执行"
}
