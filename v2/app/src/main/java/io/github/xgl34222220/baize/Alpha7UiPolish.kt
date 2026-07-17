package io.github.xgl34222220.baize

import android.app.Activity
import android.app.Application
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator

object Alpha7UiPolish {
    private const val THEME_CARD_TAG = "baize-alpha8-theme-card"

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
        when (activity) {
            is DashboardActivity -> polishDashboard(activity)
            is CleanCenterActivity -> polishCleanCenter(activity)
            is CacheActivity, is ProfileActivity -> polishProfile(activity)
        }
    }

    private fun polishDashboard(activity: Activity) {
        val primary = resolveColor(activity, androidx.appcompat.R.attr.colorPrimary)
        val onSurfaceVariant = resolveColor(activity, com.google.android.material.R.attr.colorOnSurfaceVariant)

        activity.findViewById<TextView>(R.id.versionText)?.apply {
            text = "Alpha 8"
            setTextColor(primary)
            ViewCompat.setBackgroundTintList(this, ColorStateList.valueOf(withAlpha(primary, 44)))
        }
        replaceText(activity.window.decorView, "专项工具", "更多清理")

        activity.findViewById<CircularProgressIndicator>(R.id.storageRing)?.setIndicatorColor(primary)
        activity.findViewById<MaterialButton>(R.id.cleanNowButton)?.backgroundTintList = ColorStateList.valueOf(primary)
        activity.findViewById<MaterialButton>(R.id.scanOnlyButton)?.apply {
            setTextColor(primary)
            strokeColor = ColorStateList.valueOf(withAlpha(primary, 132))
        }

        activity.findViewById<MaterialButton>(R.id.advancedAuditButton)?.apply {
            text = "清理明细\n查看缓存、空项目、规则垃圾与碎片"
            textSize = 13f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setLineSpacing(dp(activity, 2).toFloat(), 1f)
            maxLines = 2
        }
        activity.findViewById<MaterialButton>(R.id.corpsesToolButton)?.apply {
            text = "卸载残留\n扫描后可一键清理 data / obb 残留"
            textSize = 13f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setLineSpacing(dp(activity, 2).toFloat(), 1f)
            maxLines = 2
        }
        activity.findViewById<MaterialButton>(R.id.deepToolButton)?.apply {
            text = "深度清理\n4,746 条规则扫描，安全项一键清理"
            textSize = 13f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setLineSpacing(dp(activity, 2).toFloat(), 1f)
            maxLines = 2
        }

        activity.findViewById<TextView>(R.id.recentTaskText)?.visibility = View.GONE
        activity.findViewById<TextView>(R.id.taskStatusText)?.apply {
            textSize = 14f
            setLineSpacing(dp(activity, 3).toFloat(), 1f)
        }

        activity.findViewById<BottomNavigationView>(R.id.bottomNavigation)?.apply {
            setBackgroundResource(R.drawable.bg_bottom_nav)
            elevation = dp(activity, 24).toFloat()
            setItemTextAppearanceActive(R.style.TextAppearance_BaiZe_Nav_Active)
            setItemTextAppearanceInactive(R.style.TextAppearance_BaiZe_Nav_Inactive)
            setItemIconSize(dp(activity, 24))
            val itemColors = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(primary, onSurfaceVariant)
            )
            itemIconTintList = itemColors
            itemTextColor = itemColors
            itemRippleColor = ColorStateList.valueOf(withAlpha(primary, 36))
            setItemActiveIndicatorEnabled(true)
            setItemActiveIndicatorColor(ColorStateList.valueOf(withAlpha(primary, 66)))
            setItemActiveIndicatorWidth(dp(activity, 86))
            setItemActiveIndicatorHeight(dp(activity, 50))
            setItemActiveIndicatorMarginHorizontal(dp(activity, 3))
            (layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.height = dp(activity, 72)
                params.leftMargin = dp(activity, 20)
                params.rightMargin = dp(activity, 20)
                params.bottomMargin = dp(activity, 18)
                layoutParams = params
            }
            translationY = dp(activity, 10).toFloat()
            animate()
                .translationY(0f)
                .setDuration(420L)
                .setInterpolator(OvershootInterpolator(0.72f))
                .start()
        }

        installThemeSelector(activity)
    }

    private fun installThemeSelector(activity: Activity) {
        val scroll = activity.findViewById<ViewGroup>(R.id.settingsPage) ?: return
        val container = scroll.getChildAt(0) as? LinearLayout ?: return
        if (container.findViewWithTag<View>(THEME_CARD_TAG) != null) return

        val primary = resolveColor(activity, androidx.appcompat.R.attr.colorPrimary)
        val surface = resolveColor(activity, com.google.android.material.R.attr.colorSurfaceVariant)
        val outline = resolveColor(activity, com.google.android.material.R.attr.colorOutline)
        val onSurface = resolveColor(activity, com.google.android.material.R.attr.colorOnSurface)
        val secondary = resolveColor(activity, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val selected = ThemeManager.currentPalette(activity)

        val card = MaterialCardView(activity).apply {
            tag = THEME_CARD_TAG
            radius = dp(activity, 24).toFloat()
            strokeWidth = dp(activity, 1)
            strokeColor = withAlpha(outline, 128)
            setCardBackgroundColor(surface)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(activity, 14)
                bottomMargin = dp(activity, 2)
            }
            setContentPadding(dp(activity, 16), dp(activity, 15), dp(activity, 14), dp(activity, 15))
        }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val textColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(activity).apply {
            text = "主题与取色"
            textSize = 15f
            setTextColor(onSurface)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        textColumn.addView(TextView(activity).apply {
            text = "${selected.label} · ${selected.description}"
            textSize = 11f
            setTextColor(secondary)
            setPadding(0, dp(activity, 4), 0, 0)
        })
        row.addView(textColumn)
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
            if (palette.monet) "${palette.label}\n${palette.description}（Android 12+）"
            else "${palette.label}\n${palette.description}"
        }.toTypedArray()
        val checked = ThemeManager.palettes.indexOfFirst { it.id == current }.coerceAtLeast(0)

        AlertDialog.Builder(activity)
            .setTitle("主题与取色")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val palette = ThemeManager.palettes[which]
                ThemeManager.setPalette(activity, palette.id)
                dialog.dismiss()
                activity.recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun polishCleanCenter(activity: Activity) {
        replaceText(activity.window.decorView, "清理中心", "清理明细")
        replaceText(activity.window.decorView, "完整功能，不再只有应用缓存", "查看各分类路径；扫描后均可一键清理安全项")
        replaceText(activity.window.decorView, "开始智能扫描", "扫描全部安全项")
        replaceText(activity.window.decorView, "每类均提供扫描快照、分页详情、明确勾选和清理前二次校验", "每类扫描后自动选择安全项，直接一键清理；列表只用于查看")
    }

    private fun polishProfile(activity: Activity) {
        activity.findViewById<TextView>(R.id.safetyText)?.apply {
            setTextColor(resolveColor(activity, com.google.android.material.R.attr.colorOnSurfaceVariant))
            textSize = 11f
            setLineSpacing(dp(activity, 2).toFloat(), 1f)
        }
        activity.findViewById<MaterialButton>(R.id.cleanButton)?.apply {
            textSize = 14f
            minHeight = dp(activity, 54)
        }
    }

    private fun replaceText(view: View, from: String, to: String) {
        if (view is TextView && view.text.toString() == from) view.text = to
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) replaceText(view.getChildAt(index), from, to)
        }
    }

    private fun resolveColor(activity: Activity, attr: Int): Int {
        val value = TypedValue()
        if (!activity.theme.resolveAttribute(attr, value, true)) return Color.WHITE
        return if (value.resourceId != 0) ContextCompat.getColor(activity, value.resourceId) else value.data
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
