package io.github.xgl34222220.baize

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.clean.CleanRoute
import io.github.xgl34222220.baize.ui.history.HistoryRoute
import io.github.xgl34222220.baize.ui.home.HomeRoute
import io.github.xgl34222220.baize.ui.miuix.MiuixLiquidDock
import io.github.xgl34222220.baize.ui.miuix.MiuixLiquidNavItem
import io.github.xgl34222220.baize.ui.settings.SettingsRoute
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

private enum class LuoShuBaiZePage(
    val label: String,
    val icon: ImageVector
) {
    Home("首页", Icons.Rounded.Home),
    Clean("清理", Icons.Rounded.CleaningServices),
    Records("记录", Icons.Rounded.History),
    Settings("设置", Icons.Rounded.Settings)
}

/**
 * MIUIX shell intentionally mirrors LuoShu's current compositor architecture:
 * only the destination page exists during a page change, while the page itself
 * performs a short spring entrance. This prevents the glass dock from refracting
 * an outgoing page and producing the old-page ghost frame seen in recordings.
 *
 * Material mode keeps BaiZe's existing shell untouched.
 */
@Composable
fun BaiZeLuoShuApp(
    state: DashboardUiState,
    scheduler: SchedulerUiState,
    actions: DashboardActions,
    appearance: AppearanceSettings
) {
    if (appearance.uiStyle != UiStyle.MIUIX) {
        BaiZeMiuixApp(state, scheduler, actions, appearance)
        return
    }

    BaiZeTheme(appearance) {
        CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
            val dark = MaterialTheme.colorScheme.background.luminance() < .5f
            val amoled = dark && appearance.amoledBlack
            val runtimeDegraded = io.github.xgl34222220.baize.performance.PerformanceRuntime.degraded.value
            val blurActive = appearance.blurEnabled &&
                appearance.glassEnabled &&
                !amoled &&
                !(appearance.adaptiveSmoothMode && runtimeDegraded)
            val hazeState = rememberHazeState(blurEnabled = blurActive)
            val liquidBackdrop = rememberLayerBackdrop()
            val liquidGlassSupported = blurActive && isRuntimeShaderSupported()

            var page by rememberSaveable { mutableStateOf(LuoShuBaiZePage.Home) }
            var expandedCleanCategory by rememberSaveable { mutableStateOf("") }
            val navItems = remember {
                LuoShuBaiZePage.entries.map { MiuixLiquidNavItem(it.label, it.icon) }
            }

            val contentModifier = Modifier
                .fillMaxSize()
                .then(
                    if (blurActive && !liquidGlassSupported) Modifier.hazeSource(state = hazeState)
                    else Modifier
                )
                .then(
                    if (liquidGlassSupported) Modifier.layerBackdrop(liquidBackdrop)
                    else Modifier
                )

            Box(Modifier.fillMaxSize()) {
                Box(contentModifier) {
                    LuoShuBaiZeBackdrop(dark = dark, amoled = amoled)

                    // Same destination-only page transition used by LuoShu.
                    key(page) {
                        val pageEnter = remember { Animatable(0f) }
                        LaunchedEffect(Unit) {
                            pageEnter.animateTo(
                                targetValue = 1f,
                                animationSpec = spring(dampingRatio = .86f, stiffness = 430f)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .graphicsLayer {
                                    alpha = .86f + (.14f * pageEnter.value)
                                    translationY = (1f - pageEnter.value) * 18.dp.toPx()
                                }
                        ) {
                            when (page) {
                                LuoShuBaiZePage.Home -> HomeRoute(
                                    style = UiStyle.MIUIX,
                                    state = state,
                                    scheduler = scheduler,
                                    actions = actions,
                                    onOpenClean = { page = LuoShuBaiZePage.Clean }
                                )

                                LuoShuBaiZePage.Clean -> CleanRoute(
                                    style = UiStyle.MIUIX,
                                    dashboard = state,
                                    scheduler = scheduler,
                                    dashboardActions = actions,
                                    expandedCategory = expandedCleanCategory,
                                    onExpandedCategoryChanged = { expandedCleanCategory = it }
                                )

                                LuoShuBaiZePage.Records -> HistoryRoute(
                                    style = UiStyle.MIUIX,
                                    dashboard = state,
                                    actions = actions
                                )

                                LuoShuBaiZePage.Settings -> SettingsRoute(
                                    style = UiStyle.MIUIX,
                                    dashboard = state,
                                    scheduler = scheduler,
                                    appearance = appearance,
                                    actions = actions,
                                    onOpenRecords = { page = LuoShuBaiZePage.Records }
                                )
                            }
                        }
                    }
                }

                MiuixLiquidDock(
                    selectedIndex = page.ordinal,
                    items = navItems,
                    onSelected = { index -> page = LuoShuBaiZePage.entries[index] },
                    hazeState = hazeState,
                    backdrop = liquidBackdrop.takeIf { liquidGlassSupported },
                    floating = appearance.floatingDock,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun LuoShuBaiZeBackdrop(dark: Boolean, amoled: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val base = if (amoled) Color.Black else BaiZeTokens.colors.surfaceBase
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(base)
            .drawBehind {
                if (amoled) return@drawBehind
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(
                            scheme.primary.copy(alpha = if (dark) .09f else .10f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * .92f, size.height * .02f),
                        radius = size.width * .85f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(
                            scheme.secondary.copy(alpha = if (dark) .06f else .07f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * .04f, size.height * .82f),
                        radius = size.width
                    )
                )
            }
    )
}
