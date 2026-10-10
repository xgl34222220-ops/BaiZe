package io.github.xgl34222220.baize.ui.clean

import androidx.compose.runtime.Immutable
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.StorageToolMode

/** Shared category identifiers used by both Material and Miuix skins. */
enum class CleanCategoryId {
    CACHE,
    APK,
    EMPTY,
    RULES,
    FRAGMENTS,
    DEEP,
    ORGANIZE
}

enum class CleanScheduleMode(
    val id: Int,
    val title: String,
    val description: String
) {
    SMART(
        0,
        "智能定时",
        "以你设置的周期为基准，连续低收益时自动延长；存储紧张时恢复基础周期"
    ),
    STRICT_INTERVAL(
        1,
        "严格间隔",
        "完全按照各任务设置的间隔执行，不根据清理收益自动延长"
    ),
    FIXED_DAILY(
        2,
        "每日固定",
        "每天在指定时间执行已启用的清理任务"
    );

    companion object {
        fun fromId(id: Int): CleanScheduleMode =
            entries.firstOrNull { it.id == id } ?: SMART
    }
}

@Immutable
data class CleanCategoryUiItem(
    val id: CleanCategoryId,
    val title: String,
    val description: String,
    val enabled: Boolean,
    val intervalMinutes: Int
)

@Immutable
data class CleanUiState(
    val engineReady: Boolean,
    val running: Boolean,
    val scanSnapshotReady: Boolean,
    val serviceText: String,
    val automationAvailable: Boolean,
    val automationText: String,
    val automaticCleaningEnabled: Boolean,
    val categories: List<CleanCategoryUiItem>,
    val scheduleMode: CleanScheduleMode,
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
        get() = when (scheduleMode) {
            CleanScheduleMode.SMART -> "智能定时 · 各类别独立基础周期"
            CleanScheduleMode.STRICT_INTERVAL -> "严格间隔 · 完全按设置周期执行"
            CleanScheduleMode.FIXED_DAILY -> "每天 $dailyTimeText · 补做 ${formatMinutes(dailyGraceMinutes)}"
        }
}

/**
 * 清理 Tab 的动作。去重后：
 * - 「一键扫描」只在首页 Hero；[onScan] 保留给旧调用方（同一个前台工作台动作），清理 Tab 不再显示这一行。
 * - 安装包自动清理只有任务计划里的一个开关（[onCategoryEnabledChanged] APK，写 apkPackagesEnabled）。
 * - 专项清理的每个工具在清理 Tab 只出现一次，见 [cleanToolEntries]。
 */
data class CleanUiActions(
    val onAutomaticCleaningChanged: (Boolean) -> Unit,
    val onCategoryEnabledChanged: (CleanCategoryId, Boolean) -> Unit,
    val onCategoryIntervalChanged: (CleanCategoryId, Int) -> Unit,
    val onScheduleModeChanged: (CleanScheduleMode) -> Unit,
    val onDailyTimeChanged: (hour: Int, minute: Int) -> Unit,
    val onDailyGraceChanged: (minutes: Int) -> Unit,
    val onScan: () -> Unit = {},
    val onDeepClean: () -> Unit = {},
    val onLargeFiles: () -> Unit = {},
    val onDuplicates: () -> Unit = {},
    val onStorageView: (StorageToolMode) -> Unit = {},
    val onApkScan: () -> Unit = {},
    val onCorpses: () -> Unit = {},
    val onFileOrganizer: () -> Unit = {},
    val onPhotoCompression: () -> Unit = {},
    val onSwipeReview: () -> Unit = {},
    val onShizukuCache: () -> Unit = {},
    val onApkPackageDaysChanged: (Int) -> Unit = {},
    /** 打开「执行条件与高级」子页（草稿 + 保存，原 设置 → 自动任务设置）。 */
    val onOpenAutomationSettings: () -> Unit = {},
    /** 打开「运行状况」（原 设置 → 自动任务记录，SchedulerHealthDialog）。 */
    val onOpenSchedulerHealth: () -> Unit = {}
)

/** 清理 Tab「专项清理」的一行：每个工具只出现一次（[cleanToolEntries] 的标题与动作都不重复）。 */
@Immutable
data class CleanToolEntry(val key: String, val title: String, val subtitle: String, val onClick: () -> Unit)

/** 专项清理的固定入口顺序。存储分析视图下拉、扫描结果「需要你复核」属于页内切换/结果，不算入口。 */
fun cleanToolEntries(actions: CleanUiActions): List<CleanToolEntry> = listOf(
    CleanToolEntry("large", "大文件", "按大小排列，逐个确认", actions.onLargeFiles),
    CleanToolEntry("duplicates", "重复文件", "保留一份，其余可移入回收站", actions.onDuplicates),
    CleanToolEntry("screenshots", "截图与录屏", "30 天前的截图与录屏") { actions.onStorageView(StorageToolMode.SCREENSHOTS) },
    CleanToolEntry("downloads", "旧下载", "下载目录中久未改动的文件") { actions.onStorageView(StorageToolMode.OLD_DOWNLOADS) },
    CleanToolEntry("chat", "聊天媒体", "微信、QQ 等聊天应用保存的图片与视频") { actions.onStorageView(StorageToolMode.CHAT_MEDIA) },
    CleanToolEntry("apk", "安装包", "已安装或重复的 APK", actions.onApkScan),
    CleanToolEntry("corpses", "卸载残留", "已卸载应用留下的目录", actions.onCorpses),
    CleanToolEntry("root", "根目录整理", "空文件夹与散落目录") { actions.onStorageView(StorageToolMode.ROOT) },
    CleanToolEntry("organize", "文件归类", "整理下载与散落文件", actions.onFileOrganizer),
    CleanToolEntry("photo", "照片瘦身", "压缩大照片，保留原图可选", actions.onPhotoCompression),
    CleanToolEntry("swipe", "滑动整理", "左右滑动快速取舍", actions.onSwipeReview),
    CleanToolEntry("shizuku", "免 Root 缓存清理", "连接 Shizuku 后按应用清理缓存", actions.onShizukuCache)
)

fun SchedulerUiState.toCleanUiState(
    engineReady: Boolean,
    running: Boolean,
    scanSnapshotReady: Boolean,
    serviceText: String,
    automationAvailable: Boolean,
    automationText: String
): CleanUiState = CleanUiState(
    engineReady = engineReady,
    running = running,
    scanSnapshotReady = scanSnapshotReady,
    serviceText = serviceText,
    automationAvailable = automationAvailable,
    automationText = automationText,
    automaticCleaningEnabled = enabled,
    categories = listOf(
        CleanCategoryUiItem(
            id = CleanCategoryId.APK,
            title = "安装包",
            description = "独立定时 · 保留 ${apkPackageDays} 天 · APK / APKS / XAPK / APKM",
            enabled = apkPackagesEnabled,
            intervalMinutes = apkMinutes
        ),
        CleanCategoryUiItem(
            id = CleanCategoryId.CACHE,
            title = "应用缓存",
            description = "应用内部缓存、外部缓存与临时文件",
            enabled = cacheEnabled,
            intervalMinutes = cacheMinutes
        ),
        CleanCategoryUiItem(
            id = CleanCategoryId.EMPTY,
            title = "空文件与空目录",
            description = "清理公共存储中的空项目并保持目录整洁",
            enabled = emptyEnabled,
            intervalMinutes = emptyMinutes
        ),
        CleanCategoryUiItem(
            id = CleanCategoryId.RULES,
            title = "规则垃圾与日志",
            description = "规则库命中的过期日志、临时文件与常见垃圾",
            enabled = rulesEnabled,
            intervalMinutes = rulesMinutes
        ),
        CleanCategoryUiItem(
            id = CleanCategoryId.FRAGMENTS,
            title = "残留碎片",
            description = "下载碎片、缩略图、离线残留与无效片段",
            enabled = fragmentEnabled,
            intervalMinutes = fragmentMinutes
        ),
        CleanCategoryUiItem(
            id = CleanCategoryId.DEEP,
            title = "深度清理",
            description = "更广的日志和残留范围，继续受白名单与限制约束",
            enabled = deepEnabled,
            intervalMinutes = deepMinutes
        ),
        CleanCategoryUiItem(
            id = CleanCategoryId.ORGANIZE,
            title = "文件自动归类",
            description = "按文件类型整理下载目录，冲突文件会安全保留",
            enabled = organizeEnabled,
            intervalMinutes = organizeMinutes
        )
    ),
    scheduleMode = CleanScheduleMode.fromId(scheduleMode),
    dailyEnabled = scheduleMode == CleanScheduleMode.FIXED_DAILY.id,
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
    CleanCategoryId.APK -> copy(apkPackagesEnabled = enabled)
    CleanCategoryId.CACHE -> copy(cacheEnabled = enabled)
    CleanCategoryId.EMPTY -> copy(emptyEnabled = enabled)
    CleanCategoryId.RULES -> copy(rulesEnabled = enabled)
    CleanCategoryId.FRAGMENTS -> copy(fragmentEnabled = enabled)
    CleanCategoryId.DEEP -> copy(deepEnabled = enabled)
    CleanCategoryId.ORGANIZE -> copy(organizeEnabled = enabled)
}

fun SchedulerUiState.withCategoryInterval(
    id: CleanCategoryId,
    minutes: Int
): SchedulerUiState {
    val safeMinutes = minutes.coerceIn(5, 43_200)
    return when (id) {
        CleanCategoryId.APK -> copy(apkMinutes = safeMinutes)
        CleanCategoryId.CACHE -> copy(cacheMinutes = safeMinutes)
        CleanCategoryId.EMPTY -> copy(emptyMinutes = safeMinutes)
        CleanCategoryId.RULES -> copy(rulesMinutes = safeMinutes)
        CleanCategoryId.FRAGMENTS -> copy(fragmentMinutes = safeMinutes)
        CleanCategoryId.DEEP -> copy(deepMinutes = safeMinutes)
        CleanCategoryId.ORGANIZE -> copy(organizeMinutes = safeMinutes.coerceAtLeast(15))
    }
}

fun SchedulerUiState.withScheduleMode(mode: CleanScheduleMode): SchedulerUiState =
    copy(scheduleMode = mode.id, dailyEnabled = mode == CleanScheduleMode.FIXED_DAILY)

fun SchedulerUiState.withDailyTime(hour: Int, minute: Int): SchedulerUiState =
    copy(dailyHour = hour.coerceIn(0, 23), dailyMinute = minute.coerceIn(0, 59))

fun SchedulerUiState.withDailyGrace(minutes: Int): SchedulerUiState =
    copy(dailyGraceMinutes = minutes.coerceIn(15, 720))

fun SchedulerUiState.withApkPackageDays(days: Int): SchedulerUiState =
    copy(apkPackageDays = days.coerceIn(0, 365))

internal fun formatHours(hours: Int): String = when {
    hours % 24 == 0 -> "${hours / 24} 天"
    else -> "$hours 小时"
}

internal fun formatMinutes(minutes: Int): String = when {
    minutes % 1_440 == 0 -> "${minutes / 1_440} 天"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    minutes > 60 -> "${minutes / 60} 小时 ${minutes % 60} 分"
    else -> "$minutes 分钟"
}
