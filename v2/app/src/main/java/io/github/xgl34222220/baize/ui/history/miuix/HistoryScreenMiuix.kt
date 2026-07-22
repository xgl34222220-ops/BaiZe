package io.github.xgl34222220.baize.ui.history.miuix

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.AppJunkUiItem
import io.github.xgl34222220.baize.GeneralJunkUiItem
import io.github.xgl34222220.baize.HistoryUiItem
import io.github.xgl34222220.baize.ProtectedUiItem
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.common.AppPackageIcon
import io.github.xgl34222220.baize.ui.common.AppPackageIconPreloader
import io.github.xgl34222220.baize.ui.history.HistoryUiActions
import io.github.xgl34222220.baize.ui.history.HistoryUiState

/**
 * Performance-oriented MIUIx history screen.
 *
 * Long history lists must not allocate a software shadow layer for every row. v2.1.1 keeps the
 * rounded MIUIx grouping and borders, but removes per-item shadows and gives LazyColumn stable
 * content types so rows can be reused while scrolling.
 */
@Composable
fun HistoryScreenMiuix(
    state: HistoryUiState,
    actions: HistoryUiActions
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    AppPackageIconPreloader(state.recentApps.map { it.packageName })

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 146.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "history-header", contentType = "header") {
            HistoryHeader(state.records.isNotEmpty(), actions)
        }
        item(key = "history-overview", contentType = "overview") {
            HistoryOverview(state)
        }

        if (state.hasCurrentResult) {
            item(key = "latest-title", contentType = "title") {
                SectionTitle("LATEST RESULT", "最近一次结果", "按本次实际扫描或清理结果展示")
            }
            item(key = "latest-summary", contentType = "summary") {
                CurrentSummary(state)
            }

            if (state.recentApps.isNotEmpty()) {
                item(key = "apps-title", contentType = "title") {
                    SectionTitle("APP JUNK", "应用垃圾", "点击应用查看内部分类")
                }
                items(
                    items = state.recentApps,
                    key = { "app-${it.packageName}-${it.category}" },
                    contentType = { "app-result" }
                ) { AppResultCard(it) }
            }

            if (state.recentJunk.isNotEmpty()) {
                item(key = "junk-title", contentType = "title") {
                    SectionTitle("OTHER JUNK", "其他垃圾", "安装包、日志、临时文件与碎片")
                }
                items(
                    items = state.recentJunk,
                    key = { "junk-${it.name}-${it.samplePath}" },
                    contentType = { "junk-result" }
                ) { GeneralResultCard(it) }
            }
        }

        if (state.protectedItems.isNotEmpty()) {
            item(key = "protected-title", contentType = "title") {
                SectionTitle("PROTECTED REVIEW", "异常与受保护项目", "展示准确路径和原因；可复查项目由用户手动选择")
            }
            items(
                items = state.protectedItems,
                key = { "protected-${it.id}-${it.path}" },
                contentType = { "protected-result" }
            ) { ProtectedResultCard(it) }
            item(key = "protected-action", contentType = "action") {
                Button(
                    onClick = actions.onReviewProtected,
                    modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Shield, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("重新扫描并手动选择可清理项目", fontWeight = FontWeight.Bold)
                }
            }
        }

        item(key = "records-title", contentType = "title") {
            SectionTitle("CLEAN HISTORY", "最近任务", "服务端分页读取；当前显示最近 30 次任务")
        }

        if (state.records.isEmpty()) {
            item(key = "records-empty", contentType = "empty") { EmptyHistoryCard() }
        } else {
            items(
                items = state.records,
                key = { "history-${it.time}-${it.title}-${it.trigger}" },
                contentType = { "record" }
            ) { RecordCard(it) }
        }
    }
}

@Composable
private fun HistoryHeader(canClear: Boolean, actions: HistoryUiActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "CLEAN HISTORY",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.2.sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "清理记录",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 34.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                "轻量列表 · 无逐卡片阴影",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
        HeaderButton(Icons.Rounded.Refresh, "刷新记录", true, actions.onRefresh)
        Spacer(Modifier.width(8.dp))
        HeaderButton(Icons.Rounded.DeleteForever, "清空记录", canClear, actions.onClearHistory)
    }
}

@Composable
private fun HeaderButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(50.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .06f))
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = description)
        }
    }
}

@Composable
private fun HistoryOverview(state: HistoryUiState) {
    val context = LocalContext.current
    GroupSurface {
        Column(Modifier.padding(21.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (state.lifetimeRuns > 0) Color(0xFF2DBE87) else Color(0xFFF2A93B))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.lifetimeRuns > 0) "已记录 ${state.lifetimeRuns} 次任务" else "等待第一次任务",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(18.dp))
            Text("累计释放", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text(
                Formatter.formatFileSize(context, state.lifetimeReleased),
                fontSize = 39.sp,
                lineHeight = 43.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(17.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(21.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .04f))
                    .padding(horizontal = 13.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Metric("${state.lifetimeFiles} 项", "处理文件")
                Metric("${state.lifetimeEmptyDirs} 个", "空目录")
                Metric(formatElapsed(state.lifetimeElapsed), "累计耗时")
            }
        }
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Black)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
    }
}

@Composable
private fun CurrentSummary(state: HistoryUiState) {
    val context = LocalContext.current
    GroupSurface {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(Icons.Rounded.CheckCircle, false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("本次结果", fontSize = 17.sp, fontWeight = FontWeight.Black)
                if (state.lastTaskTime.isNotBlank()) {
                    Text("执行时间 ${state.lastTaskTime}", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "${state.currentItemCount} 项 · ${state.recentApps.size} 个应用 · ${state.recentJunk.size} 类其他垃圾",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }
            Text(
                Formatter.formatFileSize(context, state.currentBytes),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun AppResultCard(item: AppJunkUiItem) {
    val context = LocalContext.current
    var expanded by rememberSaveable(item.packageName, item.category) { mutableStateOf(false) }
    val hasDetails = item.categories.isNotEmpty()
    GroupSurface(
        modifier = Modifier.clickable(enabled = hasDetails) { expanded = !expanded }
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            ResultHeader(
                icon = Icons.Rounded.Apps,
                packageName = item.packageName,
                title = item.label,
                subtitle = item.category.ifBlank { item.packageName },
                bytes = Formatter.formatFileSize(context, item.bytes),
                meta = if (item.errors > 0) "${item.files} 项 · 异常 ${item.errors}" else "${item.files} 项",
                expanded = expanded,
                expandable = hasDetails,
                error = item.errors > 0
            )
            if (expanded && hasDetails) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .06f))
                item.categories.forEach { category ->
                    DetailRow(
                        title = category.name,
                        subtitle = category.samplePath,
                        value = "${category.files} 项 · ${Formatter.formatFileSize(context, category.bytes)}"
                    )
                }
            }
        }
    }
}

@Composable
private fun GeneralResultCard(item: GeneralJunkUiItem) {
    val context = LocalContext.current
    GroupSurface {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ResultHeader(
                icon = Icons.Rounded.Folder,
                title = item.name,
                subtitle = item.samplePath.ifBlank { "未提供示例路径" },
                bytes = Formatter.formatFileSize(context, item.bytes),
                meta = if (item.errors > 0) "${item.files} 项 · 异常 ${item.errors}" else "${item.files} 项",
                expanded = false,
                expandable = false,
                error = item.errors > 0
            )
        }
    }
}

@Composable
private fun ResultHeader(
    icon: ImageVector,
    packageName: String? = null,
    title: String,
    subtitle: String,
    bytes: String,
    meta: String,
    expanded: Boolean,
    expandable: Boolean,
    error: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (packageName.isNullOrBlank()) IconTile(icon, error)
        else AppPackageIcon(packageName, title, size = 50.dp, corner = 15.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Spacer(Modifier.width(4.dp))
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
private fun ProtectedResultCard(item: ProtectedUiItem) {
    val tint = if (item.selectable) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    GroupSurface {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(16.dp)).background(tint.copy(alpha = .12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(if (item.selectable) Icons.Rounded.Shield else Icons.Rounded.Lock, null, tint = tint)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.category.ifBlank { "受保护项目" }, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(item.reason.ifBlank { "未提供保护原因" }, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    item.path,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 9.sp,
                    lineHeight = 14.sp
                )
            }
            Text(if (item.selectable) "可复查" else "硬保护", color = tint, fontSize = 9.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun RecordCard(item: HistoryUiItem) {
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

    GroupSurface(
        modifier = Modifier.clickable(enabled = hasDetails) { expanded = !expanded }
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(
                    icon = if (item.errors > 0) Icons.Rounded.ErrorOutline else Icons.Rounded.CleaningServices,
                    error = item.errors > 0
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${item.time} · ${item.trigger}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                    Text(historyStatus(item), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
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
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .06f))
                item.categories.forEach { detail ->
                    DetailRow(
                        title = detail.name,
                        subtitle = "垃圾分类",
                        value = "${detail.files} 项 · ${Formatter.formatFileSize(context, detail.bytes)}"
                    )
                }
                item.apps.forEach { app ->
                    DetailRow(
                        title = app.label,
                        subtitle = app.category.ifBlank { app.packageName },
                        value = "${app.files} 项 · ${Formatter.formatFileSize(context, app.bytes)}"
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(title: String, subtitle: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(value, color = MaterialTheme.colorScheme.primary, fontSize = 9.sp)
    }
}

@Composable
private fun EmptyHistoryCard() {
    GroupSurface {
        Column(
            Modifier.fillMaxWidth().padding(25.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Rounded.History,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            Text("还没有清理记录", fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text(
                "完成一次扫描或清理后，这里会显示垃圾分类、涉及应用和释放空间。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun IconTile(icon: ImageVector, error: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (error) scheme.errorContainer else scheme.primary.copy(alpha = .11f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (error) scheme.onErrorContainer else scheme.primary
        )
    }
}

@Composable
private fun GroupSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val settings = LocalAppearanceSettings.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val amoled = dark && settings.amoledBlack
    val background = when {
        amoled -> Color(0xFF080808)
        dark -> scheme.surfaceContainerHigh
        else -> scheme.surface
    }
    Surface(
        modifier = modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = RoundedCornerShape(27.dp),
        color = background,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, scheme.onSurface.copy(alpha = if (dark) .08f else .05f))
    ) { content() }
}

@Composable
private fun SectionTitle(eyebrow: String, title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 3.dp)) {
        Text(
            eyebrow,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
        Text(title, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
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
