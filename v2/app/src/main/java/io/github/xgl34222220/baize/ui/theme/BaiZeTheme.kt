package io.github.xgl34222220.baize.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle
import io.github.xgl34222220.baize.ui.appearance.AccentOptions
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.KolorStyle
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.appearance.UiStyle

/** Material controls and MIUIX surfaces share the same corner hierarchy. */
private val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

/** Active LuoShu Miuix shape hierarchy; Material remains independent. */
private val MiuixShapes = Shapes(
    extraSmall = RoundedCornerShape(7.dp),
    small = RoundedCornerShape(11.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

private val LuoShuMiuixCorners = DefaultBaiZeCorners.copy(
    small = RoundedCornerShape(11.dp), medium = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(30.dp)
)
private val LuoShuMiuixTypeScale = DefaultBaiZeTypeScale.copy(
    title = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    headline = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    body = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp)
)

private val SharedTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.7).sp),
    headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.7).sp),
    headlineMedium = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.4).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.4).sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.4).sp),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 14.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal, letterSpacing = .15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal, letterSpacing = .15.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
)

// Ported from LuoShu ui/theme/LuoShuTheme.kt at fe4df5f. Material keeps its own typography.
private val LuoShuMiuixTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = .2.sp)
)

/** Both appearances resolve surfaces from the selected palette, including Monet and AMOLED. */
@Composable
fun BaiZeTheme(settings: AppearanceSettings, content: @Composable () -> Unit) {
    val dark = resolveDark(settings.themeMode)
    val amoled = dark && settings.amoledBlack
    DynamicMaterialTheme(
        seedColor = resolveSeedColor(settings),
        useDarkTheme = dark,
        withAmoled = amoled,
        style = settings.kolorStyle.toPaletteStyle(),
        shapes = if (settings.uiStyle == UiStyle.MATERIAL) MaterialShapes else MiuixShapes,
        typography = if (settings.uiStyle == UiStyle.MIUIX) LuoShuMiuixTypography else SharedTypography,
        animate = true
    ) {
        val generatedScheme = MaterialTheme.colorScheme
        // The default blue is deliberately crisp. Explicit Monet, alternate accents and
        // the neutral/vibrant palette options continue to use their generated colours.
        val defaultBlue = settings.uiStyle == UiStyle.MATERIAL && !settings.monetEnabled &&
            settings.seedArgb == AccentOptions.first().argb && settings.kolorStyle == KolorStyle.SOFT
        val scheme = if (defaultBlue) generatedScheme.copy(
            primary = if (dark) Color(0xFFA9CAFF) else Color(0xFF376BE6),
            onPrimary = if (dark) Color(0xFF082C63) else Color.White,
            primaryContainer = if (dark) Color(0xFF1C3656) else Color(0xFFE5EEFF),
            onPrimaryContainer = if (dark) Color(0xFFDCE9FF) else Color(0xFF163C77),
            surfaceTint = if (dark) Color(0xFFA9CAFF) else Color(0xFF376BE6),
            onSurface = if (dark) Color(0xFFE8EDF5) else Color(0xFF202A3B),
            onSurfaceVariant = if (dark) Color(0xFFADB8CA) else Color(0xFF626F82)
        ) else generatedScheme
        val semantic = if (dark) DarkBaiZeColors else LightBaiZeColors
        val colors = if (settings.uiStyle == UiStyle.MIUIX) semantic.copy(
            surfaceBase = when {
                amoled -> Color.Black
                dark -> scheme.surfaceContainerLowest
                else -> lerp(Color(0xFFF4F6FA), scheme.primaryContainer, .07f)
            },
            surfaceRaised = when {
                amoled -> Color(0xFF111214)
                dark -> scheme.surfaceContainerLow
                else -> scheme.surfaceContainerLowest
            },
            surfaceOverlay = when {
                amoled -> Color(0xFF1B1C20)
                dark -> scheme.surfaceContainerHigh
                else -> lerp(scheme.surfaceContainerLowest, scheme.primaryContainer, .20f)
            },
            success = if (dark) Color(0xFF69D9AD) else Color(0xFF187B58),
            warning = if (dark) Color(0xFFF3C378) else Color(0xFF956319)
        ) else semantic.copy(
            surfaceBase = when {
                amoled -> Color.Black
                dark -> lerp(DarkBaiZeColors.surfaceBase, scheme.primaryContainer, .03f)
                else -> lerp(LightBaiZeColors.surfaceBase, scheme.primaryContainer, .035f)
            },
            surfaceRaised = when {
                amoled -> Color(0xFF111214)
                dark -> lerp(DarkBaiZeColors.surfaceRaised, scheme.primaryContainer, .035f)
                else -> lerp(Color.White, scheme.primaryContainer, .015f)
            },
            surfaceOverlay = when {
                amoled -> Color(0xFF1B1C20)
                dark -> lerp(DarkBaiZeColors.surfaceOverlay, scheme.primaryContainer, .05f)
                else -> lerp(LightBaiZeColors.surfaceOverlay, scheme.primaryContainer, .065f)
            }
        )
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(
                LocalContentColor provides scheme.onSurface,
                LocalBaiZeColors provides colors,
                LocalBaiZeCorners provides if (settings.uiStyle == UiStyle.MIUIX) LuoShuMiuixCorners else DefaultBaiZeCorners,
                LocalBaiZeSpacing provides DefaultBaiZeSpacing,
                LocalBaiZeTypeScale provides if (settings.uiStyle == UiStyle.MIUIX) LuoShuMiuixTypeScale else DefaultBaiZeTypeScale,
                content = content
            )
        }
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
