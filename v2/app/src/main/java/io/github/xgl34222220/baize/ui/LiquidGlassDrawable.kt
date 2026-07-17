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
 * The surface remains translucent enough to reveal the ambient backdrop while using directional
 * highlights, theme-colour refraction, frost grain and a soft outline to keep text readable. It is
 * deliberately drawable-based so long lists stay smooth on OEM ROMs that lack reliable backdrop blur.
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
    private val frostPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(if (variant == Variant.DOCK || variant == Variant.HERO) 1.1f else 0.75f)
    }
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.0f)
        strokeCap = Paint.Cap.ROUND
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.05f)
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
                Variant.DOCK -> 37f
                Variant.HERO -> 31f
                Variant.ACTIVE -> 29f
                Variant.STRIP -> 25f
                Variant.BUTTON -> 22f
                Variant.CARD -> 29f
            }
        )
        clipPath.reset()
        clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)

        val save = canvas.save()
        canvas.clipPath(clipPath)

        val baseTopAlpha = when (variant) {
            Variant.HERO -> 166
            Variant.DOCK -> 145
            Variant.ACTIVE -> 188
            Variant.BUTTON -> 218
            Variant.STRIP -> 98
            Variant.CARD -> 122
        }
        val baseBottomAlpha = when (variant) {
            Variant.HERO -> 106
            Variant.DOCK -> 92
            Variant.ACTIVE -> 126
            Variant.BUTTON -> 174
            Variant.STRIP -> 60
            Variant.CARD -> 74
        }
        val pressDelta = if (pressed) 20 else 0
        val themeMix = when (variant) {
            Variant.HERO -> 0.24f
            Variant.DOCK -> 0.12f
            Variant.ACTIVE -> 0.28f
            Variant.BUTTON -> 0.36f
            Variant.STRIP -> 0.06f
            Variant.CARD -> 0.10f
        }
        fillPaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            intArrayOf(
                alpha(mix(surfaceVariant, primary, themeMix), (baseTopAlpha + pressDelta).coerceAtMost(255)),
                alpha(mix(surfaceVariant, Color.WHITE, 0.025f), (baseTopAlpha - 18 + pressDelta).coerceAtMost(255)),
                alpha(mix(surface, tertiary, themeMix * 0.46f), (baseBottomAlpha + pressDelta).coerceAtMost(255))
            ),
            floatArrayOf(0f, 0.50f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, fillPaint)

        // White refraction near the upper edge makes the card read as glass rather than a gray slab.
        fillPaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.left,
            rect.top + rect.height() * 0.48f,
            intArrayOf(alpha(Color.WHITE, if (variant == Variant.DOCK || variant == Variant.HERO) 34 else 23), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, fillPaint)

        val glowStrength = when (variant) {
            Variant.HERO -> 92
            Variant.DOCK -> 76
            Variant.ACTIVE -> 126
            Variant.BUTTON -> 112
            Variant.STRIP -> 30
            Variant.CARD -> 52
        }
        drawGlow(canvas, rect.left + rect.width() * 0.13f, rect.top + rect.height() * 0.02f, primary, glowStrength, 0.84f)
        drawGlow(
            canvas,
            rect.right - rect.width() * 0.03f,
            rect.bottom - rect.height() * 0.06f,
            if (variant == Variant.ACTIVE || variant == Variant.BUTTON) primary else tertiary,
            glowStrength / 2,
            0.70f
        )

        if (variant == Variant.HERO || variant == Variant.CARD || variant == Variant.DOCK) {
            wavePath.reset()
            wavePath.moveTo(rect.left - dp(12f), rect.bottom - rect.height() * 0.19f)
            wavePath.cubicTo(
                rect.left + rect.width() * 0.25f,
                rect.bottom - rect.height() * 0.01f,
                rect.left + rect.width() * 0.56f,
                rect.bottom - rect.height() * 0.31f,
                rect.right + dp(12f),
                rect.bottom - rect.height() * 0.13f
            )
            wavePaint.shader = LinearGradient(
                rect.left,
                rect.bottom,
                rect.right,
                rect.top,
                alpha(primary, if (variant == Variant.DOCK) 45 else 34),
                alpha(secondary, 7),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(wavePath, wavePaint)
        }

        frostPaint.shader = null
        val grainCount = when (variant) {
            Variant.DOCK, Variant.HERO -> 18
            Variant.CARD -> 12
            else -> 6
        }
        for (index in 0 until grainCount) {
            val x = rect.left + rect.width() * (((index * 31 + 9) % 97) / 97f)
            val y = rect.top + rect.height() * (((index * 47 + 13) % 89) / 89f)
            frostPaint.color = alpha(if (index % 5 == 0) primary else Color.WHITE, if (index % 5 == 0) 10 else 7)
            canvas.drawCircle(x, y, dp(0.55f + (index % 2) * 0.20f), frostPaint)
        }
        canvas.restoreToCount(save)

        strokePaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            intArrayOf(
                alpha(Color.WHITE, if (variant == Variant.ACTIVE) 198 else 132),
                alpha(primary, if (variant == Variant.ACTIVE) 222 else 142),
                alpha(outline, 82)
            ),
            floatArrayOf(0f, 0.43f, 1f),
            Shader.TileMode.CLAMP
        )
        val inset = strokePaint.strokeWidth / 2f
        canvas.drawRoundRect(
            RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset),
            radius,
            radius,
            strokePaint
        )

        shinePaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.top,
            intArrayOf(alpha(Color.WHITE, 178), alpha(primary, 104), Color.TRANSPARENT),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        val y = rect.top + dp(1.55f)
        canvas.drawLine(rect.left + radius * 0.64f, y, rect.right - radius * 0.64f, y, shinePaint)
    }

    private fun drawGlow(canvas: Canvas, x: Float, y: Float, color: Int, strength: Int, radiusScale: Float) {
        glowPaint.shader = RadialGradient(
            x,
            y,
            max(rect.width(), rect.height()) * radiusScale,
            intArrayOf(alpha(color, strength), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, glowPaint)
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
        val radius = dp(if (variant == Variant.DOCK) 37f else 29f)
        outlineValue.setRoundRect(bounds, radius)
        outlineValue.alpha = when (variant) {
            Variant.DOCK, Variant.HERO -> 0.70f
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
