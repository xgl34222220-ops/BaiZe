package io.github.xgl34222220.baize.ui.settings

import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.miuix.ProvideVideoSkin
import io.github.xgl34222220.baize.ui.miuix.VideoSkin
import io.github.xgl34222220.baize.ui.settings.miuix.VideoSettingsScreenMiuix

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

    val skin = when (style) {
        UiStyle.MATERIAL -> VideoSkin.MATERIAL3
        UiStyle.MIUIX -> VideoSkin.MIUIX
    }
    ProvideVideoSkin(skin) {
        VideoSettingsScreenMiuix(state, actions)
    }
}
