package io.github.xgl34222220.baize.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import org.json.JSONObject
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
import io.github.xgl34222220.baize.ui.settings.miuix.AutomationSettingsPage
import io.github.xgl34222220.baize.ui.settings.miuix.LuoShuSettingsHub
import io.github.xgl34222220.baize.ui.logs.LogsRoute

/**
 * 设置 Tab：连接与诊断、外观、规则与保护（CleanCenterActivity）、运行日志、卸载残留提醒。
 * 自动任务的设置已移到「清理 → 自动清理」，见 [AutomationSettingsRoute]。
 */
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
    val state = dashboard.toSettingsUiState(scheduler, appearance)
    val actions = SettingsUiActions(
        // 设置 Tab 不再编辑自动任务；这两个回调保持空实现，不会写入任何配置。
        onUpdateScheduler = {},
        onSaveScheduler = {},
        onSchedulerCommand = dashboardActions.schedulerCommand,
        onOpenAppearance = dashboardActions.theme,
        onOpenWhitelist = dashboardActions.whitelist,
        onOpenResumableScan = dashboardActions.resumableScan,
        onReconnect = dashboardActions.reconnect,
        onOpenAudit = onOpenDetails,
        onOpenCrashDiagnostics = dashboardActions.crash,
        onOpenRulesCenter = dashboardActions.audit
    )

    val skin = when (style) {
        UiStyle.MATERIAL -> VideoSkin.MATERIAL3
        UiStyle.MIUIX -> VideoSkin.MIUIX
    }
    ProvideVideoSkin(skin) {
        val runtimeLogs: @Composable (onBack: () -> Unit) -> Unit = { back ->
            LogsRoute(style, dashboard, dashboardActions, onOpenDetails = dashboardActions.cleanupAudit, onBack = back)
        }
        // 两种皮肤共用同一个设置中心（原 VideoSettingsScreenMiuix 只是转发包装，已删除）。
        LuoShuSettingsHub(state, actions, onDetailChanged, runtimeLogs)
    }
}

/**
 * 「清理 → 自动清理 → 执行条件与高级」子页（原 设置 → 自动任务设置，整页搬过来，不复制）。
 * 草稿机制不变：修改只写本地草稿，点「保存」才提交；返回即丢弃草稿；前台轮询只刷新运行状态。
 * [healthOnly] 时只显示「运行状况」弹窗（原「自动任务记录」）。
 */
@Composable
fun AutomationSettingsRoute(
    dashboard: DashboardUiState,
    scheduler: SchedulerUiState,
    appearance: AppearanceSettings,
    dashboardActions: DashboardActions,
    onBack: () -> Unit,
    healthOnly: Boolean = false
) {
    var showTaskHistory by rememberSaveable { mutableStateOf(healthOnly) }
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
        onOpenAudit = dashboardActions.cleanupAudit,
        onOpenCrashDiagnostics = dashboardActions.crash,
        onOpenTaskHistory = { showTaskHistory = true },
        onLoadWechatUsage = dashboardActions.wechatUsage
    )

    if (healthOnly) {
        if (showTaskHistory) SchedulerHealthDialog(state, actions) { showTaskHistory = false; onBack() }
        return
    }
    fun leave() {
        actions.onDiscardSchedulerDraft()
        onBack()
    }
    BackHandler { leave() }
    AutomationSettingsPage(state, actions, ::leave)
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
    maintenanceSummary = remote.maintenanceSummary,
    saving = remote.saving
)
