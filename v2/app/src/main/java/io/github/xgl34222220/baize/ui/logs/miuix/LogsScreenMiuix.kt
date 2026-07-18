package io.github.xgl34222220.baize.ui.logs.miuix

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
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.logs.LogLevel
import io.github.xgl34222220.baize.ui.logs.LogUiItem
import io.github.xgl34222220.baize.ui.logs.LogsUiActions
import io.github.xgl34222220.baize.ui.logs.LogsUiState

@Composable
fun LogsScreenMiuix(
    state: LogsUiState,
    actions: LogsUiActions
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 146.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { MiuixLogsHeader(state.logs.isNotEmpty(), actions) }
        item { MiuixRuntimeOverview(state) }
        item { MiuixSectionTitle("DIAGNOSTICS", "诊断工具", "服务恢复、清理明细与崩溃记录") }
        item { MiuixDiagnostics(actions) }
        item { MiuixSectionTitle("RUNTIME LOGS", "最近运行日志", "由真实任务记录和当前服务状态生成") }

        if (state.logs.isEmpty()) {
            item { MiuixEmptyLogs() }
        } else {
            items(state.logs, key = { it.key }) { item ->
                MiuixLogCard(item)
            }
        }
    }
}

@Composable
private fun MiuixLogsHeader(
    canClear: Boolean,
    actions: LogsUiActions
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
                "SYSTEM LOGS",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.4.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "运行日志",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 36.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                "Miuix 紧凑状态与诊断",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        MiuixHeaderButton(
            icon = Icons.Rounded.Refresh,
            description = "刷新日志",
            enabled = true,
            onClick = actions.onRefresh
        )
        Spacer(Modifier.width(8.dp))
        MiuixHeaderButton(
            icon = Icons.Rounded.DeleteForever,
            description = "清空任务日志",
            enabled = canClear,
            onClick = actions.onClearTaskLogs
        )
    }
}

@Composable
private fun MiuixHeaderButton(
    icon: ImageVector,
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
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .06f), shape)
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxSize()) {
            Icon(icon, contentDescription = description)
        }
    }
}

@Composable
private fun MiuixRuntimeOverview(state: LogsUiState) {
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
    val statusTitle = when {
        state.running -> "任务执行中"
        state.healthy -> "系统运行正常"
        state.connected -> "服务正在准备"
        else -> "服务需要恢复"
    }
    val statusColor = when {
        state.healthy -> Color(0xFF2DBE87)
        state.connected -> Color(0xFFF2A93B)
        else -> scheme.error
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
                Box(Modifier.size(11.dp).clip(CircleShape).background(statusColor))
                Spacer(Modifier.width(8.dp))
                Text(
                    "${state.device} · ${state.android}",
                    color = scheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(23.dp))
            Text("当前状态", color = scheme.onSurfaceVariant, fontSize = 12.sp)
            Text(
                statusTitle,
                color = scheme.onSurface,
                fontSize = 34.sp,
                lineHeight = 39.sp,
                fontWeight = FontWeight.Black
            )

            Spacer(Modifier.height(18.dp))
            MiuixStatusRow("服务", state.serviceText)
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = scheme.onSurface.copy(alpha = .07f))
            MiuixStatusRow("任务", state.taskPhase)
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = scheme.onSurface.copy(alpha = .07f))
            MiuixStatusRow("调度", state.schedulerText)

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                MiuixStatePill("连接", state.connected)
                MiuixStatePill("就绪", state.ready)
                MiuixStatePill("执行", state.running)
                MiuixStatePill("异常 ${state.errorCount}", state.errorCount == 0)
            }
        }
    }
}

@Composable
private fun MiuixStatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.width(45.dp),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun MiuixStatePill(label: String, positive: Boolean) {
    val background = if (positive) {
        MaterialTheme.colorScheme.primary.copy(alpha = .12f)
    } else {
        MaterialTheme.colorScheme.error.copy(alpha = .12f)
    }
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        color = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        fontSize = 9.sp,
        fontWeight = FontWeight.Black
    )
}

@Composable
private fun MiuixDiagnostics(actions: LogsUiActions) {
    MiuixGroupSurface {
        MiuixToolRow(
            icon = Icons.Rounded.RestartAlt,
            title = "重新连接 Root 服务",
            subtitle = "重新建立服务连接并刷新模块状态",
            onClick = actions.onReconnect
        )
        HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))
        MiuixToolRow(
            icon = Icons.Rounded.Description,
            title = "清理明细",
            subtitle = "查看最近扫描和清理的分类结果",
            onClick = actions.onOpenAudit
        )
        HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))
        MiuixToolRow(
            icon = Icons.Rounded.BugReport,
            title = "崩溃诊断",
            subtitle = "查看并清除最近 App 崩溃记录",
            onClick = actions.onOpenCrashDiagnostics
        )
    }
}

@Composable
private fun MiuixToolRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = .12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp
            )
        }
    }
}

@Composable
private fun MiuixLogCard(item: LogUiItem) {
    val context = LocalContext.current
    val (icon, tint) = when (item.level) {
        LogLevel.SUCCESS -> Icons.Rounded.CheckCircle to Color(0xFF2DBE87)
        LogLevel.WARNING -> Icons.Rounded.Search to Color(0xFFF2A93B)
        LogLevel.ERROR -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
        LogLevel.INFO -> Icons.Rounded.Description to MaterialTheme.colorScheme.primary
    }

    MiuixGroupSurface {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(tint.copy(alpha = .12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint)
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
                    item.message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Formatter.formatFileSize(context, item.bytes),
                    color = tint,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    if (item.errors > 0) "异常 ${item.errors}" else "${item.files} 项",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
private fun MiuixGroupSurface(
    content: @Composable Column.() -> Unit
) {
    val settings = LocalAppearanceSettings.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val amoled = dark && settings.amoledBlack
    val shape = RoundedCornerShape(29.dp)
    val background = when {
        amoled -> Color(0xFF090909)
        dark -> scheme.surfaceContainerHigh
        else -> scheme.surface
    }

    Column(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .shadow(6.dp, shape, clip = false)
            .clip(shape)
            .background(background)
            .border(1.dp, scheme.onSurface.copy(alpha = if (dark) .08f else .05f), shape),
        content = content
    )
}

@Composable
private fun MiuixSectionTitle(
    eyebrow: String,
    title: String,
    subtitle: String
) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 5.dp)) {
        Text(
            eyebrow,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.2.sp
        )
        Text(title, fontSize = 27.sp, lineHeight = 31.sp, fontWeight = FontWeight.Black)
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun MiuixEmptyLogs() {
    MiuixGroupSurface {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(27.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Rounded.Description,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            Text("还没有任务日志", fontSize = 19.sp, fontWeight = FontWeight.Black)
            Text(
                "完成一次扫描或清理后，这里会显示任务结果、大小和异常数量。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }
    }
}
