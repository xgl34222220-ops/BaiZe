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

/** Material 3：标准化控件皮肤，与 MIUIX 共用同一圆角等级。 */
private val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** MIUIX / HyperOS：分组卡片更圆润，悬浮层使用 28–32dp 圆角。 */
private val MiuixShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

private val MaterialTypography = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

private val MiuixTypography = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

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
