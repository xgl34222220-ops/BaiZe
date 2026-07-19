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
text = app.read_text(encoding="utf-8")
text = text.replace(
    """                    MaterialFloatingDock(
                        selected = page,
                        onSelected = { page = it },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )""",
    """                    MaterialFloatingDock(
                        selected = page,
                        onSelected = { page = it },
                        floating = appearance.floatingDock,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )""",
    1,
)
text = text.replace(
    """                    MiuixLiquidDock(
                        selectedIndex = page.ordinal,
                        items = miuixNavItems,
                        onSelected = { index -> page = BaiZePage.entries[index] },
                        hazeState = hazeState,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )""",
    """                    MiuixLiquidDock(
                        selectedIndex = page.ordinal,
                        items = miuixNavItems,
                        onSelected = { index -> page = BaiZePage.entries[index] },
                        hazeState = hazeState,
                        floating = appearance.floatingDock,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )""",
    1,
)
text = text.replace("Miuix × Haze Glass · Alpha 40", "Miuix × Haze Glass · Alpha 41")
start = text.index("@Composable\nprivate fun MaterialFloatingDock(")
end = text.index("\nprivate fun formatElapsedUi", start)
new_dock = '''@Composable
private fun MaterialFloatingDock(
    selected: BaiZePage,
    onSelected: (BaiZePage) -> Unit,
    floating: Boolean,
    modifier: Modifier = Modifier
) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val shape = if (floating) {
        MaterialTheme.shapes.extraLarge
    } else {
        RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    }
    val outerModifier = if (floating) {
        modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = bottom + 12.dp)
            .fillMaxWidth()
    } else {
        modifier.fillMaxWidth()
    }

    Surface(
        modifier = outerModifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .96f),
        tonalElevation = if (floating) 10.dp else 5.dp,
        shadowElevation = if (floating) 18.dp else 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 7.dp,
                    top = 7.dp,
                    end = 7.dp,
                    bottom = if (floating) 7.dp else bottom + 7.dp
                ),
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
'''
app.write_text(text[:start] + new_dock + text[end:], encoding="utf-8")

miuix = "v2/app/src/main/java/io/github/xgl34222220/baize/ui/miuix/MiuixLiquidComponents.kt"
replace_once(
    miuix,
    """    onSelected: (Int) -> Unit,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier""",
    """    onSelected: (Int) -> Unit,
    hazeState: HazeState? = null,
    floating: Boolean = true,
    modifier: Modifier = Modifier""",
)
replace_once(
    miuix,
    """    val shape = RoundedCornerShape(34.dp)""",
    """    val shape = if (floating) {
        RoundedCornerShape(34.dp)
    } else {
        RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)
    }""",
)
replace_once(
    miuix,
    """        modifier = modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = bottomInset + 10.dp)
            .fillMaxWidth()
            .shadow(22.dp, shape, clip = false)""",
    """        modifier = modifier
            .then(
                if (floating) {
                    Modifier
                        .padding(horizontal = 16.dp)
                        .padding(bottom = bottomInset + 10.dp)
                } else {
                    Modifier
                }
            )
            .fillMaxWidth()
            .shadow(if (floating) 22.dp else 8.dp, shape, clip = false)""",
)
replace_once(
    miuix,
    """            .padding(horizontal = 6.dp, vertical = 7.dp)""",
    """            .padding(
                start = 6.dp,
                top = 7.dp,
                end = 6.dp,
                bottom = if (floating) 7.dp else bottomInset + 7.dp
            )""",
)

replace_once("v2/app/build.gradle.kts", 'versionCode = 22000', 'versionCode = 22100')
replace_once("v2/app/build.gradle.kts", 'versionName = "2.0.0-alpha40"', 'versionName = "2.0.0-alpha41"')
replace_once("v2/module/module.prop", 'version=v2.0.0-alpha40', 'version=v2.0.0-alpha41')
replace_once("v2/module/module.prop", 'versionCode=22000', 'versionCode=22100')
replace_once("v2/scripts/package-module.sh", 'BaiZe-v2-Alpha40-Module.zip', 'BaiZe-v2-Alpha41-Module.zip')
replace_once(
    "v2/scripts/package-module.sh",
    '已生成 Alpha 40 Haze 动效与遗留清理模块',
    '已生成 Alpha 41 完整主题双皮肤模块',
)
replace_once(
    "v2/module/customize.sh",
    '安装白泽 v2 Alpha 40 Haze 动效与遗留清理版',
    '安装白泽 v2 Alpha 41 完整主题双皮肤版',
)

legacy_layout = Path("v2/app/src/main/res/layout/activity_theme_settings.xml")
if legacy_layout.exists():
    legacy_layout.unlink()
