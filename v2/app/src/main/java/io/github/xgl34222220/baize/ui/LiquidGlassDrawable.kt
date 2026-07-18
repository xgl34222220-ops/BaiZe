package io.github.xgl34222220.baize.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.max

/** Calm, theme-aware translucent surface without realtime blur or decorative waves. */
class LiquidGlassDrawable(
    context: Context,
    private val variant: Variant = Variant.CARD
) : Drawable() {
    enum class Variant { CARD, HERO, STRIP, DOCK, ACTIVE, BUTTON }

    private val density = context.resources.displayMetrics.density
    private val primary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, Color.rgb(53, 109, 243))
    private val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.rgb(246, 247, 251))
    private val surfaceVariant = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceVariant, Color.rgb(236, 239, 246))
    private val outline = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutline, Color.rgb(197, 201, 211))
    private val light = ColorUtils.calculateLuminance(surface) > 0.45
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(if (variant == Variant.DOCK || variant == Variant.HERO) 1.1f else 0.8f)
    }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.9f)
    }
    private val rect = RectF()
    private var pressed = false

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val radius = dp(
            when (variant) {
                Variant.DOCK -> 36f
                Variant.HERO -> 29f
                Variant.ACTIVE -> 27f
                Variant.STRIP -> 23f
                Variant.BUTTON -> 20f
                Variant.CARD -> 24f
            }
        )
        val accent = when (variant) {
            Variant.ACTIVE, Variant.BUTTON, Variant.HERO -> if (light) 0.16f else 0.22f
            Variant.DOCK -> if (light) 0.07f else 0.12f
            else -> if (light) 0.025f else 0.055f
        }
        val pressedBoost = if (pressed) 0.04f else 0f
        val top = mix(surfaceVariant, primary, (accent + pressedBoost).coerceAtMost(0.30f))
        val bottom = mix(surface, primary, if (variant == Variant.BUTTON) accent * 0.82f else accent * 0.25f)
        fill.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(top, mix(surfaceVariant, surface, 0.50f), bottom),
            floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, fill)

        if (variant == Variant.HERO || variant == Variant.DOCK || variant == Variant.ACTIVE) {
            glow.shader = RadialGradient(
                rect.left + rect.width() * 0.12f,
                rect.top,
                max(rect.width(), rect.height()) * 0.72f,
                intArrayOf(withAlpha(primary, if (light) 34 else 48), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, glow)
        }

        border.color = mix(outline, primary, if (variant == Variant.ACTIVE) 0.48f else 0.12f)
        border.alpha = if (light) 135 else 155
        val inset = border.strokeWidth / 2f
        canvas.drawRoundRect(
            RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset),
            radius, radius, border
        )

        highlight.color = Color.WHITE
        highlight.alpha = if (light) 112 else 72
        val y = rect.top + dp(1.4f)
        canvas.drawLine(rect.left + radius * 0.75f, y, rect.right - radius * 0.75f, y, highlight)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val next = state.contains(android.R.attr.state_pressed)
        if (pressed == next) return false
        pressed = next
        invalidateSelf()
        return true
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, dp(if (variant == Variant.DOCK) 36f else 24f))
        outline.alpha = if (variant == Variant.ACTIVE || variant == Variant.HERO) 0.72f else 0.56f
    }

    private fun dp(value: Float): Float = value * density
    private fun withAlpha(color: Int, value: Int): Int = Color.argb(
        value.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color)
    )
    private fun mix(first: Int, second: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        val b = 1f - a
        return Color.rgb(
            (Color.red(first) * b + Color.red(second) * a).toInt(),
            (Color.green(first) * b + Color.green(second) * a).toInt(),
            (Color.blue(first) * b + Color.blue(second) * a).toInt()
        )
    }
}
