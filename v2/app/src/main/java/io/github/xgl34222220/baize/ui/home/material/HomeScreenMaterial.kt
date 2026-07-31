package io.github.xgl34222220.baize.ui.home.material

import android.text.format.Formatter
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.FolderDelete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.home.HomeTaskPresentation
import io.github.xgl34222220.baize.ui.home.homeTaskItems
import io.github.xgl34222220.baize.ui.home.nextTask
import io.github.xgl34222220.baize.ui.home.rememberHomeNowEpoch
import io.github.xgl34222220.baize.ui.home.taskCountdownLabel
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/** Material 3 独立首页：标准居中顶栏、紧凑 tonal 卡片与独立任务条目。 */
@Composable
fun HomeScreenMaterial(
    state: DashboardUiState,
    scheduler: SchedulerUiState,
    actions: DashboardActions,
    onOpenClean: () -> Unit
) {
    val context = LocalContext.current
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val nowEpoch = rememberHomeNowEpoch()
    val tasks = scheduler.homeTaskItems()
    val nextTask = tasks.nextTask(nowEpoch)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = bottomInset + 104.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { MaterialHomeTopBar(actions.refresh) }
        item {
            MaterialStatusCard(
                state = state,
                released = Formatter.formatFileSize(context, state.lastReleased)
            )
        }
        item {
            MaterialActionStrip(
                state = state,
                actions = actions
            )
        }
        item { MaterialSectionTitle("自动任务", "各任务独立显示，不与 MIUIX 共用页面骨架") }
        if (tasks.isEmpty()) {
            item {
                MaterialReferenceCard(
                    icon = Icons.Rounded.CleaningServices,
                    title = "暂无自动任务",
                    subtitle = "进入清理页启用需要的类别",
                    value = "设置",
                    onClick = onOpenClean
                )
            }
        } else {
            tasks.forEach { task ->
                item(key = task.id) {
                    MaterialTaskCard(task, nowEpoch, scheduler, onOpenClean)
                }
            }
        }
        item { MaterialSectionTitle("设备状态", "存储与 Root 服务") }
        item {
            MaterialStorageCard(state)
        }
        item {
            MaterialReferenceCard(
                icon = Icons.Rounded.Security,
                title = when {
                    state.running -> "Root 任务执行中"
                    state.ready -> "Root 服务运行正常"
                    state.connected -> "Root 服务已连接"
                    else -> "正在恢复 Root 服务"
                },
                subtitle = state.serviceText,
                value = when {
                    state.running -> "执行中"
                    state.ready -> "正常"
                    else -> "连接中"
                }
            )
        }
        if (nextTask != null) {
            item {
                Text(
                    text = "下一项：${nextTask.title} · ${taskCountdownLabel(nextTask, nowEpoch, scheduler)}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun MaterialHomeTopBar(onRefresh: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(68.dp)
            .padding(horizontal = 12.dp)
    ) {
        Text(
            text = "白泽",
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.headlineMedium
        )
        FilledTonalIconButton(
            onClick = onRefresh,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(42.dp)
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
        }
    }
}

@Composable
private fun MaterialStatusCard(state: DashboardUiState, released: String) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .10f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (state.running) Icons.Rounded.CleaningServices else Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        state.running -> "正在清理"
                        state.scanCompleted -> "扫描结果已就绪"
                        state.ready -> "设备已就绪"
                        else -> "正在连接"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (state.running) state.taskPhase else "最近释放 $released",
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = if (state.scanCompleted) "${state.scanFiles} 项" else "${(state.storagePercent.coerceIn(0f, 1f) * 100).toInt()}%",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}

@Composable
private fun MaterialActionStrip(state: DashboardUiState, actions: DashboardActions) {
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MaterialActionCard(
            icon = if (state.running) Icons.Rounded.Stop else Icons.Rounded.CleaningServices,
            title = if (state.running) "停止" else "清理",
            primary = true,
            enabled = state.running || state.ready || state.scanCompleted,
            onClick = when {
                state.running -> actions.stop
                state.scanCompleted -> actions.cleanScan
                else -> actions.clean
            },
            modifier = Modifier.weight(1f)
        )
        MaterialActionCard(
            icon = Icons.Rounded.Search,
            title = "扫描",
            enabled = !state.running,
            onClick = actions.scan,
            modifier = Modifier.weight(1f)
        )
        MaterialActionCard(
            icon = Icons.Rounded.FolderCopy,
            title = "归类",
            enabled = state.ready && !state.running,
            onClick = actions.organize,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MaterialActionCard(
    icon: ImageVector,
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false
) {
    Surface(
        modifier = modifier
            .height(70.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = when {
            !enabled -> MaterialTheme.colorScheme.surfaceContainerLow
            primary -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (primary && enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(5.dp))
            Text(title, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun MaterialSectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 5.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MaterialTaskCard(
    task: HomeTaskPresentation,
    nowEpoch: Long,
    scheduler: SchedulerUiState,
    onClick: () -> Unit
) {
    MaterialReferenceCard(
        icon = taskIcon(task.id),
        title = task.title,
        subtitle = if (task.enabled) "自动执行" else "已关闭",
        value = taskCountdownLabel(task, nowEpoch, scheduler),
        enabled = task.enabled,
        onClick = onClick
    )
}

@Composable
private fun MaterialStorageCard(state: DashboardUiState) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("可用空间", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text(Formatter.formatFileSize(context, state.storageFree), style = MaterialTheme.typography.headlineMedium)
                }
                Text(
                    "已用 ${Formatter.formatFileSize(context, state.storageUsed)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = state.storagePercent.coerceIn(0f, 1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
    }
}

@Composable
private fun MaterialReferenceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: String,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = MaterialTheme.shapes.medium,
                color = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.outline
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                value,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End,
                maxLines = 2
            )
            if (onClick != null) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun taskIcon(id: String): ImageVector = when (id) {
    "cache" -> Icons.Rounded.CleaningServices
    "empty" -> Icons.Rounded.FolderDelete
    "rules" -> Icons.Rounded.Rule
    "fragment" -> Icons.Rounded.AutoAwesome
    "deep" -> Icons.Rounded.Security
    "organize" -> Icons.Rounded.FolderCopy
    else -> Icons.Rounded.CleaningServices
}
