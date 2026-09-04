package io.github.xgl34222220.baize.ui.home.miuix

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.home.homeTaskItems
import io.github.xgl34222220.baize.ui.home.nextTask
import io.github.xgl34222220.baize.ui.home.rememberHomeNowEpoch
import io.github.xgl34222220.baize.ui.home.taskCountdownLabel
import io.github.xgl34222220.baize.ui.miuix.MiuixLiquidPrimaryButton
import io.github.xgl34222220.baize.ui.miuix.MiuixOverviewHero
import io.github.xgl34222220.baize.ui.miuix.VideoActionTile
import io.github.xgl34222220.baize.ui.miuix.VideoCard
import io.github.xgl34222220.baize.ui.miuix.VideoIconButton
import io.github.xgl34222220.baize.ui.miuix.VideoListRow
import io.github.xgl34222220.baize.ui.miuix.VideoMetricTile
import io.github.xgl34222220.baize.ui.miuix.VideoSectionTitle
import io.github.xgl34222220.baize.ui.miuix.VideoTopBar
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/**
 * shadcn-inspired 首页：先状态、再主操作、再数据与计划。
 * 页面只负责信息编排，视觉语义全部由共享 Card / Item / Button / Metric 组件提供。
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
    val pagePadding = BaiZeTokens.spacing.pageHorizontal
    val releasedText = Formatter.formatFileSize(context, state.lastReleased)

    val statusTitle = when {
        state.running -> "清理任务执行中"
        state.scanCompleted -> "扫描结果已就绪"
        state.ready -> "清理引擎已就绪"
        state.connected -> "Root 服务已连接"
        else -> "正在恢复 Root 服务"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 92.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            VideoTopBar(
                title = "白泽",
                subtitle = "智能清理与文件归类",
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
            MiuixOverviewHero(
                device = state.device,
                android = state.android,
                statusTitle = statusTitle,
                taskPhase = if (state.running) state.taskPhase else state.serviceText,
                releasedText = if (state.running) "${state.taskProgressFiles} 项" else releasedText,
                positive = healthy,
                modifier = Modifier.padding(horizontal = pagePadding)
            )
        }

        item {
            Column(
                modifier = Modifier.padding(horizontal = pagePadding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MiuixLiquidPrimaryButton(
                    running = state.running,
                    scanReady = state.scanCompleted,
                    enabled = state.connected || state.ready || state.running || state.scanCompleted,
                    onClick = when {
                        state.running -> actions.stop
                        state.scanCompleted -> actions.cleanScan
                        else -> actions.clean
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VideoActionTile(
                        icon = Icons.Rounded.Search,
                        title = "扫描",
                        subtitle = "先查看可清理项，不删除文件",
                        onClick = actions.scan,
                        modifier = Modifier.weight(1f)
                    )
                    VideoActionTile(
                        icon = Icons.Rounded.FolderCopy,
                        title = "归类",
                        subtitle = "整理下载、附件与导出文件",
                        onClick = actions.organize,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (state.scanCompleted) {
            item {
                VideoCard(
                    modifier = Modifier
                        .padding(horizontal = pagePadding)
                        .fillMaxWidth()
                ) {
                    VideoListRow(
                        icon = Icons.Rounded.Search,
                        title = "扫描结果",
                        subtitle = "${state.scanFiles} 个文件 · ${Formatter.formatFileSize(context, state.scanBytes)}",
                        value = "可清理",
                        onClick = actions.cleanScan
                    )
                }
            }
        }

        item { VideoSectionTitle("存储", "只展示做决定需要的数据") }

        item {
            VideoCard(
                modifier = Modifier
                    .padding(horizontal = pagePadding)
                    .fillMaxWidth(),
                contentPadding = 16
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "可用空间",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = BaiZeTokens.type.caption
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            Formatter.formatFileSize(context, state.storageFree),
                            style = BaiZeTokens.type.display
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "已用 ${Formatter.formatFileSize(context, state.storageUsed)} / ${Formatter.formatFileSize(context, state.storageTotal)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = BaiZeTokens.type.caption
                        )
                    }
                    Text(
                        "${(state.storagePercent.coerceIn(0f, 1f) * 100).toInt()}%",
                        color = MaterialTheme.colorScheme.primary,
                        style = BaiZeTokens.type.title
                    )
                }
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = state.storagePercent.coerceIn(0f, 1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = BaiZeTokens.colors.surfaceOverlay
                )
            }
        }

        item {
            Row(
                modifier = Modifier.padding(horizontal = pagePadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VideoMetricTile(
                    label = "最近释放",
                    value = releasedText,
                    caption = state.lastTaskTime.ifBlank { "等待首次任务" },
                    modifier = Modifier.weight(1f)
                )
                VideoMetricTile(
                    label = "累计释放",
                    value = Formatter.formatFileSize(context, state.lifetimeReleased),
                    caption = "${state.lifetimeRuns} 次任务",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item { VideoSectionTitle("自动任务", "计划状态与 Root 服务") }

        item {
            VideoCard(
                modifier = Modifier
                    .padding(horizontal = pagePadding)
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
                    value = if (scheduler.enabled) "已启用" else "已关闭",
                    onClick = onOpenClean
                )
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(start = 60.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)
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
