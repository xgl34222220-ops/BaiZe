package io.github.xgl34222220.baize.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import com.google.android.material.color.MaterialColors
import io.github.xgl34222220.baize.R
import io.github.xgl34222220.baize.ThemeManager

/**
 * MIUIx floating dock with one animated liquid selection capsule.
 *
 * Only translation/alpha/scale properties animate. The background itself is static, avoiding the
 * expensive per-frame blur that made the previous WebUI implementation stutter on large pages.
 */
class FloatingGlassDock @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    data class Item(val itemId: Int)
    private data class Spec(val id: Int, val icon: Int, val title: String)

    private val specs = listOf(
        Spec(R.id.nav_home, R.drawable.ic_nav_home, "首页"),
        Spec(R.id.nav_plan, R.drawable.ic_nav_plan, "清理"),
        Spec(R.id.nav_records, R.drawable.ic_nav_records, "记录"),
        Spec(R.id.nav_settings, R.drawable.ic_nav_settings, "设置")
    )
    private val itemViews = LinkedHashMap<Int, DockItemView>()
    private val row = LinearLayout(context)
    private val capsule = View(context)
    private val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val amoled = ThemeManager.isAmoledActive(context)
    private val dark = ColorUtils.calculateLuminance(surface) < .5
    private val glassEnabled = ThemeManager.isGlassEnabled(context) && !amoled
    private val dockItemHeight = dp(60) + (dp(16) * (resources.configuration.fontScale - 1f).coerceIn(0f, 1f)).toInt()
    private val floatingEnabled = ThemeManager.isFloatingDockEnabled(context)
    private val easing = PathInterpolator(0.2f, 0f, 0f, 1f)
    private var listener: ((Item) -> Boolean)? = null

    var selectedItemId: Int = R.id.nav_home
        set(value) {
            if (field == value && itemViews.isNotEmpty()) {
                renderSelection(false)
                return
            }
            field = value
            renderSelection(true)
        }

    init {
        setPadding(dp(6), dp(6), dp(6), dp(6))
        clipChildren = false
        clipToPadding = false
        minimumHeight = dockItemHeight + dp(12)
        background = when {
            amoled -> rounded(android.graphics.Color.BLACK, if (floatingEnabled) 31 else 0)
            glassEnabled -> glassSurface(selected = false)
            floatingEnabled -> rounded(surface, 31)
            else -> rounded(surface, 0)
        }
        elevation = dp(if (floatingEnabled) 18 else 3).toFloat()
        translationZ = dp(0).toFloat()
        ViewCompat.setImportantForAccessibility(this, ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_YES)

        capsule.apply {
            background = if (glassEnabled) glassSurface(selected = true)
                else rounded(ColorUtils.blendARGB(surface, primary, if (dark) .22f else .10f), 25)
            alpha = 0f
            elevation = dp(if (glassEnabled) 3 else 0).toFloat()
        }
        addView(capsule, LayoutParams(0, dockItemHeight, Gravity.CENTER_VERTICAL))

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        specs.forEach { spec ->
            val item = DockItemView(context, spec.icon, spec.title).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener {
                    if (selectedItemId == spec.id) return@setOnClickListener
                    val accepted = runCatching { listener?.invoke(Item(spec.id)) ?: true }.getOrDefault(false)
                    if (accepted) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        selectedItemId = spec.id
                    }
                }
            }
            itemViews[spec.id] = item
            row.addView(item)
        }
        post { renderSelection(false) }
    }

    fun setOnItemSelectedListener(block: (Item) -> Boolean) {
        listener = block
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        post { renderSelection(false) }
    }

    private fun renderSelection(animated: Boolean) {
        val index = specs.indexOfFirst { it.id == selectedItemId }.coerceAtLeast(0)
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        if (contentWidth == 0) return
        val itemWidth = contentWidth / specs.size
        val capsuleWidth = (itemWidth - dp(8)).coerceAtLeast(dp(48))
        capsule.layoutParams = (capsule.layoutParams as LayoutParams).apply {
            width = capsuleWidth
            height = dockItemHeight
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            leftMargin = paddingLeft + dp(4)
        }
        val target = (index * itemWidth).toFloat()
        capsule.animate().cancel()
        if (animated && capsule.alpha > 0f) {
            capsule.animate().translationX(target).setDuration(340L)
                .setInterpolator(OvershootInterpolator(.65f)).start()
        } else {
            capsule.translationX = target
            capsule.alpha = 1f
        }
        itemViews.forEach { (id, view) -> view.setActive(id == selectedItemId, animated) }
    }

    private inner class DockItemView(context: Context, iconRes: Int, title: String) : LinearLayout(context) {
        private val icon = AppCompatImageView(context)
        private val label = TextView(context)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(5), dp(4), dp(4))
            isClickable = true
            isFocusable = true
            contentDescription = title
            minimumHeight = dockItemHeight
            background = null

            icon.layoutParams = LinearLayout.LayoutParams(dp(23), dp(23))
            icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            icon.setImageDrawable(AppCompatResources.getDrawable(context, iconRes))
            addView(icon)

            label.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3)
            }
            label.text = title
            label.gravity = Gravity.CENTER
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            label.includeFontPadding = false
            addView(label)
        }

        fun setActive(active: Boolean, animated: Boolean) {
            isSelected = active
            val color = if (active) primary else ColorUtils.setAlphaComponent(onSurface, 200)
            icon.imageTintList = ColorStateList.valueOf(color)
            label.setTextColor(color)
            label.setTypeface(label.typeface, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            animate().cancel()
            if (animated) {
                animate()
                    .scaleX(if (active) 1f else 0.97f)
                    .scaleY(if (active) 1f else 0.97f)
                    .alpha(if (active) 1f else 0.78f)
                    .setDuration(220L)
                    .setInterpolator(easing)
                    .start()
            } else {
                scaleX = if (active) 1f else 0.97f
                scaleY = if (active) 1f else 0.97f
                alpha = if (active) 1f else 0.78f
            }
        }
    }

    // This legacy View has no Compose backdrop source. Keep its glossy fallback legible;
    // the live Compose shell supplies actual background sampling when blur is enabled.
    private fun glassSurface(selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        orientation = GradientDrawable.Orientation.TL_BR
        val base = ColorUtils.blendARGB(surface, primary, if (selected) .13f else .015f)
        colors = intArrayOf(
            ColorUtils.blendARGB(base, android.graphics.Color.WHITE, if (dark) .045f else .20f),
            ColorUtils.setAlphaComponent(base, if (selected) 242 else 246),
            ColorUtils.blendARGB(base, primary, if (selected) .035f else .018f)
        )
        cornerRadius = dp(if (selected) 25 else if (floatingEnabled) 31 else 28).toFloat()
        setStroke(maxOf(1, dp(1)), ColorUtils.setAlphaComponent(android.graphics.Color.WHITE,
            if (dark) 32 else if (selected) 140 else 174))
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 0) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null && strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
