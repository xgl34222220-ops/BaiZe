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
    val cacheMinutes: Int = 60,
    val emptyEnabled: Boolean = true,
    val emptyMinutes: Int = 60,
    val rulesEnabled: Boolean = true,
    val rulesMinutes: Int = 360,
    val fragmentEnabled: Boolean = true,
    val fragmentMinutes: Int = 720,
    val deepEnabled: Boolean = false,
    val deepMinutes: Int = 10_080,
    val organizeEnabled: Boolean = false,
    val organizeMinutes: Int = 1_440,
    val organizeScreenOffOnly: Boolean = true,
    val organizeChargingOnly: Boolean = false,
    val organizeIdleOnly: Boolean = false,
    val organizeRunImmediately: Boolean = false,
    val organizerConflictPolicy: Int = 1,
    val organizerUndoRetention: Int = 10,
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
        .put("daily_schedule_enabled", dailyEnabled.flag())
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
            return SchedulerUiState(
                enabled = json.optInt("enabled", 1) == 1,
                cacheEnabled = json.optInt("schedule_cache_enabled", 1) == 1,
                cacheMinutes = json.optInt("schedule_cache_minutes", json.optInt("schedule_cache_hours", 1) * 60).coerceIn(5, 43_200),
                emptyEnabled = json.optInt("schedule_empty_enabled", 1) == 1,
                emptyMinutes = json.optInt("schedule_empty_minutes", json.optInt("schedule_empty_hours", 1) * 60).coerceIn(5, 43_200),
                rulesEnabled = json.optInt("schedule_rules_enabled", 1) == 1,
                rulesMinutes = json.optInt("schedule_rules_minutes", json.optInt("schedule_rules_hours", 6) * 60).coerceIn(5, 43_200),
                fragmentEnabled = json.optInt("schedule_fragment_enabled", 1) == 1,
                fragmentMinutes = json.optInt("schedule_fragment_minutes", json.optInt("schedule_fragment_hours", 12) * 60).coerceIn(5, 43_200),
                deepEnabled = json.optInt("schedule_deep_enabled", 0) == 1,
                deepMinutes = json.optInt("schedule_deep_minutes", json.optInt("schedule_deep_hours", 168) * 60).coerceIn(5, 43_200),
                organizeEnabled = json.optInt("schedule_organize_enabled", 0) == 1,
                organizeMinutes = json.optInt("schedule_organize_minutes", json.optInt("schedule_organize_hours", 24) * 60).coerceIn(15, 43_200),
                organizeScreenOffOnly = json.optInt("organize_screen_off_only", 1) == 1,
                organizeChargingOnly = json.optInt("organize_charging_only", 0) == 1,
                organizeIdleOnly = json.optInt("organize_device_idle_only", 0) == 1,
                organizeRunImmediately = json.optInt("organize_run_immediately", 0) == 1,
                organizerConflictPolicy = json.optInt("organizer_conflict_policy", 1).coerceIn(0, 2),
                organizerUndoRetention = json.optInt("organizer_undo_retention", 10).coerceIn(1, 20),
                dailyEnabled = json.optInt("daily_schedule_enabled", 0) == 1,
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
    appearance: AppearanceSettings
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
            var page by rememberSaveable { mutableStateOf(BaiZePage.Home) }
            var expandedCleanCategory by rememberSaveable { mutableStateOf("") }
            val miuixNavItems = remember {
                BaiZePage.entries.map { MiuixLiquidNavItem(it.title, it.icon) }
            }

            when (appearance.uiStyle) {
                UiStyle.MATERIAL -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
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
    val scheme = MaterialTheme.colorScheme
    // 纯色中性底 + 一抹极淡单色氛围，不做蓝紫渐变与多层光斑。
    Box(
        Modifier
            .fillMaxSize()
            .background(BaiZeTokens.colors.surfaceBase)
            .drawBehind {
                if (amoled) return@drawBehind
                drawRect(
                    Brush.radialGradient(
                        listOf(scheme.primary.copy(alpha = if (dark) .05f else .06f), Color.Transparent),
                        center = Offset(size.width * .85f, 0f),
                        radius = size.width * .9f
                    )
                )
            }
    )
}

@Composable
private fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    shadow: Int = 10,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit
) {
    val settings = LocalAppearanceSettings.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val amoled = dark && settings.amoledBlack
    val glass = settings.glassEnabled
    val fill = when {
        amoled || glass -> BaiZeTokens.colors.surfaceRaised
        else -> MaterialTheme.colorScheme.surface
    }
    val border = if (dark) Color.White.copy(alpha = .08f) else MaterialTheme.colorScheme.primary.copy(alpha = .08f)
    Box(
        modifier
            .shadow(shadow.dp, shape, clip = false)
            .clip(shape)
            .background(fill)
            .border(1.dp, border, shape)
            .padding(contentPadding)
    ) { content() }
}

@Composable
private fun ResultSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(28.dp),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 3.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)
        )
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

@Composable
private fun PageHeader(eyebrow: String, title: String, subtitle: String, refresh: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = BaiZeTokens.spacing.pageHorizontal, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow.uppercase(), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.height(5.dp))
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = BaiZeTokens.type.display)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        if (refresh != null) {
            GlassSurface(shape = BaiZeTokens.corners.medium, shadow = 6) {
                IconButton(onClick = refresh, modifier = Modifier.size(58.dp)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新", modifier = Modifier.size(27.dp))
                }
            }
        }
    }
}

@Composable
internal fun HomeScreenMiuix(
    state: DashboardUiState,
    actions: DashboardActions,
    onOpenClean: () -> Unit
) {
    val context = LocalContext.current
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val positive = state.ready || state.scanCompleted
    val statusTitle = when {
        state.running -> "清理任务执行中"
        state.scanCompleted -> "扫描结果已就绪"
        state.ready -> "清理引擎已就绪"
        state.connected -> "清理引擎已连接"
        else -> "正在恢复清理引擎"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 154.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            PageHeader(
                "SMART CLEAN",
                "白泽",
                "智能清理概览 · v${BuildConfig.VERSION_NAME}",
                actions.refresh
            )
        }
        item {
            MiuixOverviewHero(
                device = state.device,
                android = state.android,
                statusTitle = statusTitle,
                taskPhase = state.taskPhase,
                releasedText = Formatter.formatFileSize(context, state.lastReleased),
                positive = positive,
                modifier = Modifier.padding(horizontal = BaiZeTokens.spacing.pageHorizontal)
            )
        }
        item {
            GlassSurface(
                modifier = Modifier.padding(horizontal = BaiZeTokens.spacing.pageHorizontal).fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                shadow = 6,
                contentPadding = PaddingValues(BaiZeTokens.spacing.xl)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StorageRing(state.storagePercent)
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "可用空间",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            Formatter.formatFileSize(context, state.storageFree),
                            fontSize = 28.sp,
                            lineHeight = 34.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "已用 ${Formatter.formatFileSize(context, state.storageUsed)} · 共 ${Formatter.formatFileSize(context, state.storageTotal)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
        item {
            MiuixLiquidPrimaryButton(
                running = state.running,
                scanReady = state.scanCompleted,
                enabled = state.running || state.ready || state.scanCompleted,
                onClick = when {
                    state.running -> actions.stop
                    state.scanCompleted -> actions.cleanScan
                    else -> actions.clean
                },
                modifier = Modifier.padding(horizontal = BaiZeTokens.spacing.pageHorizontal)
            )
        }
        item {
            StatusPill(
                ready = state.ready,
                scanReady = state.scanCompleted,
                text = if (state.scanCompleted && !state.ready) {
                    "扫描快照已就绪；清理时会自动恢复 Root 服务"
                } else {
                    state.serviceText
                }
            )
        }
        if (state.scanCompleted) {
            item { ScanResultCard(state, actions) }
        }
        item {
            Column(Modifier.padding(horizontal = BaiZeTokens.spacing.pageHorizontal, vertical = 5.dp)) {
                Text(
                    "QUICK ACTIONS",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Text("快捷操作", style = BaiZeTokens.type.headline)
                Text(
                    "完整清理类别、开关与周期统一放在“清理”页",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }
        item {
            GlassSurface(
                Modifier.padding(horizontal = BaiZeTokens.spacing.pageHorizontal).fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                shadow = 6,
                contentPadding = PaddingValues(10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MiuixHomeQuickAction(
                        icon = Icons.Rounded.InstallMobile,
                        title = "安装包",
                        modifier = Modifier.weight(1f),
                        onClick = actions.apkScan
                    )
                    MiuixHomeQuickAction(
                        icon = Icons.Rounded.CleaningServices,
                        title = "全部选项",
                        modifier = Modifier.weight(1f),
                        onClick = onOpenClean
                    )
                }
            }
        }
    }
}
@Composable
private fun MiuixHomeQuickAction(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(BaiZeTokens.corners.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = .12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Text(
            title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StorageRing(progress: Float) {
    val primary = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .10f)
    Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(trackColor, -90f, 360f, false, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
            drawArc(primary, -90f, 360f * progress, false, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
        }
        Text("${(progress * 100).roundToInt()}%", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun StatusPill(ready: Boolean, text: String, scanReady: Boolean = false) {
    GlassSurface(
        Modifier.padding(horizontal = BaiZeTokens.spacing.pageHorizontal).fillMaxWidth(),
        shape = RoundedCornerShape(percent = 50),
        shadow = 5,
        contentPadding = PaddingValues(horizontal = BaiZeTokens.spacing.pageHorizontal, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val positive = ready || scanReady
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (positive) BaiZeTokens.colors.success else BaiZeTokens.colors.warning))
            Spacer(Modifier.width(10.dp))
            Text(text, modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                when {
                    ready -> "运行正常"
                    scanReady -> "快照就绪"
                    else -> "未就绪"
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ScanResultCard(state: DashboardUiState, actions: DashboardActions) {
    val context = LocalContext.current
    GlassSurface(
        Modifier.padding(horizontal = BaiZeTokens.spacing.pageHorizontal).fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        shadow = 8,
        contentPadding = PaddingValues(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text("清理准备完成", style = BaiZeTokens.type.title, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            state.scanFiles <= 0 -> "没有发现可安全清理的内容"
                            state.scanBytes > 0 -> "发现 ${state.scanFiles} 项；已知至少 ${Formatter.formatFileSize(context, state.scanBytes)}"
                            else -> "发现 ${state.scanFiles} 项；大小按实际删除统计"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
            Spacer(Modifier.height(15.dp))
            Text(
                "候选 ${state.scanFiles} 项 · 空文件 ${state.scanEmptyFiles} · 空目录 ${state.scanEmptyDirs} · 碎片 ${state.scanFragments} · 异常 ${state.scanErrors} · ${formatElapsedUi(state.scanElapsed)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = actions.dismissScan,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(.07f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) { Text("关闭", fontWeight = FontWeight.Bold) }
                Button(
                    onClick = actions.cleanScan,
                    enabled = state.scanFiles > 0 && !state.running,
                    modifier = Modifier.weight(1.45f).height(52.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.scanFiles > 0) "按扫描结果清理" else "无需清理", fontWeight = FontWeight.Bold)
                }
            }
            Text(
                "点击后直接消费本次快照，不会重新扫描；删除前只复核路径、白名单、挂载点和文件状态。快照 30 分钟后自动失效。",
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
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
        RoundedCornerShape(24.dp)
    } else {
        RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    }
    val outerModifier = if (floating) {
        modifier
            .padding(horizontal = 14.dp)
            .padding(bottom = bottom + 10.dp)
            .fillMaxWidth()
    } else {
        modifier.fillMaxWidth()
    }

    Surface(
        modifier = outerModifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        shadowElevation = if (floating) 4.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 8.dp,
                    top = 6.dp,
                    end = 8.dp,
                    bottom = if (floating) 6.dp else bottom + 6.dp
                )
        ) {
            BaiZePage.entries.forEach { item ->
                val active = item == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSelected(item) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 42.dp, height = 28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (active) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.title,
                            modifier = Modifier.size(20.dp),
                            tint = if (active) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = item.title,
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

private fun formatElapsedUi(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600}时${seconds % 3600 / 60}分"
    seconds >= 60 -> "${seconds / 60}分${seconds % 60}秒"
    else -> "${seconds}秒"
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
    connected = connected, ready = ready, running = running, serviceText = serviceText,
    taskPhase = taskPhase, whitelistCount = whitelistCount, scanPerformance = scanPerformance
)
