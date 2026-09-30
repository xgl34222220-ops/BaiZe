package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatchCleanupUiTest {
    @get:Rule val compose = createComposeRule()
    private val low = item("low", "低风险样例", "low")
    private val high = item("high", "高风险样例", "high")

    @Test fun lowBatchThenHighRiskStillRequiresConfirmationAndCancelDoesNotClean() {
        var state by mutableStateOf(initial())
        var cleanCalls = 0
        var scans = 0
        show({ state }, onScan = { scans++ }, onToggle = { id ->
            state = state.copy(selectedIds = if (id in state.selectedIds) state.selectedIds - id else state.selectedIds + id)
        }, onClean = {
            cleanCalls++
            state = if (cleanCalls == 1) afterFirstBatch(false) else state.copy(
                items = state.items.map { it.copy(selectable = false, outcome = "已清理") },
                selectedIds = emptySet(), scanReady = false, cleanedBytes = 1024, cleanedFiles = 1,
                phase = "已完成所选项目清理")
        })
        compose.onNodeWithText("清理已选 1 项").performClick()
        compose.onNodeWithText("本批清理完成").assertExists()
        screenshot("after-low-batch")
        selectHigh()
        screenshot("remaining-high-selected")
        compose.onNodeWithText("清理已选 1 项").assertIsEnabled().performClick()
        compose.onNodeWithText("确认清理高风险项目").assertExists()
        screenshot("high-risk-confirmation")
        compose.onNodeWithText("返回核对").performClick()
        compose.runOnIdle { assertEquals(1, cleanCalls); assertEquals(setOf(high.id), state.selectedIds) }
        compose.onNodeWithText("清理已选 1 项").performClick()
        compose.onNodeWithText("确认清理").performClick()
        compose.runOnIdle { assertEquals(2, cleanCalls); assertEquals(0, scans); assertEquals(1024L, state.cleanedBytes) }
        compose.onNodeWithText("清理完成").assertExists()
    }

    @Test fun failedLowRiskBatchLeavesHighRiskSelectableAndAttemptedLowRiskDisabled() {
        var state by mutableStateOf(afterFirstBatch(true))
        var cleanCalls = 0
        show({ state }, onToggle = { id -> state = state.copy(selectedIds = state.selectedIds + id) },
            onClean = { cleanCalls++ })
        expand("低风险样例")
        compose.onNodeWithTag("scan-workbench-list").performScrollToNode(hasContentDescription("选择low.tmp"))
        compose.onNodeWithContentDescription("选择low.tmp").assertIsNotEnabled()
        selectHigh()
        compose.onNodeWithText("清理已选 1 项").assertIsEnabled().performClick()
        compose.onNodeWithText("确认清理高风险项目").assertExists()
        compose.onNodeWithText("返回核对").performClick()
        compose.runOnIdle { assertEquals(0, cleanCalls) }
    }

    private fun show(state: () -> WorkbenchUiState, onScan: () -> Unit = {}, onToggle: (String) -> Unit, onClean: () -> Unit) {
        val appearance = AppearanceSettings(blurEnabled = false, glassEnabled = false)
        compose.setContent {
            BaiZeTheme(appearance) {
                CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
                    ScanWorkbenchScreen(appearance, state(), WorkbenchActions(onBack = {}, onScan = onScan,
                        onStop = {}, onClean = onClean, onToggleItem = onToggle, onToggleGroup = {},
                        onSelectAll = {}, onClear = {}, onProtect = {}, onQuarantine = {}))
                }
            }
        }
    }

    private fun selectHigh() {
        expand("高风险样例")
        compose.onNodeWithTag("scan-workbench-list").performScrollToNode(hasContentDescription("选择high.tmp"))
        compose.onNodeWithContentDescription("选择high.tmp").assertIsEnabled().performClick()
    }

    private fun expand(title: String) {
        compose.waitUntil(5_000) {
            runCatching {
                compose.onNodeWithTag("scan-workbench-list").performScrollToNode(hasText(title))
                compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithText(title).performClick()
    }

    private fun screenshot(name: String) {
        val image = compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
        val output = File("build/reports/batch-cleanup-ui", "$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun initial() = WorkbenchUiState(profileConnected = true, cacheRequired = false,
        scanReady = true, scanProfile = "rules", expiresAtRealtime = SystemClock.elapsedRealtime() + 600_000,
        items = listOf(low, high), selectedIds = setOf(low.id))

    private fun afterFirstBatch(failed: Boolean) = initial().copy(cleanupCompleted = true,
        items = listOf(low.copy(selectable = false, outcome = if (failed) "未清理，请重新扫描" else "已清理"), high),
        selectedIds = emptySet(), cleanedBytes = if (failed) 0 else 512, cleanedFiles = if (failed) 0 else 1,
        notice = if (failed) WorkbenchNotice.WARNING else WorkbenchNotice.SUCCESS,
        phase = "未选项目可继续选择，未完成项需重新扫描")

    private fun item(id: String, group: String, risk: String) = WorkbenchItem("profile:$id", "profile", "rules", "", "",
        "rule_trash", id, group, "$id.tmp", risk, "/synthetic/$id.tmp", if (risk == "high") 1024 else 512,
        1, 0, "仅测试", true)
}
