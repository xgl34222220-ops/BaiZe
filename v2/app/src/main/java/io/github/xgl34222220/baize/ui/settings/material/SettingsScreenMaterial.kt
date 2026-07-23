package io.github.xgl34222220.baize.ui.settings.material

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
import androidx.compose.foundation.layout.weight
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
import io.github.xgl34222220.baize.ui.settings.SettingsUiActions
import io.github.xgl34222220.baize.ui.settings.SettingsUiState

@Composable
fun SettingsScreenMaterial(
    state: SettingsUiState,
    actions: SettingsUiActions
) {
    val config = state.scheduler
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = bottomInset + 132.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Text("自动化设置", fontSize = 34.sp, fontWeight = FontWeight.Black)
                Text(
                    "白泽会自行调度、重试和恢复，无需手动唤醒",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SettingsCard {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Text(
                        if (config.enabled) "全自动清理已开启" else "全自动清理已关闭",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        "临时失败和后台重连由 Root Supervisor 自动处理，界面不再显示熔断或守护进程操作。",
                        modifier = Modifier.padding(top = 5.dp, bottom = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    SwitchRow(Icons.Rounded.AutoAwesome, "启用全自动清理", "统一控制后台清理任务", config.enabled) {
                        actions.onUpdateScheduler(config.copy(enabled = it))
                    }
                    SwitchRow(Icons.Rounded.Bedtime, "息屏时自动执行", "降低前台使用时的影响", config.screenOffOnly) {
                        actions.onUpdateScheduler(config.copy(screenOffOnly = it))
                    }
                    SwitchRow(Icons.Rounded.BatteryChargingFull, "仅充电时执行", "关闭后仍遵守电量与温度保护", config.chargingOnly) {
                        actions.onUpdateScheduler(config.copy(chargingOnly = it))
                    }
                    SwitchRow(Icons.Rounded.FolderCopy, "自动文件归类", "每天自动整理下载与接收文件", config.organizeEnabled) {
                        actions.onUpdateScheduler(config.copy(organizeEnabled = it))
                    }
                    SwitchRow(Icons.Rounded.Notifications, "完成后通知", "只报告结果，不要求人工处理", config.notifyOnComplete) {
                        actions.onUpdateScheduler(config.copy(notifyOnComplete = it))
                    }
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
                    .height(56.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(if (config.saving) "正在保存…" else "保存并自动运行", fontWeight = FontWeight.Black)
            }
        }

        item {
            SettingsCard {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                    ActionRow(Icons.Rounded.Palette, "界面与主题", "配色、深色模式与玻璃效果", actions.onOpenAppearance)
                    ActionRow(Icons.Rounded.Security, "清理白名单", "保护应用和指定路径", actions.onOpenWhitelist)
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        content = { content() }
    )
}

@Composable
private fun SwitchRow(
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
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ActionRow(
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
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
