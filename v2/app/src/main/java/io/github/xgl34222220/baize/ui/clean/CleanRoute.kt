package io.github.xgl34222220.baize.ui.clean

import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState
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
        onApkPackagesChanged = { enabled ->
            dashboardActions.updateScheduler(scheduler.copy(apkPackagesEnabled = enabled))
        },
        onSave = { dashboardActions.saveScheduler(scheduler) },
        onScan = dashboardActions.scan,
        onApkScan = dashboardActions.apkScan,
        onDeepClean = dashboardActions.deep,
        onCorpses = dashboardActions.corpses,
        onAudit = dashboardActions.audit
    )

    when (style) {
        UiStyle.MATERIAL -> CleanScreenMaterial(state, actions)
        UiStyle.MIUIX -> CleanScreenMiuix(state, actions)
    }
}
