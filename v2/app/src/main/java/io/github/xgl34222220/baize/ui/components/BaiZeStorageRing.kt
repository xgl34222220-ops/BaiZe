package io.github.xgl34222220.baize.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.baize.performance.PerformanceRuntime
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/**
 * 统一的「减少动态效果」判断：系统关闭动画（开发者选项 / 无障碍「移除动画」）或性能降级时返回 false。
 * 所有新动效都应先读它，而不是各自调用 ValueAnimator。
 */
@Composable
internal fun rememberMotionEnabled(): Boolean =
    !PerformanceRuntime.degraded.value && ValueAnimator.areAnimatorsEnabled()

/** 存储环的三段比例，纯函数，便于单元测试；所有输入都可能来自不完整的统计。 */
@Immutable
internal data class StorageRingFractions(val occupied: Float, val cleanable: Float) {
    val used: Float get() = occupied + cleanable
}

internal fun storageRingFractions(used: Long, cleanable: Long, total: Long): StorageRingFractions {
    if (total <= 0L) return StorageRingFractions(0f, 0f)
    val safeUsed = used.coerceIn(0L, total)
    val safeCleanable = cleanable.coerceIn(0L, safeUsed)
    return StorageRingFractions(
        occupied = (safeUsed - safeCleanable).toFloat() / total,
        cleanable = safeCleanable.toFloat() / total
    )
}

/**
 * 首页存储环（参考 HyperOS 手机管家与 Files by Google「清理」页顶部的占用概览）：
 * 灰色为已占用，主色为本次可清理，轨道为可用空间。只在 Canvas 中绘制，不触发重组以外的布局。
 */
@Composable
internal fun BaiZeStorageRing(
    used: Long,
    cleanable: Long,
    total: Long,
    description: String,
    modifier: Modifier = Modifier,
    diameter: Dp = 92.dp,
    strokeWidth: Dp = 9.dp,
    center: @Composable BoxScope.() -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    val colors = BaiZeTokens.colors
    val target = storageRingFractions(used, cleanable, total)
    val motion = rememberMotionEnabled()
    val occupied by animateFloatAsState(target.occupied, tween(if (motion) 520 else 0), label = "storage-ring-occupied")
    val freeable by animateFloatAsState(target.cleanable, tween(if (motion) 520 else 0), label = "storage-ring-cleanable")
    Box(
        modifier.size(diameter).semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(diameter)) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(colors.surfaceOverlay, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            val start = -90f
            val occupiedSweep = 360f * occupied
            if (occupiedSweep > 0f) drawArc(scheme.onSurfaceVariant.copy(alpha = .38f), start, occupiedSweep, false,
                topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val cleanableSweep = 360f * freeable
            if (cleanableSweep > 0f) drawArc(scheme.primary, start + occupiedSweep, cleanableSweep, false,
                topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        center()
    }
}
