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

/** Calm MIUIx background: stable base surface with only a very soft theme tint. */
class LiquidBackdropDrawable(context: Context) : Drawable() {
    private val background = MaterialColors.getColor(context, android.R.attr.colorBackground, Color.rgb(241, 242, 251))
    private val primary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, Color.rgb(11, 103, 209))
    private val light = ColorUtils.calculateLuminance(background) > 0.45
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
                mix(background, primary, if (light) 0.025f else 0.018f),
                background,
                mix(background, primary, if (light) 0.012f else 0.01f)
            ),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = RadialGradient(
            rect.right * 0.92f,
            rect.top + rect.height() * 0.08f,
            max(rect.width(), rect.height()) * 0.52f,
            intArrayOf(withAlpha(primary, if (light) 14 else 16), Color.TRANSPARENT),
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
