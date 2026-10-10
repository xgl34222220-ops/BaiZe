package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 首页下半部分：上次扫描（不编造数字）、回收站入口、扫描中按钮可取消。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeFillUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = DashboardUiState(connected = true, ready = true, storageTotal = 1_000_000_000_000L,
        storageFree = 743_000_000_000L, storageUsed = 257_000_000_000L)
    private fun actions(calls: MutableList<String> = mutableListOf()) = DashboardActions(refresh = {}, clean = {}, organize = {},
        scan = { calls += "scan" }, apkScan = { calls += "apk" }, largeFiles = { calls += "large" },
        duplicates = { calls += "duplicates" }, storageAnalysis = {}, cleanScan = {}, dismissScan = {}, stop = { calls += "stop" },
        deep = {}, corpses = {}, audit = {}, saveScheduler = {}, schedulerCommand = {},
        clearHistory = {}, clearRawLog = {}, reviewProtected = {}, whitelist = {}, resumableScan = {},
        theme = {}, reconnect = {}, resetScanPerformance = {}, crash = {},
        fileTrash = { calls += "trash" }, storageView = { calls += "view:$it" })

    @Test fun neverScannedShowsPlaceholdersAndRoutesToExistingPages() {
        val calls = mutableListOf<String>()
        compose.setContent { BaiZeMiuixApp(base, SchedulerUiState(), actions(calls), appearance()) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("尚未扫描").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("共可清理 —").assertExists()
        compose.onNodeWithText("上次扫描").assertExists()
        assertTrue(compose.onAllNodesWithText("未扫描").fetchSemanticsNodes().size >= 4)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("聊天媒体"))
        compose.onNodeWithText("聊天媒体").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("安装包").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("大文件").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("重复文件").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("view:CHAT_MEDIA", "apk", "large", "duplicates"), calls)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("回收站"))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("回收站为空").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("回收站").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("trash", calls.last())
        save("home-fill-never-scanned")
    }

    @Test fun runningButtonShowsProgressAndCancels() {
        val calls = mutableListOf<String>()
        compose.setContent { BaiZeMiuixApp(base.copy(running = true, taskOperation = "profile-scan",
            taskProgressCurrent = 42, taskProgressTotal = 100), SchedulerUiState(), actions(calls), appearance()) }
        compose.onNodeWithText("扫描中 42%").assertIsDisplayed().performClick()
        assertEquals(listOf("stop"), calls)
        save("home-fill-scan-running")
    }

    private fun appearance() = AppearanceSettings(themeMode = ThemeMode.LIGHT,
        monetEnabled = false, glassEnabled = false, blurEnabled = false)

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        File("build/reports/ui-screenshots/$name.png").apply {
            parentFile!!.mkdirs()
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
