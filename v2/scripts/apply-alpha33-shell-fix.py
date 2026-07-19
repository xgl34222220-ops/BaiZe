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


app = "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt"
replace_once(
    app,
    "import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings",
    "import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings\nimport io.github.xgl34222220.baize.ui.appearance.UiStyle",
)

old_root = '''@Composable
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
            var page by rememberSaveable { mutableStateOf(BaiZePage.Home) }
            Box(modifier = Modifier.fillMaxSize()) {
                MiuiXBackdrop(dark, amoled)
                when (page) {
                    BaiZePage.Home -> HomeRoute(appearance.uiStyle, state, actions)
                    BaiZePage.Plan -> PlanPage(scheduler, actions)
                    BaiZePage.Records -> RecordsPage(state, actions)
                    BaiZePage.Settings -> SettingsPage(state, scheduler, actions)
                }
                FloatingDock(
                    selected = page,
                    onSelected = { page = it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}'''
new_root = '''@Composable
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
            var page by rememberSaveable { mutableStateOf(BaiZePage.Home) }

            when (appearance.uiStyle) {
                UiStyle.MATERIAL -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    when (page) {
                        BaiZePage.Home -> HomeRoute(UiStyle.MATERIAL, state, actions)
                        BaiZePage.Plan -> PlanPage(scheduler, actions)
                        BaiZePage.Records -> RecordsPage(state, actions)
                        BaiZePage.Settings -> SettingsPage(state, scheduler, actions)
                    }
                    MaterialFloatingDock(
                        selected = page,
                        onSelected = { page = it },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }

                UiStyle.MIUIX -> Box(modifier = Modifier.fillMaxSize()) {
                    MiuiXBackdrop(dark, amoled)
                    when (page) {
                        BaiZePage.Home -> HomeRoute(UiStyle.MIUIX, state, actions)
                        BaiZePage.Plan -> PlanPage(scheduler, actions)
                        BaiZePage.Records -> RecordsPage(state, actions)
                        BaiZePage.Settings -> SettingsPage(state, scheduler, actions)
                    }
                    FloatingDock(
                        selected = page,
                        onSelected = { page = it },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }
}'''
replace_once(app, old_root, new_root)

anchor = '''private fun enabledScheduleCount(config: SchedulerUiState): Int = listOf(
    config.cacheEnabled, config.emptyEnabled, config.rulesEnabled, config.fragmentEnabled, config.deepEnabled
).count { it }'''
material_dock = '''@Composable
private fun MaterialFloatingDock(
    selected: BaiZePage,
    onSelected: (BaiZePage) -> Unit,
    modifier: Modifier = Modifier
) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Surface(
        modifier = modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = bottom + 12.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .96f),
        tonalElevation = 10.dp,
        shadowElevation = 18.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            BaiZePage.entries.forEach { item ->
                val active = item == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.large)
                        .clickable { onSelected(item) }
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 48.dp, height = 30.dp)
                            .clip(MaterialTheme.shapes.extraLarge)
                            .background(
                                if (active) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.title,
                            modifier = Modifier.size(20.dp),
                            tint = if (active) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = item.title,
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

''' + anchor
replace_once(app, anchor, material_dock)

changes = Path("v2/ALPHA33-UI-STEP1.md")
if changes.exists():
    text = changes.read_text(encoding="utf-8")
    note = "- Material 与 Miuix 现在从 App 外壳开始分流：背景和底部导航不再共用，切换后视觉差异明确。\n"
    if note not in text:
        changes.write_text(text.rstrip() + "\n" + note, encoding="utf-8")
