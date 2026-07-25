package io.github.xgl34222220.baize.ui.settings.material

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.settings.SettingsUiActions
import io.github.xgl34222220.baize.ui.settings.SettingsUiState
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlin.math.roundToInt

@Composable
fun SettingsScreenMaterial(state: SettingsUiState, actions: SettingsUiActions) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = bottomInset + 104.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { MaterialSettingsHeader() }
        item { MaterialAppearanceCard(state, actions) }
        item { MaterialSectionHeader("自动执行", "控制清理任务的运行条件") }
        item { MaterialAutomationConditions(state.scheduler, actions) }
        item { MaterialSectionHeader("文件自动归类", "归类任务可使用独立执行条件") }
        item { MaterialOrganizerConditions(state.scheduler, actions) }
        item { MaterialSectionHeader("清理保护", "限制电量、文件大小和白名单") }
        item { MaterialSafetySettings(state, actions) }
        item { MaterialSectionHeader("通知", "只保留有用的完成提醒") }
        item { MaterialNotificationSettings(state.scheduler, actions) }
        item { MaterialSectionHeader("服务状态", "后台会自动恢复，无需手动控制") }
        item { MaterialServiceStatus(state) }
        item {
            Button(
                onClick = { actions.onSaveScheduler(state.scheduler) },
                enabled = !state.scheduler.saving,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Text(if (state.scheduler.saving) "正在保存…" else "保存设置", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MaterialSettingsHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Text("偏好设置", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "主题、自动执行条件与清理保护",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun MaterialAppearanceCard(state: SettingsUiState, actions: SettingsUiActions) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clickable(onClick = actions.onOpenAppearance),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(46.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .10f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.DarkMode, contentDescription = null)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("界面与主题", style = MaterialTheme.typography.titleLarge)
                Text(
                    state.appearanceSummary,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun MaterialSectionHeader(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MaterialAutomationConditions(scheduler: SchedulerUiState, actions: SettingsUiActions) {
    MaterialGroup {
        MaterialSwitchRow(
            icon = Icons.Rounded.SettingsSuggest,
            title = "仅在息屏时执行",
            subtitle = "避免在使用手机时占用存储性能",
            checked = scheduler.screenOffOnly,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(screenOffOnly = it)) }
        )
        MaterialDivider()
        MaterialSwitchRow(
            icon = Icons.Rounded.BatterySaver,
            title = "仅在充电时执行",
            subtitle = "适合深度任务和夜间自动清理",
            checked = scheduler.chargingOnly,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(chargingOnly = it)) }
        )
        MaterialDivider()
        MaterialSwitchRow(
            icon = Icons.Rounded.SettingsSuggest,
            title = "仅在系统空闲时执行",
            subtitle = "等待 Android 进入空闲状态",
            checked = scheduler.idleOnly,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(idleOnly = it)) }
        )
    }
}

@Composable
private fun MaterialOrganizerConditions(scheduler: SchedulerUiState, actions: SettingsUiActions) {
    MaterialGroup {
        MaterialSwitchRow(
            icon = Icons.Rounded.FolderCopy,
            title = "归类时等待息屏",
            subtitle = "文件整理不会打断前台操作",
            checked = scheduler.organizeScreenOffOnly,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(organizeScreenOffOnly = it)) }
        )
        MaterialDivider()
        MaterialSwitchRow(
            icon = Icons.Rounded.BatterySaver,
            title = "归类时等待充电",
            subtitle = "只在设备连接电源后整理文件",
            checked = scheduler.organizeChargingOnly,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(organizeChargingOnly = it)) }
        )
        MaterialDivider()
        MaterialSwitchRow(
            icon = Icons.Rounded.SettingsSuggest,
            title = "归类时等待系统空闲",
            subtitle = "减少文件移动对前台应用的影响",
            checked = scheduler.organizeIdleOnly,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(organizeIdleOnly = it)) }
        )
    }
}

@Composable
private fun MaterialSafetySettings(state: SettingsUiState, actions: SettingsUiActions) {
    val scheduler = state.scheduler
    MaterialGroup {
        MaterialSliderRow(
            icon = Icons.Rounded.BatterySaver,
            title = "最低电量 ${scheduler.minBattery}%",
            subtitle = "低于此电量时自动等待",
            value = scheduler.minBattery.toFloat(),
            range = 0f..100f,
            steps = 19,
            onValueChange = { actions.onUpdateScheduler(scheduler.copy(minBattery = it.roundToInt())) }
        )
        MaterialDivider()
        MaterialSliderRow(
            icon = Icons.Rounded.Security,
            title = "单文件上限 ${scheduler.maxFileMb} MB",
            subtitle = "超过上限的文件不会自动清理",
            value = scheduler.maxFileMb.toFloat(),
            range = 16f..2_048f,
            steps = 30,
            onValueChange = { actions.onUpdateScheduler(scheduler.copy(maxFileMb = it.roundToInt())) }
        )
        MaterialDivider()
        MaterialValueRow(
            icon = Icons.Rounded.Security,
            title = "应用白名单",
            subtitle = "${state.whitelistCount} 个应用受到保护",
            onClick = actions.onOpenWhitelist
        )
    }
}

@Composable
private fun MaterialNotificationSettings(scheduler: SchedulerUiState, actions: SettingsUiActions) {
    MaterialGroup {
        MaterialSwitchRow(
            icon = Icons.Rounded.Notifications,
            title = "任务完成通知",
            subtitle = "自动任务结束后显示结果",
            checked = scheduler.notifyOnComplete,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(notifyOnComplete = it)) }
        )
        MaterialDivider()
        MaterialSwitchRow(
            icon = Icons.Rounded.Notifications,
            title = "零结果也通知",
            subtitle = "没有可清理内容时也发送通知",
            checked = scheduler.notifyZero,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(notifyZero = it)) }
        )
    }
}

@Composable
private fun MaterialServiceStatus(state: SettingsUiState) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (state.ready) BaiZeTokens.colors.success else BaiZeTokens.colors.warning)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Root 服务", style = MaterialTheme.typography.titleMedium)
                Text(
                    state.serviceText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    if (state.running) "执行中" else if (state.ready) "正常" else "恢复中",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun MaterialGroup(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(content = content)
    }
}

@Composable
private fun MaterialSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MaterialLeadingIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MaterialSliderRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        MaterialLeadingIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps)
        }
    }
}

@Composable
private fun MaterialValueRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MaterialLeadingIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MaterialLeadingIcon(icon: ImageVector) {
    Surface(
        modifier = Modifier.size(42.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
private fun MaterialDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 70.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .60f)
    )
}
