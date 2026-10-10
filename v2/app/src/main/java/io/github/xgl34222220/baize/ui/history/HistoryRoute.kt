package io.github.xgl34222220.baize.ui.history

import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.history.miuix.HistoryScreenMiuix
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
        onReviewProtected = dashboardActions.reviewProtected,
        onOpenTrash = dashboardActions.fileTrash,
        onOpenAudit = dashboardActions.cleanupAudit
    )

    // 两种皮肤共用同一个记录页（原 Material 专用的 VideoHistoryScreenMiuix 已删除），差异只来自 VideoSkin。
    ProvideVideoSkin(if (style == UiStyle.MIUIX) VideoSkin.MIUIX else VideoSkin.MATERIAL3) {
        HistoryScreenMiuix(state, actions)
    }
}
