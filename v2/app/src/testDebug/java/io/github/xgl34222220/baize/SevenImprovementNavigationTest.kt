package io.github.xgl34222220.baize

import android.app.Application
import androidx.activity.ComponentActivity
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

    @Test fun homeExposesPhotoDuplicatesAndTrashDirectly() {
        val opened = mutableListOf<String>()
        compose.setContent {
            BaiZeMiuixApp(DashboardUiState(), SchedulerUiState(), actions.copy(
                photoCompression = { opened += "photo" }, duplicates = { opened += "duplicates" },
                fileTrash = { opened += "trash" }), AppearanceSettings(uiStyle = UiStyle.MIUIX))
        }
        compose.onNodeWithText("照片瘦身").performScrollTo().performClick()
        compose.onNodeWithText("重复文件").performScrollTo().performClick()
        compose.onNodeWithText("回收站").performScrollTo().performClick()
        assertEquals(listOf("photo", "duplicates", "trash"), opened)
    }

    private fun verifyRoutes(style: UiStyle) {
        compose.setContent {
            BaiZeMiuixApp(DashboardUiState(), SchedulerUiState(runLedger = listOf("synthetic-ledger: 等待充电")),
                actions, AppearanceSettings(uiStyle = style))
        }
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.onNodeWithText("自动任务记录").performScrollTo().performClick()
        compose.onNodeWithText("下次检查").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("synthetic-ledger: 等待充电").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        repeat(3) { compose.onNodeWithText("规则版本与试跑").performScrollTo().performClick() }
        assertEquals(RuleBundleActivity::class.java.name,
            shadowOf(compose.activity).nextStartedActivity.component?.className)
        assertNull(shadowOf(compose.activity).nextStartedActivity)
    }
}
