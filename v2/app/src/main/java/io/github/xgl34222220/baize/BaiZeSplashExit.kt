package io.github.xgl34222220.baize

import android.view.animation.PathInterpolator
import androidx.core.splashscreen.SplashScreenViewProvider

/**
 * 启动页退出动画：图标轻微放大 + 整体淡出，总时长不超过 [DURATION_MS]。
 * 不设置 keep-on-screen 条件，首帧一到立即开始退出，冷启动不会变慢。
 */
internal object BaiZeSplashExit {
    const val DURATION_MS = 220L
    const val ICON_END_SCALE = 1.08f

    fun play(provider: SplashScreenViewProvider) {
        val easing = PathInterpolator(0.2f, 0f, 0f, 1f)
        // 部分 ROM 在非 Launcher 启动时不提供图标视图。
        runCatching { provider.iconView }.getOrNull()?.animate()
            ?.scaleX(ICON_END_SCALE)?.scaleY(ICON_END_SCALE)
            ?.setDuration(DURATION_MS)?.setInterpolator(easing)?.start()
        provider.view.animate()
            .alpha(0f)
            .setDuration(DURATION_MS)
            .setInterpolator(easing)
            .withEndAction { provider.remove() }
            .start()
    }
}
