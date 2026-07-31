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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

/** MIUIX / HyperOS 独立首页：大标题、浅紫灰底、超椭圆独立卡片和更舒展的留白。 */
@Composable
fun HomeScreenMiuix(
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
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 106.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { MiuixHomeHeader(state, scheduler.enabled, actions.refresh) }
        item {
            MiuixStatusHero(
                state = state,
                released = Formatter.formatFileSize(context, state.lastReleased)
            )
        }
        item { MiuixActionRow(state, actions) }
        item { MiuixSectionTitle("自动任务", "独立卡片展示每项任务的状态与时间") }
        tasks.forEach { task ->
            item(key = task.id) {
                MiuixTaskCard(task, nowEpoch, scheduler, onOpenClean)
            }
        }
        if (tasks.isEmpty()) {
            item {
                MiuixReferenceCard(
                    icon = Icons.Rounded.CleaningServices,
                    title = "暂无自动任务",
                    subtitle = "进入清理页选择需要的类别",
                    value = "设置",
                    onClick = onOpenClean
                )
            }
        }
        item { MiuixSectionTitle("设备与服务", "保留最关键的存储与 Root 状态") }
        item { MiuixStorageCard(state) }
        item {
            MiuixReferenceCard(
                icon = Icons.Rounded.Security,
                title = when {
                    state.running -> "Root 任务执行中"
                    state.ready -> "Root 服务运行正常"
                    state.connected -> "Root 服务已连接"
                    else -> "Root 服务正在恢复"
                },
                subtitle = state.serviceText,
                value = when {
                    state.running -> "执行中"
                    state.ready -> "正常"
                    else -> "恢复中"
                }
            )
        }
        if (nextTask != null) {
            item {
                Text(
                    text = "下一项 ${nextTask.title} · ${taskCountdownLabel(nextTask, nowEpoch, scheduler)}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
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
            .padding(start = 24.dp, end = 16.dp, top = 24.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "白泽",
                fontSize = 34.sp,
                lineHeight = 41.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    !automaticEnabled -> "自动清理已关闭"
                    state.running -> "正在执行清理任务"
                    state.ready -> "智能清理与文件归类"
                    else -> "正在连接 Root 服务"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
        Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(17.dp),
            color = BaiZeTokens.colors.surfaceRaised
        ) {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新", modifier = Modifier.size(23.dp))
            }
        }
    }
}

@Composable
private fun MiuixStatusHero(state: DashboardUiState, released: String) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    state.running -> BaiZeTokens.colors.warning
                                    state.ready || state.scanCompleted -> BaiZeTokens.colors.success
                                    else -> MaterialTheme.colorScheme.outline
                                }
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when {
                            state.running -> "运行中"
                            state.scanCompleted -> "扫描完成"
                            state.ready -> "已就绪"
                            else -> "连接中"
                        },
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (state.running) state.taskPhase else released,
                    fontSize = 30.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (state.running) {
                        state.taskProgressPath.ifBlank { state.taskOperation }
                    } else if (state.scanCompleted) {
                        "${state.scanFiles} 项 · 扫描结果可直接清理"
                    } else {
                        "最近一次释放 · ${state.device}"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .50f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (state.running) Icons.Rounded.CleaningServices else Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(46.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun MiuixActionRow(state: DashboardUiState, actions: DashboardActions) {
    Row(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        MiuixActionCard(
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
        MiuixActionCard(
            icon = Icons.Rounded.Search,
            title = "扫描",
            enabled = !state.running,
            onClick = actions.scan,
            modifier = Modifier.weight(1f)
        )
        MiuixActionCard(
            icon = Icons.Rounded.FolderCopy,
            title = "归类",
            enabled = state.ready && !state.running,
            onClick = actions.organize,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MiuixActionCard(
    icon: ImageVector,
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false
) {
    val container = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = .045f)
        primary -> MaterialTheme.colorScheme.primary
        else -> BaiZeTokens.colors.surfaceRaised
    }
    val content = when {
        !enabled -> MaterialTheme.colorScheme.outline
        primary -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = modifier
            .height(76.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = container,
        contentColor = content
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(23.dp))
            Spacer(Modifier.height(6.dp))
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MiuixSectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
        Text(title, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun MiuixTaskCard(
    task: HomeTaskPresentation,
    nowEpoch: Long,
    scheduler: SchedulerUiState,
    onClick: () -> Unit
) {
    MiuixReferenceCard(
        icon = taskIcon(task.id),
        title = task.title,
        subtitle = if (task.enabled) "自动执行" else "已关闭",
        value = taskCountdownLabel(task, nowEpoch, scheduler),
        enabled = task.enabled,
        onClick = onClick
    )
}

@Composable
private fun MiuixStorageCard(state: DashboardUiState) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = BaiZeTokens.colors.surfaceRaised
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("可用空间", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        Formatter.formatFileSize(context, state.storageFree),
                        fontSize = 27.sp,
                        lineHeight = 33.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "${(state.storagePercent.coerceIn(0f, 1f) * 100).toInt()}% 已用",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(13.dp))
            LinearProgressIndicator(
                progress = state.storagePercent.coerceIn(0f, 1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = BaiZeTokens.colors.surfaceOverlay
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "已用 ${Formatter.formatFileSize(context, state.storageUsed)} / ${Formatter.formatFileSize(context, state.storageTotal)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun MiuixReferenceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: String,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier),
        shape = RoundedCornerShape(24.dp),
        color = BaiZeTokens.colors.surfaceRaised
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = .10f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = .05f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(21.dp),
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 16.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                value,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                maxLines = 2
            )
            if (onClick != null) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(21.dp))
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
