package io.github.xgl34222220.baize

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrashBatchUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val first = TrashEntry("one", "/storage/emulated/0/Download/合同.pdf", "/trash/one", 1024,
        "a".repeat(64), 1, 2_000_000_000_000L)
    private val second = first.copy(id = "two", original = "/storage/emulated/0/Pictures/照片.jpg", stored = "/trash/two")
    private var state by mutableStateOf(FileTrashUiState())
    private var restored = emptySet<String>()
    private var purged = emptyList<TrashEntry>()
    private var cancellations = 0

    private fun render(dark: Boolean = false, large: Boolean = false) {
        state = FileTrashUiState(entries = listOf(first, second), occupied = 2048, loaded = true)
        val appearance = AppearanceSettings(monetEnabled = false, blurEnabled = false,
            themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        compose.setContent {
            val density = LocalDensity.current
            BaiZeTheme(appearance) {
                CompositionLocalProvider(LocalAppearanceSettings provides appearance,
                    LocalDensity provides Density(density.density, if (large) 1.5f else 1f)) {
                    FileTrashScreen(state, FileTrashActions(
                        onToggle = { id -> state = state.copy(selected = if (id in state.selected) state.selected - id else state.selected + id) },
                        onToggleAll = { state = state.copy(selected = if (state.selected.size == state.entries.size) emptySet() else state.entries.map { it.id }.toSet()) },
                        onRestore = { restored = it },
                        onPurge = { state = state.copy(pendingPurge = TrashBatchSnapshot.capture(state.entries, it)) },
                        onPurgeAll = { state = state.copy(pendingPurge = TrashBatchSnapshot.capture(state.entries)) },
                        onDismissPurge = { state = state.copy(pendingPurge = null) },
                        onConfirmPurge = { state.pendingPurge?.let { purged = it.entries }; state = state.copy(pendingPurge = null) },
                        onCancel = { cancellations++; state = state.copy(cancelRequested = true) }
                    ))
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun selectAllAndBatchRestoreAreReachableWithoutOpeningHelp() {
        render()
        compose.onNodeWithText("5 GiB").assertDoesNotExist()
        compose.onNodeWithText("恢复已选").assertIsNotEnabled()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("恢复已选").assertIsEnabled().performClick()
        assertEquals(setOf("one", "two"), restored)
        screenshot("trash-light-selected")
    }

    @Test fun clearAllRequiresFrozenReviewAndExcludesNewArrivals() {
        render()
        compose.onNodeWithText("清空回收站").performClick()
        compose.onNodeWithText("永久删除 2 项？").assertIsDisplayed()
        assertTrue(purged.isEmpty())
        compose.runOnIdle {
            state = state.copy(entries = state.entries + first.copy(id = "new", original = "/new/arrival.apk"))
        }
        screenshot("trash-clear-confirmation")
        compose.onNodeWithText("永久删除这 2 项").performClick()
        assertEquals(listOf("one", "two"), purged.map { it.id })
    }

    @Test fun dismissingClearAllKeepsFilesAndSelection() {
        render()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("永久删除已选").performClick()
        compose.onNodeWithText("保留文件").performClick()
        assertTrue(purged.isEmpty())
        assertEquals(setOf("one", "two"), state.selected)
        compose.onNodeWithText("永久删除 2 项？").assertDoesNotExist()
    }

    @Test fun runningBatchDisablesReentryAndStopDoesNotPromiseRollback() {
        render()
        compose.runOnIdle {
            state = state.copy(busy = true, action = TrashBatchAction.PURGE,
                progress = TrashBatchProgress(1, 2, second))
        }
        compose.onNodeWithText("清空回收站").assertIsNotEnabled()
        compose.onNodeWithContentDescription("刷新回收站").assertIsNotEnabled()
        compose.onNodeWithText("停止剩余操作").performClick()
        compose.onNodeWithText("正在停止…").assertIsNotEnabled()
        compose.onNodeWithText("正在停止；当前文件处理完成后不再开始下一项。").assertIsDisplayed()
        assertEquals(1, cancellations)
        screenshot("trash-stopping-progress")
    }

    @Test fun darkLargeFontKeepsActionsAndConfirmationVisible() {
        render(dark = true, large = true)
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("恢复已选").assertIsDisplayed()
        compose.onNodeWithText("永久删除已选").assertIsDisplayed()
        screenshot("trash-dark-large-font")
        compose.onNodeWithText("永久删除已选").performClick()
        compose.onNodeWithText("永久删除这 2 项").assertIsDisplayed()
        compose.onNodeWithText("保留文件").assertIsDisplayed()
        screenshot("trash-dark-large-font-confirmation")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w700dp-h360dp-mdpi")
    fun landscapeLargeFontKeepsIrreversibleConfirmationActionsReachable() {
        render(large = true)
        compose.runOnIdle { state = state.copy(pendingPurge = TrashBatchSnapshot.capture(state.entries)) }
        compose.onNodeWithText("永久删除这 2 项").assertIsDisplayed()
        compose.onNodeWithText("保留文件").assertIsDisplayed()
        screenshot("trash-landscape-large-font-confirmation")
        compose.onNodeWithText("保留文件").performClick()
        assertTrue(purged.isEmpty())
    }

    @Test fun longReviewListCanReachTheLastExactPathWithoutLosingConfirmation() {
        render()
        val records = (1..30).map { number -> first.copy(id = "item-$number",
            original = "/storage/emulated/0/Download/long-folder-$number/保留的完整文件名-$number.pdf") }
        compose.runOnIdle { state = state.copy(pendingPurge = TrashBatchSnapshot.capture(records)) }
        compose.onNodeWithTag("trash-reviewed-list").performScrollToNode(hasText(records.last().original, substring = true))
        compose.onNodeWithText(records.last().original, substring = true).assertIsDisplayed()
        compose.onNodeWithText("永久删除这 30 项").assertIsDisplayed()
        compose.onNodeWithText("保留文件").performClick()
        assertTrue(purged.isEmpty())
    }

    @Test fun perItemFailureRemainsReadableAndDoesNotClaimRollback() {
        render()
        val result = TrashBatchResult(TrashBatchSnapshot.capture(listOf(first, second)), listOf(
            TrashBatchItemResult(first, true, "已恢复至 ${first.original}"),
            TrashBatchItemResult(second, false, "内容已变化；请核对后重试")
        ))
        compose.runOnIdle { state = state.copy(result = result, message = "恢复完成 1 项 · 未确认完成 1 项") }
        compose.onNodeWithText("查看逐项结果").performScrollTo().performClick()
        compose.onNodeWithTag("trash-list").performScrollToNode(hasText("内容已变化；请核对后重试"))
        compose.onNodeWithText("内容已变化；请核对后重试").assertIsDisplayed()
        compose.onNodeWithText("未确认完成", substring = false).assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        val bitmap = compose.runOnIdle {
            val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView
            if (dialog == null) captureActivityContent(compose.activity)
            else android.graphics.Bitmap.createBitmap(dialog.width, dialog.height, android.graphics.Bitmap.Config.ARGB_8888)
                .also { dialog.draw(android.graphics.Canvas(it)) }
        }
        val target = File("build/reports/ui-screenshots/$name.png").apply { parentFile.mkdirs() }
        target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
