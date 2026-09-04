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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
 * BaiZe shell aligned with LuoShu's current compositor model.
 *
 * MIUIX keeps exactly one destination page alive while changing tabs. The incoming
 * destination performs a short spring entrance, so RuntimeShader never receives the
 * outgoing page as a stale backdrop layer. Material keeps the same route topology but
 * uses a standard navigation bar and no liquid compositor.
 */
@Composable
fun BaiZeLuoShuApp(
    state: DashboardUiState,
    scheduler: SchedulerUiState,
    actions: DashboardActions,
    appearance: AppearanceSettings
) {
    BaiZeTheme(appearance) {
        CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
            if (appearance.uiStyle == UiStyle.MIUIX) {
                LuoShuMiuixShell(state, scheduler, actions, appearance)
            } else {
                LuoShuMaterialShell(state, scheduler, actions)
            }
        }
    }
}

@Composable
private fun LuoShuMiuixShell(
    state: DashboardUiState,
    scheduler: SchedulerUiState,
    actions: DashboardActions,
    appearance: AppearanceSettings
) {
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
            DestinationOnlyPage(
                page = page,
                state = state,
                scheduler = scheduler,
                actions = actions,
                expandedCleanCategory = expandedCleanCategory,
                onExpandedCleanCategoryChanged = { expandedCleanCategory = it },
                onNavigate = { page = it },
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            )
        }

        MiuixLiquidDock(
            selectedIndex = page.ordinal,
            items = navItems,
            onSelected = { index -> page = LuoShuBaiZePage.entries[index] },
            hazeState = hazeState,
            backdrop = liquidBackdrop.takeIf { liquidGlassSupported },
            floating = appearance.floatingDock,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun LuoShuMaterialShell(
    state: DashboardUiState,
    scheduler: SchedulerUiState,
    actions: DashboardActions
) {
    var page by rememberSaveable { mutableStateOf(LuoShuBaiZePage.Home) }
    var expandedCleanCategory by rememberSaveable { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        DestinationOnlyPage(
            page = page,
            state = state,
            scheduler = scheduler,
            actions = actions,
            expandedCleanCategory = expandedCleanCategory,
            onExpandedCleanCategoryChanged = { expandedCleanCategory = it },
            onNavigate = { page = it },
            style = UiStyle.MATERIAL,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        )

        NavigationBar(modifier = Modifier.align(Alignment.BottomCenter)) {
            LuoShuBaiZePage.entries.forEach { destination ->
                NavigationBarItem(
                    selected = page == destination,
                    onClick = { page = destination },
                    icon = { Icon(destination.icon, contentDescription = destination.label) },
                    label = { Text(destination.label) }
                )
            }
        }
    }
}

@Composable
private fun DestinationOnlyPage(
    page: LuoShuBaiZePage,
    state: DashboardUiState,
    scheduler: SchedulerUiState,
    actions: DashboardActions,
    expandedCleanCategory: String,
    onExpandedCleanCategoryChanged: (String) -> Unit,
    onNavigate: (LuoShuBaiZePage) -> Unit,
    modifier: Modifier,
    style: UiStyle = UiStyle.MIUIX
) {
    key(page) {
        val pageEnter = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            pageEnter.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = .86f, stiffness = 430f)
            )
        }
        Box(
            modifier = modifier.graphicsLayer {
                alpha = .86f + (.14f * pageEnter.value)
                translationY = (1f - pageEnter.value) * 18.dp.toPx()
            }
        ) {
            when (page) {
                LuoShuBaiZePage.Home -> HomeRoute(
                    style = style,
                    state = state,
                    scheduler = scheduler,
                    actions = actions,
                    onOpenClean = { onNavigate(LuoShuBaiZePage.Clean) }
                )

                LuoShuBaiZePage.Clean -> CleanRoute(
                    style = style,
                    dashboard = state,
                    scheduler = scheduler,
                    dashboardActions = actions,
                    expandedCategory = expandedCleanCategory,
                    onExpandedCategoryChanged = onExpandedCleanCategoryChanged
                )

                LuoShuBaiZePage.Records -> HistoryRoute(
                    style = style,
                    dashboard = state,
                    dashboardActions = actions
                )

                LuoShuBaiZePage.Settings -> SettingsRoute(
                    style = style,
                    dashboard = state,
                    scheduler = scheduler,
                    appearance = LocalAppearanceSettings.current,
                    dashboardActions = actions,
                    onOpenDetails = { onNavigate(LuoShuBaiZePage.Records) }
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
