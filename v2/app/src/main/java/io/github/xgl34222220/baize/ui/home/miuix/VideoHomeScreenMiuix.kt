package io.github.xgl34222220.baize.ui.home.miuix

import android.text.format.Formatter
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.BuildConfig
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.home.homeTaskItems
import io.github.xgl34222220.baize.ui.home.nextTask
import io.github.xgl34222220.baize.ui.home.rememberHomeNowEpoch
import io.github.xgl34222220.baize.ui.home.taskCountdownLabel
import io.github.xgl34222220.baize.ui.miuix.VideoActionTile
import io.github.xgl34222220.baize.ui.miuix.VideoCard
import io.github.xgl34222220.baize.ui.miuix.VideoDivider
import io.github.xgl34222220.baize.ui.miuix.VideoIconButton
import io.github.xgl34222220.baize.ui.miuix.VideoListRow
import io.github.xgl34222220.baize.ui.miuix.VideoSectionTitle
import io.github.xgl34222220.baize.ui.miuix.VideoStatusPill
import io.github.xgl34222220.baize.ui.miuix.VideoTopBar
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/** One primary action, common tools, then storage and automation details. */
@Composable
fun VideoHomeScreenMiuix(
    state: DashboardUiState,
    scheduler: SchedulerUiState,
    actions: DashboardActions,
    onOpenClean: () -> Unit
) {
    val context = LocalContext.current
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val nowEpoch = rememberHomeNowEpoch()
    val nextTask = scheduler.homeTaskItems().nextTask(nowEpoch)
    val horizontal = Modifier.padding(horizontal = 20.dp).fillMaxWidth()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 108.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "header") {
            VideoTopBar("白泽", "让空间，回归有序", actions = {
                VideoIconButton(Icons.Rounded.Refresh, "刷新状态", actions.refresh)
            })
        }
        item(key = "hero") {
            HomeStatusHero(state, actions, horizontal)
        }
        item(key = "tools-heading") { VideoSectionTitle("常用工具") }
        item(key = "tools") {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VideoActionTile(Icons.Rounded.InstallMobile, "安装包", "查找与清理下载包",
                    actions.apkScan, Modifier.weight(1f))
                VideoActionTile(Icons.Rounded.FolderCopy, "文件归类", "预览整理，再决定",
                    actions.organize, Modifier.weight(1f))
            }
        }
        item(key = "storage-heading") { VideoSectionTitle("存储概览", state.device) }
        item(key = "storage") {
            VideoCard(horizontal, contentPadding = 20) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("可用空间", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                        Text(if (state.storageTotal > 0) Formatter.formatFileSize(context, state.storageFree) else "读取中",
                            style = MaterialTheme.typography.headlineMedium)
                    }
                    if (state.storageTotal > 0) Text("共 ${Formatter.formatFileSize(context, state.storageTotal)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 5.dp))
                }
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { state.storagePercent.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = BaiZeTokens.colors.surfaceOverlay
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    HomeMetric("累计释放", Formatter.formatFileSize(context, state.lifetimeReleased), Modifier.weight(1f))
                    HomeMetric("完成任务", "${state.lifetimeRuns} 次", Modifier.weight(1f))
                }
                if (state.lastTaskTime.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text("上次清理 ${state.lastTaskTime} · 释放 ${Formatter.formatFileSize(context, state.lastReleased)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item(key = "automation-heading") { VideoSectionTitle("自动清理") }
        item(key = "automation") {
            VideoCard(horizontal) {
                VideoListRow(Icons.Rounded.CalendarMonth, nextTask?.title ?: "清理计划",
                    if (scheduler.enabled) taskCountdownLabel(nextTask, nowEpoch, scheduler) else "自动任务已关闭",
                    value = "管理计划", onClick = onOpenClean)
                VideoDivider()
                VideoListRow(Icons.Rounded.Security, "服务状态", state.serviceText,
                    value = state.connectionLabel,
                    onClick = if (state.ready || state.running) null else actions.reconnect)
            }
        }
        item(key = "more-tools") {
            TextButton(onClick = onOpenClean, modifier = horizontal.heightIn(min = 48.dp)) {
                Text("查看全部清理工具", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun HomeStatusHero(state: DashboardUiState, actions: DashboardActions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val card = BaiZeTokens.colors.surfaceRaised
    val status = when {
        state.running -> "任务进行中"
        state.scanCompleted && state.scanErrors > 0 -> "扫描有异常"
        state.scanCompleted -> "扫描已完成"
        state.ready -> "模块已连接"
        state.connecting -> "正在连接"
        state.connectionFailed -> "连接失败"
        else -> "等待连接"
    }
    val title = when {
        state.running -> state.taskPhase.ifBlank { "正在处理文件" }
        state.scanCompleted && state.scanFiles > 0 -> if (state.scanBytes > 0) Formatter.formatFileSize(context, state.scanBytes) else "${state.scanFiles} 个文件"
        state.scanCompleted && state.scanErrors > 0 -> "部分位置未能扫描"
        state.scanCompleted -> "空间很清爽"
        !state.ready -> "连接清理服务"
        else -> "给手机，减减负"
    }
    val description = when {
        state.running -> state.taskProgressPath.ifBlank { state.taskOperation }.ifBlank { "正在准备清理任务" }
        state.scanCompleted && state.scanFiles > 0 -> "发现 ${state.scanFiles} 个待清理文件" +
            if (state.scanErrors > 0) " · ${state.scanErrors} 处扫描异常" else ""
        state.scanCompleted && state.scanErrors > 0 -> "${state.scanErrors} 处扫描异常，可查看记录了解原因后重试。"
        state.scanCompleted -> "本次扫描没有发现可清理文件"
        !state.ready -> state.serviceText
        else -> "扫描缓存、残留与垃圾文件，\n查看结果后，一次轻松清理。"
    }
    val primaryLabel = when {
        state.running -> "停止当前任务"
        state.scanCompleted && state.scanFiles > 0 -> "清理扫描结果"
        state.scanCompleted -> "重新扫描"
        state.connecting -> "正在连接…"
        !state.ready -> "连接 Root 服务"
        else -> "开始扫描"
    }
    val primaryAction = when {
        state.running -> actions.stop
        state.scanCompleted && state.scanFiles > 0 -> actions.cleanScan
        state.scanCompleted -> actions.scan
        !state.ready -> actions.reconnect
        else -> actions.scan
    }
    Surface(modifier, shape = RoundedCornerShape(28.dp), color = card, shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth()
            .background(Brush.linearGradient(listOf(scheme.primaryContainer.copy(alpha = .56f), card)))
            .padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VideoStatusPill(status, positive = !state.running &&
                    (if (state.scanCompleted) state.scanErrors == 0L else state.ready))
                Spacer(Modifier.weight(1f))
                Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.scanCompleted && state.scanFiles > 0) {
                    Text(if (state.scanBytes > 0) "本次可清理" else "本次待清理", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                Text(title, color = scheme.onSurface,
                    fontSize = if (state.scanCompleted && state.scanFiles > 0) 38.sp else 28.sp,
                    lineHeight = if (state.scanCompleted && state.scanFiles > 0) 44.sp else 38.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(description, style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (state.running) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.taskProgressTotal > 0) {
                        LinearProgressIndicator(
                            progress = { (state.taskProgressCurrent.toFloat() / state.taskProgressTotal).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                            color = scheme.primary, trackColor = scheme.primary.copy(alpha = .10f)
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                            color = scheme.primary, trackColor = scheme.primary.copy(alpha = .10f))
                    }
                    Text("已处理 ${state.taskProgressFiles} 个文件 · ${Formatter.formatFileSize(context, state.taskProgressBytes)}",
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = primaryAction,
                    enabled = state.running || !state.connecting || state.scanCompleted,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(18.dp)) {
                    Icon(when {
                        state.running -> Icons.Rounded.Stop
                        state.scanCompleted && state.scanFiles > 0 -> Icons.Rounded.CleaningServices
                        !state.ready && !state.scanCompleted -> Icons.Rounded.Security
                        else -> Icons.Rounded.Search
                    }, null, Modifier.size(21.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(primaryLabel, style = MaterialTheme.typography.labelLarge)
                }
                if (!state.running && (state.ready || state.scanCompleted)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = if (state.scanCompleted) actions.scan else actions.clean,
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (state.scanCompleted) "重新扫描" else "按现有规则清理", style = MaterialTheme.typography.labelMedium)
                        }
                        if (state.scanCompleted) {
                            TextButton(onClick = actions.dismissScan, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("收起结果", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
