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
 * 方向 A 设计 token：两套皮肤（Material / Miuix）共享的语义色、圆角、间距与字阶。
 * 由 [BaiZeTheme] 按明暗/AMOLED 注入，页面与组件一律经 [BaiZeTokens] 读取，
 * 不再散落硬编码色值与随机的 dp/sp。
 */

// ---------- 语义色 ----------

@Immutable
data class BaiZeColors(
    /** 成功 / 就绪（低饱和绿） */
    val success: Color,
    /** 警告 / 未就绪（低饱和琥珀） */
    val warning: Color,
    /** 错误 / 危险操作 */
    val danger: Color,
    /** 中性信息提示 */
    val info: Color,
    /** 页面背景 */
    val surfaceBase: Color,
    /** 卡片 / 列表项 */
    val surfaceRaised: Color,
    /** 浮层 / 弹层 / 强调卡片 */
    val surfaceOverlay: Color
)

val LightBaiZeColors = BaiZeColors(
    success = Color(0xFF3D9B76),
    warning = Color(0xFFB08A3C),
    danger = Color(0xFFC0554F),
    info = Color(0xFF5B84A6),
    surfaceBase = Color(0xFFF4F4F6),
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceOverlay = Color(0xFFFCFCFD)
)

val DarkBaiZeColors = BaiZeColors(
    success = Color(0xFF6FBA97),
    warning = Color(0xFFCBA465),
    danger = Color(0xFFCF7E74),
    info = Color(0xFF8AA9C4),
    surfaceBase = Color(0xFF101114),
    surfaceRaised = Color(0xFF1C1D21),
    surfaceOverlay = Color(0xFF24252A)
)

/** AMOLED 纯黑模式：背景纯黑，卡片仅用极浅阶差。 */
val AmoledBaiZeColors = DarkBaiZeColors.copy(
    surfaceBase = Color(0xFF000000),
    surfaceRaised = Color(0xFF101012),
    surfaceOverlay = Color(0xFF1A1B1F)
)

// ---------- 圆角 ----------

@Immutable
data class BaiZeCorners(
    /** 小控件：图标托底、标签、chip */
    val small: Shape,
    /** 常规卡片、列表项 */
    val medium: Shape,
    /** 页面级大卡片、对话框 */
    val large: Shape,
    /** Hero / 主按钮 / Dock */
    val extraLarge: Shape,
    /** 胶囊、圆形指示点 */
    val full: Shape
)

val DefaultBaiZeCorners = BaiZeCorners(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
    full = RoundedCornerShape(percent = 50)
)

// ---------- 间距（4 倍数） ----------

@Immutable
data class BaiZeSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val huge: Dp = 32.dp,
    /** 全应用统一的页面水平边距 */
    val pageHorizontal: Dp = 20.dp
)

val DefaultBaiZeSpacing = BaiZeSpacing()

// ---------- 字阶 ----------

@Immutable
data class BaiZeTypeScale(
    /** 11sp：说明文字、辅助信息（应用内最小字号） */
    val caption: TextStyle,
    /** 13sp：正文 */
    val body: TextStyle,
    /** 15sp：强调正文 */
    val bodyLarge: TextStyle,
    /** 17sp：卡片标题 */
    val title: TextStyle,
    /** 22sp：区块标题（中文 Bold，不用 Black） */
    val headline: TextStyle,
    /** 28sp：页头主标题 */
    val display: TextStyle,
    /** 40sp 等宽数字：Hero 大数字 */
    val hero: TextStyle
)

val DefaultBaiZeTypeScale = BaiZeTypeScale(
    caption = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
    body = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    title = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    headline = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    display = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    hero = TextStyle(
        fontSize = 40.sp,
        lineHeight = 46.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum"
    )
)

// ---------- CompositionLocal 注入 ----------

val LocalBaiZeColors = staticCompositionLocalOf { LightBaiZeColors }
val LocalBaiZeCorners = staticCompositionLocalOf { DefaultBaiZeCorners }
val LocalBaiZeSpacing = staticCompositionLocalOf { DefaultBaiZeSpacing }
val LocalBaiZeTypeScale = staticCompositionLocalOf { DefaultBaiZeTypeScale }

/** 统一的 token 读取入口：`BaiZeTokens.colors.success` 等。 */
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
