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
data class AppearanceSettings(
    val uiStyle: UiStyle = UiStyle.MIUIX,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val seedArgb: Int = 0xFF3975F4.toInt(),
    val kolorStyle: KolorStyle = KolorStyle.VIBRANT,
    val amoledBlack: Boolean = false,
    val blurEnabled: Boolean = true,
    val glassEnabled: Boolean = true
)
