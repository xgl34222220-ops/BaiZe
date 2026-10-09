package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import io.github.xgl34222220.baize.ui.appearance.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
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
class NavigationAuditProbeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val actions = DashboardActions(refresh = {}, clean = {}, organize = {}, scan = {}, apkScan = {},
        largeFiles = {}, duplicates = {}, storageAnalysis = {}, cleanScan = {}, dismissScan = {}, stop = {},
        deep = {}, corpses = {}, audit = {}, updateScheduler = {}, saveScheduler = {}, schedulerCommand = {},
        clearHistory = {}, clearRawLog = {}, reviewProtected = {}, whitelist = {}, resumableScan = {},
        theme = {}, reconnect = {}, resetScanPerformance = {}, crash = {})
    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        File("build/reports/ui-screenshots/navigation-$name.png").apply { parentFile.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun intentCount(): Int {
        var count = 0
        while (shadowOf(compose.activity).nextStartedActivity != null) count++
        return count
    }
    @Test
    @Config(qualifiers = "zh-rCN-w852dp-h393dp-land-mdpi")
    fun landscapeDarkMiuixDetailsBackAndCancel() = landscape(UiStyle.MIUIX)
    @Test
    @Config(qualifiers = "zh-rCN-w852dp-h393dp-land-mdpi")
    fun landscapeDarkMaterialDetailsBackAndCancel() = landscape(UiStyle.MATERIAL)
    private fun landscape(style: UiStyle) {
        compose.setContent { BaiZeMiuixApp(DashboardUiState(), SchedulerUiState(), actions, AppearanceSettings(uiStyle = style, themeMode = ThemeMode.DARK)) }
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("自动任务设置"))
        compose.onNodeWithText("自动任务设置").performScrollTo().performClick()
        compose.onNodeWithText("最低执行电量").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        save("settings-detail-landscape-dark-${style.name.lowercase()}")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("管理与维护"))
        compose.onNodeWithText("管理与维护").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("首页", useUnmergedTree = true).assertExists()
    }
    @Test fun storageTrashRepeatedClicksMustOpenOneActivity() {
        compose.setContent { BaiZeTheme(AppearanceSettings()) {
            StorageToolsScreen(StorageToolsUiState(), {}, {}, {}, {}, {})
        } }
        repeat(3) { compose.onNodeWithText("回收站").performClick() }
        save("storage-trash-entry")
        assertEquals("3 taps before lifecycle changes must create one trash Activity", 1, intentCount())
    }
    @Test fun analysisPhotoRepeatedClicksMustOpenOneActivity() {
        compose.setContent { BaiZeTheme(AppearanceSettings()) {
            StorageToolsScreen(StorageToolsUiState(mode = StorageToolMode.ANALYSIS), {}, {}, {}, {}, {})
        } }
        repeat(3) { compose.onNodeWithText("打开照片瘦身").performScrollTo().performClick() }
        save("analysis-photo-entry")
        assertEquals("3 taps before lifecycle changes must create one photo Activity", 1, intentCount())
    }
    @Test fun apkTrashRepeatedClicksMustOpenOneActivity() {
        compose.setContent { BaiZeTheme(AppearanceSettings()) {
            ApkScanScreen(ApkScanUiState(), {}, {}, {}, {}, {})
        } }
        repeat(3) { compose.onNodeWithText("回收站").performClick() }
        save("apk-trash-entry")
        assertEquals("3 taps before lifecycle changes must create one trash Activity", 1, intentCount())
    }
    @Test fun materialSettingsDetailMustHideDock() = settingsDetail(UiStyle.MATERIAL)
    @Test fun miuixSettingsDetailMustHideDock() = settingsDetail(UiStyle.MIUIX)
    private fun settingsDetail(style: UiStyle) {
        compose.setContent { BaiZeMiuixApp(DashboardUiState(), SchedulerUiState(), actions, AppearanceSettings(uiStyle = style)) }
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("自动任务设置"))
        compose.onNodeWithText("自动任务设置").performScrollTo().performClick()
        save("settings-detail-${style.name.lowercase()}")
        compose.onNodeWithText("清理执行条件").assertIsDisplayed()
        compose.onNodeWithText("首页", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("管理与维护"))
        compose.onNodeWithText("管理与维护").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("首页", useUnmergedTree = true).assertExists()
    }
    @Test fun settingsDetailSurvivesSavedStateAndBackReturnsToHub() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { BaiZeMiuixApp(DashboardUiState(), SchedulerUiState(), actions, AppearanceSettings(uiStyle = UiStyle.MIUIX)) }
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("自动任务设置"))
        compose.onNodeWithText("自动任务设置").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        save("settings-detail-restored-miuix")
        compose.onNodeWithText("清理执行条件").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("管理与维护"))
        compose.onNodeWithText("管理与维护").performScrollTo().assertIsDisplayed()
    }
    @Test fun rulesAndProtectionKeepUniqueActionsWithoutDuplicateDeepPages() {
        val opened = mutableListOf<String>()
        compose.setContent { BaiZeTheme(AppearanceSettings()) {
            CleanCenterRoute(CleanCenterActions({}, { opened += "safe" },
                { opened += "policy" }, { opened += "quarantine" }, { opened += it }))
        } }
        compose.onNodeWithText("完整深度清理").assertDoesNotExist()
        compose.onNodeWithText("卸载残留").assertDoesNotExist()
        compose.onNodeWithText("扫描并选择清理").assertDoesNotExist()
        // 应用缓存已由扫描工作台默认扫描与「即时缓存」覆盖，规则与保护页不再重复提供。
        compose.onNodeWithText("应用缓存").assertDoesNotExist()
        compose.onNodeWithText("清理策略").performScrollTo().performClick()
        compose.onNodeWithText("隔离区").performScrollTo().performClick()
        assertEquals(listOf("policy", "quarantine"), opened)
    }
    @Test fun homeShortcutRoutesAreSingleClickInBothSkins() {
        var style by mutableStateOf(UiStyle.MIUIX)
        val opened = mutableListOf<String>()
        compose.setContent { BaiZeMiuixApp(DashboardUiState(), SchedulerUiState(), actions.copy(
            photoCompression = { opened += "photo" }, fileTrash = { opened += "trash" }, duplicates = { opened += "duplicates" }), AppearanceSettings(uiStyle = style)) }
        for (skin in listOf(UiStyle.MIUIX, UiStyle.MATERIAL)) {
            compose.runOnIdle { style = skin }
            compose.onNodeWithText("照片瘦身").performScrollTo().performClick()
            compose.onNodeWithText("重复文件").performScrollTo().performClick()
            compose.onNodeWithText("回收站").performScrollTo().performClick()
        }
        assertEquals(listOf("photo", "duplicates", "trash", "photo", "duplicates", "trash"), opened)
    }
}
