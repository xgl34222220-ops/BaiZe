package io.github.xgl34222220.baize.ui.home

import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.home.material.HomeScreenMaterial
import io.github.xgl34222220.baize.ui.home.miuix.HomeScreenMiuix

/**
 * 两套外观只共享业务状态和操作，不共享页面骨架或视觉组件。
 * Material 3 与 MIUIX / HyperOS 分别进入各自独立实现，避免切换主题后只做换色。
 */
@Composable
fun HomeRoute(
    style: UiStyle,
    state: DashboardUiState,
    scheduler: SchedulerUiState,
    actions: DashboardActions,
    onOpenClean: () -> Unit
) {
    when (style) {
        UiStyle.MATERIAL -> HomeScreenMaterial(
            state = state,
            scheduler = scheduler,
            actions = actions,
            onOpenClean = onOpenClean
        )

        UiStyle.MIUIX -> HomeScreenMiuix(
            state = state,
            scheduler = scheduler,
            actions = actions,
            onOpenClean = onOpenClean
        )
    }
}
