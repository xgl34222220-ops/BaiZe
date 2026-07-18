package io.github.xgl34222220.baize.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors

/** Lightweight translucent surface reserved for the floating dock and small floating controls. */
class LiquidGlassDrawable(
    context: Context,
    private val variant: Variant = Variant.CARD
) : Drawable() {
    enum class Variant { CARD, HERO, STRIP, DOCK, ACTIVE, BUTTON }

    private val density = context.resources.displayMetrics.density
    private val primary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, Color.rgb(18, 104, 215))
    private val primaryContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimaryContainer, Color.rgb(221, 232, 255))
    private val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.rgb(251, 250, 255))
    private val surfaceVariant = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceVariant, Color.rgb(244, 243, 250))
    private val outline = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, Color.rgb(231, 232, 239))
    private val light = ColorUtils.calculateLuminance(surface) > 0.45
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
    }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        if (rect.isEmpty) return
        val radius = dp(
            when (variant) {
                Variant.DOCK -> 34f
                Variant.HERO -> 30f
                Variant.ACTIVE -> 26f
                Variant.BUTTON -> 20f
                Variant.STRIP -> 24f
                Variant.CARD -> 26f
            }
        )
        val base = when (variant) {
            Variant.ACTIVE, Variant.BUTTON, Variant.HERO -> primaryContainer
            Variant.STRIP -> surfaceVariant
            else -> surface
        }
        val tintAmount = when (variant) {
            Variant.DOCK -> if (light) 0.018f else 0.035f
            Variant.ACTIVE, Variant.BUTTON -> 0.025f
            else -> 0f
        }
        fill.color = ColorUtils.setAlphaComponent(
            mix(base, primary, tintAmount),
            when (variant) {
                Variant.DOCK -> if (light) 238 else 232
                else -> 255
            }
        )
        canvas.drawRoundRect(rect, radius, radius, fill)

        border.color = outline
        border.alpha = if (variant == Variant.DOCK) 150 else 95
        val inset = border.strokeWidth / 2f
        canvas.drawRoundRect(
            RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset),
            radius,
            radius,
            border
        )
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, dp(if (variant == Variant.DOCK) 34f else 26f))
        outline.alpha = if (variant == Variant.DOCK) 0.82f else 1f
    }

    private fun dp(value: Float): Float = value * density

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
