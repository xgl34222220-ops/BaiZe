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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.FolderDelete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.AutoAwesome
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.home.HomeTaskPresentation
import io.github.xgl34222220.baize.ui.home.homeTaskItems
import io.github.xgl34222220.baize.ui.home.nextTask
import io.github.xgl34222220.baize.ui.home.rememberHomeNowEpoch
import io.github.xgl34222220.baize.ui.home.taskCountdownLabel
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

@Composable
fun HomeScreenMaterial(
    state: DashboardUiState,
    scheduler: SchedulerUiState,
    actions: DashboardActions,
    onOpenClean: () -> Unit
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val nowEpoch = rememberHomeNowEpoch()
    val tasks = scheduler.homeTaskItems()
    val nextTask = tasks.nextTask(nowEpoch)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = bottomInset + 104.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { MaterialHomeHeader(state, scheduler.enabled, actions.refresh) }
        item {
            MaterialNextTaskCard(
                task = nextTask,
                countdown = taskCountdownLabel(nextTask, nowEpoch, scheduler),
                enabled = scheduler.enabled,
                onClick = onOpenClean
            )
        }
        item {
            MaterialPrimaryActions(
                enabled = state.ready && !state.running,
                onClean = actions.clean,
                onOrganize = actions.organize
            )
        }
        if (state.running) item { MaterialRunningTaskCard(state) }
        item { MaterialSectionHeader("任务计划", "每项任务独立显示下一次执行时间", onOpenClean) }
        item { MaterialTaskScheduleCard(tasks, nowEpoch, scheduler, onOpenClean) }
        item { MaterialSectionHeader("最近状态", "只保留最有用的结果与存储信息") }
        item { MaterialRecentSummary(state) }
        item { MaterialServiceStatus(state) }
    }
}

@Composable
private fun MaterialHomeHeader(
    state: DashboardUiState,
    automaticEnabled: Boolean,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("白泽", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                !automaticEnabled -> MaterialTheme.colorScheme.outline
                                state.running -> MaterialTheme.colorScheme.primary
                                state.ready -> BaiZeTokens.colors.success
                                else -> BaiZeTokens.colors.warning
                            }
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        !automaticEnabled -> "自动任务已关闭"
                        state.running -> "正在自动执行"
                        state.ready -> "自动清理与归类已就绪"
                        else -> "正在连接 Root 服务"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        FilledTonalIconButton(onClick = onRefresh, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
        }
    }
}

@Composable
private fun MaterialNextTaskCard(
    task: HomeTaskPresentation?,
    countdown: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "下一个任务",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                task?.title ?: "自动清理",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                countdown,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 19.sp,
                lineHeight = 25.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(7.dp))
            Text(
                "点击查看和调整所有任务周期",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun MaterialPrimaryActions(
    enabled: Boolean,
    onClean: () -> Unit,
    onOrganize: () -> Unit
) {
    Row(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MaterialActionButton(
            title = "一键清理",
            subtitle = "按当前规则立即执行",
            icon = Icons.Rounded.CleaningServices,
            primary = true,
            enabled = enabled,
            onClick = onClean,
            modifier = Modifier.weight(1f)
        )
        MaterialActionButton(
            title = "一键归类",
            subtitle = "整理明确的用户文件",
            icon = Icons.Rounded.FolderCopy,
            primary = false,
            enabled = enabled,
            onClick = onOrganize,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MaterialActionButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(74.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, fontSize = 10.sp, maxLines = 2, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
private fun MaterialSectionHeader(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (onClick != null) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MaterialTaskScheduleCard(
    tasks: List<HomeTaskPresentation>,
    nowEpoch: Long,
    scheduler: SchedulerUiState,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        tasks.forEachIndexed { index, task ->
            MaterialTaskRow(task, nowEpoch, scheduler)
            if (index != tasks.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 68.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)
                )
            }
        }
    }
}

@Composable
private fun MaterialTaskRow(task: HomeTaskPresentation, nowEpoch: Long, scheduler: SchedulerUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = MaterialTheme.shapes.medium,
            color = if (task.enabled) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    taskIcon(task.id),
                    contentDescription = null,
                    modifier = Modifier.size(21.dp),
                    tint = if (task.enabled) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.titleMedium)
            Text(
                if (task.enabled) "自动执行" else "已关闭",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            taskCountdownLabel(task, nowEpoch, scheduler),
            color = if (task.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2
        )
    }
}

@Composable
private fun MaterialRecentSummary(state: DashboardUiState) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                MaterialMetric(
                    label = "最近释放",
                    value = Formatter.formatFileSize(context, state.lastReleased),
                    modifier = Modifier.weight(1f)
                )
                MaterialMetric(
                    label = "可用空间",
                    value = Formatter.formatFileSize(context, state.storageFree),
                    modifier = Modifier.weight(1f)
                )
                MaterialMetric(
                    label = "累计任务",
                    value = state.lifetimeRuns.toString(),
                    modifier = Modifier.weight(1f)
                )
            }
            if (state.lastTaskTime.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
                Spacer(Modifier.height(14.dp))
                Text(
                    "上次执行 · ${state.lastTaskTime}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun MaterialMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(5.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MaterialServiceStatus(state: DashboardUiState) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (state.ready) BaiZeTokens.colors.success else BaiZeTokens.colors.warning)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                state.serviceText,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (state.running) "执行中" else if (state.ready) "运行正常" else "连接中",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium
            )
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
