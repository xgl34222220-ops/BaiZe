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
 * 白泽统一原生 UI 设计系统（2026-10 重设计，唯一来源）。
 *
 * 参考：HyperOS 手机管家「垃圾清理」/「清理存储」、SD Maid SE、Files by Google。
 * 两套皮肤（经典 / 灵动）共享同一套语义色、圆角、间距、字阶、动效与图标色调；
 * 两者只在 Material 控件形状和字阶细节上不同，页面不得再自行定义随机值。
 *
 * - 间距：4 / 8 / 12 / 16 / 24（[BaiZeSpacing]，页面左右边距 16）。
 * - 圆角：8 小控件 / 12 图标托底 / 16 输入框 / 24 卡片 / 32 底部弹层 / 胶囊（[BaiZeRadius]）。
 * - 层级：页面浅灰底 + 白色无阴影卡片；只有悬浮按钮、底部操作栏和弹层带柔和阴影（[BaiZeElevation]）。
 * - 模糊：仅底部导航与弹层背后使用，低端机或关闭动效时回退为不透明底色。
 * - 字阶：大标题 28 粗 / 页面标题 20 / 卡片标题 16 / 正文 14 / 说明 12-13；所有容量数字使用等宽数字（tnum）。
 * - 颜色：主色默认为白泽青 #006B72（与 3.0.0 图标一致，可在外观中更换为澎湃橙等），语义色见 [BaiZeColors]，
 *   图标使用 [BaiZeTones] 的彩色色调托底。
 * - 动效：短 120 / 中 220 / 长 360 ms，按压缩放 0.97；系统关闭动画或性能降级时全部为 0（rememberMotionEnabled）。
 */

@Immutable
data class BaiZeColors(
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val surfaceBase: Color,
    val surfaceRaised: Color,
    val surfaceOverlay: Color,
    val muted: Color
)

/** 浅色：浅冷灰底、低对比分组卡片。 */
val LightBaiZeColors = BaiZeColors(
    success = Color(0xFF187B58),
    warning = Color(0xFF956319),
    danger = Color(0xFFD83A3A),
    info = Color(0xFF245FD3),
    surfaceBase = Color(0xFFF7F8FA),
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceOverlay = Color(0xFFF0F2F6),
    muted = Color(0xFF8C93A0)
)

/** 普通深色：避免纯黑压迫感，维持柔和层级。 */
val DarkBaiZeColors = BaiZeColors(
    success = Color(0xFF46CF8D),
    warning = Color(0xFFE8B45D),
    danger = Color(0xFFFF8585),
    info = Color(0xFF8EAFFF),
    surfaceBase = Color(0xFF101319),
    surfaceRaised = Color(0xFF1C222C),
    surfaceOverlay = Color(0xFF293240),
    muted = Color(0xFFA2AAB8)
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
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
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
    caption = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal, letterSpacing = .15.sp),
    bodyLarge = TextStyle(fontSize = 14.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal, letterSpacing = .15.sp),
    title = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    headline = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.4).sp),
    display = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.7).sp),
    hero = TextStyle(
        fontSize = 34.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.Medium,
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

/** 圆角层级：卡片 24、弹层 32，均为平滑圆角。 */
object BaiZeRadius {
    val xs: Dp = 8.dp
    val icon: Dp = 12.dp
    val field: Dp = 16.dp
    val card: Dp = 24.dp
    val sheet: Dp = 32.dp
}

/** 阴影只用于“浮在内容之上”的元素。 */
object BaiZeElevation {
    val card: Dp = 0.dp
    val floatingButton: Dp = 6.dp
    val actionBar: Dp = 10.dp
    val sheet: Dp = 12.dp
}

/** 动效时长（毫秒）与按压缩放；关闭动效时调用方使用 0。 */
object BaiZeMotion {
    const val SHORT = 120
    const val MEDIUM = 220
    const val LONG = 360
    const val COUNT_UP = 650
    const val PRESS_SCALE = .97f
}

object BaiZeIconSize {
    val tile: Dp = 40.dp
    val glyph: Dp = 22.dp
    val check: Dp = 24.dp
    val headerButton: Dp = 44.dp
}

/** 图标彩色托底：同一含义在所有页面使用同一色调。 */
@Immutable
data class BaiZeTone(val light: Color, val dark: Color)

object BaiZeTones {
    val blue = BaiZeTone(Color(0xFF2F7BF6), Color(0xFF6EA4FF))
    val green = BaiZeTone(Color(0xFF16B256), Color(0xFF4CD787))
    val orange = BaiZeTone(Color(0xFFFF7A1A), Color(0xFFFF9F57))
    val red = BaiZeTone(Color(0xFFF0433A), Color(0xFFFF7A72))
    val purple = BaiZeTone(Color(0xFF7B61FF), Color(0xFFA897FF))
    val teal = BaiZeTone(Color(0xFF13A8A8), Color(0xFF4FD1D1))
    val folder = BaiZeTone(Color(0xFFF5B83D), Color(0xFFF7C55E))
    val slate = BaiZeTone(Color(0xFF6B7A90), Color(0xFF9AA7BA))
}
