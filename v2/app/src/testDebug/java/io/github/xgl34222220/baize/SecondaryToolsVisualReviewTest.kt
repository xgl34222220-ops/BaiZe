package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Render the real standalone tools with sample state, without binding Root services. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecondaryToolsVisualReviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun instantCacheSelection() {
        var requested = emptySet<String>()
        render("instant-cache") {
            InstantCacheScreen(cacheState, {}, {}, {}, {}, { requested = it }, {})
        }
        compose.onNodeWithText("清除所选缓存").performClick()
        assertEquals("The confirmation must remain before clearing caches", emptySet<String>(), requested)
        compose.onNodeWithText("清除缓存").performClick()
        assertEquals(cacheState.selected, requested)
        compose.onNodeWithText("系统").performClick()
        compose.onNodeWithText("示例系统工具").assertIsDisplayed()
    }

    @Test fun instantCacheFailureDark() = render("instant-cache-failure-dark", dark = true) {
        InstantCacheScreen(cacheState.copy(status = "部分应用未允许清除缓存，请查看执行结果。",
            lastResult = InstantCacheResult(succeeded = 1, failed = 2, cancelled = false)), {}, {}, {}, {}, {}, {})
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun instantCacheLargeFont() = render("instant-cache-narrow-large-font", fontScale = 1.3f) {
        InstantCacheScreen(cacheState, {}, {}, {}, {}, {}, {})
    }

    @Test fun resumablePlanReady() {
        var cleanRequests = 0
        render("resumable-plan") {
            ResumeSmartScreen(planState, {}, {}, { cleanRequests++ }, {}, {})
        }
        compose.onNodeWithText("清理这 48 项").performClick()
        assertEquals("The plan action must use the already scanned plan", 1, cleanRequests)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun resumableInterruptedDarkLargeFont() = render("resumable-interrupted-dark-large-font", dark = true, fontScale = 1.3f) {
        ResumeSmartScreen(planState.copy(
            resumable = true, totalSafe = 12, runCount = 1, phase = "清理已暂停，剩余 12 项可以继续处理。",
            processedCandidates = 36, cleanedCandidates = 34, protectedCandidates = 2,
            deletedBytes = 428L * 1024 * 1024, failures = 2, failedCandidates = 2
        ), {}, {}, {}, {}, {})
    }

    @Test fun resumableRunning() {
        var stopRequests = 0
        render("resumable-running") {
            ResumeSmartScreen(planState.copy(running = true, operation = "clean", progressCurrent = 16,
                progressTotal = 48, phase = "正在清理应用缓存…"), {}, {}, {}, { stopRequests++ }, {})
        }
        compose.onNodeWithText("停止并保存").performClick()
        assertEquals(1, stopRequests)
    }

    private val cacheState = InstantCacheUiState(
        connected = true, loading = false, status = "已读取 206 个用户应用 · 72 个系统应用",
        apps = listOf(
            InstantCacheApp("example.browser", "示例浏览器", false),
            InstantCacheApp("example.video", "示例视频", false),
            InstantCacheApp("example.download", "示例下载工具与文件管理器", false),
            InstantCacheApp("example.chat", "示例聊天", false),
            InstantCacheApp("example.notes", "示例笔记", false),
            InstantCacheApp("example.system", "示例系统工具", true)
        ), selected = setOf("example.browser", "example.video")
    )
    private val planState = ResumeSmartUiState(
        connected = true, status = "扫描、快照与断点事务引擎已连接",
        phase = "扫描完成，可清理 48 项。", totalSafe = 48, cleanReady = true, scanCompleted = true,
        cacheSummary = "32 项 · 842 MB", safeSummary = "16 项 · 空项目 4 · 规则 8 · 碎片 4"
    )

    private fun render(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                BaiZeTheme(AppearanceSettings(uiStyle = UiStyle.MIUIX,
                    themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
                    monetEnabled = false, glassEnabled = false, blurEnabled = false)) { content() }
            }
        }
        compose.waitForIdle()
        compose.onRoot().assertIsDisplayed()
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        val target = File("build/reports/ui-screenshots/tools-$name.png")
        requireNotNull(target.parentFile).mkdirs()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
