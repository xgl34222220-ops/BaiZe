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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalResources
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

/** Hetu Miuix shape hierarchy; Material remains independently selectable. */
private val MiuixShapes = Shapes(
    extraSmall = RoundedCornerShape(9.dp),
    small = RoundedCornerShape(13.dp),
    medium = RoundedCornerShape(17.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp)
)

private val HetuCorners = DefaultBaiZeCorners.copy(
    small = RoundedCornerShape(13.dp), medium = RoundedCornerShape(17.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(26.dp)
)
private val HetuTypeScale = DefaultBaiZeTypeScale.copy(
    title = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    headline = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
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

// Aligned with Hetu test.78 (063ae365): compact native type hierarchy.
private val HetuTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineLarge = TextStyle(fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.35).sp),
    headlineMedium = TextStyle(fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = .1.sp),
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
        typography = if (settings.uiStyle == UiStyle.MIUIX) HetuTypography else SharedTypography,
        animate = true
    ) {
        val generatedScheme = MaterialTheme.colorScheme
        val defaultBlue = !settings.monetEnabled &&
            settings.seedArgb == AccentOptions.first().argb && settings.kolorStyle == KolorStyle.SOFT
        val scheme = if (defaultBlue) generatedScheme.copy(
            primary = if (dark) Color(0xFF3B82F6) else Color(0xFF2563EB),
            onPrimary = if (dark) Color(0xFF081B39) else Color.White,
            primaryContainer = if (dark) Color(0xFF1E3A8A) else Color(0xFFDBEAFE),
            onPrimaryContainer = if (dark) Color(0xFFDBEAFE) else Color(0xFF1E40AF),
            secondary = if (dark) Color(0xFF60A5FA) else Color(0xFF2563EB),
            secondaryContainer = if (dark) Color(0xFF172338) else Color(0xFFEDF4FF),
            onSecondaryContainer = if (dark) Color(0xFFDBEAFE) else Color(0xFF1E40AF),
            surfaceTint = if (dark) Color(0xFF3B82F6) else Color(0xFF2563EB),
            background = if (amoled) Color.Black else if (dark) Color(0xFF121212) else Color(0xFFF4F6F9),
            surface = if (dark) Color(0xFF1E1E1E) else Color.White,
            onBackground = if (dark) Color(0xFFF8FAFC) else Color(0xFF0F172A),
            onSurface = if (dark) Color(0xFFF8FAFC) else Color(0xFF0F172A),
            onSurfaceVariant = if (dark) Color(0xFF94A3B8) else Color(0xFF64748B),
            outlineVariant = if (dark) Color(0xFF30343B) else Color(0xFFE2E8F0)
        ) else generatedScheme
        val semantic = if (dark) DarkBaiZeColors else LightBaiZeColors
        val colors = if (settings.uiStyle == UiStyle.MIUIX) semantic.copy(
            surfaceBase = if (amoled) Color.Black else if (dark) Color(0xFF121212) else Color(0xFFF4F6F9),
            surfaceRaised = if (amoled) Color(0xFF111214) else if (dark) Color(0xFF1E1E1E) else Color.White,
            surfaceOverlay = if (dark) Color(0xFF24272D) else Color(0xFFF1F5F9),
            success = if (dark) Color(0xFF34D399) else Color(0xFF059669),
            warning = if (dark) Color(0xFFFBBF24) else Color(0xFFB45309),
            danger = if (dark) Color(0xFFF87171) else Color(0xFFEF4444)
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
                LocalBaiZeCorners provides if (settings.uiStyle == UiStyle.MIUIX) HetuCorners else DefaultBaiZeCorners,
                LocalBaiZeSpacing provides DefaultBaiZeSpacing,
                LocalBaiZeTypeScale provides if (settings.uiStyle == UiStyle.MIUIX) HetuTypeScale else DefaultBaiZeTypeScale,
            ) {
                BaiZeSafeViewport(content = content)
            }
        }
    }
}

@Composable
private fun resolveSeedColor(settings: AppearanceSettings): Color {
    val resources = LocalResources.current
    return if (settings.monetEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Color(resources.getColor(android.R.color.system_accent1_500, null))
    } else {
        Color(settings.seedArgb)
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
