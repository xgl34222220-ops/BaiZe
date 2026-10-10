package io.github.xgl34222220.baize

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.clean.CleanCategoryId
import io.github.xgl34222220.baize.ui.clean.CleanUiActions
import io.github.xgl34222220.baize.ui.clean.miuix.CleanScreenMiuix
import io.github.xgl34222220.baize.ui.clean.toCleanUiState
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * CleanRoute ignores scheduler edits while a save is in flight. The switches must say so
 * (disabled) instead of looking tappable, and each switch needs an accessible name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CleanScheduleSavingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val automaticToggles = mutableListOf<Boolean>()
    private val categoryToggles = mutableListOf<Pair<CleanCategoryId, Boolean>>()
    private val actions = CleanUiActions(
        onAutomaticCleaningChanged = { automaticToggles += it },
        onCategoryEnabledChanged = { id, enabled -> categoryToggles += id to enabled },
        onCategoryIntervalChanged = { _, _ -> }, onScheduleModeChanged = {}, onDailyTimeChanged = { _, _ -> },
        onDailyGraceChanged = {}
    )

    private fun render(saving: Boolean): (Boolean) -> Unit {
        var savingState by mutableStateOf(saving)
        val appearance = AppearanceSettings(monetEnabled = false, blurEnabled = false)
        compose.setContent {
            val state = SchedulerUiState(saving = savingState).toCleanUiState(
                engineReady = true, running = false, scanSnapshotReady = false, serviceText = "",
                automationAvailable = true, automationText = "")
            BaiZeTheme(appearance) { CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
                CleanScreenMiuix(state, actions, expandedCategory = "__open_plan__", onExpandedCategoryChanged = {})
            } }
        }
        return { savingState = it }
    }

    @Test fun switchesAreNamedAndDisabledWhileSaving() {
        val setSaving = render(saving = true)
        compose.onNodeWithTag("clean-scroll").performScrollToNode(hasContentDescription("自动清理"))
        compose.onNodeWithContentDescription("自动清理").assertIsNotEnabled()
        compose.onNodeWithTag("clean-scroll").performScrollToNode(hasContentDescription("应用缓存"))
        compose.onNodeWithContentDescription("应用缓存").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(emptyList<Pair<CleanCategoryId, Boolean>>(), categoryToggles) }
        compose.onNodeWithTag("clean-scroll").performScrollToNode(hasText("正在保存设置…"))
        compose.onNodeWithText("正在保存设置…").assertIsDisplayed()

        compose.runOnIdle { setSaving(false) }
        compose.onNodeWithTag("clean-scroll").performScrollToNode(hasContentDescription("应用缓存"))
        compose.onNodeWithContentDescription("应用缓存").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(CleanCategoryId.CACHE to false), categoryToggles) }
        compose.onNodeWithTag("clean-scroll").performScrollToNode(hasContentDescription("自动清理"))
        compose.onNodeWithContentDescription("自动清理").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(false), automaticToggles) }
    }
}
