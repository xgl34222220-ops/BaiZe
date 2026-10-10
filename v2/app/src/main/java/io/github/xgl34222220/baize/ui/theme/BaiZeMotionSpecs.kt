package io.github.xgl34222220.baize.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import io.github.xgl34222220.baize.performance.PerformanceRuntime

/**
 * 统一的短动效规格：页面切换、展开收起、勾选变化共用 [BaiZeMotion] 的时长，
 * 不改变现有视觉风格，只让节奏一致。系统关闭动画或流畅降级时缩短到 [BaiZeMotion.DEGRADED]。
 */
object BaiZeMotionSpecs {
    /** 当前是否应使用完整时长；读取 Compose 状态，降级切换时会自动重组。 */
    fun fullMotion(): Boolean = !PerformanceRuntime.degraded.value && ValueAnimator.areAnimatorsEnabled()

    fun enterMillis(): Int = if (fullMotion()) BaiZeMotion.ENTER else BaiZeMotion.DEGRADED
    fun exitMillis(): Int = if (fullMotion()) BaiZeMotion.EXIT else BaiZeMotion.DEGRADED
    fun selectionMillis(): Int = if (fullMotion()) BaiZeMotion.SELECTION else 0

    fun <T> enter(): FiniteAnimationSpec<T> = tween(enterMillis(), easing = FastOutSlowInEasing)
    fun <T> exit(): FiniteAnimationSpec<T> = tween(exitMillis(), easing = FastOutSlowInEasing)
    fun <T> selection(): FiniteAnimationSpec<T> = tween(selectionMillis(), easing = FastOutSlowInEasing)

    /** 展开：高度展开 + 淡入。 */
    fun expandIn(): EnterTransition = expandVertically(enter()) + fadeIn(enter())
    /** 收起：高度收起 + 淡出。 */
    fun collapseOut(): ExitTransition = shrinkVertically(exit()) + fadeOut(exit())

    /** 进入二级详情（从右侧推入），返回时反向。 */
    fun detailTransition(forward: Boolean): ContentTransform =
        if (forward) slideInHorizontally(enter()) { it } togetherWith slideOutHorizontally(exit()) { -it / 8 }
        else slideInHorizontally(enter()) { -it / 8 } togetherWith slideOutHorizontally(exit()) { it }
}

/** 卡片、说明文字展开收起时平滑改变高度。 */
fun Modifier.baizeAnimateContentSize(): Modifier =
    animateContentSize(animationSpec = BaiZeMotionSpecs.enter<IntSize>())
