package io.github.xgl34222220.baize.ui.settings.miuix

import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.ui.settings.SettingsUiActions
import io.github.xgl34222220.baize.ui.settings.SettingsUiState

@Composable
fun VideoSettingsScreenMiuix(state: SettingsUiState, actions: SettingsUiActions, onDetailChanged: (Boolean) -> Unit = {}) =
    LuoShuSettingsHub(state, actions, onDetailChanged)
