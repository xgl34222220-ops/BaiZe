package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.appearance.UiStyle
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
class DashboardQuietStatusUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ComponentVersion("2.0.0", 30010L)
    private val versions = RuntimeVersions(app, ComponentVersion("2.0.0", 30008L))
    private val base = DashboardUiState(storageTotal = 1_000_000_000_000L,
        storageFree = 754_000_000_000L, storageUsed = 246_000_000_000L)
    private val actions = DashboardActions(refresh = {}, clean = {}, organize = {}, scan = {}, apkScan = {},
        largeFiles = {}, duplicates = {}, storageAnalysis = {}, cleanScan = {}, dismissScan = {}, stop = {},
        deep = {}, corpses = {}, audit = {}, updateScheduler = {}, saveScheduler = {}, schedulerCommand = {},
        clearHistory = {}, clearRawLog = {}, reviewProtected = {}, whitelist = {}, resumableScan = {},
        theme = {}, reconnect = {}, resetScanPerformance = {}, crash = {})

    @Test fun startupReconnectAndCompatibleReadyKeepTheSameHomeGeometry() {
        val cached = versions.presentation(app, false)
        val state = mutableStateOf(base.copy(connecting = true, versionDetails = cached.details))
        compose.setContent { BaiZeMiuixApp(state.value, SchedulerUiState(), actions, appearance()) }
        compose.onNodeWithText("正在连接").assertIsDisplayed()
        compose.onNodeWithText("开始扫描").assertIsNotEnabled()
        val title = compose.onNodeWithText("白泽").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithText("开始扫描").fetchSemanticsNode().boundsInRoot
        val current = versions.presentation(app, true)
        repeat(2) {
            compose.runOnIdle { state.value = base.copy(connected = true, ready = true,
                versionWarning = current.warning, versionDetails = current.details) }
            compose.onNodeWithText("服务已就绪").assertIsDisplayed()
            compose.onNodeWithText("已验证兼容", substring = true).assertDoesNotExist()
            compose.onNodeWithText("历史版本缓存", substring = true).assertDoesNotExist()
            compose.onNodeWithText("开始扫描").assertIsEnabled()
            assertEquals(title, compose.onNodeWithText("白泽").fetchSemanticsNode().boundsInRoot)
            assertEquals(button, compose.onNodeWithText("开始扫描").fetchSemanticsNode().boundsInRoot)
            if (it == 0) compose.runOnIdle { state.value = base.copy(connecting = true, versionDetails = cached.details) }
        }
        save("home-quiet-compatible-30010")
    }

    @Test fun settingsShowsFullVersionDetailsAndRestoresTheDetailPage() {
        val presentation = versions.presentation(app, true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            BaiZeMiuixApp(base.copy(connected = true, ready = true, versionDetails = presentation.details),
                SchedulerUiState(), actions, appearance(), initialPage = 3)
        }
        compose.onNodeWithText("已验证兼容", substring = true).assertDoesNotExist()
        compose.onNodeWithText("白泽状态").performClick()
        compose.onNodeWithText("连接与诊断").assertIsDisplayed()
        compose.onNodeWithText(presentation.details).performScrollTo().assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("连接与诊断").assertIsDisplayed()
        compose.onNodeWithText(presentation.details).performScrollTo().assertIsDisplayed()
        save("settings-version-details-30010")
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun actualFailureRemainsReadableInLargeDarkTextWithOneRetry() {
        var retries = 0
        val failure = "未取得 Root shell。请确认 Root 管理器及授权状态，再手动重连。"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                BaiZeMiuixApp(base.copy(connected = true, ready = true, connectionFailed = true, serviceText = failure),
                    SchedulerUiState(), actions.copy(reconnect = { retries++ }), appearance(dark = true))
            }
        }
        compose.onNodeWithText("连接异常").assertIsDisplayed()
        compose.onNodeWithText(failure).assertExists()
        assertEquals(0, retries)
        compose.onAllNodesWithText("重试连接").assertCountEquals(1)
        val retry = compose.onNodeWithText("重试连接").performScrollTo()
        val dockTop = compose.onNodeWithTag("luoshu-dock").fetchSemanticsNode().boundsInRoot.top
        val overlap = retry.fetchSemanticsNode().boundsInRoot.center.y - dockTop + 28f
        if (overlap > 0f) compose.onNode(hasScrollAction()).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, overlap) }
        retry.assertIsDisplayed().performClick()
        assertEquals(1, retries)
        save("home-failure-large-dark-30010")
    }

    @Test fun currentMismatchStaysVisibleInCardWithoutGlobalBannerOnMaterial() {
        val presentation = RuntimeVersions(app, ComponentVersion("2.0.0", 30007L)).presentation(app, true)
        compose.setContent {
            BaiZeMiuixApp(base.copy(connected = true, ready = true, versionWarning = presentation.warning,
                versionDetails = presentation.details), SchedulerUiState(), actions, appearance().copy(uiStyle = UiStyle.MATERIAL))
        }
        compose.onNodeWithText("版本需检查").assertIsDisplayed()
        compose.onNodeWithText(presentation.warning).assertDoesNotExist()
        compose.onNodeWithText("版本信息需要检查").performScrollTo().performClick()
        compose.onNodeWithText(presentation.warning).performScrollTo().assertIsDisplayed()
    }

    private fun appearance(dark: Boolean = false) = AppearanceSettings(
        themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
        monetEnabled = false, glassEnabled = false, blurEnabled = false)

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        File("build/reports/ui-screenshots/$name.png").apply {
            parentFile!!.mkdirs()
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
