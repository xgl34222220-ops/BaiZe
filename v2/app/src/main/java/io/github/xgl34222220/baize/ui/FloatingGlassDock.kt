package io.github.xgl34222220.baize.ui

import android.animation.TimeInterpolator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.ViewCompat
import com.google.android.material.color.MaterialColors
import io.github.xgl34222220.baize.R

/**
 * Four-item floating navigation dock built specifically for BaiZe.
 *
 * Material's default bottom navigation squeezes labels against icons on several OEM font scales.
 * This dock owns every dimension, keeping a real 5dp icon/label gap, a 68dp active glass capsule
 * and a safe floating margin above the gesture bar.
 */
class FloatingGlassDock @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    data class Item(val itemId: Int)

    private data class Spec(val id: Int, val icon: Int, val title: String)

    private val specs = listOf(
        Spec(R.id.nav_home, R.drawable.ic_nav_home, "首页"),
        Spec(R.id.nav_plan, R.drawable.ic_nav_plan, "计划"),
        Spec(R.id.nav_records, R.drawable.ic_nav_records, "记录"),
        Spec(R.id.nav_settings, R.drawable.ic_nav_settings, "设置")
    )
    private val items = LinkedHashMap<Int, DockItemView>()
    private var listener: ((Item) -> Boolean)? = null
    private val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val inactive = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val activeText = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer)
    private val bounce: TimeInterpolator = OvershootInterpolator(0.76f)

    var selectedItemId: Int = R.id.nav_home
        set(value) {
            if (field == value && items.isNotEmpty()) {
                renderSelection(value, false)
                return
            }
            field = value
            renderSelection(value, true)
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = dp(84)
        setPadding(dp(7), dp(7), dp(7), dp(7))
        background = LiquidGlassDrawable(context, LiquidGlassDrawable.Variant.DOCK)
        elevation = dp(22).toFloat()
        translationZ = dp(4).toFloat()
        clipToPadding = false
        clipChildren = false
        isClickable = true
        ViewCompat.setImportantForAccessibility(this, ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_YES)
        buildItems()
        post { renderSelection(selectedItemId, false) }
    }

    fun setOnItemSelectedListener(block: (Item) -> Boolean) {
        listener = block
    }

    private fun buildItems() {
        removeAllViews()
        items.clear()
        specs.forEach { spec ->
            val item = DockItemView(context, spec.icon, spec.title).apply {
                layoutParams = LayoutParams(0, dp(68), 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
                setOnClickListener {
                    if (selectedItemId == spec.id) return@setOnClickListener
                    val accepted = listener?.invoke(Item(spec.id)) ?: true
                    if (accepted) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        selectedItemId = spec.id
                    }
                }
            }
            items[spec.id] = item
            addView(item)
        }
    }

    private fun renderSelection(selected: Int, animated: Boolean) {
        items.forEach { (id, view) ->
            val active = id == selected
            view.setActive(active, animated, primary, activeText, inactive, bounce)
        }
    }

    private inner class DockItemView(
        context: Context,
        iconRes: Int,
        title: String
    ) : LinearLayout(context) {
        private val icon = AppCompatImageView(context)
        private val label = TextView(context)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(7), dp(5), dp(5))
            minimumWidth = dp(68)
            isClickable = true
            isFocusable = true
            clipToOutline = true
            contentDescription = title

            icon.layoutParams = LayoutParams(dp(27), dp(27))
            icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            icon.setImageDrawable(AppCompatResources.getDrawable(context, iconRes))
            addView(icon)

            label.layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(5)
            }
            label.text = title
            label.gravity = Gravity.CENTER
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            label.setLineSpacing(0f, 1f)
            label.includeFontPadding = false
            label.maxLines = 1
            addView(label)
        }

        fun setActive(
            active: Boolean,
            animated: Boolean,
            primary: Int,
            activeText: Int,
            inactive: Int,
            interpolator: TimeInterpolator
        ) {
            icon.imageTintList = ColorStateList.valueOf(if (active) activeText else inactive)
            label.setTextColor(if (active) activeText else inactive)
            label.setTypeface(label.typeface, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            background = if (active) LiquidGlassDrawable(context, LiquidGlassDrawable.Variant.ACTIVE) else null
            elevation = if (active) dp(12).toFloat() else 0f
            if (!animated) {
                scaleX = if (active) 1f else 0.94f
                scaleY = if (active) 1f else 0.94f
                alpha = if (active) 1f else 0.78f
                return
            }
            scaleX = if (active) 0.90f else 1f
            scaleY = if (active) 0.90f else 1f
            animate()
                .scaleX(if (active) 1f else 0.94f)
                .scaleY(if (active) 1f else 0.94f)
                .alpha(if (active) 1f else 0.78f)
                .setDuration(340L)
                .setInterpolator(interpolator)
                .start()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
