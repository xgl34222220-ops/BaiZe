package io.github.xgl34222220.baize.ui.settings.miuix

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.baize.ui.miuix.VideoCard
import io.github.xgl34222220.baize.ui.miuix.VideoDivider
import io.github.xgl34222220.baize.ui.miuix.VideoIconButton
import io.github.xgl34222220.baize.ui.miuix.VideoLeadingIcon
import io.github.xgl34222220.baize.ui.miuix.VideoListRow
import io.github.xgl34222220.baize.ui.miuix.VideoSectionTitle
import io.github.xgl34222220.baize.ui.miuix.VideoSwitchRow
import io.github.xgl34222220.baize.ui.miuix.VideoTopBar
import io.github.xgl34222220.baize.ui.settings.SettingsUiActions
import io.github.xgl34222220.baize.ui.settings.SettingsUiState
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlin.math.roundToInt

/** shadcn-inspired 设置页：语义分组、统一 Item、统一边框和一个明确保存动作。 */
@Composable
fun VideoSettingsScreenMiuix(
    state: SettingsUiState,
    actions: SettingsUiActions
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val scheduler = state.scheduler
    val pagePadding = BaiZeTokens.spacing.pageHorizontal

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 96.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
    ) {
        item {
            VideoTopBar(
                title = "设置",
                subtitle = "外观、自动任务与安全保护",
                actions = {
                    VideoIconButton(
                        icon = Icons.Rounded.Refresh,
                        description = "重新连接服务",
                        onClick = actions.onReconnect
                    )
                }
            )
        }

        item {
            VideoCard(
                modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth()
            ) {
                VideoListRow(
                    icon = Icons.Rounded.DarkMode,
                    title = "界面与主题",
                    subtitle = state.appearanceSummary,
                    value = "打开",
                    onClick = actions.onOpenAppearance
                )
            }
        }

        item { VideoSectionTitle("自动执行", "后台任务只在满足条件时运行") }
        item {
            VideoCard(
                modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth()
            ) {
                VideoSwitchRow(
                    icon = Icons.Rounded.SettingsSuggest,
                    title = "仅在息屏时执行",
                    subtitle = "使用手机时不占用存储性能",
                    checked = scheduler.screenOffOnly,
                    onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(screenOffOnly = it)) }
                )
                VideoDivider()
                VideoSwitchRow(
                    icon = Icons.Rounded.BatterySaver,
                    title = "仅在充电时执行",
                    subtitle = "连接电源后再开始自动任务",
                    checked = scheduler.chargingOnly,
                    onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(chargingOnly = it)) }
                )
                VideoDivider()
                VideoSwitchRow(
                    icon = Icons.Rounded.SettingsSuggest,
                    title = "仅在系统空闲时执行",
                    subtitle = "等待 Android 进入空闲状态",
                    checked = scheduler.idleOnly,
                    onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(idleOnly = it)) }
                )
            }
        }

        item { VideoSectionTitle("文件归类", "文件移动使用独立运行条件") }
        item {
            VideoCard(
                modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth()
            ) {
                VideoSwitchRow(
                    icon = Icons.Rounded.FolderCopy,
                    title = "归类时等待息屏",
                    subtitle = "避免文件移动打断前台操作",
                    checked = scheduler.organizeScreenOffOnly,
                    onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(organizeScreenOffOnly = it)) }
                )
                VideoDivider()
                VideoSwitchRow(
                    icon = Icons.Rounded.BatterySaver,
                    title = "归类时等待充电",
                    subtitle = "连接电源后再整理下载文件",
                    checked = scheduler.organizeChargingOnly,
                    onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(organizeChargingOnly = it)) }
                )
                VideoDivider()
                VideoSwitchRow(
                    icon = Icons.Rounded.SettingsSuggest,
                    title = "归类时等待系统空闲",
                    subtitle = "降低移动文件对前台应用的影响",
                    checked = scheduler.organizeIdleOnly,
                    onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(organizeIdleOnly = it)) }
                )
            }
        }

        item { VideoSectionTitle("清理保护", "电量、文件大小与白名单") }
        item {
            VideoCard(
                modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth()
            ) {
                SliderSettingRow(
                    icon = Icons.Rounded.BatterySaver,
                    title = "最低电量 ${scheduler.minBattery}%",
                    subtitle = "低于此电量时自动等待",
                    value = scheduler.minBattery.toFloat(),
                    valueRange = 0f..100f,
                    steps = 19,
                    onValueChange = {
                        actions.onUpdateScheduler(scheduler.copy(minBattery = it.roundToInt()))
                    }
                )
                VideoDivider()
                SliderSettingRow(
                    icon = Icons.Rounded.Security,
                    title = "单文件上限 ${scheduler.maxFileMb} MB",
                    subtitle = "超过上限的文件不会自动清理",
                    value = scheduler.maxFileMb.toFloat(),
                    valueRange = 16f..2048f,
                    steps = 30,
                    onValueChange = {
                        actions.onUpdateScheduler(scheduler.copy(maxFileMb = it.roundToInt()))
                    }
                )
                VideoDivider()
                VideoListRow(
                    icon = Icons.Rounded.Security,
                    title = "应用白名单",
                    subtitle = "受保护应用不会被自动清理",
                    value = "${state.whitelistCount} 个",
                    onClick = actions.onOpenWhitelist
                )
                VideoDivider()
                VideoListRow(
                    icon = Icons.Rounded.PlayArrow,
                    title = "断点续清",
                    subtitle = "消费已保存快照，中断后可继续",
                    value = "打开",
                    onClick = actions.onOpenResumableScan
                )
            }
        }

        item { VideoSectionTitle("通知", "控制任务完成后的系统提醒") }
        item {
            VideoCard(
                modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth()
            ) {
                VideoSwitchRow(
                    icon = Icons.Rounded.Notifications,
                    title = "任务完成通知",
                    subtitle = "自动任务结束后显示结果",
                    checked = scheduler.notifyOnComplete,
                    onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(notifyOnComplete = it)) }
                )
                VideoDivider()
                VideoSwitchRow(
                    icon = Icons.Rounded.Notifications,
                    title = "零结果也通知",
                    subtitle = "没有可清理内容时也发送通知",
                    checked = scheduler.notifyZero,
                    onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(notifyZero = it)) }
                )
            }
        }

        item { VideoSectionTitle("服务与诊断", "Root 状态、规则和异常入口") }
        item {
            VideoCard(
                modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth()
            ) {
                VideoListRow(
                    icon = Icons.Rounded.Security,
                    title = when {
                        state.running -> "Root 任务执行中"
                        state.connected && state.ready -> "Root 服务运行正常"
                        state.connected -> "Root 服务已连接"
                        else -> "Root 服务正在恢复"
                    },
                    subtitle = state.serviceText,
                    value = when {
                        state.running -> "执行中"
                        state.connected && state.ready -> "正常"
                        else -> "恢复中"
                    }
                )
                VideoDivider()
                VideoListRow(
                    icon = Icons.Rounded.Rule,
                    title = "规则与清理明细",
                    subtitle = "查看规则命中、扫描结果和保护状态",
                    value = "打开",
                    onClick = actions.onOpenAudit
                )
                VideoDivider()
                VideoListRow(
                    icon = Icons.Rounded.Refresh,
                    title = "重新连接 Root 服务",
                    subtitle = "授权变化或服务异常时使用",
                    value = "重连",
                    onClick = actions.onReconnect
                )
                VideoDivider()
                VideoListRow(
                    icon = Icons.Rounded.BugReport,
                    title = "崩溃与诊断信息",
                    subtitle = "查看最近异常和调试信息",
                    value = "查看",
                    onClick = actions.onOpenCrashDiagnostics
                )
            }
        }

        item {
            Surface(
                modifier = Modifier
                    .padding(horizontal = pagePadding)
                    .fillMaxWidth()
                    .height(46.dp)
                    .clickable(
                        enabled = !scheduler.saving,
                        onClick = { actions.onSaveScheduler(scheduler) }
                    ),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (scheduler.saving) "正在保存…" else "保存设置",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = BaiZeTokens.type.body.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderSettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        VideoLeadingIcon(icon)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = BaiZeTokens.type.body.copy(fontWeight = FontWeight.SemiBold))
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = BaiZeTokens.type.caption
            )
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps
            )
        }
    }
}
