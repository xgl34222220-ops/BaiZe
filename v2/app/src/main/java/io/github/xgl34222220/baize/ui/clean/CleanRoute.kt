package io.github.xgl34222220.baize.ui.clean

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.xgl34222220.baize.CleanerNavigation
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.clean.miuix.CleanScreenMiuix
import io.github.xgl34222220.baize.ui.miuix.ProvideVideoSkin
import io.github.xgl34222220.baize.ui.miuix.VideoSkin
import io.github.xgl34222220.baize.ui.settings.AutomationSettingsRoute
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

private const val CLEAN_DETAIL_AUTOMATION = "automation"
private const val CLEAN_DETAIL_HEALTH = "health"

@Composable
fun CleanRoute(
    style: UiStyle,
    dashboard: DashboardUiState,
    scheduler: SchedulerUiState,
    dashboardActions: DashboardActions,
    expandedCategory: String,
    onExpandedCategoryChanged: (String) -> Unit,
    onDetailChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    // 「自动清理」的唯一位置：列表页（自动保存）+「执行条件与高级」子页（草稿 + 保存）+「运行状况」弹窗。
    var detail by rememberSaveable { mutableStateOf("") }
    val notify by rememberUpdatedState(onDetailChanged)
    LaunchedEffect(detail) { notify(detail == CLEAN_DETAIL_AUTOMATION) }
    DisposableEffect(Unit) { onDispose { notify(false) } }

    val state = scheduler.toCleanUiState(
        engineReady = dashboard.ready,
        running = dashboard.running,
        scanSnapshotReady = dashboard.scanCompleted,
        serviceText = dashboard.serviceText,
        automationAvailable = dashboard.automationAvailable,
        automationText = dashboard.automationText
    )

    fun applyAndSave(next: SchedulerUiState) {
        if (scheduler.saving) return
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
        onScheduleModeChanged = { mode ->
            applyAndSave(scheduler.withScheduleMode(mode))
        },
        onDailyTimeChanged = { hour, minute ->
            applyAndSave(scheduler.withDailyTime(hour, minute))
        },
        onDailyGraceChanged = { minutes ->
            applyAndSave(scheduler.withDailyGrace(minutes))
        },
        onScan = dashboardActions.scan,
        onDeepClean = dashboardActions.deep,
        onLargeFiles = dashboardActions.largeFiles,
        onDuplicates = dashboardActions.duplicates,
        onStorageView = dashboardActions.storageView,
        onApkScan = dashboardActions.apkScan,
        onCorpses = dashboardActions.corpses,
        onFileOrganizer = dashboardActions.organize,
        onPhotoCompression = dashboardActions.photoCompression,
        onSwipeReview = dashboardActions.swipeReview,
        onShizukuCache = { CleanerNavigation.openFrom(context, Intent(context, io.github.xgl34222220.baize.ShizukuCacheActivity::class.java)) },
        onApkPackageDaysChanged = { days ->
            applyAndSave(scheduler.withApkPackageDays(days))
        },
        onOpenAutomationSettings = { detail = CLEAN_DETAIL_AUTOMATION },
        onOpenSchedulerHealth = { detail = CLEAN_DETAIL_HEALTH }
    )

    val appearance = LocalAppearanceSettings.current
    val listState = rememberSaveableStateHolder()
    // 两种皮肤共用同一个清理页（原 VideoCleanScreenMiuix 只是转发包装，已删除）。
    ProvideVideoSkin(if (style == UiStyle.MIUIX) VideoSkin.MIUIX else VideoSkin.MATERIAL3) {
        AnimatedContent(
            targetState = detail == CLEAN_DETAIL_AUTOMATION,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                io.github.xgl34222220.baize.ui.theme.BaiZeMotionSpecs.detailTransition(forward = targetState)
            },
            label = "cleanDetail"
        ) { showAutomation ->
            if (showAutomation) {
                listState.SaveableStateProvider("automation") {
                    Surface(Modifier.fillMaxSize(), color = BaiZeTokens.colors.surfaceBase) {
                        AutomationSettingsRoute(
                            dashboard = dashboard,
                            scheduler = scheduler,
                            appearance = appearance,
                            dashboardActions = dashboardActions,
                            onBack = { detail = "" }
                        )
                    }
                }
            } else {
                listState.SaveableStateProvider("clean-list") {
                    CleanScreenMiuix(state, actions, expandedCategory, onExpandedCategoryChanged)
                    if (detail == CLEAN_DETAIL_HEALTH) {
                        AutomationSettingsRoute(
                            dashboard = dashboard,
                            scheduler = scheduler,
                            appearance = appearance,
                            dashboardActions = dashboardActions,
                            onBack = { detail = "" },
                            healthOnly = true
                        )
                    }
                }
            }
        }
    }
}
