package io.github.xgl34222220.baize.ui.logs.miuix

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            VideoTopBar("运行日志", actions = {
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
                    LogFilter("全部 ${state.logs.size}", !onlyErrors) { onlyErrors = false }
                    LogFilter("异常 ${state.logs.count { it.level == LogLevel.ERROR || it.errors > 0 }}", onlyErrors) { onlyErrors = true }
                }
            }
            if (visibleLogs.isEmpty()) item {
                VideoEmptyState(Icons.Rounded.Description, if (onlyErrors) "没有异常任务" else "暂无任务日志",
                    if (onlyErrors) "当前记录中没有报告错误的任务。" else "执行任务后可在这里查看。", Modifier.padding(horizontal = 20.dp))
            } else itemsIndexed(visibleLogs, key = { _, item -> item.key }) { _, item -> LogCard(item) }
        } else {
            if (!state.hasRawLog) item {
                VideoEmptyState(Icons.Rounded.Description, "暂无原始输出", "执行模块任务后可在这里查看。", Modifier.padding(horizontal = 20.dp))
            } else {
                item { VideoSectionTitle(state.rawLogName.ifBlank { "最近任务输出" }, "长按可选择与复制 · 最近 ${minOf(rawLinesToShow, rawLines.size)} / ${rawLines.size} 行") }
                if (rawLines.size > rawLinesToShow) item {
                    TextButton(onClick = { rawLinesToShow = (rawLinesToShow + 120).coerceAtMost(rawLines.size) }, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                        Text("加载更早的 120 行")
                    }
                }
                items(rawLines.takeLast(rawLinesToShow).chunked(20)) { lines ->
                    SelectionContainer(Modifier.padding(horizontal = 24.dp).fillMaxWidth()) {
                        Text(lines.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    var expanded by rememberSaveable { mutableStateOf(false) }
    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 16) {
        Text("${state.device} · ${state.android}", fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            VideoStatusPill(when { state.running -> "执行中"; state.ready && state.connected -> "已就绪"; state.connected -> "准备中"; else -> "待恢复" }, state.ready && state.connected)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起状态" else "详细状态", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(4.dp))
        RuntimeRow("服务", state.serviceText)
        Spacer(Modifier.height(6.dp))
        RuntimeRow("任务", state.taskPhase)
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 6.dp)) { RuntimeRow("调度", state.schedulerText) }
        }
    }
}

@Composable
private fun LogFilter(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.semantics { this.selected = selected }) {
        Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RuntimeRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 12.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(36.dp))
        Text(value, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
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
    Column(Modifier.padding(horizontal = 24.dp).fillMaxWidth().drawBehind {
        drawCircle(tint, 3.dp.toPx(), Offset(3.dp.toPx(), 9.dp.toPx()))
        drawLine(tint.copy(alpha = .16f), Offset(3.dp.toPx(), 19.dp.toPx()), Offset(3.dp.toPx(), size.height - 4.dp.toPx()), 1.dp.toPx())
    }.clickable(role = Role.Button, onClickLabel = if (expanded) "收起日志" else "展开日志") { expanded = !expanded }
        .padding(start = 22.dp, bottom = 12.dp)) {
        Text("${item.time} · ${item.trigger}", fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(5.dp))
        Text(item.title, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Text(item.message, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Text(buildString {
            append(Formatter.formatFileSize(context, item.bytes))
            append(" · ${item.files} 项")
            if (item.errors > 0) append(" · ${item.errors} 个错误")
        }, color = tint, fontSize = 12.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
    }
}
