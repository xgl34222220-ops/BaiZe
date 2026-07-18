package io.github.xgl34222220.baize.ui.logs

import androidx.compose.runtime.Immutable
import io.github.xgl34222220.baize.DashboardUiState

@Immutable
enum class LogLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}

@Immutable
data class LogUiItem(
    val key: String,
    val time: String,
    val title: String,
    val message: String,
    val trigger: String,
    val bytes: Long,
    val files: Int,
    val errors: Int,
    val level: LogLevel
)

@Immutable
data class LogsUiState(
    val connected: Boolean,
    val ready: Boolean,
    val running: Boolean,
    val serviceText: String,
    val taskPhase: String,
    val schedulerText: String,
    val device: String,
    val android: String,
    val logs: List<LogUiItem>
) {
    val errorCount: Int
        get() = logs.sumOf { it.errors }

    val healthy: Boolean
        get() = connected && ready && errorCount == 0
}

data class LogsUiActions(
    val onRefresh: () -> Unit,
    val onReconnect: () -> Unit,
    val onOpenAudit: () -> Unit,
    val onOpenCrashDiagnostics: () -> Unit,
    val onClearTaskLogs: () -> Unit
)

fun DashboardUiState.toLogsUiState(): LogsUiState = LogsUiState(
    connected = connected,
    ready = ready,
    running = running,
    serviceText = serviceText,
    taskPhase = taskPhase,
    schedulerText = schedulerText,
    device = device,
    android = android,
    logs = history.mapIndexed { index, item ->
        val message = when {
            item.categories.isNotEmpty() -> item.categories.take(3).joinToString(" · ") { detail ->
                "${detail.name} ${detail.files} 项"
            }
            item.result.isNotBlank() -> item.result
            item.cleaned -> "清理任务已完成"
            else -> "扫描任务已完成"
        }
        LogUiItem(
            key = "${item.time}-${item.title}-${item.trigger}-$index",
            time = item.time,
            title = item.title,
            message = message,
            trigger = item.trigger,
            bytes = item.bytes,
            files = item.files,
            errors = item.errors,
            level = when {
                item.errors > 0 -> LogLevel.ERROR
                item.cleaned && item.bytes > 0L -> LogLevel.SUCCESS
                item.files > 0 -> LogLevel.WARNING
                else -> LogLevel.INFO
            }
        )
    }
)
