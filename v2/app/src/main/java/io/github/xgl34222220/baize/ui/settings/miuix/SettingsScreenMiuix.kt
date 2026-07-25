package io.github.xgl34222220.baize.ui.settings.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsSuggest
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
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.settings.SettingsUiActions
import io.github.xgl34222220.baize.ui.settings.SettingsUiState
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlin.math.roundToInt

@Composable
fun SettingsScreenMiuix(state: SettingsUiState, actions: SettingsUiActions) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 100.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { MiuixSettingsHeader() }
        item { MiuixAppearancePanel(state, actions) }
        item { MiuixSectionTitle("自动执行", "清理任务的运行条件") }
        item { MiuixAutomationGroup(state.scheduler, actions) }
        item { MiuixSectionTitle("文件自动归类", "归类任务使用独立条件") }
        item { MiuixOrganizerGroup(state.scheduler, actions) }
        item { MiuixSectionTitle("清理保护", "电量、文件大小与白名单") }
        item { MiuixSafetyGroup(state, actions) }
        item { MiuixSectionTitle("通知", "任务完成提醒") }
        item { MiuixNotificationGroup(state.scheduler, actions) }
        item { MiuixSectionTitle("服务状态", "后台自动恢复，无需手动操作") }
        item { MiuixServiceGroup(state) }
        item {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 18.dp)
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(
                        enabled = !state.scheduler.saving,
                        onClick = { actions.onSaveScheduler(state.scheduler) }
                    ),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (state.scheduler.saving) "正在保存…" else "保存设置",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun MiuixSettingsHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 22.dp, vertical = 20.dp)
    ) {
        Text("偏好设置", fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text("主题、自动执行条件与清理保护", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}

@Composable
private fun MiuixAppearancePanel(state: SettingsUiState, actions: SettingsUiActions) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = actions.onOpenAppearance),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.DarkMode, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("界面与主题", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    state.appearanceSummary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MiuixSectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 22.dp)) {
        Text(title, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun MiuixAutomationGroup(scheduler: SchedulerUiState, actions: SettingsUiActions) {
    MiuixGroup {
        MiuixSwitchRow(
            icon = Icons.Rounded.SettingsSuggest,
            title = "仅在息屏时执行",
            subtitle = "使用手机时不占用存储性能",
            checked = scheduler.screenOffOnly,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(screenOffOnly = it)) }
        )
        MiuixDivider()
        MiuixSwitchRow(
            icon = Icons.Rounded.BatterySaver,
            title = "仅在充电时执行",
            subtitle = "连接电源后再开始自动任务",
            checked = scheduler.chargingOnly,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(chargingOnly = it)) }
        )
        MiuixDivider()
        MiuixSwitchRow(
            icon = Icons.Rounded.SettingsSuggest,
            title = "仅在系统空闲时执行",
            subtitle = "等待 Android 进入空闲状态",
            checked = scheduler.idleOnly,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(idleOnly = it)) }
        )
    }
}

@Composable
private fun MiuixOrganizerGroup(scheduler: SchedulerUiState, actions: SettingsUiActions) {
    MiuixGroup {
        MiuixSwitchRow(
            icon = Icons.Rounded.FolderCopy,
            title = "归类时等待息屏",
            subtitle = "文件移动不会打断前台操作",
            checked = scheduler.organizeScreenOffOnly,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(organizeScreenOffOnly = it)) }
        )
        MiuixDivider()
        MiuixSwitchRow(
            icon = Icons.Rounded.BatterySaver,
            title = "归类时等待充电",
            subtitle = "只在连接电源后整理文件",
            checked = scheduler.organizeChargingOnly,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(organizeChargingOnly = it)) }
        )
        MiuixDivider()
        MiuixSwitchRow(
            icon = Icons.Rounded.SettingsSuggest,
            title = "归类时等待系统空闲",
            subtitle = "降低文件移动对前台应用的影响",
            checked = scheduler.organizeIdleOnly,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(organizeIdleOnly = it)) }
        )
    }
}

@Composable
private fun MiuixSafetyGroup(state: SettingsUiState, actions: SettingsUiActions) {
    val scheduler = state.scheduler
    MiuixGroup {
        MiuixSliderRow(
            icon = Icons.Rounded.BatterySaver,
            title = "最低电量 ${scheduler.minBattery}%",
            subtitle = "低于此电量时自动等待",
            value = scheduler.minBattery.toFloat(),
            range = 0f..100f,
            steps = 19,
            onValueChange = { actions.onUpdateScheduler(scheduler.copy(minBattery = it.roundToInt())) }
        )
        MiuixDivider()
        MiuixSliderRow(
            icon = Icons.Rounded.Security,
            title = "单文件上限 ${scheduler.maxFileMb} MB",
            subtitle = "超过上限的文件不会自动清理",
            value = scheduler.maxFileMb.toFloat(),
            range = 16f..2_048f,
            steps = 30,
            onValueChange = { actions.onUpdateScheduler(scheduler.copy(maxFileMb = it.roundToInt())) }
        )
        MiuixDivider()
        MiuixValueRow(
            icon = Icons.Rounded.Security,
            title = "应用白名单",
            subtitle = "${state.whitelistCount} 个应用受到保护",
            onClick = actions.onOpenWhitelist
        )
    }
}

@Composable
private fun MiuixNotificationGroup(scheduler: SchedulerUiState, actions: SettingsUiActions) {
    MiuixGroup {
        MiuixSwitchRow(
            icon = Icons.Rounded.Notifications,
            title = "任务完成通知",
            subtitle = "自动任务结束后显示结果",
            checked = scheduler.notifyOnComplete,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(notifyOnComplete = it)) }
        )
        MiuixDivider()
        MiuixSwitchRow(
            icon = Icons.Rounded.Notifications,
            title = "零结果也通知",
            subtitle = "没有可清理内容时也发送通知",
            checked = scheduler.notifyZero,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(notifyZero = it)) }
        )
    }
}

@Composable
private fun MiuixServiceGroup(state: SettingsUiState) {
    MiuixGroup {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (state.ready) BaiZeTokens.colors.success else BaiZeTokens.colors.warning)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Root 服务", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    state.serviceText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)
            ) {
                Text(
                    if (state.running) "执行中" else if (state.ready) "正常" else "恢复中",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun MiuixGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = BaiZeTokens.colors.surfaceRaised
    ) {
        Column(content = content)
    }
}

@Composable
private fun MiuixSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MiuixLeadingIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MiuixSliderRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.Top
    ) {
        MiuixLeadingIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps)
        }
    }
}

@Composable
private fun MiuixValueRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MiuixLeadingIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MiuixLeadingIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = .10f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun MiuixDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 68.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .50f)
    )
}
