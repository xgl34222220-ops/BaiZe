package io.github.xgl34222220.baize.ui.settings.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.FolderDelete
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.settings.SettingsUiActions
import io.github.xgl34222220.baize.ui.settings.SettingsUiState

/**
 * Automatic-cleaning settings exposed to users.
 *
 * Timing, task scope and device conditions are normal product controls and must remain visible.
 * Failure counters, supervisor heartbeats and manual recovery commands stay internal.
 */
@Composable
fun SettingsScreenMiuix(
    state: SettingsUiState,
    actions: SettingsUiActions
) {
    val config = state.scheduler
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = bottomInset + 146.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Text(
                    "AUTOMATION",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.2.sp
                )
                Spacer(Modifier.height(4.dp))
                Text("自动清理设置", fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Black)
                Text(
                    "选择清理内容、执行周期和运行条件",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }

        item {
            SettingsCard {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Text(
                        if (config.enabled) "全自动清理已开启" else "全自动清理已关闭",
                        modifier = Modifier.padding(vertical = 10.dp),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        if (config.enabled) {
                            "白泽会按照下面设置的周期和条件自动执行。"
                        } else {
                            "开启后才会运行下面已启用的定时任务。"
                        },
                        modifier = Modifier.padding(bottom = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                    SettingSwitchRow(
                        icon = Icons.Rounded.AutoAwesome,
                        title = "启用全自动清理",
                        description = "统一控制全部后台清理与归类任务",
                        checked = config.enabled,
                        onCheckedChange = { actions.onUpdateScheduler(config.copy(enabled = it)) }
                    )
                    SettingSwitchRow(
                        icon = Icons.Rounded.Notifications,
                        title = "完成后通知",
                        description = "任务完成时显示本次清理结果",
                        checked = config.notifyOnComplete,
                        onCheckedChange = { actions.onUpdateScheduler(config.copy(notifyOnComplete = it)) }
                    )
                }
            }
        }

        item { SectionTitle("清理周期", "每个任务可单独开启并设置多久执行一次") }

        item {
            SettingsCard {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                    IntervalSettingRow(
                        icon = Icons.Rounded.CleaningServices,
                        title = "应用缓存",
                        description = "清理常见应用缓存和临时文件",
                        enabled = config.cacheEnabled,
                        minutes = config.cacheMinutes,
                        presets = intArrayOf(15, 30, 60, 120, 360, 720, 1_440),
                        onEnabledChange = { actions.onUpdateScheduler(config.copy(cacheEnabled = it)) },
                        onMinutesChange = { actions.onUpdateScheduler(config.copy(cacheMinutes = it)) }
                    )
                    IntervalSettingRow(
                        icon = Icons.Rounded.FolderDelete,
                        title = "空文件与空目录",
                        description = "移除无内容的残留项目",
                        enabled = config.emptyEnabled,
                        minutes = config.emptyMinutes,
                        presets = intArrayOf(30, 60, 180, 360, 720, 1_440),
                        onEnabledChange = { actions.onUpdateScheduler(config.copy(emptyEnabled = it)) },
                        onMinutesChange = { actions.onUpdateScheduler(config.copy(emptyMinutes = it)) }
                    )
                    IntervalSettingRow(
                        icon = Icons.Rounded.Rule,
                        title = "规则垃圾",
                        description = "按内置安全规则清理日志和诊断缓存",
                        enabled = config.rulesEnabled,
                        minutes = config.rulesMinutes,
                        presets = intArrayOf(60, 180, 360, 720, 1_440, 2_880),
                        onEnabledChange = { actions.onUpdateScheduler(config.copy(rulesEnabled = it)) },
                        onMinutesChange = { actions.onUpdateScheduler(config.copy(rulesMinutes = it)) }
                    )
                    IntervalSettingRow(
                        icon = Icons.Rounded.DeleteSweep,
                        title = "碎片清理",
                        description = "处理可安全删除的零散残留",
                        enabled = config.fragmentEnabled,
                        minutes = config.fragmentMinutes,
                        presets = intArrayOf(180, 360, 720, 1_440, 2_880, 10_080),
                        onEnabledChange = { actions.onUpdateScheduler(config.copy(fragmentEnabled = it)) },
                        onMinutesChange = { actions.onUpdateScheduler(config.copy(fragmentMinutes = it)) }
                    )
                    IntervalSettingRow(
                        icon = Icons.Rounded.AutoAwesome,
                        title = "深度清理",
                        description = "执行更完整的扫描与清理，建议周期较长",
                        enabled = config.deepEnabled,
                        minutes = config.deepMinutes,
                        presets = intArrayOf(1_440, 2_880, 10_080, 20_160, 43_200),
                        onEnabledChange = { actions.onUpdateScheduler(config.copy(deepEnabled = it)) },
                        onMinutesChange = { actions.onUpdateScheduler(config.copy(deepMinutes = it)) }
                    )
                    IntervalSettingRow(
                        icon = Icons.Rounded.FolderCopy,
                        title = "自动文件归类",
                        description = "整理下载、接收、附件和导出文件",
                        enabled = config.organizeEnabled,
                        minutes = config.organizeMinutes,
                        presets = intArrayOf(60, 180, 360, 720, 1_440, 2_880, 10_080),
                        onEnabledChange = { actions.onUpdateScheduler(config.copy(organizeEnabled = it)) },
                        onMinutesChange = { actions.onUpdateScheduler(config.copy(organizeMinutes = it)) }
                    )
                }
            }
        }

        item { SectionTitle("固定时间", "需要时可每天在指定时间额外执行一次完整自动清理") }

        item {
            SettingsCard {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                    DailyScheduleRow(
                        enabled = config.dailyEnabled,
                        hour = config.dailyHour,
                        minute = config.dailyMinute,
                        onEnabledChange = { actions.onUpdateScheduler(config.copy(dailyEnabled = it)) },
                        onTimeChange = { hour, minute ->
                            actions.onUpdateScheduler(config.copy(dailyHour = hour, dailyMinute = minute))
                        }
                    )
                }
            }
        }

        item { SectionTitle("运行条件", "条件不满足时自动等待，满足后自动执行") }

        item {
            SettingsCard {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                    SettingSwitchRow(
                        icon = Icons.Rounded.Bedtime,
                        title = "息屏时执行",
                        description = "避免清理影响前台使用",
                        checked = config.screenOffOnly,
                        onCheckedChange = {
                            actions.onUpdateScheduler(
                                config.copy(screenOffOnly = it, organizeScreenOffOnly = it)
                            )
                        }
                    )
                    SettingSwitchRow(
                        icon = Icons.Rounded.BatteryChargingFull,
                        title = "仅充电时执行",
                        description = "适合更保守的后台策略",
                        checked = config.chargingOnly,
                        onCheckedChange = {
                            actions.onUpdateScheduler(
                                config.copy(chargingOnly = it, organizeChargingOnly = it)
                            )
                        }
                    )
                    SettingSwitchRow(
                        icon = Icons.Rounded.AutoAwesome,
                        title = "设备空闲时执行",
                        description = "等待系统进入空闲状态后运行",
                        checked = config.idleOnly,
                        onCheckedChange = {
                            actions.onUpdateScheduler(
                                config.copy(idleOnly = it, organizeIdleOnly = it)
                            )
                        }
                    )
                    BatteryLevelRow(
                        value = config.minBattery,
                        onValueChange = { actions.onUpdateScheduler(config.copy(minBattery = it)) }
                    )
                }
            }
        }

        item {
            Button(
                onClick = { actions.onSaveScheduler(config) },
                enabled = !config.saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(60.dp),
                shape = RoundedCornerShape(22.dp)
            ) {
                Text(if (config.saving) "正在保存…" else "保存定时设置", fontWeight = FontWeight.Black)
            }
        }

        item {
            SettingsCard {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                    Text(
                        "常用设置",
                        modifier = Modifier.padding(vertical = 12.dp),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black
                    )
                    SettingActionRow(
                        icon = Icons.Rounded.Palette,
                        title = "界面与主题",
                        description = "配色、深色模式与玻璃效果",
                        onClick = actions.onOpenAppearance
                    )
                    SettingActionRow(
                        icon = Icons.Rounded.Security,
                        title = "清理白名单",
                        description = "保护应用和指定路径",
                        onClick = actions.onOpenWhitelist
                    )
                }
            }
        }

        item {
            Text(
                "定时、任务范围和运行条件由你设置；后台重试与恢复由白泽自动完成。",
                modifier = Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, description: String) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 2.dp)) {
        Text(title, fontSize = 23.sp, fontWeight = FontWeight.Black)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        content = { content() }
    )
}

@Composable
private fun SettingSwitchRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun IntervalSettingRow(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    minutes: Int,
    presets: IntArray,
    onEnabledChange: (Boolean) -> Unit,
    onMinutesChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        if (enabled) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 39.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AdjustButton("−") { onMinutesChange(previousPreset(minutes, presets)) }
                Text(
                    formatInterval(minutes),
                    modifier = Modifier.width(104.dp),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
                AdjustButton("+") { onMinutesChange(nextPreset(minutes, presets)) }
            }
        }
    }
}

@Composable
private fun DailyScheduleRow(
    enabled: Boolean,
    hour: Int,
    minute: Int,
    onEnabledChange: (Boolean) -> Unit,
    onTimeChange: (Int, Int) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text("每天定时清理", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("在指定时间额外执行一次完整自动清理", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        if (enabled) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 39.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AdjustButton("−30") {
                    val shifted = shiftClock(hour, minute, -30)
                    onTimeChange(shifted.first, shifted.second)
                }
                Text(
                    "%02d:%02d".format(hour, minute),
                    modifier = Modifier.width(104.dp),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
                AdjustButton("+30") {
                    val shifted = shiftClock(hour, minute, 30)
                    onTimeChange(shifted.first, shifted.second)
                }
            }
        }
    }
}

@Composable
private fun BatteryLevelRow(value: Int, onValueChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text("最低电量", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("低于该电量时暂停自动任务", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 39.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AdjustButton("−") { onValueChange((value - 5).coerceIn(5, 95)) }
            Text(
                "$value%",
                modifier = Modifier.width(104.dp),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary
            )
            AdjustButton("+") { onValueChange((value + 5).coerceIn(5, 95)) }
        }
    }
}

@Composable
private fun AdjustButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(width = 48.dp, height = 38.dp),
        contentPadding = PaddingValues(0.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(label, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun SettingActionRow(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun previousPreset(current: Int, presets: IntArray): Int {
    return presets.lastOrNull { it < current } ?: presets.first()
}

private fun nextPreset(current: Int, presets: IntArray): Int {
    return presets.firstOrNull { it > current } ?: presets.last()
}

private fun formatInterval(minutes: Int): String {
    return when {
        minutes < 60 -> "${minutes} 分钟"
        minutes % 1_440 == 0 -> "${minutes / 1_440} 天"
        minutes % 60 == 0 -> "${minutes / 60} 小时"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分"
    }
}

private fun shiftClock(hour: Int, minute: Int, deltaMinutes: Int): Pair<Int, Int> {
    val total = ((hour * 60 + minute + deltaMinutes) % 1_440 + 1_440) % 1_440
    return total / 60 to total % 60
}
