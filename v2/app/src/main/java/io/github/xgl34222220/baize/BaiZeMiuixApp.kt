package io.github.xgl34222220.baize

import android.os.Build
import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderDelete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.color.MaterialColors
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.clean.CleanRoute
import io.github.xgl34222220.baize.ui.home.HomeRoute
import io.github.xgl34222220.baize.ui.history.HistoryRoute
import io.github.xgl34222220.baize.ui.settings.SettingsRoute
import io.github.xgl34222220.baize.ui.miuix.MiuixLiquidDock
import io.github.xgl34222220.baize.ui.miuix.MiuixLiquidNavItem
import io.github.xgl34222220.baize.ui.miuix.MiuixLiquidPrimaryButton
import io.github.xgl34222220.baize.ui.miuix.MiuixOverviewHero
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import org.json.JSONObject
import kotlin.math.roundToInt

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
    val versionWarning: String = "",
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
) {
    val connectionLabel: String
        get() = when {
            running -> "执行中"
            connectionFailed -> "连接失败"
            ready -> "已就绪"
            connecting -> "连接中"
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
    val apkMinutes: Int = 1_440,
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
    /** 打开断点续清工作台：消费已持久化的扫描快照，支持中断后继续。 */
    val resumableScan: () -> Unit,
    val theme: () -> Unit,
    val reconnect: () -> Unit,
    val resetScanPerformance: () -> Unit,
    val crash: () -> Unit
)

private enum class BaiZePage(val title: String, val icon: ImageVector) {
    Home("首页", Icons.Rounded.Home),
    Clean("清理", Icons.Rounded.CleaningServices),
    Records("记录", Icons.Rounded.History),
    Settings("设置", Icons.Rounded.Settings)
}

@Composable
fun BaiZeMiuixApp(
    state: DashboardUiState,
    scheduler: SchedulerUiState,
    actions: DashboardActions,
    appearance: AppearanceSettings,
    initialPage: Int = 0
) {
    BaiZeTheme(appearance) {
        CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
            val dark = MaterialTheme.colorScheme.background.luminance() < .5f
            val amoled = dark && appearance.amoledBlack
            val runtimeDegraded = io.github.xgl34222220.baize.performance.PerformanceRuntime.degraded.value
            val hazeState = rememberHazeState(
                blurEnabled = appearance.uiStyle == UiStyle.MIUIX &&
                    appearance.blurEnabled &&
                    appearance.glassEnabled &&
                    !amoled && !(appearance.adaptiveSmoothMode && runtimeDegraded)
            )
            var page by rememberSaveable { mutableStateOf(BaiZePage.entries[initialPage.coerceIn(0, BaiZePage.entries.lastIndex)]) }
            var expandedCleanCategory by rememberSaveable { mutableStateOf("") }
            val miuixNavItems = remember {
                BaiZePage.entries.map { MiuixLiquidNavItem(it.title, it.icon) }
            }

            Column(Modifier.fillMaxSize()) {
                if (state.versionWarning.isNotBlank()) {
                    Text(
                        text = state.versionWarning,
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 12.sp
                    )
                }
                Box(Modifier.weight(1f)) {
                    when (appearance.uiStyle) {
                        UiStyle.MATERIAL -> Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(BaiZeTokens.colors.surfaceBase)
                        ) {
                            AnimatedPageHost(
                                page = page,
                                style = UiStyle.MATERIAL,
                                modifier = Modifier
                                    .fillMaxSize()
                            ) { targetPage ->
                                when (targetPage) {
                                    BaiZePage.Home -> HomeRoute(UiStyle.MATERIAL, state.forHomePage(), scheduler, actions) { page = BaiZePage.Clean }
                                    BaiZePage.Clean -> CleanRoute(
                                        style = UiStyle.MATERIAL,
                                        dashboard = state.forCleanPage(),
                                        scheduler = scheduler,
                                        dashboardActions = actions,
                                        expandedCategory = expandedCleanCategory,
                                        onExpandedCategoryChanged = { expandedCleanCategory = it }
                                    )
                                    BaiZePage.Records -> HistoryRoute(UiStyle.MATERIAL, state.forHistoryPage(), actions)
                                    BaiZePage.Settings -> SettingsRoute(UiStyle.MATERIAL, state.forSettingsPage(), scheduler, appearance, actions) { page = BaiZePage.Records }
                                }
                            }
                            MaterialFloatingDock(
                                selected = page,
                                onSelected = { page = it },
                                floating = appearance.floatingDock,
                                modifier = Modifier.align(Alignment.BottomCenter)
                            )
                        }

                        UiStyle.MIUIX -> Box(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .hazeSource(state = hazeState)
                            ) {
                                MiuiXBackdrop(dark, amoled)
                                AnimatedPageHost(
                                    page = page,
                                    style = UiStyle.MIUIX,
                                    modifier = Modifier
                                        .fillMaxSize()
                                ) { targetPage ->
                                    when (targetPage) {
                                        BaiZePage.Home -> HomeRoute(UiStyle.MIUIX, state.forHomePage(), scheduler, actions) { page = BaiZePage.Clean }
                                        BaiZePage.Clean -> CleanRoute(
                                            style = UiStyle.MIUIX,
                                            dashboard = state.forCleanPage(),
                                            scheduler = scheduler,
                                            dashboardActions = actions,
                                            expandedCategory = expandedCleanCategory,
                                            onExpandedCategoryChanged = { expandedCleanCategory = it }
                                        )
                                        BaiZePage.Records -> HistoryRoute(UiStyle.MIUIX, state.forHistoryPage(), actions)
                                        BaiZePage.Settings -> SettingsRoute(UiStyle.MIUIX, state.forSettingsPage(), scheduler, appearance, actions) { page = BaiZePage.Records }
                                    }
                                }
                            }
                            MiuixLiquidDock(
                                selectedIndex = page.ordinal,
                                items = miuixNavItems,
                                onSelected = { index -> page = BaiZePage.entries[index] },
                                hazeState = hazeState,
                                floating = appearance.floatingDock,
                                modifier = Modifier.align(Alignment.BottomCenter)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedPageHost(
    page: BaiZePage,
    style: UiStyle,
    modifier: Modifier = Modifier,
    content: @Composable (BaiZePage) -> Unit
) {
    AnimatedContent(
        targetState = page,
        modifier = modifier,
        contentKey = { it },
        transitionSpec = {
            val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
            val degraded = io.github.xgl34222220.baize.performance.PerformanceRuntime.degraded.value
            val enterDuration = if (degraded) 90 else if (style == UiStyle.MIUIX) 210 else 180
            val exitDuration = if (degraded) 70 else if (style == UiStyle.MIUIX) 140 else 120
            val enterDivisor = if (degraded) Int.MAX_VALUE else if (style == UiStyle.MIUIX) 14 else 18
            val exitDivisor = if (degraded) Int.MAX_VALUE else if (style == UiStyle.MIUIX) 20 else 24

            (fadeIn(tween(enterDuration)) + slideInHorizontally(tween(enterDuration)) { width ->
                direction * width / enterDivisor
            }).togetherWith(
                fadeOut(tween(exitDuration)) + slideOutHorizontally(tween(exitDuration)) { width ->
                    -direction * width / exitDivisor
                }
            )
        },
        label = "baizePageMotion"
    ) { targetPage ->
        content(targetPage)
    }
}

@Composable
private fun MiuiXBackdrop(dark: Boolean, amoled: Boolean) {
    Box(Modifier.fillMaxSize().background(BaiZeTokens.colors.surfaceBase))
}

@Composable
private fun MaterialFloatingDock(
    selected: BaiZePage,
    onSelected: (BaiZePage) -> Unit,
    floating: Boolean,
    modifier: Modifier = Modifier
) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val shape = if (floating) {
        RoundedCornerShape(28.dp)
    } else {
        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    }
    val outerModifier = if (floating) {
        modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = bottom + 8.dp)
            .fillMaxWidth()
    } else {
        modifier.fillMaxWidth()
    }

    Surface(
        modifier = outerModifier,
        shape = shape,
        color = BaiZeTokens.colors.surfaceRaised,
        tonalElevation = 0.dp,
        shadowElevation = if (floating) 3.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth().selectableGroup()
                .padding(
                    start = 8.dp,
                    top = 4.dp,
                    end = 8.dp,
                    bottom = if (floating) 4.dp else bottom + 6.dp
                )
        ) {
            BaiZePage.entries.forEach { item ->
                val active = item == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .selectable(active, role = Role.Tab) { onSelected(item) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 56.dp, height = 32.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (active) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            modifier = Modifier.size(23.dp),
                            tint = if (active) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = item.title,
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

private fun DashboardUiState.forHomePage(): DashboardUiState = copy(
    rawLogName = "", rawLog = "", history = emptyList()
)

private fun DashboardUiState.forCleanPage(): DashboardUiState = copy(
    rawLogName = "", rawLog = "", history = emptyList(), lifetimeRuns = 0,
    lifetimeReleased = 0, lifetimeFiles = 0, lifetimeEmptyFiles = 0,
    lifetimeEmptyDirs = 0, lifetimeFragments = 0, lifetimeElapsed = 0
)

private fun DashboardUiState.forHistoryPage(): DashboardUiState = DashboardUiState(
    lastReleased = lastReleased,
    scanCompleted = scanCompleted,
    scanBytes = scanBytes,
    scanFiles = scanFiles,
    scanEmptyFiles = scanEmptyFiles,
    scanEmptyDirs = scanEmptyDirs,
    scanFragments = scanFragments,
    scanErrors = scanErrors,
    scanElapsed = scanElapsed,
    lifetimeRuns = lifetimeRuns,
    lifetimeReleased = lifetimeReleased,
    lifetimeFiles = lifetimeFiles,
    lifetimeEmptyFiles = lifetimeEmptyFiles,
    lifetimeEmptyDirs = lifetimeEmptyDirs,
    lifetimeFragments = lifetimeFragments,
    lifetimeElapsed = lifetimeElapsed,
    recentApps = recentApps,
    recentJunk = recentJunk,
    history = history
)

private fun DashboardUiState.forLogsPage(): DashboardUiState = DashboardUiState(
    connected = connected, ready = ready, running = running, serviceText = serviceText,
    taskPhase = taskPhase, rawLogName = rawLogName, rawLog = rawLog
)

private fun DashboardUiState.forSettingsPage(): DashboardUiState = DashboardUiState(
    connecting = connecting, connectionFailed = connectionFailed,
    connected = connected, ready = ready, running = running, serviceText = serviceText,
    taskPhase = taskPhase, whitelistCount = whitelistCount, scanPerformance = scanPerformance
)
