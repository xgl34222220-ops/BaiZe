package io.github.xgl34222220.baize.ui.miuix

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/** 底栏项目：图标与标签顺序固定，不因页面切换改变。 */
data class MiuixLiquidNavItem(
    val title: String,
    val icon: ImageVector
)

/**
 * Page sampling, a floating glass shell, then a moving lens below crisp labels.
 * Only the small dock samples the backdrop; motion runs when the selected tab changes.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
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
    val glassActive = settings.uiStyle == UiStyle.MIUIX && settings.glassEnabled && !amoled
    val hardwareBlur = LocalView.current.isHardwareAccelerated
    val activeHaze = hazeState.takeIf { hardwareBlur && glassActive && settings.blurEnabled && it?.blurEnabled == true }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val itemHeight = 60.dp + (16 * (LocalDensity.current.fontScale - 1f).coerceIn(0f, 1f)).dp
    val shape = if (floating) RoundedCornerShape(31.dp)
        else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val surface = if (amoled) Color.Black else BaiZeTokens.colors.surfaceRaised
    val glassTint = if (dark) Color(0xFF131B27) else Color.White
    val shellTint = glassTint.copy(alpha = if (dark) .42f else .34f)
    val glassEdge = if (dark) Color.White.copy(alpha = .13f) else Color.White.copy(alpha = .70f)
    val shellEffect = activeHaze?.let { state ->
        Modifier.hazeEffect(state = state, style = HazeMaterials.ultraThin()) {
            backgroundColor = surface
            tints = listOf(HazeTint(shellTint))
            fallbackTint = HazeTint(surface)
            blurRadius = 26.dp
            noiseFactor = .012f
        }
    } ?: Modifier.background(surface)

    Box(
        modifier = modifier
            .then(if (floating) Modifier.padding(horizontal = 20.dp).padding(bottom = bottomInset + 12.dp) else Modifier)
            .fillMaxWidth()
            .height(itemHeight + 12.dp + if (floating) 0.dp else bottomInset)
    ) {
        // The shell owns its blur and shadow. The tab labels never enter its effect layer.
        Box(
            Modifier.fillMaxSize()
                .shadow(
                    if (floating) 18.dp else 3.dp, shape, clip = false,
                    ambientColor = Color.Black.copy(alpha = if (dark) .28f else .08f),
                    spotColor = Color(0xFF173B6B).copy(alpha = if (dark) .24f else .16f)
                )
                .clip(shape)
                .then(shellEffect)
                .drawWithCache {
                    val topLight = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = if (dark) .07f else .30f), Color.Transparent),
                        center = Offset(size.width * .18f, 0f), radius = size.width * .68f
                    )
                    val glaze = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = if (dark) .025f else .12f), Color.Transparent)
                    )
                    onDrawBehind {
                        if (glassActive) {
                            drawRect(glaze)
                            drawRect(topLight)
                        }
                    }
                }
                .then(if (glassActive) Modifier.border(.7.dp, glassEdge, shape) else Modifier)
        )

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
                .padding(start = 6.dp, top = 6.dp, end = 6.dp, bottom = if (floating) 6.dp else bottomInset + 6.dp)
        ) {
            val itemWidth = maxWidth / items.size.toFloat()
            val targetIndex = selectedIndex.coerceIn(items.indices)
            val stretch = remember { Animatable(0f) }
            var previousIndex by remember { mutableIntStateOf(targetIndex) }
            var direction by remember { mutableIntStateOf(1) }
            LaunchedEffect(targetIndex) {
                if (targetIndex != previousIndex) {
                    direction = if (targetIndex > previousIndex) 1 else -1
                    previousIndex = targetIndex
                    if (glassActive) {
                        stretch.snapTo(1f)
                        stretch.animateTo(0f, spring(dampingRatio = .64f, stiffness = 420f))
                    }
                }
            }
            val indicatorX by animateDpAsState(
                targetValue = itemWidth * targetIndex.toFloat(),
                animationSpec = spring(dampingRatio = .78f, stiffness = 400f),
                label = "miuixDockIndicator"
            )
            val lensExtra = if (glassActive) 8.dp * stretch.value else 0.dp
            val lensShape = RoundedCornerShape(25.dp)
            val lensTint = scheme.primary.copy(alpha = if (dark) .19f else .09f)
            val lensEffect = activeHaze?.let { state ->
                Modifier.hazeEffect(state = state, style = HazeMaterials.ultraThin()) {
                    backgroundColor = surface
                    tints = listOf(
                        HazeTint(glassTint.copy(alpha = if (dark) .50f else .42f)),
                        HazeTint(lensTint)
                    )
                    fallbackTint = HazeTint(scheme.primaryContainer.copy(alpha = .86f))
                    blurRadius = 12.dp
                    noiseFactor = .005f
                }
            } ?: Modifier.background(
                if (amoled) scheme.primary.copy(alpha = .24f)
                else scheme.primary.copy(alpha = if (dark) .19f else .105f)
            )
            Box(
                modifier = Modifier
                    .offset(x = indicatorX + 4.dp - if (direction < 0) lensExtra else 0.dp)
                    .width(itemWidth - 8.dp + lensExtra)
                    .height(itemHeight)
                    .graphicsLayer { scaleY = 1f - .035f * stretch.value }
                    .shadow(if (glassActive) 3.dp else 0.dp, lensShape, clip = false,
                        ambientColor = Color.Black.copy(alpha = .06f), spotColor = scheme.primary.copy(alpha = .12f))
                    .clip(lensShape)
                    .then(lensEffect)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = if (!glassActive) 0f else if (dark) .07f else .34f),
                                Color.Transparent,
                                scheme.primary.copy(alpha = if (glassActive) .035f else 0f)
                            )
                        )
                    )
                    .then(if (glassActive) Modifier.border(.7.dp,
                        Color.White.copy(alpha = if (dark) .16f else .55f), lensShape) else Modifier)
            )

            Row(Modifier.fillMaxWidth().selectableGroup()) {
                items.forEachIndexed { index, item ->
                    val active = index == targetIndex
                    val interactions = remember(item.title) { MutableInteractionSource() }
                    val pressed by interactions.collectIsPressedAsState()
                    val contentColor by animateColorAsState(
                        targetValue = if (active) scheme.primary else scheme.onSurfaceVariant.copy(alpha = .86f),
                        animationSpec = tween(180), label = "miuixDockContentColor"
                    )
                    val pressScale by animateFloatAsState(
                        targetValue = if (pressed) .92f else 1f,
                        animationSpec = spring(dampingRatio = .72f, stiffness = 650f),
                        label = "miuixDockPress"
                    )
                    Column(
                        modifier = Modifier.width(itemWidth).height(itemHeight)
                            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                            .clip(lensShape)
                            .selectable(
                                selected = active, role = Role.Tab, interactionSource = interactions,
                                indication = null, onClick = { if (!active) onSelected(index) }
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = item.icon, contentDescription = null,
                            modifier = Modifier.size(if (index == 0 || index == items.lastIndex) 23.dp else 24.dp),
                            tint = contentColor
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = item.title, color = contentColor, fontSize = 12.sp, lineHeight = 17.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** 首页视觉中心：运行状态、设备信息与最近释放量集中在一张主状态卡。 */
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
    val dark = scheme.background.luminance() < .5f
    val shape = BaiZeTokens.corners.large

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        scheme.primaryContainer.copy(alpha = if (dark) .58f else .82f),
                        BaiZeTokens.colors.surfaceRaised
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = if (dark) .07f else .62f), shape)
            .padding(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (positive) BaiZeTokens.colors.success else BaiZeTokens.colors.warning)
                )
                Spacer(Modifier.width(8.dp))
                Text(device, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "  ·  $android",
                    color = scheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "最近一次释放",
                color = scheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                releasedText,
                color = scheme.onSurface,
                style = BaiZeTokens.type.hero
            )

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(BaiZeTokens.corners.medium)
                    .background(BaiZeTokens.colors.surfaceOverlay.copy(alpha = if (dark) .78f else .92f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (positive) Icons.Rounded.CheckCircle else Icons.Rounded.Refresh,
                    contentDescription = null,
                    tint = if (positive) BaiZeTokens.colors.success else scheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(statusTitle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        taskPhase,
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

/** 统一主操作：页面最多一个高强调主按钮，高度保持 52dp。 */
@Composable
fun MiuixLiquidPrimaryButton(
    running: Boolean,
    scanReady: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val shape = BaiZeTokens.corners.medium
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
    val contentColor = when {
        !enabled -> scheme.onSurface.copy(alpha = .45f)
        else -> Color.White
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(containerColor)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(9.dp))
            Text(label, color = contentColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
