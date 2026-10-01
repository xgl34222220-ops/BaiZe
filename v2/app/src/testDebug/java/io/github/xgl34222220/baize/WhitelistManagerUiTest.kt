package io.github.xgl34222220.baize

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
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
class WhitelistManagerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var state by mutableStateOf(WhitelistUiState())
    private var saves = 0
    private val removals = mutableListOf<String>()
    private val additions = mutableListOf<String>()
    private val restoration = StateRestorationTester(compose)
    private fun render(connected: Boolean = true, large: Boolean = false) {
        state = WhitelistUiState(connected = connected, packagesLoaded = true, pathsLoaded = true,
            apps = listOf(WhitelistApp("one.app", "测试应用")),
            paths = listOf("/storage/emulated/0/Documents/Important"),
            draft = WhitelistDraft(setOf("one.app"), setOf("one.app")))
        val appearance = AppearanceSettings(monetEnabled = false, blurEnabled = false,
            themeMode = if (large) ThemeMode.DARK else ThemeMode.LIGHT)
        restoration.setContent {
            val density = LocalDensity.current
            BaiZeTheme(appearance) { CompositionLocalProvider(LocalAppearanceSettings provides appearance,
                LocalDensity provides Density(density.density, if (large) 1.5f else 1f)) {
                WhitelistManagerScreen(state, {}, {},
                    {
                        state = state.copy(draft = state.draft.toggle(it))
                        saves++
                    },
                    {
                        state = state.copy(draft = state.draft.copy(selected = emptySet()))
                        saves++
                    },
                    { removals += it },
                    { additions += it; state = state.copy(saving = true, addingPath = true) })
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
    @Test fun uncheckingAnAppSavesImmediately() {
        render()
        compose.onNodeWithText("保存应用白名单").assertDoesNotExist()
        compose.onNodeWithContentDescription("保护测试应用").performClick()
        assertEquals(1, saves)
        compose.onNodeWithText("勾选或取消后立即保存；不会删除应用或文件。").assertIsDisplayed()
        screenshot("whitelist-apps")
    }
    @Test fun disconnectedWhitelistCannotWrite() {
        render(connected = false)
        compose.onNodeWithContentDescription("保护测试应用").assertIsNotEnabled()
        compose.onNodeWithText("路径保护").performClick()
        compose.onNodeWithText("移除此路径保护").assertIsNotEnabled()
        compose.onNodeWithText("添加路径").assertIsNotEnabled()
    }

    @Test fun manualPathAdditionValidatesInputAndWaitsForConfirmedSave() {
        render()
        compose.onNodeWithText("路径保护").performClick()
        compose.onNodeWithText("添加路径").performClick()
        compose.onNodeWithText("添加保护").assertIsNotEnabled()
        compose.onNodeWithText("完整路径").performTextInput("relative/path")
        compose.onNodeWithText("添加保护").assertIsNotEnabled()
        compose.onNodeWithText("完整路径").performTextReplacement("/storage/emulated/0/Download/Keep")
        compose.onNodeWithText("添加保护").performClick()
        assertEquals(listOf("/storage/emulated/0/Download/Keep"), additions)
        compose.onNodeWithText("添加保护路径").assertIsDisplayed()
        compose.onNodeWithText("添加保护").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(saving = false, addingPath = false, pathSaveRevision = 1) }
        compose.onNodeWithText("添加保护路径").assertDoesNotExist()
    }

    @Test fun failedSaveAndRecreationKeepTypedPathForExplicitRetry() {
        render()
        compose.onNodeWithText("路径保护").performClick()
        compose.onNodeWithText("添加路径").performClick()
        val path = "/storage/emulated/0/Download/Important.apk"
        compose.onNodeWithText("完整路径").performTextInput(path)
        compose.onNodeWithText("添加保护").performClick()
        compose.runOnIdle { state = state.copy(saving = false, addingPath = false, pathSaveError = "合成保存失败，原保护保留") }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("完整路径").assertTextContains(path)
        compose.onNodeWithText("合成保存失败，原保护保留").performScrollTo().assertIsDisplayed()
        assertEquals(1, additions.size)
        compose.onNodeWithText("添加保护").performClick()
        assertEquals(listOf(path, path), additions)
    }

    @Test fun wholeStorageScopeRequiresExplicitAcknowledgment() {
        render()
        compose.onNodeWithText("路径保护").performClick()
        compose.onNodeWithText("添加路径").performClick()
        compose.onNodeWithText("完整路径").performTextInput("/sdcard")
        compose.onNodeWithText("添加保护").assertIsNotEnabled()
        compose.onNodeWithText("我确认保护整个存储目录及其全部内容").performScrollTo().performClick()
        compose.onNodeWithText("添加保护").assertIsEnabled()
        compose.onNodeWithText("返回").performClick()
        assertTrue(additions.isEmpty())
    }

    @Test fun legacyRecordsAndEquivalentPathsAreExplainedBeforeRemoval() {
        render()
        compose.runOnIdle { state = state.copy(paths = listOf("/storage/emulated/0/Download"),
            legacyPaths = setOf("/storage/emulated/0/Download"), rootPaths = setOf("/storage/emulated/0/Download"),
            pathAliases = mapOf("/storage/emulated/0/Download" to listOf("/sdcard/Download", "/data/media/0/Download"))) }
        compose.onNodeWithText("路径保护").performClick()
        compose.onNodeWithText("清理服务与旧版设置中的同一路径").assertIsDisplayed()
        compose.onNodeWithText("移除此路径保护").performClick()
        compose.onNodeWithText("/sdcard/Download\n/data/media/0/Download").performScrollTo().assertIsDisplayed()
        assertTrue(removals.isEmpty())
        compose.onNodeWithText("保留").performClick()
        assertTrue(removals.isEmpty())
        screenshot("whitelist-legacy-path-management")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w740dp-h320dp-mdpi")
    fun landscapeLargeTextKeepsPathInputScopeAndActionsReachable() {
        render(large = true)
        compose.onNodeWithText("路径保护").performClick()
        compose.onNodeWithTag("whitelist-list").performScrollToNode(hasText("添加路径"))
        compose.onNodeWithText("添加路径").performClick()
        val path = "/storage/emulated/0/Download/" + "保留的长路径/".repeat(8) + "重要文件.apk"
        compose.onNodeWithText("完整路径").performScrollTo().performTextInput(path)
        compose.onNodeWithText("保护范围\n$path").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("添加保护").assertIsDisplayed().assertIsEnabled()
        screenshot("whitelist-add-path-landscape-large-font")
        compose.onNodeWithText("返回").performClick()
        assertTrue(additions.isEmpty())
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
