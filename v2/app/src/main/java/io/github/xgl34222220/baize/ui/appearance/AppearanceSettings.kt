package io.github.xgl34222220.baize.ui.appearance

import androidx.compose.runtime.Immutable

enum class UiStyle(val label: String) {
    MATERIAL("Material 3"),
    MIUIX("MIUIx / HyperOS");

    companion object {
        fun fromStorage(value: String?): UiStyle =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: MIUIX
    }
}

enum class ThemeMode(val storageValue: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    companion object {
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) } ?: SYSTEM
    }
}

enum class RefreshRateMode(val label: String) {
    SYSTEM("跟随系统"),
    HIGH("高刷优先"),
    STANDARD("60Hz 省电");

    companion object {
        fun fromStorage(value: String?): RefreshRateMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SYSTEM
    }
}

enum class KolorStyle(val label: String) {
    SOFT("柔和"),
    VIBRANT("鲜艳"),
    NEUTRAL("中性");

    companion object {
        fun fromStorage(value: String?): KolorStyle = when (value?.lowercase()) {
            "neutral", "monochrome" -> NEUTRAL
            "vibrant", "expressive", "rainbow", "fruit_salad", "fidelity", "content" -> VIBRANT
            else -> SOFT
        }
    }
}

@Immutable
data class AccentOption(
    val id: String,
    val label: String,
    val argb: Int
)

/**
 * 默认使用规范主色 #245FD3；动态取色不可用或对比不足时回退到该颜色。
 * 其余低饱和色保留为手动主题选项。
 */
val AccentOptions = listOf(
    AccentOption("default", "白泽蓝", 0xFF245FD3.toInt()),
    AccentOption("mist", "雾蓝", 0xFF7C93AB.toInt()),
    AccentOption("sage", "鼠尾草绿", 0xFF8FA98F.toInt()),
    AccentOption("sand", "暖沙", 0xFFC2AE8B.toInt()),
    AccentOption("terracotta", "陶土", 0xFFBE8A72.toInt()),
    AccentOption("mauve", "雾霾紫", 0xFFA494B8.toInt()),
    AccentOption("slate", "岩青", 0xFF7FA3A8.toInt()),
    AccentOption("amber", "霞光橘", 0xFFD98E4A.toInt())
)

fun accentOptionFor(argb: Int): AccentOption =
    AccentOptions.firstOrNull { it.argb == argb } ?: AccentOptions.first()

@Immutable
data class AppearanceSettings(
    val uiStyle: UiStyle = UiStyle.MIUIX,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val seedArgb: Int = AccentOptions.first().argb,
    val kolorStyle: KolorStyle = KolorStyle.SOFT,
    /** Android 12+ 默认启用 Monet；旧系统自动使用白泽蓝。 */
    val monetEnabled: Boolean = true,
    val amoledBlack: Boolean = false,
    val blurEnabled: Boolean = true,
    val glassEnabled: Boolean = true,
    val floatingDock: Boolean = true,
    val refreshRateMode: RefreshRateMode = RefreshRateMode.SYSTEM,
    val adaptiveSmoothMode: Boolean = true
) {
    val accent: AccentOption
        get() = accentOptionFor(seedArgb)
}
