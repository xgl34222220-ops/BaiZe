package io.github.xgl34222220.baize

import android.app.Activity
import android.app.Application
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton

object Alpha7UiPolish {
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
        activity.findViewById<TextView?>(R.id.versionText)?.text = "Alpha 7"
        replaceText(activity.window.decorView, "专项工具", "更多清理")

        activity.findViewById<MaterialButton?>(R.id.advancedAuditButton)?.apply {
            text = "清理明细\n查看缓存、空项目、规则垃圾与碎片"
            textSize = 13f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setLineSpacing(dp(activity, 2).toFloat(), 1f)
            maxLines = 2
        }
        activity.findViewById<MaterialButton?>(R.id.corpsesToolButton)?.apply {
            text = "卸载残留\n扫描后可一键清理 data / obb 残留"
            textSize = 13f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setLineSpacing(dp(activity, 2).toFloat(), 1f)
            maxLines = 2
        }
        activity.findViewById<MaterialButton?>(R.id.deepToolButton)?.apply {
            text = "深度清理\n4,746 条规则扫描，安全项一键清理"
            textSize = 13f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setLineSpacing(dp(activity, 2).toFloat(), 1f)
            maxLines = 2
        }

        activity.findViewById<TextView?>(R.id.recentTaskText)?.visibility = View.GONE
        activity.findViewById<TextView?>(R.id.taskStatusText)?.apply {
            textSize = 14f
            setLineSpacing(dp(activity, 3).toFloat(), 1f)
        }

        activity.findViewById<BottomNavigationView?>(R.id.bottomNavigation)?.apply {
            setBackgroundResource(R.drawable.bg_bottom_nav)
            elevation = dp(activity, 18).toFloat()
            setItemTextAppearanceActive(R.style.TextAppearance_BaiZe_Nav_Active)
            setItemTextAppearanceInactive(R.style.TextAppearance_BaiZe_Nav_Inactive)
            setItemIconSize(dp(activity, 23))
            itemIconTintList = ContextCompat.getColorStateList(activity, R.color.nav_item_color)
            itemTextColor = ContextCompat.getColorStateList(activity, R.color.nav_item_color)
            itemRippleColor = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.baize_nav_ripple))
            isItemActiveIndicatorEnabled = true
            itemActiveIndicatorColor = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.baize_nav_indicator))
            itemActiveIndicatorWidth = dp(activity, 82)
            itemActiveIndicatorHeight = dp(activity, 48)
            itemActiveIndicatorMarginHorizontal = dp(activity, 3)
            translationY = dp(activity, 10).toFloat()
            animate()
                .translationY(0f)
                .setDuration(420L)
                .setInterpolator(OvershootInterpolator(0.72f))
                .start()
        }
    }

    private fun polishCleanCenter(activity: Activity) {
        replaceText(activity.window.decorView, "清理中心", "清理明细")
        replaceText(activity.window.decorView, "完整功能，不再只有应用缓存", "查看各分类路径；扫描后均可一键清理安全项")
        replaceText(activity.window.decorView, "开始智能扫描", "扫描全部安全项")
        replaceText(activity.window.decorView, "每类均提供扫描快照、分页详情、明确勾选和清理前二次校验", "每类扫描后自动选择安全项，直接一键清理；列表只用于查看")
    }

    private fun polishProfile(activity: Activity) {
        activity.findViewById<TextView?>(R.id.safetyText)?.apply {
            setTextColor(ContextCompat.getColor(activity, R.color.baize_text_secondary))
            textSize = 11f
            setLineSpacing(dp(activity, 2).toFloat(), 1f)
        }
        activity.findViewById<MaterialButton?>(R.id.cleanButton)?.apply {
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

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
