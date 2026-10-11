package io.github.xgl34222220.baize

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.xgl34222220.baize.ui.appearance.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 3.0.1 真机反馈：大文件结果「已保留 · 命中保护名单」没有原因也无法解除。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
class ProtectedRowUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val record = StorageFileRecord(7, "content://media/external/file/7",
        "/storage/emulated/0/Download/LuoShu-engine-25060RK16C_-20261005-000107.zip",
        "LuoShu-engine-25060RK16C_-20261005-000107.zip", 133_000_000, 1772000000, "application/zip").withVerifiedStorageIdentity()
    private val userRule = StorageDeleteOutcome(ApkIndexedDeleteResult.PROTECTED, protection = ApkProtectionDetails(
        paths = listOf(ApkPathProtectionMatch("/sdcard", "/storage/emulated/0", true))))

    @Test fun protectedRowNamesTheRuleAndDetailOffersOneOffOverride() {
        var allowed = 0
        var removed = 0
        var details by mutableStateOf(false)
        val state = StorageToolsUiState(records = listOf(record), buckets = storageBuckets(listOf(record)),
            minimumBytes = 100 * StorageToolsViewModel.MIB, outcomes = mapOf(record.uri to userRule),
            status = StorageTrashSummary.status(1, 0, "", 1, 0, 0, 0, false))
        compose.setContent { BaiZeTheme(AppearanceSettings(themeMode = ThemeMode.LIGHT)) {
            StorageToolsScreen(state, {}, {}, {}, {}, {}, onOpen = { details = true })
            if (details) StorageFileDialog(record, userRule, false, "", { details = false }, {}, {}, {},
                onExclude = { removed++ }, onAllowOnce = { allowed++ })
        } }
        compose.onNodeWithText("没有可移动的文件：1 项受保护，点文件查看命中的规则").assertExists()
        compose.onAllNodesWithText("命中保护规则：路径 /sdcard", substring = true, useUnmergedTree = true).onFirst().assertExists()
        // 受保护的行不能直接勾选，先看规则。
        compose.onAllNodes(isToggleable()).assertAll(isNotEnabled())
        compose.onNodeWithText(record.name).performScrollTo().performClick()
        compose.onNodeWithTag("protection-allow-once", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("protection-remove-rule", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals(1, allowed)
        assertEquals(1, removed)
        // 已命中保护时不再显示「加入白名单」。
        compose.onNodeWithText("加入白名单，保护此文件").assertDoesNotExist()
    }

    @Test fun internalTrashIsSystemLockedWithoutOverride() {
        val trash = StorageDeleteOutcome(ApkIndexedDeleteResult.PROTECTED, protection = ApkProtectionDetails(internalTrash = true))
        compose.setContent { BaiZeTheme(AppearanceSettings(themeMode = ThemeMode.LIGHT)) {
            StorageFileDialog(record, trash, false, "", {}, {}, {}, {}, onExclude = {}, onAllowOnce = {})
        } }
        compose.onAllNodesWithText("系统级保护，无法解除", substring = true, useUnmergedTree = true).onFirst().assertExists()
        compose.onNodeWithTag("protection-allow-once", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("protection-remove-rule", useUnmergedTree = true).assertDoesNotExist()
    }
}
