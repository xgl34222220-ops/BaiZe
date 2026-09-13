package io.github.xgl34222220.baize.ui.home.miuix

import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.home.homeTaskItems
import io.github.xgl34222220.baize.ui.home.nextTask
import io.github.xgl34222220.baize.ui.home.rememberHomeNowEpoch
import io.github.xgl34222220.baize.ui.home.taskCountdownLabel
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
import io.github.xgl34222220.baize.ui.miuix.VideoCard
import io.github.xgl34222220.baize.ui.miuix.VideoIconButton
import io.github.xgl34222220.baize.ui.miuix.VideoStatusPill
import io.github.xgl34222220.baize.ui.miuix.VideoTopBar
import io.github.xgl34222220.baize.ui.miuix.glassSurface
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlin.math.roundToInt

/** Storage, task progress and scan results each retain their real meaning in the same primary card. */
@Composable
fun VideoHomeScreenMiuix(
    state: DashboardUiState,
    scheduler: SchedulerUiState,
    actions: DashboardActions,
    onOpenClean: () -> Unit,
    onOpenPlan: () -> Unit = onOpenClean
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val nowEpoch = rememberHomeNowEpoch()
    val nextTask = scheduler.homeTaskItems().nextTask(nowEpoch)
    val horizontal = Modifier.padding(horizontal = BaiZeTokens.spacing.pageHorizontal).fillMaxWidth()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 118.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") {
            VideoTopBar("白泽", actions = {
                VideoIconButton(Icons.Rounded.Refresh, "刷新状态", actions.refresh)
            })
        }
        item(key = "space") {
            HomeSpaceCard(state, actions, horizontal)
        }
        item(key = "shortcuts") {
            Column(horizontal, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("常用工具", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onOpenClean, contentPadding = PaddingValues(start = 10.dp),
                        modifier = Modifier.heightIn(min = 44.dp)) {
                        Text("全部工具", style = MaterialTheme.typography.labelMedium)
                        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp))
                    }
                }
                val tools = listOf(
                    HomeTool(Icons.Rounded.InstallMobile, "安装包", "查找并清理安装文件", scheme.primary, actions.apkScan),
                    HomeTool(Icons.Rounded.FolderCopy, "文件归类", "整理散落的文件", scheme.tertiary, actions.organize),
                    HomeTool(Icons.Rounded.CleaningServices, "深度清理", "查看应用占用与残留", scheme.primary, actions.deep),
                    HomeTool(Icons.Rounded.Shield, "白名单", "保留指定应用与路径", scheme.tertiary, actions.whitelist)
                )
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (maxWidth.value / LocalDensity.current.fontScale < 220f) 1 else 2
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        tools.chunked(columns).forEach { group ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                group.forEach { tool -> HomeToolCard(tool, Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
        item(key = "activity") {
            Column(horizontal, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("清理与计划", Modifier.padding(top = 8.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.titleMedium)
                VideoCard {
                    Row(Modifier.fillMaxWidth().padding(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        HomeMetric("累计释放", Formatter.formatFileSize(context, state.lifetimeReleased), Modifier.weight(1f))
                        HomeMetric("完成清理", "${state.lifetimeRuns} 次", Modifier.weight(1f))
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp),
                        color = scheme.onSurface.copy(alpha = .055f))
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpenPlan)
                        .padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HomeIcon(Icons.Rounded.CalendarMonth, scheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("自动清理", style = MaterialTheme.typography.titleMedium)
                            Text(if (scheduler.enabled) taskCountdownLabel(nextTask, nowEpoch, scheduler)
                                else "设置清理时间与保留规则",
                                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            Text(if (scheduler.enabled) "已开启" else "未开启",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (scheduler.enabled) BaiZeTokens.colors.success else scheme.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp),
                            tint = scheme.onSurfaceVariant.copy(alpha = .55f))
                    }
                    if (state.lastTaskTime.isNotBlank()) {
                        Text("上次清理 ${state.lastTaskTime} · 释放 ${Formatter.formatFileSize(context, state.lastReleased)}",
                            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSpaceCard(state: DashboardUiState, actions: DashboardActions, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val progress = if (state.taskProgressTotal > 0)
        (state.taskProgressCurrent.toFloat() / state.taskProgressTotal).coerceIn(0f, 1f) else 0f
    val value = when {
        state.running && state.taskProgressTotal > 0 -> "${(progress * 100).roundToInt()}%"
        state.running -> "处理中"
        state.scanCompleted && state.scanFiles > 0 && state.scanBytes > 0 -> Formatter.formatFileSize(context, state.scanBytes)
        state.scanCompleted && state.scanFiles > 0 -> "${state.scanFiles} 项"
        state.scanCompleted && state.scanErrors > 0 -> "未完成"
        state.scanCompleted -> "已扫描"
        state.storageTotal > 0 -> Formatter.formatFileSize(context, state.storageFree)
        else -> "—"
    }
    val valueLabel = when {
        state.running -> "当前任务"
        state.scanCompleted && state.scanFiles > 0 -> "本次可清理"
        state.scanCompleted && state.scanErrors > 0 -> "扫描有异常"
        state.scanCompleted -> "暂无待清理文件"
        else -> "可用空间"
    }
    val description = when {
        state.running -> state.taskPhase.ifBlank { "正在处理文件" }
        state.scanCompleted && state.scanFiles > 0 -> "发现 ${state.scanFiles} 个可清理文件" +
            if (state.scanErrors > 0) " · ${state.scanErrors} 处未完成" else ""
        state.scanCompleted && state.scanErrors > 0 -> "${state.scanErrors} 处扫描异常，请查看记录后重试"
        state.scanCompleted -> "本次扫描未发现可清理文件"
        !state.ready -> state.serviceText
        state.storageTotal > 0 -> "已用 ${Formatter.formatFileSize(context, state.storageUsed)} · 共 ${Formatter.formatFileSize(context, state.storageTotal)}"
        else -> "扫描缓存、安装包与应用残留"
    }
    val accent = if (state.scanCompleted && state.scanErrors > 0 && !state.running)
        BaiZeTokens.colors.warning else scheme.primary
    val shape = RoundedCornerShape(28.dp)
    val cardColor = BaiZeTokens.colors.surfaceRaised
    Surface(modifier = modifier.glassSurface(cardColor, shape, scheme.surface.luminance() < .3f),
        shape = shape, color = Color.Transparent, contentColor = scheme.onSurface) {
        BoxWithConstraints(Modifier.fillMaxWidth()
            .background(Brush.linearGradient(listOf(scheme.primaryContainer.copy(alpha = .46f), Color.Transparent)))
            .padding(22.dp)) {
            val compact = maxWidth.value / LocalDensity.current.fontScale < 220f
            Column(Modifier.fillMaxWidth()) {
                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("空间概览", style = MaterialTheme.typography.titleMedium)
                        HomeServiceStatus(state)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("空间概览", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        HomeServiceStatus(state)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(value, color = scheme.onSurface, style = BaiZeTokens.type.hero.copy(
                    fontSize = if (value.length > 9) 36.sp else 42.sp,
                    lineHeight = if (value.length > 9) 43.sp else 50.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = (-1.2).sp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(valueLabel, Modifier.weight(1f), color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                    when {
                        state.running -> Text("${state.taskProgressFiles} 个文件",
                            style = MaterialTheme.typography.labelSmall, color = accent)
                        !state.scanCompleted && state.storageTotal > 0 -> Text(
                            "${(state.storagePercent.coerceIn(0f, 1f) * 100).roundToInt()}% 已使用",
                            style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                    }
                }
                Spacer(Modifier.height(16.dp))
                when {
                    state.running -> {
                        if (state.taskProgressTotal > 0) LinearProgressIndicator(
                            progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = accent, trackColor = accent.copy(alpha = .10f))
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = accent, trackColor = accent.copy(alpha = .10f))
                        Spacer(Modifier.height(8.dp))
                    }
                    !state.scanCompleted && state.storageTotal > 0 -> {
                        StorageSegments(state.storagePercent)
                        Spacer(Modifier.height(8.dp))
                    }
                }
                Text(description, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                    color = if (state.scanCompleted && state.scanErrors > 0) accent else scheme.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))
                HomePrimaryAction(state, actions)
                if (!state.running && (state.ready || state.scanCompleted)) {
                    if (compact) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            HomeSecondaryActions(state, actions)
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            HomeSecondaryActions(state, actions)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeServiceStatus(state: DashboardUiState) {
    VideoStatusPill(
        when {
            state.running -> "任务进行中"
            state.connectionFailed -> "连接异常"
            state.ready -> "服务已就绪"
            state.connecting -> "正在连接"
            else -> "等待连接"
        }, positive = state.ready && !state.running && !state.connectionFailed
    )
}

@Composable
private fun HomePrimaryAction(state: DashboardUiState, actions: DashboardActions) {
    val label = when {
        state.running -> "停止当前任务"
        state.scanCompleted && state.scanFiles > 0 -> "清理扫描结果"
        state.scanCompleted -> "重新扫描"
        state.connecting -> "正在连接…"
        !state.ready -> "连接 Root 服务"
        else -> "开始扫描"
    }
    val action = when {
        state.running -> actions.stop
        state.scanCompleted && state.scanFiles > 0 -> actions.cleanScan
        state.scanCompleted -> actions.scan
        !state.ready -> actions.reconnect
        else -> actions.scan
    }
    GlassActionButton(label, action, Modifier.fillMaxWidth(), icon = when {
        state.running -> Icons.Rounded.Stop
        state.scanCompleted && state.scanFiles > 0 -> Icons.Rounded.CleaningServices
        !state.ready && !state.scanCompleted -> Icons.Rounded.Security
        else -> Icons.Rounded.Search
    }, enabled = state.running || !state.connecting || state.scanCompleted)
}

@Composable
private fun HomeSecondaryActions(state: DashboardUiState, actions: DashboardActions) {
    if (!state.scanCompleted || state.scanFiles > 0) {
        TextButton(onClick = if (state.scanCompleted) actions.scan else actions.clean,
            modifier = Modifier.heightIn(min = 44.dp)) {
            Text(if (state.scanCompleted) "重新扫描" else "按现有规则清理",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (state.scanCompleted) TextButton(onClick = actions.dismissScan, modifier = Modifier.heightIn(min = 44.dp)) {
        Text("收起结果", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The single continuous ratio is divided visually, without implying invented storage categories. */
@Composable
private fun StorageSegments(usedFraction: Float) {
    val fraction = usedFraction.coerceIn(0f, 1f)
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val track = primary.copy(alpha = .09f)
    Canvas(Modifier.fillMaxWidth().height(8.dp).semantics {
        contentDescription = "存储空间已使用 ${(fraction * 100).roundToInt()}%"
    }) {
        val count = 24
        val gap = 3.dp.toPx()
        val segment = (size.width - gap * (count - 1)) / count
        val radius = CornerRadius(2.dp.toPx())
        repeat(count) { index ->
            drawRoundRect(track, topLeft = Offset(index * (segment + gap), 0f),
                size = Size(segment, size.height), cornerRadius = radius)
        }
        clipRect(right = size.width * fraction) {
            val fill = Brush.horizontalGradient(listOf(primary, lerp(primary, secondary, .55f)))
            repeat(count) { index ->
                drawRoundRect(fill, topLeft = Offset(index * (segment + gap), 0f),
                    size = Size(segment, size.height), cornerRadius = radius)
            }
        }
    }
}

@Composable
private fun HomeToolCard(tool: HomeTool, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    VideoCard(modifier) {
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = tool.onClick)
            .heightIn(min = 132.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HomeIcon(tool.icon, tool.color)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp),
                    tint = scheme.onSurfaceVariant.copy(alpha = .38f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(tool.title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                Text(tool.description, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, minLines = 2)
            }
        }
    }
}

@Composable
private fun HomeIcon(icon: ImageVector, tint: Color) {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp))
        .background(Brush.verticalGradient(listOf(tint.copy(alpha = .12f), tint.copy(alpha = .04f)))),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(21.dp), tint = tint)
    }
}

private data class HomeTool(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val color: Color,
    val onClick: () -> Unit
)

@Composable
private fun HomeMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, style = BaiZeTokens.type.display.copy(fontSize = 23.sp, lineHeight = 29.sp),
            color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
