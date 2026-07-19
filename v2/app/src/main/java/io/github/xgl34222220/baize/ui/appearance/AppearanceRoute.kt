package io.github.xgl34222220.baize.ui.appearance

import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.ui.appearance.material.AppearanceScreenMaterial
import io.github.xgl34222220.baize.ui.appearance.miuix.AppearanceScreenMiuix

data class AppearanceUiActions(
    val onBack: () -> Unit,
    val onUiStyle: (UiStyle) -> Unit,
    val onThemeMode: (ThemeMode) -> Unit,
    val onSeedArgb: (Int) -> Unit,
    val onKolorStyle: (KolorStyle) -> Unit,
    val onMonetEnabled: (Boolean) -> Unit,
    val onAmoledBlack: (Boolean) -> Unit,
    val onGlassEnabled: (Boolean) -> Unit,
    val onBlurEnabled: (Boolean) -> Unit,
    val onFloatingDock: (Boolean) -> Unit
)

@Composable
fun AppearanceRoute(
    settings: AppearanceSettings,
    actions: AppearanceUiActions
) {
    when (settings.uiStyle) {
        UiStyle.MATERIAL -> AppearanceScreenMaterial(settings, actions)
        UiStyle.MIUIX -> AppearanceScreenMiuix(settings, actions)
    }
}