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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
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
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The dial reports real storage or task state; it never invents a cleanliness score. */
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
    val horizontal = Modifier.padding(horizontal = 20.dp).fillMaxWidth()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 118.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "header") {
            VideoTopBar("白泽", actions = {
                VideoIconButton(Icons.Rounded.Refresh, "刷新状态", actions.refresh)
            })
        }
        item(key = "space") {
            Column(horizontal, horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("空间概览", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    VideoStatusPill(
                        when {
                            state.running -> "任务进行中"
                            state.connectionFailed -> "连接异常"
                            state.ready -> "服务已就绪"
                            state.connecting -> "正在连接"
                            else -> "等待连接"
                        },
                        positive = state.ready && !state.running && !state.connectionFailed
                    )
                }
                Spacer(Modifier.height(10.dp))
                StorageDial(state)
                Spacer(Modifier.height(6.dp))
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
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(18.dp))
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
                GlassActionButton(
                    label, action, Modifier.fillMaxWidth(.88f),
                    icon = when {
                        state.running -> Icons.Rounded.Stop
                        state.scanCompleted && state.scanFiles > 0 -> Icons.Rounded.CleaningServices
                        !state.ready && !state.scanCompleted -> Icons.Rounded.Security
                        else -> Icons.Rounded.Search
                    },
                    enabled = state.running || !state.connecting || state.scanCompleted
                )
                if (!state.running && (state.ready || state.scanCompleted)) {
                    Row(horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = if (state.scanCompleted) actions.scan else actions.clean,
                            modifier = Modifier.heightIn(min = 44.dp)) {
                            Text(if (state.scanCompleted) "重新扫描" else "按现有规则清理",
                                style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        }
                        if (state.scanCompleted) TextButton(onClick = actions.dismissScan,
                            modifier = Modifier.heightIn(min = 44.dp)) {
                            Text("收起结果", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item(key = "shortcuts") {
            VideoCard(horizontal, contentPadding = 12) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val compact = maxWidth < 300.dp && LocalDensity.current.fontScale > 1.1f
                    val tools = listOf(
                        HomeTool(Icons.Rounded.InstallMobile, "安装包", scheme.primary, actions.apkScan),
                        HomeTool(Icons.Rounded.FolderCopy, "文件归类", Color(0xFF209B91), actions.organize),
                        HomeTool(Icons.Rounded.CleaningServices, "深度清理", Color(0xFF8874D3), actions.deep),
                        HomeTool(Icons.Rounded.Shield, "白名单", Color(0xFFBE8A42), actions.whitelist)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        tools.chunked(if (compact) 2 else 4).forEach { group ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 2.dp)) {
                                group.forEach { tool ->
                                    HomeShortcut(tool.icon, tool.title, tool.color, tool.onClick, Modifier.weight(1f), compact)
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "metrics") {
            Row(horizontal.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                HomeMetric("累计释放", Formatter.formatFileSize(context, state.lifetimeReleased), Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(30.dp).background(scheme.onSurface.copy(alpha = .08f)))
                HomeMetric("完成清理", "${state.lifetimeRuns} 次", Modifier.weight(1f).padding(start = 24.dp))
            }
        }
        item(key = "plan") {
            VideoCard(horizontal) {
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpenPlan)
                    .padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(22.dp), tint = scheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("自动清理", style = MaterialTheme.typography.titleSmall)
                        Text(if (scheduler.enabled) taskCountdownLabel(nextTask, nowEpoch, scheduler) else "设置适合你的清理节奏",
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(if (scheduler.enabled) "已开启" else "未开启", style = MaterialTheme.typography.labelSmall,
                        color = if (scheduler.enabled) BaiZeTokens.colors.success else scheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant.copy(alpha = .55f))
                }
            }
        }
        if (state.lastTaskTime.isNotBlank()) item(key = "last-task") {
            Text("上次清理 ${state.lastTaskTime} · 释放 ${Formatter.formatFileSize(context, state.lastReleased)}",
                modifier = horizontal.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant)
        }
        item(key = "all-tools") {
            TextButton(onClick = onOpenClean, modifier = horizontal.heightIn(min = 48.dp)) {
                Text("查看全部清理工具", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun StorageDial(state: DashboardUiState) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val dark = scheme.background.luminance() < .5f
    val accent = if (state.scanCompleted && state.scanErrors > 0 && !state.running)
        BaiZeTokens.colors.warning else scheme.primary
    val progress = when {
        state.running && state.taskProgressTotal > 0 -> state.taskProgressCurrent.toFloat() / state.taskProgressTotal
        state.running -> 0f
        state.scanCompleted && state.scanErrors == 0L -> 1f
        state.storageTotal > 0 -> state.storagePercent
        else -> 0f
    }.coerceIn(0f, 1f)
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
    val label = when {
        state.running -> "当前任务"
        state.scanCompleted && state.scanFiles > 0 -> "本次可清理"
        state.scanCompleted && state.scanErrors > 0 -> "扫描有异常"
        state.scanCompleted -> "暂无待清理文件"
        else -> "可用空间"
    }
    val diameter = 204.dp + (24 * (LocalDensity.current.fontScale - 1f).coerceIn(0f, .5f)).dp
    val discTop = if (dark) Color(0xFF263546) else Color.White
    val discBottom = if (dark) Color(0xFF131D2C) else Color(0xFFE4EEF7)
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize(.79f)
            .shadow(if (dark) 12.dp else 18.dp, CircleShape, clip = false,
                ambientColor = Color(0xFF5C87B0).copy(alpha = .16f),
                spotColor = Color(0xFF5C87B0).copy(alpha = .18f))
            .background(Brush.linearGradient(listOf(discTop, discBottom)), CircleShape))
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val inset = 9.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(accent.copy(alpha = if (dark) .14f else .10f), 135f, 270f, false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (progress > 0f) drawArc(
                brush = Brush.sweepGradient(listOf(accent, lerp(accent, Color(0xFF42C4C0), .45f), accent)),
                startAngle = 135f, sweepAngle = 270f * progress, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
            )
            val radius = size.minDimension * .5f - inset - 10.dp.toPx()
            repeat(31) { index ->
                val angle = Math.toRadians((135 + index * 9).toDouble())
                val a = Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius)
                val length = if (index % 5 == 0) 4.dp.toPx() else 2.dp.toPx()
                val b = Offset(center.x + cos(angle).toFloat() * (radius - length), center.y + sin(angle).toFloat() * (radius - length))
                drawLine(accent.copy(alpha = if (index % 5 == 0) .28f else .13f), a, b, 1.dp.toPx(), StrokeCap.Round)
            }
            drawCircle(Brush.linearGradient(listOf(Color.White.copy(alpha = if (dark) .15f else .95f), Color.White.copy(alpha = .02f))),
                radius = size.minDimension * .39f, style = Stroke(1.dp.toPx()))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (state.scanCompleted && state.scanFiles == 0L && state.scanErrors == 0L && !state.running) {
                Icon(Icons.Rounded.Check, null, Modifier.size(24.dp), tint = BaiZeTokens.colors.success)
            }
            Text(label, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Text(value, color = scheme.onSurface, fontSize = if (value.length > 9) 26.sp else 32.sp,
                lineHeight = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!state.running && !state.scanCompleted && state.storageTotal > 0) {
                Text("${(state.storagePercent.coerceIn(0f, 1f) * 100).roundToInt()}% 已使用",
                    style = MaterialTheme.typography.labelSmall, color = accent)
            } else if (state.running) {
                Text("${state.taskProgressFiles} 个文件", style = MaterialTheme.typography.labelSmall, color = accent)
            }
        }
    }
}

@Composable
private fun HomeShortcut(icon: ImageVector, title: String, color: Color, onClick: () -> Unit, modifier: Modifier, compact: Boolean = false) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val tint = if (dark) lerp(color, Color.White, .25f) else color
    if (compact) {
        Row(modifier.clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp).padding(horizontal = 2.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(32.dp).background(tint.copy(alpha = .08f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = tint) }
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    } else Column(modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick)
        .padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(40.dp).background(Brush.verticalGradient(listOf(tint.copy(alpha = .13f), tint.copy(alpha = .04f))),
            RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = tint)
        }
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private data class HomeTool(val icon: ImageVector, val title: String, val color: Color, val onClick: () -> Unit)

@Composable
private fun HomeMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
