package io.github.xgl34222220.baize.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * 关键操作的触感反馈，参考 HyperOS 手机管家：开始扫描、确认删除、长按勾选。
 * 通过 View.performHapticFeedback 触发，遵循系统“触摸反馈”开关；不可用时静默跳过。
 */
@Stable
class BaiZeHaptics internal constructor(private val view: View?) {
    /** 开始扫描：一次确认感的轻震。 */
    fun scanStart() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    /** 确认删除 / 移入回收站 / 永久删除。 */
    fun confirmDelete() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
    /** 长按勾选。 */
    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)
    /** 撤销等次要确认。 */
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)

    private fun perform(constant: Int) {
        runCatching { view?.performHapticFeedback(constant) }
    }

    companion object {
        /** 非 Compose 场景或测试使用的空实现。 */
        val None = BaiZeHaptics(null)
    }
}

@Composable
fun rememberBaiZeHaptics(): BaiZeHaptics {
    val view = LocalView.current
    return remember(view) { BaiZeHaptics(view) }
}
