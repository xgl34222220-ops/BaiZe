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
 * 白泽统一原生 UI 设计 token。
 *
 * shadcn/ui 只提供组件组织与信息层级；视觉回归白泽自己的移动端气质：
 * MIUIX / HyperOS 式圆润层级、Monet 动态色、轻玻璃与克制渐变。
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

/** 浅色：微冷的蓝紫灰底，让白色卡片自然浮起，不做网页式纯白平铺。 */
val LightBaiZeColors = BaiZeColors(
    success = Color(0xFF0A9D63),
    warning = Color(0xFFB97408),
    danger = Color(0xFFD94545),
    info = Color(0xFF3469D8),
    surfaceBase = Color(0xFFF0F1F8),
    surfaceRaised = Color(0xFFFBFBFE),
    surfaceOverlay = Color(0xFFF3F3FA)
)

/** 深色：蓝黑而非死黑，保留玻璃和卡片之间的空气感。 */
val DarkBaiZeColors = BaiZeColors(
    success = Color(0xFF45D49A),
    warning = Color(0xFFF0B75E),
    danger = Color(0xFFFF858B),
    info = Color(0xFF8FB0FF),
    surfaceBase = Color(0xFF0D1018),
    surfaceRaised = Color(0xFF171B26),
    surfaceOverlay = Color(0xFF222837)
)

/** AMOLED：页面保持真黑，浮层仍保留极轻层级，避免所有元素糊成一片。 */
val AmoledBaiZeColors = DarkBaiZeColors.copy(
    surfaceBase = Color(0xFF000000),
    surfaceRaised = Color(0xFF0B0D12),
    surfaceOverlay = Color(0xFF161922)
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
    small = RoundedCornerShape(13.dp),
    medium = RoundedCornerShape(19.dp),
    large = RoundedCornerShape(25.dp),
    extraLarge = RoundedCornerShape(34.dp),
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
    val pageHorizontal: Dp = 14.dp
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
    caption = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    title = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    headline = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    display = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    hero = TextStyle(
        fontSize = 40.sp,
        lineHeight = 46.sp,
        fontWeight = FontWeight.Bold,
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
