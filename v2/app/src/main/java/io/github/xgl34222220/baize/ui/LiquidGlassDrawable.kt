package io.github.xgl34222220.baize.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.google.android.material.color.MaterialColors
import kotlin.math.max

/**
 * Lightweight MIUI X inspired liquid-glass surface.
 *
 * The drawable avoids realtime bitmap blur on long scrolling pages. It layers translucent theme
 * surfaces, two refraction fields, a soft inner sheen and a floating edge so cards remain smooth on
 * rooted OEM ROMs while still looking visibly glass-like.
 */
class LiquidGlassDrawable(
    context: Context,
    private val variant: Variant = Variant.CARD
) : Drawable() {
    enum class Variant { CARD, HERO, STRIP, DOCK, ACTIVE, BUTTON }

    private val density = context.resources.displayMetrics.density
    private val primary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, Color.rgb(90, 168, 255))
    private val secondary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSecondary, Color.rgb(81, 214, 198))
    private val tertiary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorTertiary, Color.rgb(155, 140, 255))
    private val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.rgb(7, 16, 30))
    private val surfaceVariant = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceVariant, Color.rgb(23, 36, 56))
    private val outline = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutline, Color.rgb(100, 116, 138))

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(if (variant == Variant.DOCK || variant == Variant.HERO) 1.25f else 0.9f)
    }
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.1f)
        strokeCap = Paint.Cap.ROUND
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }

    private val rect = RectF()
    private val clipPath = Path()
    private val wavePath = Path()
    private var pressed = false

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        if (rect.width() <= 0f || rect.height() <= 0f) return

        val radius = dp(
            when (variant) {
                Variant.DOCK -> 38f
                Variant.HERO -> 31f
                Variant.ACTIVE -> 29f
                Variant.STRIP -> 25f
                Variant.BUTTON -> 23f
                Variant.CARD -> 28f
            }
        )
        clipPath.reset()
        clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)

        val baseTopAlpha = when (variant) {
            Variant.HERO -> 196
            Variant.DOCK -> 174
            Variant.ACTIVE -> 210
            Variant.BUTTON -> 232
            Variant.STRIP -> 142
            Variant.CARD -> 158
        }
        val baseBottomAlpha = when (variant) {
            Variant.HERO -> 138
            Variant.DOCK -> 118
            Variant.ACTIVE -> 164
            Variant.BUTTON -> 208
            Variant.STRIP -> 92
            Variant.CARD -> 104
        }
        val pressDelta = if (pressed) 18 else 0

        val save = canvas.save()
        canvas.clipPath(clipPath)

        fillPaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            intArrayOf(
                alpha(mix(surfaceVariant, primary, if (variant == Variant.HERO) 0.24f else 0.10f), (baseTopAlpha + pressDelta).coerceAtMost(255)),
                alpha(mix(surfaceVariant, Color.WHITE, 0.025f), (baseTopAlpha - 18 + pressDelta).coerceAtMost(255)),
                alpha(mix(surface, tertiary, 0.09f), (baseBottomAlpha + pressDelta).coerceAtMost(255))
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, fillPaint)

        val glowStrength = when (variant) {
            Variant.HERO -> 102
            Variant.DOCK -> 78
            Variant.ACTIVE -> 136
            Variant.BUTTON -> 118
            Variant.STRIP -> 44
            Variant.CARD -> 62
        }
        glowPaint.shader = RadialGradient(
            rect.left + rect.width() * 0.16f,
            rect.top + rect.height() * 0.02f,
            max(rect.width(), rect.height()) * 0.82f,
            intArrayOf(alpha(primary, glowStrength), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, glowPaint)

        glowPaint.shader = RadialGradient(
            rect.right - rect.width() * 0.03f,
            rect.bottom - rect.height() * 0.06f,
            max(rect.width(), rect.height()) * 0.70f,
            intArrayOf(alpha(if (variant == Variant.ACTIVE) primary else tertiary, glowStrength / 2), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, glowPaint)

        if (variant == Variant.HERO || variant == Variant.CARD || variant == Variant.DOCK) {
            wavePath.reset()
            wavePath.moveTo(rect.left - dp(14f), rect.bottom - rect.height() * 0.18f)
            wavePath.cubicTo(
                rect.left + rect.width() * 0.20f,
                rect.bottom - rect.height() * 0.01f,
                rect.left + rect.width() * 0.58f,
                rect.bottom - rect.height() * 0.34f,
                rect.right + dp(14f),
                rect.bottom - rect.height() * 0.12f
            )
            wavePaint.shader = LinearGradient(
                rect.left,
                rect.bottom,
                rect.right,
                rect.top,
                alpha(primary, if (variant == Variant.DOCK) 46 else 38),
                alpha(secondary, 6),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(wavePath, wavePaint)
        }
        canvas.restoreToCount(save)

        strokePaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            intArrayOf(
                alpha(Color.WHITE, if (variant == Variant.ACTIVE) 214 else 148),
                alpha(primary, if (variant == Variant.ACTIVE) 226 else 152),
                alpha(outline, 92)
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
        val inset = strokePaint.strokeWidth / 2f
        canvas.drawRoundRect(
            RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset),
            radius,
            radius,
            strokePaint
        )

        innerPaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            alpha(Color.WHITE, 48),
            alpha(primary, 14),
            Shader.TileMode.CLAMP
        )
        val innerInset = dp(2.4f)
        canvas.drawRoundRect(
            RectF(rect.left + innerInset, rect.top + innerInset, rect.right - innerInset, rect.bottom - innerInset),
            radius - innerInset,
            radius - innerInset,
            innerPaint
        )

        shinePaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.top,
            intArrayOf(alpha(Color.WHITE, 190), alpha(primary, 112), Color.TRANSPARENT),
            floatArrayOf(0f, 0.47f, 1f),
            Shader.TileMode.CLAMP
        )
        val y = rect.top + dp(1.7f)
        canvas.drawLine(rect.left + radius * 0.68f, y, rect.right - radius * 0.70f, y, shinePaint)
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

    override fun getOutline(outlineValue: Outline) {
        val radius = dp(if (variant == Variant.DOCK) 38f else 28f)
        outlineValue.setRoundRect(bounds, radius)
        outlineValue.alpha = when (variant) {
            Variant.DOCK, Variant.HERO -> 0.70f
            Variant.ACTIVE -> 0.78f
            else -> 0.56f
        }
    }

    private fun dp(value: Float): Float = value * density

    private fun alpha(color: Int, value: Int): Int = Color.argb(
        value.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun mix(first: Int, second: Int, amount: Float): Int {
        val inverse = 1f - amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(first) * inverse + Color.red(second) * amount).toInt(),
            (Color.green(first) * inverse + Color.green(second) * amount).toInt(),
            (Color.blue(first) * inverse + Color.blue(second) * amount).toInt()
        )
    }
}
