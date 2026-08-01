package io.github.xgl34222220.baize.ui.home.miuix

import android.text.format.Formatter
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import io.github.xgl34222220.baize.ui.miuix.MiuixOverviewHero
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * MIUIX 首页只使用 compose-miuix-ui 的 Card、Button、IconButton、Text 与主题色。
 * 信息密度按手机宽度重新收紧，任务名称不再被倒计时挤成省略号。
 */
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
        contentPadding = PaddingValues(bottom = bottomInset + 132.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { HomeHeader(state, scheduler.enabled, actions.refresh) }
        item {
            MiuixOverviewHero(
                device = state.device,
                android = state.android,
                statusTitle = when {
                    state.running -> "清理任务执行中"
                    state.scanCompleted -> "扫描结果已就绪"
                    state.ready -> "清理引擎已就绪"
                    state.connected -> "Root 服务已连接"
                    else -> "正在恢复 Root 服务"
                },
                taskPhase = if (state.running) {
                    state.taskProgressPath.ifBlank { state.taskPhase }
                } else {
                    state.serviceText
                },
                releasedText = Formatter.formatFileSize(context, state.lastReleased),
                positive = state.ready || state.scanCompleted,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        item { ActionRow(state, actions) }
        item { SectionTitle("自动任务", "每项任务独立显示状态和下次执行时间") }

        if (tasks.isEmpty()) {
            item {
                TaskCard(
                    icon = Icons.Rounded.CleaningServices,
                    title = "暂无自动任务",
                    subtitle = "进入清理页启用需要的类别",
                    schedule = "去设置",
                    enabled = false,
                    onClick = onOpenClean
                )
            }
        } else {
            tasks.forEach { task ->
                item(key = task.id) {
                    TaskCard(
                        icon = taskIcon(task.id),
                        title = task.title,
                        subtitle = if (task.enabled) "自动执行" else "已关闭",
                        schedule = taskCountdownLabel(task, nowEpoch, scheduler),
                        enabled = task.enabled,
                        onClick = onOpenClean
                    )
                }
            }
        }

        item { SectionTitle("设备与服务", "存储空间与 Root 后台状态") }
        item { StorageCard(state) }
        item {
            InfoCard(
                icon = Icons.Rounded.Security,
                title = when {
                    state.running -> "Root 任务执行中"
                    state.ready -> "Root 服务运行正常"
                    state.connected -> "Root 服务已连接"
                    else -> "Root 服务正在恢复"
                },
                subtitle = state.serviceText,
                trailing = when {
                    state.running -> "执行中"
                    state.ready -> "正常"
                    else -> "恢复中"
                }
            )
        }
        if (nextTask != null) {
            item {
                MiuixText(
                    text = "下一项：${nextTask.title} · ${taskCountdownLabel(nextTask, nowEpoch, scheduler)}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 5.dp),
                    color = MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = .58f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun HomeHeader(
    state: DashboardUiState,
    automaticEnabled: Boolean,
    onRefresh: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 14.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            MiuixText(
                text = "白泽",
                fontSize = 32.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            MiuixText(
                text = when {
                    !automaticEnabled -> "自动清理已关闭"
                    state.running -> "正在执行清理任务"
                    state.ready -> "智能清理与文件归类"
                    else -> "正在连接 Root 服务"
                },
                color = colors.onSurfaceContainer.copy(alpha = .60f),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
        MiuixIconButton(
            onClick = onRefresh,
            modifier = Modifier.size(44.dp),
            backgroundColor = colors.surfaceContainer,
            cornerRadius = 15.dp,
            minHeight = 44.dp,
            minWidth = 44.dp
        ) {
            MiuixIcon(
                Icons.Rounded.Refresh,
                contentDescription = "刷新",
                modifier = Modifier.size(22.dp),
                tint = colors.onSurfaceContainer
            )
        }
    }
}

@Composable
private fun ActionRow(state: DashboardUiState, actions: DashboardActions) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ActionButton(
            icon = if (state.running) Icons.Rounded.Stop else Icons.Rounded.CleaningServices,
            label = if (state.running) "停止" else "清理",
            primary = true,
            enabled = state.running || state.ready || state.scanCompleted,
            onClick = when {
                state.running -> actions.stop
                state.scanCompleted -> actions.cleanScan
                else -> actions.clean
            },
            modifier = Modifier.weight(1f)
        )
        ActionButton(
            icon = Icons.Rounded.Search,
            label = "扫描",
            enabled = !state.running,
            onClick = actions.scan,
            modifier = Modifier.weight(1f)
        )
        ActionButton(
            icon = Icons.Rounded.FolderCopy,
            label = "归类",
            enabled = state.ready && !state.running,
            onClick = actions.organize,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false
) {
    MiuixButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled,
        cornerRadius = 16.dp,
        minHeight = 48.dp,
        colors = if (primary) {
            MiuixButtonDefaults.buttonColorsPrimary()
        } else {
            MiuixButtonDefaults.buttonColors()
        },
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    ) {
        MiuixIcon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        MiuixText(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.padding(horizontal = 20.dp, vertical = 7.dp)) {
        MiuixText(title, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(1.dp))
        MiuixText(
            subtitle,
            color = colors.onSurfaceContainer.copy(alpha = .58f),
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun TaskCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    schedule: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    MiuixCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 15.dp, vertical = 13.dp),
        colors = MiuixCardDefaults.defaultColors(
            color = colors.surfaceContainer,
            contentColor = colors.onSurfaceContainer
        ),
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon = icon, enabled = enabled)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                MiuixText(
                    title,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Clip
                )
                Spacer(Modifier.height(2.dp))
                MiuixText(
                    subtitle,
                    color = colors.onSurfaceContainer.copy(alpha = .58f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            MiuixIcon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.onSurfaceContainer.copy(alpha = .60f)
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 50.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiuixText(
                "下次执行",
                color = colors.onSurfaceContainer.copy(alpha = .50f),
                fontSize = 10.sp
            )
            Spacer(Modifier.width(8.dp))
            MiuixText(
                schedule,
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = 210.dp),
                color = if (enabled) colors.primary else colors.onSurfaceContainer.copy(alpha = .42f),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
private fun StorageCard(state: DashboardUiState) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    val progress = state.storagePercent.coerceIn(0f, 1f)

    MiuixCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 17.dp, vertical = 15.dp),
        colors = MiuixCardDefaults.defaultColors(
            color = colors.surfaceContainer,
            contentColor = colors.onSurfaceContainer
        )
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                MiuixText(
                    "可用空间",
                    color = colors.onSurfaceContainer.copy(alpha = .58f),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(2.dp))
                MiuixText(
                    Formatter.formatFileSize(context, state.storageFree),
                    fontSize = 25.sp,
                    lineHeight = 31.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            MiuixText(
                "${(progress * 100).toInt()}% 已用",
                color = colors.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(11.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(CircleShape)
                .background(colors.surfaceContainerHigh)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(7.dp)
                    .clip(CircleShape)
                    .background(colors.primary)
            )
        }
        Spacer(Modifier.height(7.dp))
        MiuixText(
            "已用 ${Formatter.formatFileSize(context, state.storageUsed)} / ${Formatter.formatFileSize(context, state.storageTotal)}",
            color = colors.onSurfaceContainer.copy(alpha = .54f),
            fontSize = 10.sp
        )
    }
}

@Composable
private fun InfoCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: String
) {
    val colors = MiuixTheme.colorScheme
    MiuixCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 15.dp, vertical = 13.dp),
        colors = MiuixCardDefaults.defaultColors(
            color = colors.surfaceContainer,
            contentColor = colors.onSurfaceContainer
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, true)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                MiuixText(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                MiuixText(
                    subtitle,
                    color = colors.onSurfaceContainer.copy(alpha = .56f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            MiuixText(
                trailing,
                color = colors.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun IconTile(icon: ImageVector, enabled: Boolean) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(
                if (enabled) colors.primary.copy(alpha = .10f)
                else colors.onSurfaceContainer.copy(alpha = .05f)
            ),
        contentAlignment = Alignment.Center
    ) {
        MiuixIcon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (enabled) colors.primary else colors.onSurfaceContainer.copy(alpha = .36f)
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
