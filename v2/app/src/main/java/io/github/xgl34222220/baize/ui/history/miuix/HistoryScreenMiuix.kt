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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
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
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

@Composable
fun HistoryScreenMiuix(state: HistoryUiState, actions: HistoryUiActions) {
    val iconPackages = buildList {
        addAll(state.recentApps.map { it.packageName })
        state.records.forEach { record -> addAll(record.apps.map { it.packageName }) }
    }
    AppPackageIconPreloader(iconPackages)
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 100.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item { MiuixHistoryHeader(actions) }
        item { MiuixLifetimePanel(state) }
        item { MiuixSectionTitle("最近结果", "最近一次自动任务") }
        item { MiuixCurrentResultGroup(state) }
        if (state.recentApps.isNotEmpty()) {
            item { MiuixSectionTitle("应用垃圾", "点击应用查看清理分类与路径") }
            item { MiuixAppResultGroup(state.recentApps) }
        }
        if (state.recentJunk.isNotEmpty()) {
            item { MiuixSectionTitle("其他垃圾", "本次任务处理的非应用垃圾") }
            item { MiuixJunkResultGroup(state.recentJunk) }
        }
        item { MiuixSectionTitle("任务记录", "点击有明细的任务可展开查看") }
        item { MiuixRecordGroup(state.records) }
        if (state.protectedItems.isNotEmpty()) {
            item {
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 18.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = actions.onReviewProtected),
                    shape = RoundedCornerShape(18.dp),
                    color = BaiZeTokens.colors.surfaceRaised
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("受保护内容", modifier = Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("${state.protectedItems.size} 项", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiuixHistoryHeader(actions: HistoryUiActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 22.dp, end = 14.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("清理记录", fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text("自动任务结果与累计统计", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
        MiuixHeaderButton(Icons.Rounded.Refresh, "刷新", actions.onRefresh)
        Spacer(Modifier.width(8.dp))
        MiuixHeaderButton(Icons.Rounded.DeleteOutline, "清空记录", actions.onClearHistory)
    }
}

@Composable
private fun MiuixHeaderButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(44.dp),
        shape = RoundedCornerShape(15.dp),
        color = BaiZeTokens.colors.surfaceRaised
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
private fun MiuixLifetimePanel(state: HistoryUiState) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = .14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(13.dp))
                Column {
                    Text("累计释放", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Text(
                        Formatter.formatFileSize(context, state.lifetimeReleased),
                        fontSize = 32.sp,
                        lineHeight = 38.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MiuixMetric("任务", "${state.lifetimeRuns} 次", Modifier.weight(1f))
                MiuixMetric("处理文件", state.lifetimeFiles.toString(), Modifier.weight(1f))
                MiuixMetric("累计耗时", formatElapsed(state.lifetimeElapsed), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MiuixMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MiuixSectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 22.dp)) {
        Text(title, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun MiuixCurrentResultGroup(state: HistoryUiState) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = BaiZeTokens.colors.surfaceRaised
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 17.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (state.hasCurrentResult) BaiZeTokens.colors.success else MaterialTheme.colorScheme.outline)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        state.latestResult.ifBlank { if (state.hasCurrentResult) "任务已完成" else "暂无最近结果" },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (state.hasCurrentResult) "处理 ${state.currentItemCount} 项" else "自动任务执行后显示结果",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
                Text(
                    Formatter.formatFileSize(context, state.currentBytes),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (state.lastTaskTime.isNotBlank()) {
                MiuixDivider()
                Row(
                    modifier = Modifier.padding(start = 68.dp, end = 17.dp, top = 13.dp, bottom = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("执行时间", modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Text(state.lastTaskTime, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
    }
}


@Composable
private fun MiuixAppResultGroup(apps: List<AppJunkUiItem>) {
    Surface(
        modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = BaiZeTokens.colors.surfaceRaised
    ) {
        Column {
            apps.forEachIndexed { index, item ->
                MiuixAppResultRow(item)
                if (index != apps.lastIndex) MiuixDivider()
            }
        }
    }
}

@Composable
private fun MiuixAppResultRow(item: AppJunkUiItem) {
    val context = LocalContext.current
    var expanded by rememberSaveable(item.packageName, item.category) { mutableStateOf(false) }
    val hasDetails = item.categories.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = hasDetails) { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppPackageIcon(item.packageName, item.label.ifBlank { item.packageName }, size = 40.dp, corner = 13.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.label.ifBlank { item.packageName }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.category.ifBlank { item.packageName }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Formatter.formatFileSize(context, item.bytes), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("${item.files} 项", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            if (hasDetails) {
                Spacer(Modifier.width(5.dp))
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = if (expanded) "收起应用明细" else "展开应用明细", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (expanded && hasDetails) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .50f))
            item.categories.forEach { detail ->
                Row(Modifier.fillMaxWidth().padding(start = 52.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(detail.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(detail.samplePath.ifBlank { "未记录示例路径" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${detail.files} 项 · ${Formatter.formatFileSize(context, detail.bytes)}", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun MiuixJunkResultGroup(items: List<GeneralJunkUiItem>) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = BaiZeTokens.colors.surfaceRaised
    ) {
        Column {
            items.forEachIndexed { index, item ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .10f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(item.samplePath.ifBlank { "未记录示例路径" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Formatter.formatFileSize(context, item.bytes), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("${item.files} 项", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
                if (index != items.lastIndex) MiuixDivider()
            }
        }
    }
}

@Composable
private fun MiuixRecordGroup(records: List<HistoryUiItem>) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = BaiZeTokens.colors.surfaceRaised
    ) {
        if (records.isEmpty()) {
            Text("暂无任务记录", modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column {
                val visibleRecords = records.take(50)
                visibleRecords.forEachIndexed { index, record ->
                    MiuixRecordRow(record)
                    if (index != visibleRecords.lastIndex) MiuixDivider()
                }
            }
        }
    }
}

@Composable
private fun MiuixRecordRow(record: HistoryUiItem) {
    val context = LocalContext.current
    var expanded by rememberSaveable(record.time, record.title, record.trigger) { mutableStateOf(false) }
    val hasDetails = record.categories.isNotEmpty() || record.apps.isNotEmpty()
    val summary = when {
        record.apps.isNotEmpty() -> "涉及 ${record.apps.size} 个应用 · ${record.files} 项"
        record.categories.isNotEmpty() -> record.categories.take(2).joinToString(" · ") { it.name }
        record.bytes == 0L && record.files == 0 -> "未发现可清理内容"
        else -> record.result
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = hasDetails) { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .10f)),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (record.cleaned) BaiZeTokens.colors.success else MaterialTheme.colorScheme.outline))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(record.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(record.time, record.trigger).filter(String::isNotBlank).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Formatter.formatFileSize(context, record.bytes), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(if (record.cleaned) "已完成" else record.result.ifBlank { "已记录" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            if (hasDetails) {
                Spacer(Modifier.width(5.dp))
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = if (expanded) "收起任务明细" else "展开任务明细", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (expanded && hasDetails) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .50f))
            record.apps.forEach { app ->
                Row(Modifier.fillMaxWidth().padding(start = 52.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(app.label.ifBlank { app.packageName }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(app.category.ifBlank { app.packageName }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${app.files} 项 · ${Formatter.formatFileSize(context, app.bytes)}", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
                }
            }
            record.categories.forEach { detail ->
                Row(Modifier.fillMaxWidth().padding(start = 52.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(detail.name, modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("${detail.files} 项 · ${Formatter.formatFileSize(context, detail.bytes)}", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun MiuixDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 68.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .50f)
    )
}

private fun formatElapsed(seconds: Long): String = when {
    seconds >= 3_600 -> "${seconds / 3_600} 小时"
    seconds >= 60 -> "${seconds / 60} 分钟"
    else -> "${seconds} 秒"
}
