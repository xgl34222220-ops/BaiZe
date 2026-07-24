package io.github.xgl34222220.baize.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

@Composable
internal fun SchedulerHealthOverlay(
    state: SettingsUiState,
    actions: SettingsUiActions,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }
    val scheduler = state.scheduler
    val healthy = state.connected && state.ready && !scheduler.runtimeStale
    val disabled = !scheduler.enabled
    val container = when {
        !state.connected || scheduler.runtimeStale -> MaterialTheme.colorScheme.errorContainer
        disabled -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val content = when {
        !state.connected || scheduler.runtimeStale -> MaterialTheme.colorScheme.onErrorContainer
        disabled -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable { showDialog = true },
        shape = RoundedCornerShape(18.dp),
        color = container,
        shadowElevation = 5.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (healthy) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = when {
                    !state.connected || scheduler.runtimeStale -> "自动任务异常"
                    disabled -> "自动任务已关闭"
                    scheduler.runtimeState == "running" -> "自动任务执行中"
                    else -> "自动任务体检"
                },
                color = content,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    if (showDialog) {
        SchedulerHealthDialog(
            state = state,
            actions = actions,
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun SchedulerHealthDialog(
    state: SettingsUiState,
    actions: SettingsUiActions,
    onDismiss: () -> Unit
) {
    val scheduler = state.scheduler
    val reasonCode = schedulerReasonCode(scheduler, state.connected, state.ready)
    val blocked = blockedSummary(scheduler.blockedGroups)
    val healthy = state.connected && state.ready && !scheduler.runtimeStale

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (healthy) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = if (healthy) BaiZeTokens.colors.success else BaiZeTokens.colors.warning
            )
        },
        title = {
            Text(
                when {
                    !state.connected -> "Root 服务未连接"
                    scheduler.runtimeStale -> "后台服务需要修复"
                    !scheduler.enabled -> "自动任务已关闭"
                    scheduler.runtimeState == "running" -> "自动任务正在执行"
                    blocked.isNotBlank() -> "任务正在等待条件"
                    else -> "自动任务运行正常"
                }
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HealthStatusCard(
                    healthy = healthy,
                    title = scheduler.runtimeReason,
                    detail = "原因码：$reasonCode"
                )
                HealthLine("Root 引擎", if (state.ready) "组件完整" else state.serviceText)
                HealthLine("Supervisor", supervisorSummary(scheduler))
                HealthLine(
                    "等待队列",
                    if (scheduler.queueCount > 0) {
                        "${scheduler.queueCount} 项 · ${groupLabel(scheduler.nextTask)} 优先"
                    } else {
                        "当前没有到期任务"
                    }
                )
                if (blocked.isNotBlank()) HealthLine("阻塞详情", blocked)
                HealthLine("下次检查", nextCheckSummary(scheduler.nextCheckEpoch))
                Text(
                    "体检与修复只检查服务、心跳、队列和陈旧锁，不会修改任何定时周期、任务开关或清理规则。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(2.dp))
                Button(
                    onClick = { actions.onSchedulerCommand("scheduler-self-test") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.SettingsSuggest, contentDescription = null)
                    Text("运行无破坏体检", modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = { actions.onSchedulerCommand("scheduler-repair") },
                    enabled = !state.running,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Text(
                        if (state.running) "任务执行中，暂不可修复" else "一键修复后台服务",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                OutlinedButton(
                    onClick = { actions.onSchedulerCommand("scheduler-export-diagnostics") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Description, contentDescription = null)
                    Text("导出脱敏诊断包", modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

@Composable
private fun HealthStatusCard(healthy: Boolean, title: String, detail: String) {
    val background = if (healthy) {
        BaiZeTokens.colors.success.copy(alpha = .12f)
    } else {
        BaiZeTokens.colors.warning.copy(alpha = .14f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .padding(14.dp)
    ) {
        Text(title.ifBlank { "等待调度器返回状态" }, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(3.dp))
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun HealthLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            modifier = Modifier.padding(top = 1.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            modifier = Modifier.fillMaxWidth(.68f),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun schedulerReasonCode(scheduler: SchedulerUiState, connected: Boolean, ready: Boolean): String {
    val reason = "${scheduler.runtimeReason},${scheduler.blockedGroups}"
    return when {
        !connected || !ready || scheduler.runtimeStale -> "SERVICE_UNHEALTHY"
        !scheduler.enabled -> "SCHEDULER_DISABLED"
        scheduler.runtimeState == "running" -> "RUNNING"
        reason.contains("息屏") -> "WAIT_SCREEN_OFF"
        reason.contains("充电") -> "WAIT_CHARGING"
        reason.contains("电量") -> "WAIT_BATTERY"
        reason.contains("空闲") -> "WAIT_IDLE"
        reason.contains("停止") -> "USER_STOPPED"
        reason.contains("当前任务") || reason.contains("手动任务") -> "TASK_CONFLICT"
        reason.contains("恢复") || reason.contains("重新拉起") -> "RECOVERING"
        reason.contains("重试") -> "RETRY_BACKOFF"
        scheduler.queueCount > 0 -> "QUEUED"
        else -> "WAIT_NEXT_RUN"
    }
}

private fun supervisorSummary(scheduler: SchedulerUiState): String {
    val status = when (scheduler.supervisorStatus) {
        "running" -> "运行中"
        "recovering" -> "自动恢复中"
        "starting" -> "启动中"
        "failed" -> "启动失败"
        "stopped" -> "已停止"
        else -> scheduler.supervisorStatus.ifBlank { "未知" }
    }
    val heartbeat = when {
        scheduler.supervisorHeartbeatAge < 0L -> "暂无心跳"
        scheduler.supervisorHeartbeatAge < 60L -> "${scheduler.supervisorHeartbeatAge} 秒前心跳"
        scheduler.supervisorHeartbeatAge < 3_600L -> "${scheduler.supervisorHeartbeatAge / 60L} 分钟前心跳"
        else -> "${scheduler.supervisorHeartbeatAge / 3_600L} 小时前心跳"
    }
    return "$status · $heartbeat"
}

private fun blockedSummary(raw: String): String = raw
    .split(',')
    .mapNotNull { item ->
        val value = item.trim()
        if (value.isBlank()) return@mapNotNull null
        val group = value.substringBefore(':')
        val reason = value.substringAfter(':', "等待执行条件")
        "${groupLabel(group)}：$reason"
    }
    .joinToString("\n")

private fun groupLabel(group: String): String = when (group) {
    "cache" -> "应用缓存"
    "empty" -> "空文件与目录"
    "rules" -> "规则垃圾与日志"
    "fragment" -> "残留碎片"
    "deep" -> "深度清理"
    "organize" -> "文件自动归类"
    "cache+organize" -> "缓存与文件归类"
    else -> group.ifBlank { "下一项任务" }
}

private fun nextCheckSummary(epoch: Long): String {
    if (epoch <= 0L) return "等待首次检查"
    val seconds = (epoch - System.currentTimeMillis() / 1000L).coerceAtLeast(0L)
    return when {
        seconds < 60L -> "$seconds 秒后"
        seconds < 3_600L -> "${seconds / 60L} 分钟后"
        seconds < 86_400L -> "${seconds / 3_600L} 小时后"
        else -> "${seconds / 86_400L} 天后"
    }
}
