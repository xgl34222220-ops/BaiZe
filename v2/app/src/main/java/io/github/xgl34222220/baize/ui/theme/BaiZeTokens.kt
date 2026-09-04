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
 * 白泽 MIUIX 设计 token。
 *
 * 与洛书当前 MIUIX 主线保持同一视觉尺度：F5F3FC 页面底色、16dp 页面留白、
 * 7/11/16/22/26dp 圆角阶梯、Black 标题字阶和动态 Monet 容器层级。
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
    success = Color(0xFF27BE83),
    warning = Color(0xFFF0A532),
    danger = Color(0xFFD83A3A),
    info = Color(0xFF245FD3),
    surfaceBase = Color(0xFFF5F3FC),
    surfaceRaised = Color.White,
    surfaceOverlay = Color(0xFFF3F1F9)
)

val DarkBaiZeColors = BaiZeColors(
    success = Color(0xFF27BE83),
    warning = Color(0xFFF0A532),
    danger = Color(0xFFFF8585),
    info = Color(0xFF8EAFFF),
    surfaceBase = Color(0xFF11131A),
    surfaceRaised = Color(0xFF1B1E28),
    surfaceOverlay = Color(0xFF252937)
)

val AmoledBaiZeColors = DarkBaiZeColors.copy(
    surfaceBase = Color.Black,
    surfaceRaised = Color(0xFF080808),
    surfaceOverlay = Color(0xFF111111)
)

@Immutable
data class BaiZeCorners(
    val small: Shape,
    val medium: Shape,
    val large: Shape,
    val extraLarge: Shape,
    val full: Shape
)

val DefaultBaiZeCorners = BaiZeCorners(
    small = RoundedCornerShape(11.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(26.dp),
    full = RoundedCornerShape(percent = 50)
)

@Immutable
data class BaiZeSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val huge: Dp = 32.dp,
    val pageHorizontal: Dp = 16.dp
)

val DefaultBaiZeSpacing = BaiZeSpacing()

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

val DefaultBaiZeTypeScale = BaiZeTypeScale(
    caption = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
    body = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    title = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold),
    headline = TextStyle(fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Black),
    display = TextStyle(fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.Black),
    hero = TextStyle(
        fontSize = 36.sp,
        lineHeight = 42.sp,
        fontWeight = FontWeight.Black,
        fontFeatureSettings = "tnum"
    )
)

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
