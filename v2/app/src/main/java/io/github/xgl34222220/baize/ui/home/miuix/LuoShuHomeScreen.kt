package io.github.xgl34222220.baize.ui.home.miuix

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.BuildConfig
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.home.*
import io.github.xgl34222220.baize.ui.miuix.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlin.math.roundToInt

/** LuoShu HomeRoute -> HomeScreenCompact hierarchy, with BaiZe's real state and actions. */
@Composable
fun LuoShuHomeScreen(state: DashboardUiState, scheduler: SchedulerUiState, actions: DashboardActions,
    onOpenClean: () -> Unit, onOpenPlan: () -> Unit) {
    val context = LocalContext.current
    val now = rememberHomeNowEpoch()
    val next = scheduler.homeTaskItems().nextTask(now)
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottom + 118.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "header") {
            LuoShuPageHeader("白泽") {
                LuoShuHeaderButton(Icons.Rounded.Refresh, "刷新状态", actions.refresh)
            }
        }
        item(key = "space") { SpaceHero(state, actions) }
        // Keep the real plan reachable in the first regular-size viewport, not behind statistics.
        item(key = "plan") {
            LuoShuGroup {
                LuoShuNavigationRow(Icons.Rounded.CalendarMonth, "自动清理",
                    if (scheduler.enabled) taskCountdownLabel(next, now, scheduler) else "设置时间，让白泽按计划整理",
                    onOpenPlan)
            }
        }
        item(key = "shortcuts") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LuoShuSection("常用工具", "先看清文件，再决定清理哪些")
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth.value / LocalDensity.current.fontScale < 240f) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            LuoShuShortcut("安装包", "查找 · 选择 · 清理", Icons.Rounded.InstallMobile, actions.apkScan, Modifier.fillMaxWidth())
                            LuoShuShortcut("文件归类", "整理散落的文件", Icons.Rounded.FolderCopy, actions.organize, Modifier.fillMaxWidth())
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LuoShuShortcut("安装包", "查找 · 选择 · 清理", Icons.Rounded.InstallMobile, actions.apkScan, Modifier.weight(1f))
                        LuoShuShortcut("文件归类", "整理散落的文件", Icons.Rounded.FolderCopy, actions.organize, Modifier.weight(1f))
                    }
                }
                LuoShuGroup {
                    LuoShuNavigationRow(Icons.Rounded.CleaningServices, "深度清理", "查看应用占用、缓存与残留", actions.deep)
                    LuoShuGroupDivider()
                    LuoShuNavigationRow(Icons.Rounded.Shield, "白名单", "保留指定应用与路径", actions.whitelist)
                    LuoShuGroupDivider()
                    LuoShuNavigationRow(Icons.Rounded.Tune, "全部工具", "清理类别与自动计划", onOpenClean)
                }
            }
        }
        item(key = "history") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LuoShuSection("清理记录")
                LuoShuGroup {
                    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Metric("累计释放", Formatter.formatFileSize(context, state.lifetimeReleased), Modifier.weight(1f))
                        Metric("完成清理", "${state.lifetimeRuns} 次", Modifier.weight(1f))
                    }
                    if (state.lastTaskTime.isNotBlank()) Text(
                        "上次清理 ${state.lastTaskTime} · 释放 ${Formatter.formatFileSize(context, state.lastReleased)}",
                        Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!state.running && state.ready && !state.scanCompleted) item(key = "rule-clean") {
            TextButton(onClick = actions.clean, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("按现有规则清理")
            }
        }
    }
}

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
        !state.ready -> state.serviceText
        state.storageTotal > 0 -> "已用 ${Formatter.formatFileSize(context, state.storageUsed)} · 共 ${Formatter.formatFileSize(context, state.storageTotal)}"
        else -> "扫描缓存、安装包与应用残留"
    }
    val status = when {
        state.running -> "任务进行中"
        state.connectionFailed -> "连接异常"
        state.ready -> "服务已就绪"
        state.connecting -> "正在连接"
        else -> "等待连接"
    }
    val actionLabel = when {
        state.running -> "停止当前任务"
        hasResults -> "清理扫描结果"
        state.scanCompleted -> "重新扫描"
        state.connecting -> "正在连接…"
        !state.ready -> "连接 Root 服务"
        else -> "开始扫描"
    }
    val action = when {
        state.running -> actions.stop
        hasResults -> actions.cleanScan
        state.scanCompleted -> actions.scan
        !state.ready -> actions.reconnect
        else -> actions.scan
    }
    Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceRaised, shadowElevation = 2.dp) {
        Column(Modifier.fillMaxWidth()
            .background(Brush.linearGradient(listOf(scheme.primaryContainer.copy(alpha = .46f), colors.surfaceRaised)))
            .padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compact = maxWidth.value / LocalDensity.current.fontScale < 230f
                val badge: @Composable () -> Unit = {
                    Surface(shape = CircleShape, color = colors.surfaceRaised.copy(alpha = .72f)) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).background(if (state.ready) colors.success else colors.warning, CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(status, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (compact) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    badge()
                    Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    badge()
                    Spacer(Modifier.weight(1f))
                    Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Text(value, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.running && state.taskProgressTotal <= 0) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(5.dp))
                } else if (state.running || (!state.scanCompleted && state.storageTotal > 0)) {
                    LinearProgressIndicator(progress = { if (state.running) progress else state.storagePercent.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(5.dp))
                }
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = if (state.scanCompleted && state.scanErrors > 0) colors.warning else scheme.onSurfaceVariant)
            }
            Button(onClick = action, enabled = state.running || !state.connecting || state.scanCompleted,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(18.dp)) {
                Icon(when {
                    state.running -> Icons.Rounded.Stop
                    hasResults -> Icons.Rounded.CleaningServices
                    !state.ready && !state.scanCompleted -> Icons.Rounded.Security
                    else -> Icons.Rounded.Search
                }, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
            if (!state.running && state.scanCompleted) Column(Modifier.fillMaxWidth()) {
                if (hasResults) TextButton(actions.scan, Modifier.fillMaxWidth()) { Text("重新扫描") }
                TextButton(actions.dismissScan, Modifier.fillMaxWidth()) { Text("收起结果") }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}
