package io.github.xgl34222220.baize

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LegacyProtectionRecoveryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restoration = StateRestorationTester(compose)
    private val path = "/storage/emulated/0/Download/旧版保留目录"
    private var state by mutableStateOf(LegacyProtectionRecoveryUiState())
    private var saves = 0
    private var backs = 0
    private var refreshes = 0

    private fun pending(currentApps: Set<String> = setOf("current.protected.app")) =
        LegacyProtectionRecovery.RecoverySnapshot(currentPackages = currentApps, currentPaths = emptySet(),
            pendingPackages = emptySet(), pendingPaths = setOf(path),
            packageReviewRequired = false, pathReviewRequired = true, fingerprint = "pending", currentPackagesPresent = true)

    private fun render(snapshot: LegacyProtectionRecovery.RecoverySnapshot = pending(), large: Boolean = false) {
        state = LegacyProtectionRecoveryUiState(snapshot, snapshot.pendingPackages, snapshot.pendingPaths)
        val appearance = AppearanceSettings(monetEnabled = false, blurEnabled = false)
        restoration.setContent {
            val density = LocalDensity.current
            BaiZeTheme(appearance) {
                CompositionLocalProvider(LocalAppearanceSettings provides appearance,
                    LocalDensity provides Density(density.density, if (large) 1.7f else 1f)) {
                    LegacyProtectionRecoveryScreen(state, { backs++ }, { refreshes++ },
                        { value -> state = state.copy(selectedPackages = state.selectedPackages.toggle(value)) },
                        { value -> state = state.copy(selectedPaths = state.selectedPaths.toggle(value)) },
                        { saves++; state = state.copy(saving = true) })
                }
            }
        }
    }
    private fun Set<String>.toggle(value: String) = if (value in this) this - value else this + value
    private fun candidate(value: String = path): SemanticsNodeInteraction {
        val tag = "legacy-pending-path:$value"
        compose.onNodeWithTag("legacy-recovery-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    @Test fun selectingAndCancelingReviewNeverWritesAndListsExactKeepDiscardChoices() {
        render()
        compose.onNodeWithText("旧版保护待确认").assertIsDisplayed()
        candidate().assertIsOn().performClick().assertIsOff()
        assertEquals(0, saves)
        compose.onNodeWithTag("legacy-recovery-save").performClick()
        compose.onNodeWithText("保留的旧版路径 · 0 条").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("不恢复的旧版路径 · 1 条").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(path) and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("返回核对").performClick()
        assertEquals(0, saves)
        compose.onNodeWithTag("legacy-recovery-confirm").assertDoesNotExist()
        candidate().assertIsOff()
    }

    @Test fun existingEmptyCurrentRulesRemainVisibleAndCannotBeReplacedByHistoricalSelection() {
        render(pending(currentApps = emptySet()))
        compose.onNodeWithTag("legacy-recovery-list").performScrollToNode(hasText("当前应用 · 0 条"))
        compose.onNodeWithText("当前应用 · 0 条").assertIsDisplayed()
        compose.onNodeWithText("历史应用候选 · 0 条").assertDoesNotExist()
        candidate().assertIsOn()
        compose.onNodeWithTag("legacy-recovery-save").performClick()
        compose.onNodeWithText("保留的旧版应用 · 0 条").assertDoesNotExist()
        compose.onNodeWithText("返回核对").performClick()
        assertEquals(emptySet<String>(), state.snapshot!!.currentPackages)
        assertEquals(0, saves)
    }

    @Test fun confirmationSurvivesRotationAndBusyProgressPreventsDoubleWriteRefreshOrBack() {
        render()
        compose.onNodeWithTag("legacy-recovery-save").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("legacy-recovery-confirm").performClick()
        compose.onNodeWithTag("legacy-recovery-save").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("legacy-recovery-refresh").assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("legacy-recovery-save").assertIsNotEnabled()
        assertEquals(1, saves)
        assertEquals(0, backs)
        assertEquals(0, refreshes)
    }

    @Test fun staleOrUnreadableStateKeepsKnownInformationButBlocksConfirmation() {
        render()
        compose.runOnIdle { state = state.copy(needsRefresh = true, error = "保护记录已变化，请刷新核对") }
        compose.onNodeWithTag("legacy-recovery-save").assertIsNotEnabled()
        candidate().assertIsNotEnabled()
        compose.onNodeWithTag("legacy-recovery-list").performScrollToNode(hasText("current.protected.app"))
        compose.onNodeWithText("current.protected.app").assertIsDisplayed()
        assertEquals(0, saves)
    }

    @Test fun interruptedSaveWithExplicitEmptyCurrentPathStillRequiresConfirmationWithoutRestoringHistory() {
        render(pending().copy(currentPathsPresent = true, pendingPaths = emptySet()))
        compose.onNodeWithTag("legacy-recovery-list").performScrollToNode(hasText("当前路径 · 0 条"))
        compose.onNodeWithText("当前路径 · 0 条").assertIsDisplayed()
        compose.onNodeWithTag("legacy-recovery-save").assertIsEnabled().performClick()
        compose.onNodeWithText("保持不变的当前路径 · 0 条").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("返回核对").performClick()
        assertEquals(0, saves)
        assertTrue(state.snapshot!!.currentPathsPresent)
        assertTrue(state.snapshot!!.currentPaths.isEmpty())
    }

    @Test fun longPathsAtLargeFontHaveScrollableReviewAndVisibleCancelAction() {
        val long = path + "/完整路径需要逐项确认".repeat(18)
        render(pending().copy(pendingPaths = setOf(long)), large = true)
        candidate(long).assertIsOn()
        compose.onNodeWithTag("legacy-recovery-save").performClick()
        compose.onNodeWithTag("legacy-recovery-confirm").assertIsDisplayed()
        compose.onNode(hasText(long) and hasAnyAncestor(isDialog())).performScrollTo().assertExists()
        compose.onNodeWithText("返回核对").assertIsDisplayed().performClick()
        assertEquals(0, saves)
    }

    @Test fun disconnectedWhitelistAlwaysExposesRecoveryWithoutEnablingRootEdits() {
        var opened = 0
        val appearance = AppearanceSettings(monetEnabled = false, blurEnabled = false)
        compose.setContent {
            BaiZeTheme(appearance) {
                CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
                    WhitelistManagerScreen(WhitelistUiState(connected = false), {}, {}, {}, {}, {},
                        onReviewLegacyProtection = { opened++ })
                }
            }
        }
        compose.onNodeWithTag("whitelist-legacy-recovery").assertIsEnabled().performClick()
        assertEquals(1, opened)
    }

    @Test fun unavailableApkProtectionExposesRecoveryBeforeAnyScan() {
        var opened = 0
        val appearance = AppearanceSettings(monetEnabled = false, blurEnabled = false)
        compose.setContent {
            BaiZeTheme(appearance) {
                CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
                    ApkScanScreen(ApkScanUiState(protectionNeedsAction = true, protectionMessage = "旧版保护待确认"),
                        {}, {}, {}, {}, {}, onReviewLegacyProtection = { opened++ })
                }
            }
        }
        compose.onNodeWithTag("apk-results-list").performScrollToNode(hasTestTag("apk-legacy-recovery"))
        compose.onNodeWithTag("apk-legacy-recovery").assertIsEnabled().performClick()
        assertEquals(1, opened)
    }

    @Test fun trashHelpExposesLocalRecoveryWithoutRoot() {
        val appearance = AppearanceSettings(monetEnabled = false, blurEnabled = false)
        compose.setContent {
            BaiZeTheme(appearance) {
                CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
                    FileTrashScreen(FileTrashUiState(loaded = true), FileTrashActions())
                }
            }
        }
        compose.onNodeWithTag("trash-legacy-recovery").assertDoesNotExist()
        compose.onNodeWithText("说明与容量设置").performClick()
        compose.onNodeWithTag("trash-legacy-recovery").performScrollTo().assertIsEnabled()
    }
}
