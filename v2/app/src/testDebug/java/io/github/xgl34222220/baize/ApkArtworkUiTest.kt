package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApkArtworkUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var cleanCalls = 0
    private val icon get() = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(62, 123, 250)) }
    private fun item(index: Int) = ApkScanItem("归档-$index.apk", 1, 12_345_678, 0,
        "/synthetic/download/归档-$index.apk", "content://media/$index", modifiedSeconds = 42)
    private fun parsed() = ApkArchiveInfo("示例归档应用", "synthetic.archive", "1.2.3", "1.0.0", ApkInstallStatus.NEWER,
        iconBitmap = icon, parseStatus = ApkArchiveParseStatus.PARSED)

    @Test fun archiveArtworkNamesAndVersionsAreVisibleAndDetailsDoNotSelectOrDelete() {
        val first = item(1).copy(archive = parsed())
        val failed = item(2).copy(archive = ApkArchiveInfo(parseStatus = ApkArchiveParseStatus.FAILED,
            failureReason = ApkArchiveFailure.INVALID_ARCHIVE))
        var state by mutableStateOf(ready(listOf(first, failed)))
        render { ApkScanScreen(state, {}, {}, { cleanCalls++ }, {}, {},
            onToggle = { uri -> state = state.copy(selected = state.selected + uri) }) }
        compose.onNodeWithTag("apk-results-list").performScrollToNode(hasText("示例归档应用"))
        compose.onNodeWithText("示例归档应用").assertIsDisplayed()
        compose.onNodeWithContentDescription("来自安装包的应用图标", useUnmergedTree = true).assertIsDisplayed()
        val bounds = compose.onNodeWithTag("apk-artwork:${first.uri}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(48f, bounds.width, .1f); assertEquals(48f, bounds.height, .1f)
        compose.onNodeWithText(first.name).performClick()
        compose.onNodeWithText("安装包详情").assertIsDisplayed()
        compose.onNodeWithText("完整路径\n${first.samplePath}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        assertTrue(state.selected.isEmpty()); assertEquals(0, cleanCalls)
        compose.onNodeWithContentDescription("选择安装包${first.name}").performClick()
        assertEquals(setOf(first.uri), state.selected)
        compose.onNodeWithTag("apk-results-list").performScrollToNode(hasText(failed.name))
        compose.onNodeWithContentDescription("安装包默认图标", useUnmergedTree = true).assertIsDisplayed()
        save("apk-archive-artwork-light")
    }

    @Test fun aThousandArchivesDecodeOnlyComposedRows() {
        var reads = 0
        val state = ready((0 until 1000).map(::item))
        render { ApkScanScreen(state, {}, {}, {}, {}, {}, loadArchive = { reads++; parsed() }) }
        compose.waitUntil(5_000) { reads > 0 }
        assertTrue("Offscreen archives must not be decoded eagerly: $reads", reads < 30)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun darkLargeTextKeepsLongArchiveDetailsAndSelectionReachable() {
        val first = item(1).copy(name = "W".repeat(251) + ".apk", archive = parsed(),
            samplePath = "/synthetic/" + "长目录/".repeat(20) + "W".repeat(251) + ".apk")
        val state = ready(listOf(first)).copy(selected = setOf(first.uri))
        render(dark = true, fontScale = 1.5f) { ApkScanScreen(state, {}, {}, { cleanCalls++ }, {}, {}) }
        compose.onNodeWithText("清理已选 1 个安装包").assertIsDisplayed()
        compose.onNodeWithTag("apk-results-list").performScrollToNode(hasText(first.name))
        save("apk-archive-artwork-dark-large-font")
        compose.onNodeWithText(first.name).performClick()
        compose.onNodeWithText("完成").assertIsDisplayed()
        compose.onNodeWithText("完整路径\n${first.samplePath}").performScrollTo().assertIsDisplayed()
        save("apk-archive-details-dark-large-font")
        compose.onNodeWithText("完成").performClick()
        assertEquals(0, cleanCalls)
    }

    private fun ready(items: List<ApkScanItem>) = ApkScanUiState(connected = true, cleanReady = true,
        phase = "安装包扫描完成", items = items, totalFiles = items.size.toLong(), totalBytes = items.sumOf { it.bytes })
    private fun render(dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val appearance = AppearanceSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
            monetEnabled = false, blurEnabled = false, glassEnabled = false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalAppearanceSettings provides appearance) {
                BaiZeTheme(appearance) { content() }
            }
        }
    }
    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView
            if (dialog == null) captureActivityContent(compose.activity)
            else Bitmap.createBitmap(dialog.width, dialog.height, Bitmap.Config.ARGB_8888).also { dialog.draw(Canvas(it)) }
        }
        File("build/reports/ui-screenshots/$name.png").apply {
            parentFile!!.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
