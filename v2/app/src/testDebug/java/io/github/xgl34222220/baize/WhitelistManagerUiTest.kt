package io.github.xgl34222220.baize

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
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
class WhitelistManagerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var state by mutableStateOf(WhitelistUiState())
    private var saves = 0
    private val removals = mutableListOf<String>()
    private fun render(connected: Boolean = true) {
        state = WhitelistUiState(connected = connected, packagesLoaded = true, pathsLoaded = true,
            apps = listOf(WhitelistApp("one.app", "测试应用")),
            paths = listOf("/storage/emulated/0/Documents/Important"),
            draft = WhitelistDraft(setOf("one.app"), setOf("one.app")))
        val appearance = AppearanceSettings(monetEnabled = false, blurEnabled = false)
        compose.setContent {
            BaiZeTheme(appearance) { CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
                WhitelistManagerScreen(state, {}, {},
                    { state = state.copy(draft = state.draft.toggle(it)) },
                    { state = state.copy(draft = state.draft.copy(selected = emptySet())) },
                    { saves++ }, { removals += it })
            } }
        }
        compose.waitForIdle()
    }
    @Test fun removingAPathRequiresExactConfirmation() {
        render()
        compose.onNodeWithText("路径保护").performClick()
        compose.onNodeWithText("移除此路径保护").performClick()
        assertTrue(removals.isEmpty())
        compose.onNodeWithText("确认移除").performClick()
        assertEquals(listOf("/storage/emulated/0/Documents/Important"), removals)
        screenshot("whitelist-paths")
    }
    @Test fun cancelingPathDialogKeepsProtection() {
        render()
        compose.onNodeWithText("路径保护").performClick()
        compose.onNodeWithText("移除此路径保护").performClick()
        compose.onNodeWithText("保留").performClick()
        assertTrue(removals.isEmpty())
    }
    @Test fun uncheckingAnAppDoesNotSaveUntilRequested() {
        render()
        compose.onNodeWithText("保存应用白名单").assertIsNotEnabled()
        compose.onNodeWithContentDescription("保护测试应用").performClick()
        assertEquals(0, saves)
        compose.onNodeWithText("保存应用白名单").assertIsEnabled().performClick()
        assertEquals(1, saves)
        screenshot("whitelist-apps")
    }
    @Test fun disconnectedWhitelistCannotWrite() {
        render(connected = false)
        compose.onNodeWithContentDescription("保护测试应用").assertIsNotEnabled()
        compose.onNodeWithText("路径保护").performClick()
        compose.onNodeWithText("移除此路径保护").assertIsNotEnabled()
    }
    private fun screenshot(name: String) {
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        val target = File("build/reports/ui-screenshots/$name.png").apply { parentFile.mkdirs() }
        target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
