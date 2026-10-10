package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import io.github.xgl34222220.baize.ui.appearance.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsNavigationDraftProbeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val actions = DashboardActions(refresh = {}, clean = {}, organize = {}, scan = {}, apkScan = {},
        largeFiles = {}, duplicates = {}, storageAnalysis = {}, cleanScan = {}, dismissScan = {}, stop = {},
        deep = {}, corpses = {}, audit = {}, updateScheduler = {}, saveScheduler = {}, schedulerCommand = {},
        clearHistory = {}, clearRawLog = {}, reviewProtected = {}, whitelist = {}, resumableScan = {},
        theme = {}, reconnect = {}, resetScanPerformance = {}, crash = {})
    @Test fun leavingUnsavedDetailMustNotLeakChangesIntoOtherPageAutosave() {
        var scheduler by mutableStateOf(SchedulerUiState(chargingOnly = false))
        val saves = mutableListOf<SchedulerUiState>()
        compose.setContent { BaiZeMiuixApp(DashboardUiState(ready = true, automationAvailable = true), scheduler,
            actions.copy(updateScheduler = { scheduler = it }, saveScheduler = { saves += it }), AppearanceSettings(uiStyle = UiStyle.MIUIX)) }
        compose.onNodeWithText("清理", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("clean-scroll").performScrollToNode(hasText("执行条件与高级"))
        compose.onNodeWithText("执行条件与高级").performScrollTo().performClick()
        compose.onAllNodes(isToggleable())[1].performClick()
        compose.onAllNodes(isToggleable())[1].assertIsOn()
        assertTrue(saves.isEmpty())
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("清理", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("clean-scroll").performScrollToNode(hasContentDescription("自动清理"))
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onAllNodes(isToggleable())[0].performClick()
        assertEquals(1, saves.size)
        assertFalse("Unrelated auto-clean toggle must not save the abandoned charging-only draft", saves.single().chargingOnly)
    }
    @Test fun unsavedSettingsDraftMustSurviveRecreationAfterRuntimeRefresh() {
        var scheduler by mutableStateOf(SchedulerUiState(chargingOnly = false))
        val restore = StateRestorationTester(compose)
        restore.setContent { BaiZeMiuixApp(DashboardUiState(ready = true), scheduler,
            actions.copy(updateScheduler = { scheduler = it }), AppearanceSettings(uiStyle = UiStyle.MIUIX)) }
        compose.onNodeWithText("清理", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("clean-scroll").performScrollToNode(hasText("执行条件与高级"))
        compose.onNodeWithText("执行条件与高级").performScrollTo().performClick()
        compose.onAllNodes(isToggleable())[1].performClick()
        compose.onAllNodes(isToggleable())[1].assertIsOn()
        // The production dashboard periodically reloads the saved module configuration.
        compose.runOnIdle { scheduler = scheduler.copy(chargingOnly = false, runtimeReason = "synthetic runtime refresh") }
        compose.onAllNodes(isToggleable())[1].assertIsOn()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("清理执行条件").assertIsDisplayed()
        compose.onAllNodes(isToggleable())[1].assertIsOn()
    }
    @Test fun failedSaveKeepsDraftAndBackDiscardsWithoutGlobalMutation() {
        var scheduler by mutableStateOf(SchedulerUiState(chargingOnly = false))
        val saves = mutableListOf<SchedulerUiState>()
        var draftLeaks = 0
        compose.setContent { BaiZeMiuixApp(DashboardUiState(ready = true), scheduler,
            actions.copy(updateScheduler = { draftLeaks++; scheduler = it }, saveScheduler = { saves += it }),
            AppearanceSettings(uiStyle = UiStyle.MIUIX)) }
        compose.onNodeWithText("清理", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("clean-scroll").performScrollToNode(hasText("执行条件与高级"))
        compose.onNodeWithText("执行条件与高级").performScrollTo().performClick()
        compose.onAllNodes(isToggleable())[1].performClick()
        compose.onNodeWithText("保存").performClick()
        assertEquals(1, saves.size)
        assertTrue(saves.single().chargingOnly)
        assertEquals(0, draftLeaks)
        // A failed save has no authoritative config acknowledgement.
        compose.runOnIdle { scheduler = scheduler.copy(runtimeReason = "synthetic save rejected") }
        compose.onAllNodes(isToggleable())[1].assertIsOn()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("clean-scroll").performScrollToNode(hasText("执行条件与高级"))
        compose.onNodeWithText("执行条件与高级").performScrollTo().performClick()
        compose.onAllNodes(isToggleable())[1].assertIsOff()
        assertEquals(1, saves.size)
    }
    @Test fun materialAutomationFooterDocumentsDockOverlap() {
        compose.setContent { BaiZeMiuixApp(DashboardUiState(), SchedulerUiState(), actions, AppearanceSettings(uiStyle = UiStyle.MATERIAL)) }
        compose.onNodeWithText("清理", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("clean-scroll").performScrollToNode(hasText("执行条件与高级"))
        compose.onNodeWithText("执行条件与高级").performScrollTo().performClick()
        // 「执行条件与高级」是 LazyColumn，页脚说明需先滚动到可组合范围内。
        compose.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("修改后点右上角“保存”生效"))
        compose.onNodeWithText("修改后点右上角“保存”生效").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        File("build/reports/ui-screenshots/navigation-settings-detail-material-footer.png").apply { parentFile.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
