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
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.settings.SettingsUiActions
import io.github.xgl34222220.baize.ui.settings.SettingsUiState

/**
 * User-facing settings for an automatic cleaner.
 *
 * Scheduler failure counters, backoff, supervisor heartbeats and recovery commands are internal
 * implementation details. The Root supervisor already retries and recovers by itself, so they are
 * deliberately not exposed as buttons or alarming status text here.
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
                Text("自动化设置", fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Black)
                Text(
                    "白泽会自行调度、重试和恢复，无需手动唤醒",
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
                            "后台会按计划运行。暂时不满足条件或遇到临时异常时，会自动等待并恢复，不需要人工处理。"
                        } else {
                            "开启后，白泽会自动清理缓存、空项目、规则垃圾和安全碎片。"
                        },
                        modifier = Modifier.padding(bottom = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                    SettingSwitchRow(
                        icon = Icons.Rounded.AutoAwesome,
                        title = "启用全自动清理",
                        description = "统一控制所有后台清理任务",
                        checked = config.enabled,
                        onCheckedChange = { actions.onUpdateScheduler(config.copy(enabled = it)) }
                    )
                    SettingSwitchRow(
                        icon = Icons.Rounded.Bedtime,
                        title = "息屏时自动执行",
                        description = "减少前台使用时的性能影响",
                        checked = config.screenOffOnly,
                        onCheckedChange = { actions.onUpdateScheduler(config.copy(screenOffOnly = it)) }
                    )
                    SettingSwitchRow(
                        icon = Icons.Rounded.BatteryChargingFull,
                        title = "仅充电时执行",
                        description = "关闭后也会遵守最低电量和温度保护",
                        checked = config.chargingOnly,
                        onCheckedChange = { actions.onUpdateScheduler(config.copy(chargingOnly = it)) }
                    )
                    SettingSwitchRow(
                        icon = Icons.Rounded.FolderCopy,
                        title = "自动文件归类",
                        description = "每天自动整理下载、接收、附件和导出文件",
                        checked = config.organizeEnabled,
                        onCheckedChange = { actions.onUpdateScheduler(config.copy(organizeEnabled = it)) }
                    )
                    SettingSwitchRow(
                        icon = Icons.Rounded.Notifications,
                        title = "完成后通知",
                        description = "只报告结果，不要求用户干预",
                        checked = config.notifyOnComplete,
                        onCheckedChange = { actions.onUpdateScheduler(config.copy(notifyOnComplete = it)) }
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
                Text(if (config.saving) "正在保存…" else "保存并交给后台自动运行", fontWeight = FontWeight.Black)
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
                "“连续失败、熔断、守护进程心跳、手动唤醒”等属于内部容错机制，已从用户界面移除。白泽会在后台自行短暂等待并再次执行。",
                modifier = Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 17.sp
            )
        }
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
