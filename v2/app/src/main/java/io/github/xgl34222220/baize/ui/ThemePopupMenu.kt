package io.github.xgl34222220.baize.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.min

/** Rounded, dimmed and anchor-aware option menu matching the reference app. */
object ThemePopupMenu {
    data class Option(
        val id: String,
        val label: String,
        val colors: IntArray = intArrayOf()
    )

    fun show(
        activity: Activity,
        anchor: View,
        options: List<Option>,
        selectedId: String,
        onSelected: (Option) -> Unit
    ) {
        if (options.isEmpty()) return
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()

        val primary = MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorPrimary)
        val surface = MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorSurface)
        val onSurface = MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorOnSurface)
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.windowManager.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            activity.resources.displayMetrics.run { android.graphics.Rect(0, 0, widthPixels, heightPixels) }
        }

        val popupWidth = min(dp(340), bounds.width() - dp(40))
        val rowHeight = dp(66)
        val desiredHeight = dp(16) + rowHeight * options.size
        val popupHeight = min(desiredHeight, bounds.height() - dp(104))

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            clipToPadding = false
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(surface)
                cornerRadius = dp(24).toFloat()
            }
            elevation = dp(18).toFloat()
            clipToOutline = true
        }

        val popup = PopupWindow(scroll, popupWidth, popupHeight, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            isFocusable = true
            elevation = dp(18).toFloat()
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

        options.forEach { option ->
            val selected = option.id == selectedId
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = rowHeight
                setPadding(dp(24), 0, dp(18), 0)
                isClickable = true
                isFocusable = true
                foreground = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
                    val drawable = it.getDrawable(0)
                    it.recycle()
                    drawable
                }
            }
            val label = TextView(activity).apply {
                text = option.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(if (selected) primary else onSurface)
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            row.addView(label)

            if (option.colors.isNotEmpty()) {
                row.addView(PaletteDotsView(activity).apply {
                    setColors(option.colors)
                    layoutParams = LinearLayout.LayoutParams(dp(58), dp(32)).apply { marginEnd = dp(12) }
                })
            }

            row.addView(TextView(activity).apply {
                text = if (selected) "✓" else ""
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                setTextColor(primary)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.MATCH_PARENT)
            })

            row.setOnClickListener {
                onSelected(option)
                popup.dismiss()
            }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight))
        }

        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val x = max(dp(20), bounds.width() - popupWidth - dp(24))
        val idealY = location[1] - popupHeight / 3
        val y = idealY.coerceIn(dp(82), max(dp(82), bounds.height() - popupHeight - dp(28)))
        popup.showAtLocation(activity.window.decorView, Gravity.TOP or Gravity.START, x, y)

        scroll.post {
            val root = scroll.rootView
            val params = root.layoutParams as? WindowManager.LayoutParams ?: return@post
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            params.dimAmount = 0.34f
            runCatching {
                (activity.getSystemService(Activity.WINDOW_SERVICE) as WindowManager).updateViewLayout(root, params)
            }
        }
    }
}
