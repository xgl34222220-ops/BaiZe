package io.github.xgl34222220.baize.ui.history.miuix

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.AppJunkUiItem
import io.github.xgl34222220.baize.GeneralJunkUiItem
import io.github.xgl34222220.baize.HistoryUiItem
import io.github.xgl34222220.baize.ui.common.AppPackageIcon
import io.github.xgl34222220.baize.ui.common.AppPackageIconPreloader
import io.github.xgl34222220.baize.ui.history.HistoryUiActions
import io.github.xgl34222220.baize.ui.history.HistoryUiState
import io.github.xgl34222220.baize.ui.miuix.LuoShuGroup
import io.github.xgl34222220.baize.ui.miuix.LuoShuGroupDivider
import io.github.xgl34222220.baize.ui.miuix.LuoShuHeaderButton
import io.github.xgl34222220.baize.ui.miuix.LuoShuNavigationRow
import io.github.xgl34222220.baize.ui.miuix.LuoShuPageHeader
import io.github.xgl34222220.baize.ui.miuix.LuoShuSection
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

@Composable
fun HistoryScreenMiuix(state: HistoryUiState, actions: HistoryUiActions) {
    // Derived lists are cached per input so unrelated recompositions (scroll, expand toggles)
    // do not rebuild the 50-record grouping and icon package list on the main thread.
    val iconPackages = remember(state.recentApps, state.records) {
        buildList {
            addAll(state.recentApps.map { it.packageName })
            state.records.forEach { record -> addAll(record.apps.map { it.packageName }) }
        }
    }
    AppPackageIconPreloader(iconPackages)

    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val recordGroups = remember(state.records) {
        state.records
            .take(50)
            .groupBy { record -> record.time.trim().take(10).ifBlank { "更早记录" } }
    }
    var showZeroApps by rememberSaveable { mutableStateOf(false) }
    val meaningfulApps = remember(state.recentApps) { state.recentApps.filter { it.bytes > 0L } }
    val zeroApps = remember(state.recentApps) { state.recentApps.filter { it.bytes <= 0L } }
    val meaningfulJunk = remember(state.recentJunk) { state.recentJunk.filter { it.bytes > 0L } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomInset + 132.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "history-header") {
            LuoShuPageHeader("记录") {
                LuoShuHeaderButton(Icons.Rounded.Refresh, "刷新", actions.onRefresh)
                LuoShuHeaderButton(Icons.Rounded.DeleteOutline, "清空记录", actions.onClearHistory)
            }
        }
        item(key = "history-lifetime") { LifetimeHero(state) }
        item(key = "history-current-title") { LuoShuSection("最近结果", "最近一次扫描或清理任务") }
        item(key = "history-current") { CurrentResultGroup(state) }

        if (meaningfulApps.isNotEmpty()) {
            item(key = "history-app-title") { LuoShuSection("应用垃圾", "仅展示本次产生清理量的应用") }
            item(key = "history-apps") { AppResultGroup(meaningfulApps) }
        }
        if (zeroApps.isNotEmpty()) {
            item(key = "history-zero-apps") {
                ZeroAppGroup(
                    apps = zeroApps,
                    expanded = showZeroApps,
                    onToggle = { showZeroApps = !showZeroApps }
                )
            }
        }

        if (meaningfulJunk.isNotEmpty()) {
            item(key = "history-junk-title") { LuoShuSection("其他垃圾", "本次任务处理的非应用垃圾") }
            item(key = "history-junk") { JunkResultGroup(meaningfulJunk) }
        }

        item(key = "history-record-title") { LuoShuSection("任务记录", "按日期排列，扫描与清理状态分开显示") }

        if (recordGroups.isEmpty()) {
            item(key = "history-empty") { EmptyRecordsCard() }
        } else {
            recordGroups.forEach { (date, records) ->
                item(key = "history-date-$date") { DateLabel(date) }
                itemsIndexed(
                    items = records,
                    key = { index, record -> "${record.time}|${record.title}|${record.trigger}|$index" }
                ) { _, record ->
                    RecordCard(record)
                }
            }
        }

        item(key = "history-tools-title") { LuoShuSection("回收与审计") }
        item(key = "history-tools") {
            LuoShuGroup {
                LuoShuNavigationRow(Icons.Rounded.RestoreFromTrash, "回收站", "恢复或永久删除；页内可切换到隔离区", actions.onOpenTrash)
                if (state.protectedItems.isNotEmpty()) {
                    LuoShuGroupDivider()
                    LuoShuNavigationRow(
                        Icons.Rounded.Security,
                        "受保护内容",
                        "${state.protectedItems.size} 项被白名单或安全规则保留",
                        actions.onReviewProtected
                    )
                }
                LuoShuGroupDivider()
                LuoShuNavigationRow(Icons.Rounded.FactCheck, "清理审计", "清理依据、规则质量与改进建议", actions.onOpenAudit)
            }
        }
    }
}

@Composable
private fun LifetimeHero(state: HistoryUiState) {
    val context = LocalContext.current
    val colors = BaiZeTokens.colors
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceRaised,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(
                            scheme.primaryContainer.copy(alpha = .38f),
                            colors.surfaceRaised
                        )
                    )
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(15.dp),
                    color = colors.surfaceOverlay
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.History,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = scheme.primary
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("累计释放", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    HistoryMetricValue(Formatter.formatFileSize(context, state.lifetimeReleased))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("任务", "${state.lifetimeRuns} 次", Modifier.weight(1f))
                Metric("处理文件", state.lifetimeFiles.toString(), Modifier.weight(1f))
                Metric("累计耗时", if (state.lifetimeRuns > 0) formatElapsed(state.lifetimeElapsed) else "—", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HistoryMetricValue(value: String) {
    val match = remember(value) { splitHistoryMetric(value) }
    if (match == null) {
        Text(value, style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum"))
        return
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            match.first,
            modifier = Modifier.alignByBaseline(),
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum"
            )
        )
        Spacer(Modifier.width(5.dp))
        Text(
            match.second,
            modifier = Modifier.alignByBaseline().padding(bottom = 2.dp),
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"))
    }
}

private fun currentResultTitle(raw: String, hasResult: Boolean): String {
    if (!hasResult) return "暂无最近结果"
    val value = sanitizeText(raw)
    return when {
        value.contains("扫描") -> "最近一次扫描已完成"
        value.contains("归类") -> "文件归类已完成"
        value.contains("清理") -> "最近一次清理已完成"
        else -> "最近一次任务已完成"
    }
}

@Composable
private fun CurrentResultGroup(state: HistoryUiState) {
    val context = LocalContext.current
    LuoShuGroup {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(9.dp).clip(CircleShape).background(
                    if (state.hasCurrentResult) BaiZeTokens.colors.success else MaterialTheme.colorScheme.outline
                )
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    currentResultTitle(state.latestResult, state.hasCurrentResult),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (state.hasCurrentResult) state.currentCountDescription else "任务执行后会在这里显示结果",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            // 没有可测量的数字时降为灰色说明字，不再用粗体数字位显示“无法测量”。
            val capacity = state.currentCapacityText { Formatter.formatFileSize(context, it) }
            val numeric = capacity.any(Char::isDigit)
            if (state.hasCurrentResult) Text(
                capacity,
                style = if (numeric) MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum")
                    else MaterialTheme.typography.bodySmall,
                color = if (numeric) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.lastTaskTime.isNotBlank()) {
            LuoShuGroupDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 74.dp, end = 17.dp, top = 13.dp, bottom = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("执行时间", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    sanitizeText(state.lastTaskTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ZeroAppGroup(
    apps: List<AppJunkUiItem>,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    LuoShuGroup {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .08f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("无占用应用", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${apps.size} 个 · 扫描到条目但未产生占用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收起无垃圾应用" else "展开无垃圾应用",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            apps.forEachIndexed { index, app ->
                LuoShuGroupDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppPackageIcon(
                        app.packageName,
                        app.label.ifBlank { app.packageName },
                        size = 34.dp,
                        corner = 11.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            sanitizeText(app.label.ifBlank { app.packageName }),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            sanitizeText(app.packageName),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        "0 B · 0 项",
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AppResultGroup(apps: List<AppJunkUiItem>) {
    LuoShuGroup {
        apps.forEachIndexed { index, item ->
            AppResultRow(item)
            if (index != apps.lastIndex) LuoShuGroupDivider()
        }
    }
}

@Composable
private fun AppResultRow(item: AppJunkUiItem) {
    val context = LocalContext.current
    var expanded by rememberSaveable(item.packageName, item.category) { mutableStateOf(false) }
    val hasDetails = item.categories.isNotEmpty()
    Column(
        modifier = Modifier.fillMaxWidth().clickable(enabled = hasDetails) { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 15.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppPackageIcon(
                item.packageName,
                item.label.ifBlank { item.packageName },
                size = 42.dp,
                corner = 13.dp
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    sanitizeText(item.label.ifBlank { item.packageName }),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    sanitizeText(item.category.ifBlank { item.packageName }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Formatter.formatFileSize(context, item.bytes), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
                Text(
                    "${item.files} 项",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (hasDetails) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "收起应用明细" else "展开应用明细",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (expanded && hasDetails) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .20f))
            item.categories.forEach { detail ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 56.dp, top = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(sanitizeText(detail.name), style = MaterialTheme.typography.labelLarge)
                        Text(
                            sanitizeText(detail.samplePath).ifBlank { "未记录示例路径" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${detail.files} 项 · ${Formatter.formatFileSize(context, detail.bytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun JunkResultGroup(items: List<GeneralJunkUiItem>) {
    val context = LocalContext.current
    LuoShuGroup {
        items.forEachIndexed { index, item ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        sanitizeText(item.name),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        sanitizeText(item.samplePath).ifBlank { "未记录示例路径" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Formatter.formatFileSize(context, item.bytes), style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${item.files} 项",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (index != items.lastIndex) LuoShuGroupDivider()
        }
    }
}

@Composable
private fun DateLabel(date: String) {
    Text(
        text = date,
        modifier = Modifier.padding(start = 2.dp, top = 4.dp, bottom = 1.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun EmptyRecordsCard() {
    LuoShuGroup {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("暂无任务记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text("扫描或清理后，记录会按日期显示在这里", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecordCard(record: HistoryUiItem) {
    val context = LocalContext.current
    var expanded by rememberSaveable(record.time, record.title, record.trigger) { mutableStateOf(false) }
    val visibleApps = record.apps.filter { it.bytes > 0L }
    val visibleCategories = record.categories.filter { it.bytes > 0L }
    val hasDetails = visibleCategories.isNotEmpty() || visibleApps.isNotEmpty()
    val title = sanitizeText(record.title).ifBlank { "历史任务" }
    val meta = listOf(sanitizeText(record.time), sanitizeText(record.trigger))
        .filter(String::isNotBlank)
        .joinToString(" · ")
    val summary = sanitizeText(
        when {
            record.releaseState == "unknown" -> "释放量无法测量 · ${record.result}"
            record.releaseState == "retained" -> "移入回收站或隔离区，尚未释放空间"
            record.emptyDirs > 0 -> "${record.files.coerceAtLeast(0)} 个文件 · ${record.emptyDirs} 个目录"
            visibleApps.isNotEmpty() -> "涉及 ${visibleApps.size} 个应用 · ${record.files} 项"
            visibleCategories.isNotEmpty() -> visibleCategories.take(2).joinToString(" · ") { it.name }
            record.bytes == 0L && record.files == 0 -> "未发现可清理内容"
            else -> record.result
        }
    ).ifBlank { if (record.cleaned) "清理任务已完成" else "扫描任务已完成" }
    val statusText = when {
        record.releaseState == "unknown" -> "容量未确认"
        record.errors > 0 -> "有异常"
        record.releaseState == "retained" -> "尚未释放"
        record.cleaned -> "已处理"
        else -> "扫描完成"
    }
    val statusColor = if (record.cleaned) BaiZeTokens.colors.success else MaterialTheme.colorScheme.onSurfaceVariant
    val statusBackground = if (record.cleaned) {
        BaiZeTokens.colors.success.copy(alpha = .12f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .10f)
    }

    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .clickable(enabled = hasDetails) { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        color = BaiZeTokens.colors.surfaceRaised
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(13.dp),
                    color = statusBackground
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (record.cleaned) Icons.Rounded.CheckCircle else Icons.Rounded.Search,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (meta.isNotBlank()) {
                        Text(
                            meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Surface(shape = RoundedCornerShape(50), color = statusBackground) {
                    Text(
                        statusText,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                }
                if (hasDetails) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "收起任务明细" else "展开任务明细",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.height(11.dp))
            Row(modifier = Modifier.padding(start = 56.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    summary,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (record.cleaned) "释放" else "发现",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(record.capacityText { Formatter.formatFileSize(context, it) }, style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"))
                }
            }

            if (expanded && hasDetails) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .20f)
                )
                visibleApps.forEach { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 56.dp, top = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                sanitizeText(app.label.ifBlank { app.packageName }),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                sanitizeText(app.category.ifBlank { app.packageName }),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            "${app.files} 项 · ${Formatter.formatFileSize(context, app.bytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                visibleCategories.forEach { detail ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 56.dp, top = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            sanitizeText(detail.name),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${detail.files} 项 · ${Formatter.formatFileSize(context, detail.bytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Splits a formatted size such as "12.5 MB" into number and unit so the unit can be rendered
 * smaller, matching BaiZeMetric on Home. Android's Formatter may emit a no-break space.
 */
private val historyMetricPattern = Regex("""^([0-9][0-9.,]*)[\s\u00A0]*([A-Za-z]+)$""")

internal fun splitHistoryMetric(value: String): Pair<String, String>? =
    historyMetricPattern.matchEntire(value.trim())?.let { it.groupValues[1] to it.groupValues[2] }

/** Compiled once; sanitizeText runs for every visible row on each recomposition. */
private val unsafeTextPattern = Regex("[\\p{Cc}\\p{Cf}\\s]+")

private fun sanitizeText(value: String): String =
    value.replace(unsafeTextPattern, " ").trim()

private fun formatElapsed(seconds: Long): String = when {
    seconds >= 3_600 -> "${seconds / 3_600} 小时"
    seconds >= 60 -> "${seconds / 60} 分钟"
    // 多数任务不到 1 秒，累计仍为 0 时显示“不足 1 秒”，不显示容易误解的“0 秒”。
    seconds <= 0L -> "不足 1 秒"
    else -> "${seconds} 秒"
}
/**
 * HistoryUiState.lifetimeElapsed is accumulated in seconds (AppTaskHistoryStore adds
 * elapsedMs / 1000, HistoryRepository adds elapsedSeconds), as the MIUIX and Material
 * history screens already assume. Dividing by 1000 again showed one hour as "3s".
 */
internal fun formatLifetimeElapsedCompact(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0L)
    return when { seconds >= 3_600L -> "${seconds / 3_600L}h ${seconds % 3_600L / 60L}m"; seconds >= 60L -> "${seconds / 60L}m ${seconds % 60L}s"; else -> "${seconds}s" }
}
