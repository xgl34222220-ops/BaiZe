package io.github.xgl34222220.baize.ui.history

import androidx.compose.runtime.Immutable
import io.github.xgl34222220.baize.AppJunkUiItem
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.GeneralJunkUiItem
import io.github.xgl34222220.baize.HistoryUiItem

@Immutable
data class HistoryUiState(
    val latestResult: String,
    val lifetimeRuns: Long,
    val lifetimeReleased: Long,
    val lifetimeFiles: Long,
    val lifetimeEmptyFiles: Long,
    val lifetimeEmptyDirs: Long,
    val lifetimeFragments: Long,
    val lifetimeElapsed: Long,
    val recentApps: List<AppJunkUiItem>,
    val recentJunk: List<GeneralJunkUiItem>,
    val records: List<HistoryUiItem>
) {
    val hasCurrentResult: Boolean
        get() = recentApps.isNotEmpty() || recentJunk.isNotEmpty()

    val currentItemCount: Long
        get() = recentApps.sumOf { it.files } + recentJunk.sumOf { it.files }

    val currentBytes: Long
        get() = recentApps.sumOf { it.bytes } + recentJunk.sumOf { it.bytes }
}

data class HistoryUiActions(
    val onRefresh: () -> Unit,
    val onClearHistory: () -> Unit
)

fun DashboardUiState.toHistoryUiState(): HistoryUiState = HistoryUiState(
    latestResult = history.firstOrNull()?.result.orEmpty(),
    lifetimeRuns = lifetimeRuns,
    lifetimeReleased = lifetimeReleased,
    lifetimeFiles = lifetimeFiles,
    lifetimeEmptyFiles = lifetimeEmptyFiles,
    lifetimeEmptyDirs = lifetimeEmptyDirs,
    lifetimeFragments = lifetimeFragments,
    lifetimeElapsed = lifetimeElapsed,
    recentApps = recentApps,
    recentJunk = recentJunk,
    records = history
)
