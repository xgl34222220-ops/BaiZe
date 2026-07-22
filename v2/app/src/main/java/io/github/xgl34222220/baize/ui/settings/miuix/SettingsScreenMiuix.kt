package io.github.xgl34222220.baize.ui.settings.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.settings.SettingsUiActions
import io.github.xgl34222220.baize.ui.settings.SettingsUiState
import io.github.xgl34222220.baize.ui.settings.schedulerBlockedGroupsLabel
import io.github.xgl34222220.baize.ui.settings.schedulerQueueLabel
import io.github.xgl34222220.baize.ui.settings.schedulerReasonLabel
import io.github.xgl34222220.baize.ui.settings.schedulerSupervisorStatusLabel
import io.github.xgl34222220.baize.ui.settings.schedulerTaskLabel
import kotlin.math.roundToInt

@Composable
fun SettingsScreenMiuix(
    state: SettingsUiState,
    actions: SettingsUiActions
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val config = state.scheduler

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 146.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { MiuixSettingsHeader() }
        item { MiuixAppearanceHero(state, actions) }
        item { MiuixSectionTitle("任务管理", "任务中心", "待执行任务、暂缓原因与后台守护状态") }
        item { MiuixTaskCenter(state, actions) }
        item { MiuixSectionTitle("自动执行", "自动清理", "总开关、执行条件与最低电量") }
        item { MiuixAutomationGroup(config, actions) }
        item { MiuixSectionTitle("安全保护", "清理保护", "白名单、通知、安装包与单文件限制") }
        item { MiuixProtectionGroup(state, actions) }
        item { MiuixSectionTitle("系统服务", "服务与诊断", "后台服务恢复、清理明细与崩溃记录") }
        item { MiuixServiceGroup(state, actions) }
        item {
            Button(
                onClick = { actions.onSaveScheduler(config) },
                enabled = !config.saving,
                modifier = Modifier
                    .padding(horizontal = 18.dp)
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(
                    if (config.saving) "正在保存…" else "保存全部设置",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

@Composable
private fun MiuixSettingsHeader() {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 13.dp)
    ) {
        Text(
            "设置中心",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.4.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "偏好设置",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 36.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.Black
        )
        Text(
            "MIUI 风格设置与清理保护",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun MiuixAppearanceHero(
    state: SettingsUiState,
    actions: SettingsUiActions
) {
    val scheme = MaterialTheme.colorScheme
    MiuixGroupSurface(shape = RoundedCornerShape(36.dp), shadow = 12) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiuixIconTile(Icons.Rounded.Palette, large = true)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("界面与主题", fontSize = 21.sp, fontWeight = FontWeight.Black)
                    Text(
                        state.appearanceSummary,
                        color = scheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MiuixInfoPill(state.appearance.uiStyle.label, Modifier.weight(1f))
                MiuixInfoPill(state.appearance.themeMode.label, Modifier.weight(1f))
                MiuixInfoPill(state.appearance.kolorStyle.label, Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(21.dp))
                    .background(scheme.primary.copy(alpha = .12f))
                    .clickable(onClick = actions.onOpenAppearance)
                    .padding(horizontal = 16.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Tune, contentDescription = null, tint = scheme.primary)
                Spacer(Modifier.width(11.dp))
                Text(
                    "主题模式、配色与玻璃",
                    modifier = Modifier.weight(1f),
                    color = scheme.primary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black
                )
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = scheme.primary)
            }
        }
    }
}


@Composable
private fun MiuixTaskCenter(state: SettingsUiState, actions: SettingsUiActions) {
    val config = state.scheduler
    val scheme = MaterialTheme.colorScheme
    MiuixGroupSurface {
        Column(Modifier.padding(horizontal = 17.dp, vertical = 10.dp)) {
            Text(
                when (config.runtimeState) {
                    "running" -> "正在执行 ${schedulerTaskLabel(config.nextTask)}"
                    "failed" -> "调度异常"
                    "paused" -> "连续失败，任务暂时暂停"
                    else -> "待执行 ${config.queueCount} 项 · ${schedulerSupervisorStatusLabel(config.supervisorStatus)}"
                },
                fontSize = 17.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                buildString {
                    append(schedulerReasonLabel(config.runtimeReason))
                    if (config.queueGroups.isNotBlank()) append("\n待执行：").append(schedulerQueueLabel(config.queueGroups))
                    if (config.blockedGroups.isNotBlank()) append("\n暂缓执行：").append(schedulerBlockedGroupsLabel(config.blockedGroups))
                    if (config.runtimeStale) append("\n后台守护长时间没有响应，建议点击唤醒")
                },
                color = scheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
            Spacer(Modifier.height(8.dp))
            MiuixDivider()
            MiuixActionRow(Icons.Rounded.Refresh, "唤醒调度器", "检查计划并自动恢复后台守护进程") {
                actions.onSchedulerCommand("scheduler-wake")
            }
            MiuixDivider()
            MiuixActionRow(Icons.Rounded.CleaningServices, "立即执行清理与归类", "加入一次性公平队列，不改变正常周期") {
                actions.onSchedulerCommand("scheduler-run-now:all")
            }
            MiuixDivider()
            MiuixActionRow(Icons.Rounded.Rule, "立即执行文件归类", "条件满足后由统一后台任务执行") {
                actions.onSchedulerCommand("scheduler-run-now:organize")
            }
            MiuixDivider()
            MiuixActionRow(Icons.Rounded.Stop, "停止当前任务", "安全写入停止请求，当前后台任务会在检查点退出") {
                actions.onSchedulerCommand("scheduler-stop-current")
            }
            MiuixDivider()
            MiuixActionRow(Icons.Rounded.BugReport, "跳过下一个归类周期", "本次记为跳过，不关闭长期计划") {
                actions.onSchedulerCommand("scheduler-skip:organize")
            }
        }
    }
}

@Composable
private fun MiuixAutomationGroup(
    config: SchedulerUiState,
    actions: SettingsUiActions
) {
    MiuixGroupSurface {
        Column(Modifier.padding(horizontal = 17.dp, vertical = 6.dp)) {
            MiuixSwitchRow(
                icon = Icons.Rounded.CleaningServices,
                title = "启用自动清理",
                description = if (config.enabled) "调度器已启用" else "所有自动任务暂停",
                checked = config.enabled,
                onCheckedChange = { actions.onUpdateScheduler(config.copy(enabled = it)) }
            )
            MiuixDivider()
            MiuixSwitchRow(
                icon = Icons.Rounded.Rule,
                title = "等待息屏后执行",
                description = "降低前台使用期间的性能影响",
                checked = config.screenOffOnly,
                onCheckedChange = { actions.onUpdateScheduler(config.copy(screenOffOnly = it)) }
            )
            MiuixDivider()
            MiuixSwitchRow(
                icon = Icons.Rounded.Rule,
                title = "仅在充电时执行",
                description = "避免自动任务额外消耗电量",
                checked = config.chargingOnly,
                onCheckedChange = { actions.onUpdateScheduler(config.copy(chargingOnly = it)) }
            )
            MiuixDivider()
            MiuixSwitchRow(
                icon = Icons.Rounded.Rule,
                title = "仅在设备空闲时执行",
                description = "使用系统空闲状态限制后台任务",
                checked = config.idleOnly,
                onCheckedChange = { actions.onUpdateScheduler(config.copy(idleOnly = it)) }
            )
            MiuixDivider()
            MiuixSwitchRow(
                icon = Icons.Rounded.CleaningServices,
                title = "启用定时文件归类",
                description = "与清理共用公平队列，不会同时读写文件",
                checked = config.organizeEnabled,
                onCheckedChange = { actions.onUpdateScheduler(config.copy(organizeEnabled = it)) }
            )
            MiuixDivider()
            MiuixSliderRow(
                icon = Icons.Rounded.Rule,
                title = "归类周期 ${organizerIntervalLabel(config.organizeMinutes)}",
                description = "15 分钟到 30 天，后台计划为唯一设置来源",
                value = config.organizeMinutes.toFloat(),
                valueRange = 15f..43_200f,
                steps = 0,
                enabled = config.organizeEnabled,
                onValueChange = { actions.onUpdateScheduler(config.copy(organizeMinutes = it.roundToInt().coerceIn(15, 43_200))) }
            )
            MiuixDivider()
            MiuixSliderRow(
                icon = Icons.Rounded.Security,
                title = "同名策略 ${conflictPolicyLabel(config.organizerConflictPolicy)}",
                description = "跳过、自动重命名或内容去重",
                value = config.organizerConflictPolicy.toFloat(),
                valueRange = 0f..2f,
                steps = 1,
                enabled = config.organizeEnabled,
                onValueChange = { actions.onUpdateScheduler(config.copy(organizerConflictPolicy = it.roundToInt().coerceIn(0, 2))) }
            )
            MiuixDivider()
            MiuixSliderRow(
                icon = Icons.Rounded.Rule,
                title = "保留 ${config.organizerUndoRetention} 次撤销",
                description = "重启后仍可逐批撤销最近归类",
                value = config.organizerUndoRetention.toFloat(),
                valueRange = 1f..20f,
                steps = 18,
                enabled = config.organizeEnabled,
                onValueChange = { actions.onUpdateScheduler(config.copy(organizerUndoRetention = it.roundToInt().coerceIn(1, 20))) }
            )
            MiuixDivider()
            MiuixSliderRow(
                icon = Icons.Rounded.Rule,
                title = "最低电量 ${config.minBattery}%",
                description = "低于此值时不启动自动清理",
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
private fun MiuixProtectionGroup(
    state: SettingsUiState,
    actions: SettingsUiActions
) {
    val config = state.scheduler
    MiuixGroupSurface {
        Column(Modifier.padding(horizontal = 17.dp, vertical = 6.dp)) {
            MiuixActionRow(
                icon = Icons.Rounded.Shield,
                title = "应用白名单",
                description = if (state.whitelistCount > 0) {
                    "已保护 ${state.whitelistCount} 个应用"
                } else {
                    "尚未添加受保护应用"
                },
                onClick = actions.onOpenWhitelist
            )
            MiuixDivider()
            MiuixSwitchRow(
                icon = Icons.Rounded.Security,
                title = "任务完成后发送通知",
                description = "显示释放空间和处理数量",
                checked = config.notifyOnComplete,
                onCheckedChange = { actions.onUpdateScheduler(config.copy(notifyOnComplete = it)) }
            )
            MiuixDivider()
            MiuixSwitchRow(
                icon = Icons.Rounded.Security,
                title = "零结果也发送通知",
                description = "没有发现垃圾时仍显示完成状态",
                checked = config.notifyZero,
                onCheckedChange = { actions.onUpdateScheduler(config.copy(notifyZero = it)) }
            )
            MiuixDivider()
            MiuixSwitchRow(
                icon = Icons.Rounded.InstallMobile,
                title = "清理过期 APK 安装包",
                description = "扫描 APK、APKS 与 XAPK 下载文件",
                checked = config.apkPackagesEnabled,
                onCheckedChange = { actions.onUpdateScheduler(config.copy(apkPackagesEnabled = it)) }
            )
            MiuixDivider()
            MiuixSliderRow(
                icon = Icons.Rounded.InstallMobile,
                title = "安装包保留 ${config.apkPackageDays} 天",
                description = "控制下载目录安装包进入候选的时间",
                value = config.apkPackageDays.toFloat(),
                valueRange = 0f..365f,
                steps = 0,
                enabled = config.apkPackagesEnabled,
                onValueChange = {
                    actions.onUpdateScheduler(config.copy(apkPackageDays = it.roundToInt().coerceIn(0, 365)))
                }
            )
            MiuixDivider()
            MiuixSliderRow(
                icon = Icons.Rounded.Security,
                title = "单文件上限 ${config.maxFileMb} MB",
                description = "超过上限只统计，不会自动删除",
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
private fun MiuixServiceGroup(
    state: SettingsUiState,
    actions: SettingsUiActions
) {
    val scheme = MaterialTheme.colorScheme
    val statusColor = when {
        state.running -> Color(0xFFF2A93B)
        state.serviceHealthy -> Color(0xFF2DBE87)
        state.connected -> scheme.primary
        else -> scheme.error
    }
    MiuixGroupSurface {
        Column(Modifier.padding(horizontal = 17.dp, vertical = 10.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(11.dp).clip(CircleShape).background(statusColor))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Root 服务", fontSize = 16.sp, fontWeight = FontWeight.Black)
                    Text(
                        state.serviceText,
                        color = scheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                state.schedulerText,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp),
                color = scheme.onSurfaceVariant,
                fontSize = 10.sp
            )
            MiuixDivider()
            MiuixActionRow(Icons.Rounded.Refresh, "重新连接 Root 服务", "重新绑定双 Root 引擎", actions.onReconnect)
            MiuixDivider()
            MiuixActionRow(Icons.Rounded.Rule, "清理明细", "打开完整清理中心与候选审计", actions.onOpenAudit)
            MiuixDivider()
            MiuixActionRow(Icons.Rounded.BugReport, "崩溃诊断", "查看或清除最近 App 崩溃记录", actions.onOpenCrashDiagnostics)
        }
    }
}


private fun organizerIntervalLabel(minutes: Int): String = when (minutes) {
    30 -> "30 分钟"
    60 -> "1 小时"
    360 -> "6 小时"
    720 -> "12 小时"
    1_440 -> "每天"
    4_320 -> "3 天"
    10_080 -> "每周"
    else -> "${minutes} 分钟"
}

private fun conflictPolicyLabel(value: Int): String = when (value) {
    0 -> "跳过"
    2 -> "内容去重"
    else -> "自动重命名"
}

@Composable
private fun MiuixGroupSurface(
    shape: RoundedCornerShape = RoundedCornerShape(30.dp),
    shadow: Int = 7,
    content: @Composable () -> Unit
) {
    val settings = LocalAppearanceSettings.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val amoled = dark && settings.amoledBlack
    val background = when {
        amoled -> Color(0xFF090909)
        dark -> scheme.surfaceContainerHigh
        else -> scheme.surface
    }
    Box(
        Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .shadow(shadow.dp, shape, clip = false)
            .clip(shape)
            .background(background)
            .border(1.dp, scheme.onSurface.copy(alpha = if (dark) .08f else .05f), shape)
    ) {
        content()
    }
}

@Composable
private fun MiuixSwitchRow(
    icon: ImageVector,
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
        MiuixIconTile(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp,
                lineHeight = 13.sp
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MiuixSliderRow(
    icon: ImageVector,
    title: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiuixIconTile(icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp
                )
            }
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.padding(start = 56.dp)
        )
    }
}

@Composable
private fun MiuixActionRow(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MiuixIconTile(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(21.dp)
        )
    }
}

@Composable
private fun MiuixIconTile(icon: ImageVector, large: Boolean = false) {
    val size = if (large) 52.dp else 44.dp
    val radius = if (large) 18.dp else 15.dp
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = .12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(if (large) 27.dp else 22.dp)
        )
    }
}

@Composable
private fun MiuixInfoPill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .055f))
            .padding(horizontal = 7.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MiuixDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f)
    )
}

@Composable
private fun MiuixSectionTitle(
    eyebrow: String,
    title: String,
    subtitle: String
) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 3.dp)) {
        Text(
            eyebrow,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
        Text(title, fontSize = 27.sp, fontWeight = FontWeight.Black)
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
    }
}
