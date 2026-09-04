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
 * 统一悬浮玻璃底栏。
 * 玻璃只用于导航层，不把主体卡片做成整页模糊。
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
    val shape = if (floating) {
        RoundedCornerShape(32.dp)
    } else {
        RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    }

    val activeHazeState = hazeState.takeIf {
        settings.blurEnabled && settings.glassEnabled && !amoled
    }
    val dockColor = when {
        amoled -> Color(0xF2000000)
        activeHazeState != null && dark -> BaiZeTokens.colors.surfaceRaised.copy(alpha = .82f)
        activeHazeState != null -> BaiZeTokens.colors.surfaceRaised.copy(alpha = .84f)
        else -> BaiZeTokens.colors.surfaceRaised.copy(alpha = .98f)
    }
    val borderColor = if (dark) {
        Color.White.copy(alpha = .11f)
    } else {
        Color.White.copy(alpha = .88f)
    }
    val hazeModifier = activeHazeState?.let { state ->
        Modifier.hazeEffect(
            state = state,
            style = HazeMaterials.ultraThin()
        ) {
            blurRadius = 24.dp
            noiseFactor = .025f
        }
    } ?: Modifier

    BoxWithConstraints(
        modifier = modifier
            .then(
                if (floating) {
                    Modifier
                        .padding(horizontal = 14.dp)
                        .padding(bottom = bottomInset + 12.dp)
                } else {
                    Modifier
                }
            )
            .fillMaxWidth()
            .height(if (floating) 64.dp else 64.dp + bottomInset)
            .shadow(if (floating) 8.dp else 2.dp, shape, clip = false)
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
            animationSpec = tween(durationMillis = 240),
            label = "miuixDockIndicator"
        )

        Box(
            modifier = Modifier
                .offset(x = indicatorX + 4.dp)
                .width(itemWidth - 8.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(scheme.primaryContainer.copy(alpha = if (dark) .72f else .88f))
        )

        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                val active = index == targetIndex
                val iconColor by animateColorAsState(
                    targetValue = if (active) scheme.primary else scheme.onSurfaceVariant.copy(alpha = .82f),
                    animationSpec = tween(180),
                    label = "miuixDockIconColor"
                )
                val textColor by animateColorAsState(
                    targetValue = if (active) scheme.primary else scheme.onSurfaceVariant.copy(alpha = .78f),
                    animationSpec = tween(180),
                    label = "miuixDockTextColor"
                )

                Column(
                    modifier = Modifier
                        .width(itemWidth)
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .clickable { onSelected(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.title,
                        modifier = Modifier.size(if (compact) 20.dp else 22.dp),
                        tint = iconColor
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.title,
                        color = textColor,
                        fontSize = if (compact) 9.sp else 10.sp,
                        lineHeight = if (compact) 10.sp else 11.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

/** 首页视觉中心：运行状态、设备信息与最近释放量集中在一张主状态卡。 */
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
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        scheme.primaryContainer.copy(alpha = if (dark) .58f else .82f),
                        BaiZeTokens.colors.surfaceRaised
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = if (dark) .07f else .62f), shape)
            .padding(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (positive) BaiZeTokens.colors.success else BaiZeTokens.colors.warning)
                )
                Spacer(Modifier.width(8.dp))
                Text(device, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "  ·  $android",
                    color = scheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "最近一次释放",
                color = scheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                releasedText,
                color = scheme.onSurface,
                style = BaiZeTokens.type.hero
            )

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(BaiZeTokens.corners.medium)
                    .background(BaiZeTokens.colors.surfaceOverlay.copy(alpha = if (dark) .78f else .92f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (positive) Icons.Rounded.CheckCircle else Icons.Rounded.Refresh,
                    contentDescription = null,
                    tint = if (positive) BaiZeTokens.colors.success else scheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(statusTitle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        taskPhase,
                        color = scheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** 统一主操作：页面最多一个高强调主按钮，高度保持 52dp。 */
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
        !enabled -> scheme.onSurface.copy(alpha = .12f)
        running -> BaiZeTokens.colors.danger
        else -> scheme.primary
    }
    val contentColor = when {
        !enabled -> scheme.onSurface.copy(alpha = .45f)
        else -> Color.White
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(containerColor)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(9.dp))
            Text(label, color = contentColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
