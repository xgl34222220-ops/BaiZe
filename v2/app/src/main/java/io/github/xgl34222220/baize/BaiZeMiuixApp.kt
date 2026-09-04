package io.github.xgl34222220.baize

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
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
    val connected: Boolean = false,
    val ready: Boolean = false,
    val running: Boolean = false,
    val serviceText: String = "正在等待 Root 服务…",
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
    val rawLogName: String = "",
    val rawLog: String = "",
    val lastTaskTime: String = "",
    val protectedItems: List<ProtectedUiItem> = emptyList(),
    val history: List<HistoryUiItem> = emptyList(),
    val scanPerformance: ScanPerformanceUiState = ScanPerformanceUiState()
)

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
    val apps: List<HistoryAppUiItem> = emptyList()
)

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
    val scanRootWorkers: Int = 0,
    val runtimeState: String = "waiting",
    val runtimeReason: String = "等待调度器首次轮询",
    val queueCount: Int = 0,
    val queueGroups: String = "",
    val nextTask: String = "",
    val blockedGroups: String = "",
    val nextCheckEpoch: Long = 0L,
    val runtimeGroup: String = "",
    val cacheNextEpoch: Long = 0L,
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
        .put("scan_root_workers", 0)

    companion object {
        fun fromJson(json: JSONObject): SchedulerUiState {
            val runtime = json.optJSONObject("runtime") ?: JSONObject()
            val nextRuns = runtime.optJSONObject("nextRuns") ?: JSONObject()
            val legacyDailyEnabled = json.optInt("daily_schedule_enabled", 0) == 1
            val resolvedScheduleMode = when {
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
                scheduleMode = resolvedScheduleMode,
                dailyEnabled = resolvedScheduleMode == 2,
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
                runtimeState = runtime.optString("state", "waiting"),
                runtimeReason = runtime.optString("reason", "等待调度器首次轮询"),
                queueCount = runtime.optInt("queueCount", 0).coerceAtLeast(0),
                queueGroups = runtime.optString("queueGroups"),
                nextTask = runtime.optString("nextTask"),
                blockedGroups = runtime.optString("blockedGroups"),
                nextCheckEpoch = runtime.optLong("nextCheckEpoch", 0L).coerceAtLeast(0L),
                runtimeGroup = runtime.optString("group"),
                cacheNextEpoch = nextRuns.optLong("cache", 0L).coerceAtLeast(0L),
                emptyNextEpoch = nextRuns.optLong("empty", 0L).coerceAtLeast(0L),
                rulesNextEpoch = nextRuns.optLong("rules", 0L).coerceAtLeast(0L),
                fragmentNextEpoch = nextRuns.optLong("fragment", 0L).coerceAtLeast(0L),
                deepNextEpoch = nextRuns.optLong("deep", 0L).coerceAtLeast(0L),
                organizeNextEpoch = nextRuns.optLong("organize", 0L).coerceAtLeast(0L),
                supervisorStatus = runtime.optString("supervisorStatus", "unknown"),
                supervisorHeartbeatAge = runtime.optLong("supervisorHeartbeatAge", -1L),
                runtimeStale = runtime.optBoolean("stale", false),
                scanRootWorkers = 0
            )
        }
    }
}

private fun Boolean.flag() = if (this) 1 else 0

data class DashboardActions(
    val refresh: () -> Unit,
    val clean: () -> Unit,
    val organize: () -> Unit,
    val scan: () -> Unit,
    val apkScan: () -> Unit,
    val cleanScan: () -> Unit,
    val dismissScan: () -> Unit,
    val stop: () -> Unit,
    val deep: () -> Unit,
    val corpses: () -> Unit,
    val audit: () -> Unit,
    val updateScheduler: (SchedulerUiState) -> Unit,
    val saveScheduler: (SchedulerUiState) -> Unit,
    val schedulerCommand: (String) -> Unit,
    val clearHistory: () -> Unit,
    val clearRawLog: () -> Unit,
    val reviewProtected: () -> Unit,
    val whitelist: () -> Unit,
    val resumableScan: () -> Unit,
    val theme: () -> Unit,
    val reconnect: () -> Unit,
    val resetScanPerformance: () -> Unit,
    val crash: () -> Unit
)

@Composable
fun BaiZeMiuixApp(
    state: DashboardUiState,
    scheduler: SchedulerUiState,
    actions: DashboardActions,
    appearance: AppearanceSettings
) {
    BaiZeLuoShuApp(state, scheduler, actions, appearance)
}
