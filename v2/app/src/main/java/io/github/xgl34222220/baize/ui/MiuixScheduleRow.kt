package io.github.xgl34222220.baize.ui

import android.content.Context
import android.content.DialogInterface
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider

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
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            contentDescription = "点击精确输入 1 到 720 小时"
            setOnClickListener { showExactHoursDialog() }
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

    private fun showExactHoursDialog() {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(hours.toString())
            hint = "$MIN_HOURS - $MAX_HOURS"
            setSelectAllOnFocus(true)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("$title · 执行周期")
            .setMessage("输入 1 到 720 小时，可精确设置数百小时周期。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val selected = input.text?.toString()?.trim()?.toIntOrNull()
                if (selected == null || selected !in MIN_HOURS..MAX_HOURS) {
                    input.error = "请输入 1 到 720 之间的整数"
                    return@setOnClickListener
                }
                slider.value = selected.toFloat()
                renderValue()
                listener?.invoke()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun formatHours(value: Int): String =
        if (value >= 24 && value % 24 == 0) "$value 小时 / ${value / 24} 天" else "$value 小时"

    private fun renderValue() {
        valueView.text = if (toggle.isChecked) "${formatHours(hours)} ›" else "已关闭 · ${formatHours(hours)} ›"
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
