package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
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
class WorkbenchLongNameUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun remainingLongNamedFileKeepsDetailsScrollableAndDoneReachableInLargeDarkText() {
        // A legal 255-byte filename, not an artificial path longer than the filesystem limit.
        val filename = "W".repeat(251) + ".tmp"
        val path = "/synthetic/review/$filename"
        val item = WorkbenchItem("profile:remaining", "profile", "rules", "", "", "rule_trash",
            "remaining", "剩余项目样例", filename, "high", path, 1024, 1, 0, "仅测试", true)
        val state = WorkbenchUiState(profileConnected = true, cacheRequired = false, scanReady = true,
            cleanupCompleted = true, scanProfile = "rules", items = listOf(item), cleanedBytes = 512,
            cleanedFiles = 1, expiresAtRealtime = SystemClock.elapsedRealtime() + 600_000,
            phase = "低风险已处理，可以继续核对剩余项目")
        val appearance = AppearanceSettings(themeMode = ThemeMode.DARK, monetEnabled = false,
            blurEnabled = false, glassEnabled = false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f),
                LocalAppearanceSettings provides appearance) {
                BaiZeTheme(appearance) {
                    ScanWorkbenchScreen(appearance, state, WorkbenchActions(onBack = {}, onScan = {},
                        onStop = {}, onClean = {}, onToggleItem = {}, onToggleGroup = {}, onSelectAll = {},
                        onClear = {}, onProtect = {}, onQuarantine = {}))
                }
            }
        }
        compose.waitUntil(5_000) {
            runCatching {
                compose.onNodeWithTag("scan-workbench-list").performScrollToNode(hasText("剩余项目样例"))
            }.isSuccess
        }
        compose.onNodeWithText("剩余项目样例").performClick()
        compose.onNodeWithTag("scan-workbench-list")
            .performScrollToNode(hasContentDescription("${filename}详情"))
        compose.onNodeWithContentDescription("${filename}详情").performClick()
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val view = requireNotNull(ShadowDialog.getLatestDialog()?.window?.decorView)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File("build/reports/ui-screenshots/workbench-long-name-dark-large-font.png").apply {
            parentFile!!.mkdirs()
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNode(hasText("完成") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNode(hasText(path, substring = true) and hasAnyAncestor(isDialog()))
            .performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("完成") and hasAnyAncestor(isDialog())).performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }
}
