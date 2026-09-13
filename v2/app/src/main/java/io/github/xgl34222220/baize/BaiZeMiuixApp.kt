package io.github.xgl34222220.baize

import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.clean.CleanRoute
import io.github.xgl34222220.baize.ui.home.HomeRoute
import io.github.xgl34222220.baize.ui.history.HistoryRoute
import io.github.xgl34222220.baize.ui.settings.SettingsRoute
import io.github.xgl34222220.baize.ui.miuix.MiuixLiquidDock
import io.github.xgl34222220.baize.ui.miuix.MiuixLiquidNavItem
import io.github.xgl34222220.baize.ui.miuix.LuoShuLiquidDock
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

private enum class BaiZePage(val title: String, val icon: ImageVector) {
    Home("首页", Icons.Rounded.Home),
    Clean("清理", Icons.Rounded.CleaningServices),
    Records("记录", Icons.Rounded.History),
    Settings("设置", Icons.Rounded.Settings)
}

@Composable
fun BaiZeMiuixApp(
    state: DashboardUiState,
    scheduler: SchedulerUiState,
    actions: DashboardActions,
    appearance: AppearanceSettings,
    initialPage: Int = 0
) {
    BaiZeTheme(appearance) {
        CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
            val dark = MaterialTheme.colorScheme.background.luminance() < .5f
            val amoled = dark && appearance.amoledBlack
            val runtimeDegraded = io.github.xgl34222220.baize.performance.PerformanceRuntime.degraded.value
            val blurActive = appearance.uiStyle == UiStyle.MIUIX &&
                appearance.blurEnabled && appearance.glassEnabled &&
                !amoled && !(appearance.adaptiveSmoothMode && runtimeDegraded)
            val hazeState = rememberHazeState(blurEnabled = blurActive)
            val hardware = LocalView.current.isHardwareAccelerated
            val liquidSupported = blurActive && hardware && Build.VERSION.SDK_INT >= 33 && isRuntimeShaderSupported()
            val liquidBackdrop = if (liquidSupported) rememberLayerBackdrop() else null
            var settingsDetailVisible by remember { mutableStateOf(false) }
            var page by rememberSaveable { mutableStateOf(BaiZePage.entries[initialPage.coerceIn(0, BaiZePage.entries.lastIndex)]) }
            var expandedCleanCategory by rememberSaveable { mutableStateOf("") }
            val showDock = page != BaiZePage.Settings || !settingsDetailVisible
            val miuixNavItems = remember {
                BaiZePage.entries.map { MiuixLiquidNavItem(it.title, it.icon) }
            }

            Column(Modifier.fillMaxSize()) {
                if (state.versionWarning.isNotBlank()) {
                    Text(
                        text = state.versionWarning,
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 12.sp
                    )
                }
                Box(Modifier.weight(1f)) {
                    when (appearance.uiStyle) {
                        UiStyle.MATERIAL -> Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(BaiZeTokens.colors.surfaceBase)
                        ) {
                            AnimatedPageHost(
                                page = page,
                                style = UiStyle.MATERIAL,
                                modifier = Modifier
                                    .fillMaxSize()
                            ) { targetPage ->
                                when (targetPage) {
                                    BaiZePage.Home -> HomeRoute(
                                            style = UiStyle.MATERIAL, state = state.forHomePage(), scheduler = scheduler,
                                            actions = actions,
                                            onOpenClean = { expandedCleanCategory = ""; page = BaiZePage.Clean },
                                            onOpenPlan = { expandedCleanCategory = "__open_plan__"; page = BaiZePage.Clean }
                                        )
                                    BaiZePage.Clean -> CleanRoute(
                                        style = UiStyle.MATERIAL,
                                        dashboard = state.forCleanPage(),
                                        scheduler = scheduler,
                                        dashboardActions = actions,
                                        expandedCategory = expandedCleanCategory,
                                        onExpandedCategoryChanged = { expandedCleanCategory = it }
                                    )
                                    BaiZePage.Records -> HistoryRoute(UiStyle.MATERIAL, state.forHistoryPage(), actions)
                                    BaiZePage.Settings -> SettingsRoute(UiStyle.MATERIAL, state.forSettingsPage(), scheduler, appearance, actions) { page = BaiZePage.Records }
                                }
                            }
                            MaterialFloatingDock(
                                selected = page,
                                onSelected = { page = it },
                                floating = appearance.floatingDock,
                                modifier = Modifier.align(Alignment.BottomCenter)
                            )
                        }

                        UiStyle.MIUIX -> Box(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(if (liquidBackdrop != null && showDock) Modifier.layerBackdrop(liquidBackdrop)
                                        else if (blurActive && showDock) Modifier.hazeSource(state = hazeState) else Modifier)
                            ) {
                                MiuiXBackdrop(dark, amoled)
                                AnimatedPageHost(
                                    page = page,
                                    style = UiStyle.MIUIX,
                                    modifier = Modifier
                                        .fillMaxSize()
                                ) { targetPage ->
                                    when (targetPage) {
                                        BaiZePage.Home -> HomeRoute(
                                            style = UiStyle.MIUIX, state = state.forHomePage(), scheduler = scheduler,
                                            actions = actions,
                                            onOpenClean = { expandedCleanCategory = ""; page = BaiZePage.Clean },
                                            onOpenPlan = { expandedCleanCategory = "__open_plan__"; page = BaiZePage.Clean }
                                        )
                                        BaiZePage.Clean -> CleanRoute(
                                            style = UiStyle.MIUIX,
                                            dashboard = state.forCleanPage(),
                                            scheduler = scheduler,
                                            dashboardActions = actions,
                                            expandedCategory = expandedCleanCategory,
                                            onExpandedCategoryChanged = { expandedCleanCategory = it }
                                        )
                                        BaiZePage.Records -> HistoryRoute(UiStyle.MIUIX, state.forHistoryPage(), actions)
                                        BaiZePage.Settings -> SettingsRoute(UiStyle.MIUIX, state.forSettingsPage(), scheduler, appearance, actions,
                                            onDetailChanged = { settingsDetailVisible = it }) { page = BaiZePage.Records }
                                    }
                                }
                            }
                            if (showDock) LuoShuLiquidDock(
                                selectedIndex = page.ordinal,
                                items = miuixNavItems,
                                onSelected = { index -> page = BaiZePage.entries[index] },
                                hazeState = hazeState,
                                backdrop = liquidBackdrop,
                                effectsEnabled = blurActive,
                                modifier = Modifier.align(Alignment.BottomCenter)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedPageHost(
    page: BaiZePage,
    style: UiStyle,
    modifier: Modifier = Modifier,
    content: @Composable (BaiZePage) -> Unit
) {
    AnimatedContent(
        targetState = page,
        modifier = modifier,
        contentKey = { it },
        transitionSpec = {
            val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
            val degraded = io.github.xgl34222220.baize.performance.PerformanceRuntime.degraded.value
            val enterDuration = if (degraded) 90 else if (style == UiStyle.MIUIX) 210 else 180
            val exitDuration = if (degraded) 70 else if (style == UiStyle.MIUIX) 140 else 120
            val enterDivisor = if (degraded) Int.MAX_VALUE else if (style == UiStyle.MIUIX) 14 else 18
            val exitDivisor = if (degraded) Int.MAX_VALUE else if (style == UiStyle.MIUIX) 20 else 24

            (fadeIn(tween(enterDuration)) + slideInHorizontally(tween(enterDuration)) { width ->
                direction * width / enterDivisor
            }).togetherWith(
                fadeOut(tween(exitDuration)) + slideOutHorizontally(tween(exitDuration)) { width ->
                    -direction * width / exitDivisor
                }
            )
        },
        label = "baizePageMotion"
    ) { targetPage ->
        content(targetPage)
    }
}

@Composable
private fun MiuiXBackdrop(dark: Boolean, amoled: Boolean) {
    val colors = BaiZeTokens.colors
    val accent = MaterialTheme.colorScheme.primary
    // Static, broad color reflections give the glass a backdrop without an animation loop.
    Box(
        Modifier.fillMaxSize()
            .background(if (amoled) Color.Black else colors.surfaceBase)
            .drawWithCache {
                val topReflection = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = if (dark) .10f else .075f), Color.Transparent),
                    center = Offset(size.width * .98f, size.height * .015f),
                    radius = size.width * .94f
                )
                val lowerReflection = Brush.radialGradient(
                    colors = listOf(Color(0xFF54BCBD).copy(alpha = if (dark) .055f else .045f), Color.Transparent),
                    center = Offset(-size.width * .14f, size.height * .76f),
                    radius = size.width * 1.08f
                )
                onDrawBehind {
                    if (!amoled) {
                        drawRect(topReflection)
                        drawRect(lowerReflection)
                    }
                }
            }
    )
}

@Composable
private fun MaterialFloatingDock(
    selected: BaiZePage,
    onSelected: (BaiZePage) -> Unit,
    floating: Boolean,
    modifier: Modifier = Modifier
) {
    val items = remember { BaiZePage.entries.map { MiuixLiquidNavItem(it.title, it.icon) } }
    // The shared geometry keeps tap targets, insets and font scaling consistent. In Material
    // mode MiuixLiquidDock uses an opaque surface and a standard tonal selection indicator.
    MiuixLiquidDock(
        selectedIndex = selected.ordinal,
        items = items,
        onSelected = { onSelected(BaiZePage.entries[it]) },
        floating = floating,
        modifier = modifier
    )
}

private fun DashboardUiState.forHomePage(): DashboardUiState = copy(
    rawLogName = "", rawLog = "", history = emptyList()
)

private fun DashboardUiState.forCleanPage(): DashboardUiState = copy(
    rawLogName = "", rawLog = "", history = emptyList(), lifetimeRuns = 0,
    lifetimeReleased = 0, lifetimeFiles = 0, lifetimeEmptyFiles = 0,
    lifetimeEmptyDirs = 0, lifetimeFragments = 0, lifetimeElapsed = 0
)

private fun DashboardUiState.forHistoryPage(): DashboardUiState = DashboardUiState(
    lastReleased = lastReleased,
    scanCompleted = scanCompleted,
    scanBytes = scanBytes,
    scanFiles = scanFiles,
    scanEmptyFiles = scanEmptyFiles,
    scanEmptyDirs = scanEmptyDirs,
    scanFragments = scanFragments,
    scanErrors = scanErrors,
    scanElapsed = scanElapsed,
    lifetimeRuns = lifetimeRuns,
    lifetimeReleased = lifetimeReleased,
    lifetimeFiles = lifetimeFiles,
    lifetimeEmptyFiles = lifetimeEmptyFiles,
    lifetimeEmptyDirs = lifetimeEmptyDirs,
    lifetimeFragments = lifetimeFragments,
    lifetimeElapsed = lifetimeElapsed,
    recentApps = recentApps,
    recentJunk = recentJunk,
    history = history
)

private fun DashboardUiState.forLogsPage(): DashboardUiState = DashboardUiState(
    connected = connected, ready = ready, running = running, serviceText = serviceText,
    taskPhase = taskPhase, rawLogName = rawLogName, rawLog = rawLog
)

private fun DashboardUiState.forSettingsPage(): DashboardUiState = DashboardUiState(
    connecting = connecting, connectionFailed = connectionFailed,
    connected = connected, ready = ready, running = running, serviceText = serviceText,
    taskPhase = taskPhase, whitelistCount = whitelistCount, scanPerformance = scanPerformance
)
