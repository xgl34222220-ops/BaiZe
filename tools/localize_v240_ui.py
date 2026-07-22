from pathlib import Path

ROOT = Path("v2/app/src/main/java/io/github/xgl34222220/baize")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing replacement target: {label}")
    return text.replace(old, new, 1)


helper = ROOT / "ui/settings/SchedulerText.kt"
helper.write_text(
    r'''package io.github.xgl34222220.baize.ui.settings

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
''',
    encoding="utf-8",
)

miuix = ROOT / "ui/settings/miuix/SettingsScreenMiuix.kt"
text = miuix.read_text(encoding="utf-8")
anchor = "import io.github.xgl34222220.baize.ui.settings.SettingsUiState\n"
imports = """import io.github.xgl34222220.baize.ui.settings.SettingsUiState
import io.github.xgl34222220.baize.ui.settings.schedulerBlockedGroupsLabel
import io.github.xgl34222220.baize.ui.settings.schedulerQueueLabel
import io.github.xgl34222220.baize.ui.settings.schedulerReasonLabel
import io.github.xgl34222220.baize.ui.settings.schedulerSupervisorStatusLabel
import io.github.xgl34222220.baize.ui.settings.schedulerTaskLabel
"""
if "import io.github.xgl34222220.baize.ui.settings.schedulerQueueLabel" not in text:
    if anchor not in text:
        raise RuntimeError("missing Miuix import anchor")
    text = text.replace(anchor, imports, 1)

for old, new in {
    'MiuixSectionTitle("TASK CENTER", "任务中心", "队列、等待原因与 Root 守护状态")': 'MiuixSectionTitle("任务管理", "任务中心", "待执行任务、暂缓原因与后台守护状态")',
    'MiuixSectionTitle("AUTOMATION", "自动清理", "总开关、执行条件与最低电量")': 'MiuixSectionTitle("自动执行", "自动清理", "总开关、执行条件与最低电量")',
    'MiuixSectionTitle("PROTECTION", "清理保护", "白名单、通知、安装包与单文件限制")': 'MiuixSectionTitle("安全保护", "清理保护", "白名单、通知、安装包与单文件限制")',
    'MiuixSectionTitle("SERVICE", "服务与诊断", "Root 服务恢复、清理明细与崩溃记录")': 'MiuixSectionTitle("系统服务", "服务与诊断", "后台服务恢复、清理明细与崩溃记录")',
    '"PREFERENCES"': '"设置中心"',
    '"Miuix 紧凑设置与清理保护"': '"MIUI 风格设置与清理保护"',
    '"检查计划并自动恢复 Supervisor"': '"检查计划并自动恢复后台守护进程"',
    '"条件满足后由统一 Root Worker 执行"': '"条件满足后由统一后台任务执行"',
    '"安全写入停止请求，当前 Worker 会在检查点退出"': '"安全写入停止请求，当前后台任务会在检查点退出"',
    '"15 分钟到 30 天，Root 配置为唯一真源"': '"15 分钟到 30 天，后台计划为唯一设置来源"',
}.items():
    text = text.replace(old, new)

text = replace_once(
    text,
    '''                when (config.runtimeState) {
                    "running" -> "正在执行 ${config.nextTask.ifBlank { "Root 任务" }}"
                    "failed" -> "调度异常"
                    "paused" -> "任务已熔断暂停"
                    else -> "队列 ${config.queueCount} 项 · ${config.supervisorStatus}"
                },''',
    '''                when (config.runtimeState) {
                    "running" -> "正在执行 ${schedulerTaskLabel(config.nextTask)}"
                    "failed" -> "调度异常"
                    "paused" -> "连续失败，任务暂时暂停"
                    else -> "待执行 ${config.queueCount} 项 · ${schedulerSupervisorStatusLabel(config.supervisorStatus)}"
                },''',
    "Miuix task title",
)

old_detail = '''                    append(config.runtimeReason.ifBlank { "等待下一次调度" })
                    if (config.queueGroups.isNotBlank()) append("\\n队列：").append(config.queueGroups)
                    if (config.blockedGroups.isNotBlank()) append("\\n等待条件：").append(config.blockedGroups)
                    if (config.runtimeStale) append("\\n守护心跳已过期，建议唤醒")'''
new_detail = '''                    append(schedulerReasonLabel(config.runtimeReason))
                    if (config.queueGroups.isNotBlank()) append("\\n待执行：").append(schedulerQueueLabel(config.queueGroups))
                    if (config.blockedGroups.isNotBlank()) append("\\n暂缓执行：").append(schedulerBlockedGroupsLabel(config.blockedGroups))
                    if (config.runtimeStale) append("\\n后台守护长时间没有响应，建议点击唤醒")'''
text = replace_once(text, old_detail, new_detail, "Miuix task details")
miuix.write_text(text, encoding="utf-8")

material = ROOT / "ui/settings/material/SettingsScreenMaterial.kt"
text = material.read_text(encoding="utf-8")
material_imports = imports + "import io.github.xgl34222220.baize.ui.settings.schedulerRuntimeStateLabel\n"
if "import io.github.xgl34222220.baize.ui.settings.schedulerQueueLabel" not in text:
    if anchor not in text:
        raise RuntimeError("missing Material import anchor")
    text = text.replace(anchor, material_imports, 1)

for old, new in {
    'MaterialSectionTitle("TASK CENTER", "任务中心")': 'MaterialSectionTitle("任务管理", "任务中心")',
    'MaterialSectionTitle("AUTOMATION", "自动清理")': 'MaterialSectionTitle("自动执行", "自动清理")',
    'MaterialSectionTitle("PROTECTION", "清理保护与通知")': 'MaterialSectionTitle("安全保护", "清理保护与通知")',
    'MaterialSectionTitle("SERVICE", "服务与诊断")': 'MaterialSectionTitle("系统服务", "服务与诊断")',
    '"PREFERENCES"': '"设置中心"',
    'title = "Root 任务中心"': 'title = "后台任务中心"',
    'subtitle = "${config.runtimeState} · 队列 ${config.queueCount} 项 · ${config.supervisorStatus}"': 'subtitle = "${schedulerRuntimeStateLabel(config.runtimeState)} · 待执行 ${config.queueCount} 项 · ${schedulerSupervisorStatusLabel(config.supervisorStatus)}"',
    '"Root 配置为唯一真实计划来源"': '"后台计划为唯一真实设置来源"',
}.items():
    text = text.replace(old, new)
text = replace_once(text, old_detail, new_detail, "Material task details")
material.write_text(text, encoding="utf-8")

dashboard = ROOT / "MiuixDashboardActivity.kt"
text = dashboard.read_text(encoding="utf-8")
if '"organize", "organizer", "organize-clean" -> "文件自动归类"' not in text:
    history_anchor = '        "profile-clean" -> "分类垃圾清理"\n'
    if history_anchor not in text:
        raise RuntimeError("missing history title anchor")
    text = text.replace(
        history_anchor,
        history_anchor
        + '        "organize", "organizer", "organize-clean" -> "文件自动归类"\n'
        + '        "organize-scan", "organizer-scan" -> "文件归类扫描"\n',
        1,
    )
dashboard.write_text(text, encoding="utf-8")

print("Chinese UI localization applied")
