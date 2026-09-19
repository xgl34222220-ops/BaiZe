package io.github.xgl34222220.baize.ui.home.miuix

import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState

/** Theme variants share navigation, so removed duplicate entries cannot reappear. */
@Composable
fun VideoHomeScreenMiuix(state: DashboardUiState, scheduler: SchedulerUiState, actions: DashboardActions,
    onOpenClean: () -> Unit, onOpenPlan: () -> Unit = onOpenClean) =
    LuoShuHomeScreen(state, scheduler, actions, onOpenClean, onOpenPlan)
