package io.github.xgl34222220.baize.ui.home

import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.HomeScreenMiuix
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.home.material.HomeScreenMaterial

@Composable
fun HomeRoute(style: UiStyle, state: DashboardUiState, actions: DashboardActions) {
    when (style) {
        UiStyle.MATERIAL -> HomeScreenMaterial(state, actions)
        UiStyle.MIUIX -> HomeScreenMiuix(state, actions)
    }
}
