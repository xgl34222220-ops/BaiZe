package io.github.xgl34222220.baize.ui.home.miuix

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.home.homeTaskItems
import io.github.xgl34222220.baize.ui.home.nextTask
import io.github.xgl34222220.baize.ui.home.rememberHomeNowEpoch
import io.github.xgl34222220.baize.ui.home.taskCountdownLabel
import io.github.xgl34222220.baize.ui.miuix.VideoCard
import io.github.xgl34222220.baize.ui.miuix.VideoIconButton
import io.github.xgl34222220.baize.ui.miuix.VideoListRow
import io.github.xgl34222220.baize.ui.miuix.VideoMetricTile
import io.github.xgl34222220.baize.ui.miuix.VideoSectionTitle
import io.github.xgl34222220.baize.ui.miuix.VideoTopBar
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/**
 * 按参考视频重新搭建的白泽首页：居中标题、主状态仪表卡、卡内三段操作、
 * 紧凑指标卡和悬浮底栏。不是旧页面换色，而是重新组织首屏信息层级。
 */
@Composable
fun VideoHomeScreenMiuix(
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
    val healthy = state.ready || state.scanCompleted

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 98.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            VideoTopBar(
                title = "白泽",
                subtitle = if (state.running) "正在执行清理任务" else "智能清理与文件归类",
                actions = {
                    VideoIconButton(
                        icon = Icons.Rounded.Refresh,
                        description = "刷新",
                        onClick = actions.refresh
                    )
                }
            )
        }

        item {
            HomeStatusHero(
                state = state,
                releasedText = Formatter.formatFileSize(context, state.lastReleased),
                onPrimary = when {
                    state.running -> actions.stop
                    state.scanCompleted -> actions.cleanScan
                    else -> actions.clean
                },
                onScan = actions.scan,
                onOrganize = actions.organize
            )
        }

        if (state.scanCompleted) {
            item {
                VideoCard(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f)
                ) {
                    VideoListRow(
                        icon = Icons.Rounded.DeleteSweep,
                        title = "扫描结果已就绪",
                        subtitle = "${state.scanFiles} 个文件 · ${Formatter.formatFileSize(context, state.scanBytes)}",
                        value = "立即清理",
                        onClick = actions.cleanScan
                    )
                }
            }
        }

        item { VideoSectionTitle("设备与存储", "首屏只保留判断和行动所需的数据") }

        item {
            VideoCard(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth(),
                contentPadding = 15
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "可用空间",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = Formatter.formatFileSize(context, state.storageFree),
                            fontSize = 25.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = "已用 ${Formatter.formatFileSize(context, state.storageUsed)} / ${Formatter.formatFileSize(context, state.storageTotal)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        modifier = Modifier.size(58.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = .10f),
                        border = BorderStroke(5.dp, MaterialTheme.colorScheme.primary.copy(alpha = .18f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "${(state.storagePercent.coerceIn(0f, 1f) * 100).toInt()}%",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = state.storagePercent.coerceIn(0f, 1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = BaiZeTokens.colors.surfaceOverlay
                )
            }
        }

        item {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VideoMetricTile(
                    label = "最近释放",
                    value = Formatter.formatFileSize(context, state.lastReleased),
                    caption = if (state.lastTaskTime.isBlank()) "等待首次任务" else state.lastTaskTime,
                    modifier = Modifier.weight(1f)
                )
                VideoMetricTile(
                    label = "累计任务",
                    value = "${state.lifetimeRuns}",
                    caption = "自动与手动任务",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VideoMetricTile(
                    label = "累计释放",
                    value = Formatter.formatFileSize(context, state.lifetimeReleased),
                    caption = "历史清理总量",
                    modifier = Modifier.weight(1f)
                )
                VideoMetricTile(
                    label = "处理文件",
                    value = state.lifetimeFiles.toString(),
                    caption = "累计文件数量",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item { VideoSectionTitle("自动任务", "下一项任务与 Root 服务状态") }

        item {
            VideoCard(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
            ) {
                VideoListRow(
                    icon = Icons.Rounded.CalendarMonth,
                    title = nextTask?.title ?: "自动清理",
                    subtitle = if (scheduler.enabled) {
                        taskCountdownLabel(nextTask, nowEpoch, scheduler)
                    } else {
                        "自动任务已关闭"
                    },
                    value = "清理计划",
                    onClick = onOpenClean
                )
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(start = 67.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .34f)
                )
                VideoListRow(
                    icon = Icons.Rounded.Security,
                    title = when {
                        state.running -> "Root 任务执行中"
                        healthy -> "Root 服务运行正常"
                        state.connected -> "Root 服务已连接"
                        else -> "正在恢复 Root 服务"
                    },
                    subtitle = state.serviceText,
                    value = when {
                        state.running -> "执行中"
                        healthy -> "正常"
                        else -> "恢复中"
                    }
                )
            }
        }
    }
}

@Composable
private fun HomeStatusHero(
    state: DashboardUiState,
    releasedText: String,
    onPrimary: () -> Unit,
    onScan: () -> Unit,
    onOrganize: () -> Unit
) {
    val positive = state.ready || state.scanCompleted
    VideoCard(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f),
        contentPadding = 16
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    state.running -> BaiZeTokens.colors.warning
                                    positive -> BaiZeTokens.colors.success
                                    else -> MaterialTheme.colorScheme.outline
                                }
                            )
                    )
                    Spacer(Modifier.width(7.dp))
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
                    text = if (state.running) state.taskPhase else "最近一次释放",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (state.running) {
                        "${state.taskProgressFiles} 项"
                    } else {
                        releasedText
                    },
                    fontSize = 29.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (state.running) state.taskProgressPath.ifBlank { state.taskOperation } else state.device,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .size(82.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .46f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (state.running) Icons.Rounded.CleaningServices else Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(55.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            HeroAction(
                icon = if (state.running) Icons.Rounded.Stop else Icons.Rounded.CleaningServices,
                title = if (state.running) "停止" else if (state.scanCompleted) "清理" else "清理",
                onClick = onPrimary,
                modifier = Modifier.weight(1f),
                primary = true
            )
            HeroAction(
                icon = Icons.Rounded.Search,
                title = "扫描",
                onClick = onScan,
                modifier = Modifier.weight(1f)
            )
            HeroAction(
                icon = Icons.Rounded.FolderCopy,
                title = "归类",
                onClick = onOrganize,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun HeroAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = modifier
            .height(42.dp)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = .62f),
        contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
