package io.github.xgl34222220.baize.ui.home.miuix

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.BuildConfig
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.home.homeTaskItems
import io.github.xgl34222220.baize.ui.home.nextTask
import io.github.xgl34222220.baize.ui.home.rememberHomeNowEpoch
import io.github.xgl34222220.baize.ui.home.taskCountdownLabel
import io.github.xgl34222220.baize.ui.miuix.MiuixLiquidPrimaryButton
import io.github.xgl34222220.baize.ui.miuix.MiuixOverviewHero
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/**
 * 白泽首页直接采用洛书 HomeScreenMiuix 的视觉骨架与尺寸，只替换业务语义。
 */
@Composable
fun VideoHomeScreenMiuix(
    state: DashboardUiState,
    scheduler: SchedulerUiState,
    actions: DashboardActions,
    onOpenClean: () -> Unit
) {
    val context = LocalContext.current
    val nowEpoch = rememberHomeNowEpoch()
    val tasks = scheduler.homeTaskItems()
    val nextTask = tasks.nextTask(nowEpoch)
    val healthy = state.ready || state.scanCompleted
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
        contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 132.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "CLEAN ENGINE",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.4.sp
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = "白泽",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 39.sp,
                        lineHeight = 44.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Miuix · ${BuildConfig.VERSION_NAME}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                Card(
                    modifier = Modifier.size(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = BaiZeTokens.colors.surfaceOverlay),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    IconButton(onClick = actions.refresh, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新", modifier = Modifier.size(23.dp))
                    }
                }
            }
        }

        item {
            MiuixOverviewHero(
                device = state.device,
                android = state.android,
                statusTitle = statusTitle,
                taskPhase = if (state.running) state.taskPhase else state.serviceText,
                releasedText = if (state.running) "${state.taskProgressFiles} 项" else releasedText,
                positive = healthy
            )
        }

        item {
            HomeSectionTitle(
                eyebrow = "SYSTEM STATUS",
                title = "运行状态",
                subtitle = "Root 服务与本机存储"
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeMetricCard(
                    icon = Icons.Rounded.Security,
                    label = "ROOT SERVICE",
                    value = when {
                        state.running -> "执行中"
                        healthy -> "运行正常"
                        state.connected -> "已连接"
                        else -> "恢复中"
                    },
                    status = state.serviceText,
                    modifier = Modifier.weight(1f)
                )
                HomeMetricCard(
                    icon = Icons.Rounded.CleaningServices,
                    label = "CLEAN ENGINE",
                    value = if (state.scanCompleted) "快照就绪" else "安全模式",
                    status = if (state.scanCompleted) "${state.scanFiles} 个候选" else "白名单与风险保护已启用",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            HomeSectionTitle(
                eyebrow = "QUICK ACCESS",
                title = "常用入口",
                subtitle = "扫描、归类与完整清理选项"
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(34.dp),
                colors = CardDefaults.cardColors(containerColor = BaiZeTokens.colors.surfaceRaised),
                elevation = CardDefaults.cardElevation(defaultElevation = 7.dp)
            ) {
                Column(Modifier.padding(horizontal = 15.dp, vertical = 5.dp)) {
                    HomeActionRow(
                        icon = Icons.Rounded.Search,
                        title = "安全扫描",
                        subtitle = "只生成可清理快照，不删除文件",
                        onClick = actions.scan
                    )
                    HomeActionRow(
                        icon = Icons.Rounded.FolderCopy,
                        title = "文件归类",
                        subtitle = "整理下载、附件与导出文件",
                        onClick = actions.organize
                    )
                    HomeActionRow(
                        icon = Icons.Rounded.CleaningServices,
                        title = "清理选项",
                        subtitle = "类别、周期、深度清理与安装包策略",
                        onClick = onOpenClean
                    )
                }
            }
        }

        if (state.scanCompleted) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = BaiZeTokens.colors.surfaceRaised),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                ) {
                    HomeActionRow(
                        icon = Icons.Rounded.Search,
                        title = "扫描快照已就绪",
                        subtitle = "${state.scanFiles} 个候选 · ${Formatter.formatFileSize(context, state.scanBytes)}",
                        onClick = actions.cleanScan
                    )
                }
            }
        }

        item {
            HomeSectionTitle(
                eyebrow = "STORAGE",
                title = "存储空间",
                subtitle = "本机空间占用与累计释放"
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = BaiZeTokens.colors.surfaceRaised),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text("可用空间", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                Formatter.formatFileSize(context, state.storageFree),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 28.sp,
                                lineHeight = 34.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Text(
                            "${(state.storagePercent.coerceIn(0f, 1f) * 100).toInt()}%",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = state.storagePercent.coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "已用 ${Formatter.formatFileSize(context, state.storageUsed)} · 共 ${Formatter.formatFileSize(context, state.storageTotal)} · 累计释放 ${Formatter.formatFileSize(context, state.lifetimeReleased)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        item {
            HomeSectionTitle(
                eyebrow = "AUTOMATION",
                title = "自动任务",
                subtitle = "下一次计划与调度状态"
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = BaiZeTokens.colors.surfaceRaised),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                HomeActionRow(
                    icon = Icons.Rounded.CalendarMonth,
                    title = nextTask?.title ?: "自动清理",
                    subtitle = if (scheduler.enabled) taskCountdownLabel(nextTask, nowEpoch, scheduler) else "自动任务已关闭",
                    onClick = onOpenClean
                )
            }
        }

        item {
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
        }
    }
}

@Composable
private fun HomeSectionTitle(
    eyebrow: String,
    title: String,
    subtitle: String
) {
    Column(Modifier.padding(start = 4.dp, top = 4.dp)) {
        Text(
            text = eyebrow,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.2.sp
        )
        Spacer(Modifier.height(3.dp))
        Text(title, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun HomeMetricCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    status: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = BaiZeTokens.colors.surfaceRaised),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(Modifier.padding(15.dp)) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(17.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = .11f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            Spacer(Modifier.height(3.dp))
            Text(value, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(2.dp))
            Text(
                status,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HomeActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(17.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = .11f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
