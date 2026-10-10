package io.github.xgl34222220.baize.ui.home

import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.home.miuix.LuoShuHomeScreen
import io.github.xgl34222220.baize.ui.miuix.ProvideVideoSkin
import io.github.xgl34222220.baize.ui.miuix.VideoSkin

@Composable
fun HomeRoute(
    style: UiStyle,
    state: DashboardUiState,
    scheduler: SchedulerUiState,
    actions: DashboardActions,
    onOpenClean: () -> Unit,
    onOpenPlan: () -> Unit = onOpenClean
) {
    val skin = when (style) {
        UiStyle.MATERIAL -> VideoSkin.MATERIAL3
        UiStyle.MIUIX -> VideoSkin.MIUIX
    }
    // 两种皮肤共用同一个首页；差异只来自 VideoSkin（原 VideoHomeScreenMiuix 只是转发包装，已删除）。
    ProvideVideoSkin(skin) {
        LuoShuHomeScreen(state, scheduler, actions, onOpenClean, onOpenPlan)
    }
}
