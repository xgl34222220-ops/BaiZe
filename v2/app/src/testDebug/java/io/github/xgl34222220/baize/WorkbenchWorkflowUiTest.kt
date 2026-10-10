package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import java.io.File
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
class WorkbenchWorkflowUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var state by mutableStateOf(WorkbenchUiState())
    private var scans = 0
    private val cache = WorkbenchItem("cache", "cache", "cache", "example.browser", "浏览器", "应用缓存", "browser", "浏览器",
        "old-cache", "low", "/storage/emulated/0/Android/data/example.browser/cache/old-cache", 8_388_608, 4, 1, "可再生缓存", true)
    private val rule = cache.copy(id = "rule", source = "profile", profile = "rules", packageName = "example.notes",
        appName = "笔记", groupKey = "notes", groupTitle = "笔记", title = "debug.log", risk = "medium", bytes = 1_048_576)
    private fun ready() = WorkbenchUiState(profileConnected = true, cacheConnected = true, scanReady = true,
        items = listOf(cache, rule), selectedIds = setOf(cache.id), expiresAtRealtime = SystemClock.elapsedRealtime() + 1_800_000L,
        phase = "扫描完成", notice = WorkbenchNotice.SUCCESS)

    @Test fun categoryOverviewFiltersTheSameReviewWithoutChangingSelection() {
        render(ready())
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("workbench-category:cache").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("空间清理").assertIsDisplayed()
        compose.onNodeWithTag("workbench-stages").assertIsDisplayed()
        save("review-light")
        compose.onNodeWithTag("scan-workbench-list").performScrollToNode(hasTestTag("workbench-category:cache"))
        compose.onNodeWithTag("workbench-category:cache").performClick()
        // 结果列表末尾新增“需要你复核”分类，列表变长后从顶部开始查找筛选摘要。
        compose.onNodeWithTag("scan-workbench-list").performScrollToIndex(0)
        compose.onNodeWithTag("scan-workbench-list").performScrollToNode(hasText("显示 1 / 2 项"))
        compose.onNodeWithText("显示 1 / 2 项").assertIsDisplayed()
        assertEquals(setOf(cache.id), state.selectedIds)
        compose.onNodeWithText("清理已选 1 项").assertIsDisplayed()
        assertEquals(0, scans)
    }

    @Test fun allHighRiskCategoryCheckExpandsAndExplainsWithoutSelectingAndStaysAligned() {
        val fragment = cache.copy(id = "fragment", source = "profile", profile = "fragments", category = "fragments",
            packageName = "", appName = "", groupKey = "fragments-old", groupTitle = "旧应用残留", title = "leftover.db",
            risk = "high", path = "/storage/emulated/0/.leftover/leftover.db", bytes = 857_088, reason = "残留数据，需核对")
        val toggled = mutableListOf<Set<String>>()
        render(ready().copy(items = listOf(cache, rule, fragment)), onToggleVisible = { toggled += it })
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("workbench-category:fragments").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("scan-workbench-list").performScrollToNode(hasTestTag("workbench-category:fragments"))
        // 所有分类的勾选位在同一列，且不越过右侧页边距。
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val rights = listOf("cache", "rules", "fragments").map {
            compose.onNodeWithTag("workbench-category-check:$it").fetchSemanticsNode().boundsInRoot.right
        }
        assertTrue("category checks misaligned: $rights", rights.max() - rights.min() < 1f)
        assertTrue("check passes page margin: $rights vs ${root.right}", rights.max() <= root.right)
        compose.onNodeWithContentDescription("残留碎片需逐项确认").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("该分类需逐项确认", substring = true).fetchSemanticsNodes().isNotEmpty() }
        save("fragments-review")
        // 高风险不会被分类勾选自动选中，只展开并提示。
        assertTrue(toggled.isEmpty())
        assertEquals(setOf(cache.id), state.selectedIds)
        compose.onNodeWithTag("scan-workbench-list").performScrollToNode(hasContentDescription("选择leftover.db"))
        compose.onNodeWithContentDescription("选择leftover.db").assertIsDisplayed().assertIsOff()
        assertEquals(0, scans)
    }

    @Test fun filtersSearchAndExpandedAppSurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        render(ready(), restoration = restoration)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("workbench-category:cache").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("scan-workbench-list").performScrollToNode(hasTestTag("workbench-category:cache"))
        compose.onNodeWithTag("workbench-category:cache").performClick()
        compose.onNodeWithTag("scan-workbench-list").performScrollToNode(hasTestTag("workbench-search"))
        compose.onNodeWithTag("workbench-search").performTextInput("old-cache")
        compose.onNodeWithTag("workbench-search").performImeAction()
        compose.onNodeWithTag("scan-workbench-list").performScrollToNode(hasText("浏览器"))
        compose.onNodeWithText("浏览器").performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("scan-workbench-list").performScrollToNode(hasContentDescription("选择old-cache")) }.isSuccess
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("scan-workbench-list").performScrollToNode(hasContentDescription("选择old-cache")) }.isSuccess
        }
        compose.onNodeWithContentDescription("选择old-cache").assertIsDisplayed().assertIsOn()
        compose.onNodeWithTag("scan-workbench-list").performScrollToNode(hasTestTag("workbench-search"))
        compose.onNodeWithTag("workbench-search").assertTextContains("old-cache")
        compose.onNodeWithText("显示 1 / 2 项").assertExists()
        assertEquals(setOf(cache.id), state.selectedIds)
        assertEquals(0, scans)
        save("restored-review")
    }

    @Test fun addingProtectionDoesNotPretendToStartAnotherScan() {
        render(ready().copy(running = true, operation = "protect", notice = WorkbenchNotice.INFO,
            phase = "正在把浏览器加入保护白名单…"))
        compose.onNodeWithText("保护中").assertIsDisplayed()
        compose.onNodeWithText("扫描中").assertDoesNotExist()
        assertEquals(0, scans)
    }

    @Test fun completedCleanupShowsMeasuredBytesAndKeepsTheReportOnThisPage() {
        render(ready().copy(scanReady = false, cleanupCompleted = true, cleanedBytes = 4_096, cleanedFiles = 1,
            phase = "已清理 1 个文件，其他项目已保留", items = listOf(cache.copy(outcome = "已清理"), rule)))
        compose.onNodeWithText("本次确认删除容量").assertIsDisplayed()
        compose.onNodeWithText("4.10").assertIsDisplayed()
        compose.onNodeWithText("已清理 1 个文件").assertIsDisplayed()
        compose.onNodeWithText("上次结果已保留").assertDoesNotExist()
        compose.onNodeWithText("清理已选 1 项").assertDoesNotExist()
        compose.onNodeWithText("重新扫描").assertIsDisplayed()
        save("complete-light")
        compose.onNodeWithText("查看本次处理详情").performClick()
        compose.onNodeWithText("任务详情").assertIsDisplayed()
        assertEquals(0, scans)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun directoryOnlyResultExplainsZeroBytesWithoutCallingDirectoriesFiles() {
        render(ready().copy(scanProfile = "empty", scanReady = false, cleanupCompleted = true,
            cleanedBytes = 0, cleanedFiles = 0, cleanedDirectories = 3, selectedIds = emptySet(),
            items = listOf(cache.copy(source = "profile", profile = "empty", category = "empty_dir", title = "旧空目录",
                bytes = 0, files = 0, directories = 1, selectable = false, outcome = "已清理")),
            phase = "已完成所选项目清理", notice = WorkbenchNotice.SUCCESS),
            dark = true, fontScale = 1.5f)
        compose.onNodeWithTag("scan-workbench-list").performScrollToNode(hasText("本次移除空目录"))
        compose.onNodeWithText("本次移除空目录").assertIsDisplayed()
        compose.onNodeWithText("已清理 0 个文件 · 3 个目录").assertIsDisplayed()
        compose.onNodeWithText("已清理 3 个文件").assertDoesNotExist()
        compose.onNodeWithTag("scan-workbench-list").performScrollToNode(hasText("空目录没有文件内容，文件容量记为 0 B；移除数量单独统计。"))
        compose.onNodeWithText("空目录没有文件内容，文件容量记为 0 B；移除数量单独统计。").assertIsDisplayed()
        compose.onNodeWithText("重新扫描").assertIsDisplayed()
        save("empty-directories-count-dark-large-font")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w740dp-h320dp-mdpi")
    fun directoryResultAndItsDetailsRemainReachableInLandscape() {
        render(ready().copy(scanProfile = "empty", scanReady = false, cleanupCompleted = true,
            cleanedBytes = 0, cleanedFiles = 0, cleanedDirectories = 12,
            phase = "已移除所选空目录，未预览的父目录保持不变"), fontScale = 1.5f)
        compose.onNodeWithTag("scan-workbench-list").performScrollToNode(hasText("已清理 0 个文件 · 12 个目录"))
        compose.onNodeWithText("已清理 0 个文件 · 12 个目录").assertIsDisplayed()
        compose.onNodeWithTag("scan-workbench-list").performScrollToNode(hasText("查看本次处理详情"))
        compose.onNodeWithText("查看本次处理详情").performClick()
        compose.onNodeWithText("任务详情").assertIsDisplayed()
        assertEquals(0, scans)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun completedAndHistoricalStatesKeepOneReachableActionAtLargeFont() {
        render(ready().copy(scanReady = false, cleanupCompleted = true, cleanedBytes = 0, cleanedFiles = 0,
            phase = "所有所选文件均已变化，本次没有删除文件", notice = WorkbenchNotice.WARNING), dark = true, fontScale = 1.5f)
        compose.onNodeWithText("本次清理已结束").assertIsDisplayed()
        compose.onNodeWithText("已清理 0 个文件").assertIsDisplayed()
        compose.onNodeWithText("重新扫描").assertIsDisplayed()
        save("complete-dark-large-font")
    }

    private fun render(initial: WorkbenchUiState, dark: Boolean = false, fontScale: Float = 1f, restoration: StateRestorationTester? = null,
                       onToggleVisible: ((Set<String>) -> Unit)? = null) {
        state = initial
        val appearance = AppearanceSettings(monetEnabled = false, blurEnabled = false,
            themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        val content: @Composable () -> Unit = {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalAppearanceSettings provides appearance) {
                BaiZeTheme(appearance) {
                    ScanWorkbenchScreen(appearance, state, WorkbenchActions({}, { scans++ }, {}, {}, {}, {}, {}, {}, {}, {},
                        onToggleVisibleItems = onToggleVisible))
                }
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        compose.waitForIdle()
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        val target = File("build/reports/ui-screenshots/workflow-$name.png").apply { parentFile.mkdirs() }
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
