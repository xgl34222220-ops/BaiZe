#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"Patch target not found: {target}\n--- expected ---\n{old}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


app = Path("v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt")
liquid = Path("v2/app/src/main/java/io/github/xgl34222220/baize/ui/miuix/MiuixLiquidComponents.kt")
build = "v2/app/build.gradle.kts"
module_prop = "v2/module/module.prop"
package_script = "v2/scripts/package-module.sh"
customize = "v2/module/customize.sh"

# Dependencies and version metadata.
replace_once(
    build,
    '    implementation("com.materialkolor:material-kolor:2.0.0")\n',
    '    implementation("com.materialkolor:material-kolor:2.0.0")\n'
    '    implementation("dev.chrisbanes.haze:haze:1.6.10")\n'
    '    implementation("dev.chrisbanes.haze:haze-materials:1.6.10")\n',
)
replace_once(build, '        versionCode = 21900\n        versionName = "2.0.0-alpha39"', '        versionCode = 22000\n        versionName = "2.0.0-alpha40"')
replace_once(module_prop, 'version=v2.0.0-alpha39\nversionCode=21900', 'version=v2.0.0-alpha40\nversionCode=22000')
replace_once(package_script, 'OUTPUT="$OUT/BaiZe-v2-Alpha39-Module.zip"', 'OUTPUT="$OUT/BaiZe-v2-Alpha40-Module.zip"')
replace_once(package_script, 'echo "已生成 Alpha 39 设置双皮肤模块：$OUTPUT"', 'echo "已生成 Alpha 40 Haze 动效与清理模块：$OUTPUT"')
replace_once(customize, 'ui_print "- 安装白泽 v2 Alpha 39 设置双皮肤版"', 'ui_print "- 安装白泽 v2 Alpha 40 Haze 动效版"')

text = app.read_text(encoding="utf-8")

# Imports for page transitions and Haze source capture.
anchor = "import android.text.format.Formatter\n"
imports = (
    "import android.text.format.Formatter\n"
    "import androidx.compose.animation.AnimatedContent\n"
    "import androidx.compose.animation.fadeIn\n"
    "import androidx.compose.animation.fadeOut\n"
    "import androidx.compose.animation.slideInHorizontally\n"
    "import androidx.compose.animation.slideOutHorizontally\n"
    "import androidx.compose.animation.togetherWith\n"
    "import androidx.compose.animation.core.tween\n"
)
if "import androidx.compose.animation.AnimatedContent" not in text:
    if anchor not in text:
        raise SystemExit("App import anchor not found")
    text = text.replace(anchor, imports, 1)

haze_anchor = "import com.google.android.material.color.MaterialColors\n"
haze_imports = (
    "import com.google.android.material.color.MaterialColors\n"
    "import dev.chrisbanes.haze.hazeSource\n"
    "import dev.chrisbanes.haze.rememberHazeState\n"
)
if "import dev.chrisbanes.haze.hazeSource" not in text:
    if haze_anchor not in text:
        raise SystemExit("Haze import anchor not found")
    text = text.replace(haze_anchor, haze_imports, 1)

# Replace the app shell with animated page hosts and a real Haze source for Miuix.
start = text.find("@Composable\nfun BaiZeMiuixApp(")
end = text.find("\n@Composable\nprivate fun MiuiXBackdrop", start)
if start < 0 or end < 0:
    raise SystemExit("BaiZeMiuixApp block not found")

new_shell = '''@Composable
fun BaiZeMiuixApp(
    state: DashboardUiState,
    scheduler: SchedulerUiState,
    actions: DashboardActions,
    appearance: AppearanceSettings
) {
    BaiZeTheme(appearance) {
        CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
            val dark = MaterialTheme.colorScheme.background.luminance() < .5f
            val amoled = dark && appearance.amoledBlack
            val hazeState = rememberHazeState(
                blurEnabled = appearance.blurEnabled && appearance.glassEnabled
            )
            var page by rememberSaveable { mutableStateOf(BaiZePage.Home) }
            val miuixNavItems = remember {
                BaiZePage.entries.map { MiuixLiquidNavItem(it.title, it.icon) }
            }

            when (appearance.uiStyle) {
                UiStyle.MATERIAL -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    AnimatedPageHost(
                        page = page,
                        style = UiStyle.MATERIAL,
                        modifier = Modifier.fillMaxSize()
                    ) { targetPage ->
                        when (targetPage) {
                            BaiZePage.Home -> HomeRoute(UiStyle.MATERIAL, state, actions) { page = BaiZePage.Clean }
                            BaiZePage.Clean -> CleanRoute(UiStyle.MATERIAL, state, scheduler, actions)
                            BaiZePage.Records -> HistoryRoute(UiStyle.MATERIAL, state, actions)
                            BaiZePage.Logs -> LogsRoute(UiStyle.MATERIAL, state, actions)
                            BaiZePage.Settings -> SettingsRoute(UiStyle.MATERIAL, state, scheduler, appearance, actions)
                        }
                    }
                    MaterialFloatingDock(
                        selected = page,
                        onSelected = { page = it },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }

                UiStyle.MIUIX -> Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .hazeSource(state = hazeState)
                    ) {
                        MiuiXBackdrop(dark, amoled)
                        AnimatedPageHost(
                            page = page,
                            style = UiStyle.MIUIX,
                            modifier = Modifier.fillMaxSize()
                        ) { targetPage ->
                            when (targetPage) {
                                BaiZePage.Home -> HomeRoute(UiStyle.MIUIX, state, actions) { page = BaiZePage.Clean }
                                BaiZePage.Clean -> CleanRoute(UiStyle.MIUIX, state, scheduler, actions)
                                BaiZePage.Records -> HistoryRoute(UiStyle.MIUIX, state, actions)
                                BaiZePage.Logs -> LogsRoute(UiStyle.MIUIX, state, actions)
                                BaiZePage.Settings -> SettingsRoute(UiStyle.MIUIX, state, scheduler, appearance, actions)
                            }
                        }
                    }
                    MiuixLiquidDock(
                        selectedIndex = page.ordinal,
                        items = miuixNavItems,
                        onSelected = { index -> page = BaiZePage.entries[index] },
                        hazeState = hazeState,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
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
            val enterDuration = if (style == UiStyle.MIUIX) 300 else 250
            val exitDuration = if (style == UiStyle.MIUIX) 210 else 170
            val enterDivisor = if (style == UiStyle.MIUIX) 8 else 11
            val exitDivisor = if (style == UiStyle.MIUIX) 13 else 16

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
'''
text = text[:start] + new_shell + text[end:]

# Use the DataStore-backed appearance state consistently in remaining shared Miuix surfaces.
old_glass = '''    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val amoled = dark && ThemeManager.isAmoledEnabled(context)
    val glass = ThemeManager.isGlassEnabled(context)
'''
new_glass = '''    val settings = LocalAppearanceSettings.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val amoled = dark && settings.amoledBlack
    val glass = settings.glassEnabled
'''
if old_glass in text:
    text = text.replace(old_glass, new_glass, 1)
elif new_glass not in text:
    raise SystemExit("GlassSurface state block not found")

text = text.replace("Miuix × Liquid Glass · Alpha 39", "Miuix × Haze Glass · Alpha 40")

# Remove pages and helpers which have already left navigation in Alpha 35-39.
legacy_start = text.find("\n@Composable\nprivate fun ToolRow(")
legacy_end = text.find("\n@Composable\nprivate fun MaterialFloatingDock(", legacy_start)
if legacy_start >= 0 and legacy_end >= 0:
    text = text[:legacy_start] + text[legacy_end:]
elif "private fun ToolRow(" in text or "private fun PlanPage(" in text or "private fun SettingsPage(" in text:
    raise SystemExit("Legacy UI block boundaries not found")

# Remove helpers that only belonged to the deleted legacy pages.
helper_start = text.find("\nprivate fun enabledScheduleCount(")
helper_end = text.find("\nprivate fun formatElapsedUi(", helper_start)
if helper_start >= 0 and helper_end >= 0:
    text = text[:helper_start] + text[helper_end:]

app.write_text(text, encoding="utf-8")

# Add true Haze effect to the Miuix dock while preserving a solid fallback.
text = liquid.read_text(encoding="utf-8")
if "import dev.chrisbanes.haze.HazeState" not in text:
    text = text.replace(
        "import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings\n",
        "import dev.chrisbanes.haze.HazeState\n"
        "import dev.chrisbanes.haze.hazeEffect\n"
        "import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi\n"
        "import dev.chrisbanes.haze.materials.HazeMaterials\n"
        "import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings\n",
        1,
    )

text = text.replace(
    '''@Composable
fun MiuixLiquidDock(
    selectedIndex: Int,
    items: List<MiuixLiquidNavItem>,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {''',
    '''@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun MiuixLiquidDock(
    selectedIndex: Int,
    items: List<MiuixLiquidNavItem>,
    onSelected: (Int) -> Unit,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier
) {''',
    1,
)

old_colors = '''    val dockColor = when {
        amoled -> Color(0xE8000000)
        dark -> scheme.surface.copy(alpha = if (settings.blurEnabled) .82f else .98f)
        else -> Color.White.copy(alpha = if (settings.blurEnabled) .78f else .98f)
    }
    val borderColor = if (dark) Color.White.copy(alpha = .13f) else Color.White.copy(alpha = .88f)
'''
new_colors = '''    val activeHazeState = hazeState.takeIf {
        settings.blurEnabled && settings.glassEnabled && !amoled
    }
    val dockColor = when {
        amoled -> Color(0xEE000000)
        activeHazeState != null && dark -> scheme.surface.copy(alpha = .28f)
        activeHazeState != null -> Color.White.copy(alpha = .22f)
        dark -> scheme.surface.copy(alpha = .98f)
        else -> Color.White.copy(alpha = .98f)
    }
    val borderColor = if (dark) Color.White.copy(alpha = .15f) else Color.White.copy(alpha = .82f)
    val hazeModifier = activeHazeState?.let { state ->
        Modifier.hazeEffect(
            state = state,
            style = HazeMaterials.ultraThin()
        ) {
            blurRadius = 28.dp
            noiseFactor = .06f
        }
    } ?: Modifier
'''
if old_colors in text:
    text = text.replace(old_colors, new_colors, 1)
elif new_colors not in text:
    raise SystemExit("Miuix dock color block not found")

old_chain = '''            .shadow(22.dp, shape, clip = false)
            .clip(shape)
            .background(dockColor)
            .border(1.dp, borderColor, shape)
'''
new_chain = '''            .shadow(22.dp, shape, clip = false)
            .clip(shape)
            .then(hazeModifier)
            .background(dockColor)
            .border(1.dp, borderColor, shape)
'''
if old_chain in text:
    text = text.replace(old_chain, new_chain, 1)
elif new_chain not in text:
    raise SystemExit("Miuix dock modifier chain not found")

liquid.write_text(text, encoding="utf-8")
