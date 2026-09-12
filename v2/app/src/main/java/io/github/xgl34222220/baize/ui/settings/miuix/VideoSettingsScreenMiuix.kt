package io.github.xgl34222220.baize.ui.settings.miuix

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.clean.IntValueDialog
import io.github.xgl34222220.baize.ui.miuix.VideoCard
import io.github.xgl34222220.baize.ui.miuix.VideoDivider
import io.github.xgl34222220.baize.ui.miuix.VideoListRow
import io.github.xgl34222220.baize.ui.miuix.VideoSectionTitle
import io.github.xgl34222220.baize.ui.miuix.VideoStatusPill
import io.github.xgl34222220.baize.ui.miuix.VideoSwitchRow
import io.github.xgl34222220.baize.ui.miuix.VideoTopBar
import io.github.xgl34222220.baize.ui.settings.SettingsUiActions
import io.github.xgl34222220.baize.ui.settings.SettingsUiState

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
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { VideoTopBar(title = "设置", subtitle = "按你的习惯，安排每一次清理") }
        item {
            VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), containerColor = MaterialTheme.colorScheme.primaryContainer, contentPadding = 24) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("白泽服务", modifier = Modifier.weight(1f), fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    VideoStatusPill(when { state.running -> "执行中"; state.ready -> "已就绪"; state.connected -> "准备中"; else -> "待连接" }, state.ready || state.running)
                }
                Spacer(Modifier.height(10.dp))
                Text(state.serviceText, fontSize = 14.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(state.schedulerText, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { VideoSectionTitle("个性化与保护") }
        item {
            VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 0) {
                VideoListRow(Icons.Rounded.DarkMode, "界面与主题", "${state.appearance.uiStyle.label} · ${state.appearance.themeMode.label} · ${if (state.appearance.monetEnabled) "壁纸配色" else state.appearance.accent.label}", onClick = actions.onOpenAppearance)
                VideoDivider()
                VideoListRow(Icons.Rounded.Security, "应用白名单", "保护重要应用，跳过相关清理", value = "${state.whitelistCount} 个", onClick = actions.onOpenWhitelist)
                VideoDivider()
                VideoListRow(Icons.Rounded.Rule, "清理规则与明细", "查看规则命中与受保护文件", onClick = actions.onOpenAudit)
            }
        }
        item { VideoSectionTitle("后台运行", "此处的条件会影响定时任务能否到点执行") }
        item {
            VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 0) {
                VideoListRow(Icons.Rounded.SettingsSuggest, "自动清理执行条件",
                    conditionSummary(scheduler.screenOffOnly, scheduler.chargingOnly, scheduler.idleOnly),
                    value = if (showExecution) "收起" else "调整", onClick = { showExecution = !showExecution })
                AnimatedVisibility(showExecution) {
                    Column {
                        VideoDivider()
                        VideoSwitchRow(Icons.Rounded.DarkMode, "仅息屏时执行", "使用手机时暂缓后台清理", scheduler.screenOffOnly,
                            { actions.onUpdateScheduler(scheduler.copy(screenOffOnly = it)) })
                        VideoDivider()
                        VideoSwitchRow(Icons.Rounded.BatterySaver, "仅充电时执行", "连接电源后再开始任务", scheduler.chargingOnly,
                            { actions.onUpdateScheduler(scheduler.copy(chargingOnly = it)) })
                        VideoDivider()
                        VideoSwitchRow(Icons.Rounded.SettingsSuggest, "仅系统空闲时执行", "等待 Android 报告空闲状态", scheduler.idleOnly,
                            { actions.onUpdateScheduler(scheduler.copy(idleOnly = it)) })
                    }
                }
                VideoDivider()
                VideoListRow(Icons.Rounded.BatterySaver, "最低执行电量", "电量不足时等待", value = "${scheduler.minBattery}%", onClick = { editBattery = true })
                VideoDivider()
                VideoListRow(Icons.Rounded.Security, "单文件清理上限", "大于上限的文件不会自动清理", value = "${scheduler.maxFileMb} MB", onClick = { editFileLimit = true })
            }
        }
        item {
            VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 0) {
                VideoListRow(Icons.Rounded.FolderCopy, "文件归类执行条件",
                    conditionSummary(scheduler.organizeScreenOffOnly, scheduler.organizeChargingOnly, scheduler.organizeIdleOnly),
                    value = if (showOrganizer) "收起" else "调整", onClick = { showOrganizer = !showOrganizer })
                AnimatedVisibility(showOrganizer) {
                    Column {
                        VideoDivider()
                        VideoSwitchRow(Icons.Rounded.DarkMode, "归类时等待息屏", "避免移动文件打断前台使用", scheduler.organizeScreenOffOnly,
                            { actions.onUpdateScheduler(scheduler.copy(organizeScreenOffOnly = it)) })
                        VideoDivider()
                        VideoSwitchRow(Icons.Rounded.BatterySaver, "归类时等待充电", "接通电源后整理文件", scheduler.organizeChargingOnly,
                            { actions.onUpdateScheduler(scheduler.copy(organizeChargingOnly = it)) })
                        VideoDivider()
                        VideoSwitchRow(Icons.Rounded.SettingsSuggest, "归类时等待系统空闲", "设备空闲后再整理文件", scheduler.organizeIdleOnly,
                            { actions.onUpdateScheduler(scheduler.copy(organizeIdleOnly = it)) })
                    }
                }
            }
        }
        item {
            VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 0) {
                VideoListRow(Icons.Rounded.Notifications, "任务通知",
                    if (!scheduler.notifyOnComplete) "任务完成后不提醒" else if (scheduler.notifyZero) "每次任务完成后提醒" else "清理有结果时提醒",
                    value = if (showNotifications) "收起" else "调整", onClick = { showNotifications = !showNotifications })
                AnimatedVisibility(showNotifications) {
                    Column {
                        VideoDivider()
                        VideoSwitchRow(Icons.Rounded.Notifications, "任务完成通知", "自动任务结束后显示结果", scheduler.notifyOnComplete,
                            { actions.onUpdateScheduler(scheduler.copy(notifyOnComplete = it)) })
                        VideoDivider()
                        VideoSwitchRow(Icons.Rounded.Notifications, "零结果也通知", "未发现可清理内容时也提醒", scheduler.notifyZero,
                            { actions.onUpdateScheduler(scheduler.copy(notifyZero = it)) })
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.onSaveScheduler(scheduler) }, enabled = !scheduler.saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp)) {
                    Text(if (scheduler.saving) "正在保存…" else "保存运行设置", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("运行条件与通知修改后需要保存；主题设置即时生效。", fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { VideoSectionTitle("恢复与诊断") }
        item {
            VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 0) {
                VideoListRow(Icons.Rounded.PlayArrow, "断点续清", "从已保存的扫描结果继续处理", onClick = actions.onOpenResumableScan)
                VideoDivider()
                VideoListRow(Icons.Rounded.Refresh, "重新连接服务", "授权变化或服务异常时使用", onClick = actions.onReconnect)
                VideoDivider()
                VideoListRow(Icons.Rounded.BugReport, "崩溃与诊断信息", "查看最近异常与故障记录", onClick = actions.onOpenCrashDiagnostics)
            }
        }
    }
}

private fun conditionSummary(screenOff: Boolean, charging: Boolean, idle: Boolean): String =
    buildList {
        if (screenOff) add("息屏")
        if (charging) add("充电")
        if (idle) add("系统空闲")
    }.let { if (it.isEmpty()) "不限制息屏、充电与空闲状态" else "等待${it.joinToString("、")}" }
