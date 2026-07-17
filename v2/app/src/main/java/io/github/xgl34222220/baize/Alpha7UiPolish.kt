package io.github.xgl34222220.baize

import android.app.Activity
import android.app.Application
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import io.github.xgl34222220.baize.ui.LiquidGlassDrawable

/** Runtime visual pass for the Alpha 9 MIUI X redesign. */
object Alpha7UiPolish {
    private const val THEME_CARD_TAG = "baize-alpha9-theme-card"

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                activity.window.decorView.post { polish(activity) }
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun polish(activity: Activity) {
        applyGlassTree(activity.window.decorView, activity)
        when (activity) {
            is DashboardActivity -> polishDashboard(activity)
            is CleanCenterActivity -> polishCleanCenter(activity)
            is CacheActivity, is ProfileActivity, is SmartScanActivity -> polishDetail(activity)
        }
    }

    private fun applyGlassTree(view: View, activity: Activity) {
        if (view is MaterialCardView) {
            val variant = when (view.tag?.toString()) {
                "glass:hero" -> LiquidGlassDrawable.Variant.HERO
                "glass:strip" -> LiquidGlassDrawable.Variant.STRIP
                "glass:active" -> LiquidGlassDrawable.Variant.ACTIVE
                else -> LiquidGlassDrawable.Variant.CARD
            }
            view.setCardBackgroundColor(Color.TRANSPARENT)
            view.strokeWidth = 0
            view.cardElevation = 0f
            view.background = LiquidGlassDrawable(activity, variant)
            view.elevation = dp(activity, if (variant == LiquidGlassDrawable.Variant.HERO) 18 else 10).toFloat()
            view.translationZ = dp(activity, 1).toFloat()
            view.clipToOutline = true
        }
        if (view is MaterialButton) {
            view.stateListAnimator = null
            view.elevation = if (view.id == R.id.cleanNowButton || view.id == R.id.cleanAllButton) dp(activity, 10).toFloat() else dp(activity, 2).toFloat()
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyGlassTree(view.getChildAt(index), activity)
        }
    }

    private fun polishDashboard(activity: Activity) {
        val primary = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorPrimary, Color.rgb(90, 168, 255))
        activity.findViewById<TextView>(R.id.versionText)?.apply {
            text = "Alpha 9"
            setTextColor(primary)
        }
        activity.findViewById<CircularProgressIndicator>(R.id.storageRing)?.setIndicatorColor(primary)
        activity.findViewById<MaterialButton>(R.id.cleanNowButton)?.apply {
            background = LiquidGlassDrawable(activity, LiquidGlassDrawable.Variant.BUTTON)
            backgroundTintList = null
            setTextColor(Color.WHITE)
        }
        activity.findViewById<MaterialButton>(R.id.scanOnlyButton)?.apply {
            setTextColor(primary)
            strokeColor = ColorStateList.valueOf(alpha(primary, 170))
        }
        activity.findViewById<TextView>(R.id.recentTaskText)?.visibility = View.GONE
        activity.findViewById<TextView>(R.id.taskStatusText)?.setLineSpacing(dp(activity, 3).toFloat(), 1f)
        installThemeSelector(activity)
    }

    private fun installThemeSelector(activity: Activity) {
        val scroll = activity.findViewById<ViewGroup>(R.id.settingsPage) ?: return
        val container = scroll.getChildAt(0) as? LinearLayout ?: return
        if (container.findViewWithTag<View>(THEME_CARD_TAG) != null) return

        val primary = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorPrimary, Color.rgb(90, 168, 255))
        val onSurface = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val secondary = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.LTGRAY)
        val selected = ThemeManager.currentPalette(activity)
        val card = MaterialCardView(activity).apply {
            tag = THEME_CARD_TAG
            setCardBackgroundColor(Color.TRANSPARENT)
            strokeWidth = 0
            radius = dp(activity, 26).toFloat()
            background = LiquidGlassDrawable(activity, LiquidGlassDrawable.Variant.CARD)
            isClickable = true
            isFocusable = true
            elevation = dp(activity, 10).toFloat()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 14)
                bottomMargin = dp(activity, 2)
            }
            setContentPadding(dp(activity, 17), dp(activity, 16), dp(activity, 15), dp(activity, 16))
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        column.addView(TextView(activity).apply {
            text = "主题与取色"
            textSize = 15f
            setTextColor(onSurface)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        column.addView(TextView(activity).apply {
            text = "${selected.label} · ${selected.description}"
            textSize = 11f
            setTextColor(secondary)
            setPadding(0, dp(activity, 5), 0, 0)
        })
        row.addView(column)
        row.addView(TextView(activity).apply {
            text = "切换  ›"
            textSize = 12f
            setTextColor(primary)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        card.addView(row)
        card.setOnClickListener { showThemeDialog(activity) }
        container.addView(card, 1.coerceAtMost(container.childCount))
    }

    private fun showThemeDialog(activity: Activity) {
        val current = ThemeManager.currentId(activity)
        val labels = ThemeManager.palettes.map { palette ->
            if (palette.monet) "${palette.label}\n${palette.description}（Android 12+）" else "${palette.label}\n${palette.description}"
        }.toTypedArray()
        val checked = ThemeManager.palettes.indexOfFirst { it.id == current }.coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle("主题与取色")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                ThemeManager.setPalette(activity, ThemeManager.palettes[which].id)
                dialog.dismiss()
                activity.recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun polishCleanCenter(activity: Activity) {
        replaceText(activity.window.decorView, "清理中心", "清理明细")
        replaceText(activity.window.decorView, "完整功能，不再只有应用缓存", "分类明细仅供查看；安全项可以直接一键清理")
    }

    private fun polishDetail(activity: Activity) {
        activity.findViewById<TextView>(R.id.safetyText)?.apply {
            textSize = 11f
            setLineSpacing(dp(activity, 2).toFloat(), 1f)
        }
    }

    private fun replaceText(view: View, from: String, to: String) {
        if (view is TextView && view.text.toString() == from) view.text = to
        if (view is ViewGroup) for (index in 0 until view.childCount) replaceText(view.getChildAt(index), from, to)
    }

    private fun alpha(color: Int, value: Int): Int = Color.argb(value.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    private fun dp(activity: Activity, value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
