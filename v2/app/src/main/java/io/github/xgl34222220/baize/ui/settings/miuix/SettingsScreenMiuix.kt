package io.github.xgl34222220.baize.ui.settings.miuix

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsSuggest
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
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** MIUIX 设置页使用原生 Preference、Slider、Card 与 Button。 */
@Composable
fun SettingsScreenMiuix(state: SettingsUiState, actions: SettingsUiActions) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 132.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Header() }
        item { AppearanceCard(state, actions) }
        item { SectionTitle("自动执行", "清理任务的运行条件") }
        item { AutomationGroup(state.scheduler, actions) }
        item { SectionTitle("文件自动归类", "归类任务使用独立条件") }
        item { OrganizerGroup(state.scheduler, actions) }
        item { SectionTitle("清理保护", "电量、文件大小与白名单") }
        item { SafetyGroup(state, actions) }
        item { SectionTitle("通知", "任务完成提醒") }
        item { NotificationGroup(state.scheduler, actions) }
        item { SectionTitle("服务与诊断", "Root 状态、记录和故障诊断") }
        item { ServiceGroup(state, actions) }
        item {
            MiuixButton(
                onClick = { actions.onSaveScheduler(state.scheduler) },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = !state.scheduler.saving,
                minHeight = 50.dp,
                cornerRadius = 18.dp,
                colors = MiuixButtonDefaults.buttonColorsPrimary(),
                insideMargin = PaddingValues(horizontal = 18.dp, vertical = 11.dp)
            ) {
                MiuixText(
                    if (state.scheduler.saving) "正在保存…" else "保存设置",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun Header() {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 8.dp)
    ) {
        MiuixText("偏好设置", fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        MiuixText(
            "主题、自动执行条件与清理保护",
            color = colors.onSurfaceContainer.copy(alpha = .60f),
            fontSize = 13.sp
        )
    }
}

@Composable
private fun AppearanceCard(state: SettingsUiState, actions: SettingsUiActions) {
    PreferenceGroup {
        ArrowPreference(
            title = "界面与主题",
            summary = state.appearanceSummary,
            startAction = { LeadingIcon(Icons.Rounded.DarkMode) },
            onClick = actions.onOpenAppearance
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.padding(horizontal = 20.dp, vertical = 7.dp)) {
        MiuixText(title, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(1.dp))
        MiuixText(
            subtitle,
            color = colors.onSurfaceContainer.copy(alpha = .58f),
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun AutomationGroup(scheduler: SchedulerUiState, actions: SettingsUiActions) {
    PreferenceGroup {
        SwitchPreference(
            checked = scheduler.screenOffOnly,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(screenOffOnly = it)) },
            title = "仅在息屏时执行",
            summary = "使用手机时不占用存储性能",
            startAction = { LeadingIcon(Icons.Rounded.SettingsSuggest) }
        )
        SwitchPreference(
            checked = scheduler.chargingOnly,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(chargingOnly = it)) },
            title = "仅在充电时执行",
            summary = "连接电源后再开始自动任务",
            startAction = { LeadingIcon(Icons.Rounded.BatterySaver) }
        )
        SwitchPreference(
            checked = scheduler.idleOnly,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(idleOnly = it)) },
            title = "仅在系统空闲时执行",
            summary = "等待 Android 进入空闲状态",
            startAction = { LeadingIcon(Icons.Rounded.SettingsSuggest) }
        )
    }
}

@Composable
private fun OrganizerGroup(scheduler: SchedulerUiState, actions: SettingsUiActions) {
    PreferenceGroup {
        SwitchPreference(
            checked = scheduler.organizeScreenOffOnly,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(organizeScreenOffOnly = it)) },
            title = "归类时等待息屏",
            summary = "文件移动不会打断前台操作",
            startAction = { LeadingIcon(Icons.Rounded.FolderCopy) }
        )
        SwitchPreference(
            checked = scheduler.organizeChargingOnly,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(organizeChargingOnly = it)) },
            title = "归类时等待充电",
            summary = "只在连接电源后整理文件",
            startAction = { LeadingIcon(Icons.Rounded.BatterySaver) }
        )
        SwitchPreference(
            checked = scheduler.organizeIdleOnly,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(organizeIdleOnly = it)) },
            title = "归类时等待系统空闲",
            summary = "降低文件移动对前台应用的影响",
            startAction = { LeadingIcon(Icons.Rounded.SettingsSuggest) }
        )
    }
}

@Composable
private fun SafetyGroup(state: SettingsUiState, actions: SettingsUiActions) {
    val scheduler = state.scheduler
    PreferenceGroup {
        SliderPreference(
            icon = Icons.Rounded.BatterySaver,
            title = "最低电量 ${scheduler.minBattery}%",
            summary = "低于此电量时自动等待",
            value = scheduler.minBattery.toFloat(),
            range = 0f..100f,
            steps = 19,
            onValueChange = { actions.onUpdateScheduler(scheduler.copy(minBattery = it.roundToInt())) }
        )
        SliderPreference(
            icon = Icons.Rounded.Security,
            title = "单文件上限 ${scheduler.maxFileMb} MB",
            summary = "超过上限的文件不会自动清理",
            value = scheduler.maxFileMb.toFloat(),
            range = 16f..2_048f,
            steps = 30,
            onValueChange = { actions.onUpdateScheduler(scheduler.copy(maxFileMb = it.roundToInt())) }
        )
        ArrowPreference(
            title = "应用白名单",
            summary = "${state.whitelistCount} 个应用受到保护",
            startAction = { LeadingIcon(Icons.Rounded.Security) },
            onClick = actions.onOpenWhitelist
        )
    }
}

@Composable
private fun NotificationGroup(scheduler: SchedulerUiState, actions: SettingsUiActions) {
    PreferenceGroup {
        SwitchPreference(
            checked = scheduler.notifyOnComplete,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(notifyOnComplete = it)) },
            title = "任务完成通知",
            summary = "自动任务结束后显示结果",
            startAction = { LeadingIcon(Icons.Rounded.Notifications) }
        )
        SwitchPreference(
            checked = scheduler.notifyZero,
            onCheckedChange = { actions.onUpdateScheduler(scheduler.copy(notifyZero = it)) },
            title = "零结果也通知",
            summary = "没有可清理内容时也发送通知",
            startAction = { LeadingIcon(Icons.Rounded.Notifications) }
        )
    }
}

@Composable
private fun ServiceGroup(state: SettingsUiState, actions: SettingsUiActions) {
    val status = when {
        state.running -> "执行中"
        state.ready -> "运行正常"
        state.connected -> "已连接"
        else -> "恢复中"
    }
    PreferenceGroup {
        StatusPreference(state = state, status = status)
        ArrowPreference(
            title = "重新连接 Root 服务",
            summary = "服务异常时手动触发一次重连",
            startAction = { LeadingIcon(Icons.Rounded.Refresh) },
            onClick = actions.onReconnect
        )
        ArrowPreference(
            title = "清理记录与保护内容",
            summary = state.schedulerText,
            startAction = { LeadingIcon(Icons.Rounded.Security) },
            onClick = actions.onOpenAudit
        )
        ArrowPreference(
            title = "崩溃与故障诊断",
            summary = "查看最近一次 App 异常信息",
            startAction = { LeadingIcon(Icons.Rounded.BugReport) },
            onClick = actions.onOpenCrashDiagnostics
        )
    }
}

@Composable
private fun PreferenceGroup(content: @Composable Column.() -> Unit) {
    val colors = MiuixTheme.colorScheme
    MiuixCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(0.dp),
        colors = MiuixCardDefaults.defaultColors(
            color = colors.surfaceContainer,
            contentColor = colors.onSurfaceContainer
        )
    ) {
        content()
    }
}

@Composable
private fun SliderPreference(
    icon: ImageVector,
    title: String,
    summary: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.Top
    ) {
        LeadingIcon(icon)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            MiuixText(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(1.dp))
            MiuixText(
                summary,
                color = colors.onSurfaceContainer.copy(alpha = .56f),
                fontSize = 11.sp
            )
            Spacer(Modifier.height(7.dp))
            MiuixSlider(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                valueRange = range,
                steps = steps,
                height = 28.dp
            )
        }
    }
}

@Composable
private fun StatusPreference(state: SettingsUiState, status: String) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (state.ready) BaiZeTokens.colors.success else BaiZeTokens.colors.warning)
        )
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            MiuixText("Root 服务", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(1.dp))
            MiuixText(
                state.serviceText,
                color = colors.onSurfaceContainer.copy(alpha = .56f),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        MiuixText(
            status,
            color = colors.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun LeadingIcon(icon: ImageVector) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(colors.primary.copy(alpha = .10f)),
        contentAlignment = Alignment.Center
    ) {
        MiuixIcon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = colors.primary
        )
    }
}
