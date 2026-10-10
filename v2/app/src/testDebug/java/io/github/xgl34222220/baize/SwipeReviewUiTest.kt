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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SwipeReviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val items = listOf(
        SwipeItem("/storage/emulated/0/DCIM/Camera/IMG_1.jpg", "IMG_1.jpg", 2048, 1_700_000_000_000),
        SwipeItem("/storage/emulated/0/DCIM/Camera/IMG_2.jpg", "IMG_2.jpg", 4096, 1_600_000_000_000))

    @Test fun buttonsDecideUndoAndOnlyQueueDeletionsUntilConfirmed() {
        var state by mutableStateOf(SwipeReviewUiState(session = SwipeReviewSession(items)))
        var applyRequests = 0
        compose.setContent { BaiZeTheme(AppearanceSettings()) { SwipeReviewScreen(state, SwipeReviewActions(
            onDecide = { state = state.copy(session = state.session.decide(it)) },
            onUndo = { state = state.copy(session = state.session.undo()) },
            onApply = { applyRequests++ })) } }
        compose.onNodeWithText("IMG_1.jpg").assertIsDisplayed()
        compose.onNodeWithText("删除").performScrollTo().performClick()
        compose.onNodeWithText("IMG_2.jpg").assertIsDisplayed()
        compose.onNodeWithText("待删除 1 项").assertIsDisplayed()
        compose.onNodeWithText("撤销").performScrollTo().performClick()
        compose.onNodeWithText("IMG_1.jpg").assertIsDisplayed()
        compose.onNodeWithText("待删除 1 项").assertDoesNotExist()
        compose.onNodeWithText("保留").performScrollTo().performClick()
        compose.onNodeWithText("删除").performScrollTo().performClick()
        compose.onNodeWithText("这个文件夹已看完").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("移入回收站").performClick()
        assertEquals(1, applyRequests)
        assertEquals(listOf("IMG_2.jpg"), state.session.deletions.map { it.name })
    }

    @Test fun swipingLeftPastTheThresholdMarksTheCardForDeletion() {
        var state by mutableStateOf(SwipeReviewUiState(session = SwipeReviewSession(items)))
        compose.setContent { BaiZeTheme(AppearanceSettings()) { SwipeReviewScreen(state, SwipeReviewActions(
            onDecide = { state = state.copy(session = state.session.decide(it)) })) } }
        compose.onNodeWithTag("swipe-card").performTouchInput { swipeLeft(startX = right - 4f, endX = left - width * .6f) }
        compose.waitForIdle()
        assertEquals(listOf(SwipeDecision.DELETE), state.session.decisions)
    }

    @Test fun confirmationExplainsTheTrashAndPermissionStateOffersAccess() {
        var confirmed = 0
        val session = SwipeReviewSession(items).decide(SwipeDecision.DELETE)
        compose.setContent { BaiZeTheme(AppearanceSettings()) { SwipeReviewScreen(
            SwipeReviewUiState(session = session, confirmApply = true), SwipeReviewActions(onConfirmApply = { confirmed++ })) } }
        compose.onNodeWithText("移入回收站？").assertIsDisplayed()
        compose.onNode(hasText("移入回收站") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, confirmed)
    }

    @Test fun missingStoragePermissionShowsGrantButtonInsteadOfCards() {
        compose.setContent { BaiZeTheme(AppearanceSettings()) { SwipeReviewScreen(
            SwipeReviewUiState(permissionRequired = true, status = "需要权限"), SwipeReviewActions()) } }
        compose.onNodeWithText("开启${SharedStorageAccess.label}").assertIsDisplayed()
        compose.onNodeWithTag("swipe-card").assertDoesNotExist()
    }

    @Test fun seenFilesCanBeResetFromThePage() {
        var resets = 0
        compose.setContent { BaiZeTheme(AppearanceSettings()) { SwipeReviewScreen(
            SwipeReviewUiState(session = SwipeReviewSession(items), seenCount = 3, status = "共 2 个文件，已跳过 3 个看过的文件"),
            SwipeReviewActions(onResetSeen = { resets++ })) } }
        compose.onNodeWithText("重置「已看过」（3）").performScrollTo().performClick()
        assertEquals(1, resets)
    }

    @Test fun resetIsHiddenWhenNothingWasSeen() {
        compose.setContent { BaiZeTheme(AppearanceSettings()) { SwipeReviewScreen(
            SwipeReviewUiState(session = SwipeReviewSession(items)), SwipeReviewActions()) } }
        compose.onNodeWithTag("swipe-reset-seen").assertDoesNotExist()
    }
}
