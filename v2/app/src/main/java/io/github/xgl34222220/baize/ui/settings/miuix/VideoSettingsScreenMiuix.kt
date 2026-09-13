package io.github.xgl34222220.baize.ui.settings.miuix

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.ExpandLess
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.clean.IntValueDialog
import io.github.xgl34222220.baize.ui.components.DetailStatusText
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
import io.github.xgl34222220.baize.ui.miuix.VideoCard
import io.github.xgl34222220.baize.ui.miuix.VideoDivider
import io.github.xgl34222220.baize.ui.miuix.VideoLeadingIcon
import io.github.xgl34222220.baize.ui.miuix.VideoListRow
import io.github.xgl34222220.baize.ui.miuix.VideoSectionTitle
import io.github.xgl34222220.baize.ui.miuix.VideoStatusPill
import io.github.xgl34222220.baize.ui.miuix.VideoSwitchRow
import io.github.xgl34222220.baize.ui.miuix.VideoTopBar
import io.github.xgl34222220.baize.ui.settings.SettingsUiActions
import io.github.xgl34222220.baize.ui.settings.SettingsUiState
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

@Composable
fun VideoSettingsScreenMiuix(state: SettingsUiState, actions: SettingsUiActions) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val scheduler = state.scheduler
    var showExecution by rememberSaveable { mutableStateOf(false) }
    var showOrganizer by rememberSaveable { mutableStateOf(false) }
    var showNotifications by rememberSaveable { mutableStateOf(false) }
    var editBattery by remember { mutableStateOf(false) }
    var editFileLimit by remember { mutableStateOf(false) }

    if (editBattery) IntValueDialog(
        title = "最低执行电量", description = "低于这个电量时，后台清理会等待。", initialValue = scheduler.minBattery,
        range = 0..100, suffix = "%", onDismiss = { editBattery = false },
        onConfirm = { actions.onUpdateScheduler(scheduler.copy(minBattery = it)) }
    )
    if (editFileLimit) IntValueDialog(
        title = "单文件自动清理上限", description = "超过上限的文件会保留，可在手动扫描明细中检查。", initialValue = scheduler.maxFileMb,
        range = 16..2048, suffix = "MB", onDismiss = { editFileLimit = false },
        onConfirm = { actions.onUpdateScheduler(scheduler.copy(maxFileMb = it)) }
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            VideoTopBar(title = "设置", actions = {
                TextButton(onClick = { actions.onSaveScheduler(scheduler) }, enabled = !scheduler.saving) {
                    Text(if (scheduler.saving) "保存中" else "保存", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            })
        }
        item { ServiceOverview(state) }
        item {
            BoxWithConstraints(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                if (maxWidth < 320.dp || LocalDensity.current.fontScale > 1.2f) {
                    VideoCard(Modifier.fillMaxWidth()) {
                        VideoListRow(Icons.Rounded.DarkMode, "界面与主题", state.appearance.themeMode.label,
                            onClick = actions.onOpenAppearance)
                        VideoDivider()
                        VideoListRow(Icons.Rounded.Security, "应用白名单", "已保护 ${state.whitelistCount} 个应用",
                            onClick = actions.onOpenWhitelist)
                    }
                } else {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsShortcut(Icons.Rounded.DarkMode, "界面与主题", state.appearance.themeMode.label,
                            actions.onOpenAppearance, Modifier.weight(1f).fillMaxHeight())
                        SettingsShortcut(Icons.Rounded.Security, "应用白名单", "已保护 ${state.whitelistCount} 个应用",
                            actions.onOpenWhitelist, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
        item { VideoSectionTitle("自动任务", "按你的使用习惯运行") }
        item {
            SettingsGroup(
                Icons.Rounded.SettingsSuggest, "清理执行条件",
                "${conditionSummary(scheduler.screenOffOnly, scheduler.chargingOnly, scheduler.idleOnly)} · 电量 ≥ ${scheduler.minBattery}%",
                showExecution, { showExecution = !showExecution }
            ) {
                VideoSwitchRow(Icons.Rounded.DarkMode, "仅息屏时执行", "使用手机时暂缓清理", scheduler.screenOffOnly,
                    { actions.onUpdateScheduler(scheduler.copy(screenOffOnly = it)) })
                VideoDivider()
                VideoSwitchRow(Icons.Rounded.BatterySaver, "仅充电时执行", "连接电源后开始", scheduler.chargingOnly,
                    { actions.onUpdateScheduler(scheduler.copy(chargingOnly = it)) })
                VideoDivider()
                VideoSwitchRow(Icons.Rounded.SettingsSuggest, "仅空闲时执行", "设备空闲后开始", scheduler.idleOnly,
                    { actions.onUpdateScheduler(scheduler.copy(idleOnly = it)) })
                VideoDivider()
                VideoListRow(Icons.Rounded.BatterySaver, "最低执行电量", "电量不足时等待", value = "${scheduler.minBattery}%", onClick = { editBattery = true })
                VideoDivider()
                VideoListRow(Icons.Rounded.Security, "单文件清理上限", "大于上限的文件会保留", value = "${scheduler.maxFileMb} MB", onClick = { editFileLimit = true })
            }
        }
        item {
            SettingsGroup(Icons.Rounded.FolderCopy, "归类执行条件",
                conditionSummary(scheduler.organizeScreenOffOnly, scheduler.organizeChargingOnly, scheduler.organizeIdleOnly),
                showOrganizer, { showOrganizer = !showOrganizer }) {
                VideoSwitchRow(Icons.Rounded.DarkMode, "归类时等待息屏", "避免打断前台使用", scheduler.organizeScreenOffOnly,
                    { actions.onUpdateScheduler(scheduler.copy(organizeScreenOffOnly = it)) })
                VideoDivider()
                VideoSwitchRow(Icons.Rounded.BatterySaver, "归类时等待充电", "连接电源后整理", scheduler.organizeChargingOnly,
                    { actions.onUpdateScheduler(scheduler.copy(organizeChargingOnly = it)) })
                VideoDivider()
                VideoSwitchRow(Icons.Rounded.SettingsSuggest, "归类时等待空闲", "设备空闲后整理", scheduler.organizeIdleOnly,
                    { actions.onUpdateScheduler(scheduler.copy(organizeIdleOnly = it)) })
            }
        }
        item {
            SettingsGroup(Icons.Rounded.Notifications, "任务通知",
                if (!scheduler.notifyOnComplete) "已关闭" else if (scheduler.notifyZero) "每次任务完成后提醒" else "有清理结果时提醒",
                showNotifications, { showNotifications = !showNotifications }) {
                VideoSwitchRow(Icons.Rounded.Notifications, "任务完成通知", "自动任务结束后显示结果", scheduler.notifyOnComplete,
                    { actions.onUpdateScheduler(scheduler.copy(notifyOnComplete = it)) })
                VideoDivider()
                VideoSwitchRow(Icons.Rounded.Notifications, "零结果也通知", "没有可清理内容时也提醒", scheduler.notifyZero,
                    { actions.onUpdateScheduler(scheduler.copy(notifyZero = it)) })
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassActionButton(if (scheduler.saving) "正在保存…" else "保存任务设置",
                    onClick = { actions.onSaveScheduler(scheduler) }, enabled = !scheduler.saving,
                    secondary = true, modifier = Modifier.fillMaxWidth())
                Text("执行条件与通知保存后生效。", Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { VideoSectionTitle("记录与诊断") }
        item {
            VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                VideoListRow(Icons.Rounded.Rule, "清理结果与保护", "查看明细与保留项", onClick = actions.onOpenAudit)
                VideoDivider()
                VideoListRow(Icons.Rounded.PlayArrow, "断点续清", "继续已保存的扫描任务", onClick = actions.onOpenResumableScan)
                VideoDivider()
                VideoListRow(Icons.Rounded.Refresh, "重新连接服务", "重新获取服务连接", onClick = actions.onReconnect)
                VideoDivider()
                VideoListRow(Icons.Rounded.BugReport, "崩溃与诊断信息", "查看异常与故障记录", onClick = actions.onOpenCrashDiagnostics)
            }
        }
    }
}

@Composable
private fun ServiceOverview(state: SettingsUiState) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        containerColor = lerp(BaiZeTokens.colors.surfaceRaised, MaterialTheme.colorScheme.primary, .035f),
        contentPadding = 20) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("清理服务", fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold)
                Text(if (state.scheduler.enabled) "自动计划已开启" else "自动计划已暂停",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            VideoStatusPill(when {
                state.running -> "执行中"
                state.ready -> "已就绪"
                state.connected -> "准备中"
                else -> "待连接"
            }, state.ready || state.running)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = .04f))
            .heightIn(min = 44.dp).clickable(role = Role.Button) { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (expanded) "收起服务详情" else "查看服务详情", Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null,
                Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        AnimatedVisibility(expanded || (!state.ready && !state.running)) {
            DetailStatusText(listOf(state.serviceText, state.schedulerText).filter { it.isNotBlank() }.distinct().joinToString("\n"),
                Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun SettingsShortcut(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier) {
    VideoCard(modifier.clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClick = onClick), contentPadding = 18) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            VideoLeadingIcon(icon)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsGroup(
    icon: ImageVector, title: String, summary: String, expanded: Boolean,
    onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit
) {
    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
        VideoListRow(icon, title, summary, onClick = onToggle, trailing = {
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收起$title" else "展开$title",
                modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        })
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(16.dp)).background(BaiZeTokens.colors.surfaceOverlay.copy(alpha = .45f)), content = content)
        }
    }
}

private fun conditionSummary(screenOff: Boolean, charging: Boolean, idle: Boolean): String =
    buildList {
        if (screenOff) add("息屏")
        if (charging) add("充电")
        if (idle) add("空闲")
    }.let { if (it.isEmpty()) "随时执行" else "等待${it.joinToString("、")}" }
