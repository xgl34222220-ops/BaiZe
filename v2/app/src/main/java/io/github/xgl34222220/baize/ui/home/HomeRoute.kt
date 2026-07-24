package io.github.xgl34222220.baize.ui.home

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.ScanWorkbenchActivity
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
    val context = LocalContext.current
    val workbenchActions = actions.copy(
        clean = { context.startActivity(Intent(context, ScanWorkbenchActivity::class.java)) }
    )
    when (style) {
        UiStyle.MATERIAL -> HomeScreenMaterial(state, scheduler, workbenchActions, onOpenClean)
        UiStyle.MIUIX -> HomeScreenMiuix(state, scheduler, workbenchActions, onOpenClean)
    }
}
