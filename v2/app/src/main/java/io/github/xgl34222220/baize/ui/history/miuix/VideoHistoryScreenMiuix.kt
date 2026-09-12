package io.github.xgl34222220.baize.ui.history.miuix

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.HistoryUiItem
import io.github.xgl34222220.baize.ui.common.AppPackageIcon
import io.github.xgl34222220.baize.ui.history.HistoryUiActions
import io.github.xgl34222220.baize.ui.history.HistoryUiState
import io.github.xgl34222220.baize.ui.miuix.VideoCard
import io.github.xgl34222220.baize.ui.miuix.VideoDivider
import io.github.xgl34222220.baize.ui.miuix.VideoEmptyState
import io.github.xgl34222220.baize.ui.miuix.VideoIconButton
import io.github.xgl34222220.baize.ui.miuix.VideoListRow
import io.github.xgl34222220.baize.ui.miuix.VideoSectionTitle
import io.github.xgl34222220.baize.ui.miuix.VideoTabs
import io.github.xgl34222220.baize.ui.miuix.VideoTopBar

@Composable
fun VideoHistoryScreenMiuix(state: HistoryUiState, actions: HistoryUiActions) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 112.dp)
    ) {
        item {
            VideoTopBar("记录", actions = {
                VideoIconButton(Icons.Rounded.Refresh, "刷新记录", actions.onRefresh)
                VideoIconButton(Icons.Rounded.DeleteOutline, "清空记录", actions.onClearHistory)
            })
        }
        item { LifetimeSummary(state) }
        if (state.hasCurrentResult || state.latestResult.isNotBlank()) {
            item { CurrentResultCard(state) }
        }
        if (state.protectedItems.isNotEmpty()) {
            item {
                VideoCard(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth()) {
                    VideoListRow(Icons.Rounded.Security, "已保留的内容", "白名单与保护规则", value = "${state.protectedItems.size} 项", onClick = actions.onReviewProtected)
                }
            }
        }
        item {
            VideoTabs(listOf("任务时间线", "应用与文件"), selectedTab, { selectedTab = it }, Modifier.padding(top = 18.dp, bottom = 20.dp))
        }
        if (selectedTab == 0) {
            if (state.records.isEmpty()) item {
                VideoEmptyState(Icons.Rounded.History, "暂无清理记录", "任务完成后会保存在这里。", Modifier.padding(horizontal = 20.dp))
            } else {
                itemsIndexed(state.records, key = { index, record -> "${record.time}|${record.title}|$index" }) { index, record ->
                    HistoryTimelineRow(record, index == 0, index == state.records.lastIndex)
                }
            }
        } else {
            if (state.recentApps.isEmpty() && state.recentJunk.isEmpty()) item {
                VideoEmptyState(Icons.Rounded.Folder, "暂无分类结果", "扫描或清理后可查看应用与文件明细。", Modifier.padding(horizontal = 20.dp))
            }
            if (state.recentApps.isNotEmpty()) {
                item { VideoSectionTitle("按应用", "最近一次任务") }
                itemsIndexed(state.recentApps, key = { index, app -> "app:${app.packageName}:$index" }) { _, app ->
                    AppResultRow(app.packageName, app.label.ifBlank { app.packageName }, app.category.ifBlank { "应用垃圾" }, app.bytes, app.files)
                }
            }
            if (state.recentJunk.isNotEmpty()) {
                item { VideoSectionTitle("其他文件", modifier = Modifier.padding(top = 16.dp)) }
                itemsIndexed(state.recentJunk, key = { index, junk -> "junk:${junk.name}:$index" }) { _, junk ->
                    val context = LocalContext.current
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        VideoListRow(Icons.Rounded.Folder, junk.name, junk.samplePath.ifBlank { "未记录示例路径" }, value = "${Formatter.formatFileSize(context, junk.bytes)}\n${junk.files} 项")
                        VideoDivider(start = 0)
                    }
                }
            }
        }
    }
}

@Composable
private fun LifetimeSummary(state: HistoryUiState) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("累计释放", Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (expanded) "收起统计" else "详细统计",
                Modifier.heightIn(min = 40.dp).clickable(role = Role.Button) { expanded = !expanded }.padding(vertical = 10.dp),
                fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        }
        Text(Formatter.formatFileSize(context, state.lifetimeReleased), fontSize = 36.sp, lineHeight = 43.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            SmallMetric("完成任务", state.lifetimeRuns.toString(), Modifier.weight(1f))
            SmallMetric("处理文件", state.lifetimeFiles.toString(), Modifier.weight(1f))
            SmallMetric("运行时长", formatElapsed(state.lifetimeElapsed), Modifier.weight(1f))
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VideoDivider(start = 0)
                StatisticRow("空文件", state.lifetimeEmptyFiles.toString())
                StatisticRow("空目录", state.lifetimeEmptyDirs.toString())
                StatisticRow("残留碎片", state.lifetimeFragments.toString())
            }
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun SmallMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatisticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CurrentResultCard(state: HistoryUiState) {
    val context = LocalContext.current
    VideoCard(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(), contentPadding = 16) {
        Text("最近一次 · ${state.lastTaskTime}", fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(state.latestResult.ifBlank { "最近一次任务已完成" }, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
        if (state.hasCurrentResult) {
            Spacer(Modifier.height(8.dp))
            Text("${Formatter.formatFileSize(context, state.currentBytes)} · ${state.currentItemCount} 项内容",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AppResultRow(packageName: String, label: String, subtitle: String, bytes: Long, files: Long) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 24.dp)) {
        Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppPackageIcon(packageName = packageName, label = label, size = 38.dp, corner = 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("$subtitle · $files 项", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                Text(Formatter.formatFileSize(context, bytes), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
        }
        VideoDivider(start = 50)
    }
}

@Composable
private fun HistoryTimelineRow(record: HistoryUiItem, first: Boolean, last: Boolean) {
    val context = LocalContext.current
    var expanded by rememberSaveable(record.time, record.title) { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().drawBehind {
        val x = 28.dp.toPx()
        val y = 10.dp.toPx()
        drawLine(accent.copy(alpha = .15f), Offset(x, if (first) y else 0f), Offset(x, if (last) y else size.height), 1.dp.toPx())
        drawCircle(accent.copy(alpha = .12f), 7.dp.toPx(), Offset(x, y))
        drawCircle(accent, 3.dp.toPx(), Offset(x, y))
    }.clickable(role = Role.Button, onClickLabel = if (expanded) "收起任务详情" else "展开任务详情") { expanded = !expanded }
        .padding(start = 48.dp, end = 24.dp, bottom = 24.dp)) {
        Text("${record.time} · ${record.trigger}", fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(5.dp))
        Text(record.title, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Text(record.result.ifBlank { "任务已完成" }, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Text("${Formatter.formatFileSize(context, record.bytes)} · ${record.files} 项 · ${if (record.cleaned) "已清理" else "已记录"}",
            fontSize = 12.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, color = accent)
    }
}

private fun formatElapsed(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    return when { seconds >= 3_600L -> "${seconds / 3_600L}h ${seconds % 3_600L / 60L}m"; seconds >= 60L -> "${seconds / 60L}m ${seconds % 60L}s"; else -> "${seconds}s" }
}
