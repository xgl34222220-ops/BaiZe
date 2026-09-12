package io.github.xgl34222220.baize

import android.app.Application
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkbenchVisualReviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var state by mutableStateOf(WorkbenchUiState())
    private var cleanRequests = 0
    private var scanRequests = 0

    @Test fun thousandsOfResultsKeepTheCleanupActionInView() {
        render(ready())
        compose.onNodeWithText("清理已选 1800 项").assertIsDisplayed().performClick()
        assertEquals(1, cleanRequests)
        save("results-light")
    }

    @Test fun resultsDark() { render(ready(), dark = true); save("results-dark") }

    @Test fun reportedFailureRetainsReviewWithoutShowingASecondEmptyState() {
        render(ready().copy(notice = WorkbenchNotice.ERROR,
            phase = "所选项目清理失败：data parcel size 429480 bytes"))
        compose.onNodeWithText("本次任务未完成").assertIsDisplayed()
        compose.onNodeWithText("清理已选 1800 项").assertIsDisplayed()
        compose.onNodeWithText("按应用查看清理内容").assertDoesNotExist()
        save("failure-preserved")
        compose.onNodeWithContentDescription("查看任务详情").performClick()
        compose.onNodeWithText("所选项目清理失败：data parcel size 429480 bytes").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun failedReviewFitsNarrowLargeFont() {
        render(ready().copy(scanReady = false, notice = WorkbenchNotice.ERROR,
            phase = "清理结果未确认；记录已保留，请重新扫描"), fontScale = 1.3f)
        compose.onNodeWithText("重新扫描").assertIsDisplayed().performClick()
        assertEquals(1, scanRequests)
        save("failure-narrow-large-font")
    }

    @Test fun mediumCanBeSelectedAndHighRiskRequiresExplicitConfirmation() {
        val medium = item(0).copy(id = "medium", title = "诊断日志", groupKey = "manual", groupTitle = "示例应用", risk = "medium")
        val high = item(1).copy(id = "high", title = "离线资源", groupKey = "manual", groupTitle = "示例应用", risk = "high")
        render(ready().copy(items = listOf(medium, high), selectedIds = emptySet(), policyTitle = "保守"))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("示例应用").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("示例应用").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("选择诊断日志").fetchSemanticsNodes().size == 1 &&
                compose.onAllNodesWithContentDescription("选择离线资源").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithContentDescription("选择诊断日志").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("选择离线资源").assertIsEnabled().performClick()
        compose.onNodeWithText("清理已选 2 项").assertIsDisplayed().performClick()
        assertEquals(0, cleanRequests)
        compose.onNodeWithText("确认清理高风险项目").assertIsDisplayed()
        save("high-risk-selected")
        compose.onNodeWithText("确认清理").performClick()
        assertEquals(1, cleanRequests)
    }

    @Test fun changedSnapshotCannotReuseTheHighRiskConfirmation() {
        val high = item(1).copy(id = "high", title = "离线资源", risk = "high")
        render(ready().copy(items = listOf(high), selectedIds = setOf(high.id)))
        compose.onNodeWithText("清理已选 1 项").performClick()
        compose.runOnIdle { state = state.copy(expiresAtRealtime = state.expiresAtRealtime + 1_000L) }
        compose.onNodeWithText("确认清理").assertIsNotEnabled()
        assertEquals(0, cleanRequests)
    }

    @Test fun initialScanHasOnePrimaryAction() {
        render(WorkbenchUiState(profileConnected = true, cacheConnected = true))
        compose.onNodeWithText("开始扫描").assertIsDisplayed()
        save("empty")
    }

    private fun render(initial: WorkbenchUiState, dark: Boolean = false, fontScale: Float = 1f) {
        state = initial
        val appearance = AppearanceSettings(monetEnabled = false, themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
            blurEnabled = false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalAppearanceSettings provides appearance) {
                BaiZeTheme(appearance) {
                    ScanWorkbenchScreen(appearance, state, WorkbenchActions(
                        onBack = {}, onScan = { scanRequests++ }, onStop = {}, onClean = { cleanRequests++ },
                        onToggleItem = { id -> state = state.copy(selectedIds = state.selectedIds.toMutableSet().apply { if (!add(id)) remove(id) }) },
                        onToggleGroup = {}, onSelectAll = {}, onClear = { state = state.copy(selectedIds = emptySet()) },
                        onProtect = {}, onQuarantine = {}
                    ))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun ready(): WorkbenchUiState {
        val items = (0 until 1980).map(::item)
        return WorkbenchUiState(profileConnected = true, cacheConnected = true, scanReady = true,
            notice = WorkbenchNotice.SUCCESS, phase = "扫描完成", items = items,
            selectedIds = items.take(1800).mapTo(linkedSetOf()) { it.id },
            expiresAtRealtime = SystemClock.elapsedRealtime() + 30 * 60 * 1000L)
    }

    private fun item(index: Int) = WorkbenchItem(
        id = "profile:$index", source = if (index % 2 == 0) "cache" else "profile", profile = "rules",
        packageName = "example.app${index % 12}", appName = "示例应用 ${index % 12 + 1}", category = "诊断日志",
        groupKey = "app:${index % 12}", groupTitle = "示例应用 ${index % 12 + 1}", title = "清理样例-$index.log",
        risk = "medium", path = "/storage/emulated/0/Android/data/example.app${index % 12}/cache/清理样例-$index.log",
        bytes = 1024L * (index + 1), files = 1, directories = 0, reason = "诊断日志", selectable = true
    )

    private fun save(name: String) {
        compose.waitForIdle()
        val output = File("build/reports/ui-screenshots/workbench-$name.png").apply { parentFile.mkdirs() }
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
