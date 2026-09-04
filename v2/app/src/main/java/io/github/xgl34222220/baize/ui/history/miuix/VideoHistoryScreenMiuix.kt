package io.github.xgl34222220.baize.ui.history.miuix

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
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.HistoryUiItem
import io.github.xgl34222220.baize.ui.common.AppPackageIcon
import io.github.xgl34222220.baize.ui.history.HistoryUiActions
import io.github.xgl34222220.baize.ui.history.HistoryUiState
import io.github.xgl34222220.baize.ui.miuix.LuoShuPageHeader
import io.github.xgl34222220.baize.ui.miuix.LuoShuSectionTitle
import io.github.xgl34222220.baize.ui.miuix.VideoCard
import io.github.xgl34222220.baize.ui.miuix.VideoDivider
import io.github.xgl34222220.baize.ui.miuix.VideoListRow
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/** LuoShu-style continuous records page. No dashboard tabs and no four-tile metric wall. */
@Composable
fun VideoHistoryScreenMiuix(
    state: HistoryUiState,
    actions: HistoryUiActions
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val pagePadding = 16.dp

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            LuoShuPageHeader(
                eyebrow = "CLEAN RECORDS",
                title = "记录",
                subtitle = if (state.lastTaskTime.isBlank()) "等待首次清理任务" else "最近执行 ${state.lastTaskTime}",
                actionIcon = Icons.Rounded.Refresh,
                actionDescription = "刷新记录",
                onAction = actions.onRefresh
            )
        }

        item { LifetimeHero(state) }

        item {
            LuoShuSectionTitle(
                eyebrow = "SUMMARY",
                title = "累计统计",
                subtitle = "把关键数字收在一个分组里，避免首页式四宫格"
            )
        }
        item {
            VideoCard(
                modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth(),
                contentPadding = 0
            ) {
                SummaryRow("任务次数", state.lifetimeRuns.toString(), "累计完成的扫描、清理与归类任务")
                VideoDivider()
                SummaryRow("处理文件", state.lifetimeFiles.toString(), "累计参与处理的文件数量")
                VideoDivider()
                SummaryRow(
                    "空项目",
                    (state.lifetimeEmptyFiles + state.lifetimeEmptyDirs).toString(),
                    "空文件与空目录"
                )
                VideoDivider()
                SummaryRow("累计耗时", formatElapsed(state.lifetimeElapsed), "所有清理任务合计")
            }
        }

        item {
            LuoShuSectionTitle(
                eyebrow = "LATEST",
                title = "最近结果",
                subtitle = "最近一次扫描或清理的摘要"
            )
        }
        item { CurrentResultCard(state) }
        if (state.protectedItems.isNotEmpty()) {
            item {
                VideoCard(
                    modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth(),
                    contentPadding = 0
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

        if (state.recentApps.isNotEmpty() || state.recentJunk.isNotEmpty()) {
            item {
                LuoShuSectionTitle(
                    eyebrow = "BREAKDOWN",
                    title = "结果明细",
                    subtitle = "按应用和其他垃圾汇总最近一次任务"
                )
            }
            if (state.recentApps.isNotEmpty()) {
                item {
                    VideoCard(
                        modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth(),
                        contentPadding = 0
                    ) {
                        state.recentApps.take(12).forEachIndexed { index, app ->
                            AppResultRow(
                                packageName = app.packageName,
                                label = app.label.ifBlank { app.packageName },
                                subtitle = app.category.ifBlank { "应用垃圾" },
                                bytes = app.bytes,
                                files = app.files
                            )
                            if (index != state.recentApps.take(12).lastIndex) VideoDivider()
                        }
                    }
                }
            }
            if (state.recentJunk.isNotEmpty()) {
                item {
                    VideoCard(
                        modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth(),
                        contentPadding = 0
                    ) {
                        state.recentJunk.take(12).forEachIndexed { index, junk ->
                            VideoListRow(
                                icon = Icons.Rounded.Folder,
                                title = junk.name,
                                subtitle = junk.samplePath.ifBlank { "未记录示例路径" },
                                value = "${Formatter.formatFileSize(LocalContext.current, junk.bytes)}\n${junk.files} 项"
                            )
                            if (index != state.recentJunk.take(12).lastIndex) VideoDivider()
                        }
                    }
                }
            }
        }

        item {
            LuoShuSectionTitle(
                eyebrow = "TIMELINE",
                title = "任务历史",
                subtitle = "最近的扫描、清理和归类记录"
            )
        }
        if (state.records.isEmpty()) {
            item { EmptyCard("暂无历史记录", "自动任务执行后会保留结果") }
        } else {
            state.records.take(30).forEachIndexed { index, record ->
                item(key = "${record.time}|${record.title}|$index") {
                    HistoryRecordCard(record)
                }
            }
        }

        item {
            VideoCard(
                modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth(),
                contentPadding = 0
            ) {
                VideoListRow(
                    icon = Icons.Rounded.DeleteOutline,
                    title = "清空最近记录",
                    subtitle = "只删除最近任务摘要，累计统计继续保留",
                    value = "清空",
                    onClick = actions.onClearHistory
                )
            }
        }
    }
}

@Composable
private fun LifetimeHero(state: HistoryUiState) {
    val context = LocalContext.current
    VideoCard(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        contentPadding = 20
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = .11f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(27.dp)
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("累计释放", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Text(
                    Formatter.formatFileSize(context, state.lifetimeReleased),
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .045f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (state.hasCurrentResult) Icons.Rounded.CheckCircle else Icons.Rounded.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    if (state.hasCurrentResult) "最近一次结果已载入" else "等待下一次任务",
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${state.lifetimeRuns} 次",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(title: String, value: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Black
        )
    }
}

@Composable
private fun CurrentResultCard(state: HistoryUiState) {
    val context = LocalContext.current
    VideoCard(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        contentPadding = 0
    ) {
        VideoListRow(
            icon = Icons.Rounded.CheckCircle,
            title = if (state.hasCurrentResult) state.latestResult.ifBlank { "最近一次任务已完成" } else "暂无最近结果",
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppPackageIcon(packageName = packageName, label = label, size = 38.dp, corner = 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Formatter.formatFileSize(context, bytes), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("$files 项", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        }
    }
}

@Composable
private fun HistoryRecordCard(record: HistoryUiItem) {
    val context = LocalContext.current
    val statusColor = if (record.cleaned) BaiZeTokens.colors.success else MaterialTheme.colorScheme.primary
    VideoCard(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        contentPadding = 14
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(statusColor, CircleShape))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(record.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(9.dp))
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .04f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    record.result.ifBlank { "任务已完成" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                Text("${record.files} 项", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EmptyCard(title: String, subtitle: String) {
    VideoCard(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        contentPadding = 22
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(30.dp)
                )
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
