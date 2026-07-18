package io.github.xgl34222220.baize

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.annotation.StyleRes

/**
 * Runtime palette controller for Alpha 8.
 *
 * The activity theme is selected before Activity.onCreate on modern Android so every Material
 * component, dialog, slider, switch, card and navigation indicator resolves the same palette.
 * Monet uses Material 3 dynamic system colors on Android 12+ and falls back to BaiZe Blue on older
 * systems. Fixed palettes never depend on the wallpaper and remain stable across ROMs.
 */
object ThemeManager {
    const val PREFS = "baize_v2"
    const val KEY_PALETTE = "theme_palette"

    data class Palette(
        val id: String,
        val label: String,
        val description: String,
        @StyleRes val themeRes: Int,
        val monet: Boolean = false
    )

    val palettes: List<Palette> = listOf(
        Palette("blue", "澄澈蓝", "跟随系统明暗的克制蓝灰", R.style.Theme_BaiZe_Blue),
        Palette("aurora", "暮光紫", "低饱和紫与雾蓝", R.style.Theme_BaiZe_Aurora),
        Palette("jade", "青岚", "青绿与冷灰的清爽组合", R.style.Theme_BaiZe_Jade),
        Palette("sunset", "暖砂", "柔和橙棕与暖灰", R.style.Theme_BaiZe_Sunset),
        Palette("amoled", "极夜黑", "纯黑背景与低亮度高对比", R.style.Theme_BaiZe_Amoled),
        Palette("monet", "系统取色", "Android 12+ 壁纸动态配色", R.style.Theme_BaiZe_Monet, monet = true)
    )

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyBeforeCreate(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Android 9 and earlier do not dispatch onActivityPreCreated. The default BaiZe theme
                // remains usable there; theme selection is fully applied on Android 10+.
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

    fun currentId(activity: Activity): String = currentId(activity.application)

    fun currentId(application: Application): String {
        val fallback = "blue"
        val stored = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PALETTE, fallback)
            .orEmpty()
        return palettes.firstOrNull { it.id == stored }?.id ?: fallback
    }

    fun currentPalette(activity: Activity): Palette = paletteFor(currentId(activity))

    fun setPalette(activity: Activity, id: String) {
        val normalized = palettes.firstOrNull { it.id == id }?.id ?: "blue"
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PALETTE, normalized)
            .apply()
    }

    private fun paletteFor(id: String): Palette {
        val selected = palettes.firstOrNull { it.id == id } ?: palettes.first { it.id == "blue" }
        return if (selected.monet && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            palettes.first { it.id == "blue" }
        } else {
            selected
        }
    }

    private fun applyBeforeCreate(activity: Activity) {
        activity.setTheme(currentPalette(activity).themeRes)
    }
}
