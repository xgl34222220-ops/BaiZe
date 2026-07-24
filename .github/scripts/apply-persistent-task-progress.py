from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(path: str, old: str, new: str) -> None:
    file = ROOT / path
    text = file.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"expected block not found: {path}\n{old[:180]}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


# Persist the Root worker's structured progress separately from the human-readable phase string.
replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt",
    '''    val taskPhase: String = "等待下一次清理",
    val schedulerText: String = "等待调度器状态",''',
    '''    val taskPhase: String = "等待下一次清理",
    val taskOperation: String = "",
    val taskProgressCurrent: Long = 0L,
    val taskProgressTotal: Long = 0L,
    val taskProgressPath: String = "",
    val taskProgressBytes: Long = 0L,
    val taskProgressFiles: Long = 0L,
    val taskProgressElapsedMs: Long = 0L,
    val schedulerText: String = "等待调度器状态",'''
)

activity = "v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt"

replace_once(
    activity,
    '''        dashboardState.value = dashboardState.value.copy(
            running = true,
            scanCompleted = false,
            taskPhase = "正在把文件归类交给独立 Root Worker…"
        )''',
    '''        dashboardState.value = dashboardState.value.copy(
            running = true,
            scanCompleted = false,
            taskPhase = "正在把文件归类交给独立 Root Worker…",
            taskOperation = "module-organize",
            taskProgressCurrent = 0L,
            taskProgressTotal = 0L,
            taskProgressPath = "",
            taskProgressBytes = 0L,
            taskProgressFiles = 0L,
            taskProgressElapsedMs = 0L
        )'''
)

replace_once(
    activity,
    '''        dashboardState.value = dashboardState.value.copy(
            running = true,
            scanCompleted = false,
            taskPhase = "正在把清理任务交给独立 Root Worker…"
        )''',
    '''        dashboardState.value = dashboardState.value.copy(
            running = true,
            scanCompleted = false,
            taskPhase = "正在把清理任务交给独立 Root Worker…",
            taskOperation = "module-clean",
            taskProgressCurrent = 0L,
            taskProgressTotal = 0L,
            taskProgressPath = "",
            taskProgressBytes = 0L,
            taskProgressFiles = 0L,
            taskProgressElapsedMs = 0L
        )'''
)

replace_once(
    activity,
    '''    private fun renderTaskState(json: JSONObject) {
        if (!json.optBoolean("running")) return
        val current = json.optInt("progress_current", json.optInt("current", 0))
        val total = json.optInt("progress_total", json.optInt("total", 0))
        val target = json.optString("current_path", json.optString("currentPath")).trim()
        val targetText = when {
            looksLikePackageName(target) -> "${appLabel(target)} · $target"
            target.isNotBlank() -> target.takeLast(72)
            else -> ""
        }
        val text = buildString {
            append(json.optString("phase", "任务执行中"))
            if (total > 0) append(" · $current/$total")
            if (targetText.isNotBlank()) append("\n").append(targetText)
            if (json.optBoolean("cancelRequested")) append("\n正在停止…")
            append("\n可切到后台，Root 会继续执行")
        }
        dashboardState.value = dashboardState.value.copy(
            running = true,
            scanCompleted = false,
            taskPhase = text
        )
    }''',
    '''    private fun renderTaskState(json: JSONObject) {
        if (!json.optBoolean("running")) return
        val current = json.optLong("progress_current", json.optLong("current", 0L)).coerceAtLeast(0L)
        val total = json.optLong("progress_total", json.optLong("total", 0L)).coerceAtLeast(0L)
        val target = json.optString("current_path", json.optString("currentPath")).trim()
        val targetText = when {
            looksLikePackageName(target) -> "${appLabel(target)} · $target"
            target.isNotBlank() -> target.takeLast(96)
            else -> ""
        }
        val phase = json.optString("phase", "任务执行中").ifBlank { "任务执行中" }
        val operation = json.optString("operation", json.optString("mode")).ifBlank { "module-task" }
        val deletedBytes = json.optLong(
            "deleted_bytes",
            json.optLong("deletedBytes", json.optLong("bytes", 0L))
        ).coerceAtLeast(0L)
        val deletedFiles = json.optLong(
            "deleted_files",
            json.optLong("deletedFiles", json.optLong("moved", json.optLong("files", 0L)))
        ).coerceAtLeast(0L)
        val elapsedMs = json.optLong(
            "elapsed_ms",
            json.optLong("elapsedMs", json.optLong("elapsed", 0L) * 1_000L)
        ).coerceAtLeast(0L)
        val text = buildString {
            append(phase)
            if (total > 0L) append(" · ").append(current.coerceAtMost(total)).append('/').append(total)
            if (json.optBoolean("cancelRequested")) append(" · 正在停止")
        }
        dashboardState.value = dashboardState.value.copy(
            running = true,
            scanCompleted = false,
            taskPhase = text,
            taskOperation = operation,
            taskProgressCurrent = current,
            taskProgressTotal = total,
            taskProgressPath = targetText,
            taskProgressBytes = deletedBytes,
            taskProgressFiles = deletedFiles,
            taskProgressElapsedMs = elapsedMs
        )
    }'''
)

replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/material/HomeScreenMaterial.kt",
    '''        item {
            MaterialPrimaryActions(
                enabled = state.ready && !state.running,
                onClean = actions.clean,
                onOrganize = actions.organize
            )
        }
        item { MaterialSectionHeader("任务计划", "每项任务独立显示下一次执行时间", onOpenClean) }''',
    '''        item {
            MaterialPrimaryActions(
                enabled = state.ready && !state.running,
                onClean = actions.clean,
                onOrganize = actions.organize
            )
        }
        if (state.running) item { MaterialRunningTaskCard(state) }
        item { MaterialSectionHeader("任务计划", "每项任务独立显示下一次执行时间", onOpenClean) }'''
)

replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/miuix/HomeScreenMiuix.kt",
    '''        item {
            MiuixPrimaryActions(
                enabled = state.ready && !state.running,
                onClean = actions.clean,
                onOrganize = actions.organize
            )
        }
        item { MiuixSectionTitle("任务计划", "所有任务按条件自动运行") }''',
    '''        item {
            MiuixPrimaryActions(
                enabled = state.ready && !state.running,
                onClean = actions.clean,
                onOrganize = actions.organize
            )
        }
        if (state.running) item { MiuixRunningTaskCard(state) }
        item { MiuixSectionTitle("任务计划", "所有任务按条件自动运行") }'''
)

material_card = r'''package io.github.xgl34222220.baize.ui.home.material

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
'''

miuix_card = r'''package io.github.xgl34222220.baize.ui.home.miuix

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

@Composable
internal fun MiuixRunningTaskCard(state: DashboardUiState) {
    val context = LocalContext.current
    val current = state.taskProgressCurrent.coerceAtLeast(0L)
    val total = state.taskProgressTotal.coerceAtLeast(0L)
    val determinate = total > 0L
    val fraction = if (determinate) (current.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    val isOrganizer = state.taskOperation.contains("organize", ignoreCase = true)

    Surface(
        modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = BaiZeTokens.colors.surfaceRaised
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = .12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isOrganizer) Icons.Rounded.FolderCopy else Icons.Rounded.CleaningServices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isOrganizer) "正在归类文件" else "正在清理垃圾",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        state.taskPhase.ifBlank { "独立 Root Worker 正在执行" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (determinate) {
                    Text(
                        "${(fraction * 100).toInt()}%",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            if (determinate) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = .10f)
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = .10f)
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (determinate) "${current.coerceAtMost(total)} / $total 项" else "后台处理中",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
                val handled = buildList {
                    if (state.taskProgressFiles > 0L) add("${state.taskProgressFiles} 个文件")
                    if (state.taskProgressBytes > 0L) add(Formatter.formatFileSize(context, state.taskProgressBytes))
                }.joinToString(" · ")
                if (handled.isNotBlank()) {
                    Text(handled, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (state.taskProgressPath.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                Spacer(Modifier.height(9.dp))
                Text(
                    state.taskProgressPath,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "由 Magisk 模块后台执行 · 关闭或划掉 App 不会中断",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}
'''

(ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/material/MaterialRunningTaskCard.kt").write_text(material_card, encoding="utf-8")
(ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/miuix/MiuixRunningTaskCard.kt").write_text(miuix_card, encoding="utf-8")

# Static contract checks: every primary long-running action exposed by the redesigned home is detached.
service = (ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt").read_text(encoding="utf-8")
if 'private val DETACHED_TASKS = setOf("clean", "organize")' not in service:
    raise SystemExit("clean and organize must remain detached Root tasks")
worker = (ROOT / "v2/module/task-worker.sh").read_text(encoding="utf-8")
for token in ('setsid "$SHELL_BIN" "$RUNNER"', 'nohup "$SHELL_BIN" "$RUNNER"', 'WAIT_MODE=${4:-detach}'):
    if token not in worker:
        raise SystemExit(f"detached worker contract missing: {token}")

print("persistent progress UI and detached-task contracts applied")
