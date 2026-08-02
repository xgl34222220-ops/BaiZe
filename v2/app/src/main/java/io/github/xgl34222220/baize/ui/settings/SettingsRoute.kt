package io.github.xgl34222220.baize.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.settings.material.SettingsScreenMaterial
import io.github.xgl34222220.baize.ui.settings.miuix.SettingsScreenMiuix

@Composable
fun SettingsRoute(
    style: UiStyle,
    dashboard: DashboardUiState,
    scheduler: SchedulerUiState,
    appearance: AppearanceSettings,
    dashboardActions: DashboardActions,
    onOpenDetails: () -> Unit
) {
    // Root 服务的前台监控会持续刷新调度器运行状态。设置页必须持有独立草稿，
    // 否则用户刚修改的开关会被尚未保存的 Root 旧配置立即覆盖并回弹。
    var schedulerDraft by remember { mutableStateOf<SchedulerUiState?>(null) }

    val displayedScheduler = schedulerDraft?.copy(saving = scheduler.saving) ?: scheduler
    val state = dashboard.toSettingsUiState(displayedScheduler, appearance)
    val actions = SettingsUiActions(
        onUpdateScheduler = { updated ->
            schedulerDraft = updated.copy(saving = false)
        },
        onSaveScheduler = { updated ->
            dashboardActions.saveScheduler(updated.copy(saving = false))
        },
        onSchedulerCommand = dashboardActions.schedulerCommand,
        onOpenAppearance = dashboardActions.theme,
        onOpenWhitelist = dashboardActions.whitelist,
        onReconnect = dashboardActions.reconnect,
        onOpenAudit = onOpenDetails,
        onOpenCrashDiagnostics = dashboardActions.crash
    )

    when (style) {
        UiStyle.MATERIAL -> SettingsScreenMaterial(state = state, actions = actions)
        UiStyle.MIUIX -> SettingsScreenMiuix(state = state, actions = actions)
    }
}
