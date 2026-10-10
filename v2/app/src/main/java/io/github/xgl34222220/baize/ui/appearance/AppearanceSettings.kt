package io.github.xgl34222220.baize.ui.appearance

import androidx.compose.runtime.Immutable

enum class UiStyle(val label: String) {
    MATERIAL("经典"),
    MIUIX("灵动");

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
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: HIGH
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

/** 3.0.0 起的默认主题色：与新图标底色一致的白泽青 #006B72。 */
const val DEFAULT_ACCENT_ARGB: Int = 0xFF006B72.toInt()

/**
 * 默认使用白泽青 #006B72（与 3.0.0 图标底色一致）；动态取色不可用时回退到该颜色。
 * 已保存过主题色的用户不受影响（含选过澎湃橙的用户）；澎湃橙、白泽蓝与其余低饱和色
 * 保留为手动主题选项。列表首项即默认项。
 */
val AccentOptions = listOf(
    AccentOption("teal", "白泽青", DEFAULT_ACCENT_ARGB),
    AccentOption("hyper", "澎湃橙", 0xFFF25C26.toInt()),
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
    /** 与未配置时的仓库默认值一致，用户可另行启用壁纸取色。 */
    val monetEnabled: Boolean = false,
    val amoledBlack: Boolean = false,
    val blurEnabled: Boolean = true,
    val glassEnabled: Boolean = true,
    val floatingDock: Boolean = true,
    val refreshRateMode: RefreshRateMode = RefreshRateMode.HIGH,
    val adaptiveSmoothMode: Boolean = true
) {
    val accent: AccentOption
        get() = accentOptionFor(seedArgb)
}
