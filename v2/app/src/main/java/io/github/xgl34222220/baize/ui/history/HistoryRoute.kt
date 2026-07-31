package io.github.xgl34222220.baize.ui.history

import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.history.miuix.VideoHistoryScreenMiuix
import io.github.xgl34222220.baize.ui.miuix.ProvideVideoSkin
import io.github.xgl34222220.baize.ui.miuix.VideoSkin

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

    val skin = when (style) {
        UiStyle.MATERIAL -> VideoSkin.MATERIAL3
        UiStyle.MIUIX -> VideoSkin.MIUIX
    }
    ProvideVideoSkin(skin) {
        VideoHistoryScreenMiuix(state, actions)
    }
}
