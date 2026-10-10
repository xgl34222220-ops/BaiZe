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
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.home.*
import io.github.xgl34222220.baize.ui.miuix.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import io.github.xgl34222220.baize.ui.theme.BaiZeTone
import io.github.xgl34222220.baize.ui.theme.BaiZeTones
import io.github.xgl34222220.baize.StorageToolMode
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 132.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 顶部主色柔光 + 页头 + 一键扫描主卡，参考 HyperOS「清理存储」。
        item(key = "space") {
            Column(Modifier.fillMaxWidth().baiZeHeroWash().padding(horizontal = 16.dp)) {
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
                SpaceHero(state, actions)
            }
        }
        item(key = "shortcuts") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LuoShuSection("整理空间")
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val tools = homeTools(actions, onOpenPlan)
                    // 参考 SD Maid SE 仪表盘的工具卡与 Files by Google 清理建议卡：窄屏/大字号单列，常规两列，平板三列。
                    val width = maxWidth.value / LocalDensity.current.fontScale
                    val columns = when {
                        width < 240f -> 1
                        width < 560f -> 2
                        else -> 3
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        tools.chunked(columns).forEach { group ->
                            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                group.forEach { tool ->
                                    // 单列（窄屏 / 大字号）改用横向紧凑格，避免卡片过高。
                                    if (columns == 1) CompactTile(tool, Modifier.weight(1f).fillMaxHeight(), homeToolTone(tool.title))
                                    else LuoShuShortcut(tool.title, tool.subtitle, tool.icon, tool.onClick,
                                        Modifier.weight(1f).fillMaxHeight(), tone = homeToolTone(tool.title))
                                }
                                // 最后一行不满时保持与其他格同宽。
                                repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
        if (state.storageTotal > 0) item(key = "storage") {
            StorageSpaceCard(state, actions.storageAnalysis, Modifier.padding(horizontal = 16.dp))
        }
        item(key = "app-specific") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BaiZeCaption("应用专清")
                BaiZeCard {
                    BaiZeListRow("微信专清", { actions.storageView(StorageToolMode.CHAT_MEDIA) },
                        leading = { BaiZeTintedIcon(Icons.Rounded.ChatBubble, BaiZeTones.green) }, value = "前往清理")
                    BaiZeInsetDivider()
                    BaiZeListRow("QQ 专清", { actions.storageView(StorageToolMode.CHAT_MEDIA) },
                        leading = { BaiZeTintedIcon(Icons.Rounded.Forum, BaiZeTones.blue) }, value = "前往清理")
                }
            }
        }
        item(key = "recommended") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BaiZeCaption("推荐清理")
                BaiZeCard {
                    BaiZeListRow("大文件", actions.largeFiles, subtitle = "按大小排列，逐个确认",
                        leading = { BaiZeTintedIcon(Icons.Rounded.InsertDriveFile, BaiZeTones.red) })
                    BaiZeInsetDivider()
                    BaiZeListRow("重复文件", actions.duplicates, subtitle = "保留一份，其余可移入回收站",
                        leading = { BaiZeTintedIcon(Icons.Rounded.ContentCopy, BaiZeTones.blue) })
                    BaiZeInsetDivider()
                    BaiZeListRow("截图与录屏", { actions.storageView(StorageToolMode.SCREENSHOTS) }, subtitle = "30 天前的截图与录屏",
                        leading = { BaiZeTintedIcon(Icons.Rounded.Screenshot, BaiZeTones.green) })
                    BaiZeInsetDivider()
                    BaiZeListRow("旧下载", { actions.storageView(StorageToolMode.OLD_DOWNLOADS) }, subtitle = "下载目录中久未改动的文件",
                        leading = { BaiZeTintedIcon(Icons.Rounded.Download, BaiZeTones.orange) })
                }
            }
        }
        item(key = "more") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BaiZeCaption("更多清理")
                val more = listOf(
                    HomeTool("根目录整理", "空文件夹与卸载残留", Icons.Rounded.FolderSpecial) { actions.storageView(StorageToolMode.ROOT) },
                    HomeTool("安装包", "已安装或重复的 APK", Icons.Rounded.InstallMobile, actions.apkScan),
                    HomeTool("照片瘦身", "压缩大照片，保留原图可选", Icons.Rounded.PhotoSizeSelectLarge, actions.photoCompression),
                    HomeTool("滑动整理", "左右滑动快速取舍", Icons.Rounded.Swipe, actions.swipeReview)
                )
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // 窄屏或大字号时单列，避免标题被截断。
                    val columns = if (maxWidth.value / LocalDensity.current.fontScale < 300f) 1 else 2
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        more.chunked(columns).forEach { pair ->
                            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pair.forEach { tool ->
                                    CompactTile(tool, Modifier.weight(1f).fillMaxHeight())
                                }
                                repeat(columns - pair.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
        if (state.automationAvailable) item(key = "plan") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LuoShuSection("清理记录")
                LuoShuGroup {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Metric("累计释放", Formatter.formatFileSize(context, animatedBytes(state.lifetimeReleased)), Modifier.weight(1f))
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

private fun homeToolTone(title: String): BaiZeTone = when (title) {
    "存储分析" -> BaiZeTones.blue
    "自动任务" -> BaiZeTones.purple
    "规则与白名单" -> BaiZeTones.green
    else -> BaiZeTones.orange
}

/** 更多清理的紧凑两列格：图标在左，标题与一行说明在右。 */
@Composable
private fun CompactTile(tool: HomeTool, modifier: Modifier, toneOverride: BaiZeTone? = null) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val tone = toneOverride ?: when (tool.title) {
        "根目录整理" -> BaiZeTones.folder
        "安装包" -> BaiZeTones.green
        "照片瘦身" -> BaiZeTones.purple
        else -> BaiZeTones.teal
    }
    Row(modifier.baiZePress(interaction).clip(RoundedCornerShape(24.dp)).background(BaiZeTokens.colors.surfaceRaised)
        .clickable(interaction, ripple(), role = androidx.compose.ui.semantics.Role.Button, onClick = tool.onClick)
        .padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        BaiZeTintedIcon(tool.icon, tone, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(tool.title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(tool.subtitle, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 存储空间卡：已用 / 总量 + 分段占用条（已占用、本次可清理、可用）。 */
@Composable
private fun StorageSpaceCard(state: DashboardUiState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val fractions = storageRingFractions(state.storageUsed, state.ringCleanable(), state.storageTotal)
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    BaiZeCard(modifier.baiZePress(interaction)) {
        Column(Modifier.fillMaxWidth().clickable(interaction, ripple(), onClickLabel = "查看存储构成", onClick = onOpen)
            .padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("存储空间", Modifier.weight(1f), fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                Text("已用 ${Formatter.formatFileSize(context, state.storageUsed)} / ${Formatter.formatFileSize(context, state.storageTotal)}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), fontSize = 13.sp,
                    color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.4f, fill = false))
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant.copy(alpha = .55f))
            }
            BaiZeUsageBar(listOf(fractions.occupied to scheme.primary, fractions.cleanable to BaiZeTones.orange.color()))
            if (state.ringCleanable() > 0L) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(BaiZeTones.orange.color(), CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("本次可清理 ${Formatter.formatFileSize(context, state.ringCleanable())}", fontSize = 12.sp,
                    color = scheme.onSurfaceVariant)
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

/**
 * 统一信息架构：首页只保留「一键扫描」主入口（存储卡片）与 4 个次级入口。
 * 原来分散的大文件、重复文件、截图、聊天媒体、照片瘦身、滑动整理、安装包等都并入扫描结果的复核分类或存储分析视图，
 * 映射见 [io.github.xgl34222220.baize.LegacyEntryRedirects.HOME_TOOL_DESTINATIONS]。
 */
internal fun homeTools(actions: DashboardActions, onOpenPlan: () -> Unit = {}): List<HomeTool> = listOf(
    HomeTool("存储分析", "空间构成与 8 种视图", Icons.Rounded.DataUsage, actions.storageAnalysis),
    HomeTool("自动任务", "统一计划 · 系统维护", Icons.Rounded.CalendarMonth, onOpenPlan),
    HomeTool("规则与白名单", "保护应用与路径", Icons.Rounded.Shield, actions.whitelist),
    HomeTool("历史与回收站", "撤销已移入文件", Icons.Rounded.RestoreFromTrash, actions.fileTrash)
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
        hasResults && state.scanBytes > 0 -> Formatter.formatFileSize(context, animatedBytes(state.scanBytes))
        hasResults -> "${state.scanFiles} 项"
        state.scanCompleted && state.scanErrors > 0 -> "未完成"
        state.scanCompleted -> "已扫描"
        state.storageTotal > 0 -> Formatter.formatFileSize(context, animatedBytes(state.storageFree))
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
    val statusColor = when {
        state.connectionFailed -> scheme.error
        state.connecting -> scheme.onSurfaceVariant
        state.ready && !state.running && state.versionWarning.isBlank() -> colors.success
        else -> colors.warning
    }
    val fractions = storageRingFractions(state.storageUsed, state.ringCleanable(), state.storageTotal)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth.value / LocalDensity.current.fontScale < HERO_RING_MIN_WIDTH_DP
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.clip(CircleShape).background(statusColor.copy(alpha = .10f))
                        .padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (state.scanCompleted && state.scanErrors == 0L && !state.running) BaiZeSuccessMark(Modifier.size(16.dp))
                        else Box(Modifier.size(6.dp).background(statusColor, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        // 连接 / 任务状态变化时由读屏播报，不抢焦点。
                        Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.labelMedium, color = statusColor)
                    }
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    HeroMetricValue(value)
                }
                // 立体光泽环：空闲时显示存储占用，扫描/清理时显示进度，扫描完成且无可清理时显示对勾。
                if (state.storageTotal > 0 || state.running) {
                    val done = state.scanCompleted && !state.running && state.scanFiles == 0L && state.scanErrors == 0L
                    val ringProgress = when {
                        state.running && state.taskProgressTotal > 0 -> progress
                        state.running -> null
                        else -> fractions.used
                    }
                    val percent = ((ringProgress ?: 0f) * 100).roundToInt().coerceIn(0, 100)
                    BaiZeGlossyBadge(ringProgress, done, Modifier.semantics {
                        contentDescription = if (state.running) "任务进度 $percent%" else "存储已用 $percent%"
                    }, size = if (narrow) 84.dp else 108.dp) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (state.running && ringProgress == null) "…" else "$percent%",
                                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(if (state.running) "进度" else "已用", style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.running && state.taskProgressTotal <= 0) BaiZeProgress()
            else if (state.running) BaiZeProgress(progress = progress)
            if (state.running) BaiZePathText(description, live = true)
            else Text(description, style = MaterialTheme.typography.bodySmall,
                color = if (state.scanCompleted && state.scanErrors > 0) colors.warning else scheme.onSurfaceVariant)
        }
        if (state.versionWarning.isNotBlank()) {
            DetailExpandableText("版本信息需要检查", state.versionWarning, Modifier.padding(horizontal = 0.dp))
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

@Composable
private fun HeroMetricValue(value: String) = BaiZeMetric(value, large = true)

private fun DashboardUiState.ringCleanable(): Long =
    if (scanCompleted) scanBytes.coerceIn(0L, storageUsed.coerceAtLeast(0L)) else 0L

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold))
    }
}
