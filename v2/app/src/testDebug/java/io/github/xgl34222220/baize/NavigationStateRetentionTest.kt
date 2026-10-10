package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationStateRetentionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val actions = DashboardActions(refresh = {}, clean = {}, organize = {}, scan = {}, apkScan = {},
        largeFiles = {}, duplicates = {}, storageAnalysis = {}, cleanScan = {}, dismissScan = {}, stop = {},
        deep = {}, corpses = {}, audit = {}, saveScheduler = {}, schedulerCommand = {},
        clearHistory = {}, clearRawLog = {}, reviewProtected = {}, whitelist = {}, resumableScan = {},
        theme = {}, reconnect = {}, resetScanPerformance = {}, crash = {})

    @Test fun miuixBottomTabsRetainScrollPosition() = retainTabPosition(UiStyle.MIUIX)
    @Test fun materialBottomTabsRetainScrollPosition() = retainTabPosition(UiStyle.MATERIAL)
    private fun retainTabPosition(style: UiStyle) {
        // 去重后设置页变短，改用最长的清理 Tab 验证切换 Tab 不重置滚动位置。
        compose.setContent { BaiZeMiuixApp(DashboardUiState(), SchedulerUiState(), actions,
            AppearanceSettings(uiStyle = style), initialPage = 1) }
        scrollTo("运行状况").assertIsDisplayed()
        val before = scrollPosition()
        assertTrue(before > 0f)
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("清理", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals("Switching tabs must not restart the clean list", before, scrollPosition(), 1f)
        compose.onNodeWithText("运行状况").assertIsDisplayed()
    }

    @Test fun miuixDetailBackRetainsHubPosition() = retainHubPosition(UiStyle.MIUIX)
    @Test fun materialDetailBackRetainsHubPosition() = retainHubPosition(UiStyle.MATERIAL)
    private fun retainHubPosition(style: UiStyle) {
        compose.setContent { BaiZeMiuixApp(DashboardUiState(), SchedulerUiState(), actions,
            AppearanceSettings(uiStyle = style), initialPage = 1) }
        scrollTo("运行状况")
        scrollTo("执行条件与高级")
        val before = scrollPosition()
        assertTrue(before > 0f)
        compose.onNodeWithText("执行条件与高级").performClick()
        compose.onNodeWithText("清理执行条件").assertIsDisplayed()
        back()
        assertEquals("Back must return to the same clean-list position", before, scrollPosition(), 1f)
        compose.onNodeWithText("执行条件与高级").assertIsDisplayed()
    }

    @Test fun numericDraftSurvivesRecreationAndCancelStillDiscardsIt() {
        val saves = mutableListOf<SchedulerUiState>()
        val restore = StateRestorationTester(compose)
        restore.setContent { BaiZeMiuixApp(DashboardUiState(), SchedulerUiState(minBattery = 20),
            actions.copy(saveScheduler = { saves += it }), AppearanceSettings(), initialPage = 1) }
        scrollTo("执行条件与高级").performClick()
        scrollTo("最低执行电量").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("75")
        restore.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextContains("75")
        compose.onNodeWithText("取消").performClick()
        scrollTo("最低执行电量").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("20")
        compose.onNodeWithText("取消").performClick()
        back()
        assertTrue(saves.isEmpty())
    }

    @Test fun miuixDailyTimeDialogAndTypedValuesSurviveRecreation() = retainDailyTime(UiStyle.MIUIX)
    @Test fun materialDailyTimeDialogAndTypedValuesSurviveRecreation() = retainDailyTime(UiStyle.MATERIAL)
    private fun retainDailyTime(style: UiStyle) {
        val savedTimes = mutableListOf<SchedulerUiState>()
        val restore = StateRestorationTester(compose)
        restore.setContent { BaiZeMiuixApp(DashboardUiState(ready = true, automationAvailable = true),
            SchedulerUiState(scheduleMode = 2, dailyEnabled = true, dailyHour = 3, dailyMinute = 15),
            actions.copy(saveScheduler = { savedTimes += it }), AppearanceSettings(uiStyle = style), initialPage = 1) }
        // 专项清理列表位于自动清理之前，总开关卡片初始不在 LazyColumn 的组合范围内。
        compose.onNodeWithTag("clean-scroll").performScrollToNode(hasContentDescription("展开自动清理设置"))
        compose.onNodeWithContentDescription("展开自动清理设置").performScrollTo().performClick()
        scrollTo("执行时间").performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("21")
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("42")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("设置每日执行时间").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction())[0].assertTextContains("21")
        compose.onAllNodes(hasSetTextAction())[1].assertTextContains("42")
        compose.onNodeWithText("确定").performClick()
        assertEquals(1, savedTimes.size)
        assertEquals(21, savedTimes.single().dailyHour)
        assertEquals(42, savedTimes.single().dailyMinute)
    }

    @Test fun miuixLogsHaveLiveDataOneLevelBackAndNoDuplicateAudit() = verifyLogs(UiStyle.MIUIX)
    @Test fun materialLogsHaveLiveDataOneLevelBackAndNoDuplicateAudit() = verifyLogs(UiStyle.MATERIAL)
    private fun verifyLogs(style: UiStyle) {
        var dashboard by mutableStateOf(DashboardUiState(rawLogName = "synthetic.log", rawLog = "synthetic first line",
            history = listOf(HistoryUiItem("合成清理记录", "2026-10-03 16:30", "手动", "合成测试结果", 10, 1, 0, 0, true))))
        var clears = 0
        val restore = StateRestorationTester(compose)
        restore.setContent { BaiZeMiuixApp(dashboard, SchedulerUiState(),
            actions.copy(clearRawLog = { clears++ }), AppearanceSettings(uiStyle = style), initialPage = 3) }
        scrollTo("清理有据，保留有度")
        scrollTo("运行日志").performClick()
        assertNull("Inline logs must not create another Activity", shadowOf(compose.activity).nextStartedActivity)
        compose.onNodeWithText("合成清理记录").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("原始输出").performScrollTo().performClick()
        compose.onNodeWithText("synthetic first line").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { dashboard = dashboard.copy(rawLog = "synthetic refreshed line") }
        compose.onNodeWithText("synthetic refreshed line").assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("synthetic refreshed line").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("首页", useUnmergedTree = true).assertDoesNotExist()
        save("logs-${style.name.lowercase()}")
        compose.onNodeWithContentDescription("清空原始输出").performScrollTo().performClick()
        assertEquals(1, clears)
        // 去重：运行日志不再有「诊断与恢复/清理明细」；清理审计唯一入口在 记录 Tab。
        compose.onNodeWithText("清理明细").assertDoesNotExist()
        compose.onNodeWithText("诊断与恢复").assertDoesNotExist()
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        back()
        compose.onNodeWithText("管理与维护").performScrollTo().assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
        compose.onNodeWithText("首页", useUnmergedTree = true).assertExists()
    }

    private fun scrollPosition(): Float {
        compose.waitForIdle()
        return compose.onNode(verticalList()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    }
    private fun verticalList() = hasScrollAction() and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    private fun scrollTo(text: String): SemanticsNodeInteraction {
        compose.onNode(verticalList()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text).performScrollTo()
    }
    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        File("build/reports/ui-screenshots/navigation-state-$name.png").apply { parentFile.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
