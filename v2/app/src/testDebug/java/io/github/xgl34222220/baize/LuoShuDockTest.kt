package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.xgl34222220.baize.ui.appearance.*
import io.github.xgl34222220.baize.ui.miuix.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import java.io.File
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
class LuoShuDockTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun capabilityPolicyNeverUsesShaderWithoutHardwareOrEffects() {
        assertEquals(DockRendering.OPAQUE, dockRendering(26, true, true, false, true))
        assertEquals(DockRendering.HAZE, dockRendering(32, true, true, false, true))
        assertEquals(DockRendering.LIQUID, dockRendering(35, true, true, true, true))
        assertEquals(DockRendering.OPAQUE, dockRendering(35, false, true, true, true))
        assertEquals(DockRendering.OPAQUE, dockRendering(35, true, false, true, true))
        assertEquals(DockRendering.OPAQUE, dockRendering(35, true, true, false, false))
    }

    @Test fun allTabsNavigateWithoutStartingCleanup() {
        var cleanups = 0
        render(actions = actions.copy(clean = { cleanups++ }, cleanScan = { cleanups++ }))
        listOf(1, 2, 3, 0).forEach { index ->
            compose.onNodeWithTag("dock-tab-$index").performClick()
            compose.onNodeWithTag("dock-tab-$index").assertIsSelected()
        }
        assertEquals(0, cleanups)
        save("dock-home-light")
    }

    @Test fun taskDetailsHideDockAndBackRestoresSelection() {
        render(page = 3)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("自动任务设置"))
        compose.onNodeWithText("自动任务设置").performScrollTo().performClick()
        compose.onNodeWithTag("luoshu-dock").assertDoesNotExist()
        save("dock-hidden-task-details")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("luoshu-dock").assertIsDisplayed()
        compose.onNodeWithTag("dock-tab-3").assertIsSelected()
    }

    @Test fun diagnosticsHideDockAndSystemBackRestoresIt() {
        render(page = 3)
        compose.onNodeWithText("白泽状态").performClick()
        compose.onNodeWithTag("luoshu-dock").assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("luoshu-dock").assertIsDisplayed()
        compose.onNodeWithTag("dock-tab-3").assertIsSelected()
    }

    @Test fun darkFallbackRemainsReadable() {
        render(dark = true)
        compose.onNodeWithTag("dock-shell-opaque").assertIsDisplayed()
        save("dock-home-dark")
    }

    @Test
    @Config(sdk = [26])
    fun oldAndroidCanRenderWithoutLoadingTheShader() {
        render()
        compose.onNodeWithTag("dock-shell-opaque").assertIsDisplayed()
        compose.onNodeWithTag("dock-tab-1").performClick()
        compose.onNodeWithTag("dock-tab-1").assertIsSelected()
    }

    @Test fun opaqueFallbackDoesNotExposeChangingBackgroundPixels() {
        var background by mutableStateOf(Color.Red)
        val appearance = AppearanceSettings(blurEnabled = false, monetEnabled = false, themeMode = ThemeMode.LIGHT)
        compose.setContent {
            BaiZeTheme(appearance) {
                CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
                    Box(Modifier.fillMaxSize().background(background)) {
                        LuoShuLiquidDock(0, List(4) { MiuixLiquidNavItem("项目$it", Icons.Rounded.Home) }, {},
                            hazeState = null, backdrop = null, effectsEnabled = false,
                            modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("dock-shell-opaque").fetchSemanticsNode().boundsInRoot
        val before = compose.runOnIdle { captureActivityContent(compose.activity) }
        compose.runOnIdle { background = Color.Blue }
        compose.waitForIdle()
        val after = compose.runOnIdle { captureActivityContent(compose.activity) }
        for (y in bounds.top.toInt() + 12 until bounds.bottom.toInt() - 12) {
            for (x in bounds.left.toInt() + 24 until bounds.right.toInt() - 24) {
                assertEquals("Backdrop leaked through dock at $x,$y", before.getPixel(x, y), after.getPixel(x, y))
            }
        }
    }

    private fun render(page: Int = 0, dark: Boolean = false, actions: DashboardActions = this.actions) {
        compose.setContent {
            BaiZeMiuixApp(DashboardUiState(ready = true, connected = true,
                storageTotal = 256L * 1024 * 1024 * 1024, storageUsed = 164L * 1024 * 1024 * 1024,
                storageFree = 92L * 1024 * 1024 * 1024, storagePercent = .64f),
                SchedulerUiState(), actions,
                AppearanceSettings(monetEnabled = false, blurEnabled = false,
                    themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT), initialPage = page)
        }
        compose.waitForIdle()
    }

    private fun save(name: String) {
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        val output = File("build/reports/ui-screenshots/$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val actions = DashboardActions(
        refresh = {}, clean = {}, organize = {}, scan = {}, apkScan = {}, cleanScan = {},
        dismissScan = {}, stop = {}, deep = {}, corpses = {}, audit = {},
        updateScheduler = {}, saveScheduler = {}, schedulerCommand = {}, clearHistory = {},
        clearRawLog = {}, reviewProtected = {}, whitelist = {}, resumableScan = {},
        theme = {}, reconnect = {}, resetScanPerformance = {}, crash = {},
    )
}
