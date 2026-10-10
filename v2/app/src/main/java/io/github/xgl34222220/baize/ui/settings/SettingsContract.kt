package io.github.xgl34222220.baize.ui.settings

import androidx.compose.runtime.Immutable
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings

@Immutable
data class SettingsUiState(
    val appearance: AppearanceSettings,
    val scheduler: SchedulerUiState,
    val whitelistCount: Int,
    val connected: Boolean,
    val ready: Boolean,
    val running: Boolean,
    val serviceText: String,
    val schedulerText: String,
    val versionDetails: String = "",
    val connecting: Boolean = false,
    val connectionFailed: Boolean = false,
    val connectionLabel: String = if (running) "执行中" else if (ready) "已就绪" else if (connected) "未就绪" else "未连接"
) {
    val appearanceSummary: String
        get() = buildList {
            add(appearance.uiStyle.label)
            add(appearance.themeMode.label)
            add(if (appearance.monetEnabled) "Monet" else appearance.accent.label)
            add(appearance.kolorStyle.label)
            if (appearance.amoledBlack) add("AMOLED")
            if (appearance.glassEnabled) add("玻璃")
            if (appearance.blurEnabled && appearance.glassEnabled) add("模糊")
            add(if (appearance.floatingDock) "悬浮底栏" else "贴底底栏")
        }.joinToString(" · ")

    val serviceHealthy: Boolean
        get() = connected && ready && !running && !connectionFailed
}

data class SettingsUiActions(
    val onUpdateScheduler: (SchedulerUiState) -> Unit,
    val onSaveScheduler: (SchedulerUiState) -> Unit,
    val onSchedulerCommand: (String) -> Unit,
    val onOpenAppearance: () -> Unit,
    val onOpenWhitelist: () -> Unit,
    /** 打开断点续清工作台：消费已持久化的扫描快照，中断后可继续。 */
    val onOpenResumableScan: () -> Unit,
    val onReconnect: () -> Unit,
    val onOpenAudit: () -> Unit,
    val onOpenCrashDiagnostics: () -> Unit,
    val onDiscardSchedulerDraft: () -> Unit = {},
    val onOpenTaskHistory: () -> Unit = {},
    /** 只读统计微信存储构成，结果回调在主线程。 */
    val onLoadWechatUsage: ((io.github.xgl34222220.baize.WechatUsage) -> Unit) -> Unit = {},
    /** 设置 →「规则与保护」中心（CleanCenterActivity）：保护名单、清理策略、规则版本与试跑等的唯一入口。 */
    val onOpenRulesCenter: () -> Unit = {},
    /** 清除扫描 worker 的自适应性能基准（Root 已有实现，之前没有入口）。 */
    val onResetScanPerformance: () -> Unit = {}
)

fun DashboardUiState.toSettingsUiState(
    scheduler: SchedulerUiState,
    appearance: AppearanceSettings
): SettingsUiState = SettingsUiState(
    appearance = appearance,
    scheduler = scheduler,
    whitelistCount = whitelistCount,
    connected = connected,
    ready = ready,
    running = running,
    serviceText = serviceText,
    schedulerText = schedulerText,
    connectionLabel = connectionLabel,
    connecting = connecting,
    connectionFailed = connectionFailed,
    versionDetails = versionDetails
)