package io.github.xgl34222220.baize.ui.clean

import androidx.compose.runtime.Immutable
import io.github.xgl34222220.baize.ScanPerformanceUiState
import io.github.xgl34222220.baize.SchedulerUiState

/** Shared category identifiers used by both Material and Miuix skins. */
enum class CleanCategoryId {
    CACHE,
    EMPTY,
    RULES,
    FRAGMENTS,
    DEEP
}

@Immutable
data class CleanCategoryUiItem(
    val id: CleanCategoryId,
    val title: String,
    val description: String,
    val enabled: Boolean,
    val intervalHours: Int
)

@Immutable
data class CleanUiState(
    val engineReady: Boolean,
    val running: Boolean,
    val scanSnapshotReady: Boolean,
    val serviceText: String,
    val automaticCleaningEnabled: Boolean,
    val categories: List<CleanCategoryUiItem>,
    val dailyEnabled: Boolean,
    val dailyHour: Int,
    val dailyMinute: Int,
    val dailyGraceMinutes: Int,
    val apkPackagesEnabled: Boolean,
    val apkPackageDays: Int,
    val scanRootWorkers: Int,
    val scanPerformance: ScanPerformanceUiState,
    val saving: Boolean
) {
    val enabledCategoryCount: Int
        get() = categories.count { it.enabled }

    val dailyTimeText: String
        get() = "%02d:%02d".format(dailyHour, dailyMinute)

    val scheduleSummary: String
        get() = if (dailyEnabled) {
            "每天 $dailyTimeText · 补做 ${formatMinutes(dailyGraceMinutes)}"
        } else {
            "各类别独立周期"
        }
}

data class CleanUiActions(
    val onAutomaticCleaningChanged: (Boolean) -> Unit,
    val onCategoryEnabledChanged: (CleanCategoryId, Boolean) -> Unit,
    val onCategoryIntervalChanged: (CleanCategoryId, Int) -> Unit,
    val onDailyScheduleChanged: (Boolean) -> Unit,
    val onDailyTimeChanged: (hour: Int, minute: Int) -> Unit,
    val onDailyGraceChanged: (minutes: Int) -> Unit,
    val onApkPackagesChanged: (Boolean) -> Unit,
    val onScanWorkerModeChanged: (Int) -> Unit,
    val onResetScanPerformance: () -> Unit,
    val onSave: () -> Unit,
    val onScan: () -> Unit,
    val onApkScan: () -> Unit,
    val onDeepClean: () -> Unit,
    val onCorpses: () -> Unit,
    val onAudit: () -> Unit
)

fun SchedulerUiState.toCleanUiState(
    engineReady: Boolean,
    running: Boolean,
    scanSnapshotReady: Boolean,
    serviceText: String,
    scanPerformance: ScanPerformanceUiState
): CleanUiState = CleanUiState(
    engineReady = engineReady,
    running = running,
    scanSnapshotReady = scanSnapshotReady,
    serviceText = serviceText,
    automaticCleaningEnabled = enabled,
    categories = listOf(
        CleanCategoryUiItem(
            id = CleanCategoryId.CACHE,
            title = "应用缓存",
            description = "应用内部缓存、外部缓存与临时文件",
            enabled = cacheEnabled,
            intervalHours = cacheHours
        ),
        CleanCategoryUiItem(
            id = CleanCategoryId.EMPTY,
            title = "空文件与空目录",
            description = "清理公共存储中的空项目并保持目录整洁",
            enabled = emptyEnabled,
            intervalHours = emptyHours
        ),
        CleanCategoryUiItem(
            id = CleanCategoryId.RULES,
            title = "规则垃圾与日志",
            description = "规则库命中的过期日志、临时文件与常见垃圾",
            enabled = rulesEnabled,
            intervalHours = rulesHours
        ),
        CleanCategoryUiItem(
            id = CleanCategoryId.FRAGMENTS,
            title = "残留碎片",
            description = "下载碎片、缩略图、离线残留与无效片段",
            enabled = fragmentEnabled,
            intervalHours = fragmentHours
        ),
        CleanCategoryUiItem(
            id = CleanCategoryId.DEEP,
            title = "深度清理项",
            description = "更广的日志和残留范围，继续受白名单与限制约束",
            enabled = deepEnabled,
            intervalHours = deepHours
        )
    ),
    dailyEnabled = dailyEnabled,
    dailyHour = dailyHour,
    dailyMinute = dailyMinute,
    dailyGraceMinutes = dailyGraceMinutes,
    apkPackagesEnabled = apkPackagesEnabled,
    apkPackageDays = apkPackageDays,
    scanRootWorkers = scanRootWorkers,
    scanPerformance = scanPerformance,
    saving = saving
)

fun SchedulerUiState.withAutomaticCleaning(enabled: Boolean): SchedulerUiState =
    copy(enabled = enabled)

fun SchedulerUiState.withCategoryEnabled(
    id: CleanCategoryId,
    enabled: Boolean
): SchedulerUiState = when (id) {
    CleanCategoryId.CACHE -> copy(cacheEnabled = enabled)
    CleanCategoryId.EMPTY -> copy(emptyEnabled = enabled)
    CleanCategoryId.RULES -> copy(rulesEnabled = enabled)
    CleanCategoryId.FRAGMENTS -> copy(fragmentEnabled = enabled)
    CleanCategoryId.DEEP -> copy(deepEnabled = enabled)
}

fun SchedulerUiState.withCategoryInterval(
    id: CleanCategoryId,
    hours: Int
): SchedulerUiState {
    val safeHours = hours.coerceIn(1, 720)
    return when (id) {
        CleanCategoryId.CACHE -> copy(cacheHours = safeHours)
        CleanCategoryId.EMPTY -> copy(emptyHours = safeHours)
        CleanCategoryId.RULES -> copy(rulesHours = safeHours)
        CleanCategoryId.FRAGMENTS -> copy(fragmentHours = safeHours)
        CleanCategoryId.DEEP -> copy(deepHours = safeHours)
    }
}

fun SchedulerUiState.withDailySchedule(enabled: Boolean): SchedulerUiState =
    copy(dailyEnabled = enabled)

fun SchedulerUiState.withDailyTime(hour: Int, minute: Int): SchedulerUiState =
    copy(dailyHour = hour.coerceIn(0, 23), dailyMinute = minute.coerceIn(0, 59))

fun SchedulerUiState.withDailyGrace(minutes: Int): SchedulerUiState =
    copy(dailyGraceMinutes = minutes.coerceIn(15, 720))

internal fun formatHours(hours: Int): String = when {
    hours % 24 == 0 -> "${hours / 24} 天"
    else -> "$hours 小时"
}

internal fun formatMinutes(minutes: Int): String = when {
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    minutes > 60 -> "${minutes / 60} 小时 ${minutes % 60} 分"
    else -> "$minutes 分钟"
}

internal fun scanWorkerModeLabel(mode: Int): String = when (mode) {
    1 -> "固定串行"
    2 -> "固定双进程"
    else -> "自动推荐"
}

internal fun scanWorkerReasonLabel(reason: String): String = when (reason) {
    "auto_bootstrap_serial" -> "首次建立串行基准"
    "auto_not_eligible" -> "当前设备或目录条件不适合并发"
    "auto_parallel_cooldown" -> "并发异常，暂时回退串行"
    "auto_small_workload" -> "工作量较小，串行更合适"
    "auto_parallel_probe" -> "正在探测双进程表现"
    "auto_serial_reprobe" -> "正在复测串行表现"
    "auto_parallel_reprobe" -> "正在复测双进程表现"
    "auto_parallel_faster" -> "本机双进程明显更快"
    "auto_serial_faster" -> "本机串行更快或差距不足"
    "manual_serial" -> "用户固定串行"
    "manual_parallel" -> "用户固定双进程"
    "manual_parallel_unavailable" -> "双进程条件不足，已使用串行"
    "auto_parallel_failed" -> "双进程失败，已进入冷却"
    else -> "等待建立本机性能基准"
}

internal fun scanRateText(rate: Long): String = if (rate > 0) "$rate 项/秒" else "暂无样本"
