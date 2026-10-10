package io.github.xgl34222220.baize

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.history.HistoryUiActions
import io.github.xgl34222220.baize.ui.history.HistoryUiState
import io.github.xgl34222220.baize.ui.history.miuix.HistoryScreenMiuix
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** lifetimeElapsed is seconds; both theme variants must show the same duration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoryLifetimeElapsedUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val state = HistoryUiState("", "", 3, 0, 12, 0, 0, 0, 3_600,
        emptyList(), emptyList(), emptyList(), emptyList())
    private val appearance = AppearanceSettings(monetEnabled = false, blurEnabled = false)

    @Test fun miuixHistoryShowsTheSameHour() {
        compose.setContent { BaiZeTheme(appearance) { CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
            HistoryScreenMiuix(state, HistoryUiActions({}, {}, {}))
        } } }
        compose.onNodeWithText("1 小时").assertIsDisplayed()
    }
}
