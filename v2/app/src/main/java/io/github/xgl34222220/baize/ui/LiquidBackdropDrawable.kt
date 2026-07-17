package io.github.xgl34222220.baize.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.google.android.material.color.MaterialColors
import kotlin.math.max

/** Theme-aware ambient background that gives translucent surfaces visible depth and refraction. */
class LiquidBackdropDrawable(context: Context) : Drawable() {
    private val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.rgb(5, 12, 27))
    private val primary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, Color.rgb(91, 169, 255))
    private val secondary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSecondary, Color.rgb(82, 215, 198))
    private val tertiary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorTertiary, Color.rgb(157, 139, 255))
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.1f
        strokeCap = Paint.Cap.ROUND
    }
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        if (rect.isEmpty) return

        paint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            intArrayOf(
                darken(surface, 0.38f),
                mix(surface, primary, 0.075f),
                mix(surface, tertiary, 0.045f),
                darken(surface, 0.48f)
            ),
            floatArrayOf(0f, 0.36f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)

        drawGlow(canvas, rect.right * 0.91f, rect.top + rect.height() * 0.08f, primary, 62, 0.72f)
        drawGlow(canvas, rect.left + rect.width() * 0.04f, rect.bottom - rect.height() * 0.10f, tertiary, 48, 0.64f)
        drawGlow(canvas, rect.right - rect.width() * 0.08f, rect.top + rect.height() * 0.58f, secondary, 28, 0.48f)

        path.reset()
        path.moveTo(rect.left - rect.width() * 0.10f, rect.top + rect.height() * 0.34f)
        path.cubicTo(
            rect.left + rect.width() * 0.20f,
            rect.top + rect.height() * 0.20f,
            rect.left + rect.width() * 0.66f,
            rect.top + rect.height() * 0.49f,
            rect.right + rect.width() * 0.12f,
            rect.top + rect.height() * 0.29f
        )
        linePaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            alpha(primary, 27),
            alpha(secondary, 3),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, linePaint)

        path.reset()
        path.moveTo(rect.left - rect.width() * 0.08f, rect.top + rect.height() * 0.76f)
        path.cubicTo(
            rect.left + rect.width() * 0.30f,
            rect.top + rect.height() * 0.58f,
            rect.left + rect.width() * 0.62f,
            rect.top + rect.height() * 0.88f,
            rect.right + rect.width() * 0.10f,
            rect.top + rect.height() * 0.66f
        )
        linePaint.shader = LinearGradient(
            rect.left,
            rect.bottom,
            rect.right,
            rect.top,
            alpha(tertiary, 22),
            alpha(primary, 2),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, linePaint)

        // A tiny deterministic frost grain prevents large dark areas from looking like a flat fill.
        grainPaint.shader = null
        for (index in 0 until 26) {
            val x = rect.left + rect.width() * (((index * 37) % 101) / 101f)
            val y = rect.top + rect.height() * (((index * 61 + 17) % 103) / 103f)
            val radius = density * (0.45f + (index % 3) * 0.18f)
            grainPaint.color = alpha(if (index % 4 == 0) primary else Color.WHITE, if (index % 4 == 0) 8 else 5)
            canvas.drawCircle(x, y, radius, grainPaint)
        }
    }

    private fun drawGlow(canvas: Canvas, x: Float, y: Float, color: Int, strength: Int, radiusScale: Float) {
        paint.shader = RadialGradient(
            x,
            y,
            max(rect.width(), rect.height()) * radiusScale,
            intArrayOf(alpha(color, strength), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE

    private fun alpha(color: Int, value: Int): Int = Color.argb(
        value.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
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

    private fun darken(color: Int, amount: Float): Int = mix(color, Color.BLACK, amount)
}
