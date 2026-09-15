package io.github.xgl34222220.baize.ui.history

import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.history.miuix.HistoryScreenMiuix
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

    if (style == UiStyle.MIUIX) {
        HistoryScreenMiuix(state, actions)
    } else {
        ProvideVideoSkin(VideoSkin.MATERIAL3) {
            VideoHistoryScreenMiuix(state, actions)
        }
    }
}
