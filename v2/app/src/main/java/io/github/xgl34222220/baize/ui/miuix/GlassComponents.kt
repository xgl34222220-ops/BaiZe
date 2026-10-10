package io.github.xgl34222220.baize.ui.miuix

import io.github.xgl34222220.baize.ui.components.baiZeLineIcon
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/**
 * 2026-10 统一设计：原“玻璃”分层光影改为 HyperOS 平面卡片（纯色、无渐变、无阴影），
 * 避免同屏混用玻璃卡与 MIUIX 卡。保留函数名以免改动所有调用点。
 */
@Suppress("UNUSED_PARAMETER")
internal fun Modifier.glassSurface(
    color: Color,
    shape: Shape,
    dark: Boolean
): Modifier = this.clip(shape).background(color)

/** Shared action; its reflection and press response match the floating navigation. */
@Composable
fun GlassActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    secondary: Boolean = false,
    compact: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(percent = 50)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) .97f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "glassActionPress"
    )
    val base = when {
        !enabled -> BaiZeTokens.colors.surfaceOverlay
        secondary -> BaiZeTokens.colors.surfaceOverlay
        else -> scheme.primary
    }
    val foreground = when {
        !enabled -> scheme.onSurfaceVariant.copy(alpha = .55f)
        secondary -> scheme.onSurface
        else -> scheme.onPrimary
    }
    val haptic = io.github.xgl34222220.baize.ui.components.rememberBaiZeHaptic()

    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .heightIn(min = if (compact) 44.dp else 50.dp)
            .clip(shape)
            .background(base)
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                enabled = enabled,
                role = Role.Button,
                onClick = { if (!secondary) haptic(); onClick() }
            )
            .padding(horizontal = if (compact) 16.dp else 20.dp, vertical = if (compact) 10.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        if (icon != null) Icon(baiZeLineIcon(icon), null, Modifier.size(20.dp), tint = foreground)
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = foreground,
            textAlign = TextAlign.Center,
            maxLines = if (compact && LocalDensity.current.fontScale <= 1.2f) 1 else 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
