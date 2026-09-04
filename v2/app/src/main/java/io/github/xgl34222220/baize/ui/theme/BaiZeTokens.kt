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
 * 设计基线改为 shadcn/ui 式的移动端语义系统：中性底色、清晰边框、紧凑圆角、
 * 更克制的层级与排版。Material / MIUIX 继续共享同一套信息架构与 token，
 * 不复制 Web DOM，也不把 Android 控件伪装成网页组件。
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

/** 浅色：接近 shadcn neutral/zinc 的干净中性层级。 */
val LightBaiZeColors = BaiZeColors(
    success = Color(0xFF15803D),
    warning = Color(0xFFA16207),
    danger = Color(0xFFDC2626),
    info = Color(0xFF2563EB),
    surfaceBase = Color(0xFFFAFAFA),
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceOverlay = Color(0xFFF4F4F5)
)

/** 深色：避免蓝紫染色，使用纯中性阶差。 */
val DarkBaiZeColors = BaiZeColors(
    success = Color(0xFF4ADE80),
    warning = Color(0xFFFACC15),
    danger = Color(0xFFF87171),
    info = Color(0xFF60A5FA),
    surfaceBase = Color(0xFF09090B),
    surfaceRaised = Color(0xFF18181B),
    surfaceOverlay = Color(0xFF27272A)
)

/** AMOLED：保持真正黑底，组件仍保留必要边界。 */
val AmoledBaiZeColors = DarkBaiZeColors.copy(
    surfaceBase = Color(0xFF000000),
    surfaceRaised = Color(0xFF0A0A0A),
    surfaceOverlay = Color(0xFF171717)
)

@Immutable
data class BaiZeCorners(
    /** Badge、紧凑按钮、图标容器。 */
    val small: Shape,
    /** 输入框、列表项、设置分组。 */
    val medium: Shape,
    /** Card、统计卡、主要内容容器。 */
    val large: Shape,
    /** Sheet、Dock 与高层级浮层。 */
    val extraLarge: Shape,
    /** 胶囊和圆形状态。 */
    val full: Shape
)

val DefaultBaiZeCorners = BaiZeCorners(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
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
    /** shadcn 风格统一页面左右留白，提升主页面对齐感。 */
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
    caption = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    title = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    headline = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    display = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    hero = TextStyle(
        fontSize = 38.sp,
        lineHeight = 44.sp,
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
