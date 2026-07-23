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

// Material 3 Shapes 与 BaiZeCorners 四档对齐（12/20/28/36）。
private val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

private val MiuixShapes = MaterialShapes

// 两套皮肤共用同一字阶；中文标题 Bold（不再使用 Black），大数字经 BaiZeTokens.type.hero 提供 tnum。
private val MaterialTypography = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold)
)

private val MiuixTypography = MaterialTypography

@Composable
fun BaiZeTheme(settings: AppearanceSettings, content: @Composable () -> Unit) {
    val dark = resolveDark(settings.themeMode)
    val amoled = dark && settings.amoledBlack
    val baiZeColors = when {
        amoled -> AmoledBaiZeColors
        dark -> DarkBaiZeColors
        else -> LightBaiZeColors
    }
    CompositionLocalProvider(
        LocalBaiZeColors provides baiZeColors,
        LocalBaiZeCorners provides DefaultBaiZeCorners,
        LocalBaiZeSpacing provides DefaultBaiZeSpacing,
        LocalBaiZeTypeScale provides DefaultBaiZeTypeScale
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
        withAmoled = settings.amoledBlack,
        style = settings.kolorStyle.toPaletteStyle(),
        shapes = MaterialShapes,
        typography = MaterialTypography,
        animate = true,
        content = content
    )
}

@Composable
private fun BaiZeMiuixTheme(settings: AppearanceSettings, dark: Boolean, content: @Composable () -> Unit) {
    DynamicMaterialTheme(
        seedColor = resolveSeedColor(settings),
        useDarkTheme = dark,
        withAmoled = dark && settings.amoledBlack,
        style = settings.kolorStyle.toPaletteStyle(),
        shapes = MiuixShapes,
        typography = MiuixTypography,
        animate = true,
        content = content
    )
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
