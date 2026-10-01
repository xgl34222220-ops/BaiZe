package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.components.BaiZeDialog
import io.github.xgl34222220.baize.ui.components.BaiZeDialogButton
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DialogContentReachabilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val filename = "W".repeat(251) + ".tmp"
    private val fullPath = "/synthetic/" + "长路径核对/".repeat(18) + filename

    @Test fun multiLineTitleAndLongPathScrollWithActionsFixedInPortrait() = checkDialog("portrait")

    @Test fun selectedContentReviewCannotConfirmWhileReadingAndCancellationKeepsFiles() {
        var open by mutableStateOf(true)
        var preparing by mutableStateOf(true)
        var confirmations = 0
        render { if (open) IndexedCleanupReviewDialog(preparing, 3,
            if (preparing) "正在核对 2 / 3 个文件，仅核对所选内容…" else "已核对 3 个文件的当前内容。确认后内容再变化会保留。",
            { confirmations++; open = false }, { open = false }) }
        compose.onNodeWithText("确认删除").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsDisplayed()
        save("indexed-content-review-preparing-dark-large-font")
        compose.runOnIdle { preparing = false }
        compose.onNodeWithText("确认删除").assertIsEnabled()
        save("indexed-content-review-ready-dark-large-font")
        compose.onNodeWithText("取消").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertEquals(0, confirmations)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w740dp-h320dp-land-mdpi")
    fun emptyContentReviewCannotConfirmInLandscape() {
        render { IndexedCleanupReviewDialog(false, 0, "3 个文件无法核对，已保留并取消勾选。" + "核对说明。".repeat(40), {}, {}) }
        compose.onNodeWithText("确认删除").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsDisplayed()
        save("indexed-content-review-empty-landscape-dark-large-font")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w740dp-h320dp-land-mdpi")
    fun multiLineTitleAndLongPathScrollWithActionsFixedInLandscape() = checkDialog("landscape")

    private fun checkDialog(name: String) {
        var open by mutableStateOf(true)
        var confirmations = 0
        render {
            if (open) BaiZeDialog(onDismissRequest = { open = false },
                title = { Text("核对文件\n$filename\n完整标题末尾") },
                text = { Column { Text(fullPath); Text("正文末尾核对标记") } },
                confirmButton = { BaiZeDialogButton({ confirmations++ }) { Text("确认") } },
                dismissButton = { BaiZeDialogButton({ open = false }) { Text("返回核对") } })
        }
        compose.onNodeWithText("返回核对").assertIsDisplayed()
        compose.onNodeWithText(fullPath).assertExists()
        compose.onNodeWithText("正文末尾核对标记").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("确认").assertIsDisplayed()
        save("dialog-long-content-$name")
        compose.onNodeWithText("返回核对").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertEquals(0, confirmations)
    }

    @Test fun organizerLongNameKeepsBothFullPathsReachableWithoutMovingFiles() {
        val destination = "/synthetic/归类目标/$filename"
        val item = OrganizerPreviewItem("long", filename, "文档", 1024, "测试来源", fullPath, destination)
        val state = FileOrganizerUiState(connected = true, previewReady = true, snapshotId = "synthetic",
            expiresAtRealtime = SystemClock.elapsedRealtime() + 600_000, items = listOf(item), totalFound = 1)
        var moves = 0
        render {
            FileOrganizerScreen(state, FileOrganizerScheduleSettings(), "", onBack = {}, onOneTap = {},
                onUndo = {}, onStop = {}, onScheduleChange = {}, onSaveSchedule = {}, onApply = { moves++ })
        }
        compose.onNodeWithTag("organizer-preview-list")
            .performScrollToNode(hasTestTag("organizer-category:文档"))
        compose.onNodeWithTag("organizer-category:文档").performClick()
        compose.onNodeWithTag("organizer-preview-list").performScrollToNode(hasText(filename))
        compose.onNodeWithText(filename).performClick()
        compose.onNode(hasText("完成") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText("来源：测试来源\n$fullPath").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("归类到：\n$destination").performScrollTo().assertIsDisplayed()
        save("organizer-long-name-paths-dark-large-font")
        compose.onNode(hasText("完成") and hasAnyAncestor(isDialog())).performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertEquals(0, moves)
    }

    private fun render(content: @Composable () -> Unit) {
        val appearance = AppearanceSettings(themeMode = ThemeMode.DARK, monetEnabled = false,
            blurEnabled = false, glassEnabled = false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f),
                LocalAppearanceSettings provides appearance) {
                BaiZeTheme(appearance) { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val view = requireNotNull(ShadowDialog.getLatestDialog()?.window?.decorView)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File("build/reports/ui-screenshots/$name.png").apply {
            parentFile!!.mkdirs()
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
