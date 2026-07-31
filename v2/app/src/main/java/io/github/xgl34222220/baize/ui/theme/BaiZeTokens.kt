package io.github.xgl34222220.baize.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 白泽的语义色可以由两套外观共同消费，但几何、间距和字阶必须分别注入。
 * Material 3 与 MIUIX / HyperOS 不再共用页面尺度，避免最终只剩“同一界面换色”。
 */
@Immutable
data class BaiZeColors(
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val surfaceBase: Color,
    val surfaceRaised: Color,
    val surfaceOverlay: Color
)

val LightBaiZeColors = BaiZeColors(
    success = Color(0xFF0AA45B),
    warning = Color(0xFFB87300),
    danger = Color(0xFFD83A3A),
    info = Color(0xFF245FD3),
    surfaceBase = Color(0xFFECEBFA),
    surfaceRaised = Color(0xFFF8F7FD),
    surfaceOverlay = Color(0xFFF2F0FA)
)

val DarkBaiZeColors = BaiZeColors(
    success = Color(0xFF46CF8D),
    warning = Color(0xFFE8B45D),
    danger = Color(0xFFFF8585),
    info = Color(0xFF8EAFFF),
    surfaceBase = Color(0xFF11131A),
    surfaceRaised = Color(0xFF1B1E28),
    surfaceOverlay = Color(0xFF252937)
)

val AmoledBaiZeColors = DarkBaiZeColors.copy(
    surfaceBase = Color(0xFF000000),
    surfaceRaised = Color(0xFF0C0C0D),
    surfaceOverlay = Color(0xFF171719)
)

@Immutable
data class BaiZeCorners(
    val small: Shape,
    val medium: Shape,
    val large: Shape,
    val extraLarge: Shape,
    val full: Shape
)

/** Material 3：紧凑、层级清晰，卡片圆角不模拟 MIUIX 超椭圆。 */
val MaterialBaiZeCorners = BaiZeCorners(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
    full = RoundedCornerShape(percent = 50)
)

/** MIUIX / HyperOS：更大的连续圆角与悬浮层尺度。 */
val MiuixBaiZeCorners = BaiZeCorners(
    small = RoundedCornerShape(13.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
    full = RoundedCornerShape(percent = 50)
)

@Immutable
data class BaiZeSpacing(
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xxl: Dp,
    val huge: Dp,
    val pageHorizontal: Dp
)

val MaterialBaiZeSpacing = BaiZeSpacing(
    xs = 4.dp,
    sm = 8.dp,
    md = 12.dp,
    lg = 16.dp,
    xl = 20.dp,
    xxl = 24.dp,
    huge = 32.dp,
    pageHorizontal = 12.dp
)

val MiuixBaiZeSpacing = BaiZeSpacing(
    xs = 4.dp,
    sm = 8.dp,
    md = 12.dp,
    lg = 18.dp,
    xl = 22.dp,
    xxl = 28.dp,
    huge = 36.dp,
    pageHorizontal = 18.dp
)

@Immutable
data class BaiZeTypeScale(
    val caption: TextStyle,
    val body: TextStyle,
    val bodyLarge: TextStyle,
    val title: TextStyle,
    val headline: TextStyle,
    val display: TextStyle,
    val hero: TextStyle
)

val MaterialBaiZeTypeScale = BaiZeTypeScale(
    caption = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
    body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    title = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    headline = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    display = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    hero = TextStyle(
        fontSize = 36.sp,
        lineHeight = 42.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum"
    )
)

val MiuixBaiZeTypeScale = BaiZeTypeScale(
    caption = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    title = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    headline = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    display = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
    hero = TextStyle(
        fontSize = 42.sp,
        lineHeight = 48.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum"
    )
)

/* 默认值只用于预览或尚未进入 BaiZeTheme 的调用点。 */
val DefaultBaiZeCorners = MiuixBaiZeCorners
val DefaultBaiZeSpacing = MiuixBaiZeSpacing
val DefaultBaiZeTypeScale = MiuixBaiZeTypeScale

val LocalBaiZeColors = staticCompositionLocalOf { LightBaiZeColors }
val LocalBaiZeCorners = staticCompositionLocalOf { DefaultBaiZeCorners }
val LocalBaiZeSpacing = staticCompositionLocalOf { DefaultBaiZeSpacing }
val LocalBaiZeTypeScale = staticCompositionLocalOf { DefaultBaiZeTypeScale }

object BaiZeTokens {
    val colors: BaiZeColors
        @Composable get() = LocalBaiZeColors.current
    val corners: BaiZeCorners
        @Composable get() = LocalBaiZeCorners.current
    val spacing: BaiZeSpacing
        @Composable get() = LocalBaiZeSpacing.current
    val type: BaiZeTypeScale
        @Composable get() = LocalBaiZeTypeScale.current
}
