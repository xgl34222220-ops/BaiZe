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
 * 基准：MIUIX / HyperOS 气质 + Material 3 动态色 + Monet + 轻量玻璃。
 * 两套皮肤共享语义色、圆角、间距和字阶，页面不再自行定义随机值。
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

/** 浅色：浅紫灰底、低对比分组卡片。 */
val LightBaiZeColors = BaiZeColors(
    success = Color(0xFF0AA45B),
    warning = Color(0xFFB87300),
    danger = Color(0xFFD83A3A),
    info = Color(0xFF245FD3),
    surfaceBase = Color(0xFFECEBFA),
    surfaceRaised = Color(0xFFF8F7FD),
    surfaceOverlay = Color(0xFFF2F0FA)
)

/** 普通深色：避免纯黑压迫感，维持柔和层级。 */
val DarkBaiZeColors = BaiZeColors(
    success = Color(0xFF46CF8D),
    warning = Color(0xFFE8B45D),
    danger = Color(0xFFFF8585),
    info = Color(0xFF8EAFFF),
    surfaceBase = Color(0xFF11131A),
    surfaceRaised = Color(0xFF1B1E28),
    surfaceOverlay = Color(0xFF252937)
)

/** AMOLED：页面纯黑，主体与次级容器保留极轻阶差。 */
val AmoledBaiZeColors = DarkBaiZeColors.copy(
    surfaceBase = Color(0xFF000000),
    surfaceRaised = Color(0xFF0C0C0D),
    surfaceOverlay = Color(0xFF171719)
)

@Immutable
data class BaiZeCorners(
    /** Chip、小按钮、图标托底。 */
    val small: Shape,
    /** 输入框、设置分组卡片。 */
    val medium: Shape,
    /** 数据卡、仪表盘主卡片。 */
    val large: Shape,
    /** 悬浮底栏、底部弹层与高层级浮层。 */
    val extraLarge: Shape,
    /** 胶囊和圆形状态。 */
    val full: Shape
)

val DefaultBaiZeCorners = BaiZeCorners(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(32.dp),
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
    /** 360dp 手机基准的统一页面左右边距。 */
    val pageHorizontal: Dp = 12.dp
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
    body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
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
