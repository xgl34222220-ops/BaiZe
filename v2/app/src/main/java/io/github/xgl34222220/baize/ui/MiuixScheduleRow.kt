package io.github.xgl34222220.baize.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.slider.Slider
import com.google.android.material.color.MaterialColors

/** Compact independent scheduler row used by the five cleaner groups. */
class MiuixScheduleRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    private val titleView = TextView(context)
    private val valueView = TextView(context)
    private val toggle = MiuixSwitch(context)
    private val slider = Slider(context)
    private var listener: (() -> Unit)? = null

    var title: CharSequence
        get() = titleView.text
        set(value) { titleView.text = value }

    var enabledForSchedule: Boolean
        get() = toggle.isChecked
        set(value) {
            toggle.setCheckedSilently(value)
            renderEnabledState()
        }

    var hours: Int
        get() = slider.value.toInt()
        set(value) {
            slider.value = value.coerceIn(MIN_HOURS, MAX_HOURS).toFloat()
            renderValue()
        }

    init {
        orientation = VERTICAL
        setPadding(dp(18), dp(12), dp(14), dp(8))

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView.apply {
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
        }
        valueView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
            setPadding(dp(8), 0, dp(8), 0)
        }
        toggle.layoutParams = LayoutParams(dp(58), dp(44))
        header.addView(titleView)
        header.addView(valueView)
        header.addView(toggle)
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        slider.apply {
            valueFrom = MIN_HOURS.toFloat()
            valueTo = MAX_HOURS.toFloat()
            stepSize = 1f
            value = 24f
            isTickVisible = false
            addOnChangeListener { _, _, fromUser ->
                renderValue()
                if (fromUser) listener?.invoke()
            }
        }
        addView(slider, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
        toggle.setOnCheckedChangeListener { _, _ ->
            renderEnabledState()
            listener?.invoke()
        }
        renderEnabledState()
        renderValue()
    }

    fun configure(label: CharSequence, defaultHours: Int) {
        title = label
        hours = defaultHours
    }

    fun setOnScheduleChangedListener(block: () -> Unit) {
        listener = block
    }

    private fun renderValue() {
        valueView.text = if (toggle.isChecked) "${hours} 小时" else "已关闭"
    }

    private fun renderEnabledState() {
        slider.isEnabled = toggle.isChecked
        slider.alpha = if (toggle.isChecked) 1f else 0.38f
        renderValue()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val MIN_HOURS = 1
        private const val MAX_HOURS = 720
    }
}
