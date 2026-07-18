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
    val schedulerText: String
) {
    val appearanceSummary: String
        get() = buildList {
            add(appearance.uiStyle.label)
            add(appearance.themeMode.label)
            add(appearance.kolorStyle.label)
            if (appearance.amoledBlack) add("AMOLED")
            if (appearance.glassEnabled) add("玻璃")
            if (appearance.blurEnabled) add("模糊")
        }.joinToString(" · ")

    val serviceHealthy: Boolean
        get() = connected && ready && !running
}

data class SettingsUiActions(
    val onUpdateScheduler: (SchedulerUiState) -> Unit,
    val onSaveScheduler: (SchedulerUiState) -> Unit,
    val onOpenAppearance: () -> Unit,
    val onOpenWhitelist: () -> Unit,
    val onReconnect: () -> Unit,
    val onOpenAudit: () -> Unit,
    val onOpenCrashDiagnostics: () -> Unit
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
    schedulerText = schedulerText
)
