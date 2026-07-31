package io.github.xgl34222220.baize.ui.history

import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.history.material.HistoryScreenMaterial
import io.github.xgl34222220.baize.ui.history.miuix.VideoHistoryScreenMiuix

@Composable
fun HistoryRoute(
    style: UiStyle,
    dashboard: DashboardUiState,
    dashboardActions: DashboardActions
) {
    val state = dashboard.toHistoryUiState()
    val actions = HistoryUiActions(
        onRefresh = dashboardActions.refresh,
        onClearHistory = dashboardActions.clearHistory,
        onReviewProtected = dashboardActions.reviewProtected
    )

    when (style) {
        UiStyle.MATERIAL -> HistoryScreenMaterial(state, actions)
        UiStyle.MIUIX -> VideoHistoryScreenMiuix(state, actions)
    }
}
