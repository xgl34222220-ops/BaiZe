package io.github.xgl34222220.baize.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import io.github.xgl34222220.baize.ThemeManager

/**
 * Static MIUIx-style ambient backdrop.
 *
 * The gradients are rebuilt only when the view size or theme changes. There is no frame-by-frame
 * blur or animation, so even older devices keep smooth scrolling while translucent cards and the
 * dock still have visible depth.
 */
class MiuixBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val background = MaterialColors.getColor(
        this,
        android.R.attr.colorBackground,
        Color.rgb(242, 245, 250)
    )
    private val primary = MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorPrimary,
        Color.rgb(37, 137, 244)
    )
    private val tertiary = MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorTertiary,
        Color.rgb(116, 104, 216)
    )
    private val effectsEnabled = ThemeManager.isBlurEnabled(context)
    private var topGlow: RadialGradient? = null
    private var sideGlow: RadialGradient? = null

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0 || height <= 0) return
        val topRadius = width * 1.08f
        topGlow = RadialGradient(
            width * 0.82f,
            -height * 0.02f,
            topRadius,
            intArrayOf(
                ColorUtils.setAlphaComponent(primary, if (effectsEnabled) 48 else 18),
                ColorUtils.setAlphaComponent(primary, if (effectsEnabled) 14 else 5),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.46f, 1f),
            Shader.TileMode.CLAMP
        )
        sideGlow = RadialGradient(
            -width * 0.08f,
            height * 0.57f,
            width * 0.92f,
            intArrayOf(
                ColorUtils.setAlphaComponent(tertiary, if (effectsEnabled) 30 else 10),
                ColorUtils.setAlphaComponent(tertiary, if (effectsEnabled) 8 else 3),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(background)
        topGlow?.let {
            paint.shader = it
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        sideGlow?.let {
            paint.shader = it
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        paint.shader = null
    }
}
