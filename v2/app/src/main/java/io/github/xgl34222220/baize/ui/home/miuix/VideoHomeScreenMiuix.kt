package io.github.xgl34222220.baize.ui.home.miuix

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.home.homeTaskItems
import io.github.xgl34222220.baize.ui.home.nextTask
import io.github.xgl34222220.baize.ui.home.rememberHomeNowEpoch
import io.github.xgl34222220.baize.ui.home.taskCountdownLabel
import io.github.xgl34222220.baize.ui.miuix.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlin.math.roundToInt

/** A primary scan action, a compact tool group, then the real automatic plan. */
@Composable
fun VideoHomeScreenMiuix(
    state: DashboardUiState,
    scheduler: SchedulerUiState,
    actions: DashboardActions,
    onOpenClean: () -> Unit,
    onOpenPlan: () -> Unit = onOpenClean
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val now = rememberHomeNowEpoch()
    val next = scheduler.homeTaskItems().nextTask(now)
    val inset = Modifier.padding(horizontal = BaiZeTokens.spacing.pageHorizontal).fillMaxWidth()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 118.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") {
            VideoTopBar("白泽", actions = {
                VideoIconButton(Icons.Rounded.Refresh, "刷新状态", actions.refresh)
            })
        }
        item(key = "space") { HomeSpaceCard(state, actions, inset) }
        item(key = "tools") {
            Column(inset, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("常用工具", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onOpenClean, modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(start = 10.dp)) {
                        Text("全部工具", style = MaterialTheme.typography.labelMedium)
                        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp))
                    }
                }
                val tools = listOf(
                    HomeTool(Icons.Rounded.InstallMobile, "安装包", "查找并清理安装文件", actions.apkScan),
                    HomeTool(Icons.Rounded.FolderCopy, "文件归类", "整理散落的文件", actions.organize),
                    HomeTool(Icons.Rounded.CleaningServices, "深度清理", "查看应用占用与残留", actions.deep),
                    HomeTool(Icons.Rounded.Shield, "白名单", "保留指定应用与路径", actions.whitelist)
                )
                VideoCard {
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(8.dp)) {
                        val columns = if (maxWidth.value / LocalDensity.current.fontScale >= 300f) 4 else 2
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            tools.chunked(columns).forEach { group ->
                                Row(Modifier.fillMaxWidth()) {
                                    group.forEach { tool -> HomeToolCell(tool, Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "activity") {
            Column(inset, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("自动清理与记录", Modifier.padding(top = 8.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.titleMedium)
                VideoCard {
                    VideoListRow(Icons.Rounded.CalendarMonth, "自动清理",
                        if (scheduler.enabled) taskCountdownLabel(next, now, scheduler) else "设置清理时间与保留规则",
                        value = if (scheduler.enabled) "已开启" else "未开启", onClick = onOpenPlan)
                    VideoDivider(start = 20)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        HomeMetric("累计释放", Formatter.formatFileSize(context, state.lifetimeReleased), Modifier.weight(1f))
                        HomeMetric("完成清理", "${state.lifetimeRuns} 次", Modifier.weight(1f))
                    }
                    if (state.lastTaskTime.isNotBlank()) {
                        Text("上次清理 ${state.lastTaskTime} · 释放 ${Formatter.formatFileSize(context, state.lastReleased)}",
                            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
        // Retain the existing rule-clean action, but do not compete with scan-first flow.
        if (!state.running && state.ready && !state.scanCompleted) {
            item(key = "rule-clean") {
                TextButton(onClick = actions.clean, modifier = inset.heightIn(min = 48.dp)) {
                    Text("按现有规则清理", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HomeSpaceCard(state: DashboardUiState, actions: DashboardActions, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val progress = if (state.taskProgressTotal > 0)
        (state.taskProgressCurrent.toFloat() / state.taskProgressTotal).coerceIn(0f, 1f) else 0f
    val value = when {
        state.running && state.taskProgressTotal > 0 -> "${(progress * 100).roundToInt()}%"
        state.running -> "处理中"
        state.scanCompleted && state.scanFiles > 0 && state.scanBytes > 0 -> Formatter.formatFileSize(context, state.scanBytes)
        state.scanCompleted && state.scanFiles > 0 -> "${state.scanFiles} 项"
        state.scanCompleted && state.scanErrors > 0 -> "未完成"
        state.scanCompleted -> "已扫描"
        state.storageTotal > 0 -> Formatter.formatFileSize(context, state.storageFree)
        else -> "—"
    }
    val label = when {
        state.running -> "当前任务"
        state.scanCompleted && state.scanFiles > 0 -> "本次可清理"
        state.scanCompleted && state.scanErrors > 0 -> "扫描有异常"
        state.scanCompleted -> "暂无待清理文件"
        else -> "可用空间"
    }
    val description = when {
        state.running -> state.taskPhase.ifBlank { "正在处理文件" }
        state.scanCompleted && state.scanFiles > 0 -> "发现 ${state.scanFiles} 个可清理文件" +
            if (state.scanErrors > 0) " · ${state.scanErrors} 处未完成" else ""
        state.scanCompleted && state.scanErrors > 0 -> "${state.scanErrors} 处扫描异常，请查看记录后重试"
        state.scanCompleted -> "本次扫描未发现可清理文件"
        !state.ready -> state.serviceText
        state.storageTotal > 0 -> "已用 ${Formatter.formatFileSize(context, state.storageUsed)} · 共 ${Formatter.formatFileSize(context, state.storageTotal)}"
        else -> "扫描缓存、安装包与应用残留"
    }
    val accent = if (state.scanCompleted && state.scanErrors > 0 && !state.running)
        BaiZeTokens.colors.warning else scheme.primary
    VideoCard(modifier, containerColor = lerp(BaiZeTokens.colors.surfaceRaised, scheme.primary, .035f)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(20.dp)) {
            val compact = maxWidth.value / LocalDensity.current.fontScale < 220f
            Column(Modifier.fillMaxWidth()) {
                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("空间概览", style = MaterialTheme.typography.titleMedium)
                        HomeServiceStatus(state)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("空间概览", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        HomeServiceStatus(state)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(value, style = BaiZeTokens.type.hero.copy(
                    fontSize = if (value.length > 9) 34.sp else 40.sp,
                    lineHeight = if (value.length > 9) 42.sp else 48.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = (-1.2).sp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    if (!state.running && !state.scanCompleted && state.storageTotal > 0) {
                        Text("${(state.storagePercent.coerceIn(0f, 1f) * 100).roundToInt()}% 已使用",
                            style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (state.running || (!state.scanCompleted && state.storageTotal > 0)) {
                    if (state.running && state.taskProgressTotal <= 0) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), color = accent,
                            trackColor = accent.copy(alpha = .10f))
                    } else {
                        LinearProgressIndicator(progress = { if (state.running) progress else state.storagePercent.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp), color = accent, trackColor = accent.copy(alpha = .10f))
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = if (state.scanCompleted && state.scanErrors > 0) accent else scheme.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))
                HomePrimaryAction(state, actions)
                if (!state.running && state.scanCompleted) {
                    if (compact) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        HomeSecondaryActions(state, actions)
                    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        HomeSecondaryActions(state, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeServiceStatus(state: DashboardUiState) {
    VideoStatusPill(when {
        state.running -> "任务进行中"
        state.connectionFailed -> "连接异常"
        state.ready -> "服务已就绪"
        state.connecting -> "正在连接"
        else -> "等待连接"
    }, positive = state.ready && !state.running && !state.connectionFailed)
}

@Composable
private fun HomePrimaryAction(state: DashboardUiState, actions: DashboardActions) {
    val label = when {
        state.running -> "停止当前任务"
        state.scanCompleted && state.scanFiles > 0 -> "清理扫描结果"
        state.scanCompleted -> "重新扫描"
        state.connecting -> "正在连接…"
        !state.ready -> "连接 Root 服务"
        else -> "开始扫描"
    }
    val action = when {
        state.running -> actions.stop
        state.scanCompleted && state.scanFiles > 0 -> actions.cleanScan
        state.scanCompleted -> actions.scan
        !state.ready -> actions.reconnect
        else -> actions.scan
    }
    GlassActionButton(label, action, Modifier.fillMaxWidth(), icon = when {
        state.running -> Icons.Rounded.Stop
        state.scanCompleted && state.scanFiles > 0 -> Icons.Rounded.CleaningServices
        !state.ready && !state.scanCompleted -> Icons.Rounded.Security
        else -> Icons.Rounded.Search
    }, enabled = state.running || !state.connecting || state.scanCompleted)
}

@Composable
private fun HomeSecondaryActions(state: DashboardUiState, actions: DashboardActions) {
    if (state.scanFiles > 0) TextButton(onClick = actions.scan, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("重新扫描", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    TextButton(onClick = actions.dismissScan, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("收起结果", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class HomeTool(val icon: ImageVector, val title: String, val description: String, val onClick: () -> Unit)

@Composable
private fun HomeToolCell(tool: HomeTool, modifier: Modifier) {
    val tint = MaterialTheme.colorScheme.primary
    Column(modifier.clip(RoundedCornerShape(18.dp))
        .clickable(role = Role.Button, onClickLabel = tool.description, onClick = tool.onClick)
        .padding(horizontal = 4.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = .12f), tint.copy(alpha = .04f)))),
            contentAlignment = Alignment.Center) {
            Icon(tool.icon, null, Modifier.size(22.dp), tint = tint)
        }
        Text(tool.title, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HomeMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, style = BaiZeTokens.type.display.copy(fontSize = 23.sp, lineHeight = 29.sp),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
