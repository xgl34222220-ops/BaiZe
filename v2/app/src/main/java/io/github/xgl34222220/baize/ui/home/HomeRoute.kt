package io.github.xgl34222220.baize.ui.home

import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.home.material.HomeScreenMaterial
import io.github.xgl34222220.baize.ui.home.miuix.HomeScreenMiuix

@Composable
fun HomeRoute(
    style: UiStyle,
    state: DashboardUiState,
    scheduler: SchedulerUiState,
    actions: DashboardActions,
    onOpenClean: () -> Unit
) {
    when (style) {
        UiStyle.MATERIAL -> HomeScreenMaterial(state, scheduler, actions, onOpenClean)
        UiStyle.MIUIX -> HomeScreenMiuix(state, scheduler, actions, onOpenClean)
    }
}
