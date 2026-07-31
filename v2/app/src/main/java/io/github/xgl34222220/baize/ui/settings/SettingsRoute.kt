package io.github.xgl34222220.baize.ui.settings

import androidx.compose.runtime.Composable
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
    val state = dashboard.toSettingsUiState(scheduler, appearance)
    val actions = SettingsUiActions(
        onUpdateScheduler = dashboardActions.updateScheduler,
        onSaveScheduler = dashboardActions.saveScheduler,
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
