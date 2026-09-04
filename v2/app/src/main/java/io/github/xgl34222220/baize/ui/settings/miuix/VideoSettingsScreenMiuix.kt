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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.miuix.LuoShuPageHeader
import io.github.xgl34222220.baize.ui.miuix.LuoShuSectionTitle
import io.github.xgl34222220.baize.ui.miuix.VideoCard
import io.github.xgl34222220.baize.ui.miuix.VideoDivider
import io.github.xgl34222220.baize.ui.miuix.VideoLeadingIcon
import io.github.xgl34222220.baize.ui.miuix.VideoListRow
import io.github.xgl34222220.baize.ui.miuix.VideoSwitchRow
import io.github.xgl34222220.baize.ui.settings.SettingsUiActions
import io.github.xgl34222220.baize.ui.settings.SettingsUiState
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlin.math.roundToInt

/** LuoShu-style grouped settings: dense controls stay collapsed until the user opens that group. */
@Composable
fun VideoSettingsScreenMiuix(
    state: SettingsUiState,
    actions: SettingsUiActions
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val scheduler = state.scheduler
    val pagePadding = 16.dp
    var autoConditionsExpanded by rememberSaveable { mutableStateOf(false) }
    var organizeConditionsExpanded by rememberSaveable { mutableStateOf(false) }
    var safetyExpanded by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 112.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
    ) {
        item {
            LuoShuPageHeader(
                eyebrow = "SYSTEM SETTINGS",
                title = "设置",
                subtitle = state.appearanceSummary,
                actionIcon = Icons.Rounded.Refresh,
                actionDescription = "重新连接服务",
                onAction = actions.onReconnect
            )
        }

        item { SettingsStatusHero(state) }

        item {
            LuoShuSectionTitle(
                eyebrow = "APPEARANCE",
                title = "外观",
                subtitle = "主题、Monet、玻璃效果与底栏"
            )
        }
        item {
            VideoCard(
                modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth(),
                contentPadding = 0
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

        item {
            LuoShuSectionTitle(
                eyebrow = "AUTOMATION",
                title = "自动执行",
                subtitle = "运行条件按需展开，不把所有开关铺满页面"
            )
        }
        item {
            VideoCard(
                modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth(),
                contentPadding = 0
            ) {
                ExpandableHeaderRow(
                    icon = Icons.Rounded.SettingsSuggest,
                    title = "自动清理条件",
                    subtitle = autoConditionSummary(state),
                    expanded = autoConditionsExpanded,
                    onClick = { autoConditionsExpanded = !autoConditionsExpanded }
                )
                if (autoConditionsExpanded) {
                    VideoDivider()
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

                VideoDivider()
                ExpandableHeaderRow(
                    icon = Icons.Rounded.FolderCopy,
                    title = "文件归类条件",
                    subtitle = organizeConditionSummary(state),
                    expanded = organizeConditionsExpanded,
                    onClick = { organizeConditionsExpanded = !organizeConditionsExpanded }
                )
                if (organizeConditionsExpanded) {
                    VideoDivider()
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
        }

        item {
            LuoShuSectionTitle(
                eyebrow = "PROTECTION",
                title = "清理保护",
                subtitle = "安全阈值、白名单和断点续清"
            )
        }
        item {
            VideoCard(
                modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth(),
                contentPadding = 0
            ) {
                ExpandableHeaderRow(
                    icon = Icons.Rounded.Security,
                    title = "安全阈值",
                    subtitle = "最低电量 ${scheduler.minBattery}% · 单文件 ${scheduler.maxFileMb} MB",
                    expanded = safetyExpanded,
                    onClick = { safetyExpanded = !safetyExpanded }
                )
                if (safetyExpanded) {
                    VideoDivider()
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
                }
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

        item {
            LuoShuSectionTitle(
                eyebrow = "NOTIFICATIONS",
                title = "通知",
                subtitle = "控制自动任务结束后的系统提醒"
            )
        }
        item {
            VideoCard(
                modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth(),
                contentPadding = 0
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

        item {
            LuoShuSectionTitle(
                eyebrow = "SERVICE",
                title = "服务与诊断",
                subtitle = "Root、规则、异常与高级入口"
            )
        }
        item {
            VideoCard(
                modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth(),
                contentPadding = 0
            ) {
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
                    .height(54.dp)
                    .clickable(
                        enabled = !scheduler.saving,
                        onClick = { actions.onSaveScheduler(scheduler) }
                    ),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primary,
                tonalElevation = 1.dp,
                shadowElevation = 5.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (scheduler.saving) "正在保存…" else "保存设置",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsStatusHero(state: SettingsUiState) {
    val healthy = state.connected && state.ready
    VideoCard(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        contentPadding = 20
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = .11f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(27.dp)
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        state.running -> "Root 任务执行中"
                        healthy -> "白泽运行正常"
                        state.connected -> "Root 服务已连接"
                        else -> "正在恢复 Root 服务"
                    },
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    state.serviceText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    maxLines = 2
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .045f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "自动任务",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    state.schedulerText,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ExpandableHeaderRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VideoLeadingIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
        }
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = if (expanded) "收起" else "展开",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { rotationZ = if (expanded) 180f else 0f }
        )
    }
}

@Composable
private fun SliderSettingRow(
    icon: ImageVector,
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
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 15.sp
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

private fun autoConditionSummary(state: SettingsUiState): String = buildList {
    if (state.scheduler.screenOffOnly) add("息屏")
    if (state.scheduler.chargingOnly) add("充电")
    if (state.scheduler.idleOnly) add("空闲")
}.ifEmpty { listOf("无额外限制") }.joinToString(" · ")

private fun organizeConditionSummary(state: SettingsUiState): String = buildList {
    if (state.scheduler.organizeScreenOffOnly) add("息屏")
    if (state.scheduler.organizeChargingOnly) add("充电")
    if (state.scheduler.organizeIdleOnly) add("空闲")
}.ifEmpty { listOf("无额外限制") }.joinToString(" · ")
