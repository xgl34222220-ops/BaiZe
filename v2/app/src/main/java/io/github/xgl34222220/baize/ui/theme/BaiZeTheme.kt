package io.github.xgl34222220.baize.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.KolorStyle
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme as NativeMiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

private val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

private val MiuixShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

private val MaterialTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)

private val MiuixTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun BaiZeTheme(settings: AppearanceSettings, content: @Composable () -> Unit) {
    val dark = resolveDark(settings.themeMode)
    val amoled = dark && settings.amoledBlack
    val colors = when {
        amoled -> AmoledBaiZeColors
        dark -> DarkBaiZeColors
        else -> LightBaiZeColors
    }
    val corners = when (settings.uiStyle) {
        UiStyle.MATERIAL -> MaterialBaiZeCorners
        UiStyle.MIUIX -> MiuixBaiZeCorners
    }
    val spacing = when (settings.uiStyle) {
        UiStyle.MATERIAL -> MaterialBaiZeSpacing
        UiStyle.MIUIX -> MiuixBaiZeSpacing
    }
    val typeScale = when (settings.uiStyle) {
        UiStyle.MATERIAL -> MaterialBaiZeTypeScale
        UiStyle.MIUIX -> MiuixBaiZeTypeScale
    }

    CompositionLocalProvider(
        LocalBaiZeColors provides colors,
        LocalBaiZeCorners provides corners,
        LocalBaiZeSpacing provides spacing,
        LocalBaiZeTypeScale provides typeScale
    ) {
        when (settings.uiStyle) {
            UiStyle.MATERIAL -> BaiZeMaterialTheme(settings, dark, content)
            UiStyle.MIUIX -> BaiZeMiuixTheme(settings, dark, content)
        }
    }
}

@Composable
private fun BaiZeMaterialTheme(settings: AppearanceSettings, dark: Boolean, content: @Composable () -> Unit) {
    DynamicMaterialTheme(
        seedColor = resolveSeedColor(settings),
        useDarkTheme = dark,
        withAmoled = dark && settings.amoledBlack,
        style = settings.kolorStyle.toPaletteStyle(),
        shapes = MaterialShapes,
        typography = MaterialTypography,
        animate = true,
        content = content
    )
}

@Composable
private fun BaiZeMiuixTheme(settings: AppearanceSettings, dark: Boolean, content: @Composable () -> Unit) {
    val seed = resolveSeedColor(settings)
    val mode = if (dark) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight
    val palette = settings.kolorStyle.toMiuixPaletteStyle()
    val controller = remember(mode, seed, palette) {
        ThemeController(
            colorSchemeMode = mode,
            keyColor = seed,
            colorSpec = ThemeColorSpec.Spec2021,
            paletteStyle = palette,
            isDark = dark
        )
    }

    // Keep MaterialTheme available for legacy business dialogs while the MIUIX
    // page tree is driven by the real MiuixTheme and its component tokens.
    DynamicMaterialTheme(
        seedColor = seed,
        useDarkTheme = dark,
        withAmoled = dark && settings.amoledBlack,
        style = settings.kolorStyle.toPaletteStyle(),
        shapes = MiuixShapes,
        typography = MiuixTypography,
        animate = true
    ) {
        NativeMiuixTheme(controller = controller, content = content)
    }
}

@Composable
private fun resolveSeedColor(settings: AppearanceSettings): Color {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(settings.monetEnabled, settings.seedArgb, configuration) {
        if (settings.monetEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Color(context.getColor(android.R.color.system_accent1_500))
        } else {
            Color(settings.seedArgb)
        }
    }
}

@Composable
private fun resolveDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

private fun KolorStyle.toPaletteStyle(): PaletteStyle = when (this) {
    KolorStyle.SOFT -> PaletteStyle.TonalSpot
    KolorStyle.VIBRANT -> PaletteStyle.Vibrant
    KolorStyle.NEUTRAL -> PaletteStyle.Neutral
}

private fun KolorStyle.toMiuixPaletteStyle(): ThemePaletteStyle = when (this) {
    KolorStyle.SOFT -> ThemePaletteStyle.TonalSpot
    KolorStyle.VIBRANT -> ThemePaletteStyle.Vibrant
    KolorStyle.NEUTRAL -> ThemePaletteStyle.Neutral
}
