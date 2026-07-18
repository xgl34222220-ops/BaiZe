package io.github.xgl34222220.baize.ui.history.miuix

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.AppJunkUiItem
import io.github.xgl34222220.baize.GeneralJunkUiItem
import io.github.xgl34222220.baize.HistoryUiItem
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.history.HistoryUiActions
import io.github.xgl34222220.baize.ui.history.HistoryUiState

@Composable
fun HistoryScreenMiuix(
    state: HistoryUiState,
    actions: HistoryUiActions
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 146.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { MiuixHistoryHeader(state.records.isNotEmpty(), actions) }
        item { MiuixHistoryOverview(state) }

        if (state.hasCurrentResult) {
            item { MiuixSectionTitle("LATEST RESULT", "最近一次结果", "按本次实际扫描或清理结果展示") }
            item { MiuixCurrentSummary(state) }

            if (state.recentApps.isNotEmpty()) {
                item { MiuixSectionTitle("APP JUNK", "应用垃圾", "点击应用查看内部分类") }
                items(
                    items = state.recentApps,
                    key = { "miuix-app-${it.packageName}-${it.category}" }
                ) { item -> MiuixAppResultCard(item) }
            }

            if (state.recentJunk.isNotEmpty()) {
                item { MiuixSectionTitle("OTHER JUNK", "其他垃圾", "安装包、日志、临时文件与碎片") }
                items(
                    items = state.recentJunk,
                    key = { "miuix-junk-${it.name}-${it.samplePath}" }
                ) { item -> MiuixGeneralResultCard(item) }
            }
        }

        item { MiuixSectionTitle("CLEAN HISTORY", "最近任务", "最多保留最近 100 次任务明细") }

        if (state.records.isEmpty()) {
            item { MiuixEmptyHistoryCard() }
        } else {
            items(
                items = state.records,
                key = { "miuix-history-${it.time}-${it.title}-${it.trigger}" }
            ) { item -> MiuixHistoryRecordCard(item) }
        }
    }
}

@Composable
private fun MiuixHistoryHeader(
    canClear: Boolean,
    actions: HistoryUiActions
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "CLEAN HISTORY",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.4.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "清理记录",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 36.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                "Miuix 紧凑结果与历史",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        MiuixHeaderButton(
            icon = Icons.Rounded.Refresh,
            description = "刷新记录",
            enabled = true,
            onClick = actions.onRefresh
        )
        Spacer(Modifier.width(8.dp))
        MiuixHeaderButton(
            icon = Icons.Rounded.DeleteForever,
            description = "清空记录",
            enabled = canClear,
            onClick = actions.onClearHistory
        )
    }
}

@Composable
private fun MiuixHeaderButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(19.dp)
    Box(
        Modifier
            .size(54.dp)
            .shadow(6.dp, shape, clip = false)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = .06f),
                shape
            )
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxSize()) {
            Icon(icon, contentDescription = description)
        }
    }
}

@Composable
private fun MiuixHistoryOverview(state: HistoryUiState) {
    val context = LocalContext.current
    val settings = LocalAppearanceSettings.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val amoled = dark && settings.amoledBlack
    val shape = RoundedCornerShape(36.dp)
    val background = when {
        amoled -> Color(0xFF090909)
        dark -> scheme.surfaceContainerHigh
        else -> scheme.surface
    }

    Box(
        Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .shadow(12.dp, shape, clip = false)
            .clip(shape)
            .background(background)
            .border(1.dp, scheme.onSurface.copy(alpha = if (dark) .08f else .05f), shape)
            .padding(23.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (state.lifetimeRuns > 0) Color(0xFF2DBE87) else Color(0xFFF2A93B))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.lifetimeRuns > 0) "已记录 ${state.lifetimeRuns} 次任务" else "等待第一次任务",
                    color = scheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(23.dp))
            Text("累计释放", color = scheme.onSurfaceVariant, fontSize = 12.sp)
            Text(
                Formatter.formatFileSize(context, state.lifetimeReleased),
                color = scheme.onSurface,
                fontSize = 43.sp,
                lineHeight = 47.sp,
                fontWeight = FontWeight.Black
            )

            Spacer(Modifier.height(20.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(23.dp))
                    .background(scheme.onSurface.copy(alpha = if (dark) .055f else .04f))
                    .padding(horizontal = 15.dp, vertical = 13.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MiuixMetric("${state.lifetimeFiles} 项", "处理文件")
                MiuixMetric("${state.lifetimeEmptyDirs} 个", "空目录")
                MiuixMetric(formatElapsed(state.lifetimeElapsed), "累计耗时")
            }
        }
    }
}

@Composable
private fun MiuixMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Black)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
    }
}

@Composable
private fun MiuixCurrentSummary(state: HistoryUiState) {
    val context = LocalContext.current

    MiuixGroupSurface {
        Row(
            Modifier.padding(horizontal = 17.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiuixIconTile(Icons.Rounded.CheckCircle)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text("本次结果", fontSize = 18.sp, fontWeight = FontWeight.Black)
                Text(
                    "${state.currentItemCount} 项 · ${state.recentApps.size} 个应用 · ${state.recentJunk.size} 类其他垃圾",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }
            Text(
                Formatter.formatFileSize(context, state.currentBytes),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun MiuixAppResultCard(item: AppJunkUiItem) {
    val context = LocalContext.current
    var expanded by rememberSaveable(item.packageName, item.category) { mutableStateOf(false) }

    MiuixGroupSurface(
        modifier = Modifier.clickable(enabled = item.categories.isNotEmpty()) { expanded = !expanded }
    ) {
        Column(Modifier.padding(horizontal = 17.dp, vertical = 15.dp)) {
            MiuixResultHeader(
                icon = Icons.Rounded.Apps,
                title = item.label,
                subtitle = item.category.ifBlank { item.packageName },
                bytes = Formatter.formatFileSize(context, item.bytes),
                meta = "${item.files} 项",
                expanded = expanded,
                expandable = item.categories.isNotEmpty()
            )

            if (expanded && item.categories.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 11.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))
                item.categories.forEach { category ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(category.name, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
private fun MiuixGeneralResultCard(item: GeneralJunkUiItem) {
    val context = LocalContext.current
    MiuixGroupSurface {
        Column(Modifier.padding(horizontal = 17.dp, vertical = 15.dp)) {
            MiuixResultHeader(
                icon = Icons.Rounded.Folder,
                title = item.name,
                subtitle = item.samplePath.ifBlank { "未提供示例路径" },
                bytes = Formatter.formatFileSize(context, item.bytes),
                meta = if (item.errors > 0) "${item.files} 项 · 异常 ${item.errors}" else "${item.files} 项",
                expanded = false,
                expandable = false
            )
        }
    }
}

@Composable
private fun MiuixResultHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    bytes: String,
    meta: String,
    expanded: Boolean,
    expandable: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MiuixIconTile(icon)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(bytes, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Black)
            Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
        }
        if (expandable) {
            Spacer(Modifier.width(5.dp))
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MiuixHistoryRecordCard(item: HistoryUiItem) {
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

    MiuixGroupSurface(
        modifier = Modifier.clickable(enabled = hasDetails) { expanded = !expanded }
    ) {
        Column(Modifier.padding(horizontal = 17.dp, vertical = 15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(47.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (item.errors > 0) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (item.errors > 0) Icons.Rounded.ErrorOutline else Icons.Rounded.CleaningServices,
                        contentDescription = null,
                        tint = if (item.errors > 0) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${item.time} · ${item.trigger}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp
                    )
                    Text(
                        summary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = if (expanded) 4 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Formatter.formatFileSize(context, item.bytes),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        historyStatus(item),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp
                    )
                }

                if (hasDetails) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (expanded && hasDetails) {
                HorizontalDivider(Modifier.padding(vertical = 11.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))

                if (item.categories.isNotEmpty()) {
                    Text("垃圾分类", fontSize = 11.sp, fontWeight = FontWeight.Black)
                    item.categories.forEach { detail ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(detail.name, modifier = Modifier.weight(1f), fontSize = 10.sp)
                            Text(
                                "${detail.files} 项 · ${Formatter.formatFileSize(context, detail.bytes)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.sp
                            )
                        }
                    }
                }

                if (item.apps.isNotEmpty()) {
                    Text(
                        "涉及应用",
                        modifier = Modifier.padding(top = 12.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                    item.apps.forEach { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    app.category.ifBlank { app.packageName },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 8.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                "${app.files} 项 · ${Formatter.formatFileSize(context, app.bytes)}",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiuixEmptyHistoryCard() {
    MiuixGroupSurface {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Rounded.History,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(11.dp))
            Text("还没有清理记录", fontSize = 19.sp, fontWeight = FontWeight.Black)
            Text(
                "完成一次扫描或清理后，这里会显示垃圾分类、涉及应用和释放空间。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun MiuixIconTile(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        Modifier
            .size(47.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = .12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun MiuixGroupSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val settings = LocalAppearanceSettings.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val amoled = dark && settings.amoledBlack
    val shape = RoundedCornerShape(28.dp)
    val background = when {
        amoled -> Color(0xFF080808)
        dark -> scheme.surfaceContainerHigh
        else -> scheme.surface
    }

    Surface(
        modifier = modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .shadow(5.dp, shape, clip = false),
        shape = shape,
        color = background,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            scheme.onSurface.copy(alpha = if (dark) .08f else .05f)
        )
    ) {
        content()
    }
}

@Composable
private fun MiuixSectionTitle(
    eyebrow: String,
    title: String,
    subtitle: String
) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) {
        Text(
            eyebrow,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
        Text(title, fontSize = 27.sp, lineHeight = 31.sp, fontWeight = FontWeight.Black)
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
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
