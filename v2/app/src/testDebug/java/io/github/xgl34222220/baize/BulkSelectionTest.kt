package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.xgl34222220.baize.ui.appearance.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
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
class BulkSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun categoryCountsDoNotReadDigitsFromErrors() {
        val state = ResumeSmartUiState(totalSafe = 4, cacheCount = 4, cacheBytes = 200L,
            apkSummary = "Android 16 错误 403", apkSelected = true).selectAll(true)
        assertEquals(4, state.selectedCount)
        assertEquals(200L, state.selectedBytes)
        assertTrue(state.allSelected)
        assertFalse(state.apkSelected)
        assertEquals(0, state.selectAll(false).selectedCount)
        assertNull(state.copy(cacheBytes = null).selectedBytes)
    }

    @Test fun duplicateBulkSelectionRetainsOnePerGroup() {
        val first = listOf(record(1), record(2), record(3))
        val second = listOf(record(4), record(5))
        val state = StorageToolsUiState(mode = StorageToolMode.DUPLICATES,
            duplicateGroups = listOf(DuplicateFileGroup("a", 100, first), DuplicateFileGroup("b", 100, second)))
        val selected = state.toggleAllSelection()
        assertEquals(setOf("uri2", "uri3", "uri5"), selected.selected)
        assertEquals(300L, selected.selectedBytes)
        assertEquals(selected.selected, selected.toggleSelection("uri1").selected)
        assertTrue(selected.toggleAllSelection().selected.isEmpty())
        // The retained original can be changed by unchecking another copy first.
        assertEquals(setOf("uri1", "uri3", "uri5"), selected.toggleSelection("uri2").toggleSelection("uri1").selected)
    }

    @Test fun largeFilesSelectAllAndClear() {
        val state = StorageToolsUiState(records = listOf(record(1), record(2)), selected = setOf("uri1"))
        assertEquals(setOf("uri1", "uri2"), state.toggleAllSelection().selected)
        assertTrue(state.toggleAllSelection().toggleAllSelection().selected.isEmpty())
    }

    @Test fun smartFooterRemainsInteractive() {
        var state by mutableStateOf(ResumeSmartUiState(connected = true, scanCompleted = true, cleanReady = true,
            totalSafe = 12, cacheCount = 8, apkCount = 4, cacheBytes = 400_000_000, apkBytes = 200_000_000,
            cacheSummary = "8 项 · 400 MB", apkSummary = "4 个 · 200 MB", safeSummary = "0 项",
            phase = "扫描完成 · 发现 12 项可清理内容").selectAll(true))
        var cleaned = 0
        compose.setContent { BaiZeTheme(AppearanceSettings(themeMode = ThemeMode.LIGHT)) {
            ResumeSmartScreen(state, {}, {}, { cleaned++ }, {}, {}, {},
                onToggleAll = { state = state.selectAll(!state.allSelected) })
        } }
        compose.onNodeWithText("取消全选").performClick()
        compose.onNodeWithText("清理已选 0 项").assertIsNotEnabled()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("清理已选 12 项").performClick()
        assertEquals(1, cleaned)
        save("smart-selection")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun duplicateFooterDarkLargeFont() {
        var state by mutableStateOf(StorageToolsUiState(mode = StorageToolMode.DUPLICATES,
            status = "发现 1 组重复文件", duplicateGroups = listOf(DuplicateFileGroup("a", 100,
                listOf(record(1), record(2), record(3))))))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                BaiZeTheme(AppearanceSettings(themeMode = ThemeMode.DARK)) {
                    StorageToolsScreen(state, {}, {}, { state = state.toggleSelection(it) }, {}, {},
                        onToggleAll = { state = state.toggleAllSelection() })
                }
            }
        }
        compose.onNodeWithText("勾选多余副本").assertIsDisplayed().performClick()
        compose.onNodeWithText("删除已选 2 项").assertIsDisplayed()
        save("duplicate-selection-dark-320")
    }

    private fun record(id: Long) = StorageFileRecord(id, "uri$id", "/storage/emulated/0/Download/文档$id.pdf",
        "文档$id.pdf", 100, 1, "application/pdf")
    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        val file = File("build/reports/ui-screenshots/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
