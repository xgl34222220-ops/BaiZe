package io.github.xgl34222220.baize.ui.history.material

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.AppJunkUiItem
import io.github.xgl34222220.baize.GeneralJunkUiItem
import io.github.xgl34222220.baize.HistoryUiItem
import io.github.xgl34222220.baize.ui.history.HistoryUiActions
import io.github.xgl34222220.baize.ui.history.HistoryUiState

@Composable
fun HistoryScreenMaterial(
    state: HistoryUiState,
    actions: HistoryUiActions
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomInset + 146.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { MaterialHistoryHeader(state.records.isNotEmpty(), actions) }
            item { MaterialHistoryOverview(state) }

            if (state.hasCurrentResult) {
                item { MaterialSectionTitle("LATEST RESULT", "最近一次清理结果") }
                item { MaterialCurrentResultSummary(state) }

                if (state.recentApps.isNotEmpty()) {
                    item { MaterialSectionTitle("APP JUNK", "涉及应用") }
                    items(
                        items = state.recentApps,
                        key = { "material-app-${it.packageName}-${it.category}" }
                    ) { item -> MaterialAppResultCard(item) }
                }

                if (state.recentJunk.isNotEmpty()) {
                    item { MaterialSectionTitle("OTHER JUNK", "其他垃圾") }
                    items(
                        items = state.recentJunk,
                        key = { "material-junk-${it.name}-${it.samplePath}" }
                    ) { item -> MaterialGeneralResultCard(item) }
                }
            }

            item { MaterialSectionTitle("CLEAN HISTORY", "最近任务") }

            if (state.records.isEmpty()) {
                item { MaterialEmptyHistoryCard() }
            } else {
                items(
                    items = state.records,
                    key = { "material-history-${it.time}-${it.title}-${it.trigger}" }
                ) { item -> MaterialHistoryRecordCard(item) }
            }
        }
    }
}

@Composable
private fun MaterialHistoryHeader(
    canClear: Boolean,
    actions: HistoryUiActions
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "CLEAN HISTORY",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.3.sp
            )
            Spacer(Modifier.height(5.dp))
            Text("清理记录", style = MaterialTheme.typography.headlineLarge)
            Text(
                "结果、分类、应用与累计统计",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        FilledTonalIconButton(
            onClick = actions.onRefresh,
            modifier = Modifier.size(54.dp)
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = "刷新记录")
        }

        Spacer(Modifier.width(8.dp))

        FilledTonalIconButton(
            onClick = actions.onClearHistory,
            enabled = canClear,
            modifier = Modifier.size(54.dp)
        ) {
            Icon(Icons.Rounded.DeleteForever, contentDescription = "清空记录")
        }
    }
}

@Composable
private fun MaterialHistoryOverview(state: HistoryUiState) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val latest = state.latestResult.ifBlank {
        if (state.records.isEmpty()) "等待第一条清理记录" else "最近任务已完成"
    }

    Card(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(scheme.primary, scheme.tertiary)
                    )
                )
                .padding(23.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.History,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = .9f)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        latest,
                        color = Color.White.copy(alpha = .88f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(24.dp))

                Text(
                    "累计释放",
                    color = Color.White.copy(alpha = .65f),
                    fontSize = 12.sp
                )
                Text(
                    Formatter.formatFileSize(context, state.lifetimeReleased),
                    color = Color.White,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black
                )

                Spacer(Modifier.height(21.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MaterialOverviewMetric("${state.lifetimeRuns} 次", "累计任务")
                    MaterialOverviewMetric("${state.lifetimeFiles} 项", "处理文件")
                    MaterialOverviewMetric(formatElapsed(state.lifetimeElapsed), "累计耗时")
                }
            }
        }
    }
}

@Composable
private fun MaterialOverviewMetric(value: String, label: String) {
    Column {
        Text(value, color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
        Text(label, color = Color.White.copy(alpha = .62f), fontSize = 10.sp)
    }
}

@Composable
private fun MaterialCurrentResultSummary(state: HistoryUiState) {
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("本次结果", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${state.currentItemCount} 项 · ${state.recentApps.size} 个应用 · ${state.recentJunk.size} 类其他垃圾",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                Text(
                    Formatter.formatFileSize(context, state.currentBytes),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Spacer(Modifier.height(15.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
            Spacer(Modifier.height(13.dp))
            Text(
                "空文件 ${state.lifetimeEmptyFiles} · 空目录 ${state.lifetimeEmptyDirs} · 碎片 ${state.lifetimeFragments}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun MaterialAppResultCard(item: AppJunkUiItem) {
    val context = LocalContext.current
    var expanded by rememberSaveable(item.packageName, item.category) { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .clickable(enabled = item.categories.isNotEmpty()) { expanded = !expanded },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(17.dp)) {
            ResultHeaderRow(
                icon = Icons.Rounded.Apps,
                title = item.label,
                subtitle = item.category.ifBlank { item.packageName },
                bytesText = Formatter.formatFileSize(context, item.bytes),
                metaText = "${item.files} 项",
                expanded = expanded,
                expandable = item.categories.isNotEmpty()
            )

            if (expanded && item.categories.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                item.categories.forEach { category ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(category.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                category.samplePath,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            "${category.files} 项 · ${Formatter.formatFileSize(context, category.bytes)}",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MaterialGeneralResultCard(item: GeneralJunkUiItem) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(17.dp)) {
            ResultHeaderRow(
                icon = Icons.Rounded.Folder,
                title = item.name,
                subtitle = item.samplePath.ifBlank { "未提供示例路径" },
                bytesText = Formatter.formatFileSize(context, item.bytes),
                metaText = if (item.errors > 0) "${item.files} 项 · 异常 ${item.errors}" else "${item.files} 项",
                expanded = false,
                expandable = false
            )
        }
    }
}

@Composable
private fun ResultHeaderRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    bytesText: String,
    metaText: String,
    expanded: Boolean,
    expandable: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(bytesText, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
            Text(metaText, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        }
        if (expandable) {
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MaterialHistoryRecordCard(item: HistoryUiItem) {
    val context = LocalContext.current
    var expanded by rememberSaveable(item.time, item.title, item.trigger) { mutableStateOf(false) }
    val hasDetails = item.categories.isNotEmpty() || item.apps.isNotEmpty()
    val summary = when {
        item.categories.isNotEmpty() -> item.categories.take(3).joinToString(" · ") {
            "${it.name} ${Formatter.formatFileSize(context, it.bytes)}"
        }
        item.bytes == 0L && item.files == 0 -> if (item.cleaned) "未发现可清理内容" else "扫描未发现垃圾"
        else -> item.result
    }

    Card(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .clickable(enabled = hasDetails) { expanded = !expanded },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(50.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = if (item.errors > 0) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (item.errors > 0) Icons.Rounded.ErrorOutline else Icons.Rounded.CleaningServices,
                            contentDescription = null,
                            tint = if (item.errors > 0) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            }
                        )
                    }
                }

                Spacer(Modifier.width(14.dp))

                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${item.time} · ${item.trigger}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                    Text(
                        summary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = if (expanded) 4 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Formatter.formatFileSize(context, item.bytes),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        historyStatus(item),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }

                if (hasDetails) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (expanded) "收起" else "展开"
                        )
                    }
                }
            }

            if (expanded && hasDetails) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                if (item.categories.isNotEmpty()) {
                    Text("垃圾分类", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    item.categories.forEach { detail ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(detail.name, modifier = Modifier.weight(1f), fontSize = 11.sp)
                            Text(
                                "${detail.files} 项 · ${Formatter.formatFileSize(context, detail.bytes)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                if (item.apps.isNotEmpty()) {
                    Text(
                        "涉及应用",
                        modifier = Modifier.padding(top = 12.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    item.apps.forEach { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    app.category.ifBlank { app.packageName },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                "${app.files} 项 · ${Formatter.formatFileSize(context, app.bytes)}",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MaterialEmptyHistoryCard() {
    Card(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Rounded.History,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            Text("还没有清理记录", style = MaterialTheme.typography.titleLarge)
            Text(
                "完成一次扫描或清理后，这里会显示任务结果、垃圾分类和涉及应用。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun MaterialSectionTitle(eyebrow: String, title: String) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 3.dp)) {
        Text(
            eyebrow,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

private fun historyStatus(item: HistoryUiItem): String = when {
    item.errors > 0 -> "异常 ${item.errors}"
    item.cleaned && item.bytes > 0 -> "已清理"
    item.cleaned -> "无垃圾"
    item.files > 0 -> "发现 ${item.files} 项"
    else -> "未发现"
}

private fun formatElapsed(seconds: Long): String = when {
    seconds <= 0 -> "0 秒"
    seconds < 60 -> "${seconds} 秒"
    seconds < 3600 -> "${seconds / 60} 分"
    else -> "${seconds / 3600} 小时"
}
