package io.github.xgl34222220.baize

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.xgl34222220.baize.root.ReleaseAmount
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReleaseAmountUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun show(state: WorkbenchUiState) {
        compose.setContent { BaiZeTheme(AppearanceSettings(monetEnabled = false, blurEnabled = false)) {
            WorkbenchCompletionCard(state) {}
        } }
    }
    @Test fun missingCapacityShowsUnknownInsteadOfAZeroMetric() {
        show(WorkbenchUiState(cleanupCompleted = true, cleanedBytesKnown = false, cleanedFiles = 1))
        compose.onNodeWithText("无法测量").assertIsDisplayed()
        compose.onAllNodesWithText("0 B").assertCountEquals(0)
        compose.onAllNodesWithText("本次实际释放").assertCountEquals(0)
        compose.onNodeWithText("部分清理结果未返回容量，未计入确认删除量。请查看处理详情。").assertIsDisplayed()
    }
    @Test fun measuredEmptyFilesExplainTheirTrueZeroContentSize() {
        show(WorkbenchUiState(cleanupCompleted = true, cleanedBytesKnown = true, cleanedFiles = 3, cleanedBytes = 0))
        compose.onNodeWithText("本次确认删除容量").assertIsDisplayed()
        compose.onNodeWithText("本次确认删除的文件内容为 0 B；保护、跳过和失败不算已释放。").assertIsDisplayed()
        compose.onAllNodesWithText("无法测量").assertCountEquals(0)
    }
    @Test fun directoryOnlyCleanupHasAnIndependentCount() {
        show(WorkbenchUiState(cleanupCompleted = true, cleanedBytesKnown = true, cleanedDirectories = 4))
        compose.onNodeWithText("本次移除空目录").assertIsDisplayed()
        compose.onNodeWithText("4 个").assertIsDisplayed()
        compose.onAllNodesWithText("本次确认删除容量").assertCountEquals(0)
    }
    @Test fun retainedAmountAndUnknownHistoryHaveExplicitDescriptions() {
        assertTrue(ReleaseAmount(ReleaseAmount.State.RETAINED, retainedBytes = 4096).description { "$it B" }.contains("尚未释放"))
        val record = HistoryUiItem("清理", "today", "test", "request succeeded", 0, 1, 0, 0, true, releaseState = "unknown")
        assertEquals("无法测量", record.capacityText { "$it B" })
        assertEquals("0 B", record.copy(releaseState = "measured").capacityText { "$it B" })
    }
}
