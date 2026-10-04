package io.github.xgl34222220.baize

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DirectoryDrillDownUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun unindexedLeafShowsItsOwnCountAndExplainsWhyTheFileListIsEmpty() {
        val root = "/storage/emulated/0"
        val leaf = "$root/Download/fixture"
        val usage = DirectoryUsage(listOf(root), listOf(StorageDirectory(root, 80, 9000000),
            StorageDirectory(leaf, 2, 3072)), 0, 0, false, "本地")
        val state = StorageToolsUiState(mode = StorageToolMode.ANALYSIS, directoryUsage = usage,
            directory = leaf, status = "存储分析完成")
        compose.setContent { BaiZeTheme(AppearanceSettings()) { StorageToolsScreen(state, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("当前目录占用").assertIsDisplayed()
        compose.onNodeWithText("2 个文件（含子目录）").assertIsDisplayed()
        compose.onNodeWithText("目录文件尚不可操作").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("没有符合条件的文件").assertDoesNotExist()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
    }
}
