package io.github.xgl34222220.baize.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.theme.BaiZeIconSize
import io.github.xgl34222220.baize.ui.theme.BaiZeMotion
import io.github.xgl34222220.baize.ui.theme.BaiZeRadius
import io.github.xgl34222220.baize.ui.theme.BaiZeTone
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/*
 * HyperOS 风格基础组件（设计系统见 BaiZeTokens.kt）。
 * 所有页面的卡片、行、圆形勾选、底部主按钮与按压反馈都从这里取，避免各页自定义。
 */

/** 主操作的轻触感反馈；系统关闭触感时 View 会自行忽略。 */
@Composable
internal fun rememberBaiZeHaptic(): () -> Unit {
    val view = LocalView.current
    return remember(view) { { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) } }
}

/** 按压缩放 0.97；关闭动效时不缩放。 */
@Composable
internal fun Modifier.baiZePress(interaction: MutableInteractionSource, enabled: Boolean = true): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val motion = rememberMotionEnabled()
    val scale by animateFloatAsState(if (pressed && enabled && motion) BaiZeMotion.PRESS_SCALE else 1f,
        tween(if (motion) BaiZeMotion.SHORT else 0), label = "baize-press")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
internal fun BaiZeTone.color(): Color =
    if (BaiZeTokens.colors.surfaceRaised.luminance() < .3f) dark else light

/** 白色 24dp 圆角卡片，无阴影，浅灰页面底上靠色差分层。 */
@Composable
internal fun BaiZeCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(BaiZeRadius.card))
        .background(BaiZeTokens.colors.surfaceRaised), content = content)
}

/** 分组标题：卡片上方的小号灰字（推荐清理 / 更多清理）。 */
@Composable
internal fun BaiZeCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp), fontSize = 13.sp, lineHeight = 18.sp,
        fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 彩色托底图标：40dp、12dp 圆角、10% 色调底。 */
@Composable
internal fun BaiZeTintedIcon(icon: ImageVector, tone: BaiZeTone, modifier: Modifier = Modifier, size: Dp = BaiZeIconSize.tile) {
    val color = tone.color()
    Box(modifier.size(size).clip(RoundedCornerShape(BaiZeRadius.icon)).background(color.copy(alpha = .12f)),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * .55f), tint = color)
    }
}

/** 文件类分类（日志、空文件夹、残留）使用的黄色文件夹图标。 */
@Composable
internal fun BaiZeFolderGlyph(modifier: Modifier = Modifier, size: Dp = BaiZeIconSize.tile) {
    val tone = io.github.xgl34222220.baize.ui.theme.BaiZeTones.folder.color()
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val r = w * .12f
        val tab = Path().apply {
            moveTo(w * .08f, h * .30f); lineTo(w * .08f, h * .22f)
            quadraticTo(w * .08f, h * .16f, w * .16f, h * .16f)
            lineTo(w * .38f, h * .16f); lineTo(w * .48f, h * .26f); lineTo(w * .84f, h * .26f)
            quadraticTo(w * .92f, h * .26f, w * .92f, h * .34f); lineTo(w * .92f, h * .40f); close()
        }
        drawPath(tab, lerp(tone, Color.Black, .12f))
        drawRoundRect(Brush.verticalGradient(listOf(lerp(tone, Color.White, .18f), tone)),
            topLeft = Offset(w * .08f, h * .32f), size = Size(w * .84f, h * .54f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
    }
}

/** 圆形勾选：选中为主色实心 + 白勾，未选为灰色空心圈，部分选中为主色实心 + 白横线。 */
@Composable
internal fun BaiZeRoundCheck(
    state: ToggleableState,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String? = null
) {
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) .45f else .22f)
    val haptic = rememberBaiZeHaptic()
    val motion = rememberMotionEnabled()
    val fill by animateFloatAsState(if (state == ToggleableState.Off) 0f else 1f,
        tween(if (motion) BaiZeMotion.SELECTION else 0), label = "round-check")
    val base = if (onClick != null) modifier.minimumInteractiveComponentSize().triStateToggleable(state, enabled = enabled,
        role = Role.Checkbox, onClick = { haptic(); onClick() }) else modifier
    Box(base.then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(BaiZeIconSize.check)) {
            val stroke = 1.6.dp.toPx()
            val radius = size.minDimension / 2f
            if (fill < 1f) drawCircle(outline, radius - stroke / 2f, style = Stroke(stroke))
            if (fill > 0f) drawCircle((if (enabled) accent else accent.copy(alpha = .4f)).copy(alpha = fill), radius * (.6f + .4f * fill))
            if (fill > .5f) {
                val mark = Color.White.copy(alpha = (fill - .5f) * 2f)
                if (state == ToggleableState.Indeterminate) {
                    drawLine(mark, Offset(size.width * .3f, size.height * .5f), Offset(size.width * .7f, size.height * .5f),
                        2.dp.toPx(), StrokeCap.Round)
                } else {
                    val path = Path().apply {
                        moveTo(size.width * .29f, size.height * .52f); lineTo(size.width * .44f, size.height * .66f)
                        lineTo(size.width * .72f, size.height * .37f)
                    }
                    drawPath(path, mark, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}

/** 分组卡内的标准行：彩色图标 + 标题 + 右侧数值 + 箭头，最小 64dp。 */
@Composable
internal fun BaiZeListRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    subtitle: String = "",
    value: String = "",
    chevron: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    Row(modifier.fillMaxWidth().baiZePress(interaction)
        .clickable(interaction, androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick)
        .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.5.sp, lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (value.isNotBlank()) Text(value, Modifier.padding(start = 8.dp), fontSize = 14.sp,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        if (chevron) Icon(Icons.Rounded.ChevronRight, null, Modifier.padding(start = 4.dp).size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f))
    }
}

@Composable
internal fun BaiZeInsetDivider(start: Dp = 70.dp) {
    HorizontalDivider(Modifier.padding(start = start, end = 16.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .06f))
}

/** 两列工具格：图标、标题、副标题、色调“前往”胶囊。 */
@Composable
internal fun BaiZeToolTile(
    icon: ImageVector,
    tone: BaiZeTone,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberBaiZeHaptic()
    val color = tone.color()
    Column(modifier.baiZePress(interaction).clip(RoundedCornerShape(BaiZeRadius.card))
        .background(BaiZeTokens.colors.surfaceRaised)
        .clickable(interaction, androidx.compose.material3.ripple(), role = Role.Button) { haptic(); onClick() }
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BaiZeTintedIcon(icon, tone, size = 36.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.5.sp, lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (actionLabel != null) Text(actionLabel, Modifier.clip(CircleShape).background(color.copy(alpha = .12f))
            .padding(horizontal = 14.dp, vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** 底部整宽主色胶囊按钮（清理选中垃圾 853 MB）。label 与 trailing 是独立文本节点。 */
@Composable
internal fun BaiZePillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String = "",
    enabled: Boolean = true,
    secondary: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberBaiZeHaptic()
    val container = when { !enabled -> BaiZeTokens.colors.surfaceOverlay; secondary -> BaiZeTokens.colors.surfaceOverlay; else -> scheme.primary }
    val content = when { !enabled -> scheme.onSurfaceVariant.copy(alpha = .6f); secondary -> scheme.onSurface; else -> scheme.onPrimary }
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).baiZePress(interaction, enabled).clip(CircleShape)
        .background(container)
        .clickable(interaction, androidx.compose.material3.ripple(), enabled = enabled, role = Role.Button) {
            if (!secondary) haptic(); onClick()
        }
        .padding(horizontal = 20.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = content,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (trailing.isNotBlank()) Text(" $trailing", fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
            color = content, maxLines = 1, style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"))
    }
}

/** 灰色次要胶囊（忽略）与色调主胶囊（去清理）。 */
@Composable
internal fun BaiZeChipButton(label: String, onClick: () -> Unit, primary: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberBaiZeHaptic()
    Text(label, modifier.baiZePress(interaction).clip(CircleShape)
        .background(if (primary) scheme.primary.copy(alpha = .12f) else BaiZeTokens.colors.surfaceOverlay)
        .clickable(interaction, androidx.compose.material3.ripple(), enabled = enabled, role = Role.Button) { if (primary) haptic(); onClick() }
        .heightIn(min = 40.dp).padding(horizontal = 18.dp, vertical = 9.dp),
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center, overflow = TextOverflow.Ellipsis,
        color = (if (primary) scheme.primary else scheme.onSurface).copy(alpha = if (enabled) 1f else .4f))
}

/** 页面顶部的柔和主色渐变，淡入浅灰底。 */
@Composable
internal fun Modifier.baiZeHeroWash(): Modifier {
    val accent = MaterialTheme.colorScheme.primary
    val base = BaiZeTokens.colors.surfaceBase
    val dark = base.luminance() < .3f
    return this.background(Brush.verticalGradient(listOf(
        lerp(base, accent, if (dark) .16f else .13f), lerp(base, accent, if (dark) .06f else .05f), base)))
}

/**
 * 立体光泽进度徽章：外圈渐变环（扫描时填充动画），内部白色凸面圆盘；完成时显示对勾。
 * progress 为 null 时只画轨道。
 */
@Composable
internal fun BaiZeGlossyBadge(
    progress: Float?,
    done: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 112.dp,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val accent = MaterialTheme.colorScheme.primary
    val surface = BaiZeTokens.colors.surfaceRaised
    val dark = surface.luminance() < .3f
    val motion = rememberMotionEnabled()
    val sweep by animateFloatAsState(if (done) 1f else (progress ?: 0f).coerceIn(0f, 1f),
        tween(if (motion) BaiZeMotion.LONG else 0), label = "badge-sweep")
    val check by animateFloatAsState(if (done) 1f else 0f, tween(if (motion) BaiZeMotion.LONG else 0), label = "badge-check")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val ring = this.size.minDimension * .13f
            val r = this.size.minDimension / 2f - ring / 2f
            val tl = Offset(center.x - r, center.y - r)
            val arc = Size(r * 2, r * 2)
            // 外圈投影
            drawCircle(accent.copy(alpha = if (dark) .18f else .14f), r + ring * .9f, center + Offset(0f, ring * .35f))
            drawCircle(lerp(surface, accent, if (dark) .22f else .14f), r, style = Stroke(ring))
            if (sweep > 0f) drawArc(Brush.sweepGradient(listOf(lerp(accent, Color.White, .25f), accent, lerp(accent, Color.Black, .08f),
                lerp(accent, Color.White, .25f)), center), -90f, 360f * sweep, false, tl, arc, style = Stroke(ring, cap = StrokeCap.Round))
            // 光泽高光
            drawArc(Color.White.copy(alpha = if (dark) .10f else .35f), 200f, 70f, false,
                Offset(tl.x + ring * .2f, tl.y + ring * .2f), Size(arc.width - ring * .4f, arc.height - ring * .4f),
                style = Stroke(ring * .25f, cap = StrokeCap.Round))
            val inner = r - ring / 2f - ring * .35f
            drawCircle(Brush.radialGradient(listOf(lerp(surface, Color.White, if (dark) .10f else 1f),
                lerp(surface, if (dark) Color.Black else Color(0xFFE9ECF1), .55f)),
                center = center - Offset(inner * .3f, inner * .35f), radius = inner * 1.6f), inner)
            if (check > 0f) {
                val s = inner * 1.1f
                val path = Path().apply {
                    moveTo(center.x - s * .42f, center.y + s * .02f)
                    lineTo(center.x - s * .12f, center.y + s * .30f)
                    lineTo(center.x + s * .42f, center.y - s * .28f)
                }
                drawPath(path, accent.copy(alpha = check), style = Stroke(ring * .62f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        if (!done) content()
    }
}

/** 存储空间分段占用条。 */
@Composable
internal fun BaiZeUsageBar(segments: List<Pair<Float, Color>>, modifier: Modifier = Modifier) {
    val track = BaiZeTokens.colors.surfaceOverlay
    val motion = rememberMotionEnabled()
    var shown by remember { mutableStateOf(!motion) }
    LaunchedEffect(Unit) { shown = true }
    val grow by animateFloatAsState(if (shown) 1f else 0f, tween(if (motion) BaiZeMotion.COUNT_UP else 0), label = "usage-grow")
    Canvas(modifier.fillMaxWidth().height(10.dp).clip(CircleShape)) {
        drawRect(track)
        var x = 0f
        segments.forEach { (fraction, color) ->
            val w = size.width * fraction.coerceIn(0f, 1f) * grow
            if (w > 0f) drawRect(color, Offset(x, 0f), Size(w, size.height))
            x += w
        }
    }
}

/** 容量数字平滑递增（count-up）；关闭动效时直接显示终值。 */
@Composable
internal fun animatedBytes(target: Long): Long {
    val motion = rememberMotionEnabled()
    var start by remember { mutableStateOf(if (motion) 0f else 1f) }
    LaunchedEffect(target) { start = 1f }
    val fraction by animateFloatAsState(start, tween(if (motion) BaiZeMotion.COUNT_UP else 0), label = "count-up")
    return (target * fraction).toLong()
}

/** 悬浮圆形按钮（返回 / 更多）：白底柔和阴影。 */
@Composable
internal fun BaiZeFloatingIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val surface = BaiZeTokens.colors.surfaceRaised
    val dark = surface.luminance() < .3f
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(BaiZeIconSize.headerButton).baiZePress(interaction)
            .shadow(BaiZeElevationFloating, CircleShape, clip = false,
                ambientColor = Color.Black.copy(alpha = if (dark) .3f else .06f),
                spotColor = Color.Black.copy(alpha = if (dark) .3f else .10f))
            .clip(CircleShape).background(if (dark) BaiZeTokens.colors.surfaceOverlay else surface)
            .clickable(interaction, androidx.compose.material3.ripple(), role = Role.Button, onClickLabel = description, onClick = onClick),
            contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

private val BaiZeElevationFloating = io.github.xgl34222220.baize.ui.theme.BaiZeElevation.floatingButton
