package io.github.xgl34222220.baize

import android.app.Application
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkbenchRefactorUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var state by mutableStateOf(WorkbenchUiState())
    private var scans = 0
    private var whitelistRequests = 0
    private fun item(id: String, risk: String, selectable: Boolean = true) = WorkbenchItem(id,
        "profile", "rules", "test.app", "测试应用", "日志", "app:test.app", "测试应用", id, risk,
        "/storage/emulated/0/test/$id", 100, 1, 0, "白名单保护", selectable)
    private fun render(history: Boolean = false, highOnly: Boolean = false) {
        state = WorkbenchUiState(profileConnected = true, cacheConnected = true, scanReady = !history,
            notice = if (history) WorkbenchNotice.WARNING else WorkbenchNotice.SUCCESS,
            phase = if (history) "已恢复上次结果，重新扫描后可清理" else "扫描完成",
            items = if (highOnly) listOf(item("offline", "high")) else listOf(item("low", "low"), item("medium", "medium"), item("high", "high"), item("blocked", "medium", false)),
            expiresAtRealtime = SystemClock.elapsedRealtime() + 1_800_000L)
        val appearance = AppearanceSettings(monetEnabled = false, blurEnabled = false)
        compose.setContent {
            BaiZeTheme(appearance) { CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
                ScanWorkbenchScreen(appearance, state, WorkbenchActions({}, { scans++ }, {}, {},
                    { id -> state = state.copy(selectedIds = state.selectedIds.toMutableSet().apply { if (!add(id)) remove(id) }) },
                    {}, { state = state.copy(selectedIds = reviewRiskSelection(state.items, setOf("low", "medium"))) },
                    { state = state.copy(selectedIds = emptySet()) }, {}, {},
                    { state = state.copy(selectedIds = reviewRiskSelection(state.items, setOf("medium"))) }, { whitelistRequests++ }))
            } }
        }
        compose.waitForIdle()
    }
    @Test fun historyExplainsWhyBatchSelectionIsDisabled() {
        render(history = true)
        compose.onNodeWithText("历史记录 · 需重新扫描").assertIsDisplayed()
        compose.onNodeWithText("有项目需要核对").assertDoesNotExist()
        compose.onNodeWithText("全选低、中风险").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("仅选中风险").assertIsNotEnabled()
        compose.onNodeWithText("重新扫描").performClick()
        assertEquals(1, scans)
    }
    @Test fun mediumSelectionExcludesBlockedAndHigh() {
        render()
        compose.onNodeWithText("仅选中风险").performScrollTo().performClick()
        assertEquals(setOf("medium"), state.selectedIds)
        compose.onNodeWithText("全选低、中风险").performClick()
        assertEquals(setOf("low", "medium"), state.selectedIds)
    }
    @Test fun highOnlyGroupShowsAManualSelectionEntry() {
        render(highOnly = true)
        // Grouping is intentionally calculated off the main thread. Wait for the actual
        // manual-selection entry instead of using the transient empty-state as a proxy.
        compose.waitUntil(10_000) {
            runCatching {
                compose.onNode(hasScrollAction()).performScrollToNode(hasText("逐项选择"))
                true
            }.getOrDefault(false)
        }
        compose.onNodeWithText("逐项选择").assertIsEnabled().performClick()
        compose.waitUntil(10_000) {
            runCatching {
                compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("选择offline"))
                true
            }.getOrDefault(false)
        }
        compose.onNodeWithContentDescription("选择offline").assertIsEnabled()
    }
}
