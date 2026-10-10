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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 2026-10 UI 重设计的截图覆盖：根目录整理与存储分析（常规 / 320dp + 1.3 倍字号 / 深色）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RedesignVisualReviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val installed = setOf("com.tencent.mm", "com.sina.weibo")
    private fun dir(name: String, empty: Boolean = false, bytes: Long = 10) =
        RootEntry(name, true, empty, if (empty) 0 else 3, if (empty) 0 else bytes)
    private val rootState = RootTidyUiState(
        status = "已检查 6 个根目录项目",
        reviews = listOf(dir("baidu", bytes = 48_000_000), dir("empty", empty = true), dir("Random", bytes = 2_400_000),
            dir("tencent", bytes = 120_000_000), dir("DCIM", bytes = 3_200_000_000), dir("Download", bytes = 900_000_000))
            .map { RootDirectoryOrganizer.classify(it, installed, RootTidyRules()) },
        selected = setOf("empty"))

    private val records = listOf(
        StorageFileRecord(1, "uri1", "/storage/emulated/0/DCIM/Camera/海边日落.mp4", "海边日落.mp4", 1_840_000_000, 1773000000, "video/mp4"),
        StorageFileRecord(2, "uri2", "/storage/emulated/0/Download/旅行照片.zip", "旅行照片.zip", 420_000_000, 1772000000, "application/zip"),
        StorageFileRecord(4, "uri4", "/storage/emulated/0/Pictures/壁纸.jpg", "壁纸.jpg", 12_000_000, 1770000000, "image/jpeg"))
        .map { it.withVerifiedStorageIdentity() }

    @Test fun rootTidyLight() = renderRoot("redesign-root-tidy-light")

    @Test fun rootTidyDark() = renderRoot("redesign-root-tidy-dark", dark = true)

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun rootTidyNarrowLargeFont() = renderRoot("redesign-root-tidy-narrow-large-font", fontScale = 1.3f)

    @Test fun storageAnalysisLight() = renderStorage("redesign-storage-analysis-light")

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun storageAnalysisNarrowLargeFont() = renderStorage("redesign-storage-analysis-narrow-large-font", fontScale = 1.3f)

    @Test fun storageViewSwitcherOpensAsSheet() {
        renderStorage("redesign-storage-analysis-light")
        compose.onNodeWithText("视图：存储分析").performClick()
        compose.onNodeWithText("切换视图").assertIsDisplayed()
        save("redesign-storage-view-sheet")
    }

    private fun renderRoot(name: String, dark: Boolean = false, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                BaiZeTheme(AppearanceSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, monetEnabled = false)) {
                    RootTidyScreen(rootState, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> "" }, {}, {})
                }
            }
        }
        compose.onNodeWithText("根目录整理").assertIsDisplayed()
        save(name)
    }

    private fun renderStorage(name: String, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                BaiZeTheme(AppearanceSettings(themeMode = ThemeMode.LIGHT, monetEnabled = false)) {
                    StorageToolsScreen(StorageToolsUiState(mode = StorageToolMode.ANALYSIS, records = records,
                        buckets = storageBuckets(records), status = "存储分析完成",
                        coverage = "已读取 3 个已索引文件，不含应用私有数据。", elapsedMs = 820), {}, {}, {}, {}, {})
                }
            }
        }
        save(name)
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        val file = File("build/reports/ui-screenshots/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
