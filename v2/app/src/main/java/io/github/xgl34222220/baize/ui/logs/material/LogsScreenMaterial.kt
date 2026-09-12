package io.github.xgl34222220.baize.ui.logs.material

import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.ui.logs.LogsUiActions
import io.github.xgl34222220.baize.ui.logs.LogsUiState
import io.github.xgl34222220.baize.ui.logs.miuix.LogsScreenMiuix
import io.github.xgl34222220.baize.ui.miuix.ProvideVideoSkin
import io.github.xgl34222220.baize.ui.miuix.VideoSkin

@Composable
fun LogsScreenMaterial(state: LogsUiState, actions: LogsUiActions) {
    ProvideVideoSkin(VideoSkin.MATERIAL3) { LogsScreenMiuix(state, actions) }
}
