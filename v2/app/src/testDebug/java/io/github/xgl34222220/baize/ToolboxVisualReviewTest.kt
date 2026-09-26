package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
    @Test fun extensionsReuseNavigationAndDoNotDuplicateExistingCleaners() {
        val model = ToolboxViewModel(RuntimeEnvironment.getApplication())
        val appearance = AppearanceSettings(themeMode = ThemeMode.LIGHT, monetEnabled = false, blurEnabled = false)
        compose.setContent {
            CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
                BaiZeTheme(appearance) { ToolboxScreen(model, {}, {}, {}, {}, {}, {}) }
            }
        }
        compose.onNodeWithText("扩展工具").assertIsDisplayed()
        compose.onNodeWithText("微信专项").assertIsDisplayed()
        compose.onNodeWithText("空文件与空目录").assertDoesNotExist()
        compose.onNodeWithText("碎片文件清理").assertDoesNotExist()
        compose.onNodeWithText("文件归类").assertDoesNotExist()
        capture("home")
        compose.onNodeWithText("系统维护").performScrollTo().performClick()
        compose.onNodeWithText("后台进程管理").performScrollTo().assertIsDisplayed()
        capture("system")
        compose.onNodeWithText("后台进程管理").performClick()
        compose.onNodeWithText("管理应用").performScrollTo().assertIsDisplayed()
        capture("process")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("维护工具").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("执行记录").performClick()
        compose.onNodeWithText("暂无执行记录").assertIsDisplayed()
        capture("history")
    }
    @Test fun darkAppearanceAndLargerTextUseTheSameComponents() {
        val model = ToolboxViewModel(RuntimeEnvironment.getApplication())
        val appearance = AppearanceSettings(themeMode = ThemeMode.DARK, monetEnabled = false, blurEnabled = false)
        compose.setContent {
            CompositionLocalProvider(LocalAppearanceSettings provides appearance,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 1.3f)) {
                BaiZeTheme(appearance) { ToolboxScreen(model, {}, {}, {}, {}, {}, {}) }
            }
        }
        compose.onNodeWithText("微信专项").assertIsDisplayed()
        capture("dark-large-text")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        val target = File("build/reports/ui-screenshots/toolbox-$name.png")
        target.parentFile!!.mkdirs()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
