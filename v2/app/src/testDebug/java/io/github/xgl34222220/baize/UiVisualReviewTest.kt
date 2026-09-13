package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import android.view.View
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
    @Test fun glassHome() {
        // This harness draws to a Bitmap Canvas, not a GPU surface. Keep the effects
        // preference ON, but require the real renderer's software-capability fallback.
        render("home-glass-software-fallback", 0, blur = true)
        compose.onNodeWithTag("dock-shell-opaque").assertIsDisplayed()
    }
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

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun narrowLargeFontSettings() = render("settings-narrow-large-font", 3, fontScale = 1.3f)

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun narrowLargeFontRecords() = render("records-narrow-large-font", 2, fontScale = 1.3f)

    @Test fun homePlanOpensAutomaticPlan() {
        render("home-plan-entry", 0)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("自动清理"))
        compose.onNodeWithText("自动清理").performScrollTo().performClick()
        compose.waitForIdle()
        save("clean-plan")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("安装包保留时间"))
        compose.onNodeWithText("安装包保留时间").performScrollTo().assertIsDisplayed()
        save("clean-plan-apk-retention")
    }

    @Test fun homePlanIsVisibleOnTheFirstScreen() {
        render("home-plan-visible", 0)
        compose.onNodeWithText("自动清理").assertIsDisplayed()
    }

    @Test fun groupedHomeToolsKeepTheirOwnActions() {
        val calls = mutableListOf<String>()
        render("home-tools", 0, actions = previewActions.copy(
            apkScan = { calls += "apk" }, organize = { calls += "organize" },
            deep = { calls += "deep" }, whitelist = { calls += "whitelist" }))
        listOf("安装包", "文件归类", "深度清理", "白名单").forEach { title ->
            val list = compose.onNode(hasScrollAction())
            list.performScrollToNode(hasText(title))
            val node = compose.onNodeWithText(title).performScrollTo()
            // A floating dock overlays the viewport. Bring the physical tap point
            // above it rather than clicking a row still behind the dock.
            val dockTop = compose.onNodeWithTag("luoshu-dock").fetchSemanticsNode().boundsInRoot.top
            val overlap = node.fetchSemanticsNode().boundsInRoot.center.y - dockTop + 28f
            if (overlap > 0f) {
                list.performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, overlap) }
                compose.waitForIdle()
            }
            org.junit.Assert.assertTrue("Tool tap point must clear the dock",
                node.fetchSemanticsNode().boundsInRoot.center.y < dockTop)
            node.assertIsDisplayed().performClick()
        }
        assertEquals(listOf("apk", "organize", "deep", "whitelist"), calls)
    }

    @Test fun disconnectedHomeOnlyReconnects() {
        var reconnects = 0
        var scans = 0
        var cleans = 0
        render("home-reconnect-action", 0, connected = false, actions = previewActions.copy(
            reconnect = { reconnects++ }, scan = { scans++ }, clean = { cleans++ }, cleanScan = { cleans++ }))
        compose.onNodeWithText("连接 Root 服务").performClick()
        assertEquals(1, reconnects)
        assertEquals(0, scans)
        assertEquals(0, cleans)
    }

    @Test fun settingsHubOpensTaskDetailsAndReturns() {
        render("settings-hub", 3)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("自动任务设置"))
        compose.onNodeWithText("自动任务设置").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("清理执行条件").assertIsDisplayed()
        save("settings-task-details")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("你的白泽").assertIsDisplayed()
    }

    @Test fun settingsAppearanceAndWhitelistKeepTheirActions() {
        var appearance = 0
        var whitelist = 0
        render("settings-action-routing", 3, actions = previewActions.copy(theme = { appearance++ }, whitelist = { whitelist++ }))
        compose.onNodeWithText("外观与主题").performScrollTo().performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("应用白名单"))
        compose.onNodeWithText("应用白名单").performScrollTo().performClick()
        assertEquals(1, appearance)
        assertEquals(1, whitelist)
    }

    @Test fun taskSettingsSaveTheEditedDraft() {
        var updated: SchedulerUiState? = null
        var saved: SchedulerUiState? = null
        render("settings-draft", 3, actions = previewActions.copy(
            updateScheduler = { updated = it }, saveScheduler = { saved = it }))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("自动任务设置"))
        compose.onNodeWithText("自动任务设置").performScrollTo().performClick()
        compose.onNodeWithContentDescription("仅息屏时执行").performClick()
        compose.onNodeWithText("保存").performClick()
        assertEquals(!SchedulerUiState().screenOffOnly, updated?.screenOffOnly)
        assertEquals(updated?.screenOffOnly, saved?.screenOffOnly)
    }

    private fun render(
        name: String,
        page: Int,
        dark: Boolean = false,
        style: UiStyle = UiStyle.MIUIX,
        connected: Boolean = true,
        fontScale: Float = 1f,
        blur: Boolean = false,
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
            val hostView = LocalView.current
            // Bitmap captures cannot execute GPU shaders. Override only the view exposed
            // to the effects consumer, not Robolectric's global View/ShadowViewGroup graph.
            // The Compose owner, activity, layout, semantics and touch delivery stay real.
            val captureView = remember(hostView) {
                object : View(hostView.context) {
                    override fun isHardwareAccelerated(): Boolean = false
                }
            }
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalView provides if (blur) captureView else hostView,
            ) {
                BaiZeMiuixApp(
                    state = state,
                    scheduler = SchedulerUiState(enabled = true, apkPackagesEnabled = true, apkMinutes = 60, apkPackageDays = 0),
                    actions = actions,
                    appearance = AppearanceSettings(
                        uiStyle = style,
                        themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
                        monetEnabled = false,
                        glassEnabled = true,
                        blurEnabled = blur,
                    ),
                    initialPage = page,
                )
            }
        }
        compose.waitForIdle()
        compose.onRoot().assertIsDisplayed()
        save(name)
    }

    private fun save(name: String) {
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
