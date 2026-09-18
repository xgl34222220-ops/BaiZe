package io.github.xgl34222220.baize

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import io.github.xgl34222220.baize.ui.appearance.AppearanceRoute
import io.github.xgl34222220.baize.ui.appearance.AppearanceUiActions
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

class ThemeSettingsActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            val settings = appearanceViewModel.settings.collectAsStateWithLifecycle().value
            val systemDark = isSystemInDarkTheme()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }

            BaiZeTheme(settings) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BaiZeTokens.colors.surfaceBase
                ) {
                    CompositionLocalProvider(LocalAppearanceSettings provides settings) {
                        AppearanceRoute(
                            settings = settings,
                            actions = AppearanceUiActions(
                                onBack = ::finish,
                                onUiStyle = appearanceViewModel::setUiStyle,
                                onThemeMode = appearanceViewModel::setThemeMode,
                                onSeedArgb = appearanceViewModel::setSeedArgb,
                                onKolorStyle = appearanceViewModel::setKolorStyle,
                                onMonetEnabled = appearanceViewModel::setMonetEnabled,
                                onAmoledBlack = appearanceViewModel::setAmoledBlack,
                                onGlassEnabled = appearanceViewModel::setGlassEnabled,
                                onBlurEnabled = appearanceViewModel::setBlurEnabled,
                                onFloatingDock = appearanceViewModel::setFloatingDock,
                                onRefreshRateMode = appearanceViewModel::setRefreshRateMode,
                                onAdaptiveSmoothMode = appearanceViewModel::setAdaptiveSmoothMode
                            )
                        )
                    }
                }
            }
        }
    }
}