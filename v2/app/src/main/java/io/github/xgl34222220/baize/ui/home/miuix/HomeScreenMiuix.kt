package io.github.xgl34222220.baize.ui.home.miuix

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.FolderDelete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
fun HomeScreenMiuix(
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
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 100.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item { MiuixHomeHeader(state, scheduler.enabled, actions.refresh) }
        item { MiuixNextTaskPanel(nextTask, nowEpoch, scheduler.enabled, onOpenClean) }
        item {
            MiuixPrimaryActions(
                enabled = state.ready && !state.running,
                onClean = actions.clean,
                onOrganize = actions.organize
            )
        }
        if (state.running) item { MiuixRunningTaskCard(state) }
        item { MiuixSectionTitle("任务计划", "所有任务按条件自动运行") }
        item { MiuixTaskGroup(tasks, nowEpoch, onOpenClean) }
        item { MiuixSectionTitle("最近状态", "最近一次自动任务的结果") }
        item { MiuixRecentGroup(state) }
        item { MiuixServiceRow(state) }
    }
}

@Composable
private fun MiuixHomeHeader(
    state: DashboardUiState,
    automaticEnabled: Boolean,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 22.dp, end = 14.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "白泽",
                fontSize = 34.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(5.dp))
            Text(
                when {
                    !automaticEnabled -> "自动清理已关闭"
                    state.running -> "正在执行自动任务"
                    state.ready -> "自动清理与文件归类"
                    else -> "正在连接 Root 服务"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
        Surface(
            modifier = Modifier.size(46.dp),
            shape = RoundedCornerShape(16.dp),
            color = BaiZeTokens.colors.surfaceRaised
        ) {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新", modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun MiuixNextTaskPanel(
    task: HomeTaskPresentation?,
    nowEpoch: Long,
    automaticEnabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(68.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "下一个任务",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    task?.title ?: "自动清理",
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (automaticEnabled) taskCountdownLabel(task, nowEpoch) else "自动任务已关闭",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MiuixPrimaryActions(
    enabled: Boolean,
    onClean: () -> Unit,
    onOrganize: () -> Unit
) {
    Surface(
        modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = BaiZeTokens.colors.surfaceRaised
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MiuixActionButton(
                title = "一键清理",
                subtitle = "立即执行清理规则",
                icon = Icons.Rounded.CleaningServices,
                primary = true,
                enabled = enabled,
                onClick = onClean,
                modifier = Modifier.weight(1f)
            )
            MiuixActionButton(
                title = "一键归类",
                subtitle = "整理用户下载文件",
                icon = Icons.Rounded.FolderCopy,
                primary = false,
                enabled = enabled,
                onClick = onOrganize,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MiuixActionButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fill = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = .10f)
    val content = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier
            .height(76.dp)
            .clip(RoundedCornerShape(17.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(17.dp),
        color = if (enabled) fill else MaterialTheme.colorScheme.onSurface.copy(alpha = .05f),
        contentColor = if (enabled) content else MaterialTheme.colorScheme.outline
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(23.dp))
            Spacer(Modifier.width(9.dp))
            Column {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 2)
            }
        }
    }
}

@Composable
private fun MiuixSectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 22.dp)) {
        Text(title, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun MiuixTaskGroup(
    tasks: List<HomeTaskPresentation>,
    nowEpoch: Long,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = BaiZeTokens.colors.surfaceRaised
    ) {
        Column {
            tasks.forEachIndexed { index, task ->
                MiuixTaskRow(task, nowEpoch)
                if (index != tasks.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 68.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .50f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MiuixTaskRow(task: HomeTaskPresentation, nowEpoch: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (task.enabled) MaterialTheme.colorScheme.primary.copy(alpha = .11f)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                taskIcon(task.id),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (task.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(task.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (task.enabled) "自动执行" else "已关闭",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
        Text(
            taskCountdownLabel(task, nowEpoch),
            color = if (task.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2
        )
    }
}

@Composable
private fun MiuixRecentGroup(state: DashboardUiState) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = BaiZeTokens.colors.surfaceRaised
    ) {
        Column {
            MiuixValueRow("最近释放", Formatter.formatFileSize(context, state.lastReleased))
            HorizontalDivider(
                modifier = Modifier.padding(start = 20.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .50f)
            )
            MiuixValueRow("可用空间", Formatter.formatFileSize(context, state.storageFree))
            HorizontalDivider(
                modifier = Modifier.padding(start = 20.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .50f)
            )
            MiuixValueRow("累计自动任务", "${state.lifetimeRuns} 次")
            if (state.lastTaskTime.isNotBlank()) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .50f)
                )
                MiuixValueRow("上次执行", state.lastTaskTime)
            }
        }
    }
}

@Composable
private fun MiuixValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MiuixServiceRow(state: DashboardUiState) {
    Row(
        modifier = Modifier
            .padding(horizontal = 22.dp, vertical = 2.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (state.ready) BaiZeTokens.colors.success else BaiZeTokens.colors.warning)
        )
        Spacer(Modifier.width(9.dp))
        Text(
            state.serviceText,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            if (state.running) "执行中" else if (state.ready) "运行正常" else "连接中",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
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
