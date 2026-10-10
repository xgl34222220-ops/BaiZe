package io.github.xgl34222220.baize

import android.os.Build
import androidx.compose.runtime.Immutable
import org.json.JSONObject

@Immutable
data class ScanPerformanceUiState(
    val available: Boolean = false,
    val workerPolicy: String = "auto",
    val workerReason: String = "not_measured",
    val actualWorkers: Int = 1,
    val recommendedWorkers: Int = 1,
    val parallelGainPercent: Int = 0,
    val serialRate: Long = 0,
    val parallelRate: Long = 0,
    val successfulRuns: Int = 0,
    val nextProbeRun: Int = 0,
    val parallelBlockedUntil: Long = 0
)

@Immutable
data class DashboardUiState(
    val connecting: Boolean = false,
    val connectionFailed: Boolean = false,
    val connected: Boolean = false,
    val ready: Boolean = false,
    val running: Boolean = false,
    val serviceText: String = "正在等待 Root 服务…",
    val automationAvailable: Boolean = false,
    val automationText: String = "未安装自动清理模块",
    val versionWarning: String = "",
    val versionDetails: String = "",
    val taskPhase: String = "等待下一次清理",
    val taskOperation: String = "",
    val taskProgressCurrent: Long = 0L,
    val taskProgressTotal: Long = 0L,
    val taskProgressPath: String = "",
    val taskProgressBytes: Long = 0L,
    val taskProgressFiles: Long = 0L,
    val taskProgressElapsedMs: Long = 0L,
    val schedulerText: String = "等待调度器状态",
    val device: String = Build.MODEL,
    val android: String = "Android ${Build.VERSION.RELEASE}",
    val storageTotal: Long = 0,
    val storageUsed: Long = 0,
    val storageFree: Long = 0,
    val storagePercent: Float = 0f,
    val lastReleased: Long = 0,
    val lastReleasedKnown: Boolean = true,
    val scanCompleted: Boolean = false,
    val scanBytes: Long = 0,
    val scanFiles: Long = 0,
    val scanEmptyFiles: Long = 0,
    val scanEmptyDirs: Long = 0,
    val scanFragments: Long = 0,
    val scanErrors: Long = 0,
    val scanElapsed: Long = 0,
    val lifetimeRuns: Long = 0,
    val lifetimeReleased: Long = 0,
    val lifetimeFiles: Long = 0,
    val lifetimeEmptyFiles: Long = 0,
    val lifetimeEmptyDirs: Long = 0,
    val lifetimeFragments: Long = 0,
    val lifetimeElapsed: Long = 0,
    val whitelistCount: Int = 0,
    val recentApps: List<AppJunkUiItem> = emptyList(),
    val recentJunk: List<GeneralJunkUiItem> = emptyList(),
    /** History record id of the run that produced [recentApps]/[recentJunk]; blank when unknown (module/legacy). */
    val recentRecordId: String = "",
    /** True only when the recent lists carry confirmed deleted bytes, never scan estimates. */
    val recentDeletedEvidence: Boolean = false,
    val rawLogName: String = "",
    val rawLog: String = "",
    val lastTaskTime: String = "",
    val protectedItems: List<ProtectedUiItem> = emptyList(),
    /** 存在未过期的续清计划时，首页显示「继续上次清理」横幅（只读判断，校验仍由续清页完成）。 */
    val resumablePlan: Boolean = false,
    val history: List<HistoryUiItem> = emptyList(),
    val scanPerformance: ScanPerformanceUiState = ScanPerformanceUiState()
) {
    val connectionLabel: String
        get() = when {
            running -> "执行中"
            connectionFailed -> "连接失败"
            connecting -> "连接中"
            ready -> "已就绪"
            connected -> "未就绪"
            else -> "未连接"
        }
}

@Immutable
data class AppJunkUiItem(
    val packageName: String,
    val label: String,
    val category: String,
    val files: Long,
    val bytes: Long,
    val errors: Long = 0,
    val categories: List<AppJunkCategoryUiItem> = emptyList()
)

@Immutable
data class AppJunkCategoryUiItem(
    val name: String,
    val files: Long,
    val bytes: Long,
    val errors: Long,
    val samplePath: String
)

@Immutable
data class GeneralJunkUiItem(
    val name: String,
    val files: Long,
    val bytes: Long,
    val errors: Long,
    val samplePath: String
)

@Immutable
data class ProtectedUiItem(
    val id: String,
    val category: String,
    val path: String,
    val reason: String,
    val risk: String,
    val selectable: Boolean
)

@Immutable
data class HistoryUiItem(
    val title: String,
    val time: String,
    val trigger: String,
    val result: String,
    val bytes: Long,
    val files: Int,
    val emptyDirs: Int,
    val errors: Int,
    val cleaned: Boolean,
    val categories: List<HistoryCategoryUiItem> = emptyList(),
    val apps: List<HistoryAppUiItem> = emptyList(),
    val releaseState: String = "measured",
    val recordId: String = ""
) {
    fun capacityText(format: (Long) -> String): String = when (releaseState) {
        "unknown" -> "无法测量"
        "partial" -> "已确认 ${format(bytes)} · 部分未知"
        "retained" -> "尚未释放"
        "not_applicable" -> "不计释放"
        else -> format(bytes)
    }
}

@Immutable
data class HistoryCategoryUiItem(
    val name: String,
    val bytes: Long,
    val files: Long
)

@Immutable
data class HistoryAppUiItem(
    val packageName: String,
    val label: String,
    val category: String,
    val bytes: Long,
    val files: Long
)

@Immutable
data class SchedulerUiState(
    val enabled: Boolean = true,
    val cacheEnabled: Boolean = true,
    val cacheMinutes: Int = 1_440,
    val emptyEnabled: Boolean = true,
    val emptyMinutes: Int = 1_440,
    val rulesEnabled: Boolean = true,
    val rulesMinutes: Int = 1_440,
    val fragmentEnabled: Boolean = true,
    val fragmentMinutes: Int = 4_320,
    val deepEnabled: Boolean = false,
    val deepMinutes: Int = 10_080,
    val organizeEnabled: Boolean = false,
    val organizeMinutes: Int = 1_440,
    val organizeScreenOffOnly: Boolean = false,
    val organizeChargingOnly: Boolean = false,
    val organizeIdleOnly: Boolean = false,
    val organizeRunImmediately: Boolean = false,
    val organizerConflictPolicy: Int = 1,
    val organizerUndoRetention: Int = 10,
    val scheduleMode: Int = 0,
    val dailyEnabled: Boolean = false,
    val dailyHour: Int = 3,
    val dailyMinute: Int = 30,
    val dailyGraceMinutes: Int = 240,
    val screenOffOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val idleOnly: Boolean = false,
    val minBattery: Int = 25,
    val notifyOnComplete: Boolean = true,
    val notifyZero: Boolean = false,
    val maxFileMb: Int = 256,
    val apkPackagesEnabled: Boolean = true,
    val apkPackageDays: Int = 30,
    val apkMinutes: Int = 1_440,
    val scanRootWorkers: Int = 0,
    /** 应用专项缓存档位：0 保守 / 1 标准 / 2 增强。 */
    val appProfileTier: Int = 1,
    /** 聊天图片/短视频等用户媒体，仅在增强档下生效且需用户明确开启。 */
    val appProfileUserMedia: Boolean = false,
    /** 聊天媒体只清理多少天之前的内容（App 提供 7/30/90，模块接受 7~365）。 */
    val appProfileMediaDays: Int = 30,
    /** F2FS GC + TRIM：充电且息屏时每日最多一次。 */
    val maintenanceEnabled: Boolean = true,
    /** 系统维护的一步：根目录空文件夹与“禁止重建”占位，默认关闭。 */
    val rootTidyAuto: Boolean = false,
    val maintenanceSummary: String = "",
    val runtimeState: String = "waiting",
    val runtimeReason: String = "等待调度器首次轮询",
    val queueCount: Int = 0,
    val queueGroups: String = "",
    val nextTask: String = "",
    val blockedGroups: String = "",
    val nextCheckEpoch: Long = 0L,
    val runLedger: List<String> = emptyList(),
    val runtimeGroup: String = "",
    val cacheNextEpoch: Long = 0L,
    val apkNextEpoch: Long = 0L,
    val emptyNextEpoch: Long = 0L,
    val rulesNextEpoch: Long = 0L,
    val fragmentNextEpoch: Long = 0L,
    val deepNextEpoch: Long = 0L,
    val organizeNextEpoch: Long = 0L,
    val supervisorStatus: String = "unknown",
    val supervisorHeartbeatAge: Long = -1L,
    val runtimeStale: Boolean = false,
    val saving: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled.flag())
        .put("schedule_cache_enabled", cacheEnabled.flag())
        .put("schedule_cache_minutes", cacheMinutes.coerceIn(5, 43_200))
        .put("schedule_cache_hours", ((cacheMinutes + 59) / 60).coerceIn(1, 720))
        .put("schedule_empty_enabled", emptyEnabled.flag())
        .put("schedule_empty_minutes", emptyMinutes.coerceIn(5, 43_200))
        .put("schedule_empty_hours", ((emptyMinutes + 59) / 60).coerceIn(1, 720))
        .put("schedule_rules_enabled", rulesEnabled.flag())
        .put("schedule_rules_minutes", rulesMinutes.coerceIn(5, 43_200))
        .put("schedule_rules_hours", ((rulesMinutes + 59) / 60).coerceIn(1, 720))
        .put("schedule_fragment_enabled", fragmentEnabled.flag())
        .put("schedule_fragment_minutes", fragmentMinutes.coerceIn(5, 43_200))
        .put("schedule_fragment_hours", ((fragmentMinutes + 59) / 60).coerceIn(1, 720))
        .put("schedule_deep_enabled", deepEnabled.flag())
        .put("schedule_deep_minutes", deepMinutes.coerceIn(5, 43_200))
        .put("schedule_deep_hours", ((deepMinutes + 59) / 60).coerceIn(1, 720))
        .put("schedule_organize_enabled", organizeEnabled.flag())
        .put("schedule_organize_minutes", organizeMinutes.coerceIn(15, 43_200))
        .put("schedule_organize_hours", ((organizeMinutes + 59) / 60).coerceIn(1, 720))
        .put("organize_screen_off_only", organizeScreenOffOnly.flag())
        .put("organize_charging_only", organizeChargingOnly.flag())
        .put("organize_device_idle_only", organizeIdleOnly.flag())
        .put("organize_run_immediately", organizeRunImmediately.flag())
        .put("organizer_conflict_policy", organizerConflictPolicy.coerceIn(0, 2))
        .put("organizer_undo_retention", organizerUndoRetention.coerceIn(1, 20))
        .put("schedule_mode", scheduleMode.coerceIn(0, 2))
        .put("autopilot_enabled", if (scheduleMode == 0) 1 else 0)
        .put("daily_schedule_enabled", (scheduleMode == 2).flag())
        .put("daily_schedule_hour", dailyHour.coerceIn(0, 23))
        .put("daily_schedule_minute", dailyMinute.coerceIn(0, 59))
        .put("daily_grace_minutes", dailyGraceMinutes.coerceIn(15, 720))
        .put("screen_off_only", screenOffOnly.flag())
        .put("charging_only", chargingOnly.flag())
        .put("device_idle_only", idleOnly.flag())
        .put("min_battery", minBattery.coerceIn(0, 100))
        .put("notify_on_complete", notifyOnComplete.flag())
        .put("notify_zero_result", notifyZero.flag())
        .put("max_file_mb", maxFileMb.coerceIn(16, 2048))
        .put("clean_apk_packages", apkPackagesEnabled.flag())
        .put("apk_package_days", apkPackageDays.coerceIn(0, 365))
        .put("schedule_apk_minutes", apkMinutes.coerceIn(5, 43_200))
        .put("schedule_apk_hours", ((apkMinutes + 59) / 60).coerceIn(1, 720))
        .put("scan_root_workers", 0)
        .put("app_profile_tier", appProfileTier.coerceIn(0, 2))
        .put("app_profile_user_media", (appProfileUserMedia && appProfileTier == 2).flag())
        .put("app_profile_media_days", appProfileMediaDays.coerceIn(7, 365))
        .put("maintenance_enabled", maintenanceEnabled.flag())
        .put("root_tidy_auto", rootTidyAuto.flag())

    companion object {
        fun fromJson(json: JSONObject): SchedulerUiState {
            val runtime = json.optJSONObject("runtime") ?: JSONObject()
            val nextRuns = runtime.optJSONObject("nextRuns") ?: JSONObject()
            val legacyDailyEnabled = json.optInt("daily_schedule_enabled", 0) == 1
            val scheduleMode = when {
                json.has("schedule_mode") -> json.optInt("schedule_mode", 0).coerceIn(0, 2)
                legacyDailyEnabled -> 2
                json.optInt("autopilot_enabled", 1) == 0 -> 1
                else -> 0
            }
            return SchedulerUiState(
                enabled = json.optInt("enabled", 1) == 1,
                cacheEnabled = json.optInt("schedule_cache_enabled", 1) == 1,
                cacheMinutes = json.optInt("schedule_cache_minutes", json.optInt("schedule_cache_hours", 24) * 60).coerceIn(5, 43_200),
                emptyEnabled = json.optInt("schedule_empty_enabled", 1) == 1,
                emptyMinutes = json.optInt("schedule_empty_minutes", json.optInt("schedule_empty_hours", 24) * 60).coerceIn(5, 43_200),
                rulesEnabled = json.optInt("schedule_rules_enabled", 1) == 1,
                rulesMinutes = json.optInt("schedule_rules_minutes", json.optInt("schedule_rules_hours", 24) * 60).coerceIn(5, 43_200),
                fragmentEnabled = json.optInt("schedule_fragment_enabled", 1) == 1,
                fragmentMinutes = json.optInt("schedule_fragment_minutes", json.optInt("schedule_fragment_hours", 72) * 60).coerceIn(5, 43_200),
                deepEnabled = json.optInt("schedule_deep_enabled", 0) == 1,
                deepMinutes = json.optInt("schedule_deep_minutes", json.optInt("schedule_deep_hours", 168) * 60).coerceIn(5, 43_200),
                organizeEnabled = json.optInt("schedule_organize_enabled", 0) == 1,
                organizeMinutes = json.optInt("schedule_organize_minutes", json.optInt("schedule_organize_hours", 24) * 60).coerceIn(15, 43_200),
                organizeScreenOffOnly = json.optInt("organize_screen_off_only", 0) == 1,
                organizeChargingOnly = json.optInt("organize_charging_only", 0) == 1,
                organizeIdleOnly = json.optInt("organize_device_idle_only", 0) == 1,
                organizeRunImmediately = json.optInt("organize_run_immediately", 0) == 1,
                organizerConflictPolicy = json.optInt("organizer_conflict_policy", 1).coerceIn(0, 2),
                organizerUndoRetention = json.optInt("organizer_undo_retention", 10).coerceIn(1, 20),
                scheduleMode = scheduleMode,
                dailyEnabled = scheduleMode == 2,
                dailyHour = json.optInt("daily_schedule_hour", 3).coerceIn(0, 23),
                dailyMinute = json.optInt("daily_schedule_minute", 30).coerceIn(0, 59),
                dailyGraceMinutes = json.optInt("daily_grace_minutes", 240).coerceIn(15, 720),
                screenOffOnly = json.optInt("screen_off_only", 1) == 1,
                chargingOnly = json.optInt("charging_only", 0) == 1,
                idleOnly = json.optInt("device_idle_only", 0) == 1,
                minBattery = json.optInt("min_battery", 25).coerceIn(0, 100),
                notifyOnComplete = json.optInt("notify_on_complete", 1) == 1,
                notifyZero = json.optInt("notify_zero_result", 0) == 1,
                maxFileMb = json.optInt("max_file_mb", 256).coerceIn(16, 2048),
                apkPackagesEnabled = json.optInt("clean_apk_packages", 1) == 1,
                apkPackageDays = json.optInt("apk_package_days", 30).coerceIn(0, 365),
                apkMinutes = json.optInt("schedule_apk_minutes", json.optInt("schedule_rules_minutes", 1_440)).coerceIn(5, 43_200),
                runtimeState = runtime.optString("state", "waiting"),
                runtimeReason = runtime.optString("reason", "等待调度器首次轮询"),
                queueCount = runtime.optInt("queueCount", 0).coerceAtLeast(0),
                queueGroups = runtime.optString("queueGroups"),
                nextTask = runtime.optString("nextTask"),
                blockedGroups = runtime.optString("blockedGroups"),
                nextCheckEpoch = runtime.optLong("nextCheckEpoch", 0L).coerceAtLeast(0L),
                runLedger = runtime.optJSONArray("runLedger")?.let { rows -> (0 until minOf(rows.length(), 30)).map { index ->
                    val row = rows.optJSONObject(index) ?: org.json.JSONObject()
                    val stamp = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(row.optLong("epoch") * 1000))
                    "$stamp · ${row.optString("group")} · ${row.optString("reason")}" +
                        if (row.has("exitCode")) "（退出码 ${row.optInt("exitCode")}）" else ""
                } } ?: emptyList(),
                runtimeGroup = runtime.optString("group"),
                cacheNextEpoch = nextRuns.optLong("cache", 0L).coerceAtLeast(0L),
                apkNextEpoch = nextRuns.optLong("apk", 0L).coerceAtLeast(0L),
                emptyNextEpoch = nextRuns.optLong("empty", 0L).coerceAtLeast(0L),
                rulesNextEpoch = nextRuns.optLong("rules", 0L).coerceAtLeast(0L),
                fragmentNextEpoch = nextRuns.optLong("fragment", 0L).coerceAtLeast(0L),
                deepNextEpoch = nextRuns.optLong("deep", 0L).coerceAtLeast(0L),
                organizeNextEpoch = nextRuns.optLong("organize", 0L).coerceAtLeast(0L),
                supervisorStatus = runtime.optString("supervisorStatus", "unknown"),
                supervisorHeartbeatAge = runtime.optLong("supervisorHeartbeatAge", -1L),
                runtimeStale = runtime.optBoolean("stale", false),
                scanRootWorkers = 0,
                appProfileTier = json.optInt("app_profile_tier", 1).coerceIn(0, 2),
                appProfileUserMedia = json.optInt("app_profile_user_media", 0) == 1 &&
                    json.optInt("app_profile_tier", 1) == 2,
                appProfileMediaDays = json.optInt("app_profile_media_days", 30).coerceIn(7, 365),
                maintenanceEnabled = json.optInt("maintenance_enabled", 1) == 1,
                rootTidyAuto = json.optInt("root_tidy_auto", 0) == 1,
                maintenanceSummary = maintenanceSummary(runtime.optJSONObject("maintenance"))
            )
        }
    }
}

private fun Boolean.flag() = if (this) 1 else 0

/** 一行中文概括最近一次存储维护结果；从未运行时返回空串。 */
internal fun maintenanceSummary(maintenance: JSONObject?): String {
    if (maintenance == null) return ""
    val epoch = maintenance.optLong("lastEpoch", 0L)
    if (epoch <= 0L) return ""
    val stamp = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        .format(java.util.Date(epoch * 1000))
    val result = when (maintenance.optString("result")) {
        "ok" -> "已完成"
        "trim-only" -> "仅完成 TRIM"
        "interrupted" -> "已中断（亮屏或拔电）"
        "unsupported" -> "设备不支持"
        "running" -> "进行中"
        "killed" -> "上次被系统终止，已恢复"
        else -> "未知"
    }
    val dirty = maintenance.optString("dirtyBefore").takeIf { it.isNotBlank() && it != "-" }
        ?.let { before -> " · 脏段 $before→${maintenance.optString("dirtyAfter")}" }.orEmpty()
    return "$stamp · $result$dirty"
}

data class DashboardActions(
    val refresh: () -> Unit,
    val clean: () -> Unit,
    val organize: () -> Unit,
    val scan: () -> Unit,
    val apkScan: () -> Unit,
    val largeFiles: () -> Unit,
    val duplicates: () -> Unit,
    val storageAnalysis: () -> Unit,
    val cleanScan: () -> Unit,
    val dismissScan: () -> Unit,
    val stop: () -> Unit,
    val deep: () -> Unit,
    val corpses: () -> Unit,
    val audit: () -> Unit,
    val saveScheduler: (SchedulerUiState) -> Unit,
    val schedulerCommand: (String) -> Unit,
    val clearHistory: () -> Unit,
    val clearRawLog: () -> Unit,
    val reviewProtected: () -> Unit,
    val whitelist: () -> Unit,
    /** 打开断点续清工作台：消费已持久化的扫描快照，支持中断后继续。 */
    val resumableScan: () -> Unit,
    val theme: () -> Unit,
    val reconnect: () -> Unit,
    val resetScanPerformance: () -> Unit,
    val crash: () -> Unit,
    val photoCompression: () -> Unit = {},
    /** 只读统计微信各类目录占用（Root、后台线程），结果回到主线程。 */
    val wechatUsage: ((WechatUsage) -> Unit) -> Unit = { it(WechatUsage.failed("Root 服务尚未连接")) },
    val fileTrash: () -> Unit = {},
    val swipeReview: () -> Unit = {},
    /** 直接打开存储分析的某个视图（清理 Tab「专项清理」入口）。 */
    val storageView: (StorageToolMode) -> Unit = {},
    /** 清理审计（AuditActivity）。唯一入口在记录 Tab。 */
    val cleanupAudit: () -> Unit = {}
)
