package io.github.xgl34222220.baize.ui.logs.material

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.logs.LogLevel
import io.github.xgl34222220.baize.ui.logs.LogUiItem
import io.github.xgl34222220.baize.ui.logs.LogsUiActions
import io.github.xgl34222220.baize.ui.logs.LogsUiState

@Composable
fun LogsScreenMaterial(
    state: LogsUiState,
    actions: LogsUiActions
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
            item { MaterialLogsHeader(state.logs.isNotEmpty(), actions) }
            item { MaterialRuntimeOverview(state) }
            item { MaterialSectionTitle("RAW OUTPUT", "模块原始输出") }
            item { MaterialRawLogCard(state, actions) }
            item { MaterialSectionTitle("DIAGNOSTICS", "诊断工具") }
            item { MaterialDiagnosticActions(actions) }
            item { MaterialSectionTitle("RUNTIME LOGS", "最近运行日志") }

            if (state.logs.isEmpty()) {
                item { MaterialEmptyLogs() }
            } else {
                items(state.logs, key = { it.key }) { item ->
                    MaterialLogCard(item)
                }
            }
        }
    }
}

@Composable
private fun MaterialLogsHeader(
    canClear: Boolean,
    actions: LogsUiActions
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
                "SYSTEM LOGS",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.3.sp
            )
            Spacer(Modifier.height(5.dp))
            Text("运行日志", style = MaterialTheme.typography.headlineLarge)
            Text(
                "服务、调度、任务与异常诊断",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        FilledTonalIconButton(
            onClick = actions.onRefresh,
            modifier = Modifier.size(54.dp)
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = "刷新日志")
        }

        Spacer(Modifier.width(8.dp))

        FilledTonalIconButton(
            onClick = actions.onClearTaskLogs,
            enabled = canClear,
            modifier = Modifier.size(54.dp)
        ) {
            Icon(Icons.Rounded.DeleteForever, contentDescription = "清空任务日志")
        }
    }
}

@Composable
private fun MaterialRuntimeOverview(state: LogsUiState) {
    val scheme = MaterialTheme.colorScheme
    val status = when {
        state.running -> "任务执行中"
        state.healthy -> "运行状态正常"
        state.connected -> "服务已连接，等待就绪"
        else -> "服务连接异常"
    }
    val statusColor = when {
        state.healthy -> scheme.primary
        state.connected -> scheme.tertiary
        else -> scheme.error
    }

    Card(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = MaterialTheme.shapes.large,
                    color = statusColor.copy(alpha = .14f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (state.healthy) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = statusColor
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(status, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${state.device} · ${state.android}",
                        color = scheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            MaterialStatusLine("服务", state.serviceText)
            HorizontalDivider(Modifier.padding(vertical = 11.dp))
            MaterialStatusLine("任务", state.taskPhase)
            HorizontalDivider(Modifier.padding(vertical = 11.dp))
            MaterialStatusLine("调度", state.schedulerText)

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MaterialStateChip("已连接", state.connected)
                MaterialStateChip("已就绪", state.ready)
                MaterialStateChip("执行中", state.running)
                MaterialStateChip("异常 ${state.errorCount}", state.errorCount == 0)
            }
        }
    }
}

@Composable
private fun MaterialStatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.width(48.dp),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun MaterialStateChip(label: String, positive: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = if (positive) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.errorContainer
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            color = if (positive) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onErrorContainer,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun MaterialRawLogCard(state: LogsUiState, actions: LogsUiActions) {
    val visible = state.rawLog.lineSequence().toList().takeLast(36).joinToString("\n")
    Card(
        modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (state.hasRawLog) state.rawLogName.ifBlank { "最近模块任务.log" } else "暂无模块原始日志",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (state.hasRawLog) "显示最后 36 行真实 cleaner.sh 输出" else "执行模块扫描或清理后自动读取",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
                FilledTonalIconButton(
                    onClick = actions.onClearRawLog,
                    enabled = state.hasRawLog
                ) {
                    Icon(Icons.Rounded.DeleteForever, contentDescription = "清空原始日志")
                }
            }
            if (visible.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        visible,
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun MaterialDiagnosticActions(actions: LogsUiActions) {
    Card(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column {
            MaterialToolRow(
                icon = Icons.Rounded.RestartAlt,
                title = "重新连接 Root 服务",
                subtitle = "服务断开或状态异常时重新建立连接",
                onClick = actions.onReconnect
            )
            HorizontalDivider(Modifier.padding(start = 76.dp))
            MaterialToolRow(
                icon = Icons.Rounded.Description,
                title = "清理明细",
                subtitle = "查看最近扫描与清理的真实分类结果",
                onClick = actions.onOpenAudit
            )
            HorizontalDivider(Modifier.padding(start = 76.dp))
            MaterialToolRow(
                icon = Icons.Rounded.BugReport,
                title = "崩溃诊断",
                subtitle = "查看并清除最近 App 崩溃记录",
                onClick = actions.onOpenCrashDiagnostics
            )
        }
    }
}

@Composable
private fun MaterialToolRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 17.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(46.dp)) {
            Icon(icon, contentDescription = null)
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun MaterialLogCard(item: LogUiItem) {
    val context = LocalContext.current
    val (icon, tint) = when (item.level) {
        LogLevel.SUCCESS -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.primary
        LogLevel.WARNING -> Icons.Rounded.Search to MaterialTheme.colorScheme.tertiary
        LogLevel.ERROR -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
        LogLevel.INFO -> Icons.Rounded.Description to MaterialTheme.colorScheme.secondary
    }

    Card(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            Modifier.padding(17.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.medium,
                color = tint.copy(alpha = .14f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = tint)
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${item.time} · ${item.trigger}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp
                )
                Text(
                    item.message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
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
private fun MaterialSectionTitle(eyebrow: String, title: String) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) {
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

@Composable
private fun MaterialEmptyLogs() {
    Card(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Rounded.CleaningServices,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            Text("还没有任务日志", style = MaterialTheme.typography.titleLarge)
            Text(
                "完成一次扫描或清理后，这里会显示任务时间、结果、大小和异常数量。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}
