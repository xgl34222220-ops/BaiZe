package io.github.xgl34222220.baize

import android.app.Activity
import android.app.Application
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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

/** Alpha 17 BOX-style pass: grouped surfaces, one accent and user-controlled visual effects. */
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
        // Alpha 19 Clean Center owns its complete WebUI-derived surface and inset system.
        if (activity is CleanCenterActivity) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        content?.getChildAt(0)?.background = if (ThemeManager.isBlurEnabled(activity)) {
            LiquidBackdropDrawable(activity)
        } else {
            ColorDrawable(MaterialColors.getColor(activity, android.R.attr.colorBackground, Color.rgb(238, 239, 249)))
        }
        when (activity) {
            is DashboardActivity -> polishDashboard(activity)
            is CleanCenterActivity -> polishCleanCenter(activity)
            is CacheActivity, is ProfileActivity, is SmartScanActivity -> polishDetail(activity)
        }
    }

    private fun polishDashboard(activity: Activity) {
        val primary = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorPrimary, Color.rgb(18, 104, 215))
        val onPrimary = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)
        val primaryContainer = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorPrimaryContainer, Color.rgb(221, 232, 255))
        val onPrimaryContainer = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnPrimaryContainer, Color.rgb(10, 53, 111))

        activity.findViewById<TextView>(R.id.versionText)?.apply {
            text = "Alpha 19"
            setTextColor(primary)
        }
        activity.findViewById<CircularProgressIndicator>(R.id.storageRing)?.apply {
            setIndicatorColor(primary)
            trackColor = withAlpha(onPrimaryContainer, 34)
        }

        findParentCard(activity.findViewById(R.id.freeSpaceText))?.apply {
            setCardBackgroundColor(primaryContainer)
            strokeWidth = 0
            cardElevation = 0f
        }

        activity.findViewById<MaterialButton>(R.id.cleanNowButton)?.apply {
            backgroundTintList = ColorStateList.valueOf(primary)
            setTextColor(onPrimary)
            stateListAnimator = null
            elevation = 0f
        }
        activity.findViewById<MaterialButton>(R.id.savePlanButton)?.apply {
            backgroundTintList = ColorStateList.valueOf(primary)
            setTextColor(onPrimary)
            stateListAnimator = null
            elevation = 0f
        }

        styleTool(activity, R.id.advancedAuditButton, R.drawable.ic_clean_detail, primary, "清理明细\n查看缓存、空项目、规则垃圾与碎片")
        styleTool(activity, R.id.corpsesToolButton, R.drawable.ic_uninstall_residue, primary, "卸载残留\n扫描 data、obb 与 media 无主目录")
        styleTool(activity, R.id.deepToolButton, R.drawable.ic_deep_clean, primary, "深度清理\n完整规则扫描，按风险分级处理")

        activity.findViewById<TextView>(R.id.recentTaskText)?.visibility = View.GONE
        activity.findViewById<TextView>(R.id.taskStatusText)?.setLineSpacing(dp(activity, 3).toFloat(), 1f)
    }

    private fun findParentCard(start: View?): MaterialCardView? {
        var node = start
        while (node != null && node !is MaterialCardView) node = node.parent as? View
        return node as? MaterialCardView
    }

    private fun styleTool(activity: Activity, id: Int, iconRes: Int, color: Int, label: String) {
        activity.findViewById<MaterialButton>(id)?.apply {
            text = label
            textSize = 14f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setLineSpacing(dp(activity, 3).toFloat(), 1f)
            maxLines = 2
            icon = AppCompatResources.getDrawable(activity, iconRes)
            iconTint = ColorStateList.valueOf(color)
            iconSize = dp(activity, 26)
            iconPadding = dp(activity, 18)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            insetTop = 0
            insetBottom = 0
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            stateListAnimator = null
            elevation = 0f
        }
    }

    private fun polishCleanCenter(activity: Activity) {
        replaceText(activity.window.decorView, "清理中心", "清理明细")
        replaceText(activity.window.decorView, "完整功能，不再只有应用缓存", "按分类查看候选项；安全项目可直接清理")
    }

    private fun polishDetail(activity: Activity) {
        activity.findViewById<TextView>(R.id.safetyText)?.apply {
            textSize = 12f
            setLineSpacing(dp(activity, 2).toFloat(), 1f)
        }
    }

    private fun replaceText(view: View, from: String, to: String) {
        if (view is TextView && view.text.toString() == from) view.text = to
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) replaceText(view.getChildAt(index), from, to)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color)
    )

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
