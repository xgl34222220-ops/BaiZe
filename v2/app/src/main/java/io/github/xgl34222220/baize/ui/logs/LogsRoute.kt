package io.github.xgl34222220.baize.ui.logs

import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.logs.material.LogsScreenMaterial
import io.github.xgl34222220.baize.ui.logs.miuix.LogsScreenMiuix

@Composable
fun LogsRoute(
    style: UiStyle,
    dashboard: DashboardUiState,
    dashboardActions: DashboardActions
) {
    val state = dashboard.toLogsUiState()
    val actions = LogsUiActions(
        onRefresh = dashboardActions.refresh,
        onReconnect = dashboardActions.reconnect,
        onOpenAudit = dashboardActions.audit,
        onOpenCrashDiagnostics = dashboardActions.crash,
        onClearRawLog = dashboardActions.clearRawLog,
        onClearTaskLogs = dashboardActions.clearHistory
    )

    when (style) {
        UiStyle.MATERIAL -> LogsScreenMaterial(state, actions)
        UiStyle.MIUIX -> LogsScreenMiuix(state, actions)
    }
}
