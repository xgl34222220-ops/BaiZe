package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the actual Compose routes with deterministic, explicitly simulated device state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiVisualReviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun homeLight() = render("home-light", 0)
    @Test fun homeDark() = render("home-dark", 0, dark = true)
    @Test fun cleanLight() = render("clean-light", 1)
    @Test fun cleanDark() = render("clean-dark", 1, dark = true)
    @Test fun recordsLight() = render("records-light", 2)
    @Test fun settingsLight() = render("settings-light", 3)
    @Test fun settingsDark() = render("settings-dark", 3, dark = true)
    @Test fun materialHome() = render("material-home", 0, style = UiStyle.MATERIAL)
    @Test fun disconnectedHome() = render("home-disconnected", 0, connected = false)

    @Test fun homePrimaryScansBeforeAnyCleanup() {
        var scans = 0
        var cleans = 0
        render("home-primary-action", 0, actions = previewActions.copy(scan = { scans++ }, clean = { cleans++ }, cleanScan = { cleans++ }))
        compose.onNodeWithText("开始扫描").performClick()
        assertEquals(1, scans)
        assertEquals(0, cleans)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun narrowLargeFontHome() = render("home-narrow-large-font", 0, fontScale = 1.3f)

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun narrowLargeFontClean() = render("clean-narrow-large-font", 1, fontScale = 1.3f)

    private fun render(
        name: String,
        page: Int,
        dark: Boolean = false,
        style: UiStyle = UiStyle.MIUIX,
        connected: Boolean = true,
        fontScale: Float = 1f,
        actions: DashboardActions = previewActions,
    ) {
        val state = DashboardUiState(
            ready = connected,
            connected = connected,
            serviceText = if (connected) "清理服务已就绪" else "请授予 Root 权限",
            schedulerText = "定时计划已启用",
            storageTotal = 256L * 1024 * 1024 * 1024,
            storageUsed = 164L * 1024 * 1024 * 1024,
            storageFree = 92L * 1024 * 1024 * 1024,
            storagePercent = .64f,
            lastReleased = 842L * 1024 * 1024,
            lifetimeReleased = 6L * 1024 * 1024 * 1024,
            lifetimeRuns = 12,
            lifetimeFiles = 1642,
            device = "界面验证设备",
            android = "Android 15",
            lastTaskTime = "今天 09:41",
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                BaiZeMiuixApp(
                    state = state,
                    scheduler = SchedulerUiState(enabled = true, apkPackagesEnabled = true, apkMinutes = 60, apkPackageDays = 0),
                    actions = actions,
                    appearance = AppearanceSettings(
                        uiStyle = style,
                        themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
                        monetEnabled = false,
                        glassEnabled = false,
                        blurEnabled = false,
                    ),
                    initialPage = page,
                )
            }
        }
        compose.waitForIdle()
        compose.onRoot().assertIsDisplayed()
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        require(bitmap.width >= 320 && bitmap.height >= 640) { "Unexpected rendering bounds" }
        val target = File("build/reports/ui-screenshots/$name.png")
        requireNotNull(target.parentFile).mkdirs()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val previewActions = DashboardActions(
        refresh = {}, clean = {}, organize = {}, scan = {}, apkScan = {}, cleanScan = {},
        dismissScan = {}, stop = {}, deep = {}, corpses = {}, audit = {},
        updateScheduler = {}, saveScheduler = {}, schedulerCommand = {}, clearHistory = {},
        clearRawLog = {}, reviewProtected = {}, whitelist = {}, resumableScan = {},
        theme = {}, reconnect = {}, resetScanPerformance = {}, crash = {},
    )
}
