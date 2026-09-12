package io.github.xgl34222220.baize.ui.appearance.material

import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.AppearanceUiActions
import io.github.xgl34222220.baize.ui.appearance.miuix.AppearanceScreenMiuix
import io.github.xgl34222220.baize.ui.miuix.ProvideVideoSkin
import io.github.xgl34222220.baize.ui.miuix.VideoSkin

/** One responsive appearance flow; Material controls and surfaces follow the selected skin. */
@Composable
fun AppearanceScreenMaterial(settings: AppearanceSettings, actions: AppearanceUiActions) {
    ProvideVideoSkin(VideoSkin.MATERIAL3) { AppearanceScreenMiuix(settings, actions) }
}
