package io.github.xgl34222220.baize.ui.home

import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.home.miuix.VideoHomeScreenMiuix
import io.github.xgl34222220.baize.ui.miuix.ProvideVideoSkin
import io.github.xgl34222220.baize.ui.miuix.VideoSkin

@Composable
fun HomeRoute(
    style: UiStyle,
    state: DashboardUiState,
    scheduler: SchedulerUiState,
    actions: DashboardActions,
    onOpenClean: () -> Unit
) {
    val skin = when (style) {
        UiStyle.MATERIAL -> VideoSkin.MATERIAL3
        UiStyle.MIUIX -> VideoSkin.MIUIX
    }
    ProvideVideoSkin(skin) {
        VideoHomeScreenMiuix(state, scheduler, actions, onOpenClean)
    }
}
