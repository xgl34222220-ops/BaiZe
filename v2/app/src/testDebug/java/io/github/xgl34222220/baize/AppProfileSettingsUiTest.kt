package io.github.xgl34222220.baize

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.xgl34222220.baize.ui.appearance.*
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
class AppProfileSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val actions = DashboardActions(refresh = {}, clean = {}, organize = {}, scan = {}, apkScan = {},
        largeFiles = {}, duplicates = {}, storageAnalysis = {}, cleanScan = {}, dismissScan = {}, stop = {},
        deep = {}, corpses = {}, audit = {}, updateScheduler = {}, saveScheduler = {}, schedulerCommand = {},
        clearHistory = {}, clearRawLog = {}, reviewProtected = {}, whitelist = {}, resumableScan = {},
        theme = {}, reconnect = {}, resetScanPerformance = {}, crash = {})

    private fun openTaskSettings(saves: MutableList<SchedulerUiState>, initial: SchedulerUiState) {
        var scheduler by mutableStateOf(initial)
        compose.setContent { BaiZeMiuixApp(DashboardUiState(ready = true), scheduler,
            actions.copy(updateScheduler = { scheduler = it }, saveScheduler = { saves += it }),
            AppearanceSettings(uiStyle = UiStyle.MIUIX)) }
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("自动任务设置"))
        compose.onNodeWithText("自动任务设置").performScrollTo().performClick()
    }

    private fun save() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("保存"))
        compose.onNodeWithText("保存").performClick()
    }

    @Test fun enhancedTierAndChatMediaNeedExplicitConfirmation() {
        val saves = mutableListOf<SchedulerUiState>()
        openTaskSettings(saves, SchedulerUiState())
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("清理档位"))
        compose.onNodeWithText("同时清理聊天媒体").assertDoesNotExist()
        compose.onNodeWithText("清理档位").performClick()
        compose.onNodeWithText("增强").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("同时清理聊天媒体"))
        compose.onNodeWithContentDescription("同时清理聊天媒体").performClick()
        // Dismissing the warning keeps media cleanup off.
        compose.onNodeWithText("清理聊天媒体？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        save()
        assertEquals(2, saves.last().appProfileTier)
        assertFalse(saves.last().appProfileUserMedia)
        assertEquals(0, saves.last().toJson().getInt("app_profile_user_media"))

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("同时清理聊天媒体"))
        compose.onNodeWithContentDescription("同时清理聊天媒体").performClick()
        compose.onNodeWithText("开启").performClick()
        save()
        assertTrue(saves.last().appProfileUserMedia)
        assertEquals(1, saves.last().toJson().getInt("app_profile_user_media"))
    }

    @Test fun maintenanceToggleAndLastResultAreShown() {
        val saves = mutableListOf<SchedulerUiState>()
        openTaskSettings(saves, SchedulerUiState(maintenanceSummary = "10/10/26 3:00 AM · 已完成"))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("充电息屏时整理存储"))
        compose.onNodeWithText("最近一次：10/10/26 3:00 AM · 已完成").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("充电息屏时整理存储").performClick()
        save()
        assertFalse(saves.last().maintenanceEnabled)
        assertEquals(0, saves.last().toJson().getInt("maintenance_enabled"))
    }
}
