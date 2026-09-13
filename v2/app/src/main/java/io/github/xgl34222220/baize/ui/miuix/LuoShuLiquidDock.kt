package io.github.xgl34222220.baize.ui.miuix

// Visual/optical parameters ported from LuoShu@fe4df5f LuoShuAppShell.kt.
// Only the four BaiZe destinations and capability/accessibility fallback differ.
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.*
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.glass.liquidGlassLens
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.squircle.squircleClip

internal enum class DockRendering { LIQUID, HAZE, OPAQUE }
internal fun dockRendering(sdk: Int, hardware: Boolean, effects: Boolean,
    backdropAvailable: Boolean, hazeAvailable: Boolean): DockRendering = when {
    !hardware || !effects -> DockRendering.OPAQUE
    sdk >= 33 && backdropAvailable -> DockRendering.LIQUID
    sdk >= 31 && hazeAvailable -> DockRendering.HAZE
    else -> DockRendering.OPAQUE
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun LuoShuLiquidDock(
    selectedIndex: Int,
    items: List<MiuixLiquidNavItem>,
    onSelected: (Int) -> Unit,
    hazeState: HazeState?,
    backdrop: LayerBackdrop?,
    effectsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val appearance = LocalAppearanceSettings.current
    val scheme = MaterialTheme.colorScheme
    val tokens = BaiZeTokens.colors
    val dark = scheme.background.luminance() < .5f
    val amoled = dark && appearance.amoledBlack
    val floating = appearance.floatingDock
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val itemHeight = 60.dp + (16f * (LocalDensity.current.fontScale - 1f).coerceIn(0f, 1f)).dp
    val shape = if (floating) RoundedCornerShape(31.dp) else RoundedCornerShape(topStart = 31.dp, topEnd = 31.dp)
    val mode = dockRendering(Build.VERSION.SDK_INT, LocalView.current.isHardwareAccelerated,
        effectsEnabled && appearance.glassEnabled && appearance.blurEnabled && !amoled,
        backdrop != null, hazeState?.blurEnabled == true)
    val glass = mode != DockRendering.OPAQUE
    // Do not initialise the runtime backdrop on devices taking the API 26-32 fallback.
    val shellBackdrop = if (mode == DockRendering.LIQUID) rememberLayerBackdrop() else null
    val shellTint = if (dark) scheme.surface.copy(alpha = .39f) else Color.White.copy(alpha = .40f)
    val effect = when (mode) {
        DockRendering.LIQUID -> Modifier.drawBackdrop(
            backdrop = requireNotNull(backdrop), shape = { shape },
            effects = {
                padding = maxOf(padding, 30.dp.toPx())
                colorControls(brightness = if (dark) -.015f else .025f, contrast = 1.05f, saturation = 1.40f)
                blur(9.dp.toPx(), 9.dp.toPx())
                liquidGlassLens(17.dp.toPx(), 13.dp.toPx(), depthEffect = true, chromaticAberration = .045f)
            },
            highlight = {
                (if (dark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight)
                    .copy(alpha = if (dark) .72f else .86f)
            },
            onDrawSurface = {
                drawRect(shellTint)
                drawRect(Brush.radialGradient(
                    listOf(Color.White.copy(alpha = if (dark) .06f else .20f), Color.Transparent),
                    center = Offset(size.width * .16f, 0f), radius = size.width * .70f))
            },
        )
        DockRendering.HAZE -> Modifier.hazeEffect(state = requireNotNull(hazeState), style = HazeMaterials.ultraThin()) {
            blurRadius = 30.dp
            noiseFactor = .018f
            fallbackTint = HazeTint(tokens.surfaceOverlay)
        }.background(Brush.verticalGradient(if (dark)
            listOf(Color.White.copy(alpha = .10f), Color.White.copy(alpha = .035f)) else
            listOf(Color.White.copy(alpha = .22f), Color.White.copy(alpha = .09f))))
        // No transparent imitation without an actual blur: background text must not ghost.
        DockRendering.OPAQUE -> Modifier.background(if (amoled) Color.Black else tokens.surfaceOverlay.copy(alpha = 1f))
    }
    Box(modifier.testTag("luoshu-dock")
        .then(if (floating) Modifier.padding(horizontal = 20.dp).padding(bottom = bottomInset + 12.dp) else Modifier)
        .fillMaxWidth().height(itemHeight + 12.dp + if (floating) 0.dp else bottomInset)) {
        Box(Modifier.fillMaxSize().testTag("dock-shell-${mode.name.lowercase()}")
            .shadow(if (floating) 18.dp else 5.dp, shape, clip = false)
            // Squircle clipping also uses RuntimeShader; gate it with the refractive layer.
            .then(if (floating && mode == DockRendering.LIQUID) Modifier.squircleClip(31.dp) else Modifier.clip(shape))
            .then(if (shellBackdrop != null) Modifier.layerBackdrop(shellBackdrop) else Modifier)
            .then(effect)
            .border(if (mode == DockRendering.LIQUID) .45.dp else .7.dp,
                if (glass) Color.White.copy(alpha = if (dark) .11f else .32f)
                else Color.White.copy(alpha = if (dark) .10f else .50f), shape))
        BoxWithConstraints(Modifier.fillMaxSize()
            .padding(start = 6.dp, top = 6.dp, end = 6.dp, bottom = if (floating) 6.dp else bottomInset + 6.dp)) {
            val itemWidth = maxWidth / items.size.toFloat()
            val target = selectedIndex.coerceIn(items.indices)
            val stretch = remember { Animatable(0f) }
            var direction by remember { mutableFloatStateOf(0f) }
            var previous by remember { mutableIntStateOf(target) }
            LaunchedEffect(target, glass) {
                if (target != previous) {
                    direction = if (target > previous) 1f else -1f
                    previous = target
                    if (glass) {
                        stretch.snapTo(1f)
                        stretch.animateTo(0f, spring(dampingRatio = .55f, stiffness = Spring.StiffnessMediumLow))
                    }
                }
                if (!glass) stretch.snapTo(0f)
            }
            val x by animateDpAsState(itemWidth * target,
                spring(dampingRatio = if (glass) .68f else .84f,
                    stiffness = if (glass) 310f else Spring.StiffnessMediumLow), label = "luoshuDockIndicator")
            val extra = if (glass) 13.dp * stretch.value else 0.dp
            val indicatorShape = RoundedCornerShape(23.dp)
            val indicatorColor = scheme.primary.copy(alpha = if (dark) .28f else .16f)
            val lens = if (shellBackdrop != null) Modifier.drawBackdrop(
                backdrop = shellBackdrop, shape = { indicatorShape },
                effects = {
                    val amount = stretch.value
                    padding = maxOf(padding, 22.dp.toPx())
                    colorControls(brightness = .015f, contrast = 1.06f, saturation = 1.34f)
                    blur(3.dp.toPx(), 3.dp.toPx())
                    liquidGlassLens((13.dp + 4.dp * amount).toPx(), (14.dp + 5.dp * amount).toPx(),
                        depthEffect = true, chromaticAberration = .08f + .10f * amount)
                },
                highlight = {
                    (if (dark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight).copy(alpha = .88f)
                },
                layerBlock = { scaleY = 1f - .045f * stretch.value },
                onDrawSurface = {
                    drawRect(indicatorColor)
                    drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = if (dark) .055f else .16f), Color.Transparent)))
                },
            ) else Modifier.drawBehind {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(Brush.verticalGradient(if (glass) listOf(
                    indicatorColor.copy(alpha = (indicatorColor.alpha * 1.18f).coerceAtMost(1f)),
                    indicatorColor.copy(alpha = indicatorColor.alpha * .72f)) else listOf(indicatorColor, indicatorColor)), cornerRadius = radius)
                if (glass) drawRoundRect(Brush.radialGradient(
                    listOf(Color.White.copy(alpha = if (dark) .10f else .24f), Color.Transparent),
                    center = Offset(size.width * .27f, 0f), radius = size.width * .74f), cornerRadius = radius)
            }
            // Record ONLY the shell above. Labels stay outside both shader/capture layers.
            Box(Modifier.offset(x = x + 4.dp - if (direction < 0f) extra else 0.dp)
                .width((itemWidth - 8.dp + extra).coerceAtLeast(1.dp)).height(itemHeight)
                .shadow(if (shellBackdrop != null) 4.dp else 3.dp, indicatorShape, clip = false)
                .then(if (shellBackdrop != null) Modifier.squircleClip(23.dp) else Modifier.clip(indicatorShape)).then(lens)
                .border(1.dp, Color.White.copy(alpha = if (dark) .18f else .46f), indicatorShape))
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                items.forEachIndexed { index, item ->
                    val selected = index == target
                    val interactions = remember(item.title) { MutableInteractionSource() }
                    val pressed by interactions.collectIsPressedAsState()
                    val base = if (selected) scheme.primary else scheme.onSurfaceVariant.copy(alpha = .90f)
                    val color by animateColorAsState(if (pressed) base.copy(alpha = .62f) else base, tween(170), label = "dockColor$index")
                    val scale by animateFloatAsState(when {
                        pressed -> .92f; selected && glass -> 1.035f; else -> 1f
                    }, spring(dampingRatio = .66f, stiffness = 520f), label = "dockScale$index")
                    Column(Modifier.width(itemWidth).height(itemHeight).testTag("dock-tab-$index")
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .clip(indicatorShape)
                        .selectable(selected, role = Role.Tab, interactionSource = interactions, indication = null,
                            onClick = { if (!selected) onSelected(index) }),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        val opticalScale = when (index) { 0, 3 -> .94f; 2 -> .96f; else -> 1f }
                        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                            Icon(item.icon, null, Modifier.fillMaxSize().scale(opticalScale), tint = color)
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(item.title, color = color, fontSize = 12.sp, lineHeight = 17.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
