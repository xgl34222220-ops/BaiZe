package io.github.xgl34222220.baize.ui.settings.miuix

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.FileOrganizerWorker
import io.github.xgl34222220.baize.UninstallWatcherSettings
import io.github.xgl34222220.baize.WechatUsage
import io.github.xgl34222220.baize.formatUsageBytes
import io.github.xgl34222220.baize.wechatUsageTierHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.xgl34222220.baize.ui.clean.IntValueDialog
import io.github.xgl34222220.baize.ui.components.DetailStatusText
import io.github.xgl34222220.baize.ui.miuix.*
import io.github.xgl34222220.baize.ui.settings.SettingsUiActions
import io.github.xgl34222220.baize.ui.settings.SettingsUiState
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/**
 * 清理 → 自动清理 →「执行条件与高级」：原 设置 →「自动任务设置」整页（草稿 + 右上角保存）。
 * 文件归类的执行条件、同名文件策略、开启后立即执行也只在这里修改（文件归类页只读显示）。
 */
@Composable
internal fun AutomationSettingsPage(state: SettingsUiState, actions: SettingsUiActions, back: () -> Unit) {
    val s = state.scheduler
    var edit by rememberSaveable { mutableStateOf("") }
    if (edit == "tier") AlertDialog(
        onDismissRequest = { edit = "" },
        title = { Text("应用专项清理档位") },
        text = {
            Column {
                for (tier in 0..2) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = s.appProfileTier == tier, onClick = {
                            actions.onUpdateScheduler(s.copy(appProfileTier = tier, appProfileUserMedia = s.appProfileUserMedia && tier == 2))
                            edit = ""
                        }).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = s.appProfileTier == tier, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(appProfileTierLabel(tier), fontWeight = FontWeight.Medium)
                            Text(appProfileTierDescription(tier), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { edit = "" }) { Text("关闭") } }
    ) else if (edit == "media") AlertDialog(
        onDismissRequest = { edit = "" },
        title = { Text("清理聊天媒体？") },
        text = { Text("将按增强档清理 ${s.appProfileMediaDays} 天前的微信聊天图片（含缩略图）、视频、语音，以及 QQ 聊天图片与短视频。删除后无法在白泽中恢复；聊天记录数据库、收藏、表情与收到的文件不受影响。") },
        confirmButton = { TextButton(onClick = { actions.onUpdateScheduler(s.copy(appProfileUserMedia = true)); edit = "" }) { Text("开启") } },
        dismissButton = { TextButton(onClick = { edit = "" }) { Text("取消") } }
    ) else if (edit == "mediaDays") AlertDialog(
        onDismissRequest = { edit = "" },
        title = { Text("聊天媒体保留天数") },
        text = {
            Column {
                for (days in APP_PROFILE_MEDIA_DAY_CHOICES) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = s.appProfileMediaDays == days, onClick = {
                            actions.onUpdateScheduler(s.copy(appProfileMediaDays = days))
                            edit = ""
                        }).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = s.appProfileMediaDays == days, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("$days 天前", fontWeight = FontWeight.Medium)
                            Text(appProfileMediaDaysDescription(days), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { edit = "" }) { Text("关闭") } }
    ) else if (edit == "conflict") AlertDialog(
        onDismissRequest = { edit = "" },
        title = { Text("同名文件") },
        text = {
            Column {
                for (policy in 0..2) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = s.organizerConflictPolicy == policy, onClick = {
                            actions.onUpdateScheduler(s.copy(organizerConflictPolicy = policy))
                            edit = ""
                        }).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = s.organizerConflictPolicy == policy, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Text(FileOrganizerWorker.conflictPolicyLabel(policy), fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { edit = "" }) { Text("关闭") } }
    ) else if (edit == "wechat") WechatUsageDialog(actions.onLoadWechatUsage, onDismiss = { edit = "" }) else if (edit.isNotEmpty()) IntValueDialog(
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
            LuoShuPageHeader("执行条件与高级", back) {
                TextButton(onClick = { actions.onSaveScheduler(s) }, enabled = !s.saving) { Text(if (s.saving) "保存中" else "保存") }
            }
        }
        item { LuoShuSection("清理执行条件") }
        item {
            LuoShuGroup {
                LuoShuSwitchRow(Icons.Rounded.DarkMode, "仅息屏时执行", "使用手机时暂缓清理", s.screenOffOnly,
                    { actions.onUpdateScheduler(s.copy(screenOffOnly = it)) })
                LuoShuGroupDivider()
                LuoShuSwitchRow(Icons.Rounded.BatterySaver, "仅充电时执行", "连接电源后开始", s.chargingOnly,
                    { actions.onUpdateScheduler(s.copy(chargingOnly = it)) })
                LuoShuGroupDivider()
                LuoShuSwitchRow(Icons.Rounded.SettingsSuggest, "仅空闲时执行", "设备空闲后开始", s.idleOnly,
                    { actions.onUpdateScheduler(s.copy(idleOnly = it)) })
                LuoShuGroupDivider()
                LuoShuNavigationRow(Icons.Rounded.BatterySaver, "最低执行电量", "${s.minBattery}%", { edit = "battery" })
                LuoShuGroupDivider()
                LuoShuNavigationRow(Icons.Rounded.Security, "单文件清理上限", "${s.maxFileMb} MB", { edit = "limit" })
            }
        }
        item { LuoShuSection("文件归类") }
        item {
            LuoShuGroup {
                LuoShuSwitchRow(Icons.Rounded.DarkMode, "归类时等待息屏", "避免打断前台使用", s.organizeScreenOffOnly,
                    { actions.onUpdateScheduler(s.copy(organizeScreenOffOnly = it)) })
                LuoShuGroupDivider()
                LuoShuSwitchRow(Icons.Rounded.BatterySaver, "归类时等待充电", "连接电源后整理", s.organizeChargingOnly,
                    { actions.onUpdateScheduler(s.copy(organizeChargingOnly = it)) })
                LuoShuGroupDivider()
                LuoShuSwitchRow(Icons.Rounded.SettingsSuggest, "归类时等待空闲", "设备空闲后整理", s.organizeIdleOnly,
                    { actions.onUpdateScheduler(s.copy(organizeIdleOnly = it)) })
                LuoShuGroupDivider()
                LuoShuSwitchRow(Icons.Rounded.PlayArrow, "开启后立即执行一次", "开启文件自动归类时加入立即执行队列", s.organizeRunImmediately,
                    { actions.onUpdateScheduler(s.copy(organizeRunImmediately = it)) })
                LuoShuGroupDivider()
                LuoShuNavigationRow(Icons.Rounded.FolderCopy, "同名文件", FileOrganizerWorker.conflictPolicyLabel(s.organizerConflictPolicy),
                    { edit = "conflict" })
                Text(
                    "开关与周期在「任务计划 → 文件自动归类」。自动清理总开关关闭时不会执行归类。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
        item { LuoShuSection("任务通知") }
        item {
            LuoShuGroup {
                LuoShuSwitchRow(Icons.Rounded.Notifications, "任务完成通知", "自动任务结束后显示结果", s.notifyOnComplete,
                    { actions.onUpdateScheduler(s.copy(notifyOnComplete = it)) })
                LuoShuGroupDivider()
                LuoShuSwitchRow(Icons.Rounded.Notifications, "零结果也通知", "没有可清理内容时也提醒", s.notifyZero,
                    { actions.onUpdateScheduler(s.copy(notifyZero = it)) })
                LuoShuGroupDivider()
                Text(
                    if (s.saving) "正在保存…" else "修改后点右上角“保存”生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
        item { LuoShuSection("应用专项清理", "微信、QQ、抖音等应用的缓存规则") }
        item {
            LuoShuGroup {
                LuoShuNavigationRow(Icons.Rounded.Tune, "清理档位", appProfileTierLabel(s.appProfileTier), { edit = "tier" })
                if (s.appProfileTier == 2) {
                    LuoShuGroupDivider()
                    LuoShuSwitchRow(Icons.Rounded.Image, "同时清理聊天媒体",
                        "仅清理 ${s.appProfileMediaDays} 天前的聊天图片、视频与语音，默认关闭", s.appProfileUserMedia,
                        { if (it) edit = "media" else actions.onUpdateScheduler(s.copy(appProfileUserMedia = false)) })
                    LuoShuGroupDivider()
                    LuoShuNavigationRow(Icons.Rounded.Schedule, "聊天媒体保留天数",
                        "只清理 ${s.appProfileMediaDays} 天前的聊天媒体", { edit = "mediaDays" })
                }
                LuoShuGroupDivider()
                LuoShuNavigationRow(Icons.Rounded.DataUsage, "微信占用分析",
                    "只读统计聊天图片、视频、缓存等各类大小", { edit = "wechat" })
            }
        }
        item { LuoShuSection("系统维护", "与自动清理同一计划：开机 15 分钟后、充电息屏时执行") }
        item {
            LuoShuGroup {
                LuoShuSwitchRow(Icons.Rounded.Storage, "充电息屏时整理存储",
                    "每天最多一次，亮屏或拔电立即停止", s.maintenanceEnabled,
                    { actions.onUpdateScheduler(s.copy(maintenanceEnabled = it)) })
                LuoShuGroupDivider()
                LuoShuSwitchRow(Icons.Rounded.FolderOff, "根目录自动整理",
                    if (s.maintenanceEnabled) "只删超过 1 天的空文件夹，维持禁止重建"
                    else "需先开启“充电息屏时整理存储”", s.rootTidyAuto && s.maintenanceEnabled,
                    { if (s.maintenanceEnabled) actions.onUpdateScheduler(s.copy(rootTidyAuto = it)) }, enabled = s.maintenanceEnabled)
                if (s.maintenanceSummary.isNotBlank()) {
                    LuoShuGroupDivider()
                    Text(
                        "最近一次：${s.maintenanceSummary}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun pagePadding(detail: Boolean = false): PaddingValues = PaddingValues(start = 16.dp, end = 16.dp,
    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + if (detail) 32.dp else 132.dp)

internal val APP_PROFILE_MEDIA_DAY_CHOICES = listOf(7, 30, 90)

internal fun appProfileMediaDaysDescription(days: Int): String = when (days) {
    7 -> "只保留最近一周的聊天媒体"
    30 -> "推荐：保留最近一个月"
    else -> "保留最近三个月"
}

/** 只读展示微信存储构成；统计在 Root 服务后台线程执行，这里只显示结果。 */
@Composable
private fun WechatUsageDialog(load: ((WechatUsage) -> Unit) -> Unit, onDismiss: () -> Unit) {
    var usage by remember { mutableStateOf<WechatUsage?>(null) }
    LaunchedEffect(Unit) { load { usage = it } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("微信存储构成") },
        text = {
            val current = usage
            when {
                current == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在只读统计，目录较大时需要几十秒…")
                }
                current.error != null -> Text("统计失败：${current.error}")
                else -> Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("共 ${formatUsageBytes(current.totalBytes)} · ${current.accounts} 个账号", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    for (entry in current.entries) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.label)
                                Text(wechatUsageTierHint(entry.tier), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(formatUsageBytes(entry.bytes), fontWeight = FontWeight.Medium)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("统计只读，不会删除任何文件；实际清理范围取决于档位与聊天媒体开关。",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

