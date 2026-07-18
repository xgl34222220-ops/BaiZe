package io.github.xgl34222220.baize.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

class PaletteDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var colors = intArrayOf(0xFF1268D7.toInt(), 0xFFBFC5D3.toInt(), 0xFFE9DFFF.toInt())

    fun setColors(value: IntArray) {
        colors = if (value.isEmpty()) colors else value.copyOf(3.coerceAtMost(value.size))
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(58f).toInt(), widthMeasureSpec), resolveSize(dp(28f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = dp(7f)
        val gap = dp(7f)
        val total = colors.size * radius * 2 + (colors.size - 1).coerceAtLeast(0) * gap
        var x = (width - total) / 2f + radius
        colors.forEach { color ->
            paint.color = color
            canvas.drawCircle(x, height / 2f, radius, paint)
            x += radius * 2 + gap
        }
    }

    private fun dp(value: Float) = value * density
}

class ChevronPairView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.1f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = MaterialColors.getColor(this@ChevronPairView, com.google.android.material.R.attr.colorOnSurfaceVariant)
    }
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(24f).toInt(), widthMeasureSpec), resolveSize(dp(34f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val half = dp(5.5f)
        val topY = height / 2f - dp(6f)
        path.reset()
        path.moveTo(cx - half, topY + dp(3f))
        path.lineTo(cx, topY - dp(2f))
        path.lineTo(cx + half, topY + dp(3f))
        canvas.drawPath(path, paint)

        val bottomY = height / 2f + dp(6f)
        path.reset()
        path.moveTo(cx - half, bottomY - dp(3f))
        path.lineTo(cx, bottomY + dp(2f))
        path.lineTo(cx + half, bottomY - dp(3f))
        canvas.drawPath(path, paint)
    }

    private fun dp(value: Float) = value * density
}
