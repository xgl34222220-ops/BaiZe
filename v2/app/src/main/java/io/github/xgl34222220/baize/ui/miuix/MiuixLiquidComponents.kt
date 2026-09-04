package io.github.xgl34222220.baize.ui.miuix

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/** 底栏项目：图标与标签顺序固定，不因页面切换改变。 */
data class MiuixLiquidNavItem(
    val title: String,
    val icon: ImageVector
)

/**
 * 主导航改为 shadcn/ui 式的低噪音 floating navigation：
 * 单一中性容器、清晰边框、选中项使用 secondary surface，不再依赖大面积玻璃模糊。
 * hazeState 参数继续保留，确保外部 API 与旧版本兼容。
 */
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
    @Suppress("UNUSED_VARIABLE") val compatibilityHazeState = hazeState

    val scheme = MaterialTheme.colorScheme
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val targetIndex = selectedIndex.coerceIn(items.indices)
    val shape = if (floating) BaiZeTokens.corners.large else RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

    Surface(
        modifier = modifier
            .then(
                if (floating) Modifier
                    .padding(horizontal = BaiZeTokens.spacing.pageHorizontal)
                    .padding(bottom = bottomInset + 10.dp)
                else Modifier
            )
            .fillMaxWidth(),
        shape = shape,
        color = BaiZeTokens.colors.surfaceRaised,
        contentColor = scheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            scheme.outlineVariant.copy(alpha = .72f)
        ),
        shadowElevation = if (floating) 3.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(
                start = 5.dp,
                top = 5.dp,
                end = 5.dp,
                bottom = if (floating) 5.dp else bottomInset + 5.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val active = index == targetIndex
                val iconColor by animateColorAsState(
                    targetValue = if (active) scheme.primary else scheme.onSurfaceVariant,
                    animationSpec = tween(150),
                    label = "baizeNavIcon"
                )
                val textColor by animateColorAsState(
                    targetValue = if (active) scheme.onSurface else scheme.onSurfaceVariant,
                    animationSpec = tween(150),
                    label = "baizeNavText"
                )
                val itemShape = RoundedCornerShape(11.dp)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(itemShape)
                        .then(
                            if (active) Modifier
                                .background(BaiZeTokens.colors.surfaceOverlay)
                                .border(1.dp, scheme.outlineVariant.copy(alpha = .50f), itemShape)
                            else Modifier
                        )
                        .clickable { onSelected(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.title,
                        modifier = Modifier.size(19.dp),
                        tint = iconColor
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.title,
                        color = textColor,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

/** 首页主状态卡：去掉渐变装饰，把状态、释放量和任务信息组织成清晰 Card 层级。 */
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
    val shape = BaiZeTokens.corners.large

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = BaiZeTokens.colors.surfaceRaised,
        contentColor = scheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            scheme.outlineVariant.copy(alpha = .68f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (positive) BaiZeTokens.colors.success else BaiZeTokens.colors.warning)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    device,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "  ·  $android",
                    color = scheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "最近一次释放",
                color = scheme.onSurfaceVariant,
                style = BaiZeTokens.type.caption
            )
            Spacer(Modifier.height(2.dp))
            Text(
                releasedText,
                color = scheme.onSurface,
                style = BaiZeTokens.type.hero
            )

            Spacer(Modifier.height(14.dp))
            Surface(
                shape = BaiZeTokens.corners.medium,
                color = BaiZeTokens.colors.surfaceOverlay,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    scheme.outlineVariant.copy(alpha = .46f)
                )
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                (if (positive) BaiZeTokens.colors.success else scheme.primary).copy(alpha = .10f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (positive) Icons.Rounded.CheckCircle else Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = if (positive) BaiZeTokens.colors.success else scheme.primary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(statusTitle, style = BaiZeTokens.type.body.copy(fontWeight = FontWeight.SemiBold))
                        if (taskPhase.isNotBlank()) {
                            Spacer(Modifier.height(1.dp))
                            Text(
                                taskPhase,
                                color = scheme.onSurfaceVariant,
                                style = BaiZeTokens.type.caption,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 统一主操作，按 shadcn Button 的紧凑高度与圆角处理。 */
@Composable
fun MiuixLiquidPrimaryButton(
    running: Boolean,
    scanReady: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
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
        !enabled -> scheme.onSurface.copy(alpha = .10f)
        running -> BaiZeTokens.colors.danger
        else -> scheme.primary
    }
    val contentColor = when {
        !enabled -> scheme.onSurface.copy(alpha = .42f)
        else -> Color.White
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .background(containerColor)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(label, color = contentColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
