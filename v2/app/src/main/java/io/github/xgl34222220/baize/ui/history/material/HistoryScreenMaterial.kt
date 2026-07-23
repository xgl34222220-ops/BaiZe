package io.github.xgl34222220.baize.ui.history.material

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.HistoryUiItem
import io.github.xgl34222220.baize.ui.history.HistoryUiActions
import io.github.xgl34222220.baize.ui.history.HistoryUiState
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

@Composable
fun HistoryScreenMaterial(state: HistoryUiState, actions: HistoryUiActions) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = bottomInset + 104.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { MaterialHistoryHeader(actions) }
        item { MaterialLifetimeSummary(state) }
        item { MaterialSectionHeader("最近结果", "最近一次自动任务的清理内容") }
        item { MaterialCurrentResult(state) }
        item { MaterialSectionHeader("任务记录", "按执行时间倒序排列") }
        item { MaterialRecordGroup(state.records) }
        if (state.protectedItems.isNotEmpty()) {
            item {
                OutlinedButton(
                    onClick = actions.onReviewProtected,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Icon(Icons.Rounded.Security, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("查看 ${state.protectedItems.size} 项受保护内容")
                }
            }
        }
    }
}

@Composable
private fun MaterialHistoryHeader(actions: HistoryUiActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("清理记录", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "自动任务结果与累计统计",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        FilledTonalIconButton(onClick = actions.onRefresh, modifier = Modifier.size(46.dp)) {
            Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalIconButton(onClick = actions.onClearHistory, modifier = Modifier.size(46.dp)) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = "清空记录")
        }
    }
}

@Composable
private fun MaterialLifetimeSummary(state: HistoryUiState) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .10f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.History, contentDescription = null)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("累计释放", color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .70f))
                    Text(
                        Formatter.formatFileSize(context, state.lifetimeReleased),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 34.sp,
                        lineHeight = 40.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MaterialHistoryMetric("任务", "${state.lifetimeRuns} 次", Modifier.weight(1f))
                MaterialHistoryMetric("处理文件", state.lifetimeFiles.toString(), Modifier.weight(1f))
                MaterialHistoryMetric("累计耗时", formatElapsed(state.lifetimeElapsed), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MaterialHistoryMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .64f), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Text(value, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MaterialSectionHeader(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MaterialCurrentResult(state: HistoryUiState) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (state.hasCurrentResult) BaiZeTokens.colors.success else MaterialTheme.colorScheme.outline)
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    state.latestResult.ifBlank { if (state.hasCurrentResult) "任务已完成" else "暂无最近结果" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    Formatter.formatFileSize(context, state.currentBytes),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (state.hasCurrentResult) {
                    "处理 ${state.currentItemCount} 项 · ${state.lastTaskTime.ifBlank { "时间未记录" }}"
                } else {
                    "自动任务执行后会在这里显示结果"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            val details = buildList {
                addAll(state.recentApps.take(2).map { it.label.ifBlank { it.packageName } to it.bytes })
                addAll(state.recentJunk.take(2).map { it.name to it.bytes })
            }
            details.forEach { (label, bytes) ->
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .60f))
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        Formatter.formatFileSize(context, bytes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun MaterialRecordGroup(records: List<HistoryUiItem>) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        if (records.isEmpty()) {
            Text(
                "暂无任务记录",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            records.take(50).forEachIndexed { index, record ->
                MaterialRecordRow(record)
                if (index != records.take(50).lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 58.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .60f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MaterialRecordRow(record: HistoryUiItem) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(
                    if (record.cleaned) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (record.cleaned) BaiZeTokens.colors.success else MaterialTheme.colorScheme.outline)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(record.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(record.time, record.trigger).filter(String::isNotBlank).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                Formatter.formatFileSize(context, record.bytes),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                if (record.cleaned) "已完成" else record.result.ifBlank { "已记录" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun formatElapsed(seconds: Long): String = when {
    seconds >= 3_600 -> "${seconds / 3_600} 小时"
    seconds >= 60 -> "${seconds / 60} 分钟"
    else -> "${seconds} 秒"
}
