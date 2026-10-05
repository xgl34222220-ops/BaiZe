package io.github.xgl34222220.baize

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApkProtectionManagementUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val parent = "/storage/emulated/10/Download"
    private val file = "$parent/app.apk"
    private val guard = ApkDeletionGuard(setOf("/storage/emulated/10"), "/storage/emulated/10")
    private val empty = ApkProtectionRules(emptySet(), emptySet())
    private var managed = 0
    private var trash = 0
    private val restoration = StateRestorationTester(compose)

    private fun content(body: @Composable () -> Unit) {
        val appearance = AppearanceSettings(monetEnabled = false, blurEnabled = false)
        restoration.setContent {
            BaiZeTheme(appearance) { CompositionLocalProvider(LocalAppearanceSettings provides appearance) { body() } }
        }
    }
    private fun panel(details: ApkProtectionDetails, historical: Boolean = false) = content {
        ApkProtectionResultPanel(details, historical, onManage = { managed++ }, onTrash = { trash++ })
    }

    @Test fun protectedFileShowsExactDetailsAndDirectManageAction() {
        val details = guard.protectionDetails(file, ApkProtectionState.KnownRoot(empty.copy(paths = setOf("/sdcard/Download", file))))
        panel(details)
        compose.onNodeWithText("管理此文件保护").performClick()
        assertEquals(1, managed)
        compose.onNodeWithText("查看保护原因").performClick()
        compose.onNodeWithText(details.explanation).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
    }

    @Test fun internalTrashRoutesToTrashWithoutOfferingWhitelistCancellation() {
        panel(ApkProtectionDetails(internalTrash = true))
        compose.onNodeWithText("管理此文件保护").assertDoesNotExist()
        compose.onNodeWithText("到回收站处理").performClick()
        assertEquals(1, trash)
        assertEquals(0, managed)
    }

    @Test fun unknownAndHistoricalResultsNeverClaimRevalidatedProtection() {
        panel(ApkProtectionDetails(unavailableReason = "保护服务断开，请重连后重新扫描。"), historical = true)
        compose.onNodeWithText("管理此文件保护").assertDoesNotExist()
        compose.onNodeWithText("查看保护原因").performClick()
        compose.onNodeWithText("以下为上次核对结果。请重新扫描确认当前规则。").assertIsDisplayed()
        compose.onNodeWithText("保护服务断开，请重连后重新扫描。").assertIsDisplayed()
    }

    @Test fun focusedExactRemovalKeepsParentAndHidesUnrelatedRulesAndGlobalClear() {
        var rules = empty.copy(paths = setOf(parent, file, "/storage/emulated/10/Documents"))
        fun focused() = WhitelistUiState(connected = true, pathsLoaded = true, packagesLoaded = true,
            focusFile = file).withProtection(WhitelistProtectionSnapshot(rules, empty, "/storage/emulated/10"), guard)
        var state by mutableStateOf(focused())
        val removals = mutableListOf<String>()
        content { WhitelistManagerScreen(state, {}, {}, {}, { error("Must never clear all") }, { path ->
            removals += path
            rules = rules.copy(paths = rules.paths - path)
            state = focused()
        }) }
        compose.onNodeWithTag("whitelist-list").performScrollToNode(hasTestTag("whitelist-path:$file"))
        compose.onNode(hasText("移除此路径保护") and hasAnyAncestor(hasTestTag("whitelist-path:$file"))).performClick()
        compose.onNodeWithText("保留").performClick()
        assertTrue(removals.isEmpty())
        compose.onNode(hasText("移除此路径保护") and hasAnyAncestor(hasTestTag("whitelist-path:$file"))).performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("确认移除").performClick()
        assertEquals(listOf(file), removals)
        assertEquals(setOf(parent, "/storage/emulated/10/Documents"), rules.paths)
        compose.onNodeWithTag("whitelist-list").performScrollToNode(hasTestTag("whitelist-path:$parent"))
        compose.onNodeWithText("此文件受该父目录保护，包含目录内全部子项").assertIsDisplayed()
        compose.onNodeWithTag("whitelist-path:/storage/emulated/10/Documents").assertDoesNotExist()
        compose.onNodeWithText("应用保护").performClick()
        compose.onNodeWithText("取消全部应用保护").assertDoesNotExist()
    }

    @Test fun packageOnlyFocusOpensMatchingAppInsteadOfAllApps() {
        val target = "/storage/emulated/10/Android/data/example.app/cache/test.apk"
        val rules = empty.copy(packages = setOf("example.app", "unrelated.app"))
        val state = WhitelistUiState(connected = true, pathsLoaded = true, packagesLoaded = true,
            focusFile = target, apps = listOf(WhitelistApp("example.app", "对应应用"), WhitelistApp("unrelated.app", "其它应用")),
            draft = WhitelistDraft(rules.packages, rules.packages))
            .withProtection(WhitelistProtectionSnapshot(rules, empty, "/storage/emulated/10"), guard)
        var toggled: String? = null
        content { WhitelistManagerScreen(state, {}, {}, { toggled = it }, {}, {}) }
        compose.onNodeWithTag("whitelist-list").performScrollToNode(hasContentDescription("保护对应应用"))
        compose.onNodeWithContentDescription("保护对应应用").performClick()
        assertEquals("example.app", toggled)
        compose.onNodeWithContentDescription("保护其它应用").assertDoesNotExist()
        compose.onNodeWithText("取消全部应用保护").assertDoesNotExist()
    }
}
