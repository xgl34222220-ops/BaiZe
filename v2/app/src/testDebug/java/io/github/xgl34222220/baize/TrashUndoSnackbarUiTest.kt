package io.github.xgl34222220.baize

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 移入回收站后弹出带「撤销」的 Snackbar；点撤销只触发一次本批恢复，批次清空后 Snackbar 收起。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrashUndoSnackbarUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun awaitText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun storageTrashShowsUndoSnackbarAndRestoresOnce() {
        var state by mutableStateOf(StorageToolsUiState(mode = StorageToolMode.LARGE, status = "已移入回收站 2 个文件",
            lastTrashed = listOf("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222"),
            lastTrashedBytes = 0L))
        var undos = 0
        compose.setContent { BaiZeTheme(AppearanceSettings()) {
            StorageToolsScreen(state, {}, {}, {}, {}, {}, onUndo = { undos++; state = state.copy(lastTrashed = emptyList()) })
        } }
        awaitText("已移入回收站 2 个文件，尚未释放空间")
        compose.onNodeWithText(TrashUndo.ACTION_LABEL).assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(1, undos)
        compose.onNodeWithText(TrashUndo.ACTION_LABEL).assertDoesNotExist()
    }

    @Test fun clearedBatchDismissesSnackbarWithoutUndo() {
        var state by mutableStateOf(StorageToolsUiState(mode = StorageToolMode.LARGE,
            lastTrashed = listOf("33333333-3333-3333-3333-333333333333")))
        var undos = 0
        compose.setContent { BaiZeTheme(AppearanceSettings()) {
            StorageToolsScreen(state, {}, {}, {}, {}, {}, onUndo = { undos++ })
        } }
        awaitText("已移入回收站 1 个文件")
        // 重新扫描等操作清空批次：旧 Snackbar 收起，不会对过期批次执行撤销。
        compose.runOnIdle { state = state.copy(lastTrashed = emptyList()) }
        compose.waitForIdle()
        compose.onNodeWithText(TrashUndo.ACTION_LABEL).assertDoesNotExist()
        assertEquals(0, undos)
    }

    @Test fun swipeReviewBatchOffersUndoThroughSnackbar() {
        val entry = TrashEntry("44444444-4444-4444-4444-444444444444", "/storage/emulated/0/DCIM/Camera/IMG_1.jpg",
            "/storage/emulated/0/.baize-file-trash/x/44444444-4444-4444-4444-444444444444", 2048, "a".repeat(64), 1L, 2L)
        var state by mutableStateOf(SwipeReviewUiState(lastBatch = listOf(entry)))
        var undos = 0
        compose.setContent { BaiZeTheme(AppearanceSettings()) {
            SwipeReviewScreen(state, SwipeReviewActions(onUndoBatch = { undos++; state = state.copy(lastBatch = emptyList()) }))
        } }
        awaitText("已移入回收站 1 个文件")
        compose.onNodeWithText(TrashUndo.ACTION_LABEL).performClick()
        compose.waitForIdle()
        assertEquals(1, undos)
        compose.onNodeWithText(TrashUndo.ACTION_LABEL).assertDoesNotExist()
    }
}
