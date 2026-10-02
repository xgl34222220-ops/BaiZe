package io.github.xgl34222220.baize.ui.settings

import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import org.json.JSONObject
import io.github.xgl34222220.baize.RuleBundleActivity
import io.github.xgl34222220.baize.AuditActivity
import io.github.xgl34222220.baize.CleanerNavigation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.miuix.ProvideVideoSkin
import io.github.xgl34222220.baize.ui.miuix.VideoSkin
import io.github.xgl34222220.baize.ui.settings.miuix.VideoSettingsScreenMiuix
import io.github.xgl34222220.baize.ui.settings.miuix.LuoShuSettingsHub

@Composable
fun SettingsRoute(
    style: UiStyle,
    dashboard: DashboardUiState,
    scheduler: SchedulerUiState,
    appearance: AppearanceSettings,
    dashboardActions: DashboardActions,
    onDetailChanged: (Boolean) -> Unit = {},
    onOpenDetails: () -> Unit
) {
    val context = LocalContext.current
    var showTaskHistory by rememberSaveable { mutableStateOf(false) }
    val draftSaver = remember { Saver<SchedulerUiState, String>(
        save = { it.toJson().toString() }, restore = { SchedulerUiState.fromJson(JSONObject(it)) }) }
    var draft by rememberSaveable(stateSaver = draftSaver) { mutableStateOf(scheduler.copy(saving = false)) }
    var dirty by rememberSaveable { mutableStateOf(false) }
    var saveRequested by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(scheduler) {
        when {
            !dirty -> {
                draft = scheduler.copy(saving = false)
            }

            saveRequested && !scheduler.saving && scheduler.hasSameEditableConfig(draft) -> {
                draft = scheduler.copy(saving = false)
                dirty = false
                saveRequested = false
            }

            else -> {
                draft = draft.withRuntimeFrom(scheduler).copy(saving = false)
            }
        }
    }

    val visibleScheduler = draft.withRuntimeFrom(scheduler)
    val state = dashboard.toSettingsUiState(visibleScheduler, appearance)
    val actions = SettingsUiActions(
        onUpdateScheduler = { updated ->
            draft = updated.copy(saving = false)
            dirty = true
            saveRequested = false
        },
        onSaveScheduler = { requested ->
            val cleanDraft = requested.copy(saving = false)
            draft = cleanDraft
            dirty = true
            saveRequested = true
            dashboardActions.saveScheduler(cleanDraft)
        },
        onDiscardSchedulerDraft = {
            draft = scheduler.copy(saving = false)
            dirty = false
            saveRequested = false
        },
        onSchedulerCommand = dashboardActions.schedulerCommand,
        onOpenAppearance = dashboardActions.theme,
        onOpenWhitelist = dashboardActions.whitelist,
        onOpenResumableScan = dashboardActions.resumableScan,
        onReconnect = dashboardActions.reconnect,
        onOpenAudit = onOpenDetails,
        onOpenCrashDiagnostics = dashboardActions.crash,
        onOpenTaskHistory = { showTaskHistory = true },
        onOpenCleanupAudit = { CleanerNavigation.openFrom(context, Intent(context, AuditActivity::class.java)) },
        onOpenRuleVersions = { CleanerNavigation.openFrom(context, Intent(context, RuleBundleActivity::class.java)) }
    )

    val skin = when (style) {
        UiStyle.MATERIAL -> VideoSkin.MATERIAL3
        UiStyle.MIUIX -> VideoSkin.MIUIX
    }
    ProvideVideoSkin(skin) {
        if (showTaskHistory) SchedulerHealthDialog(state, actions) { showTaskHistory = false }
        if (style == UiStyle.MIUIX) LuoShuSettingsHub(state, actions, onDetailChanged)
        else VideoSettingsScreenMiuix(state, actions, onDetailChanged)
    }
}

private fun SchedulerUiState.hasSameEditableConfig(other: SchedulerUiState): Boolean =
    toJson().toString() == other.toJson().toString()

/**
 * 前台轮询只负责刷新调度器运行状态；尚未保存的设置草稿必须继续留在界面中。
 */
private fun SchedulerUiState.withRuntimeFrom(remote: SchedulerUiState): SchedulerUiState = copy(
    runtimeState = remote.runtimeState,
    runtimeReason = remote.runtimeReason,
    queueCount = remote.queueCount,
    queueGroups = remote.queueGroups,
    nextTask = remote.nextTask,
    blockedGroups = remote.blockedGroups,
    nextCheckEpoch = remote.nextCheckEpoch,
    runLedger = remote.runLedger,
    runtimeGroup = remote.runtimeGroup,
    cacheNextEpoch = remote.cacheNextEpoch,
    apkNextEpoch = remote.apkNextEpoch,
    emptyNextEpoch = remote.emptyNextEpoch,
    rulesNextEpoch = remote.rulesNextEpoch,
    fragmentNextEpoch = remote.fragmentNextEpoch,
    deepNextEpoch = remote.deepNextEpoch,
    organizeNextEpoch = remote.organizeNextEpoch,
    supervisorStatus = remote.supervisorStatus,
    supervisorHeartbeatAge = remote.supervisorHeartbeatAge,
    runtimeStale = remote.runtimeStale,
    saving = remote.saving
)
