package io.github.xgl34222220.baize

import android.app.Activity
import android.app.Application
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import io.github.xgl34222220.baize.ui.LiquidBackdropDrawable
import io.github.xgl34222220.baize.ui.LiquidGlassDrawable

/** Stable runtime visual pass for the Alpha 10 MIUI X redesign. */
object Alpha7UiPolish {
    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                activity.window.decorView.post { runCatching { polish(activity) } }
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
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        content?.getChildAt(0)?.background = LiquidBackdropDrawable(activity)

        if (activity is DashboardActivity) markDashboardSurfaces(activity)
        applyGlassTree(activity.window.decorView, activity)

        when (activity) {
            is DashboardActivity -> polishDashboard(activity)
            is CleanCenterActivity -> polishCleanCenter(activity)
            is CacheActivity, is ProfileActivity, is SmartScanActivity -> polishDetail(activity)
        }
    }

    private fun markDashboardSurfaces(activity: Activity) {
        markCard(activity, R.id.freeSpaceText, "glass:hero")
        markCard(activity, R.id.serviceStatusText, "glass:strip")
        markCard(activity, R.id.schedulerStatusText, "glass:strip")
        markCard(activity, R.id.scheduleSwitch, "glass:hero")
        markCard(activity, R.id.taskStatusText, "glass:card")
        markCard(activity, R.id.recordSummaryText, "glass:card")
        markCard(activity, R.id.settingsStatusText, "glass:card")
    }

    private fun markCard(activity: Activity, childId: Int, tag: String) {
        var node: View? = activity.findViewById(childId)
        while (node != null && node !is MaterialCardView) node = node.parent as? View
        (node as? MaterialCardView)?.tag = tag
    }

    private fun applyGlassTree(view: View, activity: Activity) {
        // Keep the settings subtree on stock Material rendering for this hotfix. A few OEM GPU and
        // Material combinations crash while revealing hidden sliders inside custom clipped drawables.
        if (view.id == R.id.settingsPage) return
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
            view.elevation = dp(activity, if (variant == LiquidGlassDrawable.Variant.HERO) 10 else 4).toFloat()
            view.translationZ = dp(activity, 1).toFloat()
            view.clipToOutline = true
        }
        if (view is MaterialButton) {
            view.stateListAnimator = null
            view.elevation = if (
                view.id == R.id.cleanNowButton ||
                view.id == R.id.cleanAllButton ||
                view.id == R.id.savePlanButton
            ) dp(activity, 6).toFloat() else 0f
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyGlassTree(view.getChildAt(index), activity)
        }
    }

    private fun polishDashboard(activity: Activity) {
        val primary = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorPrimary, Color.rgb(90, 168, 255))
        val secondary = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorSecondary, Color.rgb(81, 214, 198))
        val tertiary = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorTertiary, Color.rgb(155, 140, 255))

        activity.findViewById<TextView>(R.id.versionText)?.apply {
            text = "Alpha 13"
            setTextColor(primary)
        }
        activity.findViewById<CircularProgressIndicator>(R.id.storageRing)?.setIndicatorColor(primary)
        activity.findViewById<MaterialButton>(R.id.cleanNowButton)?.apply {
            background = LiquidGlassDrawable(activity, LiquidGlassDrawable.Variant.BUTTON)
            backgroundTintList = null
            setTextColor(Color.WHITE)
        }
                activity.findViewById<MaterialButton>(R.id.savePlanButton)?.apply {
            background = LiquidGlassDrawable(activity, LiquidGlassDrawable.Variant.BUTTON)
            backgroundTintList = null
            setTextColor(Color.WHITE)
        }

        styleTool(
            activity,
            R.id.advancedAuditButton,
            R.drawable.ic_clean_detail,
            primary,
            "清理明细\n查看缓存、空项目、规则垃圾与碎片"
        )
        styleTool(
            activity,
            R.id.corpsesToolButton,
            R.drawable.ic_uninstall_residue,
            secondary,
            "卸载残留\n扫描后可一键清理 data / obb 残留"
        )
        styleTool(
            activity,
            R.id.deepToolButton,
            R.drawable.ic_deep_clean,
            tertiary,
            "深度清理\n4,746 条规则扫描，安全项一键清理"
        )
        activity.findViewById<TextView>(R.id.recentTaskText)?.visibility = View.GONE
        activity.findViewById<TextView>(R.id.taskStatusText)?.setLineSpacing(dp(activity, 3).toFloat(), 1f)
    }

    private fun styleTool(activity: Activity, id: Int, iconRes: Int, color: Int, label: String) {
        activity.findViewById<MaterialButton>(id)?.apply {
            text = label
            textSize = 13f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setLineSpacing(dp(activity, 3).toFloat(), 1f)
            maxLines = 2
            icon = AppCompatResources.getDrawable(activity, iconRes)
            iconTint = ColorStateList.valueOf(color)
            iconSize = dp(activity, 27)
            iconPadding = dp(activity, 17)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            insetTop = 0
            insetBottom = 0
        }
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
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) replaceText(view.getChildAt(index), from, to)
        }
    }

    private fun alpha(color: Int, value: Int): Int = Color.argb(
        value.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
