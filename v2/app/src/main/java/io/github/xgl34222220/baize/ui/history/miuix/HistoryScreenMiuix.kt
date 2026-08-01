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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * MIUIX 记录页使用原生 Miuix Card、IconButton、Icon 与 Text。
 * 卡片间距和行高与首页、清理页统一，不再混入 Material Surface。
 */
@Composable
fun HistoryScreenMiuix(state: HistoryUiState, actions: HistoryUiActions) {
    val iconPackages = buildList {
        addAll(state.recentApps.map { it.packageName })
        state.records.forEach { record -> addAll(record.apps.map { it.packageName }) }
    }
    AppPackageIconPreloader(iconPackages)

    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val recordGroups = state.records
        .take(50)
        .groupBy { record -> record.time.trim().take(10).ifBlank { "更早记录" } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 132.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "header") { Header(actions) }
        item(key = "lifetime") { LifetimeCard(state) }
        item(key = "current-title") { SectionTitle("最近结果", "最近一次自动任务的结果") }
        item(key = "current") { CurrentResultCard(state) }

        if (state.recentApps.isNotEmpty()) {
            item(key = "apps-title") { SectionTitle("应用垃圾", "点击应用展开分类与示例路径") }
            item(key = "apps") { AppResultGroup(state.recentApps) }
        }
        if (state.recentJunk.isNotEmpty()) {
            item(key = "junk-title") { SectionTitle("其他垃圾", "本次任务处理的非应用内容") }
            item(key = "junk") { JunkResultGroup(state.recentJunk) }
        }

        item(key = "records-title") { SectionTitle("任务记录", "按日期排列最近 50 条任务") }
        if (recordGroups.isEmpty()) {
            item(key = "empty") { EmptyCard() }
        } else {
            recordGroups.forEach { (date, records) ->
                item(key = "date-$date") { DateLabel(date) }
                itemsIndexed(
                    items = records,
                    key = { index, record -> "${record.time}|${record.title}|${record.trigger}|$index" }
                ) { _, record -> RecordCard(record) }
            }
        }

        if (state.protectedItems.isNotEmpty()) {
            item(key = "protected") {
                InfoCard(
                    icon = Icons.Rounded.Security,
                    title = "受保护内容",
                    summary = "白名单、路径和安全策略拦截的内容",
                    trailing = "${state.protectedItems.size} 项",
                    onClick = actions.onReviewProtected
                )
            }
        }
    }
}

@Composable
private fun Header(actions: HistoryUiActions) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 14.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            MiuixText("清理记录", fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            MiuixText(
                "自动任务结果与累计统计",
                color = colors.onSurfaceContainer.copy(alpha = .60f),
                fontSize = 13.sp
            )
        }
        HeaderButton(Icons.Rounded.Refresh, "刷新", actions.onRefresh)
        Spacer(Modifier.width(7.dp))
        HeaderButton(Icons.Rounded.DeleteOutline, "清空记录", actions.onClearHistory)
    }
}

@Composable
private fun HeaderButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    MiuixIconButton(
        onClick = onClick,
        modifier = Modifier.size(42.dp),
        backgroundColor = colors.surfaceContainer,
        cornerRadius = 14.dp,
        minHeight = 42.dp,
        minWidth = 42.dp
    ) {
        MiuixIcon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun LifetimeCard(state: HistoryUiState) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    MiuixCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        cornerRadius = 22.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 17.dp),
        colors = MiuixCardDefaults.defaultColors(
            color = colors.surfaceContainer,
            contentColor = colors.onSurfaceContainer
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.History, true, 42.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                MiuixText(
                    "累计释放",
                    color = colors.onSurfaceContainer.copy(alpha = .58f),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(1.dp))
                MiuixText(
                    Formatter.formatFileSize(context, state.lifetimeReleased),
                    fontSize = 29.sp,
                    lineHeight = 35.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(15.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric("任务", "${state.lifetimeRuns} 次", Modifier.weight(1f))
            Metric("处理文件", state.lifetimeFiles.toString(), Modifier.weight(1f))
            Metric("累计耗时", formatElapsed(state.lifetimeElapsed), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    Column(modifier) {
        MiuixText(label, color = colors.onSurfaceContainer.copy(alpha = .54f), fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        MiuixText(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.padding(horizontal = 20.dp, vertical = 7.dp)) {
        MiuixText(title, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(1.dp))
        MiuixText(
            subtitle,
            color = colors.onSurfaceContainer.copy(alpha = .58f),
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun CurrentResultCard(state: HistoryUiState) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    MiuixCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 15.dp, vertical = 14.dp),
        colors = MiuixCardDefaults.defaultColors(
            color = colors.surfaceContainer,
            contentColor = colors.onSurfaceContainer
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (state.hasCurrentResult) BaiZeTokens.colors.success else colors.onSurfaceContainer.copy(alpha = .30f))
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                MiuixText(
                    currentResultTitle(state.latestResult, state.hasCurrentResult),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                MiuixText(
                    if (state.hasCurrentResult) "处理 ${state.currentItemCount} 项" else "执行任务后在这里显示结果",
                    color = colors.onSurfaceContainer.copy(alpha = .56f),
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            MiuixText(
                Formatter.formatFileSize(context, state.currentBytes),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (state.lastTaskTime.isNotBlank()) {
            Spacer(Modifier.height(11.dp))
            Divider(start = 19.dp)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiuixText("执行时间", modifier = Modifier.weight(1f), fontSize = 12.sp)
                MiuixText(
                    sanitizeText(state.lastTaskTime),
                    color = colors.onSurfaceContainer.copy(alpha = .58f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun AppResultGroup(apps: List<AppJunkUiItem>) {
    val colors = MiuixTheme.colorScheme
    MiuixCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(0.dp),
        colors = MiuixCardDefaults.defaultColors(
            color = colors.surfaceContainer,
            contentColor = colors.onSurfaceContainer
        )
    ) {
        apps.forEachIndexed { index, item ->
            AppResultRow(item)
            if (index != apps.lastIndex) Divider()
        }
    }
}

@Composable
private fun AppResultRow(item: AppJunkUiItem) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    var expanded by rememberSaveable(item.packageName, item.category) { mutableStateOf(false) }
    val hasDetails = item.categories.isNotEmpty()

    Column(
        modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppPackageIcon(
                item.packageName,
                item.label.ifBlank { item.packageName },
                size = 38.dp,
                corner = 12.dp
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                MiuixText(
                    sanitizeText(item.label.ifBlank { item.packageName }),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(1.dp))
                MiuixText(
                    sanitizeText(item.category.ifBlank { item.packageName }),
                    color = colors.onSurfaceContainer.copy(alpha = .54f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                MiuixText(
                    Formatter.formatFileSize(context, item.bytes),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                MiuixText("${item.files} 项", color = colors.onSurfaceContainer.copy(alpha = .52f), fontSize = 10.sp)
            }
            if (hasDetails) {
                Spacer(Modifier.width(5.dp))
                MiuixIconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(34.dp),
                    backgroundColor = colors.surfaceContainerHigh,
                    cornerRadius = 12.dp,
                    minHeight = 34.dp,
                    minWidth = 34.dp
                ) {
                    MiuixIcon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        if (expanded && hasDetails) {
            Spacer(Modifier.height(9.dp))
            Divider(start = 49.dp)
            item.categories.forEach { detail ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 49.dp, top = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        MiuixText(sanitizeText(detail.name), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        MiuixText(
                            sanitizeText(detail.samplePath).ifBlank { "未记录示例路径" },
                            color = colors.onSurfaceContainer.copy(alpha = .52f),
                            fontSize = 9.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    MiuixText(
                        "${detail.files} 项 · ${Formatter.formatFileSize(context, detail.bytes)}",
                        color = colors.onSurfaceContainer.copy(alpha = .56f),
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun JunkResultGroup(items: List<GeneralJunkUiItem>) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    MiuixCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(0.dp),
        colors = MiuixCardDefaults.defaultColors(
            color = colors.surfaceContainer,
            contentColor = colors.onSurfaceContainer
        )
    ) {
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconTile(Icons.Rounded.Folder, true)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    MiuixText(
                        sanitizeText(item.name),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    MiuixText(
                        sanitizeText(item.samplePath).ifBlank { "未记录示例路径" },
                        color = colors.onSurfaceContainer.copy(alpha = .54f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    MiuixText(Formatter.formatFileSize(context, item.bytes), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    MiuixText("${item.files} 项", color = colors.onSurfaceContainer.copy(alpha = .52f), fontSize = 10.sp)
                }
            }
            if (index != items.lastIndex) Divider()
        }
    }
}

@Composable
private fun DateLabel(date: String) {
    MiuixText(
        date,
        modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 1.dp),
        color = MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = .56f),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun EmptyCard() {
    val colors = MiuixTheme.colorScheme
    MiuixCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        cornerRadius = 19.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 17.dp),
        colors = MiuixCardDefaults.defaultColors(
            color = colors.surfaceContainer,
            contentColor = colors.onSurfaceContainer
        )
    ) {
        MiuixText(
            "暂无任务记录",
            color = colors.onSurfaceContainer.copy(alpha = .58f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RecordCard(record: HistoryUiItem) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    var expanded by rememberSaveable(record.time, record.title, record.trigger) { mutableStateOf(false) }
    val hasDetails = record.categories.isNotEmpty() || record.apps.isNotEmpty()
    val title = sanitizeText(record.title).ifBlank { "历史任务" }
    val meta = listOf(sanitizeText(record.time), sanitizeText(record.trigger))
        .filter(String::isNotBlank)
        .joinToString(" · ")
    val summary = sanitizeText(
        when {
            record.apps.isNotEmpty() -> "涉及 ${record.apps.size} 个应用 · ${record.files} 项"
            record.categories.isNotEmpty() -> record.categories.take(2).joinToString(" · ") { it.name }
            record.bytes == 0L && record.files == 0 -> "未发现可清理内容"
            else -> record.result
        }
    ).ifBlank { if (record.cleaned) "清理任务已完成" else "扫描任务已完成" }

    MiuixCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        cornerRadius = 19.dp,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
        colors = MiuixCardDefaults.defaultColors(
            color = colors.surfaceContainer,
            contentColor = colors.onSurfaceContainer
        ),
        onClick = { if (hasDetails) expanded = !expanded }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(if (record.cleaned) Icons.Rounded.CheckCircle else Icons.Rounded.Search, record.cleaned)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                MiuixText(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (meta.isNotBlank()) {
                    MiuixText(
                        meta,
                        color = colors.onSurfaceContainer.copy(alpha = .52f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            MiuixText(
                if (record.cleaned) "已清理" else "扫描完成",
                color = if (record.cleaned) BaiZeTokens.colors.success else colors.primary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (hasDetails) {
                Spacer(Modifier.width(4.dp))
                MiuixIcon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = colors.onSurfaceContainer.copy(alpha = .55f)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.padding(start = 49.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            MiuixText(
                summary,
                modifier = Modifier.weight(1f),
                color = colors.onSurfaceContainer.copy(alpha = .56f),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            MiuixText(
                Formatter.formatFileSize(context, record.bytes),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (expanded && hasDetails) {
            Spacer(Modifier.height(10.dp))
            Divider(start = 49.dp)
            record.apps.forEach { app ->
                DetailRow(
                    title = sanitizeText(app.label.ifBlank { app.packageName }),
                    summary = sanitizeText(app.category.ifBlank { app.packageName }),
                    value = "${app.files} 项 · ${Formatter.formatFileSize(context, app.bytes)}"
                )
            }
            record.categories.forEach { detail ->
                DetailRow(
                    title = sanitizeText(detail.name),
                    summary = "",
                    value = "${detail.files} 项 · ${Formatter.formatFileSize(context, detail.bytes)}"
                )
            }
        }
    }
}

@Composable
private fun DetailRow(title: String, summary: String, value: String) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 49.dp, top = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            MiuixText(title, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (summary.isNotBlank()) {
                MiuixText(summary, color = colors.onSurfaceContainer.copy(alpha = .50f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        MiuixText(value, color = colors.onSurfaceContainer.copy(alpha = .54f), fontSize = 9.sp)
    }
}

@Composable
private fun InfoCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    trailing: String,
    onClick: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    MiuixCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        cornerRadius = 19.dp,
        insideMargin = PaddingValues(horizontal = 15.dp, vertical = 13.dp),
        colors = MiuixCardDefaults.defaultColors(
            color = colors.surfaceContainer,
            contentColor = colors.onSurfaceContainer
        ),
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, true)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                MiuixText(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                MiuixText(summary, color = colors.onSurfaceContainer.copy(alpha = .54f), fontSize = 10.sp)
            }
            MiuixText(trailing, color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun IconTile(icon: androidx.compose.ui.graphics.vector.ImageVector, positive: Boolean, size: Dp = 38.dp) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (positive) colors.primary.copy(alpha = .10f)
                else colors.onSurfaceContainer.copy(alpha = .05f)
            ),
        contentAlignment = Alignment.Center
    ) {
        MiuixIcon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (positive) colors.primary else colors.onSurfaceContainer.copy(alpha = .38f)
        )
    }
}

@Composable
private fun Divider(start: Dp = 58.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = start)
            .height(1.dp)
            .background(MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = .08f))
    )
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

private fun sanitizeText(value: String): String =
    value.replace(Regex("[\\p{Cc}\\p{Cf}\\s]+"), " ").trim()

private fun formatElapsed(seconds: Long): String = when {
    seconds >= 3_600 -> "${seconds / 3_600} 小时"
    seconds >= 60 -> "${seconds / 60} 分钟"
    else -> "${seconds} 秒"
}
