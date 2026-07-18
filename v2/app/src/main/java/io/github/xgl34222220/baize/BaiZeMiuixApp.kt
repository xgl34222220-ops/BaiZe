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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
    val history: List<HistoryUiItem> = emptyList()
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
    val cleaned: Boolean
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
            maxFileMb = json.optInt("max_file_mb", 256).coerceIn(16, 2048)
        )
    }
}

private fun Boolean.flag() = if (this) 1 else 0

data class DashboardActions(
    val refresh: () -> Unit,
    val clean: () -> Unit,
    val scan: () -> Unit,
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
    Plan("计划", Icons.Rounded.CalendarMonth),
    Records("记录", Icons.Rounded.History),
    Settings("设置", Icons.Rounded.Settings)
}

@Composable
fun BaiZeMiuixApp(state: DashboardUiState, scheduler: SchedulerUiState, actions: DashboardActions) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (ThemeManager.currentMode(context)) {
        ThemeManager.MODE_LIGHT -> false
        ThemeManager.MODE_DARK -> true
        else -> systemDark
    }
    val resolvedPrimary = Color(MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, 0xFF3975F4.toInt()))
    val resolvedSecondary = Color(MaterialColors.getColor(context, com.google.android.material.R.attr.colorSecondary, 0xFF7658E8.toInt()))
    val resolvedTertiary = Color(MaterialColors.getColor(context, com.google.android.material.R.attr.colorTertiary, 0xFFFF91D0.toInt()))
    val resolvedSurface = Color(MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, if (dark) 0xFF191B24.toInt() else 0xFFFFFFFF.toInt()))
    val resolvedOnSurface = Color(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, if (dark) 0xFFF0F1F8.toInt() else 0xFF151722.toInt()))
    val resolvedOnSurfaceVariant = Color(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, if (dark) 0xFFBFC2D0.toInt() else 0xFF6D7080.toInt()))
    val colors = if (dark) {
        darkColorScheme(
            primary = resolvedPrimary,
            secondary = resolvedSecondary,
            tertiary = resolvedTertiary,
            background = if (ThemeManager.isAmoledEnabled(context)) Color.Black else Color(0xFF101117),
            surface = resolvedSurface,
            onSurface = resolvedOnSurface,
            onSurfaceVariant = resolvedOnSurfaceVariant
        )
    } else {
        lightColorScheme(
            primary = resolvedPrimary,
            secondary = resolvedSecondary,
            tertiary = resolvedTertiary,
            background = Color(0xFFF4F5FB),
            surface = resolvedSurface,
            onSurface = resolvedOnSurface,
            onSurfaceVariant = resolvedOnSurfaceVariant
        )
    }
    MaterialTheme(colorScheme = colors) {
        var page by rememberSaveable { mutableStateOf(BaiZePage.Home) }
        Box(modifier = Modifier.fillMaxSize()) {
            MiuiXBackdrop(dark)
            when (page) {
                BaiZePage.Home -> HomePage(state, actions)
                BaiZePage.Plan -> PlanPage(scheduler, actions)
                BaiZePage.Records -> RecordsPage(state, actions)
                BaiZePage.Settings -> SettingsPage(state, scheduler, actions)
            }
            FloatingDock(
                selected = page,
                onSelected = { page = it },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun MiuiXBackdrop(dark: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val base = if (dark) listOf(Color(0xFF101117), Color(0xFF151827), Color(0xFF101117))
    else listOf(Color(0xFFF8F7FF), Color(0xFFF0F5FF), Color(0xFFF8F8FC))
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(base))
            .drawBehind {
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
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val fill = if (dark) Color(0xFF252733).copy(alpha = .86f) else Color.White.copy(alpha = .80f)
    val border = if (dark) Color.White.copy(alpha = .09f) else Color.White.copy(alpha = .82f)
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
private fun HomePage(state: DashboardUiState, actions: DashboardActions) {
    val context = LocalContext.current
    val accentGradient = rememberAccentGradient()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 126.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item { PageHeader("SMART CLEAN", "白泽", "智能清理引擎 · Alpha 23", actions.refresh) }
        item {
            Box(
                Modifier
                    .padding(horizontal = 18.dp)
                    .fillMaxWidth()
                    .shadow(18.dp, RoundedCornerShape(36.dp))
                    .clip(RoundedCornerShape(36.dp))
                    .background(Brush.horizontalGradient(accentGradient))
                    .padding(24.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(11.dp).clip(CircleShape).background(if (state.ready) Color(0xFF83F0C0) else Color(0xFFFFD27D)))
                        Spacer(Modifier.width(8.dp))
                        Text(state.device, color = Color.White.copy(.88f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("  ·  ${state.android}", color = Color.White.copy(.65f), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(22.dp))
                    Text(if (state.running) "清理任务执行中" else "清理引擎已连接", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(7.dp))
                    Text(state.taskPhase, color = Color.White.copy(.72f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(22.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Column {
                            Text("最近一次释放", color = Color.White.copy(.62f), fontSize = 12.sp)
                            Text(Formatter.formatFileSize(context, state.lastReleased), color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        }
                        Icon(Icons.Rounded.CheckCircle, null, tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                }
            }
        }
        item {
            GlassSurface(
                modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
                contentPadding = PaddingValues(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StorageRing(state.storagePercent)
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text("可用空间", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        Text(Formatter.formatFileSize(context, state.storageFree), fontSize = 29.sp, fontWeight = FontWeight.Black)
                        Text(
                            "已用 ${Formatter.formatFileSize(context, state.storageUsed)} · 共 ${Formatter.formatFileSize(context, state.storageTotal)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
        item {
            Button(
                onClick = if (state.running) actions.stop else actions.clean,
                enabled = state.connected,
                modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(72.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(0.dp)
            ) {
                Row(
                    Modifier.fillMaxSize().background(
                        if (state.running) Brush.horizontalGradient(listOf(Color(0xFF6D7080), Color(0xFF4A4D5C)))
                        else Brush.horizontalGradient(accentGradient.take(2))
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(if (state.running) Icons.Rounded.Stop else Icons.Rounded.AutoAwesome, null, tint = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text(if (state.running) "安全停止任务" else "立即智能清理", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            StatusPill(state.ready, state.serviceText)
        }
        if (state.scanCompleted) {
            item { ScanResultCard(state, actions) }
        }
        item {
            Text("更多清理", modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp), fontSize = 25.sp, fontWeight = FontWeight.Black)
        }
        item {
            GlassSurface(Modifier.padding(horizontal = 18.dp).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp)) {
                Column {
                    ToolRow(Icons.Rounded.Search, "安全扫描", "只查找并统计垃圾，不删除；完成后可一键清理", actions.scan)
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.12f))
                    ToolRow(Icons.Rounded.DeleteSweep, "深度清理", "高风险规则先展示，再由你确认", actions.deep)
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.12f))
                    ToolRow(Icons.Rounded.FolderDelete, "卸载残留", "扫描 data / obb / media 无主目录", actions.corpses)
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.12f))
                    ToolRow(Icons.Rounded.Rule, "清理明细", "查看缓存、空项目、规则与碎片", actions.audit)
                }
            }
        }
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
private fun StatusPill(ready: Boolean, text: String) {
    GlassSurface(
        Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        shadow = 5,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (ready) SuccessGreen else Color(0xFFF2A93B)))
            Spacer(Modifier.width(10.dp))
            Text(text, modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (ready) "运行正常" else "待检查", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
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
                    Text("安全扫描完成", fontSize = 19.sp, fontWeight = FontWeight.Black)
                    Text(
                        if (state.scanBytes > 0) "发现 ${Formatter.formatFileSize(context, state.scanBytes)} 可清理内容"
                        else "没有发现可安全清理的内容",
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
                    onClick = actions.clean,
                    enabled = state.scanBytes > 0 && !state.running,
                    modifier = Modifier.weight(1.45f).height(52.dp),
                    shape = RoundedCornerShape(19.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.scanBytes > 0) "一键清理" else "无需清理", fontWeight = FontWeight.Bold)
                }
            }
            Text(
                "执行清理时会重新校验路径、白名单和文件状态，不会直接使用过期扫描列表。",
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
                    ScheduleRow("deep", "深度安全项", "可单独启用定时，仍受保护规则限制", config.deepEnabled, config.deepHours, expanded == "deep", {
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
private fun RecordsPage(state: DashboardUiState, actions: DashboardActions) {
    val context = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item { PageHeader("CLEAN HISTORY", "清理记录", "累计统计永久保存，任务明细保留最近 100 次", actions.refresh) }
        item {
            GlassSurface(Modifier.padding(horizontal = 18.dp).fillMaxWidth(), contentPadding = PaddingValues(22.dp)) {
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
            items(state.history, key = { "${it.time}-${it.title}-${it.bytes}" }) { item ->
                HistoryCard(item)
            }
        }
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
    GlassSurface(
        Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        shadow = 5,
        contentPadding = PaddingValues(17.dp)
    ) {
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
                Text("${item.files} 个项目 · 空目录 ${item.emptyDirs} · 异常 ${item.errors}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Formatter.formatFileSize(context, item.bytes), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(if (item.cleaned) "已清理" else "仅扫描", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text("清理保护", fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text(
                        if (state.whitelistCount > 0) "已保护 ${state.whitelistCount} 个应用" else "尚未添加应用白名单",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(15.dp))
                    PrimaryButton("管理应用白名单", true, actions.whitelist, outerPadding = false)
                    SettingSwitch("任务完成后发送通知", config.notifyOnComplete) { actions.updateScheduler(config.copy(notifyOnComplete = it)) }
                    SettingSwitch("没有垃圾时也发送通知", config.notifyZero) { actions.updateScheduler(config.copy(notifyZero = it)) }
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
private fun FloatingDock(selected: BaiZePage, onSelected: (BaiZePage) -> Unit, modifier: Modifier = Modifier) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    GlassSurface(
        modifier = modifier.padding(horizontal = 18.dp).padding(bottom = bottom + 10.dp).fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        shadow = 18,
        contentPadding = PaddingValues(7.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BaiZePage.entries.forEach { item ->
                val active = item == selected
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(24.dp))
                        .background(if (active) Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary.copy(.18f), MaterialTheme.colorScheme.secondary.copy(.15f))) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
                        .clickable { onSelected(item) }.padding(vertical = 11.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(item.icon, item.title, modifier = Modifier.size(21.dp), tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (active) {
                        Spacer(Modifier.width(7.dp))
                        Text(item.title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
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
