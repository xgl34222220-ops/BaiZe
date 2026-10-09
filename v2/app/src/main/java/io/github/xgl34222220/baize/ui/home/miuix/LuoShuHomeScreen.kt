package io.github.xgl34222220.baize.ui.home.miuix

import io.github.xgl34222220.baize.ui.components.*
import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.home.*
import io.github.xgl34222220.baize.ui.miuix.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** LuoShu HomeRoute -> HomeScreenCompact hierarchy, with BaiZe's real state and actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuoShuHomeScreen(state: DashboardUiState, scheduler: SchedulerUiState, actions: DashboardActions,
    onOpenClean: () -> Unit, onOpenPlan: () -> Unit) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val now = rememberHomeNowEpoch()
    val next = scheduler.homeTaskItems().nextTask(now)
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Pull down to refresh, as in MIUIX PullToRefresh: same action as the "刷新状态" menu item.
    var refreshing by remember { mutableStateOf(false) }
    val pullState = rememberPullToRefreshState()
    LaunchedEffect(refreshing) {
        if (refreshing) {
            delay(HOME_REFRESH_INDICATOR_MS)
            refreshing = false
        }
    }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { refreshing = true; actions.refresh() },
        modifier = Modifier.fillMaxSize(),
        state = pullState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                containerColor = BaiZeTokens.colors.surfaceRaised,
                color = MaterialTheme.colorScheme.primary
            )
        }
    ) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottom + 132.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "header") {
            LuoShuPageHeader("白泽") {
                Box {
                    LuoShuHeaderButton(Icons.Rounded.MoreVert, "更多操作", { menuOpen = true })
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("刷新状态") }, onClick = { menuOpen = false; actions.refresh() })
                        if (!state.running && state.scanCompleted) {
                            if (state.scanFiles > 0) DropdownMenuItem(text = { Text("重新扫描") }, onClick = { menuOpen = false; actions.scan() })
                            DropdownMenuItem(text = { Text("收起结果") }, onClick = { menuOpen = false; actions.dismissScan() })
                        }
                    }
                }
            }
        }
        item(key = "space") { SpaceHero(state, actions) }
        item(key = "shortcuts") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LuoShuSection("整理空间", "按文件类型，快速找到需要处理的内容")
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val tools = listOf(
                        Triple("照片瘦身", Icons.Rounded.Photo, actions.photoCompression),
                        Triple("重复文件", Icons.Rounded.ContentCopy, actions.duplicates),
                        Triple("回收站", Icons.Rounded.RestoreFromTrash, actions.fileTrash),
                        Triple("安装包", Icons.Rounded.InstallMobile, actions.apkScan),
                        Triple("大文件", Icons.Rounded.FolderOpen, actions.largeFiles),
                        Triple("存储分析", Icons.Rounded.DataUsage, actions.storageAnalysis),
                        Triple("滑动整理", Icons.Rounded.Swipe, actions.swipeReview)
                    )
                    val columns = if (maxWidth.value / LocalDensity.current.fontScale < 240f) 1 else 2
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        tools.chunked(columns).forEach { group ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                group.forEach { (label, icon, action) ->
                                    LuoShuShortcut(label, when (label) {
                                        "照片瘦身" -> "预览后另存"; "回收站" -> "恢复已移入文件";
                                        "安装包" -> "下载遗留"; "大文件" -> "占用排行"; "重复文件" -> "保留一份";
                                        "滑动整理" -> "左删右留"; else -> "空间构成"
                                    }, icon, action, Modifier.weight(1f))
                                }
                                // 奇数个工具时保持最后一格与其他格同宽。
                                repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
        if (state.automationAvailable) item(key = "plan") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LuoShuSection("自动清理")
                LuoShuGroup {
                    LuoShuNavigationRow(
                        Icons.Rounded.CalendarMonth,
                        "自动清理模块",
                        if (state.automationAvailable) {
                            if (scheduler.enabled) taskCountdownLabel(next, now, scheduler) else "模块已安装 · 自动任务已暂停"
                        } else "安装模块后可定时自动清理",
                        onOpenPlan
                    )
                }
            }
        }
        item(key = "history") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LuoShuSection("清理记录")
                LuoShuGroup {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Metric("累计释放", Formatter.formatFileSize(context, state.lifetimeReleased), Modifier.weight(1f))
                        Metric("完成清理", "${state.lifetimeRuns} 次", Modifier.weight(1f))
                    }
                    if (state.lastTaskTime.isNotBlank()) Text(
                        "上次清理 ${state.lastTaskTime} · " + if (state.lastReleasedKnown)
                            "确认删除 ${Formatter.formatFileSize(context, state.lastReleased)} 内容" else "释放量未完整确认",
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    }
}

private const val HOME_REFRESH_INDICATOR_MS = 700L

@Composable
private fun SpaceHero(state: DashboardUiState, actions: DashboardActions) {
    val scheme = MaterialTheme.colorScheme
    val colors = BaiZeTokens.colors
    val context = LocalContext.current
    val progress = if (state.taskProgressTotal > 0)
        (state.taskProgressCurrent.toFloat() / state.taskProgressTotal).coerceIn(0f, 1f) else 0f
    val hasResults = state.scanCompleted && state.scanFiles > 0
    val value = when {
        state.running && state.taskProgressTotal > 0 -> "${(progress * 100).roundToInt()}%"
        state.running -> "处理中"
        hasResults && state.scanBytes > 0 -> Formatter.formatFileSize(context, state.scanBytes)
        hasResults -> "${state.scanFiles} 项"
        state.scanCompleted && state.scanErrors > 0 -> "未完成"
        state.scanCompleted -> "已扫描"
        state.storageTotal > 0 -> Formatter.formatFileSize(context, state.storageFree)
        else -> "—"
    }
    val label = when {
        state.running -> "当前任务"
        hasResults -> "本次可清理"
        state.scanCompleted && state.scanErrors > 0 -> "扫描有异常"
        state.scanCompleted -> "暂无待清理文件"
        else -> "可用空间"
    }
    val description = when {
        state.running -> state.taskPhase.ifBlank { "正在处理文件" }
        hasResults -> "发现 ${state.scanFiles} 个可清理文件" + if (state.scanErrors > 0) " · ${state.scanErrors} 处未完成" else ""
        state.scanCompleted && state.scanErrors > 0 -> "${state.scanErrors} 处扫描异常，请查看记录后重试"
        state.scanCompleted -> "本次扫描未发现可清理文件"
        state.connectionFailed -> state.serviceText
        state.storageTotal > 0 -> "已用 ${Formatter.formatFileSize(context, state.storageUsed)} · 共 ${Formatter.formatFileSize(context, state.storageTotal)}"
        else -> "扫描缓存、安装包与应用残留"
    }
    val status = when {
        state.running -> "任务进行中"
        state.connectionFailed -> "连接异常"
        state.connecting -> "正在连接"
        state.versionWarning.isNotBlank() -> "版本需检查"
        state.ready -> "服务已就绪"
        else -> "等待连接"
    }
    val actionLabel = when {
        state.running -> "停止当前任务"
        hasResults -> "清理扫描结果"
        state.scanCompleted -> "重新扫描"
        state.connectionFailed -> "重试连接"
        state.connecting -> "开始扫描"
        !state.ready -> "连接 Root 服务"
        else -> "开始扫描"
    }
    val action = when {
        state.running -> actions.stop
        hasResults -> actions.cleanScan
        state.scanCompleted -> actions.scan
        state.connectionFailed || !state.ready -> actions.reconnect
        else -> actions.scan
    }
    Surface(modifier = Modifier.glassSurface(colors.surfaceRaised, RoundedCornerShape(16.dp), colors.surfaceRaised.luminance() < .3f),
        shape = RoundedCornerShape(16.dp), color = Color.Transparent) {
        Column(Modifier.fillMaxWidth()
            .background(Brush.linearGradient(listOf(scheme.primaryContainer.copy(alpha = .30f), colors.surfaceRaised)))

            .padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            val statusColor = when {
                state.connectionFailed -> scheme.error
                state.connecting -> scheme.onSurfaceVariant
                state.ready && !state.running && state.versionWarning.isBlank() -> colors.success
                else -> colors.warning
            }
            Surface(
                shape = CircleShape,
                color = statusColor.copy(alpha = .055f)
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.scanCompleted && state.scanErrors == 0L && !state.running) BaiZeSuccessMark()
                    else Box(Modifier.size(6.dp).background(statusColor, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(status, style = MaterialTheme.typography.labelMedium, color = statusColor)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                HeroMetricValue(value)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.running && state.taskProgressTotal <= 0) {
                    BaiZeProgress()
                } else if (state.running) {
                    BaiZeProgress(progress = progress)
                } else if (state.storageTotal > 0) {
                    StorageSegments(state)
                }
                if (state.running) BaiZePathText(description, live = true)
                else Text(description, style = MaterialTheme.typography.bodySmall,
                    color = if (state.scanCompleted && state.scanErrors > 0) colors.warning else scheme.onSurfaceVariant)
            }
            if (state.versionWarning.isNotBlank()) {
                DetailExpandableText("版本信息需要检查", state.versionWarning)
            }
            GlassActionButton(actionLabel, action, Modifier.fillMaxWidth(),
                enabled = state.running || !state.connecting || state.scanCompleted,
                icon = when {
                    state.running -> Icons.Rounded.Stop
                    hasResults -> Icons.Rounded.CleaningServices
                    (state.connectionFailed || !state.ready) && !state.scanCompleted -> Icons.Rounded.Security
                    else -> Icons.Rounded.Search
                })

        }
    }
}

@Composable
private fun HeroMetricValue(value: String) = BaiZeMetric(value, large = true)

@Composable
private fun StorageSegments(state: DashboardUiState) {
    val scheme = MaterialTheme.colorScheme
    val colors = BaiZeTokens.colors
    val total = state.storageTotal.coerceAtLeast(1L)
    val cleanable = if (state.scanCompleted) state.scanBytes.coerceIn(0L, state.storageUsed.coerceAtLeast(0L)) else 0L
    val occupied = (state.storageUsed - cleanable).coerceAtLeast(0L)
    val free = state.storageFree.coerceAtLeast(0L)
    val occupiedWeight = (occupied.toFloat() / total).coerceAtLeast(.0001f)
    val cleanableWeight = (cleanable.toFloat() / total).coerceAtLeast(.0001f)
    val freeWeight = (free.toFloat() / total).coerceAtLeast(.0001f)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (occupied > 0L) Box(
                Modifier.weight(occupiedWeight).fillMaxHeight()
                    .background(scheme.onSurfaceVariant.copy(alpha = .22f))
            )
            if (cleanable > 0L) Box(
                Modifier.weight(cleanableWeight).fillMaxHeight()
                    .background(scheme.primary.copy(alpha = .72f))
            )
            if (free > 0L) Box(
                Modifier.weight(freeWeight).fillMaxHeight()
                    .background(colors.surfaceOverlay)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StorageLegend("已占用", scheme.onSurfaceVariant.copy(alpha = .58f))
            if (cleanable > 0L) StorageLegend("可清理", scheme.primary)
            StorageLegend("可用", scheme.onSurfaceVariant.copy(alpha = .36f))
        }
    }
}

@Composable
private fun StorageLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
    }
}
