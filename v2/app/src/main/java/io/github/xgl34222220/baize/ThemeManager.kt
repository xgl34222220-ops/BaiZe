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
import com.google.android.material.color.DynamicColorsOptions
import com.google.android.material.color.utilities.Hct

/** Central controller for the Alpha 17 BOX-style MIUIx theme system. */
object ThemeManager {
    const val PREFS = "baize_v2"
    const val KEY_PALETTE = "theme_palette"
    const val KEY_ACCENT = "theme_accent"
    const val KEY_MODE = "theme_mode"
    const val KEY_MONET = "theme_monet"
    const val KEY_MONET_STYLE = "theme_monet_style"
    const val KEY_COLOR_STANDARD = "theme_color_standard"
    const val KEY_AMOLED = "theme_amoled"
    const val KEY_BLUR = "theme_blur"
    const val KEY_FLOATING_DOCK = "theme_floating_dock"
    const val KEY_GLASS = "theme_glass"
    const val KEY_PREDICTIVE_BACK = "theme_predictive_back"
    const val KEY_FOLLOW_EDGE = "theme_follow_edge"
    private const val KEY_ALPHA17_MIGRATED = "theme_alpha17_migrated"

    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    data class Palette(
        val id: String,
        val label: String,
        val description: String,
        @StyleRes val themeRes: Int,
        val preview: IntArray
    )

    data class MonetStyle(
        val id: String,
        val label: String,
        val preview: IntArray
    )

    data class ColorStandard(
        val id: String,
        val label: String
    )

    val palettes: List<Palette> = listOf(
        Palette("default", "默认", "系统蓝与淡紫灰", R.style.Theme_BaiZe_Blue, intArrayOf(0xFF1268D7.toInt(), 0xFFBFC5D3.toInt(), 0xFFE9DFFF.toInt())),
        Palette("red", "红色", "明亮红与暖中性色", R.style.Theme_BaiZe_Red, intArrayOf(0xFFC70018.toInt(), 0xFFD9BFC0.toInt(), 0xFFFFD9BE.toInt())),
        Palette("pink", "粉色", "玫红与柔和粉灰", R.style.Theme_BaiZe_Pink, intArrayOf(0xFFC50056.toInt(), 0xFFD7C0C8.toInt(), 0xFFFFD8CA.toInt())),
        Palette("purple", "紫色", "鲜紫与中性灰紫", R.style.Theme_BaiZe_Purple, intArrayOf(0xFFAF00C7.toInt(), 0xFFCDBED0.toInt(), 0xFFFFD7EB.toInt())),
        Palette("deep_purple", "深紫", "深紫与冷粉灰", R.style.Theme_BaiZe_DeepPurple, intArrayOf(0xFF7900F5.toInt(), 0xFFC9C0D1.toInt(), 0xFFF5D8FF.toInt())),
        Palette("indigo", "靛蓝", "高饱和靛蓝与冷灰", R.style.Theme_BaiZe_Indigo, intArrayOf(0xFF1559F4.toInt(), 0xFFBCC4D4.toInt(), 0xFFE6DDFF.toInt())),
        Palette("blue", "蓝色", "清透蓝与淡蓝灰", R.style.Theme_BaiZe_Cobalt, intArrayOf(0xFF0A79B8.toInt(), 0xFFB9C8D1.toInt(), 0xFFDFE7FF.toInt())),
        Palette("light_blue", "浅蓝", "青蓝与清淡冷灰", R.style.Theme_BaiZe_LightBlue, intArrayOf(0xFF0080A0.toInt(), 0xFFBDD0D5.toInt(), 0xFFDDE8FF.toInt()))
    )

    val monetStyles: List<MonetStyle> = listOf(
        MonetStyle("tonal_spot", "Tonal Spot", intArrayOf(0xFF536AA0.toInt(), 0xFFBEC2CB.toInt(), 0xFFF0D9F6.toInt())),
        MonetStyle("neutral", "Neutral", intArrayOf(0xFF6A6F7B.toInt(), 0xFFC7C7C7.toInt(), 0xFFE2E8FA.toInt())),
        MonetStyle("vibrant", "Vibrant", intArrayOf(0xFF1268D7.toInt(), 0xFFBFC5D3.toInt(), 0xFFE9DFFF.toInt())),
        MonetStyle("expressive", "Expressive", intArrayOf(0xFF2C7A4B.toInt(), 0xFFBDC1CC.toInt(), 0xFFE6E2FF.toInt())),
        MonetStyle("rainbow", "Rainbow", intArrayOf(0xFF3C6BAA.toInt(), 0xFFC8C8C8.toInt(), 0xFFFFD9F6.toInt())),
        MonetStyle("fruit_salad", "Fruit Salad", intArrayOf(0xFF087E8B.toInt(), 0xFFBFC8DA.toInt(), 0xFFD9E4FF.toInt())),
        MonetStyle("monochrome", "Monochrome", intArrayOf(0xFF000000.toInt(), 0xFFB9B9B9.toInt(), 0xFF757575.toInt())),
        MonetStyle("fidelity", "Fidelity", intArrayOf(0xFF1268D7.toInt(), 0xFFBFC5D3.toInt(), 0xFFD14A00.toInt())),
        MonetStyle("content", "Content", intArrayOf(0xFF356FA8.toInt(), 0xFFC5C9D0.toInt(), 0xFFDCC7F2.toInt()))
    )

    val colorStandards: List<ColorStandard> = listOf(
        ColorStandard("m3_2021", "Material 3 2021"),
        ColorStandard("m3_2025", "Material 3 Expressive 2025")
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
        val stored = prefs(context).getString(KEY_ACCENT, null)
            ?: prefs(context).getString(KEY_PALETTE, "default")
            ?: "default"
        return normalizeAccent(stored)
    }

    fun currentPalette(context: Context): Palette =
        palettes.firstOrNull { it.id == currentId(context) } ?: palettes.first()

    fun currentMode(context: Context): String = when (prefs(context).getString(KEY_MODE, MODE_SYSTEM)) {
        MODE_LIGHT -> MODE_LIGHT
        MODE_DARK -> MODE_DARK
        else -> MODE_SYSTEM
    }

    fun currentMonetStyle(context: Context): MonetStyle {
        val id = prefs(context).getString(KEY_MONET_STYLE, "vibrant").orEmpty()
        return monetStyles.firstOrNull { it.id == id } ?: monetStyles.first { it.id == "vibrant" }
    }

    fun currentColorStandard(context: Context): ColorStandard {
        val id = prefs(context).getString(KEY_COLOR_STANDARD, "m3_2021").orEmpty()
        return colorStandards.firstOrNull { it.id == id } ?: colorStandards.first()
    }

    fun isMonetEnabled(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && prefs(context).getBoolean(KEY_MONET, false)

    fun isAmoledEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AMOLED, false)
    fun isBlurEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_BLUR, true)
    fun isFloatingDockEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_FLOATING_DOCK, true)
    fun isGlassEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_GLASS, true)
    fun isPredictiveBackEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_PREDICTIVE_BACK, true)
    fun isFollowEdgeEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_FOLLOW_EDGE, false)

    fun setPalette(context: Context, id: String) {
        val normalized = normalizeAccent(id)
        prefs(context).edit()
            .putString(KEY_ACCENT, normalized)
            .putString(KEY_PALETTE, normalized)
            .apply()
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
        prefs(context).edit()
            .putBoolean(KEY_MONET, enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            .apply()
    }

    fun setMonetStyle(context: Context, id: String) {
        val normalized = monetStyles.firstOrNull { it.id == id }?.id ?: "vibrant"
        prefs(context).edit().putString(KEY_MONET_STYLE, normalized).apply()
    }

    fun setColorStandard(context: Context, id: String) {
        val normalized = colorStandards.firstOrNull { it.id == id }?.id ?: "m3_2021"
        prefs(context).edit().putString(KEY_COLOR_STANDARD, normalized).apply()
    }

    fun setAmoled(context: Context, enabled: Boolean) = putBoolean(context, KEY_AMOLED, enabled)
    fun setBlur(context: Context, enabled: Boolean) = putBoolean(context, KEY_BLUR, enabled)
    fun setFloatingDock(context: Context, enabled: Boolean) = putBoolean(context, KEY_FLOATING_DOCK, enabled)
    fun setGlass(context: Context, enabled: Boolean) = putBoolean(context, KEY_GLASS, enabled)
    fun setPredictiveBack(context: Context, enabled: Boolean) = putBoolean(context, KEY_PREDICTIVE_BACK, enabled)
    fun setFollowEdge(context: Context, enabled: Boolean) = putBoolean(context, KEY_FOLLOW_EDGE, enabled)

    fun modeLabel(context: Context): String = when (currentMode(context)) {
        MODE_LIGHT -> "浅色"
        MODE_DARK -> "深色"
        else -> "跟随系统"
    }

    fun themeSummary(context: Context): String = buildString {
        append(modeLabel(context)).append(" · ")
        if (isMonetEnabled(context)) {
            append("Monet ").append(currentMonetStyle(context).label)
        } else {
            append(currentPalette(context).label)
            if (isAmoledEnabled(context) && isDark(context)) append(" · 纯黑")
        }
        append(if (isGlassEnabled(context)) " · 液态玻璃" else " · 实心底栏")
    }

    fun isDark(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun putBoolean(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }

    private fun normalizeAccent(value: String): String {
        palettes.firstOrNull { it.id == value }?.let { return it.id }
        return when (value) {
            "aurora" -> "purple"
            "jade" -> "light_blue"
            "sunset" -> "red"
            else -> "default"
        }
    }

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
            val options = DynamicColorsOptions.Builder()
                .setContentBasedSource(styledMonetSeed(activity))
                .build()
            DynamicColors.applyToActivityIfAvailable(activity, options)
        }
    }

    /**
     * Material 1.12 exposes content-based dynamic colors but not the newer variant API used by
     * the reference app. Derive a real wallpaper/accent seed in HCT so every visible style option
     * produces a distinct palette instead of being a decorative preference only.
     */
    private fun styledMonetSeed(context: Context): Int {
        val sourceColor = if (currentId(context) == "default" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { context.getColor(android.R.color.system_accent1_500) }
                .getOrDefault(currentPalette(context).preview.first())
        } else {
            currentPalette(context).preview.first()
        }
        val source = Hct.fromInt(sourceColor)
        var hue = source.hue
        var chroma = source.chroma

        when (currentMonetStyle(context).id) {
            "tonal_spot" -> chroma = 36.0
            "neutral" -> chroma = 8.0
            "vibrant" -> chroma = maxOf(72.0, source.chroma * 1.30)
            "expressive" -> {
                hue = (hue + 240.0) % 360.0
                chroma = maxOf(44.0, source.chroma * 0.90)
            }
            "rainbow" -> {
                hue = (hue + 60.0) % 360.0
                chroma = 56.0
            }
            "fruit_salad" -> {
                hue = (hue + 310.0) % 360.0
                chroma = 48.0
            }
            "monochrome" -> chroma = 0.0
            "fidelity" -> chroma = maxOf(24.0, source.chroma)
            "content" -> chroma = maxOf(32.0, source.chroma * 0.82)
        }

        if (currentColorStandard(context).id == "m3_2025" && currentMonetStyle(context).id != "monochrome") {
            hue = (hue + 12.0) % 360.0
            chroma = chroma * 1.15 + 6.0
        }
        return Hct.from(hue, chroma.coerceIn(0.0, 120.0), 50.0).toInt()
    }

    private fun migrateLegacySettings(context: Context) {
        val preferences = prefs(context)
        if (preferences.getBoolean(KEY_ALPHA17_MIGRATED, false)) return

        val oldPalette = preferences.getString(KEY_PALETTE, "default").orEmpty()
        val editor = preferences.edit()
        when (oldPalette) {
            "monet" -> {
                editor.putString(KEY_ACCENT, "default")
                editor.putBoolean(KEY_MONET, Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            }
            "amoled" -> {
                editor.putString(KEY_ACCENT, "default")
                editor.putBoolean(KEY_AMOLED, true)
            }
            else -> editor.putString(KEY_ACCENT, if (oldPalette == "blue") "default" else normalizeAccent(oldPalette))
        }
        if (!preferences.contains(KEY_MONET_STYLE)) editor.putString(KEY_MONET_STYLE, "vibrant")
        if (!preferences.contains(KEY_COLOR_STANDARD)) editor.putString(KEY_COLOR_STANDARD, "m3_2021")
        if (!preferences.contains(KEY_BLUR)) editor.putBoolean(KEY_BLUR, true)
        if (!preferences.contains(KEY_FLOATING_DOCK)) editor.putBoolean(KEY_FLOATING_DOCK, true)
        if (!preferences.contains(KEY_PREDICTIVE_BACK)) editor.putBoolean(KEY_PREDICTIVE_BACK, true)
        if (!preferences.contains(KEY_FOLLOW_EDGE)) editor.putBoolean(KEY_FOLLOW_EDGE, false)
        editor.putBoolean(KEY_ALPHA17_MIGRATED, true).apply()
    }
}
