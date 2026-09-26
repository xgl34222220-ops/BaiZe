package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolboxVisualReviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun realConsoleRendersAndSwitchesSectionsWithoutRoot() {
        val model = ToolboxViewModel(RuntimeEnvironment.getApplication())
        val appearance = AppearanceSettings(themeMode = ThemeMode.LIGHT, monetEnabled = false, blurEnabled = false)
        compose.setContent {
            CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
                BaiZeTheme(appearance) { ToolboxScreen(model, {}, {}, {}, {}, {}, {}) }
            }
        }
        compose.onNodeWithText("功能控制台").assertIsDisplayed()
        compose.onNodeWithText("碎片文件清理").assertIsDisplayed()
        capture("functions")
        compose.onNodeWithText("独立计划").performClick()
        compose.onNodeWithText("仅关屏时自动执行").assertIsDisplayed()
        capture("schedules")
        compose.onNodeWithText("结果记录").performClick()
        compose.onNodeWithText("尚无任务结果。提交后的任务会在实际结束后写入记录。").assertIsDisplayed()
        capture("history")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        val target = File("build/reports/ui-screenshots/toolbox-$name.png")
        target.parentFile!!.mkdirs()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
