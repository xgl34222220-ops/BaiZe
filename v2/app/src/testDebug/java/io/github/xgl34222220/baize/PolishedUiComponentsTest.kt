package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import io.github.xgl34222220.baize.ui.appearance.*
import io.github.xgl34222220.baize.ui.components.*
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
class PolishedUiComponentsTest {
    private var dialogView: View? = null
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun compactBarKeepsBothActionsAndDoesNotConsumeTheList() {
        var selected by mutableStateOf(false)
        var cleanRequests = 0
        compose.setContent { BaiZeTheme(AppearanceSettings()) {
            Column {
                Text("文件列表")
                Box(Modifier.testTag("selection-bar")) {
                    CleanSelectionBar(if (selected) 52 else 0, 52, if (selected) "9.03 MB" else "0 B",
                        selected, true, { selected = !selected }, { cleanRequests++ })
                }
            }
        } }
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("取消全选").assertIsDisplayed()
        compose.onNodeWithText("清理已选 52 项").assertIsDisplayed().performClick()
        assertEquals(1, cleanRequests)
        // 80dp content + 12dp external padding; system navigation inset is zero in this fixture.
        val bar = compose.onNodeWithTag("selection-bar").fetchSemanticsNode().boundsInRoot
        assertTrue("Compact selection bar was ${bar.height}px tall", bar.height <= 94f)
        save("v7-selection-compact")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun largeFontConfirmationHasReachableActionsAndCancelDoesNotClean() {
        var open by mutableStateOf(true)
        var cleaned = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                BaiZeTheme(AppearanceSettings(themeMode = ThemeMode.DARK, blurEnabled = false)) {
                    Text("清理列表")
                    if (open) BaiZeDialog(onDismissRequest = { open = false },
                        title = {
                            val view = LocalView.current
                            SideEffect { dialogView = view.rootView }
                            Text("确认清理高风险项目")
                        },
                        text = { Text(("请核对完整路径：/storage/emulated/0/Android/data/example.app/files/离线文件。\n").repeat(15)) },
                        confirmButton = { BaiZeDialogButton({ cleaned++; open = false }) { Text("确认清理") } },
                        dismissButton = { BaiZeDialogButton({ open = false }) { Text("返回核对") } })
                }
            }
        }
        compose.onNodeWithText("确认清理").assertIsDisplayed()
        compose.onNodeWithText("返回核对").assertIsDisplayed()
        save("v7-dialog-dark-large-font")
        compose.onNodeWithText("返回核对").performClick()
        assertFalse(open)
        assertEquals(0, cleaned)
    }

    @Test fun livePathHasStableHeightAndDeliversTheLastUpdate() {
        val first = "/data/cache/first"
        val last = "/storage/emulated/0/Android/data/example.app/cache/Default/GPUCache/" + "长文件名".repeat(30)
        var path by mutableStateOf(first)
        compose.setContent { BaiZeTheme(AppearanceSettings()) { BaiZePathText(path, Modifier.testTag("path"), live = true) } }
        val height = compose.onNodeWithTag("path").fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { path = last }
        compose.waitUntil(timeoutMillis = 3000) { compose.onAllNodesWithText(last).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(height, compose.onNodeWithTag("path").fetchSemanticsNode().boundsInRoot.height, .1f)
        save("v7-path-single-line")
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val out = File("build/reports/ui-screenshots/$name.png").apply { parentFile.mkdirs() }
        val bitmap = compose.runOnIdle {
            val view = dialogView
            if (view == null) captureActivityContent(compose.activity)
            else Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
