package io.github.xgl34222220.baize.ui.history.miuix

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            VideoTopBar("记录", "每一次清理，都有据可查", actions = {
                VideoIconButton(Icons.Rounded.Refresh, "刷新记录", actions.onRefresh)
                VideoIconButton(Icons.Rounded.DeleteOutline, "清空记录", actions.onClearHistory)
            })
        }
        item { LifetimeHero(state) }
        if (state.hasCurrentResult || state.latestResult.isNotBlank()) {
            item { VideoSectionTitle("最近一次") }
            item { CurrentResultCard(state) }
        }
        if (state.protectedItems.isNotEmpty()) {
            item {
                VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 0) {
                    VideoListRow(Icons.Rounded.Security, "已保留的内容", "白名单与保护规则命中的文件", value = "${state.protectedItems.size} 项", onClick = actions.onReviewProtected)
                }
            }
        }
        item { VideoTabs(listOf("任务时间线", "应用与文件"), selectedTab, { selectedTab = it }) }
        if (selectedTab == 0) {
            if (state.records.isEmpty()) item {
                VideoEmptyState(Icons.Rounded.History, "还没有清理记录", "完成扫描、清理或归类后，可在这里查看结果。", Modifier.padding(horizontal = 20.dp))
            } else {
                itemsIndexed(state.records, key = { index, record -> "${record.time}|${record.title}|$index" }) { _, record -> HistoryRecordCard(record) }
            }
        } else {
            if (state.recentApps.isEmpty() && state.recentJunk.isEmpty()) item {
                VideoEmptyState(Icons.Rounded.Folder, "还没有分类结果", "完成一次扫描或清理后，这里会按应用与文件类型汇总。", Modifier.padding(horizontal = 20.dp))
            }
            if (state.recentApps.isNotEmpty()) {
                item { VideoSectionTitle("按应用", "最近一次任务的分类明细") }
                itemsIndexed(state.recentApps, key = { index, app -> "app:${app.packageName}:$index" }) { _, app ->
                    AppResultRow(app.packageName, app.label.ifBlank { app.packageName }, app.category.ifBlank { "应用垃圾" }, app.bytes, app.files)
                }
            }
            if (state.recentJunk.isNotEmpty()) {
                item { VideoSectionTitle("其他文件") }
                itemsIndexed(state.recentJunk, key = { index, junk -> "junk:${junk.name}:$index" }) { _, junk ->
                    val context = LocalContext.current
                    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 0) {
                        VideoListRow(Icons.Rounded.Folder, junk.name, junk.samplePath.ifBlank { "未记录示例路径" }, value = "${Formatter.formatFileSize(context, junk.bytes)}\n${junk.files} 项")
                    }
                }
            }
        }
    }
}

@Composable
private fun LifetimeHero(state: HistoryUiState) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), containerColor = MaterialTheme.colorScheme.primaryContainer, contentPadding = 24) {
        Text("累计释放空间", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(10.dp))
        Text(Formatter.formatFileSize(context, state.lifetimeReleased), fontSize = 38.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(12.dp))
        Text("${state.lifetimeRuns} 次任务 · ${state.lifetimeFiles} 个文件", fontSize = 14.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .78f))
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { expanded = !expanded }.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("累计统计", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(if (expanded) "收起" else "展开", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VideoDivider(start = 0)
                StatisticRow("空文件", state.lifetimeEmptyFiles.toString())
                StatisticRow("空目录", state.lifetimeEmptyDirs.toString())
                StatisticRow("残留碎片", state.lifetimeFragments.toString())
                StatisticRow("总耗时", formatElapsed(state.lifetimeElapsed))
            }
        }
    }
}

@Composable
private fun StatisticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CurrentResultCard(state: HistoryUiState) {
    val context = LocalContext.current
    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 0) {
        VideoListRow(Icons.Rounded.CheckCircle, state.latestResult.ifBlank { "最近一次任务已完成" },
            "${state.lastTaskTime}\n${state.currentItemCount} 项内容",
            value = if (state.hasCurrentResult) Formatter.formatFileSize(context, state.currentBytes) else null)
    }
}

@Composable
private fun AppResultRow(packageName: String, label: String, subtitle: String, bytes: Long, files: Long) {
    val context = LocalContext.current
    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 20) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppPackageIcon(packageName = packageName, label = label, size = 44.dp, corner = 14.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$files 项", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text(Formatter.formatFileSize(context, bytes), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun HistoryRecordCard(record: HistoryUiItem) {
    val context = LocalContext.current
    var expanded by rememberSaveable(record.time, record.title) { mutableStateOf(false) }
    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable { expanded = !expanded }, contentPadding = 20) {
        Text("${record.time} · ${record.trigger}", fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(record.title, Modifier.weight(1f), fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            Text(Formatter.formatFileSize(context, record.bytes), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(10.dp))
        Text(record.result.ifBlank { "任务已完成" }, fontSize = 14.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        Text("${record.files} 项 · ${if (record.cleaned) "已清理" else "已记录"}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatElapsed(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    return when { seconds >= 3_600L -> "${seconds / 3_600L} 小时 ${seconds % 3_600L / 60L} 分钟"; seconds >= 60L -> "${seconds / 60L} 分钟 ${seconds % 60L} 秒"; else -> "$seconds 秒" }
}
