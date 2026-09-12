package io.github.xgl34222220.baize.ui.logs.miuix

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.logs.LogLevel
import io.github.xgl34222220.baize.ui.logs.LogUiItem
import io.github.xgl34222220.baize.ui.logs.LogsUiActions
import io.github.xgl34222220.baize.ui.logs.LogsUiState
import io.github.xgl34222220.baize.ui.miuix.VideoCard
import io.github.xgl34222220.baize.ui.miuix.VideoDivider
import io.github.xgl34222220.baize.ui.miuix.VideoEmptyState
import io.github.xgl34222220.baize.ui.miuix.VideoIconButton
import io.github.xgl34222220.baize.ui.miuix.VideoListRow
import io.github.xgl34222220.baize.ui.miuix.VideoSectionTitle
import io.github.xgl34222220.baize.ui.miuix.VideoStatusPill
import io.github.xgl34222220.baize.ui.miuix.VideoTabs
import io.github.xgl34222220.baize.ui.miuix.VideoTopBar
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

@Composable
fun LogsScreenMiuix(state: LogsUiState, actions: LogsUiActions) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var onlyErrors by rememberSaveable { mutableStateOf(false) }
    var rawLinesToShow by rememberSaveable { mutableIntStateOf(80) }
    val visibleLogs = remember(state.logs, onlyErrors) { if (onlyErrors) state.logs.filter { it.level == LogLevel.ERROR || it.errors > 0 } else state.logs }
    val rawLines = remember(state.rawLog) { state.rawLog.lines() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            VideoTopBar("运行日志", "定位问题，了解任务执行情况", actions = {
                VideoIconButton(Icons.Rounded.Refresh, "刷新日志", actions.onRefresh)
                if (selectedTab == 0 && state.logs.isNotEmpty()) VideoIconButton(Icons.Rounded.DeleteOutline, "清空任务日志", actions.onClearTaskLogs)
                if (selectedTab == 1 && state.hasRawLog) VideoIconButton(Icons.Rounded.DeleteOutline, "清空原始输出", actions.onClearRawLog)
            })
        }
        item { RuntimeCard(state) }
        item { VideoTabs(listOf("任务日志", "原始输出"), selectedTab, { selectedTab = it }) }
        if (selectedTab == 0) {
            item {
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !onlyErrors, onClick = { onlyErrors = false }, label = { Text("全部 ${state.logs.size}") })
                    FilterChip(selected = onlyErrors, onClick = { onlyErrors = true }, label = { Text("异常 ${state.logs.count { it.level == LogLevel.ERROR || it.errors > 0 }}") })
                }
            }
            if (visibleLogs.isEmpty()) item {
                VideoEmptyState(Icons.Rounded.Description, if (onlyErrors) "没有异常任务" else "还没有任务日志",
                    if (onlyErrors) "当前记录中未发现报告错误的任务。" else "执行扫描或清理后，这里会显示结果与运行信息。", Modifier.padding(horizontal = 20.dp))
            } else items(visibleLogs, key = { it.key }) { LogCard(it) }
        } else {
            if (!state.hasRawLog) item {
                VideoEmptyState(Icons.Rounded.Description, "还没有原始输出", "执行一次模块任务后，这里会显示实际运行日志。", Modifier.padding(horizontal = 20.dp))
            } else {
                item { VideoSectionTitle(state.rawLogName.ifBlank { "最近任务输出" }, "长按可选择与复制 · 最近 ${minOf(rawLinesToShow, rawLines.size)} / ${rawLines.size} 行") }
                if (rawLines.size > rawLinesToShow) item {
                    TextButton(onClick = { rawLinesToShow = (rawLinesToShow + 120).coerceAtMost(rawLines.size) }, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                        Text("加载更早的 120 行")
                    }
                }
                items(rawLines.takeLast(rawLinesToShow).chunked(20)) { lines ->
                    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 16) {
                        SelectionContainer {
                            Text(lines.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item { VideoSectionTitle("诊断与恢复") }
        item {
            VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 0) {
                VideoListRow(Icons.Rounded.RestartAlt, "重新连接 Root 服务", "恢复连接并重新读取模块状态", onClick = actions.onReconnect)
                VideoDivider()
                VideoListRow(Icons.Rounded.Description, "清理明细", "查看最近任务的分类结果与保护项", onClick = actions.onOpenAudit)
                VideoDivider()
                VideoListRow(Icons.Rounded.BugReport, "崩溃诊断", "查看与清除 App 崩溃记录", onClick = actions.onOpenCrashDiagnostics)
            }
        }
    }
}

@Composable
private fun RuntimeCard(state: LogsUiState) {
    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 24) {
        VideoStatusPill(when { state.running -> "任务执行中"; state.ready && state.connected -> "服务已就绪"; state.connected -> "服务准备中"; else -> "服务待恢复" }, state.ready && state.connected)
        Spacer(Modifier.height(16.dp))
        Text("${state.device} · ${state.android}", fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp))
        RuntimeRow("服务", state.serviceText)
        Spacer(Modifier.height(10.dp))
        RuntimeRow("任务", state.taskPhase)
        Spacer(Modifier.height(10.dp))
        RuntimeRow("调度", state.schedulerText)
    }
}

@Composable
private fun RuntimeRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 13.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(44.dp))
        Text(value, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LogCard(item: LogUiItem) {
    val context = LocalContext.current
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val tint = when (item.level) {
        LogLevel.SUCCESS -> BaiZeTokens.colors.success
        LogLevel.WARNING -> BaiZeTokens.colors.warning
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
    }
    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable { expanded = !expanded }, contentPadding = 20) {
        Text("${item.time} · ${item.trigger}", fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Text(item.title, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(item.message, fontSize = 14.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = .07f)).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (item.errors > 0) "${item.errors} 个错误 · ${item.files} 项" else "${item.files} 项", modifier = Modifier.weight(1f), color = tint, fontSize = 13.sp)
            Spacer(Modifier.width(12.dp))
            Text(Formatter.formatFileSize(context, item.bytes), color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
