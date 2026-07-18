package io.github.xgl34222220.baize.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.max

/** Low-cost ambient background: neutral surface with two restrained palette glows. */
class LiquidBackdropDrawable(context: Context) : Drawable() {
    private val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.rgb(246, 247, 251))
    private val primary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, Color.rgb(53, 109, 243))
    private val secondary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSecondary, Color.rgb(23, 122, 131))
    private val light = ColorUtils.calculateLuminance(surface) > 0.45
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        if (rect.isEmpty) return
        paint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            intArrayOf(
                mix(surface, if (light) Color.WHITE else Color.BLACK, if (light) 0.18f else 0.08f),
                mix(surface, primary, if (light) 0.035f else 0.055f),
                surface
            ),
            floatArrayOf(0f, 0.56f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        drawGlow(canvas, rect.right * 0.92f, rect.top + rect.height() * 0.07f, primary, if (light) 26 else 38, 0.58f)
        drawGlow(canvas, rect.left + rect.width() * 0.03f, rect.bottom - rect.height() * 0.05f, secondary, if (light) 18 else 25, 0.54f)
    }

    private fun drawGlow(canvas: Canvas, x: Float, y: Float, color: Int, alpha: Int, scale: Float) {
        paint.shader = RadialGradient(
            x,
            y,
            max(rect.width(), rect.height()) * scale,
            intArrayOf(withAlpha(color, alpha), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE

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
