from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


# 1) Root scheduler: sleep exactly until the calculated due second.
service = ROOT / "service.sh"
text = read(service)
text = replace_once(
    text,
    "MIN_SLEEP_SECONDS=${BAIZE_MIN_SLEEP_SECONDS:-30}",
    "MIN_SLEEP_SECONDS=${BAIZE_MIN_SLEEP_SECONDS:-1}",
    "minimum scheduler sleep",
)
text = replace_once(
    text,
    "CONDITION_RETRY_SECONDS=${BAIZE_CONDITION_RETRY_SECONDS:-15}",
    "CONDITION_RETRY_SECONDS=${BAIZE_CONDITION_RETRY_SECONDS:-5}",
    "condition retry cadence",
)
write(service, text)

# 2) Home countdown should visually update every second as well.
model = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/HomeTaskPresentation.kt"
text = read(model)
text = replace_once(text, "delay(30_000L)", "delay(1_000L)", "home countdown refresh")
write(model, text)

# 3) Restore the actual MIUIx liquid glass floating dock.
liquid = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/ui/miuix/MiuixLiquidComponents.kt"
text = read(liquid)
if "import androidx.compose.ui.draw.drawBehind" not in text:
    text = replace_once(
        text,
        "import androidx.compose.ui.draw.clip\n",
        "import androidx.compose.ui.draw.clip\nimport androidx.compose.ui.draw.drawBehind\n",
        "drawBehind import",
    )
if "import androidx.compose.ui.geometry.Offset" not in text:
    text = replace_once(
        text,
        "import androidx.compose.ui.graphics.Color\n",
        "import androidx.compose.ui.geometry.Offset\nimport androidx.compose.ui.graphics.Brush\nimport androidx.compose.ui.graphics.Color\n",
        "liquid graphics imports",
    )

liquid_dock = r'''@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun MiuixLiquidDock(
    selectedIndex: Int,
    items: List<MiuixLiquidNavItem>,
    onSelected: (Int) -> Unit,
    hazeState: HazeState? = null,
    floating: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    val settings = LocalAppearanceSettings.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val amoled = dark && settings.amoledBlack
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val shape = if (floating) {
        RoundedCornerShape(34.dp)
    } else {
        RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)
    }

    val activeHazeState = hazeState.takeIf {
        settings.blurEnabled && settings.glassEnabled && !amoled
    }
    val dockColor = when {
        amoled -> Color(0xEE000000)
        activeHazeState != null && dark -> scheme.surface.copy(alpha = .28f)
        activeHazeState != null -> Color.White.copy(alpha = .22f)
        dark -> scheme.surface.copy(alpha = .96f)
        else -> Color.White.copy(alpha = .94f)
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

    BoxWithConstraints(
        modifier = modifier
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
            .shadow(if (floating) 18.dp else 7.dp, shape, clip = false)
            .clip(shape)
            .then(hazeModifier)
            .background(dockColor)
            .border(1.dp, borderColor, shape)
            .drawBehind {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (dark) .13f else .44f),
                            Color.Transparent
                        )
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(34.dp.toPx()),
                    size = size.copy(height = size.height * .58f)
                )
                if (!amoled && settings.glassEnabled) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(scheme.primary.copy(alpha = .14f), Color.Transparent),
                            center = Offset(size.width * .18f, size.height * .08f),
                            radius = size.width * .6f
                        ),
                        radius = size.width * .6f,
                        center = Offset(size.width * .18f, size.height * .08f)
                    )
                }
            }
            .padding(
                start = 6.dp,
                top = 7.dp,
                end = 6.dp,
                bottom = if (floating) 7.dp else bottomInset + 7.dp
            )
    ) {
        val itemWidth = maxWidth / items.size.toFloat()
        val compact = items.size > 4
        val targetIndex = selectedIndex.coerceIn(items.indices)
        val indicatorX by animateDpAsState(
            targetValue = itemWidth * targetIndex.toFloat(),
            animationSpec = spring(
                dampingRatio = .72f,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "miuixLiquidIndicator"
        )

        Box(
            modifier = Modifier
                .offset(x = indicatorX + 4.dp)
                .width(itemWidth - 8.dp)
                .height(58.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            scheme.primary.copy(alpha = if (dark) .28f else .20f),
                            scheme.tertiary.copy(alpha = if (dark) .20f else .13f)
                        )
                    )
                )
                .border(
                    1.dp,
                    Color.White.copy(alpha = if (dark) .12f else .62f),
                    RoundedCornerShape(24.dp)
                )
        )

        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                val active = index == targetIndex
                val iconColor by animateColorAsState(
                    targetValue = if (active) scheme.primary else scheme.onSurfaceVariant,
                    label = "miuixDockIconColor"
                )
                val textColor by animateColorAsState(
                    targetValue = if (active) scheme.primary else scheme.onSurfaceVariant.copy(alpha = .78f),
                    label = "miuixDockTextColor"
                )

                Column(
                    modifier = Modifier
                        .width(itemWidth)
                        .height(58.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .clickable { onSelected(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.title,
                        modifier = Modifier.size(if (compact) 20.dp else if (active) 23.dp else 21.dp),
                        tint = iconColor
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = item.title,
                        color = textColor,
                        fontSize = if (compact) 9.sp else 10.sp,
                        lineHeight = if (compact) 11.sp else 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}'''

text, count = re.subn(
    r'@OptIn\(ExperimentalHazeMaterialsApi::class\)\n@Composable\nfun MiuixLiquidDock\(.*?\n}\n\n@Composable\nfun MiuixOverviewHero',
    liquid_dock + '\n\n@Composable\nfun MiuixOverviewHero',
    text,
    count=1,
    flags=re.S,
)
if count != 1:
    raise RuntimeError("MIUIx liquid dock function not found")
write(liquid, text)

# 4) Material 3 remains a distinct theme, but make it compact instead of oversized.
material_home = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/material/HomeScreenMaterial.kt"
text = read(material_home)
text = replace_once(text, 'Text("白泽", style = MaterialTheme.typography.headlineLarge)', 'Text("白泽", style = MaterialTheme.typography.headlineMedium)', "material title size")
text = replace_once(text, 'FilledTonalIconButton(onClick = onRefresh, modifier = Modifier.size(48.dp))', 'FilledTonalIconButton(onClick = onRefresh, modifier = Modifier.size(44.dp))', "material refresh size")
start = text.index("private fun MaterialNextTaskCard(")
end = text.index("@Composable\nprivate fun MaterialPrimaryActions", start)
block = text[start:end]
block = replace_once(block, "shape = MaterialTheme.shapes.extraLarge", "shape = MaterialTheme.shapes.large", "material next card shape")
block = replace_once(block, "Column(Modifier.padding(horizontal = 24.dp, vertical = 22.dp))", "Column(Modifier.padding(horizontal = 20.dp, vertical = 17.dp))", "material next card padding")
block = replace_once(block, "Spacer(Modifier.height(18.dp))", "Spacer(Modifier.height(10.dp))", "material next card title gap")
block = replace_once(block, "style = MaterialTheme.typography.headlineMedium", "style = MaterialTheme.typography.titleLarge", "material next task title")
block = replace_once(block, "fontSize = 28.sp,\n                lineHeight = 34.sp", "fontSize = 19.sp,\n                lineHeight = 25.sp", "material next task status")
block = replace_once(block, "Spacer(Modifier.height(12.dp))", "Spacer(Modifier.height(7.dp))", "material next card footer gap")
block = replace_once(block, "style = MaterialTheme.typography.bodyMedium", "style = MaterialTheme.typography.bodySmall", "material next card helper")
text = text[:start] + block + text[end:]
text = replace_once(text, ".height(82.dp)", ".height(74.dp)", "material primary action height")
write(material_home, text)

# 5) Make the Material dock smaller; MIUIx keeps the liquid glass dock above.
app = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt"
text = read(app)
start = text.index("private fun MaterialFloatingDock(")
end = text.index("private fun formatElapsedUi", start)
block = text[start:end]
block = block.replace("RoundedCornerShape(24.dp)", "RoundedCornerShape(20.dp)", 1)
block = block.replace(".padding(horizontal = 14.dp)", ".padding(horizontal = 20.dp)", 1)
block = block.replace(".padding(bottom = bottom + 10.dp)", ".padding(bottom = bottom + 8.dp)", 1)
block = block.replace("shadowElevation = if (floating) 4.dp else 0.dp", "shadowElevation = if (floating) 3.dp else 0.dp", 1)
block = block.replace("top = 6.dp", "top = 4.dp", 1)
block = block.replace("bottom = if (floating) 6.dp", "bottom = if (floating) 4.dp", 1)
block = block.replace(".size(width = 42.dp, height = 28.dp)", ".size(width = 38.dp, height = 24.dp)", 1)
block = block.replace("RoundedCornerShape(14.dp)", "RoundedCornerShape(12.dp)", 1)
block = block.replace("modifier = Modifier.size(20.dp)", "modifier = Modifier.size(19.dp)", 1)
block = block.replace("fontSize = 11.sp", "fontSize = 10.sp", 1)
text = text[:start] + block + text[end:]
write(app, text)

print("patched exact scheduler, compact M3 home and restored MIUIx liquid dock")
