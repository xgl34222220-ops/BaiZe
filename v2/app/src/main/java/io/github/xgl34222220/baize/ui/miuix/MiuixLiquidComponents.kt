package io.github.xgl34222220.baize.ui.miuix

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

/** A stable Miuix navigation item. Labels always stay below icons. */
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
            .shadow(if (floating) 22.dp else 8.dp, shape, clip = false)
            .clip(shape)
            .then(hazeModifier)
            .background(dockColor)
            .border(1.dp, borderColor, shape)
            .drawBehind {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (dark) .14f else .46f),
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
    val settings = LocalAppearanceSettings.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val amoled = dark && settings.amoledBlack
    val shape = RoundedCornerShape(36.dp)
    val surface = when {
        amoled -> Color(0xFF090909)
        dark -> scheme.surfaceContainerHigh
        else -> scheme.surface
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(12.dp, shape, clip = false)
            .clip(shape)
            .background(surface)
            .border(1.dp, scheme.onSurface.copy(alpha = if (dark) .08f else .05f), shape)
            .drawBehind {
                if (!amoled) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(scheme.primary.copy(alpha = if (dark) .20f else .16f), Color.Transparent),
                            center = Offset(size.width, 0f),
                            radius = size.width * .78f
                        ),
                        radius = size.width * .78f,
                        center = Offset(size.width, 0f)
                    )
                }
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = if (dark) .05f else .35f), Color.Transparent)
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(36.dp.toPx()),
                    size = size.copy(height = size.height * .38f)
                )
            }
            .padding(24.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (positive) Color(0xFF2DBE87) else Color(0xFFF2A93B))
                )
                Spacer(Modifier.width(8.dp))
                Text(device, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    "  ·  $android",
                    color = scheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(23.dp))
            Text(
                "最近一次释放",
                color = scheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                releasedText,
                color = scheme.onSurface,
                fontSize = 42.sp,
                lineHeight = 46.sp,
                fontWeight = FontWeight.Black
            )

            Spacer(Modifier.height(20.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(scheme.onSurface.copy(alpha = if (dark) .055f else .04f))
                    .padding(horizontal = 15.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (positive) Icons.Rounded.CheckCircle else Icons.Rounded.Refresh,
                    contentDescription = null,
                    tint = if (positive) Color(0xFF2DBE87) else scheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(statusTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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

@Composable
fun MiuixLiquidPrimaryButton(
    running: Boolean,
    scanReady: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val settings = LocalAppearanceSettings.current
    val dark = scheme.background.luminance() < .5f
    val shape = RoundedCornerShape(28.dp)
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

    val baseGradient = when {
        !enabled -> listOf(scheme.onSurface.copy(alpha = .16f), scheme.onSurface.copy(alpha = .10f))
        running -> listOf(Color(0xFF6B6E79), Color(0xFF4C4F59))
        else -> listOf(scheme.primary, scheme.tertiary)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .shadow(if (enabled) 14.dp else 0.dp, shape, clip = false)
            .clip(shape)
            .background(Brush.horizontalGradient(baseGradient))
            .border(1.dp, Color.White.copy(alpha = if (dark) .16f else .48f), shape)
            .drawBehind {
                if (settings.glassEnabled) {
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = .34f), Color.Transparent)
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(28.dp.toPx()),
                        size = size.copy(height = size.height * .54f)
                    )
                }
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(25.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black)
        }
    }
}
