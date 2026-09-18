package io.github.xgl34222220.baize

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoryZeroByteUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun zeroByteAppIsCollapsedEvenWhenItHasFiles() {
        val state = HistoryUiState(
            latestResult = "扫描完成",
            lastTaskTime = "今天 10:20",
            lifetimeRuns = 2,
            lifetimeReleased = 64L * 1024 * 1024,
            lifetimeFiles = 6,
            lifetimeEmptyFiles = 0,
            lifetimeEmptyDirs = 0,
            lifetimeFragments = 0,
            lifetimeElapsed = 9,
            recentApps = listOf(
                AppJunkUiItem("com.example.real", "示例应用", "缓存", 4, 64L * 1024 * 1024),
                AppJunkUiItem("com.eg.android.AlipayGphone", "支付宝", "扫描条目", 2, 0L)
            ),
            recentJunk = emptyList(),
            protectedItems = emptyList(),
            records = emptyList()
        )
        val appearance = AppearanceSettings(monetEnabled = false, blurEnabled = false)
        compose.setContent {
            BaiZeTheme(appearance) {
                CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
                    HistoryScreenMiuix(
                        state,
                        HistoryUiActions(onRefresh = {}, onClearHistory = {}, onReviewProtected = {})
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("示例应用").assertIsDisplayed()
        compose.onNodeWithText("支付宝").assertDoesNotExist()
        compose.onNodeWithText("无占用应用").assertIsDisplayed().performClick()
        compose.onNodeWithText("支付宝").assertIsDisplayed()
    }
}
