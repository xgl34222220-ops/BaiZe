package io.github.xgl34222220.baize.ui.miuix

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.glass.liquidGlassLens
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.colorControls
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.squircle.squircleClip

data class MiuixLiquidNavItem(
    val title: String,
    val icon: ImageVector
)

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun MiuixLiquidDock(
    selectedIndex: Int,
    items: List<MiuixLiquidNavItem>,
    onSelected: (Int) -> Unit,
    hazeState: HazeState? = null,
    backdrop: LayerBackdrop? = null,
    floating: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    val appearance = LocalAppearanceSettings.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val shape = if (floating) RoundedCornerShape(31.dp) else RoundedCornerShape(topStart = 31.dp, topEnd = 31.dp)
    val activeGlass = appearance.glassEnabled
    val runtimeLiquid = activeGlass && appearance.blurEnabled && backdrop != null && isRuntimeShaderSupported()
    val activeHaze = activeGlass && appearance.blurEnabled && !runtimeLiquid && hazeState != null
    val dockSurfaceBackdrop = rememberLayerBackdrop()

    val hazeModifier = if (activeHaze) {
        Modifier.hazeEffect(state = requireNotNull(hazeState), style = HazeMaterials.ultraThin()) {
            blurRadius = 30.dp
            noiseFactor = .018f
        }
    } else Modifier

    val glassBrush = when {
        activeGlass && dark -> Brush.verticalGradient(listOf(Color.White.copy(alpha = .10f), Color.White.copy(alpha = .035f)))
        activeGlass -> Brush.verticalGradient(listOf(Color.White.copy(alpha = .22f), Color.White.copy(alpha = .09f)))
        else -> Brush.verticalGradient(
            listOf(BaiZeTokens.colors.surfaceOverlay.copy(alpha = .98f), BaiZeTokens.colors.surfaceOverlay.copy(alpha = .98f))
        )
    }
    val shellTint = if (dark) scheme.surface.copy(alpha = .39f) else Color.White.copy(alpha = .40f)
    val liquidShellModifier = if (runtimeLiquid) {
        Modifier.drawBackdrop(
            backdrop = requireNotNull(backdrop),
            shape = { shape },
            effects = {
                padding = maxOf(padding, 30.dp.toPx())
                colorControls(
                    brightness = if (dark) -.015f else .025f,
                    contrast = 1.05f,
                    saturation = 1.40f
                )
                blur(9.dp.toPx(), 9.dp.toPx())
                liquidGlassLens(
                    refractionHeight = 17.dp.toPx(),
                    refractionAmount = 13.dp.toPx(),
                    depthEffect = true,
                    chromaticAberration = .045f
                )
            },
            highlight = {
                (if (dark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight)
                    .copy(alpha = if (dark) .72f else .86f)
            },
            onDrawSurface = {
                drawRect(shellTint)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (dark) .06f else .20f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * .16f, 0f),
                        radius = size.width * .70f
                    )
                )
            }
        )
    } else {
        Modifier
            .then(hazeModifier)
            .background(glassBrush)
            .drawBehind {
                if (activeGlass) {
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (dark) .08f else .24f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * .18f, 0f),
                            radius = size.width * .72f
                        ),
                        cornerRadius = CornerRadius(size.height / 2f)
                    )
                }
            }
    }

    Box(
        modifier = modifier
            .then(if (floating) Modifier.padding(horizontal = 12.dp).padding(bottom = bottomInset + 10.dp) else Modifier)
            .fillMaxWidth()
            .height(66.dp + if (floating) 0.dp else bottomInset)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shadow(if (floating) 18.dp else 5.dp, shape, clip = false)
                .squircleClip(31.dp)
                .then(if (runtimeLiquid) Modifier.layerBackdrop(dockSurfaceBackdrop) else Modifier)
                .then(liquidShellModifier)
                .border(
                    if (runtimeLiquid) .45.dp else .7.dp,
                    if (activeGlass) {
                        if (dark) Color.White.copy(alpha = .11f) else Color.White.copy(alpha = .32f)
                    } else if (dark) Color.White.copy(alpha = .10f) else Color.White.copy(alpha = .50f),
                    shape
                )
        )

        MiuixDockLayout(
            items = items,
            selectedIndex = selectedIndex,
            onSelected = onSelected,
            itemHeight = 54.dp,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 6.dp, top = 6.dp, end = 6.dp, bottom = if (floating) 6.dp else bottomInset + 6.dp),
            indicatorColor = scheme.primary.copy(alpha = if (dark) .28f else .16f),
            indicatorBorderColor = Color.White.copy(alpha = if (dark) .18f else .46f),
            indicatorShadow = 3.dp,
            selectedColor = scheme.primary,
            unselectedColor = scheme.onSurfaceVariant.copy(alpha = .72f),
            liquidGlass = activeGlass,
            indicatorBackdrop = dockSurfaceBackdrop.takeIf { runtimeLiquid },
            dark = dark
        )
    }
}

@Composable
private fun MiuixDockLayout(
    items: List<MiuixLiquidNavItem>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    itemHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
    indicatorColor: Color,
    indicatorBorderColor: Color = Color.Transparent,
    indicatorShadow: androidx.compose.ui.unit.Dp = 0.dp,
    selectedColor: Color,
    unselectedColor: Color,
    liquidGlass: Boolean = false,
    indicatorBackdrop: LayerBackdrop? = null,
    dark: Boolean = false
) {
    BoxWithConstraints(modifier = modifier) {
        val itemWidth = maxWidth / items.size.toFloat()
        val targetIndex = selectedIndex.coerceIn(items.indices)
        val indicatorInset = 4.dp
        val liquidStretch = remember { Animatable(0f) }
        var travelDirection by remember { mutableFloatStateOf(0f) }
        var previousIndex by remember { mutableStateOf(targetIndex) }

        LaunchedEffect(targetIndex) {
            if (targetIndex != previousIndex) {
                travelDirection = if (targetIndex > previousIndex) 1f else -1f
                previousIndex = targetIndex
                liquidStretch.snapTo(1f)
                liquidStretch.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = .55f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        }

        val indicatorX by animateDpAsState(
            targetValue = itemWidth * targetIndex.toFloat(),
            animationSpec = spring(
                dampingRatio = if (liquidGlass) .68f else .84f,
                stiffness = if (liquidGlass) 310f else Spring.StiffnessMediumLow
            ),
            label = "baizeMiuixDockIndicator"
        )
        val liquidExtra = if (liquidGlass) 13.dp * liquidStretch.value else 0.dp
        val indicatorStart = indicatorX + indicatorInset - if (travelDirection < 0f) liquidExtra else 0.dp
        val indicatorShape = RoundedCornerShape(23.dp)
        val activeLens = liquidGlass && indicatorBackdrop != null
        val movingLensModifier = if (activeLens) {
            Modifier.drawBackdrop(
                backdrop = requireNotNull(indicatorBackdrop),
                shape = { indicatorShape },
                effects = {
                    val stretch = liquidStretch.value
                    padding = maxOf(padding, 22.dp.toPx())
                    colorControls(brightness = .015f, contrast = 1.06f, saturation = 1.34f)
                    blur(3.dp.toPx(), 3.dp.toPx())
                    liquidGlassLens(
                        refractionHeight = (13.dp + 4.dp * stretch).toPx(),
                        refractionAmount = (14.dp + 5.dp * stretch).toPx(),
                        depthEffect = true,
                        chromaticAberration = .08f + .10f * stretch
                    )
                },
                highlight = {
                    (if (dark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight)
                        .copy(alpha = .88f)
                },
                layerBlock = {
                    scaleY = 1f - .045f * liquidStretch.value
                },
                onDrawSurface = {
                    drawRect(indicatorColor)
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (dark) .055f else .16f),
                                Color.Transparent
                            )
                        )
                    )
                }
            )
        } else {
            Modifier.drawBehind {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        if (liquidGlass) {
                            listOf(
                                indicatorColor.copy(alpha = (indicatorColor.alpha * 1.18f).coerceAtMost(1f)),
                                indicatorColor.copy(alpha = indicatorColor.alpha * .72f)
                            )
                        } else listOf(indicatorColor, indicatorColor)
                    ),
                    cornerRadius = radius
                )
                if (liquidGlass) {
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (dark) .10f else .24f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * .27f, 0f),
                            radius = size.width * .74f
                        ),
                        cornerRadius = radius
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .offset(x = indicatorStart)
                .width(itemWidth - (indicatorInset * 2) + liquidExtra)
                .height(itemHeight)
                .shadow(if (activeLens) 4.dp else indicatorShadow, indicatorShape, clip = false)
                .squircleClip(23.dp)
                .then(movingLensModifier)
                .border(1.dp, indicatorBorderColor, indicatorShape)
        )

        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                val selected = targetIndex == index
                val interactionSource = remember(item.title) { MutableInteractionSource() }
                val pressed by interactionSource.collectIsPressedAsState()
                val baseItemColor = if (selected) selectedColor else unselectedColor
                val itemColor by animateColorAsState(
                    targetValue = if (pressed) baseItemColor.copy(alpha = .62f) else baseItemColor,
                    animationSpec = tween(170),
                    label = "${item.title}DockColor"
                )
                val itemScale by animateFloatAsState(
                    targetValue = when {
                        pressed -> .92f
                        selected && liquidGlass -> 1.035f
                        else -> 1f
                    },
                    animationSpec = spring(dampingRatio = .66f, stiffness = 520f),
                    label = "${item.title}DockScale"
                )

                Column(
                    modifier = Modifier
                        .width(itemWidth)
                        .height(itemHeight)
                        .graphicsLayer {
                            scaleX = itemScale
                            scaleY = itemScale
                        }
                        .clip(RoundedCornerShape(23.dp))
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) { onSelected(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.title,
                        modifier = Modifier.size(22.dp),
                        tint = itemColor
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.title,
                        color = itemColor,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun MiuixOverviewHero(
    device: String,
    android: String,
    statusTitle: String,
    taskPhase: String,
    releasedText: String,
    positive: Boolean,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(30.dp)
    Card(
        modifier = modifier.fillMaxWidth().shadow(8.dp, shape, clip = false),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = BaiZeTokens.colors.surfaceRaised)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(scheme.primary.copy(alpha = .18f), Color.Transparent),
                            center = Offset(size.width, 0f),
                            radius = size.width * .78f
                        ),
                        radius = size.width * .78f,
                        center = Offset(size.width, 0f)
                    )
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = .24f), Color.Transparent)),
                        cornerRadius = CornerRadius(36.dp.toPx()),
                        size = size.copy(height = size.height * .36f)
                    )
                }
                .padding(20.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = if (positive) BaiZeTokens.colors.success else BaiZeTokens.colors.warning,
                                shape = CircleShape
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (positive) "清理引擎已连接" else "正在等待清理引擎",
                        color = scheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text("最近一次释放", color = scheme.onSurfaceVariant, fontSize = 12.sp)
                Text(
                    text = releasedText,
                    color = scheme.onSurface,
                    fontSize = 36.sp,
                    lineHeight = 42.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = scheme.onSurface.copy(alpha = .045f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (positive) Icons.Rounded.CheckCircle else Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = if (positive) BaiZeTokens.colors.success else scheme.primary
                        )
                        Spacer(Modifier.width(11.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(statusTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = listOf(device, android, taskPhase).filter { it.isNotBlank() }.joinToString(" · "),
                                color = scheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MiuixLiquidPrimaryButton(
    running: Boolean,
    scanReady: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val label = when {
        running -> "停止清理"
        scanReady -> "按扫描结果清理"
        else -> "立即智能清理"
    }
    val icon = when {
        running -> Icons.Rounded.Stop
        scanReady -> Icons.Rounded.DeleteSweep
        else -> Icons.Rounded.AutoAwesome
    }
    val containerColor = when {
        !enabled -> scheme.onSurface.copy(alpha = .12f)
        running -> BaiZeTokens.colors.danger
        else -> scheme.primary
    }
    val contentColor = if (!enabled) scheme.onSurface.copy(alpha = .45f) else Color.White

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(shape)
            .background(containerColor)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(9.dp))
            Text(label, color = contentColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}
