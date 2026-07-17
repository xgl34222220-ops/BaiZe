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

/** Dark theme-aware ambient background used behind the translucent glass surfaces. */
class LiquidBackdropDrawable(context: Context) : Drawable() {
    private val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.rgb(5, 12, 27))
    private val primary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, Color.rgb(91, 169, 255))
    private val secondary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSecondary, Color.rgb(82, 215, 198))
    private val tertiary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorTertiary, Color.rgb(157, 139, 255))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 1.2f
    }
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
            intArrayOf(darken(surface, 0.30f), mix(surface, primary, 0.055f), darken(surface, 0.42f)),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)

        paint.shader = RadialGradient(
            rect.right * 0.87f,
            rect.top + rect.height() * 0.12f,
            max(rect.width(), rect.height()) * 0.72f,
            intArrayOf(alpha(primary, 40), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)

        paint.shader = RadialGradient(
            rect.left + rect.width() * 0.08f,
            rect.bottom - rect.height() * 0.12f,
            max(rect.width(), rect.height()) * 0.65f,
            intArrayOf(alpha(tertiary, 26), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)

        path.reset()
        path.moveTo(rect.left - rect.width() * 0.10f, rect.top + rect.height() * 0.58f)
        path.cubicTo(
            rect.left + rect.width() * 0.24f,
            rect.top + rect.height() * 0.43f,
            rect.left + rect.width() * 0.62f,
            rect.top + rect.height() * 0.72f,
            rect.right + rect.width() * 0.10f,
            rect.top + rect.height() * 0.49f
        )
        linePaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            alpha(primary, 15),
            alpha(secondary, 5),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, linePaint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE

    private fun alpha(color: Int, value: Int): Int = Color.argb(value.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

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
