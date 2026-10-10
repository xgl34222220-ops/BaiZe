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
    val records: List<HistoryUiItem>,
    /** History record id of the run that produced [recentApps]/[recentJunk]; blank for module/legacy lists. */
    val recentRecordId: String = "",
    /** True only when the recent lists carry confirmed deleted bytes (never scan estimates). */
    val recentDeletedEvidence: Boolean = false
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

    /**
     * Deleted bytes confirmed by the per-app/per-category result of the same run as the latest record.
     * Zero unless that result is linked to the record by id and was reported as deleted content.
     */
    val confirmedCurrentBytes: Long
        get() {
            val record = records.firstOrNull() ?: return 0L
            if (!recentDeletedEvidence || recentRecordId.isBlank() || record.recordId != recentRecordId) return 0L
            return currentBytes
        }

    fun currentCapacityText(format: (Long) -> String): String {
        val record = records.firstOrNull()?.takeIf { it.result == latestResult } ?: return "无法测量"
        // The run total was not measured, but this run's own deletion evidence was: show it as a floor.
        if (record.releaseState == "unknown" && confirmedCurrentBytes > 0L) {
            return "已确认 ${format(confirmedCurrentBytes)} · 部分无法测量"
        }
        return record.capacityText(format)
    }
}

data class HistoryUiActions(
    val onRefresh: () -> Unit,
    val onClearHistory: () -> Unit,
    val onReviewProtected: () -> Unit,
    /** 回收站（FileTrashActivity，页内可切换到隔离区）。记录 Tab 是它的固定入口。 */
    val onOpenTrash: () -> Unit = {},
    /** 清理审计与规则质量（AuditActivity）。唯一入口；不在 HistoryRoute 里直接构造 Intent。 */
    val onOpenAudit: () -> Unit = {}
)

fun DashboardUiState.toHistoryUiState(): HistoryUiState {
    // A result linked to an older run than the latest record is not "this run"; never show it as such.
    val latestRecordId = history.firstOrNull()?.recordId
    val recentIsStale = recentRecordId.isNotBlank() && latestRecordId != null && latestRecordId != recentRecordId
    return historyUiState(
        apps = if (recentIsStale) emptyList() else recentApps,
        junk = if (recentIsStale) emptyList() else recentJunk,
        evidence = recentDeletedEvidence && !recentIsStale
    )
}

private fun DashboardUiState.historyUiState(
    apps: List<AppJunkUiItem>,
    junk: List<GeneralJunkUiItem>,
    evidence: Boolean
): HistoryUiState = HistoryUiState(
    latestResult = history.firstOrNull()?.result.orEmpty(),
    lastTaskTime = lastTaskTime.ifBlank { history.firstOrNull()?.time.orEmpty() },
    lifetimeRuns = lifetimeRuns,
    lifetimeReleased = lifetimeReleased,
    lifetimeFiles = lifetimeFiles,
    lifetimeEmptyFiles = lifetimeEmptyFiles,
    lifetimeEmptyDirs = lifetimeEmptyDirs,
    lifetimeFragments = lifetimeFragments,
    lifetimeElapsed = lifetimeElapsed,
    recentApps = apps,
    recentJunk = junk,
    protectedItems = protectedItems,
    records = history,
    recentRecordId = recentRecordId,
    recentDeletedEvidence = evidence
)
