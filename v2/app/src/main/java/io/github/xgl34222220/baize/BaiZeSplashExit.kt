package io.github.xgl34222220.baize

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreenViewProvider

/**
 * 启动页「弥散渐变玻璃」：
 * - Android 8–11：启动窗口画 windowBackground（drawable/baize_splash_window：弥散渐变 + 启动图标）；
 *   core-splashscreen 的兼容启动视图默认是纯色底，[attach] 立即把它换成同一张渐变，首帧不闪纯色。
 * - Android 12+：系统启动页只能是纯色（取渐变中心色），首帧到达时 [play] 先把同一张渐变淡入，再整体淡出。
 * 退出动画：渐变淡入 [BLOOM_MS] + 图标轻微放大 + 整体淡出，总时长不超过 [DURATION_MS]。
 * 不设置 keep-on-screen 条件，首帧一到立即开始退出，冷启动不会变慢。
 */
internal object BaiZeSplashExit {
    const val DURATION_MS = 250L
    const val BLOOM_MS = 110L
    const val ICON_END_SCALE = 1.06f

    fun attach(activity: Activity, splash: SplashScreen) {
        splash.setOnExitAnimationListener(::play)
        if (Build.VERSION.SDK_INT < 31) {
            runCatching {
                // 兼容启动视图刚被 setOnExitAnimationListener 加到 decor 末尾；不含 content 的那个就是它。
                val decor = activity.window.decorView as ViewGroup
                val view = decor.getChildAt(decor.childCount - 1)
                if (view != null && view.findViewById<View>(android.R.id.content) == null) {
                    mesh(activity)?.let { view.background = it }
                }
            }
        }
    }

    fun play(provider: SplashScreenViewProvider) {
        val easing = PathInterpolator(0.2f, 0f, 0f, 1f)
        val view = provider.view
        if (Build.VERSION.SDK_INT >= 31) runCatching { bloom(view, easing) }
        // 部分 ROM 在非 Launcher 启动时不提供图标视图。
        runCatching { provider.iconView }.getOrNull()?.animate()
            ?.scaleX(ICON_END_SCALE)?.scaleY(ICON_END_SCALE)
            ?.setDuration(DURATION_MS)?.setInterpolator(easing)?.start()
        view.animate()
            .alpha(0f)
            .setStartDelay(BLOOM_MS)
            .setDuration(DURATION_MS - BLOOM_MS)
            .setInterpolator(easing)
            .withEndAction { provider.remove() }
            .start()
    }

    private fun bloom(view: View, easing: PathInterpolator) {
        val mesh = mesh(view.context) ?: return
        val base = view.background ?: ColorDrawable(ContextCompat.getColor(view.context, R.color.baize_splash_background))
        mesh.alpha = 0
        view.background = LayerDrawable(arrayOf(base, mesh))
        ValueAnimator.ofInt(0, 255).apply {
            duration = BLOOM_MS
            interpolator = easing
            addUpdateListener { mesh.alpha = it.animatedValue as Int }
        }.start()
    }

    private fun mesh(context: android.content.Context): Drawable? =
        ContextCompat.getDrawable(context, R.drawable.baize_splash_mesh)?.mutate()
}
