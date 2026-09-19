package io.github.xgl34222220.baize.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.performance.PerformanceRuntime
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlinx.coroutines.delay

/** Only presentation is sampled; engine events and the full path remain untouched. */
@Composable
internal fun sampledScanText(text: String): String {
    val latest by rememberUpdatedState(text)
    val shown by produceState(text) {
        snapshotFlow { latest }.collect { value = it; delay(100L) }
    }
    return shown
}

@Composable
internal fun BaiZePathText(path: String, modifier: Modifier = Modifier, live: Boolean = false) {
    val displayed = if (live) sampledScanText(path) else path
    Text(displayed, modifier, fontSize = 12.sp, lineHeight = 17.sp,
        maxLines = 1, softWrap = false, overflow = TextOverflow.MiddleEllipsis,
        color = BaiZeTokens.colors.muted)
}

@Composable
internal fun BaiZeMetric(value: String, modifier: Modifier = Modifier, large: Boolean = false) {
    val match = remember(value) { Regex("""^([0-9][0-9.,]*)\s*([A-Za-z]+|项|%)$""").matchEntire(value.trim()) }
    val size = if (large) 44.sp else 32.sp
    if (match == null) {
        Text(value, modifier, fontSize = if (large) 32.sp else 26.sp,
            lineHeight = if (large) 42.sp else 34.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    } else Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(match.groupValues[1], Modifier.alignByBaseline().weight(1f, fill = false),
            style = MaterialTheme.typography.headlineLarge.copy(fontSize = size,
                lineHeight = if (large) 52.sp else 40.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = (-1).sp, fontFeatureSettings = "tnum"),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(match.groupValues[2], Modifier.alignByBaseline().padding(start = 6.dp),
            fontSize = if (large) 18.sp else 13.sp, fontWeight = FontWeight.Medium,
            color = BaiZeTokens.colors.muted)
    }
}

/** A single draw-only animation, absent when idle or system animations are disabled. */
@Composable
internal fun BaiZeProgress(modifier: Modifier = Modifier, progress: Float? = null) {
    val scheme = MaterialTheme.colorScheme
    val degraded = PerformanceRuntime.degraded.value || !ValueAnimator.areAnimatorsEnabled()
    val fraction by animateFloatAsState((progress ?: .32f).coerceIn(0f, 1f),
        tween(if (degraded) 0 else 220), label = "task-progress")
    val glow = if (!degraded) {
        val transition = rememberInfiniteTransition(label = "progress-light")
        transition.animateFloat(-.35f, 1.35f,
            infiniteRepeatable(tween(1700, easing = LinearEasing)), label = "progress-light-position")
    } else remember { mutableFloatStateOf(.5f) }
    Canvas(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).semantics {
        progressBarRangeInfo = if (progress == null) ProgressBarRangeInfo.Indeterminate
        else ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
    }) {
        drawRoundRect(scheme.primary.copy(alpha = .09f), cornerRadius = CornerRadius(size.height / 2))
        val width = size.width * fraction
        val start = if (progress == null) ((size.width - width) * glow.value.coerceIn(0f, 1f)) else 0f
        drawRoundRect(Brush.horizontalGradient(listOf(scheme.primary.copy(alpha = .65f), scheme.primary)),
            topLeft = Offset(start, 0f), size = Size(width, size.height), cornerRadius = CornerRadius(size.height / 2))
        if (!degraded && width > 0) {
            val center = start + width * glow.value
            drawRoundRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .36f), Color.Transparent),
                startX = center - size.width * .12f, endX = center + size.width * .12f),
                topLeft = Offset(start, 0f), size = Size(width, size.height), cornerRadius = CornerRadius(size.height / 2))
        }
    }
}

@Composable
internal fun BaiZeSuccessMark(modifier: Modifier = Modifier) {
    val color = BaiZeTokens.colors.success
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val scale by animateFloatAsState(if (visible) 1f else .72f,
        tween(if (ValueAnimator.areAnimatorsEnabled()) 260 else 0), label = "success-check")
    Canvas(modifier.size(20.dp).scale(scale)) {
        drawCircle(color.copy(alpha = .10f))
        val check = Path().apply { moveTo(size.width * .28f, size.height * .51f); lineTo(size.width * .44f, size.height * .66f); lineTo(size.width * .73f, size.height * .35f) }
        drawPath(check, color, style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Small native vector scene shared by every empty result; no bitmap/dependency needed. */
@Composable
internal fun BaiZeEmptyIllustration(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.primaryContainer
    Canvas(modifier.size(76.dp, 60.dp)) {
        val k = size.width / 76f
        drawOval(fill.copy(alpha = .5f), Offset(3*k, 7*k), Size(68*k, 46*k))
        val folder = Path().apply {
            moveTo(13*k, 22*k); lineTo(13*k, 15*k); quadraticTo(13*k, 12*k, 17*k, 12*k)
            lineTo(29*k, 12*k); lineTo(35*k, 18*k); lineTo(58*k, 18*k)
            quadraticTo(62*k, 18*k, 62*k, 22*k); lineTo(62*k, 44*k)
            quadraticTo(62*k, 48*k, 58*k, 48*k); lineTo(17*k, 48*k)
            quadraticTo(13*k, 48*k, 13*k, 44*k); close()
        }
        drawPath(folder, fill)
        drawPath(folder, ink.copy(alpha = .30f), style = Stroke(1.7f*k, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(ink.copy(alpha = .30f), Offset(14*k, 25*k), Offset(61*k, 25*k), 1.7f*k, StrokeCap.Round)
        drawLine(ink.copy(alpha = .4f), Offset(53*k, 29*k), Offset(44*k, 44*k), 2f*k, StrokeCap.Round)
        drawLine(ink.copy(alpha = .4f), Offset(44*k, 44*k), Offset(39*k, 49*k), 5f*k, StrokeCap.Round)
        drawCircle(ink.copy(alpha = .17f), 2f*k, Offset(67*k, 12*k))
    }
}

@Composable
internal fun <T> BaiZeIntervalPicker(options: List<T>, selected: T, label: (T) -> String,
    onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { value ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) },
                label = { Text(label(value), fontSize = 12.sp, maxLines = 1) },
                shape = CircleShape, border = null,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = BaiZeTokens.colors.surfaceOverlay,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer))
        }
    }
}
