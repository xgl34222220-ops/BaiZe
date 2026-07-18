package io.github.xgl34222220.baize

import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.clean.CleanRoute
import io.github.xgl34222220.baize.ui.home.HomeRoute
import io.github.xgl34222220.baize.ui.history.HistoryRoute
import io.github.xgl34222220.baize.ui.miuix.MiuixLiquidDock
import io.github.xgl34222220.baize.ui.miuix.MiuixLiquidNavItem
import io.github.xgl34222220.baize.ui.miuix.MiuixLiquidPrimaryButton
import io.github.xgl34222220.baize.ui.miuix.MiuixOverviewHero
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import org.json.JSONObject
import kotlin.math.roundToInt

private val SuccessGreen = Color(0xFF2DBE87)

data class DashboardUiState(
    val connected: Boolean = false,
    val ready: Boolean = false,
    val running: Boolean = false,
    val serviceText: String = "正在等待 Root 服务…",
    val taskPhase: String = "等待下一次清理",
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
    val history: List<HistoryUiItem> = emptyList()
)

data class AppJunkUiItem(
    val packageName: String,
    val label: String,
    val category: String,
    val files: Long,
    val bytes: Long,
    val errors: Long = 0,
    val categories: List<AppJunkCategoryUiItem> = emptyList()
)

data class AppJunkCategoryUiItem(
    val name: String,
    val files: Long,
    val bytes: Long,
    val errors: Long,
    val samplePath: String
)

data class GeneralJunkUiItem(
    val name: String,
    val files: Long,
    val bytes: Long,
    val errors: Long,
    val samplePath: String
)

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

data class HistoryCategoryUiItem(
    val name: String,
    val bytes: Long,
    val files: Long
)

data class HistoryAppUiItem(
    val packageName: String,
    val label: String,
    val category: String,
    val bytes: Long,
    val files: Long
)

data class SchedulerUiState(
    val enabled: Boolean = true,
    val cacheEnabled: Boolean = true,
    val cacheHours: Int = 1,
    val emptyEnabled: Boolean = true,
    val emptyHours: Int = 1,
    val rulesEnabled: Boolean = true,
    val rulesHours: Int = 6,
    val fragmentEnabled: Boolean = true,
    val fragmentHours: Int = 12,
    val deepEnabled: Boolean = false,
    val deepHours: Int = 168,
    val screenOffOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val idleOnly: Boolean = false,
    val minBattery: Int = 25,
    val notifyOnComplete: Boolean = true,
    val notifyZero: Boolean = false,
    val maxFileMb: Int = 256,
    val apkPackagesEnabled: Boolean = true,
    val apkPackageDays: Int = 30,
    val saving: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled.flag())
        .put("schedule_cache_enabled", cacheEnabled.flag())
        .put("schedule_cache_hours", cacheHours.coerceIn(1, 720))
        .put("schedule_empty_enabled", emptyEnabled.flag())
        .put("schedule_empty_hours", emptyHours.coerceIn(1, 720))
        .put("schedule_rules_enabled", rulesEnabled.flag())
        .put("schedule_rules_hours", rulesHours.coerceIn(1, 720))
        .put("schedule_fragment_enabled", fragmentEnabled.flag())
        .put("schedule_fragment_hours", fragmentHours.coerceIn(1, 720))
        .put("schedule_deep_enabled", deepEnabled.flag())
        .put("schedule_deep_hours", deepHours.coerceIn(1, 720))
        .put("screen_off_only", screenOffOnly.flag())
        .put("charging_only", chargingOnly.flag())
        .put("device_idle_only", idleOnly.flag())
        .put("min_battery", minBattery.coerceIn(0, 100))
        .put("notify_on_complete", notifyOnComplete.flag())
        .put("notify_zero_result", notifyZero.flag())
        .put("max_file_mb", maxFileMb.coerceIn(16, 2048))
        .put("clean_apk_packages", apkPackagesEnabled.flag())
        .put("apk_package_days", apkPackageDays.coerceIn(0, 365))

    companion object {
        fun fromJson(json: JSONObject) = SchedulerUiState(
            enabled = json.optInt("enabled", 1) == 1,
            cacheEnabled = json.optInt("schedule_cache_enabled", 1) == 1,
            cacheHours = json.optInt("schedule_cache_hours", 1).coerceIn(1, 720),
            emptyEnabled = json.optInt("schedule_empty_enabled", 1) == 1,
            emptyHours = json.optInt("schedule_empty_hours", 1).coerceIn(1, 720),
            rulesEnabled = json.optInt("schedule_rules_enabled", 1) == 1,
            rulesHours = json.optInt("schedule_rules_hours", 6).coerceIn(1, 720),
            fragmentEnabled = json.optInt("schedule_fragment_enabled", 1) == 1,
            fragmentHours = json.optInt("schedule_fragment_hours", 12).coerceIn(1, 720),
            deepEnabled = json.optInt("schedule_deep_enabled", 0) == 1,
            deepHours = json.optInt("schedule_deep_hours", 168).coerceIn(1, 720),
            screenOffOnly = json.optInt("screen_off_only", 1) == 1,
            chargingOnly = json.optInt("charging_only", 0) == 1,
            idleOnly = json.optInt("device_idle_only", 0) == 1,
            minBattery = json.optInt("min_battery", 25).coerceIn(0, 100),
            notifyOnComplete = json.optInt("notify_on_complete", 1) == 1,
            notifyZero = json.optInt("notify_zero_result", 0) == 1,
            maxFileMb = json.optInt("max_file_mb", 256).coerceIn(16, 2048),
            apkPackagesEnabled = json.optInt("clean_apk_packages", 1) == 1,
            apkPackageDays = json.optInt("apk_package_days", 30).coerceIn(0, 365)
        )
    }
}

private fun Boolean.flag() = if (this) 1 else 0

data class DashboardActions(
    val refresh: () -> Unit,
    val clean: () -> Unit,
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
    val clearHistory: () -> Unit,
    val whitelist: () -> Unit,
    val theme: () -> Unit,
    val reconnect: () -> Unit,
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
            var page by rememberSaveable { mutableStateOf(BaiZePage.Home) }
            val miuixNavItems = remember {
                BaiZePage.entries.map { MiuixLiquidNavItem(it.title, it.icon) }
            }

            when (appearance.uiStyle) {
                UiStyle.MATERIAL -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    when (page) {
                        BaiZePage.Home -> HomeRoute(UiStyle.MATERIAL, state, actions) { page = BaiZePage.Clean }
                        BaiZePage.Clean -> CleanRoute(UiStyle.MATERIAL, state, scheduler, actions)
                        BaiZePage.Records -> HistoryRoute(UiStyle.MATERIAL, state, actions)
                        BaiZePage.Settings -> SettingsPage(state, scheduler, actions)
                    }
                    MaterialFloatingDock(
                        selected = page,
                        onSelected = { page = it },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }

                UiStyle.MIUIX -> Box(modifier = Modifier.fillMaxSize()) {
                    MiuiXBackdrop(dark, amoled)
                    when (page) {
                        BaiZePage.Home -> HomeRoute(UiStyle.MIUIX, state, actions) { page = BaiZePage.Clean }
                        BaiZePage.Clean -> CleanRoute(UiStyle.MIUIX, state, scheduler, actions)
                        BaiZePage.Records -> HistoryRoute(UiStyle.MIUIX, state, actions)
                        BaiZePage.Settings -> SettingsPage(state, scheduler, actions)
                    }
                    MiuixLiquidDock(
                        selectedIndex = page.ordinal,
                        items = miuixNavItems,
                        onSelected = { index -> page = BaiZePage.entries[index] },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }
}

@Composable
private fun MiuiXBackdrop(dark: Boolean, amoled: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val base = when {
        amoled -> listOf(Color.Black, Color.Black, Color.Black)
        dark -> listOf(Color(0xFF101117), Color(0xFF151827), Color(0xFF101117))
        else -> listOf(Color(0xFFF8F7FF), Color(0xFFF0F5FF), Color(0xFFF8F8FC))
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(base))
            .drawBehind {
                if (amoled) return@drawBehind
                drawRect(
                    Brush.radialGradient(
                        listOf(scheme.secondary.copy(alpha = if (dark) .15f else .24f), Color.Transparent),
                        center = Offset(size.width * .9f, size.height * .06f),
                        radius = size.width * .72f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(scheme.primary.copy(alpha = if (dark) .12f else .18f), Color.Transparent),
                        center = Offset(size.width * .02f, size.height * .54f),
                        radius = size.width * .82f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(scheme.tertiary.copy(alpha = if (dark) .06f else .12f), Color.Transparent),
                        center = Offset(size.width, size.height),
                        radius = size.width * .72f
                    )
                )
            }
    )
}

@Composable
private fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(30.dp),
    shadow: Int = 10,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val amoled = dark && ThemeManager.isAmoledEnabled(context)
    val glass = ThemeManager.isGlassEnabled(context)
    val fill = when {
        amoled -> Color(0xFF080808)
        dark && glass -> Color(0xFF1B1D25)
        dark -> MaterialTheme.colorScheme.surface
        glass -> Color(0xFFF9F9FD)
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
    shape: RoundedCornerShape = RoundedCornerShape(26.dp),
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
            .statusBarsPadding()
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow.uppercase(), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.height(5.dp))
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        if (refresh != null) {
            GlassSurface(shape = RoundedCornerShape(18.dp), shadow = 6) {
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
                "Miuix × Liquid Glass · Alpha 37",
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
                modifier = Modifier.padding(horizontal = 18.dp)
            )
        }
        item {
            GlassSurface(
                modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                shadow = 6,
                contentPadding = PaddingValues(18.dp)
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
                            fontSize = 31.sp,
                            lineHeight = 35.sp,
                            fontWeight = FontWeight.Black
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
                modifier = Modifier.padding(horizontal = 18.dp)
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
            Column(Modifier.padding(horizontal = 22.dp, vertical = 5.dp)) {
                Text(
                    "QUICK ACTIONS",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Text("快捷操作", fontSize = 27.sp, fontWeight = FontWeight.Black)
                Text(
                    "完整清理类别、开关与周期统一放在“清理”页",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }
        item {
            GlassSurface(
                Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                shadow = 6,
                contentPadding = PaddingValues(10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MiuixHomeQuickAction(
                        icon = Icons.Rounded.Search,
                        title = "垃圾扫描",
                        modifier = Modifier.weight(1f),
                        onClick = actions.scan
                    )
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
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(16.dp))
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
    Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(Color(0xFFDDE4F1), -90f, 360f, false, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
            drawArc(primary, -90f, 360f * progress, false, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
        }
        Text("${(progress * 100).roundToInt()}%", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun StatusPill(ready: Boolean, text: String, scanReady: Boolean = false) {
    GlassSurface(
        Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        shadow = 5,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val positive = ready || scanReady
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (positive) SuccessGreen else Color(0xFFF2A93B)))
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
        Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        shadow = 8,
        contentPadding = PaddingValues(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text("垃圾扫描完成", fontSize = 19.sp, fontWeight = FontWeight.Black)
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
                    shape = RoundedCornerShape(19.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(.07f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) { Text("关闭", fontWeight = FontWeight.Bold) }
                Button(
                    onClick = actions.cleanScan,
                    enabled = state.scanFiles > 0 && !state.running,
                    modifier = Modifier.weight(1.45f).height(52.dp),
                    shape = RoundedCornerShape(19.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.scanFiles > 0) "按扫描结果清理" else "无需清理", fontWeight = FontWeight.Bold)
                }
            }
            Text(
                "点击后直接消费本次快照，不会重新扫描；删除前只复核路径、白名单、挂载点和文件状态。快照 30 分钟后自动失效。",
                modifier = Modifier.padding(top = 11.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun ToolRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.primary.copy(.10f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlanPage(config: SchedulerUiState, actions: DashboardActions) {
    var expanded by rememberSaveable { mutableStateOf("cache") }
    val accentGradient = rememberAccentGradient()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 132.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item { PageHeader("AUTOMATION", "自动清理计划", "每类任务独立定时，最短支持 1 小时") }
        item {
            Box(
                Modifier.padding(horizontal = 18.dp).fillMaxWidth().clip(RoundedCornerShape(32.dp))
                    .background(Brush.horizontalGradient(accentGradient)).padding(22.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("启用自动清理", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Black)
                            Text(
                                if (config.enabled) "已启用 ${enabledScheduleCount(config)} 类独立任务" else "总开关已关闭",
                                color = Color.White.copy(.70f), fontSize = 12.sp
                            )
                        }
                        Switch(checked = config.enabled, onCheckedChange = { actions.updateScheduler(config.copy(enabled = it)) })
                    }
                }
            }
        }
        item { SectionTitle("独立定时", "点开一项再调整周期，避免页面堆满滑杆") }
        item {
            GlassSurface(Modifier.padding(horizontal = 18.dp).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp)) {
                Column {
                    ScheduleRow("cache", "应用缓存", "安全缓存与临时文件", config.cacheEnabled, config.cacheHours, expanded == "cache", {
                        actions.updateScheduler(config.copy(cacheEnabled = it))
                    }, {
                        actions.updateScheduler(config.copy(cacheHours = it))
                    }) { expanded = if (expanded == "cache") "" else "cache" }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.10f))
                    ScheduleRow("empty", "空文件与空目录", "保持公共存储目录整洁", config.emptyEnabled, config.emptyHours, expanded == "empty", {
                        actions.updateScheduler(config.copy(emptyEnabled = it))
                    }, {
                        actions.updateScheduler(config.copy(emptyHours = it))
                    }) { expanded = if (expanded == "empty") "" else "empty" }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.10f))
                    ScheduleRow("rules", "规则垃圾与日志", "规则库命中与过期日志", config.rulesEnabled, config.rulesHours, expanded == "rules", {
                        actions.updateScheduler(config.copy(rulesEnabled = it))
                    }, {
                        actions.updateScheduler(config.copy(rulesHours = it))
                    }) { expanded = if (expanded == "rules") "" else "rules" }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.10f))
                    ScheduleRow("fragment", "残留碎片", "下载碎片、缩略图和离线残留", config.fragmentEnabled, config.fragmentHours, expanded == "fragment", {
                        actions.updateScheduler(config.copy(fragmentEnabled = it))
                    }, {
                        actions.updateScheduler(config.copy(fragmentHours = it))
                    }) { expanded = if (expanded == "fragment") "" else "fragment" }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.10f))
                    ScheduleRow("deep", "深度清理项", "可单独启用定时，仍受保护规则限制", config.deepEnabled, config.deepHours, expanded == "deep", {
                        actions.updateScheduler(config.copy(deepEnabled = it))
                    }, {
                        actions.updateScheduler(config.copy(deepHours = it))
                    }) { expanded = if (expanded == "deep") "" else "deep" }
                }
            }
        }
        item { SectionTitle("执行条件", "降低前台卡顿和意外耗电") }
        item {
            GlassSurface(Modifier.padding(horizontal = 18.dp).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                Column {
                    SettingSwitch("等待息屏后执行", config.screenOffOnly) { actions.updateScheduler(config.copy(screenOffOnly = it)) }
                    SettingSwitch("仅在充电时执行", config.chargingOnly) { actions.updateScheduler(config.copy(chargingOnly = it)) }
                    SettingSwitch("仅在设备空闲时执行", config.idleOnly) { actions.updateScheduler(config.copy(idleOnly = it)) }
                    Text("最低电量 ${config.minBattery}%", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                    Slider(value = config.minBattery.toFloat(), onValueChange = { actions.updateScheduler(config.copy(minBattery = (it / 5).roundToInt() * 5)) }, valueRange = 0f..100f, steps = 19)
                }
            }
        }
        item {
            PrimaryButton(
                text = if (config.saving) "正在保存…" else "保存自动清理计划",
                enabled = !config.saving,
                onClick = { actions.saveScheduler(config) }
            )
        }
    }
}

@Composable
private fun ScheduleRow(
    key: String,
    title: String,
    subtitle: String,
    enabled: Boolean,
    hours: Int,
    expanded: Boolean,
    onEnabled: (Boolean) -> Unit,
    onHours: (Int) -> Unit,
    onExpand: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onExpand)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("$hours 小时", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.width(9.dp))
            Switch(checked = enabled, onCheckedChange = onEnabled)
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(1, 6, 12, 24).forEach { quick ->
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp))
                            .background(if (hours == quick) MaterialTheme.colorScheme.primary.copy(.18f) else MaterialTheme.colorScheme.onSurface.copy(.05f))
                            .clickable { onHours(quick) }.padding(horizontal = 12.dp, vertical = 7.dp)
                    ) { Text("${quick}h", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (hours == quick) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Slider(value = hours.toFloat(), onValueChange = { onHours(it.roundToInt().coerceIn(1, 720)) }, valueRange = 1f..720f)
            Text("可设 1–720 小时；拖动用于长周期，常用周期可直接点选。", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LegacyRecordsPage(state: DashboardUiState, actions: DashboardActions) {
    val context = LocalContext.current
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 128.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item { PageHeader("CLEAN HISTORY", "清理记录", "累计统计永久保存，任务明细保留最近 100 次", actions.refresh) }
        if (state.recentApps.isNotEmpty() || state.recentJunk.isNotEmpty()) {
            item {
                ResultSurface(
                    Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    contentPadding = PaddingValues(20.dp)
                ) {
                    CurrentCleanupSummaryContent(state.recentApps, state.recentJunk)
                }
            }
        }
        item {
            ResultSurface(Modifier.padding(horizontal = 18.dp).fillMaxWidth(), contentPadding = PaddingValues(22.dp)) {
                Column {
                    Text(if (state.history.isEmpty()) "等待第一条清理记录" else state.history.first().result, fontSize = 21.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        StatColumn("${state.lifetimeRuns} 次", "累计清理")
                        StatColumn(Formatter.formatFileSize(context, state.lifetimeReleased), "累计释放")
                        StatColumn(formatElapsedUi(state.lifetimeElapsed), "累计耗时")
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "文件 ${state.lifetimeFiles} · 空文件 ${state.lifetimeEmptyFiles} · 空目录 ${state.lifetimeEmptyDirs} · 碎片 ${state.lifetimeFragments}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
                    )
                }
            }
        }
        if (state.recentApps.isNotEmpty()) {
            item { SectionTitle("应用垃圾", "按实际结果从大到小排列，点击卡片查看分类") }
            items(state.recentApps.indices.toList(), key = { index -> "app-$index-${state.recentApps[index].packageName}" }) { index ->
                AppJunkCard(state.recentApps[index])
            }
        }
        if (state.recentJunk.isNotEmpty()) {
            item { SectionTitle("其他垃圾", "安装包、日志、临时文件与碎片") }
            items(state.recentJunk.indices.toList(), key = { index -> "junk-$index-${state.recentJunk[index].name}" }) { index ->
                GeneralJunkCard(state.recentJunk[index])
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("最近任务", modifier = Modifier.weight(1f), fontSize = 25.sp, fontWeight = FontWeight.Black)
                Text("清空", modifier = Modifier.clickable(onClick = actions.clearHistory).padding(10.dp), color = Color(0xFFC43743), fontWeight = FontWeight.Bold)
            }
        }
        if (state.history.isEmpty()) {
            item {
                GlassSurface(Modifier.padding(horizontal = 18.dp).fillMaxWidth(), contentPadding = PaddingValues(24.dp)) {
                    Text("完成一次手动或自动清理后，这里会显示释放空间、文件数量、空目录、异常和清理时间。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(state.history.indices.toList(), key = { index -> "history-$index" }) { index ->
                HistoryCard(state.history[index])
            }
        }
    }
}

@Composable
private fun AppJunkCard(item: AppJunkUiItem) {
    ResultSurface(
        Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(17.dp)
    ) {
        AppJunkCardContent(item)
    }
}

@Composable
private fun GeneralJunkCard(item: GeneralJunkUiItem) {
    ResultSurface(
        Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(17.dp)
    ) {
        GeneralJunkCardContent(item)
    }
}

@Composable
private fun StatColumn(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = MaterialTheme.colorScheme.primary, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    }
}

@Composable
private fun HistoryCard(item: HistoryUiItem) {
    val context = LocalContext.current
    var expanded by rememberSaveable(item.time, item.title) { mutableStateOf(false) }
    val hasDetails = item.categories.isNotEmpty() || item.apps.isNotEmpty()
    val categorySummary = item.categories.take(3).joinToString(" · ") {
        "${it.name} ${Formatter.formatFileSize(context, it.bytes)}"
    }
    val appSummary = item.apps.take(2).joinToString(" · ") {
        "${it.label} ${Formatter.formatFileSize(context, it.bytes)}"
    }
    ResultSurface(
        Modifier.padding(horizontal = 18.dp).fillMaxWidth()
            .clickable(enabled = hasDetails) { expanded = !expanded },
        shape = RoundedCornerShape(26.dp),
        contentPadding = PaddingValues(17.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(50.dp).clip(RoundedCornerShape(16.dp)).background(if (item.errors > 0) Color(0xFFFFC8C8).copy(.45f) else MaterialTheme.colorScheme.primary.copy(.13f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (item.errors > 0) Icons.Rounded.ErrorOutline else Icons.Rounded.CleaningServices, null, tint = if (item.errors > 0) Color(0xFFC43743) else MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${item.time} · ${item.trigger}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    Text(
                        when {
                            categorySummary.isNotBlank() -> categorySummary
                            item.bytes == 0L && item.files == 0 -> if (item.cleaned) "未发现可清理内容" else "扫描未发现垃圾"
                            else -> item.result
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = if (expanded) 4 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (appSummary.isNotBlank()) {
                        Text(appSummary, color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(Formatter.formatFileSize(context, item.bytes), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            item.errors > 0 -> "异常 ${item.errors}"
                            item.cleaned && item.bytes > 0 -> "已清理"
                            item.cleaned -> "无垃圾"
                            item.files > 0 -> "发现 ${item.files} 项"
                            else -> "未发现"
                        },
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (expanded && hasDetails) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(.08f))
                if (item.categories.isNotEmpty()) {
                    Text("垃圾分类", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    item.categories.forEach { detail ->
                        Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(detail.name, modifier = Modifier.weight(1f), fontSize = 11.sp)
                            Text("${detail.files} 项 · ${Formatter.formatFileSize(context, detail.bytes)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (item.apps.isNotEmpty()) {
                    Text("涉及应用", modifier = Modifier.padding(top = 12.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    item.apps.forEach { app ->
                        Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text(app.category.ifBlank { app.packageName }, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("${app.files} 项 · ${Formatter.formatFileSize(context, app.bytes)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(state: DashboardUiState, config: SchedulerUiState, actions: DashboardActions) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        PageHeader("PREFERENCES", "偏好设置", "外观、清理保护与服务管理")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 132.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            GlassSurface(Modifier.padding(horizontal = 18.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
                Column {
                    Text("外观", fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text(ThemeManager.themeSummary(context), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Spacer(Modifier.height(15.dp))
                    OutlineAction(Icons.Rounded.Palette, "主题模式、配色与玻璃", actions.theme)
                }
            }
            GlassSurface(Modifier.padding(horizontal = 18.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
                Column {
                    Text("清理范围", fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text(
                        if (state.whitelistCount > 0) "已保护 ${state.whitelistCount} 个应用" else "尚未添加应用白名单",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(15.dp))
                    PrimaryButton("管理应用白名单", true, actions.whitelist, outerPadding = false)
                    SettingSwitch("任务完成后发送通知", config.notifyOnComplete) { actions.updateScheduler(config.copy(notifyOnComplete = it)) }
                    SettingSwitch("没有垃圾时也发送通知", config.notifyZero) { actions.updateScheduler(config.copy(notifyZero = it)) }
                    SettingSwitch("清理过期 APK 安装包", config.apkPackagesEnabled) { actions.updateScheduler(config.copy(apkPackagesEnabled = it)) }
                    Text("APK 安装包保留 ${config.apkPackageDays} 天", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    Text("扫描 Download、QQ、微信及常见浏览器下载目录中的 APK/APKS/XAPK。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    Slider(value = config.apkPackageDays.toFloat(), onValueChange = { actions.updateScheduler(config.copy(apkPackageDays = it.roundToInt().coerceIn(0, 365))) }, valueRange = 0f..365f)
                    Text("单文件上限 ${config.maxFileMb} MB", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                    Text("超过上限的单个文件只统计，不会自动删除。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    Slider(value = config.maxFileMb.toFloat(), onValueChange = { actions.updateScheduler(config.copy(maxFileMb = (it / 16).roundToInt() * 16)) }, valueRange = 16f..2048f)
                    PrimaryButton(if (config.saving) "正在保存…" else "保存保护设置", !config.saving, { actions.saveScheduler(config) }, outerPadding = false)
                }
            }
            GlassSurface(Modifier.padding(horizontal = 18.dp).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp)) {
                Column {
                    Text("服务与诊断", modifier = Modifier.padding(top = 20.dp), fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text(state.serviceText, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    ToolRow(Icons.Rounded.Refresh, "重新连接 Root 服务", state.schedulerText, actions.reconnect)
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.10f))
                    ToolRow(Icons.Rounded.BugReport, "崩溃诊断", "查看或清除最近 App 崩溃记录", actions.crash)
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 3.dp)) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
private fun OutlineAction(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(.7f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(9.dp))
        Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit, outerPadding: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = (if (outerPadding) Modifier.padding(horizontal = 18.dp) else Modifier).fillMaxWidth().height(58.dp),
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun MaterialFloatingDock(
    selected: BaiZePage,
    onSelected: (BaiZePage) -> Unit,
    modifier: Modifier = Modifier
) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Surface(
        modifier = modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = bottom + 12.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .96f),
        tonalElevation = 10.dp,
        shadowElevation = 18.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            BaiZePage.entries.forEach { item ->
                val active = item == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.large)
                        .clickable { onSelected(item) }
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 48.dp, height = 30.dp)
                            .clip(MaterialTheme.shapes.extraLarge)
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
                        fontSize = 10.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

private fun enabledScheduleCount(config: SchedulerUiState): Int = listOf(
    config.cacheEnabled, config.emptyEnabled, config.rulesEnabled, config.fragmentEnabled, config.deepEnabled
).count { it }

@Composable
private fun rememberAccentGradient(): List<Color> {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    return remember(scheme.primary, scheme.secondary, scheme.tertiary, dark) {
        listOf(
            if (dark) scheme.primary.darken(.58f) else scheme.primary,
            if (dark) scheme.secondary.darken(.58f) else scheme.secondary,
            if (dark) scheme.tertiary.darken(.62f) else scheme.tertiary
        )
    }
}

private fun Color.darken(factor: Float): Color = Color(
    red = red * factor,
    green = green * factor,
    blue = blue * factor,
    alpha = alpha
)

private fun formatElapsedUi(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600}时${seconds % 3600 / 60}分"
    seconds >= 60 -> "${seconds / 60}分${seconds % 60}秒"
    else -> "${seconds}秒"
}
