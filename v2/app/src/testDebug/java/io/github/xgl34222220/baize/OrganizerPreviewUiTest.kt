package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrganizerPreviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var state by mutableStateOf(FileOrganizerUiState())
    private var scans = 0
    private var moves = 0
    private val document = OrganizerPreviewItem("document", "项目说明与交付记录.pdf", "文档", 4_096,
        "浏览器下载", "内部存储/Download/项目说明与交付记录.pdf", "内部存储/BaiZe归类/文档/项目说明与交付记录.pdf")
    private val photo = OrganizerPreviewItem("photo", "海边照片.jpg", "图片", 8_192,
        "蓝牙接收", "内部存储/Bluetooth/海边照片.jpg", "内部存储/BaiZe归类/图片/海边照片.jpg")
    private fun ready() = FileOrganizerUiState(connected = true, snapshotId = "preview", previewReady = true,
        expiresAtRealtime = SystemClock.elapsedRealtime() + 1_800_000, items = listOf(document, photo), totalFound = 2,
        status = "找到 2 个文件。先核对来源和去向，再选择需要移动的内容")

    @Test fun scanAndReviewDoNotMoveUntilTheExplicitConfirmation() {
        render(ready())
        compose.onNodeWithText("归类已选 0 个文件").assertIsNotEnabled()
        compose.onNodeWithTag("organizer-preview-list").performScrollToNode(hasContentDescription("选择文档类别"))
        compose.onNodeWithContentDescription("选择文档类别").performClick()
        compose.onNodeWithText("归类已选 1 个文件").performClick()
        compose.onNodeWithText("确认移动 1 个文件").assertIsDisplayed()
        assertEquals(0, moves)
        compose.onNodeWithText("返回核对").performClick()
        assertEquals(setOf(document.id), state.selectedIds)
        assertEquals(0, moves)
        compose.onNodeWithText("归类已选 1 个文件").performClick()
        compose.onNodeWithText("确认归类").performClick()
        assertEquals(1, moves)
        save("selected-light")
    }

    @Test fun changedPlanInvalidatesAnOpenConfirmation() {
        render(ready().copy(selectedIds = setOf(document.id)))
        compose.onNodeWithText("归类已选 1 个文件").performClick()
        compose.runOnIdle { state = state.copy(snapshotId = "replacement") }
        compose.onNodeWithText("确认归类").assertIsNotEnabled()
        assertEquals(0, moves)
    }

    @Test fun restoringReviewKeepsTheScanActionDisabled() {
        render(FileOrganizerUiState(restoringReview = true, status = "正在恢复归类记录…"))
        compose.onNodeWithText("正在恢复记录").assertIsNotEnabled()
        assertEquals(0, scans)
        assertEquals(0, moves)
    }

    @Test fun initialActionOnlyRequestsAScan() {
        render(FileOrganizerUiState(connected = true))
        compose.onNodeWithText("扫描可归类文件").performClick()
        assertEquals(1, scans)
        assertEquals(0, moves)
    }

    @Test fun incompletePreviewKeepsFilesReadableButDisablesSelection() {
        render(ready().copy(previewReady = false, totalFound = 10, status = "归类预览读取未完成"))
        compose.onNodeWithText("重新扫描后归类").assertIsDisplayed()
        compose.onNodeWithTag("organizer-preview-list").performScrollToNode(hasContentDescription("选择文档类别"))
        compose.onNodeWithContentDescription("选择文档类别").assertIsNotEnabled()
        compose.onNodeWithTag("organizer-category:文档").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithTag("organizer-preview-list").performScrollToNode(hasContentDescription("选择文件${document.name}"))
        compose.onNodeWithContentDescription("选择文件${document.name}").assertIsNotEnabled()
        compose.onNodeWithText(document.name).performClick()
        compose.onNodeWithText("来源：${document.sourceGroup}\n${document.source}").assertIsDisplayed()
        compose.onNodeWithText("归类到：\n${document.destination}").assertIsDisplayed()
        assertEquals(0, moves)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun darkLargeFontKeepsPathsAndMoveActionReachable() {
        render(ready().copy(selectedIds = setOf(document.id), truncated = true), dark = true, fontScale = 1.5f)
        compose.onNodeWithText("归类已选 1 个文件").assertIsDisplayed()
        compose.onNodeWithTag("organizer-preview-list").performScrollToNode(hasTestTag("organizer-category:文档"))
        compose.onNodeWithTag("organizer-category:文档").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithTag("organizer-preview-list").performScrollToNode(hasContentDescription("选择文件${document.name}"))
        compose.onNodeWithText(document.name).assertIsDisplayed()
        save("preview-dark-large-font")
        compose.onNodeWithText("归类已选 1 个文件").performClick()
        compose.onNodeWithText("返回核对").assertIsDisplayed()
        compose.onNodeWithText("确认归类").assertIsDisplayed()
    }

    private fun render(initial: FileOrganizerUiState, dark: Boolean = false, fontScale: Float = 1f) {
        state = initial
        val appearance = AppearanceSettings(monetEnabled = false, blurEnabled = false,
            themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalAppearanceSettings provides appearance) {
                BaiZeTheme(appearance) {
                    FileOrganizerScreen(state, FileOrganizerScheduleSettings(), {}, { scans++ }, {}, {}, {},
                        onToggleItem = { id -> toggle(setOf(id)) },
                        onToggleCategory = { category -> toggle(state.items.filter { it.category == category }.mapTo(linkedSetOf()) { it.id }) },
                        onToggleAll = { toggle(state.items.mapTo(linkedSetOf()) { it.id }) }, onApply = { moves++ })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun toggle(ids: Set<String>) {
        val selected = state.selectedIds.toMutableSet()
        if (selected.containsAll(ids)) selected.removeAll(ids) else selected.addAll(ids)
        state = state.copy(selectedIds = selected)
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        val target = File("build/reports/ui-screenshots/organizer-$name.png").apply { parentFile.mkdirs() }
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
