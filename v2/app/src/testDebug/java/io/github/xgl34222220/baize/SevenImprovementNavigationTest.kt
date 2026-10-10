package io.github.xgl34222220.baize

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SevenImprovementNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val actions = DashboardActions(refresh = {}, clean = {}, organize = {}, scan = {}, apkScan = {},
        largeFiles = {}, duplicates = {}, storageAnalysis = {}, cleanScan = {}, dismissScan = {}, stop = {},
        deep = {}, corpses = {}, audit = {}, updateScheduler = {}, saveScheduler = {}, schedulerCommand = {},
        clearHistory = {}, clearRawLog = {}, reviewProtected = {}, whitelist = {}, resumableScan = {},
        theme = {}, reconnect = {}, resetScanPerformance = {}, crash = {})

    @Test fun launchedMiuixHomeReachesActualTaskLedgerAndRules() = verifyRoutes(UiStyle.MIUIX)
    @Test fun launchedMaterialHomeReachesActualTaskLedgerAndRules() = verifyRoutes(UiStyle.MATERIAL)

    @Test fun cleanTabExposesEachSpecialToolOnce() {
        val opened = mutableListOf<String>()
        compose.setContent {
            BaiZeMiuixApp(DashboardUiState(), SchedulerUiState(), actions.copy(
                photoCompression = { opened += "photo" }, duplicates = { opened += "duplicates" },
                swipeReview = { opened += "swipe" }), AppearanceSettings(uiStyle = UiStyle.MIUIX), initialPage = 1)
        }
        for (title in listOf("照片瘦身", "重复文件", "滑动整理")) {
            compose.onNodeWithTag("clean-scroll").performScrollToNode(hasText(title))
            compose.onAllNodesWithText(title).assertCountEquals(1)
            // 行可能停在浮动 Dock 下方；直接触发该行的点击语义，验证入口接线而不是坐标命中。
            compose.onNodeWithText(title).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        }
        compose.onNodeWithText("一键扫描").assertDoesNotExist()
        assertEquals(listOf("photo", "duplicates", "swipe"), opened)
    }

    private fun verifyRoutes(style: UiStyle) {
        var rulesCenter = 0
        compose.setContent {
            BaiZeMiuixApp(DashboardUiState(), SchedulerUiState(runLedger = listOf("synthetic-ledger: 等待充电")),
                actions.copy(audit = { rulesCenter++ }), AppearanceSettings(uiStyle = style))
        }
        // 原 设置 →「自动任务记录」→ 清理 → 自动清理 →「运行状况」。
        compose.onNodeWithText("清理", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("clean-scroll").performScrollToNode(hasText("运行状况"))
        compose.onNodeWithText("运行状况").performScrollTo().performClick()
        compose.onNodeWithText("下次检查").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("synthetic-ledger: 等待充电").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        // 规则版本与试跑并入 设置 →「规则与保护」中心（CleanCenterActivity，打开去重由 CleanerNavigation 负责）。
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.onNodeWithText("规则与保护").performScrollTo().performClick()
        assertEquals(1, rulesCenter)
        compose.onNodeWithText("规则版本与试跑").assertDoesNotExist()
        compose.onNodeWithText("自动任务记录").assertDoesNotExist()
        assertNull(shadowOf(compose.activity).nextStartedActivity)
    }
}
