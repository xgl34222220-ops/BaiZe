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
import android.util.TypedValue
import com.google.android.material.color.MaterialColors
import io.github.xgl34222220.baize.R
import kotlin.math.max

/**
 * Lightweight MIUI X inspired liquid-glass surface.
 *
 * Android does not expose a reliable per-view backdrop blur API across rooted OEM ROMs. Instead of
 * putting a heavy realtime bitmap blur on every scrolling card, this drawable builds the glass from
 * translucent theme surfaces, directional highlights, colored refraction and a soft floating edge.
 * It stays fast inside long lists while Monet/fixed palettes still recolor the complete surface.
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
        strokeWidth = dp(if (variant == Variant.DOCK || variant == Variant.HERO) 1.15f else 0.8f)
    }
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.05f)
        strokeCap = Paint.Cap.ROUND
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.1f)
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
                Variant.DOCK -> 36f
                Variant.HERO -> 30f
                Variant.ACTIVE -> 28f
                Variant.STRIP -> 24f
                Variant.BUTTON -> 22f
                Variant.CARD -> 28f
            }
        )
        clipPath.reset()
        clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)

        val save = canvas.save()
        canvas.clipPath(clipPath)

        val baseTopAlpha = when (variant) {
            Variant.HERO -> 220
            Variant.DOCK -> 205
            Variant.ACTIVE -> 218
            Variant.BUTTON -> 235
            Variant.STRIP -> 168
            Variant.CARD -> 185
        }
        val baseBottomAlpha = when (variant) {
            Variant.HERO -> 180
            Variant.DOCK -> 174
            Variant.ACTIVE -> 186
            Variant.BUTTON -> 220
            Variant.STRIP -> 138
            Variant.CARD -> 148
        }
        val pressDelta = if (pressed) 18 else 0
        fillPaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            intArrayOf(
                alpha(mix(surfaceVariant, primary, if (variant == Variant.HERO) 0.20f else 0.08f), (baseTopAlpha + pressDelta).coerceAtMost(255)),
                alpha(surfaceVariant, (baseTopAlpha - 10 + pressDelta).coerceAtMost(255)),
                alpha(mix(surface, primary, 0.10f), (baseBottomAlpha + pressDelta).coerceAtMost(255))
            ),
            floatArrayOf(0f, 0.53f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, fillPaint)

        val glowStrength = when (variant) {
            Variant.HERO -> 80
            Variant.DOCK -> 66
            Variant.ACTIVE -> 116
            Variant.BUTTON -> 100
            Variant.STRIP -> 34
            Variant.CARD -> 48
        }
        glowPaint.shader = RadialGradient(
            rect.left + rect.width() * 0.18f,
            rect.top + rect.height() * 0.05f,
            max(rect.width(), rect.height()) * 0.85f,
            intArrayOf(alpha(primary, glowStrength), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, glowPaint)

        glowPaint.shader = RadialGradient(
            rect.right - rect.width() * 0.04f,
            rect.bottom - rect.height() * 0.10f,
            max(rect.width(), rect.height()) * 0.72f,
            intArrayOf(alpha(if (variant == Variant.ACTIVE) primary else tertiary, glowStrength / 2), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, glowPaint)

        if (variant == Variant.HERO || variant == Variant.CARD || variant == Variant.DOCK) {
            wavePath.reset()
            wavePath.moveTo(rect.left - dp(12f), rect.bottom - rect.height() * 0.20f)
            wavePath.cubicTo(
                rect.left + rect.width() * 0.24f,
                rect.bottom - rect.height() * 0.02f,
                rect.left + rect.width() * 0.55f,
                rect.bottom - rect.height() * 0.30f,
                rect.right + dp(12f),
                rect.bottom - rect.height() * 0.14f
            )
            wavePaint.shader = LinearGradient(
                rect.left,
                rect.bottom,
                rect.right,
                rect.top,
                alpha(primary, 34),
                alpha(secondary, 8),
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
            intArrayOf(alpha(Color.WHITE, if (variant == Variant.ACTIVE) 190 else 116), alpha(primary, if (variant == Variant.ACTIVE) 220 else 126), alpha(outline, 96)),
            floatArrayOf(0f, 0.43f, 1f),
            Shader.TileMode.CLAMP
        )
        val inset = strokePaint.strokeWidth / 2f
        canvas.drawRoundRect(RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset), radius, radius, strokePaint)

        shinePaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.top,
            intArrayOf(alpha(Color.WHITE, 164), alpha(primary, 98), Color.TRANSPARENT),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        val y = rect.top + dp(1.6f)
        canvas.drawLine(rect.left + radius * 0.65f, y, rect.right - radius * 0.65f, y, shinePaint)
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
        val radius = dp(if (variant == Variant.DOCK) 36f else 28f)
        outlineValue.setRoundRect(bounds, radius)
        outlineValue.alpha = when (variant) {
            Variant.DOCK, Variant.HERO -> 0.78f
            else -> 0.62f
        }
    }

    private fun dp(value: Float): Float = value * density

    private fun alpha(color: Int, value: Int): Int = Color.argb(value.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun mix(first: Int, second: Int, amount: Float): Int {
        val inverse = 1f - amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(first) * inverse + Color.red(second) * amount).toInt(),
            (Color.green(first) * inverse + Color.green(second) * amount).toInt(),
            (Color.blue(first) * inverse + Color.blue(second) * amount).toInt()
        )
    }
}
