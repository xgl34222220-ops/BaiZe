package io.github.xgl34222220.baize.ui.settings.material

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.settings.SettingsUiActions
import io.github.xgl34222220.baize.ui.settings.SettingsUiState
import io.github.xgl34222220.baize.ui.settings.schedulerCountdownLabel
import io.github.xgl34222220.baize.ui.settings.schedulerTaskLabel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun SettingsScreenMaterial(
    state: SettingsUiState,
    actions: SettingsUiActions
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val config = state.scheduler

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomInset + 146.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { MaterialSettingsHeader() }
            item { MaterialAppearanceCard(state, actions) }
            item { MaterialSectionTitle("任务管理", "任务中心") }
            item { MaterialTaskCenterCard(state) }
            item { MaterialSectionTitle("自动执行", "自动清理") }
            item { MaterialAutomationCard(config, actions) }
            item { MaterialSectionTitle("安全保护", "清理保护与通知") }
            item { MaterialProtectionCard(state, actions) }
            item { MaterialSectionTitle("系统服务", "服务与诊断") }
            item { MaterialServiceCard(state, actions) }
            item {
                Button(
                    onClick = { actions.onSaveScheduler(config) },
                    enabled = !config.saving,
                    modifier = Modifier
                        .padding(horizontal = 18.dp)
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Text(
                        if (config.saving) "正在保存…" else "保存全部设置",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun MaterialSettingsHeader() {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 15.dp)
    ) {
        Text(
            "设置中心",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.3.sp
        )
        Spacer(Modifier.height(5.dp))
        Text("偏好设置", style = MaterialTheme.typography.headlineLarge)
        Text(
            "外观、自动清理、保护规则与服务管理",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun MaterialAppearanceCard(
    state: SettingsUiState,
    actions: SettingsUiActions
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(20.dp)) {
            MaterialCardHeader(
                icon = Icons.Rounded.Palette,
                title = "界面与主题",
                subtitle = state.appearanceSummary
            )
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MaterialInfoPill(state.appearance.uiStyle.label, Modifier.weight(1f))
                MaterialInfoPill(state.appearance.themeMode.label, Modifier.weight(1f))
                MaterialInfoPill(state.appearance.kolorStyle.label, Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(
                onClick = actions.onOpenAppearance,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Icon(Icons.Rounded.Tune, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("主题模式、配色与玻璃", fontWeight = FontWeight.Bold)
            }
        }
    }
}


@Composable
private fun MaterialTaskCenterCard(state: SettingsUiState) {
    val config = state.scheduler
    val nowEpoch = rememberMaterialSchedulerNowEpoch()
    val runningGroup = config.runtimeGroup.ifBlank { config.nextTask }
    val isRunning = config.runtimeState == "running"
    Card(
        modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(20.dp)) {
            MaterialCardHeader(
                icon = Icons.Rounded.Rule,
                title = if (isRunning) "正在执行 ${schedulerTaskLabel(runningGroup)}" else "待执行",
                subtitle = "自动任务会按计划运行，暂时未满足条件时继续等待"
            )
            Spacer(Modifier.height(12.dp))
            MaterialTaskStatusRow("应用缓存清理", schedulerCountdownLabel(config.enabled && config.cacheEnabled, config.cacheNextEpoch, isRunning && runningGroup == "cache", nowEpoch))
            HorizontalDivider()
            MaterialTaskStatusRow("空文件与空目录清理", schedulerCountdownLabel(config.enabled && config.emptyEnabled, config.emptyNextEpoch, isRunning && runningGroup == "empty", nowEpoch))
            HorizontalDivider()
            MaterialTaskStatusRow("规则垃圾清理", schedulerCountdownLabel(config.enabled && config.rulesEnabled, config.rulesNextEpoch, isRunning && runningGroup == "rules", nowEpoch))
            HorizontalDivider()
            MaterialTaskStatusRow("残留碎片清理", schedulerCountdownLabel(config.enabled && config.fragmentEnabled, config.fragmentNextEpoch, isRunning && runningGroup == "fragment", nowEpoch))
            HorizontalDivider()
            MaterialTaskStatusRow("深度安全清理", schedulerCountdownLabel(config.enabled && config.deepEnabled, config.deepNextEpoch, isRunning && runningGroup == "deep", nowEpoch))
            HorizontalDivider()
            MaterialTaskStatusRow("文件自动归类", schedulerCountdownLabel(config.enabled && config.organizeEnabled, config.organizeNextEpoch, isRunning && runningGroup == "organize", nowEpoch))
        }
    }
}

@Composable
private fun MaterialTaskStatusRow(title: String, status: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Rule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(status, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun rememberMaterialSchedulerNowEpoch(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis() / 1000L
        }
    }
    return now
}

@Composable
private fun MaterialAutomationCard(
    config: SchedulerUiState,
    actions: SettingsUiActions
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            MaterialCardHeader(
                icon = Icons.Rounded.CleaningServices,
                title = "自动清理",
                subtitle = if (config.enabled) "调度器已启用" else "调度器总开关已关闭"
            )
            Spacer(Modifier.height(10.dp))
            MaterialSwitchRow(
                title = "启用自动清理",
                description = "按各清理类别设定的周期自动执行",
                checked = config.enabled,
                onCheckedChange = { actions.onUpdateScheduler(config.copy(enabled = it)) }
            )
            HorizontalDivider()
            MaterialSwitchRow(
                title = "等待息屏后执行",
                description = "减少前台使用期间的性能影响",
                checked = config.screenOffOnly,
                onCheckedChange = { actions.onUpdateScheduler(config.copy(screenOffOnly = it)) }
            )
            HorizontalDivider()
            MaterialSwitchRow(
                title = "仅在充电时执行",
                description = "避免自动任务额外消耗电量",
                checked = config.chargingOnly,
                onCheckedChange = { actions.onUpdateScheduler(config.copy(chargingOnly = it)) }
            )
            HorizontalDivider()
            MaterialSwitchRow(
                title = "仅在设备空闲时执行",
                description = "由系统空闲状态限制后台任务",
                checked = config.idleOnly,
                onCheckedChange = { actions.onUpdateScheduler(config.copy(idleOnly = it)) }
            )
            HorizontalDivider()
            MaterialSwitchRow(
                title = "启用定时文件归类",
                description = "与垃圾清理共享公平队列，不并行读写",
                checked = config.organizeEnabled,
                onCheckedChange = { actions.onUpdateScheduler(config.copy(organizeEnabled = it)) }
            )
            Spacer(Modifier.height(12.dp))
            MaterialSliderSetting(
                icon = Icons.Rounded.Rule,
                title = "归类周期 ${materialOrganizerIntervalLabel(config.organizeMinutes)}",
                description = "后台计划为唯一真实设置来源",
                value = config.organizeMinutes.toFloat(),
                valueRange = 15f..43_200f,
                steps = 0,
                enabled = config.organizeEnabled,
                onValueChange = { actions.onUpdateScheduler(config.copy(organizeMinutes = it.roundToInt().coerceIn(15, 43_200))) }
            )
            MaterialSliderSetting(
                icon = Icons.Rounded.Security,
                title = "同名策略 ${materialConflictPolicyLabel(config.organizerConflictPolicy)}",
                description = "跳过、自动重命名或内容去重",
                value = config.organizerConflictPolicy.toFloat(),
                valueRange = 0f..2f,
                steps = 1,
                enabled = config.organizeEnabled,
                onValueChange = { actions.onUpdateScheduler(config.copy(organizerConflictPolicy = it.roundToInt().coerceIn(0, 2))) }
            )
            MaterialSliderSetting(
                icon = Icons.Rounded.Rule,
                title = "保留 ${config.organizerUndoRetention} 次撤销",
                description = "撤销记录跨重启持久保存",
                value = config.organizerUndoRetention.toFloat(),
                valueRange = 1f..20f,
                steps = 18,
                enabled = config.organizeEnabled,
                onValueChange = { actions.onUpdateScheduler(config.copy(organizerUndoRetention = it.roundToInt().coerceIn(1, 20))) }
            )
            Spacer(Modifier.height(8.dp))
            MaterialSliderSetting(
                icon = Icons.Rounded.Rule,
                title = "最低电量 ${config.minBattery}%",
                description = "电量低于此值时不启动自动清理",
                value = config.minBattery.toFloat(),
                valueRange = 0f..100f,
                steps = 19,
                onValueChange = {
                    actions.onUpdateScheduler(config.copy(minBattery = (it / 5f).roundToInt() * 5))
                }
            )
        }
    }
}

@Composable
private fun MaterialProtectionCard(
    state: SettingsUiState,
    actions: SettingsUiActions
) {
    val config = state.scheduler
    Card(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            MaterialCardHeader(
                icon = Icons.Rounded.Shield,
                title = "清理保护",
                subtitle = if (state.whitelistCount > 0) {
                    "已保护 ${state.whitelistCount} 个应用"
                } else {
                    "尚未添加应用白名单"
                }
            )
            Spacer(Modifier.height(15.dp))
            FilledTonalButton(
                onClick = actions.onOpenWhitelist,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Icon(Icons.Rounded.Security, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("管理应用白名单", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            MaterialSwitchRow(
                title = "任务完成后发送通知",
                description = "显示释放空间和处理项目数量",
                checked = config.notifyOnComplete,
                onCheckedChange = { actions.onUpdateScheduler(config.copy(notifyOnComplete = it)) }
            )
            HorizontalDivider()
            MaterialSwitchRow(
                title = "没有垃圾时也发送通知",
                description = "零结果任务仍然显示完成通知",
                checked = config.notifyZero,
                onCheckedChange = { actions.onUpdateScheduler(config.copy(notifyZero = it)) }
            )
            HorizontalDivider()
            MaterialSwitchRow(
                title = "清理过期 APK 安装包",
                description = "扫描常见下载目录中的 APK、APKS 与 XAPK",
                checked = config.apkPackagesEnabled,
                onCheckedChange = { actions.onUpdateScheduler(config.copy(apkPackagesEnabled = it)) }
            )
            Spacer(Modifier.height(12.dp))
            MaterialSliderSetting(
                icon = Icons.Rounded.InstallMobile,
                title = "安装包保留 ${config.apkPackageDays} 天",
                description = "0 天表示安装包进入扫描范围后即可作为候选",
                value = config.apkPackageDays.toFloat(),
                valueRange = 0f..365f,
                steps = 0,
                enabled = config.apkPackagesEnabled,
                onValueChange = {
                    actions.onUpdateScheduler(config.copy(apkPackageDays = it.roundToInt().coerceIn(0, 365)))
                }
            )
            Spacer(Modifier.height(8.dp))
            MaterialSliderSetting(
                icon = Icons.Rounded.Security,
                title = "单文件上限 ${config.maxFileMb} MB",
                description = "超过上限的文件只统计，不会自动删除",
                value = config.maxFileMb.toFloat(),
                valueRange = 16f..2048f,
                steps = 0,
                onValueChange = {
                    actions.onUpdateScheduler(config.copy(maxFileMb = ((it / 16f).roundToInt() * 16).coerceIn(16, 2048)))
                }
            )
        }
    }
}

@Composable
private fun MaterialServiceCard(
    state: SettingsUiState,
    actions: SettingsUiActions
) {
    val statusColor = when {
        state.running -> MaterialTheme.colorScheme.tertiary
        state.serviceHealthy -> MaterialTheme.colorScheme.primary
        state.connected -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(statusColor, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Root 服务", style = MaterialTheme.typography.titleLarge)
                    Text(
                        state.serviceText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                state.schedulerText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(16.dp))
            MaterialActionButton(Icons.Rounded.Refresh, "重新连接 Root 服务", actions.onReconnect)
            Spacer(Modifier.height(8.dp))
            MaterialActionButton(Icons.Rounded.Rule, "打开清理明细", actions.onOpenAudit)
            Spacer(Modifier.height(8.dp))
            MaterialActionButton(Icons.Rounded.BugReport, "崩溃诊断", actions.onOpenCrashDiagnostics)
        }
    }
}

@Composable
private fun MaterialCardHeader(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.large),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MaterialSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MaterialSliderSetting(
    icon: ImageVector,
    title: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled
        )
    }
}


private fun materialOrganizerIntervalLabel(minutes: Int): String = when (minutes) {
    30 -> "30 分钟"
    60 -> "1 小时"
    360 -> "6 小时"
    720 -> "12 小时"
    1_440 -> "每天"
    4_320 -> "3 天"
    10_080 -> "每周"
    else -> "${minutes} 分钟"
}

private fun materialConflictPolicyLabel(value: Int): String = when (value) {
    0 -> "跳过"
    2 -> "内容去重"
    else -> "自动重命名"
}

@Composable
private fun MaterialActionButton(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(9.dp))
        Text(title, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MaterialInfoPill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.extraLarge)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MaterialSectionTitle(eyebrow: String, title: String) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 2.dp)) {
        Text(
            eyebrow,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
    }
}
