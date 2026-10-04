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
import io.github.xgl34222220.baize.ui.history.miuix.VideoHistoryScreenMiuix
import io.github.xgl34222220.baize.ui.history.material.HistoryScreenMaterial
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

    @Test fun videoUnknownCapacityDoesNotRenderAsMeasuredZero() = videoCapacity("unknown", "无法测量")
    @Test fun videoRetainedCapacityDoesNotRenderAsReleasedBytes() = videoCapacity("retained", "尚未释放")
    @Test fun videoVerifiedZeroRemainsZero() = videoCapacity("measured", "0 B")

    private fun videoCapacity(release: String, expected: String) {
        val record = HistoryUiItem("容量状态夹具", "2026-10-03 12:00:00", "手动", "待核对", 0, 0, 0, 0, true, releaseState = release)
        val state = HistoryUiState("待核对", "2026-10-03 12:00:00", 1, 0, 0, 0, 0, 0, 1,
            emptyList(), emptyList(), emptyList(), listOf(record))
        val appearance = AppearanceSettings(monetEnabled = false, blurEnabled = false)
        compose.setContent { BaiZeTheme(appearance) { CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
            VideoHistoryScreenMiuix(state, HistoryUiActions({}, {}, {}))
        } } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("容量状态夹具"))
        compose.onAllNodesWithText(expected).onLast().assertIsDisplayed()
    }

    @Test fun directoryOnlyMiuixHistoryDoesNotClaimNothingWasCleaned() = directoryHistory(0)
    @Test fun directoryOnlyMaterialHistoryDoesNotClaimNothingWasCleaned() = directoryHistory(1)
    @Test fun directoryOnlyVideoHistoryDoesNotTurnDirectoriesIntoFiles() = directoryHistory(2)
    @Test fun zeroByteFilesAndDirectoriesKeepSeparateActualHistoryCounts() = directoryHistory(0, files = 2)

    private fun directoryHistory(style: Int, files: Int = 0) {
        val state = HistoryUiState("空目录清理完成", "2026-10-01 12:00:00", 1, 0, 0, 0, 3, 0, 1,
            emptyList(), emptyList(), emptyList(), listOf(HistoryUiItem("空目录清理", "2026-10-01 12:00:00",
                "手动", "空目录清理完成", 0, files, 3, 0, true)))
        val appearance = AppearanceSettings(monetEnabled = false, blurEnabled = false)
        val actions = HistoryUiActions(onRefresh = {}, onClearHistory = {}, onReviewProtected = {})
        compose.setContent {
            BaiZeTheme(appearance) { CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
                when (style) {
                    0 -> HistoryScreenMiuix(state, actions)
                    1 -> HistoryScreenMaterial(state, actions)
                    else -> VideoHistoryScreenMiuix(state, actions)
                }
            } }
        }
        compose.onAllNodesWithText("$files 个文件 · 3 个目录", substring = true).onFirst().assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("空目录清理"))
        compose.onAllNodesWithText("$files 个文件 · 3 个目录").onLast().assertIsDisplayed()
        compose.onAllNodesWithText("未发现可清理内容").assertCountEquals(0)
        compose.onAllNodesWithText("暂无最近结果").assertCountEquals(0)
    }

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
        compose.onNodeWithText("无占用应用").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("支付宝").assertIsDisplayed()
    }
}
