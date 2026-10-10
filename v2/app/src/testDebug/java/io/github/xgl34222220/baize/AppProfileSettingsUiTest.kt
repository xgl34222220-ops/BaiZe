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

    private fun openTaskSettings(
        saves: MutableList<SchedulerUiState>,
        initial: SchedulerUiState,
        wechatUsage: ((WechatUsage) -> Unit) -> Unit = {}
    ) {
        var scheduler by mutableStateOf(initial)
        compose.setContent { BaiZeMiuixApp(DashboardUiState(ready = true), scheduler,
            actions.copy(updateScheduler = { scheduler = it }, saveScheduler = { saves += it }, wechatUsage = wechatUsage),
            AppearanceSettings(uiStyle = UiStyle.MIUIX)) }
        // 去重后：原 设置 →「自动任务设置」整页搬到 清理 → 自动清理 →「执行条件与高级」。
        compose.onNodeWithText("清理", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("clean-scroll").performScrollToNode(hasText("执行条件与高级"))
        compose.onNodeWithText("执行条件与高级").performScrollTo().performClick()
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

    @Test fun wechatUsageBreakdownIsShownAndMediaDaysAreSelectable() {
        val saves = mutableListOf<SchedulerUiState>()
        var loads = 0
        openTaskSettings(saves, SchedulerUiState(appProfileTier = 2)) { done ->
            loads++
            done(WechatUsage.parse("image|聊天图片|5368709120|media\nsns|朋友圈缓存|1048576|conservative\n" +
                "received|收到的文件（不清理）|2048|protected\ntotal|微信总占用|6442450944|total\naccounts|1\n"))
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("微信占用分析"))
        compose.onNodeWithText("微信占用分析").performClick()
        compose.onNodeWithText("微信存储构成").assertIsDisplayed()
        compose.onNodeWithText("共 6.0 GB · 1 个账号").assertIsDisplayed()
        compose.onNodeWithText("聊天图片").assertIsDisplayed()
        compose.onNodeWithText("5.0 GB").assertIsDisplayed()
        compose.onNodeWithText("需增强档并开启聊天媒体").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        assertEquals(1, loads)
        assertTrue("the breakdown never saves settings", saves.isEmpty())

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("聊天媒体保留天数"))
        compose.onNodeWithText("聊天媒体保留天数").performClick()
        compose.onNodeWithText("90 天前").performClick()
        save()
        assertEquals(90, saves.last().appProfileMediaDays)
        assertEquals(90, saves.last().toJson().getInt("app_profile_media_days"))
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

    @Test fun rootTidyRunsInsideSystemMaintenanceAndDefaultsOff() {
        val saves = mutableListOf<SchedulerUiState>()
        openTaskSettings(saves, SchedulerUiState())
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("根目录自动整理"))
        compose.onNodeWithText("系统维护").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("根目录自动整理").performClick()
        save()
        assertEquals(1, saves.last().toJson().getInt("root_tidy_auto"))
        assertEquals(0, SchedulerUiState().toJson().getInt("root_tidy_auto"))
    }

    @Test fun categoryCleanupLivesInAdvancedSettingsAndDefaultsOff() {
        val saves = mutableListOf<SchedulerUiState>()
        openTaskSettings(saves, SchedulerUiState())
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("logcat 日志缓冲区"))
        compose.onNodeWithText("高级设置 · 分类定时清理").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("微信缓存").performScrollTo().performClick()
        save()
        val json = saves.last().toJson()
        assertEquals(1, json.getInt("maint_clean_wechat"))
        assertEquals(0, json.getInt("maint_clean_qq"))
        assertEquals(0, json.getInt("maint_clean_shortvideo"))
        assertEquals(0, json.getInt("maint_clean_logcat"))
    }
}
