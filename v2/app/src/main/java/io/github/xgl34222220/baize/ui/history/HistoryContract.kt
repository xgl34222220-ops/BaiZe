package io.github.xgl34222220.baize.ui.history

import androidx.compose.runtime.Immutable
import io.github.xgl34222220.baize.AppJunkUiItem
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.GeneralJunkUiItem
import io.github.xgl34222220.baize.HistoryUiItem
import io.github.xgl34222220.baize.ProtectedUiItem

@Immutable
data class HistoryUiState(
    val latestResult: String,
    val lastTaskTime: String,
    val lifetimeRuns: Long,
    val lifetimeReleased: Long,
    val lifetimeFiles: Long,
    val lifetimeEmptyFiles: Long,
    val lifetimeEmptyDirs: Long,
    val lifetimeFragments: Long,
    val lifetimeElapsed: Long,
    val recentApps: List<AppJunkUiItem>,
    val recentJunk: List<GeneralJunkUiItem>,
    val protectedItems: List<ProtectedUiItem>,
    val records: List<HistoryUiItem>
) {
    val currentDirectoryCount: Int
        get() = records.firstOrNull()?.takeIf { it.result == latestResult }?.emptyDirs?.coerceAtLeast(0) ?: 0

    val hasCurrentResult: Boolean
        get() = currentDirectoryCount > 0 || recentApps.any { it.bytes > 0L } || recentJunk.any { it.bytes > 0L }

    val currentCountDescription: String
        get() = if (currentDirectoryCount > 0) "$currentItemCount 个文件 · $currentDirectoryCount 个目录" else "处理 $currentItemCount 项"

    val currentItemCount: Long
        get() = if (currentDirectoryCount > 0) records.first().files.coerceAtLeast(0).toLong()
        else recentApps.filter { it.bytes > 0L }.sumOf { it.files } +
            recentJunk.filter { it.bytes > 0L }.sumOf { it.files }

    val currentBytes: Long
        get() = recentApps.sumOf { it.bytes.coerceAtLeast(0L) } +
            recentJunk.sumOf { it.bytes.coerceAtLeast(0L) }
}

data class HistoryUiActions(
    val onRefresh: () -> Unit,
    val onClearHistory: () -> Unit,
    val onReviewProtected: () -> Unit
)

fun DashboardUiState.toHistoryUiState(): HistoryUiState = HistoryUiState(
    latestResult = history.firstOrNull()?.result.orEmpty(),
    lastTaskTime = lastTaskTime.ifBlank { history.firstOrNull()?.time.orEmpty() },
    lifetimeRuns = lifetimeRuns,
    lifetimeReleased = lifetimeReleased,
    lifetimeFiles = lifetimeFiles,
    lifetimeEmptyFiles = lifetimeEmptyFiles,
    lifetimeEmptyDirs = lifetimeEmptyDirs,
    lifetimeFragments = lifetimeFragments,
    lifetimeElapsed = lifetimeElapsed,
    recentApps = recentApps,
    recentJunk = recentJunk,
    protectedItems = protectedItems,
    records = history
)
