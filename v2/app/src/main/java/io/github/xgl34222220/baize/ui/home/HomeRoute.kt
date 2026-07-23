package io.github.xgl34222220.baize.ui.home

import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.ui.appearance.UiStyle

/**
 * Automatic product mode uses one status-only dashboard for both visual styles.
 * Manual scan/clean/stop/package shortcuts are deliberately not rendered.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun HomeRoute(
    style: UiStyle,
    state: DashboardUiState,
    actions: DashboardActions,
    onOpenClean: () -> Unit
) {
    AutomaticHomeScreen(state)
}
