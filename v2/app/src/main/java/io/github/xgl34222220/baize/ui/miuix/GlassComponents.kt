package io.github.xgl34222220.baize.ui.miuix

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

/** Static layered light: no backdrop capture, blur or continuous animation during scanning. */
internal fun Modifier.glassSurface(
    color: Color,
    shape: Shape,
    dark: Boolean
): Modifier = this
    .shadow(
        elevation = 8.dp,
        shape = shape,
        clip = false,
        ambientColor = Color(0xFF244064).copy(alpha = if (dark) .10f else .045f),
        spotColor = Color(0xFF244064).copy(alpha = if (dark) .14f else .065f)
    )
    .shadow(
        elevation = 1.dp,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = .025f),
        spotColor = Color.Black.copy(alpha = if (dark) .12f else .035f)
    )
    .clip(shape)
    .background(
        Brush.verticalGradient(
            0f to lerp(color, Color.White, if (dark) .035f else .45f),
            .10f to lerp(color, Color.White, if (dark) .012f else .12f),
            1f to color
        )
    )
    .insetTopLight(if (dark) .055f else .32f)

/** A six-dp internal reflection, clipped by the parent shape rather than drawn as a border. */
private fun Modifier.insetTopLight(alpha: Float): Modifier = drawWithCache {
    val reflection = Brush.verticalGradient(
        colors = listOf(Color.White.copy(alpha = alpha), Color.Transparent),
        startY = 0f,
        endY = 6.dp.toPx()
    )
    onDrawWithContent {
        drawContent()
        drawRect(reflection)
    }
}

/** Shared 48dp action. Motion only follows the user's press and honours disabled state. */
@Composable
fun GlassActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    secondary: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < .3f
    val shape = RoundedCornerShape(16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) .986f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "glassActionPress"
    )
    val base = when {
        !enabled -> BaiZeTokens.colors.surfaceOverlay
        secondary -> BaiZeTokens.colors.surfaceRaised
        else -> scheme.primary
    }
    val foreground = when {
        !enabled -> scheme.onSurfaceVariant.copy(alpha = .55f)
        secondary -> scheme.primary
        else -> scheme.onPrimary
    }
    val upper = if (secondary || !enabled) {
        lerp(base, Color.White, if (dark) .05f else .7f)
    } else {
        lerp(base, Color.White, if (dark) .05f else .09f)
    }

    val lower = if (enabled && !secondary) lerp(base, Color.Black, if (dark) .015f else .04f) else base

    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .heightIn(min = 48.dp)
            .shadow(
                elevation = if (enabled) 6.dp else 0.dp,
                shape = shape,
                clip = false,
                ambientColor = scheme.primary.copy(alpha = if (secondary) .03f else .09f),
                spotColor = scheme.primary.copy(alpha = if (secondary) .05f else .14f)
            )
            .shadow(
                elevation = if (enabled) 1.dp else 0.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = .025f),
                spotColor = Color.Black.copy(alpha = if (dark) .08f else .045f)
            )
            .clip(shape)
            .background(Brush.verticalGradient(listOf(upper, base, lower)))
            .insetTopLight(if (!enabled) .08f else if (dark) .10f else if (secondary) .45f else .18f)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = foreground)
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = foreground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
