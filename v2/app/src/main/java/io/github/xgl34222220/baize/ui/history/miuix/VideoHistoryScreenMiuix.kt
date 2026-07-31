package io.github.xgl34222220.baize.ui.history.miuix

import android.text.format.Formatter
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.xgl34222220.baize.ui.miuix.VideoIconButton
import io.github.xgl34222220.baize.ui.miuix.VideoListRow
import io.github.xgl34222220.baize.ui.miuix.VideoMetricTile
import io.github.xgl34222220.baize.ui.miuix.VideoSectionTitle
import io.github.xgl34222220.baize.ui.miuix.VideoTabs
import io.github.xgl34222220.baize.ui.miuix.VideoTopBar
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/** 记录页按参考视频改为顶部标签切换，概览、应用结果和历史记录不再全部堆在一个长页面。 */
@Composable
fun VideoHistoryScreenMiuix(
    state: HistoryUiState,
    actions: HistoryUiActions
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            VideoTopBar(
                title = "记录",
                subtitle = "清理结果与累计统计",
                actions = {
                    VideoIconButton(Icons.Rounded.Refresh, "刷新", actions.onRefresh)
                    VideoIconButton(Icons.Rounded.DeleteOutline, "清空记录", actions.onClearHistory)
                }
            )
        }
        item {
            VideoTabs(
                labels = listOf("概览", "应用", "历史"),
                selectedIndex = selectedTab,
                onSelected = { selectedTab = it }
            )
        }

        when (selectedTab) {
            0 -> {
                item { LifetimeHero(state) }
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VideoMetricTile(
                            label = "任务",
                            value = state.lifetimeRuns.toString(),
                            caption = "累计执行次数",
                            modifier = Modifier.weight(1f)
                        )
                        VideoMetricTile(
                            label = "处理文件",
                            value = state.lifetimeFiles.toString(),
                            caption = "累计文件数量",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VideoMetricTile(
                            label = "空项目",
                            value = (state.lifetimeEmptyFiles + state.lifetimeEmptyDirs).toString(),
                            caption = "空文件与目录",
                            modifier = Modifier.weight(1f)
                        )
                        VideoMetricTile(
                            label = "累计耗时",
                            value = formatElapsed(state.lifetimeElapsed),
                            caption = "所有清理任务",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                item { VideoSectionTitle("最近结果", "最近一次任务的可读摘要") }
                item { CurrentResultCard(state) }
                if (state.protectedItems.isNotEmpty()) {
                    item {
                        VideoCard(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .fillMaxWidth()
                        ) {
                            VideoListRow(
                                icon = Icons.Rounded.Security,
                                title = "受保护内容",
                                subtitle = "白名单与安全规则阻止了潜在误删",
                                value = "${state.protectedItems.size} 项",
                                onClick = actions.onReviewProtected
                            )
                        }
                    }
                }
            }

            1 -> {
                item { VideoSectionTitle("应用垃圾", "最近一次任务按应用汇总") }
                if (state.recentApps.isEmpty() && state.recentJunk.isEmpty()) {
                    item { EmptyCard("暂无应用结果", "完成一次扫描或清理后显示") }
                } else {
                    if (state.recentApps.isNotEmpty()) {
                        item {
                            VideoCard(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .fillMaxWidth()
                            ) {
                                state.recentApps.forEachIndexed { index, app ->
                                    AppResultRow(
                                        packageName = app.packageName,
                                        label = app.label.ifBlank { app.packageName },
                                        subtitle = app.category.ifBlank { "应用垃圾" },
                                        bytes = app.bytes,
                                        files = app.files
                                    )
                                    if (index != state.recentApps.lastIndex) VideoDivider()
                                }
                            }
                        }
                    }
                    if (state.recentJunk.isNotEmpty()) {
                        item { VideoSectionTitle("其他垃圾", "不属于单个应用的清理结果") }
                        item {
                            VideoCard(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .fillMaxWidth()
                            ) {
                                state.recentJunk.forEachIndexed { index, junk ->
                                    GenericResultRow(
                                        title = junk.name,
                                        subtitle = junk.samplePath.ifBlank { "未记录示例路径" },
                                        bytes = junk.bytes,
                                        files = junk.files
                                    )
                                    if (index != state.recentJunk.lastIndex) VideoDivider()
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                item { VideoSectionTitle("任务历史", "按时间查看扫描、清理和归类结果") }
                if (state.records.isEmpty()) {
                    item { EmptyCard("暂无历史记录", "自动任务执行后会保留结果") }
                } else {
                    state.records.take(50).forEachIndexed { index, record ->
                        item(key = "${record.time}|${record.title}|$index") {
                            HistoryRecordCard(record)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LifetimeHero(state: HistoryUiState) {
    val context = LocalContext.current
    VideoCard(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f),
        contentPadding = 16
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(BaiZeTokens.colors.success)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "累计清理",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    Formatter.formatFileSize(context, state.lifetimeReleased),
                    fontSize = 30.sp,
                    lineHeight = 35.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (state.lastTaskTime.isBlank()) "等待首次任务" else "最近执行 ${state.lastTaskTime}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .52f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            }
        }
    }
}

@Composable
private fun CurrentResultCard(state: HistoryUiState) {
    val context = LocalContext.current
    VideoCard(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
    ) {
        VideoListRow(
            icon = Icons.Rounded.CheckCircle,
            title = if (state.hasCurrentResult) {
                state.latestResult.ifBlank { "最近一次任务已完成" }
            } else {
                "暂无最近结果"
            },
            subtitle = if (state.hasCurrentResult) {
                "处理 ${state.currentItemCount} 项 · ${state.lastTaskTime}"
            } else {
                "完成一次扫描或清理后显示结果"
            },
            value = Formatter.formatFileSize(context, state.currentBytes)
        )
    }
}

@Composable
private fun AppResultRow(
    packageName: String,
    label: String,
    subtitle: String,
    bytes: Long,
    files: Long
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppPackageIcon(
            packageName = packageName,
            label = label,
            size = 40.dp,
            corner = 13.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Formatter.formatFileSize(context, bytes), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("$files 项", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        }
    }
}

@Composable
private fun GenericResultRow(
    title: String,
    subtitle: String,
    bytes: Long,
    files: Long
) {
    val context = LocalContext.current
    VideoListRow(
        icon = Icons.Rounded.Folder,
        title = title,
        subtitle = subtitle,
        value = "${Formatter.formatFileSize(context, bytes)}\n$files 项"
    )
}

@Composable
private fun HistoryRecordCard(record: HistoryUiItem) {
    val context = LocalContext.current
    val statusColor = if (record.cleaned) BaiZeTokens.colors.success else MaterialTheme.colorScheme.primary
    VideoCard(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
        contentPadding = 15
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(record.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${record.time} · ${record.trigger}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                Formatter.formatFileSize(context, record.bytes),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(9.dp))
        Surface(
            shape = RoundedCornerShape(13.dp),
            color = BaiZeTokens.colors.surfaceOverlay
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(record.result.ifBlank { "任务已完成" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Text("${record.files} 项", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EmptyCard(title: String, subtitle: String) {
    VideoCard(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
        contentPadding = 22
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(8.dp))
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
        }
    }
}

private fun formatElapsed(milliseconds: Long): String {
    if (milliseconds <= 0L) return "0 秒"
    val seconds = milliseconds / 1_000L
    return when {
        seconds >= 3_600L -> "${seconds / 3_600L} 时"
        seconds >= 60L -> "${seconds / 60L} 分"
        else -> "$seconds 秒"
    }
}
