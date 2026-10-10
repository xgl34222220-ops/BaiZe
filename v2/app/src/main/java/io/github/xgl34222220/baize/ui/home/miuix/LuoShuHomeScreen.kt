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
    val now = rememberHomeNowEpoch()
    val next = scheduler.homeTaskItems().nextTask(now)
    val scanSummary = rememberHomeScanSummary()
    val trashSummary = rememberHomeTrashSummary()
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
        // 去重后的首页只保留状态与一个主动作：存储空间卡是「存储分析」的唯一入口，
        // 专项清理工具都在「清理」Tab，历史/回收站在「记录」Tab，规则与保护在「设置」。
        if (state.resumablePlan && !state.running) item(key = "resume") {
            ResumePlanBanner(actions.resumableScan, Modifier.padding(horizontal = 16.dp))
        }
        // 上次扫描：四个专项工具最近一次结果的只读摘要（从未扫描显示「未扫描」）。
        item(key = "last-scan") {
            HomeLastScanSection(scanSummary, now * 1000L,
                onChat = { actions.storageView(StorageToolMode.CHAT_MEDIA) },
                onApk = actions.apkScan, onLarge = actions.largeFiles, onDuplicates = actions.duplicates,
                onViewAll = onOpenClean, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (state.automationAvailable) item(key = "plan") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LuoShuSection("自动清理")
                LuoShuGroup {
                    LuoShuNavigationRow(
                        Icons.Rounded.CalendarMonth,
                        "自动清理模块",
                        if (scheduler.enabled) planSubtitle(state, next, now, scheduler) else "模块已安装 · 自动任务已暂停",
                        onOpenPlan
                    )
                }
            }
        }
        item(key = "trash") {
            HomeTrashCard(trashSummary, now * 1000L, actions.fileTrash, Modifier.padding(horizontal = 16.dp))
        }
    }
    }
}

/**
 * 自动清理卡副标题：能确定下次执行时间时显示「下次 <time> · 上次释放 <size>」；
 * 执行中、排队、计算中等状态沿用原倒计时文案（[taskCountdownLabel]）。
 */
@Composable
private fun planSubtitle(state: DashboardUiState, next: HomeTaskPresentation?, now: Long, scheduler: SchedulerUiState): String {
    val context = LocalContext.current
    val countdown = taskCountdownLabel(next, now, scheduler)
    val released = if (state.lastReleasedKnown && (state.lastReleased > 0L || state.history.isNotEmpty()))
        Formatter.formatFileSize(context, state.lastReleased) else null
    return io.github.xgl34222220.baize.HomePresentation.planSubtitle(next?.nextEpoch ?: 0L, now,
        showsCountdown = next != null && next.enabled && countdown.startsWith("还有"), lastReleased = released) ?: countdown
}

/** 只在存在未完成的续清计划时出现：打开续清页面，由该页面重新校验计划后才会继续。 */
@Composable
private fun ResumePlanBanner(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    LuoShuGroup(modifier) {
        LuoShuNavigationRow(Icons.Rounded.PlayArrow, "继续上次清理", "上次清理未完成，确认后继续剩余项目", onOpen)
    }
}

/**
 * Hero 内的紧凑存储条：存储只显示一次（不再另放「存储空间」卡）。整行可点，是「存储分析」的唯一入口。
 */
@Composable
private fun HeroStorageLink(state: DashboardUiState, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fractions = storageRingFractions(state.storageUsed, state.ringCleanable(), state.storageTotal)
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(Modifier.fillMaxWidth().baiZePress(interaction).clip(RoundedCornerShape(16.dp))
        .clickable(interaction, ripple(), onClickLabel = "查看存储构成", role = androidx.compose.ui.semantics.Role.Button, onClick = onOpen)
        .padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BaiZeUsageBar(listOf(fractions.occupied to scheme.primary, fractions.cleanable to BaiZeTones.orange.color()))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("存储分析", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = scheme.primary)
        }
    }
}

private const val HOME_REFRESH_INDICATOR_MS = 700L
private const val HOME_REFRESH_INDICATOR_REDUCED_MS = 250L
private const val HERO_RING_MIN_WIDTH_DP = 280f

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
        if (state.storageTotal > 0 && !state.running) HeroStorageLink(state, actions.storageAnalysis)
        if (state.versionWarning.isNotBlank()) {
            DetailExpandableText("版本信息需要检查", state.versionWarning, Modifier.padding(horizontal = 0.dp))
        }
        HomeScanButton(actionLabel, action, Modifier.fillMaxWidth(),
            enabled = state.running || !state.connecting || state.scanCompleted,
            running = state.running,
            runningLabel = when {
                state.taskOperation.contains("scan") -> "扫描中"
                state.taskOperation.isNotBlank() -> "清理中"
                else -> "处理中"
            },
            progress = if (state.running && state.taskProgressTotal > 0) progress else null,
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
