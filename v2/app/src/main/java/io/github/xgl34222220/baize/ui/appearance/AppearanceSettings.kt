package io.github.xgl34222220.baize.ui.appearance

import androidx.compose.runtime.Immutable

enum class UiStyle(val label: String) {
    MATERIAL("Material"),
    MIUIX("Miuix");

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

val AccentOptions = listOf(
    AccentOption("default", "白泽蓝", 0xFF3975F4.toInt()),
    AccentOption("red", "朱红", 0xFFC70018.toInt()),
    AccentOption("pink", "玫粉", 0xFFC50056.toInt()),
    AccentOption("purple", "曜紫", 0xFFAF00C7.toInt()),
    AccentOption("deep_purple", "深紫", 0xFF7900F5.toInt()),
    AccentOption("indigo", "靛蓝", 0xFF1559F4.toInt()),
    AccentOption("blue", "湖蓝", 0xFF0A79B8.toInt()),
    AccentOption("light_blue", "青蓝", 0xFF0080A0.toInt())
)

fun accentOptionFor(argb: Int): AccentOption =
    AccentOptions.firstOrNull { it.argb == argb } ?: AccentOptions.first()

@Immutable
data class AppearanceSettings(
    val uiStyle: UiStyle = UiStyle.MIUIX,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val seedArgb: Int = AccentOptions.first().argb,
    val kolorStyle: KolorStyle = KolorStyle.VIBRANT,
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