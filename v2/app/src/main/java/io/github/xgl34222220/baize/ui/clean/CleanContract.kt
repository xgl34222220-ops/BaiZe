package io.github.xgl34222220.baize.ui.clean

import androidx.compose.runtime.Immutable
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
    serviceText: String
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
