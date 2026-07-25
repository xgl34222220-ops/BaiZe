package io.github.xgl34222220.baize.ui.clean

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.xgl34222220.baize.ApkScanActivity
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.InstantCacheActivity
import io.github.xgl34222220.baize.FileOrganizerActivity
import io.github.xgl34222220.baize.ScanWorkbenchActivity
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.clean.material.CleanScreenMaterial
import io.github.xgl34222220.baize.ui.clean.miuix.CleanScreenMiuix

@Composable
fun CleanRoute(
    style: UiStyle,
    dashboard: DashboardUiState,
    scheduler: SchedulerUiState,
    dashboardActions: DashboardActions,
    expandedCategory: String,
    onExpandedCategoryChanged: (String) -> Unit
) {
    val context = LocalContext.current
    val state = scheduler.toCleanUiState(
        engineReady = dashboard.ready,
        running = dashboard.running,
        scanSnapshotReady = dashboard.scanCompleted,
        serviceText = dashboard.serviceText
    )

    fun applyAndSave(next: SchedulerUiState) {
        dashboardActions.updateScheduler(next)
        dashboardActions.saveScheduler(next)
    }

    val actions = CleanUiActions(
        onAutomaticCleaningChanged = { enabled ->
            applyAndSave(scheduler.withAutomaticCleaning(enabled))
        },
        onCategoryEnabledChanged = { id, enabled ->
            applyAndSave(scheduler.withCategoryEnabled(id, enabled))
        },
        onCategoryIntervalChanged = { id, minutes ->
            applyAndSave(scheduler.withCategoryInterval(id, minutes))
        },
        onDailyScheduleChanged = { enabled ->
            applyAndSave(scheduler.withDailySchedule(enabled))
        },
        onDailyTimeChanged = { hour, minute ->
            applyAndSave(scheduler.withDailyTime(hour, minute))
        },
        onDailyGraceChanged = { minutes ->
            applyAndSave(scheduler.withDailyGrace(minutes))
        },
        onApkPackagesChanged = { enabled ->
            applyAndSave(scheduler.copy(apkPackagesEnabled = enabled))
        },
        onSave = { dashboardActions.saveScheduler(scheduler) },
        onScan = { context.startActivity(Intent(context, ScanWorkbenchActivity::class.java)) },
        onApkScan = { context.startActivity(Intent(context, ApkScanActivity::class.java)) },
        onInstantCache = { context.startActivity(Intent(context, InstantCacheActivity::class.java)) },
        onFileOrganizer = { context.startActivity(Intent(context, FileOrganizerActivity::class.java)) },
        onDeepClean = dashboardActions.deep,
        onCorpses = dashboardActions.corpses,
        onAudit = dashboardActions.audit
    )

    when (style) {
        UiStyle.MATERIAL -> CleanScreenMaterial(
            state = state,
            actions = actions,
            expandedCategory = expandedCategory,
            onExpandedCategoryChanged = onExpandedCategoryChanged
        )
        UiStyle.MIUIX -> CleanScreenMiuix(
            state = state,
            actions = actions,
            expandedCategory = expandedCategory,
            onExpandedCategoryChanged = onExpandedCategoryChanged
        )
    }
}
