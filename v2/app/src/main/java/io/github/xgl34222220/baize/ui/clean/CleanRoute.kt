package io.github.xgl34222220.baize.ui.clean

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.xgl34222220.baize.ApkScanActivity
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.InstantCacheActivity
import io.github.xgl34222220.baize.FileOrganizerActivity
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.SmartScanActivity
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.clean.material.CleanScreenMaterial
import io.github.xgl34222220.baize.ui.clean.miuix.CleanScreenMiuix

@Composable
fun CleanRoute(
    style: UiStyle,
    dashboard: DashboardUiState,
    scheduler: SchedulerUiState,
    dashboardActions: DashboardActions
) {
    val context = LocalContext.current
    val state = scheduler.toCleanUiState(
        engineReady = dashboard.ready,
        running = dashboard.running,
        scanSnapshotReady = dashboard.scanCompleted,
        serviceText = dashboard.serviceText
    )

    val actions = CleanUiActions(
        onAutomaticCleaningChanged = { enabled ->
            dashboardActions.updateScheduler(scheduler.withAutomaticCleaning(enabled))
        },
        onCategoryEnabledChanged = { id, enabled ->
            dashboardActions.updateScheduler(scheduler.withCategoryEnabled(id, enabled))
        },
        onCategoryIntervalChanged = { id, hours ->
            dashboardActions.updateScheduler(scheduler.withCategoryInterval(id, hours))
        },
        onDailyScheduleChanged = { enabled ->
            dashboardActions.updateScheduler(scheduler.withDailySchedule(enabled))
        },
        onDailyTimeChanged = { hour, minute ->
            dashboardActions.updateScheduler(scheduler.withDailyTime(hour, minute))
        },
        onDailyGraceChanged = { minutes ->
            dashboardActions.updateScheduler(scheduler.withDailyGrace(minutes))
        },
        onApkPackagesChanged = { enabled ->
            dashboardActions.updateScheduler(scheduler.copy(apkPackagesEnabled = enabled))
        },
        onSave = { dashboardActions.saveScheduler(scheduler) },
        onScan = { context.startActivity(Intent(context, SmartScanActivity::class.java)) },
        onApkScan = { context.startActivity(Intent(context, ApkScanActivity::class.java)) },
        onInstantCache = { context.startActivity(Intent(context, InstantCacheActivity::class.java)) },
        onFileOrganizer = { context.startActivity(Intent(context, FileOrganizerActivity::class.java)) },
        onDeepClean = dashboardActions.deep,
        onCorpses = dashboardActions.corpses,
        onAudit = dashboardActions.audit
    )

    when (style) {
        UiStyle.MATERIAL -> CleanScreenMaterial(state, actions)
        UiStyle.MIUIX -> CleanScreenMiuix(state, actions)
    }
}
