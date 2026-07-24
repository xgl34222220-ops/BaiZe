package io.github.xgl34222220.baize.ui.home.material

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.baize.DashboardUiState

@Composable
internal fun MaterialRunningTaskCard(state: DashboardUiState) {
    val context = LocalContext.current
    val current = state.taskProgressCurrent.coerceAtLeast(0L)
    val total = state.taskProgressTotal.coerceAtLeast(0L)
    val determinate = total > 0L
    val fraction = if (determinate) (current.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    val isOrganizer = state.taskOperation.contains("organize", ignoreCase = true)

    Card(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (isOrganizer) Icons.Rounded.FolderCopy else Icons.Rounded.CleaningServices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isOrganizer) "文件归类正在后台执行" else "清理正在后台执行",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        state.taskPhase.ifBlank { "独立 Root Worker 正在处理" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (determinate) {
                    Text(
                        "${(fraction * 100).toInt()}%",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(15.dp))
            if (determinate) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (determinate) "${current.coerceAtMost(total)} / $total 项" else "正在读取任务进度",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium
                )
                val handled = buildList {
                    if (state.taskProgressFiles > 0L) add("${state.taskProgressFiles} 个文件")
                    if (state.taskProgressBytes > 0L) add(Formatter.formatFileSize(context, state.taskProgressBytes))
                }.joinToString(" · ")
                if (handled.isNotBlank()) {
                    Text(
                        handled,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (state.taskProgressPath.isNotBlank()) {
                Spacer(Modifier.height(9.dp))
                Text(
                    state.taskProgressPath,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "任务由 Magisk 模块的独立 Root Worker 持有，关闭或划掉 App 不会中断",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
