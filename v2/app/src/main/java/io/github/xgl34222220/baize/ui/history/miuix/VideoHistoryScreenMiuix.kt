package io.github.xgl34222220.baize.ui.history.miuix

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.HistoryUiItem
import io.github.xgl34222220.baize.ui.common.AppPackageIcon
import io.github.xgl34222220.baize.ui.components.DetailStatusText
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
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

@Composable
fun VideoHistoryScreenMiuix(state: HistoryUiState, actions: HistoryUiActions) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            VideoTopBar("记录", actions = {
                VideoIconButton(Icons.Rounded.Refresh, "刷新记录", actions.onRefresh)
                VideoIconButton(Icons.Rounded.DeleteOutline, "清空记录", actions.onClearHistory)
            })
        }
        item { LifetimeSummary(state) }
        if (state.hasCurrentResult || state.latestResult.isNotBlank() || state.protectedItems.isNotEmpty()) {
            item { CurrentResultCard(state, actions.onReviewProtected) }
        }
        item {
            VideoTabs(listOf("任务时间线", "应用与文件"), selectedTab, { selectedTab = it })
        }
        if (selectedTab == 0) {
            if (state.records.isEmpty()) item {
                VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                    VideoEmptyState(Icons.Rounded.History, "暂无清理记录", "完成一次清理后，在这里回看结果。")
                }
            } else {
                itemsIndexed(state.records, key = { index, record -> "${record.time}|${record.title}|$index" }) { _, record ->
                    HistoryTimelineRow(record)
                }
            }
        } else {
            if (state.recentApps.isEmpty() && state.recentJunk.isEmpty()) item {
                VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                    VideoEmptyState(Icons.Rounded.Folder, "暂无分类结果", "扫描后可按应用、文件查看明细。")
                }
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
                    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                        VideoListRow(Icons.Rounded.Folder, junk.name, junk.samplePath.ifBlank { "未记录示例路径" }, value = "${Formatter.formatFileSize(context, junk.bytes)}\n${junk.files} 项")
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
    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        containerColor = lerp(BaiZeTokens.colors.surfaceRaised, MaterialTheme.colorScheme.primary, .04f),
        contentPadding = 20) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("累计释放", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { expanded = !expanded }.padding(start = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (expanded) "收起统计" else "详细统计", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ChevronRight,
                    null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(Formatter.formatFileSize(context, state.lifetimeReleased), fontSize = 38.sp, lineHeight = 46.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = (-1.1).sp)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = .045f)).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SmallMetric("完成任务", state.lifetimeRuns.toString(), Modifier.weight(1f))
            SmallMetric("处理文件", state.lifetimeFiles.toString(), Modifier.weight(1f))
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatisticRow("累计运行时长", formatElapsed(state.lifetimeElapsed))
                StatisticRow("空文件", state.lifetimeEmptyFiles.toString())
                StatisticRow("空目录", state.lifetimeEmptyDirs.toString())
                StatisticRow("残留碎片", state.lifetimeFragments.toString())
            }
        }
    }
}

@Composable
private fun SmallMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(value, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatisticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

@Composable
private fun CurrentResultCard(state: HistoryUiState, onReviewProtected: () -> Unit) {
    val context = LocalContext.current
    val hasResult = state.hasCurrentResult || state.latestResult.isNotBlank()
    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
        if (hasResult) {
            Column(Modifier.padding(18.dp)) {
                Text(listOf("最近一次", state.lastTaskTime).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DetailStatusText(state.latestResult.ifBlank { "最近一次任务已完成" }, Modifier.padding(top = 6.dp))
                if (state.hasCurrentResult) {
                    Spacer(Modifier.height(8.dp))
                    Text("${Formatter.formatFileSize(context, state.currentBytes)} · ${state.currentItemCount} 项内容",
                        fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (state.protectedItems.isNotEmpty()) {
            if (hasResult) VideoDivider(start = 16)
            VideoListRow(Icons.Rounded.Security, "已保留的内容", "白名单与保护规则",
                value = "${state.protectedItems.size} 项", onClick = onReviewProtected)
        }
    }
}

@Composable
private fun AppResultRow(packageName: String, label: String, subtitle: String, bytes: Long, files: Long) {
    val context = LocalContext.current
    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppPackageIcon(packageName = packageName, label = label, size = 38.dp, corner = 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 21.sp,
                        fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(Formatter.formatFileSize(context, bytes), Modifier.widthIn(max = 88.dp),
                        fontSize = 12.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End, color = MaterialTheme.colorScheme.primary)
                }
                Text("$subtitle · $files 项", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun HistoryTimelineRow(record: HistoryUiItem) {
    val context = LocalContext.current
    var expanded by rememberSaveable(record.time, record.title) { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button,
            onClickLabel = if (expanded) "收起任务详情" else "展开任务详情") { expanded = !expanded }.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(accent.copy(alpha = .7f)))
                Text("${record.time} · ${record.trigger}", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ChevronRight, null,
                    Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f))
            }
            Spacer(Modifier.height(10.dp))
            Text(record.title, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text(record.result.ifBlank { "任务已完成" }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = .045f)).padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${Formatter.formatFileSize(context, record.bytes)} · ${record.files} 项", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, color = accent)
                Text(if (record.cleaned) "已清理" else "已记录", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatElapsed(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    return when { seconds >= 3_600L -> "${seconds / 3_600L}h ${seconds % 3_600L / 60L}m"; seconds >= 60L -> "${seconds / 60L}m ${seconds % 60L}s"; else -> "${seconds}s" }
}
