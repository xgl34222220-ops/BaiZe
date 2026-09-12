package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real route rendering, with service results supplied as fixtures rather than a Root connection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RulesPolicyVisualReviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun rulesLightKeepsToolsAndConfirmation() {
        var profile: String? = null
        render { CleanCenterRoute(actions.copy(onOpenProfile = { profile = it })) }
        compose.onNodeWithText("规则与保护").assertIsDisplayed()
        save("rules-light")
        compose.onNodeWithText("规则垃圾").performClick()
        assertEquals("rules", profile)
        profile = null
        compose.onNodeWithText("卸载残留").performScrollTo().performClick()
        compose.onNodeWithText("扫描卸载残留？").assertIsDisplayed()
        assertNull(profile)
        compose.onNodeWithText("继续扫描").performClick()
        assertEquals("corpses", profile)
    }

    @Test fun rulesDark() {
        render(dark = true) { CleanCenterRoute(actions) }
        save("rules-dark")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h720dp-mdpi")
    fun rulesNarrowLargeFont() {
        render(fontScale = 1.3f) { CleanCenterRoute(actions) }
        save("rules-narrow-large-font")
        compose.onNodeWithText("清理策略").performScrollTo().assertIsDisplayed()
    }

    @Test fun policyLightAppliesChosenPreset() {
        var applied: CleanupPolicy? = null
        render { CleanupPolicyScreen(policyState, {}, {}, { applied = it }) }
        compose.onNodeWithText("清理策略").assertIsDisplayed()
        save("policy-light")
        compose.onNodeWithText("积极").performScrollTo().performClick()
        assertNull(applied)
        compose.onNodeWithText("应用积极档").performScrollTo().performClick()
        assertEquals(CleanupPolicy.AGGRESSIVE, applied)
        compose.onNodeWithText("积极档的清理范围").performScrollTo().performClick()
        compose.onNodeWithText("单文件保护上限 1 GB", substring = true).assertIsDisplayed()
    }

    @Test fun policyDark() {
        render(dark = true) { CleanupPolicyScreen(policyState, {}, {}, {}) }
        save("policy-dark")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h720dp-mdpi")
    fun policyNarrowLargeFont() {
        render(fontScale = 1.3f) { CleanupPolicyScreen(policyState, {}, {}, {}) }
        save("policy-narrow-large-font")
        compose.onNodeWithText("积极").performScrollTo().performClick()
        compose.onNodeWithText("应用积极档").performScrollTo().assertIsDisplayed()
    }

    @Test fun policyAdviceCanExpandAndApply() {
        var applied: CleanupPolicy? = null
        render { CleanupPolicyScreen(policyState.copy(advice = advice), {}, {}, { applied = it }) }
        compose.onNodeWithText("查看建议依据").performScrollTo().performClick()
        compose.onNodeWithText("近期隔离恢复比例偏高，可延长保留时间。").performScrollTo().assertIsDisplayed()
        save("policy-advice")
        compose.onNodeWithText("采用建议的保守档").performScrollTo().performClick()
        assertEquals(CleanupPolicy.CONSERVATIVE, applied)
    }

    private fun render(dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val settings = AppearanceSettings(
            uiStyle = UiStyle.MIUIX,
            themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
            monetEnabled = false,
            glassEnabled = true,
            blurEnabled = false,
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalAppearanceSettings provides settings,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                BaiZeTheme(settings) {
                    Surface(Modifier.fillMaxSize(), color = BaiZeTokens.colors.surfaceBase) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun save(name: String) {
        compose.waitForIdle()
        compose.onRoot().assertIsDisplayed()
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        val file = File("build/reports/ui-screenshots/tools-$name.png")
        requireNotNull(file.parentFile).mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val actions = CleanCenterActions({}, {}, {}, {}, {}, {})
    private val policyState = CleanupPolicyUiState(
        connected = true,
        activePolicy = CleanupPolicy.BALANCED,
        message = "当前使用均衡档",
    )
    private val advice = PolicyAdvice(
        recommendedPolicy = CleanupPolicy.CONSERVATIVE,
        summary = "建议延长保留时间，减少重要临时数据被提前处理。",
        confidence = "medium",
        storageFreePercent = 38,
        failureRate = 2,
        restoreRate = 16,
        protectionRate = 9,
        averageScanMs = 4500,
        sampleCount = 24,
        reasons = listOf("近期隔离恢复比例偏高，可延长保留时间。"),
        automatic = false,
        scheduleUntouched = true,
    )
}
