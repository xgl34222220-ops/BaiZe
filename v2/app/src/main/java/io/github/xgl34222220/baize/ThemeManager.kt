package io.github.xgl34222220.baize

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors

/** Central theme controller for the Alpha 15 MIUIx visual system. */
object ThemeManager {
    const val PREFS = "baize_v2"
    const val KEY_PALETTE = "theme_palette"
    const val KEY_MODE = "theme_mode"
    const val KEY_MONET = "theme_monet"
    const val KEY_AMOLED = "theme_amoled"
    const val KEY_GLASS = "theme_glass"

    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    data class Palette(
        val id: String,
        val label: String,
        val description: String,
        @StyleRes val themeRes: Int
    )

    val palettes: List<Palette> = listOf(
        Palette("blue", "澄澈蓝", "清爽蓝与淡紫灰背景", R.style.Theme_BaiZe_Blue),
        Palette("aurora", "雾紫", "低饱和紫与冷灰", R.style.Theme_BaiZe_Aurora),
        Palette("jade", "青岚", "柔和青绿与浅灰", R.style.Theme_BaiZe_Jade),
        Palette("sunset", "暖砂", "温和米棕与暖灰", R.style.Theme_BaiZe_Sunset)
    )

    fun install(application: Application) {
        migrateLegacySettings(application)
        syncNightMode(application)
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyBeforeCreate(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) applyBeforeCreate(activity)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun currentId(context: Context): String {
        val stored = prefs(context).getString(KEY_PALETTE, "blue").orEmpty()
        return palettes.firstOrNull { it.id == stored }?.id ?: "blue"
    }

    fun currentPalette(context: Context): Palette =
        palettes.firstOrNull { it.id == currentId(context) } ?: palettes.first()

    fun currentMode(context: Context): String = when (prefs(context).getString(KEY_MODE, MODE_SYSTEM)) {
        MODE_LIGHT -> MODE_LIGHT
        MODE_DARK -> MODE_DARK
        else -> MODE_SYSTEM
    }

    fun isMonetEnabled(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            prefs(context).getBoolean(KEY_MONET, Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

    fun isAmoledEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AMOLED, false)

    fun isGlassEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_GLASS, true)

    fun setPalette(context: Context, id: String) {
        val normalized = palettes.firstOrNull { it.id == id }?.id ?: "blue"
        prefs(context).edit().putString(KEY_PALETTE, normalized).apply()
    }

    fun setMode(context: Context, mode: String) {
        val normalized = when (mode) {
            MODE_LIGHT, MODE_DARK -> mode
            else -> MODE_SYSTEM
        }
        prefs(context).edit().putString(KEY_MODE, normalized).apply()
        syncNightMode(context)
    }

    fun setMonet(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MONET, enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S).apply()
    }

    fun setAmoled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AMOLED, enabled).apply()
    }

    fun setGlass(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GLASS, enabled).apply()
    }

    fun modeLabel(context: Context): String = when (currentMode(context)) {
        MODE_LIGHT -> "浅色"
        MODE_DARK -> "深色"
        else -> "跟随系统"
    }

    fun themeSummary(context: Context): String {
        val palette = currentPalette(context)
        return buildString {
            append(modeLabel(context)).append(" · ")
            if (isMonetEnabled(context)) {
                append("Monet 壁纸取色")
            } else {
                append(palette.label)
                if (isAmoledEnabled(context) && isDark(context)) append(" · 纯黑")
            }
            append(if (isGlassEnabled(context)) " · 液态玻璃底栏" else " · 简约底栏")
        }
    }

    fun isDark(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun syncNightMode(context: Context) {
        val mode = when (currentMode(context)) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    private fun applyBeforeCreate(activity: Activity) {
        val monet = isMonetEnabled(activity)
        val themeRes = when {
            !monet && isAmoledEnabled(activity) && isDark(activity) -> R.style.Theme_BaiZe_Amoled
            else -> currentPalette(activity).themeRes
        }
        activity.setTheme(themeRes)
        if (monet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DynamicColors.applyToActivityIfAvailable(activity)
        }
    }

    private fun migrateLegacySettings(context: Context) {
        val preferences = prefs(context)
        val oldPalette = preferences.getString(KEY_PALETTE, "blue").orEmpty()
        if (oldPalette == "monet") {
            preferences.edit()
                .putString(KEY_PALETTE, "blue")
                .putBoolean(KEY_MONET, Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                .apply()
        } else if (oldPalette == "amoled") {
            preferences.edit()
                .putString(KEY_PALETTE, "blue")
                .putBoolean(KEY_AMOLED, true)
                .apply()
        }
    }
}
