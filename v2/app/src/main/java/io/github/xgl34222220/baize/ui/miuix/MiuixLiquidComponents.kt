package io.github.xgl34222220.baize.ui.miuix

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/** 底栏项目：图标与标签顺序固定，不因页面切换改变。 */
data class MiuixLiquidNavItem(
    val title: String,
    val icon: ImageVector
)

/**
 * 白泽悬浮液态玻璃底栏。
 * 玻璃仅放在导航层，主体内容保持清晰；选中态用动态色柔光块，不做网页式分段按钮。
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun MiuixLiquidDock(
    selectedIndex: Int,
    items: List<MiuixLiquidNavItem>,
    onSelected: (Int) -> Unit,
    hazeState: HazeState? = null,
    floating: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    val settings = LocalAppearanceSettings.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val amoled = dark && settings.amoledBlack
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val shape = if (floating) BaiZeTokens.corners.extraLarge else RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)

    val activeHazeState = hazeState.takeIf {
        settings.blurEnabled && settings.glassEnabled && !amoled
    }
    val dockColor = when {
        amoled -> Color(0xF0000000)
        activeHazeState != null && dark -> BaiZeTokens.colors.surfaceRaised.copy(alpha = .76f)
        activeHazeState != null -> BaiZeTokens.colors.surfaceRaised.copy(alpha = .72f)
        dark -> BaiZeTokens.colors.surfaceRaised.copy(alpha = .96f)
        else -> BaiZeTokens.colors.surfaceRaised.copy(alpha = .94f)
    }
    val borderColor = if (dark) Color.White.copy(alpha = .10f) else Color.White.copy(alpha = .78f)
    val hazeModifier = activeHazeState?.let { state ->
        Modifier.hazeEffect(state = state, style = HazeMaterials.ultraThin()) {
            blurRadius = 28.dp
            noiseFactor = .022f
        }
    } ?: Modifier

    BoxWithConstraints(
        modifier = modifier
            .then(
                if (floating) Modifier
                    .padding(horizontal = 14.dp)
                    .padding(bottom = bottomInset + 12.dp)
                else Modifier
            )
            .fillMaxWidth()
            .height(if (floating) 68.dp else 68.dp + bottomInset)
            .shadow(if (floating) 12.dp else 3.dp, shape, clip = false)
            .clip(shape)
            .then(hazeModifier)
            .background(dockColor)
            .border(1.dp, borderColor, shape)
            .padding(
                start = 6.dp,
                top = 6.dp,
                end = 6.dp,
                bottom = if (floating) 6.dp else bottomInset + 6.dp
            )
    ) {
        val itemWidth = maxWidth / items.size.toFloat()
        val compact = items.size > 4
        val targetIndex = selectedIndex.coerceIn(items.indices)
        val indicatorX by animateDpAsState(
            targetValue = itemWidth * targetIndex.toFloat(),
            animationSpec = tween(240),
            label = "baizeDockIndicator"
        )

        Box(
            modifier = Modifier
                .offset(x = indicatorX + 3.dp)
                .width(itemWidth - 6.dp)
                .height(50.dp)
                .clip(RoundedCornerShape(25.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            scheme.primaryContainer.copy(alpha = if (dark) .72f else .92f),
                            scheme.secondaryContainer.copy(alpha = if (dark) .52f else .76f)
                        )
                    )
                )
                .border(
                    1.dp,
                    scheme.primary.copy(alpha = if (dark) .12f else .10f),
                    RoundedCornerShape(25.dp)
                )
        )

        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                val active = index == targetIndex
                val iconColor by animateColorAsState(
                    targetValue = if (active) scheme.primary else scheme.onSurfaceVariant.copy(alpha = .76f),
                    animationSpec = tween(180),
                    label = "baizeDockIcon"
                )
                val textColor by animateColorAsState(
                    targetValue = if (active) scheme.primary else scheme.onSurfaceVariant.copy(alpha = .70f),
                    animationSpec = tween(180),
                    label = "baizeDockText"
                )

                Column(
                    modifier = Modifier
                        .width(itemWidth)
                        .height(50.dp)
                        .clip(RoundedCornerShape(25.dp))
                        .clickable { onSelected(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        item.icon,
                        item.title,
                        modifier = Modifier.size(if (compact) 20.dp else 22.dp),
                        tint = iconColor
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.title,
                        color = textColor,
                        fontSize = if (compact) 9.sp else 10.sp,
                        lineHeight = 11.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

/** 首页视觉中心：保留信息克制，但恢复白泽自己的动态色渐变与悬浮层次。 */
@Composable
fun MiuixOverviewHero(
    device: String,
    android: String,
    statusTitle: String,
    taskPhase: String,
    releasedText: String,
    positive: Boolean,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val shape = BaiZeTokens.corners.large

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(4.dp, shape, clip = false)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        scheme.primaryContainer.copy(alpha = if (dark) .52f else .88f),
                        scheme.secondaryContainer.copy(alpha = if (dark) .24f else .58f),
                        BaiZeTokens.colors.surfaceRaised.copy(alpha = .98f)
                    )
                )
            )
            .border(
                1.dp,
                if (dark) Color.White.copy(alpha = .08f) else Color.White.copy(alpha = .68f),
                shape
            )
            .padding(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (positive) BaiZeTokens.colors.success else BaiZeTokens.colors.warning)
                )
                Spacer(Modifier.width(8.dp))
                Text(device, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "  ·  $android",
                    color = scheme.onSurfaceVariant.copy(alpha = .78f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(22.dp))
            Text(
                "最近一次释放",
                color = scheme.onSurfaceVariant.copy(alpha = .78f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(1.dp))
            Text(releasedText, color = scheme.onSurface, style = BaiZeTokens.type.hero)

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(BaiZeTokens.corners.medium)
                    .background(BaiZeTokens.colors.surfaceRaised.copy(alpha = if (dark) .62f else .66f))
                    .border(
                        1.dp,
                        if (dark) Color.White.copy(alpha = .06f) else Color.White.copy(alpha = .52f),
                        BaiZeTokens.corners.medium
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(
                            (if (positive) BaiZeTokens.colors.success else scheme.primary).copy(alpha = .10f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (positive) Icons.Rounded.CheckCircle else Icons.Rounded.Refresh,
                        null,
                        tint = if (positive) BaiZeTokens.colors.success else scheme.primary,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(statusTitle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (taskPhase.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            taskPhase,
                            color = scheme.onSurfaceVariant.copy(alpha = .76f),
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** 高强调主操作：保持单一主 CTA，但恢复更舒展的尺寸与悬浮感。 */
@Composable
fun MiuixLiquidPrimaryButton(
    running: Boolean,
    scanReady: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val shape = BaiZeTokens.corners.medium
    val label = when {
        running -> "停止清理"
        scanReady -> "按扫描结果清理"
        else -> "立即智能清理"
    }
    val icon = when {
        running -> Icons.Rounded.Stop
        scanReady -> Icons.Rounded.DeleteSweep
        else -> Icons.Rounded.AutoAwesome
    }
    val containerColor = when {
        !enabled -> scheme.onSurface.copy(alpha = .11f)
        running -> BaiZeTokens.colors.danger
        else -> scheme.primary
    }
    val contentColor = if (!enabled) scheme.onSurface.copy(alpha = .42f) else Color.White

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .shadow(if (enabled) 5.dp else 0.dp, shape, clip = false)
            .clip(shape)
            .background(containerColor)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(9.dp))
            Text(label, color = contentColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
