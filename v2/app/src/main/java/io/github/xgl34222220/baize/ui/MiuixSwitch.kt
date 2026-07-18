package io.github.xgl34222220.baize.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors

/** Wide MIUIx-style switch used by the BOX-inspired settings pages. */
class MiuixSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val variant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant)
    private val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)

    private var progress = 0f
    private var listener: ((MiuixSwitch, Boolean) -> Unit)? = null
    private var checkedState = false

    var isChecked: Boolean
        get() = checkedState
        set(value) {
            setChecked(value, true)
        }

    init {
        isClickable = true
        isFocusable = true
        minimumWidth = dp(64f).toInt()
        minimumHeight = dp(48f).toInt()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setOnCheckedChangeListener(block: ((MiuixSwitch, Boolean) -> Unit)?) {
        listener = block
    }

    fun setCheckedSilently(value: Boolean) {
        setChecked(value, false, notify = false)
    }

    fun setChecked(value: Boolean, animate: Boolean, notify: Boolean = true) {
        val target = if (value) 1f else 0f
        if (checkedState == value && progress == target) return
        checkedState = value
        if (!animate || !isLaidOut) {
            progress = target
            invalidate()
        } else {
            ValueAnimator.ofFloat(progress, target).apply {
                duration = 180L
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            stateDescription = if (value) "已开启" else "已关闭"
        }
        if (notify) listener?.invoke(this, value)
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (!isEnabled) return false
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        setChecked(!checkedState, true)
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = dp(64f).toInt()
        val desiredHeight = dp(48f).toInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val trackWidth = dp(58f)
        val trackHeight = dp(36f)
        val left = (width - trackWidth) / 2f
        val top = (height - trackHeight) / 2f
        rect.set(left, top, left + trackWidth, top + trackHeight)

        val offTrack = ColorUtils.blendARGB(variant, onSurface, if (isEnabled) 0.13f else 0.07f)
        track.color = ColorUtils.blendARGB(offTrack, primary, progress)
        track.alpha = if (isEnabled) 255 else 145
        canvas.drawRoundRect(rect, trackHeight / 2f, trackHeight / 2f, track)

        val radius = dp(14.5f)
        val startX = left + dp(18f)
        val endX = rect.right - dp(18f)
        val centerX = startX + (endX - startX) * progress
        val centerY = rect.centerY()
        thumb.color = ColorUtils.blendARGB(ColorUtils.blendARGB(surface, onSurface, 0.12f), surface, progress)
        thumb.alpha = if (isEnabled) 255 else 180
        canvas.drawCircle(centerX, centerY, radius, thumb)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        alpha = if (isEnabled) 1f else 0.56f
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"

    private fun dp(value: Float): Float = value * density
}
