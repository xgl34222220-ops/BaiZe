package io.github.xgl34222220.baize.ui.settings.miuix

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.BuildConfig
import io.github.xgl34222220.baize.ui.clean.IntValueDialog
import io.github.xgl34222220.baize.ui.components.DetailStatusText
import io.github.xgl34222220.baize.ui.miuix.*
import io.github.xgl34222220.baize.ui.settings.SettingsUiActions
import io.github.xgl34222220.baize.ui.settings.SettingsUiState
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/** LuoShu SettingsHubScreen structure: overview -> navigation groups -> separate detail page. */
@Composable
fun LuoShuSettingsHub(state: SettingsUiState, actions: SettingsUiActions, onDetailChanged: (Boolean) -> Unit = {}) {
    var section by rememberSaveable { mutableStateOf("") }
    val notify by rememberUpdatedState(onDetailChanged)
    LaunchedEffect(section) { notify(section.isNotEmpty()) }
    DisposableEffect(Unit) { onDispose { notify(false) } }
    BackHandler(enabled = section.isNotEmpty()) { section = "" }
    AnimatedContent(targetState = section,
        transitionSpec = {
            if (targetState.isNotEmpty()) {
                (fadeIn(tween(250)) + slideInHorizontally(tween(340)) { it }) togetherWith
                    (fadeOut(tween(210), targetAlpha = .52f) + slideOutHorizontally(tween(340)) { -it / 7 })
            } else {
                (fadeIn(tween(230)) + slideInHorizontally(tween(340)) { -it / 7 }) togetherWith
                    (fadeOut(tween(210)) + slideOutHorizontally(tween(340)) { it })
            }
        }, label = "settingsHub") { target ->
        when (target) {
            "tasks" -> TaskSettings(state, actions, { section = "" })
            "service" -> ServiceDetails(state, actions, { section = "" })
            else -> SettingsHome(state, actions, { section = it })
        }
    }
}

@Composable
private fun pagePadding(detail: Boolean = false): PaddingValues = PaddingValues(start = 20.dp, end = 20.dp,
    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + if (detail) 32.dp else 118.dp)

@Composable
private fun SettingsHome(state: SettingsUiState, actions: SettingsUiActions, open: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val colors = BaiZeTokens.colors
    val statusColor = if (state.ready) colors.success else colors.warning
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { LuoShuPageHeader("设置") }
        item {
            Surface(onClick = { open("service") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
                color = colors.surfaceRaised, shadowElevation = 1.dp) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(52.dp), shape = RoundedCornerShape(18.dp), color = scheme.primary.copy(alpha = .10f)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("泽", color = scheme.primary, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("白泽状态", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(state.connectionLabel, fontSize = 12.sp, lineHeight = 18.sp, color = statusColor)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(22.dp), tint = scheme.onSurfaceVariant)
                    }
                    Surface(shape = RoundedCornerShape(16.dp), color = scheme.primary.copy(alpha = .045f)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("自动清理", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                            Text(if (state.scheduler.enabled) "自动计划已开启" else "自动计划已暂停",
                                fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                            if (!state.ready && state.serviceText.isNotBlank()) {
                                DetailStatusText(state.serviceText)
                            }
                        }
                    }
                }
            }
        }
        item { LuoShuSection("你的白泽", "调整喜欢的样子，回看每次清理") }
        item {
            LuoShuGroup {
                LuoShuNavigationRow(Icons.Rounded.Palette, "外观与主题", "颜色、深色模式与界面效果", actions.onOpenAppearance)
                LuoShuGroupDivider()
                LuoShuNavigationRow(Icons.Rounded.History, "清理记录", "结果明细、保留项目与任务记录", actions.onOpenAudit)
            }
        }
        item { LuoShuSection("管理与维护") }
        item {
            LuoShuGroup {
                LuoShuNavigationRow(Icons.Rounded.CalendarMonth, "自动任务设置", "执行条件、文件上限与完成通知", { open("tasks") })
                LuoShuGroupDivider()
                LuoShuNavigationRow(Icons.Rounded.Shield, "应用白名单", "已保护 ${state.whitelistCount} 个应用", actions.onOpenWhitelist)
                LuoShuGroupDivider()
                LuoShuNavigationRow(Icons.Rounded.PlayArrow, "断点续清", "继续已保存的扫描任务", actions.onOpenResumableScan)
                LuoShuGroupDivider()
                LuoShuNavigationRow(Icons.Rounded.Security, "连接与诊断", "服务详情、重新连接与异常信息", { open("service") })
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("白泽 · ${BuildConfig.VERSION_NAME}", color = scheme.onSurfaceVariant, fontSize = 12.sp)
                Text("清理有据，保留有度", color = scheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun TaskSettings(state: SettingsUiState, actions: SettingsUiActions, back: () -> Unit) {
    val s = state.scheduler
    var edit by rememberSaveable { mutableStateOf("") }
    if (edit.isNotEmpty()) IntValueDialog(
        title = if (edit == "battery") "最低执行电量" else "单文件自动清理上限",
        description = if (edit == "battery") "电量不足时等待，不改变清理内容。" else "大于上限的文件会保留，可在手动扫描中检查。",
        initialValue = if (edit == "battery") s.minBattery else s.maxFileMb,
        range = if (edit == "battery") 0..100 else 16..2048,
        suffix = if (edit == "battery") "%" else "MB", onDismiss = { edit = "" }, onConfirm = {
            actions.onUpdateScheduler(if (edit == "battery") s.copy(minBattery = it) else s.copy(maxFileMb = it))
            edit = ""
        })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding(detail = true), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            LuoShuPageHeader("自动任务设置", back) {
                TextButton(onClick = { actions.onSaveScheduler(s) }, enabled = !s.saving) { Text(if (s.saving) "保存中" else "保存") }
            }
        }
        item { LuoShuSection("清理执行条件", "条件满足后，才会运行自动任务") }
        item {
            LuoShuGroup {
                VideoSwitchRow(Icons.Rounded.DarkMode, "仅息屏时执行", "使用手机时暂缓清理", s.screenOffOnly,
                    { actions.onUpdateScheduler(s.copy(screenOffOnly = it)) })
                LuoShuGroupDivider()
                VideoSwitchRow(Icons.Rounded.BatterySaver, "仅充电时执行", "连接电源后开始", s.chargingOnly,
                    { actions.onUpdateScheduler(s.copy(chargingOnly = it)) })
                LuoShuGroupDivider()
                VideoSwitchRow(Icons.Rounded.SettingsSuggest, "仅空闲时执行", "设备空闲后开始", s.idleOnly,
                    { actions.onUpdateScheduler(s.copy(idleOnly = it)) })
                LuoShuGroupDivider()
                LuoShuNavigationRow(Icons.Rounded.BatterySaver, "最低执行电量", "${s.minBattery}% · 电量不足时等待", { edit = "battery" })
                LuoShuGroupDivider()
                LuoShuNavigationRow(Icons.Rounded.Security, "单文件清理上限", "${s.maxFileMb} MB · 大于上限的文件会保留", { edit = "limit" })
            }
        }
        item { LuoShuSection("文件归类") }
        item {
            LuoShuGroup {
                VideoSwitchRow(Icons.Rounded.DarkMode, "归类时等待息屏", "避免打断前台使用", s.organizeScreenOffOnly,
                    { actions.onUpdateScheduler(s.copy(organizeScreenOffOnly = it)) })
                LuoShuGroupDivider()
                VideoSwitchRow(Icons.Rounded.BatterySaver, "归类时等待充电", "连接电源后整理", s.organizeChargingOnly,
                    { actions.onUpdateScheduler(s.copy(organizeChargingOnly = it)) })
                LuoShuGroupDivider()
                VideoSwitchRow(Icons.Rounded.SettingsSuggest, "归类时等待空闲", "设备空闲后整理", s.organizeIdleOnly,
                    { actions.onUpdateScheduler(s.copy(organizeIdleOnly = it)) })
            }
        }
        item { LuoShuSection("任务通知") }
        item {
            LuoShuGroup {
                VideoSwitchRow(Icons.Rounded.Notifications, "任务完成通知", "自动任务结束后显示结果", s.notifyOnComplete,
                    { actions.onUpdateScheduler(s.copy(notifyOnComplete = it)) })
                LuoShuGroupDivider()
                VideoSwitchRow(Icons.Rounded.Notifications, "零结果也通知", "没有可清理内容时也提醒", s.notifyZero,
                    { actions.onUpdateScheduler(s.copy(notifyZero = it)) })
            }
        }
        item {
            Button(onClick = { actions.onSaveScheduler(s) }, enabled = !s.saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(18.dp)) {
                Text(if (s.saving) "正在保存…" else "保存任务设置")
            }
        }
        item { Text("执行条件与通知保存后生效。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ServiceDetails(state: SettingsUiState, actions: SettingsUiActions, back: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding(detail = true), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { LuoShuPageHeader("连接与诊断", back) }
        item {
            LuoShuGroup {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("清理服务 · ${state.connectionLabel}", style = MaterialTheme.typography.titleMedium)
                    DetailStatusText(listOf(state.serviceText, state.schedulerText).filter { it.isNotBlank() }.distinct().joinToString("\n"))
                }
            }
        }
        item {
            LuoShuGroup {
                LuoShuNavigationRow(Icons.Rounded.Refresh, "重新连接服务", "重新获取服务连接", actions.onReconnect)
                LuoShuGroupDivider()
                LuoShuNavigationRow(Icons.Rounded.BugReport, "崩溃与诊断信息", "查看异常与故障记录", actions.onOpenCrashDiagnostics)
            }
        }
    }
}
