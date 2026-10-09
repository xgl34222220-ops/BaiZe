package io.github.xgl34222220.baize

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
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
class StorageSunburstUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val root = "/storage/emulated/0"

    @Test fun tappingTheLargestSectorDrillsIntoThatDirectory() {
        val usage = DirectoryUsage(listOf(root), listOf(StorageDirectory(root, 10, 1000),
            StorageDirectory("$root/Movies", 3, 750), StorageDirectory("$root/Download", 2, 250)), 0, 0, false, "本地")
        val opened = mutableListOf<String?>()
        compose.setContent { BaiZeTheme(AppearanceSettings()) {
            StorageToolsScreen(StorageToolsUiState(mode = StorageToolMode.ANALYSIS, directoryUsage = usage, directory = root,
                status = "存储分析完成"), {}, {}, {}, {}, {}, onDirectory = { opened += it })
        } }
        val chart = compose.onNode(hasContentDescription("目录占用环形图", substring = true)).performScrollTo()
        chart.assertIsDisplayed()
        compose.onNodeWithText("2 个子目录").assertIsDisplayed()
        // Movies spans 0..75 % clockwise from 12 o'clock; 3 o'clock on the inner ring is inside it.
        chart.performTouchInput { click(Offset(width / 2f + width / 2f * .535f, height / 2f)) }
        assertEquals(listOf<String?>("$root/Movies"), opened)
        // The centre hole returns to the parent level.
        chart.performTouchInput { click(center) }
        assertEquals(2, opened.size)
        assertNull(opened.last())
    }

    @Test fun largeDirectoriesArePagedInsteadOfRenderedAtOnce() {
        val dirs = (0 until 55).map { StorageDirectory("$root/d$it", 1, (10_000 - it).toLong()) }
        val usage = DirectoryUsage(listOf(root), listOf(StorageDirectory(root, 55, 1_000_000)) + dirs, 0, 0, false, "本地")
        compose.setContent { BaiZeTheme(AppearanceSettings()) {
            StorageToolsScreen(StorageToolsUiState(mode = StorageToolMode.ANALYSIS, directoryUsage = usage, directory = root,
                status = "存储分析完成"), {}, {}, {}, {}, {})
        } }
        val more = compose.onNodeWithText("显示更多目录（还有 15 个）")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("显示更多目录（还有 15 个）"))
        compose.onNodeWithText("d54").assertDoesNotExist()
        more.performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("d54"))
        compose.onNodeWithText("d54").assertIsDisplayed()
        compose.onNodeWithText("显示更多目录（还有 15 个）").assertDoesNotExist()
    }
}
