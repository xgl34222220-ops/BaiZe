package io.github.xgl34222220.baize.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.ViewCompat
import com.google.android.material.color.MaterialColors
import io.github.xgl34222220.baize.R
import io.github.xgl34222220.baize.ThemeManager

/** Box-inspired four-item floating bar: compact, calm and easy to read with custom fonts. */
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
    private val primaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
    private val inactive = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val activeText = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer)
    private val glassEnabled = ThemeManager.isGlassEnabled(context)
    private val easing = DecelerateInterpolator(1.6f)

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
        minimumHeight = dp(76)
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = if (glassEnabled) {
            LiquidGlassDrawable(context, LiquidGlassDrawable.Variant.DOCK)
        } else {
            rounded(surface, 34, outline, 1)
        }
        elevation = dp(if (glassEnabled) 12 else 8).toFloat()
        translationZ = dp(3).toFloat()
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
                layoutParams = LayoutParams(0, dp(62), 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
                setOnClickListener {
                    if (selectedItemId == spec.id) return@setOnClickListener
                    val accepted = runCatching { listener?.invoke(Item(spec.id)) ?: true }.getOrDefault(false)
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
        items.forEach { (id, view) -> view.setActive(id == selected, animated) }
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
            setPadding(dp(5), dp(5), dp(5), dp(4))
            minimumWidth = dp(64)
            isClickable = true
            isFocusable = true
            clipToOutline = true
            contentDescription = title

            icon.layoutParams = LayoutParams(dp(21), dp(21))
            icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            icon.setImageDrawable(AppCompatResources.getDrawable(context, iconRes))
            addView(icon)

            label.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(19)).apply {
                topMargin = dp(4)
            }
            label.text = title
            label.gravity = Gravity.CENTER
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            label.includeFontPadding = false
            label.maxLines = 1
            label.letterSpacing = 0.01f
            addView(label)
        }

        fun setActive(active: Boolean, animated: Boolean) {
            icon.imageTintList = ColorStateList.valueOf(if (active) activeText else inactive)
            label.setTextColor(if (active) activeText else inactive)
            label.setTypeface(label.typeface, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            background = if (active) rounded(primaryContainer, 26) else null
            elevation = if (active) dp(2).toFloat() else 0f
            if (!animated) {
                scaleX = if (active) 1f else 0.96f
                scaleY = if (active) 1f else 0.96f
                alpha = if (active) 1f else 0.82f
                return
            }
            animate().cancel()
            animate()
                .scaleX(if (active) 1f else 0.96f)
                .scaleY(if (active) 1f else 0.96f)
                .alpha(if (active) 1f else 0.82f)
                .setDuration(180L)
                .setInterpolator(easing)
                .start()
        }
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null && strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
