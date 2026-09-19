package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import io.github.xgl34222220.baize.ui.appearance.*
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
class StorageWorkbenchUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val records = listOf(
        StorageFileRecord(1, "uri1", "/storage/emulated/0/DCIM/Camera/海边日落.mp4", "海边日落.mp4", 1_840_000_000, 1773000000, "video/mp4"),
        StorageFileRecord(2, "uri2", "/storage/emulated/0/Download/旅行照片.zip", "旅行照片.zip", 420_000_000, 1772000000, "application/zip"),
        StorageFileRecord(3, "uri3", "/storage/emulated/0/Android/media/com.example.chat/视频.mp4", "视频.mp4", 230_000_000, 1771000000, "video/mp4", "聊天应用"),
        StorageFileRecord(4, "uri4", "/storage/emulated/0/Pictures/壁纸.jpg", "壁纸.jpg", 12_000_000, 1770000000, "image/jpeg"))

    @Test fun analysisCategoryOpensSelectableFiles() {
        var state by mutableStateOf(StorageToolsUiState(mode = StorageToolMode.ANALYSIS, records = records,
            buckets = storageBuckets(records), status = "存储分析完成", coverage = "已读取 4 个已索引文件，不含应用私有数据。", elapsedMs = 820))
        compose.setContent { BaiZeTheme(AppearanceSettings(themeMode = ThemeMode.LIGHT)) {
            StorageToolsScreen(state, {}, {}, { state = state.toggleSelection(it) }, {}, {},
                onCategory = { state = state.copy(category = it, selected = emptySet()) },
                onToggleAll = { state = state.toggleAllSelection() })
        } }
        save("v6-analysis-overview")
        compose.onNodeWithText("视频", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("海边日落.mp4").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("全选当前结果").performClick()
        compose.onNodeWithText("删除已选 2 项").assertIsDisplayed()
        assertEquals(setOf("uri1", "uri3"), state.selected)
        save("v6-analysis-video")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun largeFileSearchDarkNarrowKeepsFooter() {
        var state by mutableStateOf(StorageToolsUiState(records = records, buckets = storageBuckets(records),
            query = "海边", minimumBytes = 100 * StorageToolsViewModel.MIB, status = "大文件扫描完成"))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                BaiZeTheme(AppearanceSettings(themeMode = ThemeMode.DARK)) {
                    StorageToolsScreen(state, {}, {}, { state = state.toggleSelection(it) }, {}, {},
                        onToggleAll = { state = state.toggleAllSelection() })
                }
            }
        }
        compose.onNodeWithText("全选当前结果").performClick()
        compose.onNodeWithText("删除已选 1 项").assertIsDisplayed()
        compose.onNodeWithText("海边日落.mp4").performScrollTo()
        save("v6-large-dark-320")
    }

    @Test fun apkVersionFilterBulkSelection() {
        val old = ApkScanItem("地图_1.0.apk", 1, 85_000_000, 0, "/storage/emulated/0/Download/地图_1.0.apk",
            archive = ApkArchiveInfo("示例地图", "com.example.maps", "1.0", "2.0", ApkInstallStatus.OLDER))
        val fresh = old.copy(name = "地图_3.0.apk", uri = "uri2", archive = old.archive.copy(version = "3.0", status = ApkInstallStatus.NEWER))
        var state by mutableStateOf(ApkScanUiState(items = listOf(old, fresh), totalFiles = 2, totalBytes = 170_000_000,
            cleanReady = true, connected = true, phase = "安装包扫描完成"))
        compose.setContent { BaiZeTheme(AppearanceSettings(themeMode = ThemeMode.LIGHT)) {
            ApkScanScreen(state, {}, {}, {}, {}, {}, onToggleAll = { state = state.toggleAllSelection() },
                onFilter = { state = state.copy(filter = it, selected = emptySet()) })
        } }
        compose.onNodeWithText("低于已装版本").assertDoesNotExist()
        compose.onNodeWithContentDescription("筛选安装包").performScrollTo().performClick()
        compose.onNodeWithText("低于已装版本").performClick()
        compose.onNodeWithText("应用").performClick()
        compose.onNodeWithText("全选").performClick()
        assertEquals(setOf(old.uri), state.selected)
        compose.onNodeWithText("地图_1.0.apk").performScrollTo()
        save("v6-apk-version-filter")
    }
    @Test fun filterChangesWaitForApplyAndCancelPreservesSelection() {
        var state by mutableStateOf(StorageToolsUiState(records = records, buckets = storageBuckets(records),
            minimumBytes = 100 * StorageToolsViewModel.MIB, selected = setOf("uri1"), status = "扫描完成"))
        compose.setContent { BaiZeTheme(AppearanceSettings(themeMode = ThemeMode.LIGHT)) {
            StorageToolsScreen(state, {}, {}, { state = state.toggleSelection(it) }, {}, {},
                onCategory = { state = state.copy(category = it, selected = emptySet()) },
                onThreshold = { state = state.copy(minimumBytes = it, selected = emptySet()) },
                onSort = { state = state.copy(sort = it, selected = emptySet()) },
                onToggleAll = { state = state.toggleAllSelection() })
        } }
        compose.onNodeWithText("最新").assertDoesNotExist()
        compose.onNodeWithContentDescription("筛选文件").performScrollTo().performClick()
        compose.onNodeWithText("≥ 500 MB").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(100 * StorageToolsViewModel.MIB, state.minimumBytes)
        assertEquals(setOf("uri1"), state.selected)
        compose.onNodeWithContentDescription("筛选文件").performScrollTo().performClick()
        compose.onNodeWithText("≥ 500 MB").performScrollTo().performClick()
        compose.onNodeWithText("应用").performClick()
        assertEquals(500 * StorageToolsViewModel.MIB, state.minimumBytes)
        assertTrue(state.selected.isEmpty())
        compose.onNodeWithText("全选当前结果").performClick()
        assertEquals(setOf("uri1"), state.selected)
        save("v6-filtered-selection")
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        val file = File("build/reports/ui-screenshots/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
