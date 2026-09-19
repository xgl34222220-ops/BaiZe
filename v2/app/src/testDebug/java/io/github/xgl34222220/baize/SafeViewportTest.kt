package io.github.xgl34222220.baize

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.theme.BaiZeSafeViewport
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
class SafeViewportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun cutoutAndStatusAreaClipContentAndConsumePaddingOnlyOnce() {
        val inset = WindowInsets(left = 24, top = 48, right = 16)
        compose.setContent {
            BaiZeTheme(AppearanceSettings(themeMode = ThemeMode.LIGHT)) {
                BaiZeSafeViewport(inset) {
                    // Model a child header still requesting insets plus overscroll/shadow painting.
                    Box(Modifier.fillMaxSize().windowInsetsPadding(inset)) {
                        Box(Modifier.fillMaxSize().testTag("content").background(Color.Magenta)
                            .drawWithContent {
                                drawRect(Color.Magenta, Offset(-200f, -200f), Size(size.width + 400f, size.height + 400f))
                                drawContent()
                            })
                    }
                }
            }
        }
        val bounds = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        assertEquals(48f, bounds.top, .5f)
        assertEquals(24f, bounds.left, .5f)
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        assertEquals(bitmap.width - 16f, bounds.right, .5f)
        assertNotEquals("Drawing must not reach the status bar", android.graphics.Color.MAGENTA, bitmap.getPixel(100, 20))
        assertNotEquals("Drawing must not reach a side cutout", android.graphics.Color.MAGENTA, bitmap.getPixel(10, 100))
        assertEquals(android.graphics.Color.MAGENTA, bitmap.getPixel(100, 100))
    }

    @Test fun viewportReflowsWhenSystemInsetsChange() {
        var inset by mutableStateOf<WindowInsets>(WindowInsets(top = 48))
        compose.setContent {
            BaiZeTheme(AppearanceSettings(themeMode = ThemeMode.DARK)) {
                BaiZeSafeViewport(inset) { Box(Modifier.fillMaxSize().testTag("content")) }
            }
        }
        assertEquals(48f, compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot.top, .5f)
        compose.runOnIdle { inset = WindowInsets(left = 40, top = 24) }
        val bounds = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        assertEquals(24f, bounds.top, .5f)
        assertEquals(40f, bounds.left, .5f)
    }
}
