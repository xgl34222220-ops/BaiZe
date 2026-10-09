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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.StorageToolMode
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
    val motion = rememberMotionEnabled()
    LaunchedEffect(refreshing) {
        if (refreshing) {
            delay(if (motion) HOME_REFRESH_INDICATOR_MS else HOME_REFRESH_INDICATOR_REDUCED_MS)
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
                    val tools = homeTools(actions)
                    // 参考 SD Maid SE 仪表盘的工具卡与 Files by Google 清理建议卡：窄屏/大字号单列，常规两列，平板三列。
                    val width = maxWidth.value / LocalDensity.current.fontScale
                    val columns = when {
                        width < 240f -> 1
                        width < 560f -> 2
                        else -> 3
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        tools.chunked(columns).forEach { group ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                group.forEach { tool ->
                                    LuoShuShortcut(tool.title, tool.subtitle, tool.icon, tool.onClick, Modifier.weight(1f))
                                }
                                // 最后一行不满时保持与其他格同宽。
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
                        if (scheduler.enabled) taskCountdownLabel(next, now, scheduler) else "模块已安装 · 自动任务已暂停",
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
private const val HOME_REFRESH_INDICATOR_REDUCED_MS = 250L
private const val HERO_RING_MIN_WIDTH_DP = 280f

/** 首页工具格：顺序即优先级；副标题与入口一一对应，不再按标题字符串匹配。 */
@androidx.compose.runtime.Immutable
internal data class HomeTool(val title: String, val subtitle: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val onClick: () -> Unit)

internal fun homeTools(actions: DashboardActions): List<HomeTool> = listOf(
    HomeTool("照片瘦身", "预览后另存", Icons.Rounded.Photo, actions.photoCompression),
    HomeTool("重复文件", "保留一份", Icons.Rounded.ContentCopy, actions.duplicates),
    HomeTool("回收站", "恢复已移入文件", Icons.Rounded.RestoreFromTrash, actions.fileTrash),
    HomeTool("安装包", "下载遗留", Icons.Rounded.InstallMobile, actions.apkScan),
    HomeTool("大文件", "占用排行", Icons.Rounded.FolderOpen, actions.largeFiles),
    HomeTool("存储分析", "空间构成", Icons.Rounded.DataUsage, actions.storageAnalysis),
    HomeTool("滑动整理", "左删右留", Icons.Rounded.Swipe, actions.swipeReview),
    HomeTool("截图录屏", "旧截图与录屏", Icons.Rounded.Screenshot) { actions.storageReview(StorageToolMode.SCREENSHOTS) },
    HomeTool("旧下载", "久未动的下载", Icons.Rounded.Download) { actions.storageReview(StorageToolMode.OLD_DOWNLOADS) },
    HomeTool("聊天媒体", "微信/QQ 已存文件", Icons.Rounded.Forum) { actions.storageReview(StorageToolMode.CHAT_MEDIA) },
    HomeTool("自定义规则", "按路径筛选", Icons.Rounded.FilterAlt) { actions.storageReview(StorageToolMode.CUSTOM) }
)

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
                    // 连接 / 任务状态变化时由读屏播报，不抢焦点。
                    Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.labelMedium, color = statusColor)
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // 窄屏或大字号时数值优先，不让存储环挤压主数值（避免「98.…」截断）。
                val roomForRing = maxWidth.value / LocalDensity.current.fontScale >= HERO_RING_MIN_WIDTH_DP
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        HeroMetricValue(value)
                    }
                    // 空闲时用存储环概览占用（参考 HyperOS 手机管家 / Files by Google 清理页顶部）；运行中让位给进度条。
                    if (roomForRing && !state.running && state.storageTotal > 0) StorageRing(state)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.running && state.taskProgressTotal <= 0) {
                    BaiZeProgress()
                } else if (state.running) {
                    BaiZeProgress(progress = progress)
                } else if (state.storageTotal > 0) {
                    StorageLegendRow(state)
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

private fun DashboardUiState.ringCleanable(): Long =
    if (scanCompleted) scanBytes.coerceIn(0L, storageUsed.coerceAtLeast(0L)) else 0L

@Composable
private fun StorageRing(state: DashboardUiState) {
    val context = LocalContext.current
    val fractions = storageRingFractions(state.storageUsed, state.ringCleanable(), state.storageTotal)
    val percent = (fractions.used * 100).roundToInt().coerceIn(0, 100)
    val description = buildString {
        append("存储已用 $percent%，")
        append("已用 ${Formatter.formatFileSize(context, state.storageUsed)}，")
        append("共 ${Formatter.formatFileSize(context, state.storageTotal)}")
        if (state.ringCleanable() > 0L) append("，可清理 ${Formatter.formatFileSize(context, state.ringCleanable())}")
    }
    BaiZeStorageRing(state.storageUsed, state.ringCleanable(), state.storageTotal, description) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$percent%", style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("已用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StorageLegendRow(state: DashboardUiState) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        StorageLegend("已占用", scheme.onSurfaceVariant.copy(alpha = .58f))
        if (state.ringCleanable() > 0L) StorageLegend("可清理", scheme.primary)
        StorageLegend("可用", scheme.onSurfaceVariant.copy(alpha = .36f))
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
