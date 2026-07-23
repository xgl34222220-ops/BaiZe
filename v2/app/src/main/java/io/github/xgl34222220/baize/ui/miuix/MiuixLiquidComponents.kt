package io.github.xgl34222220.baize.ui.miuix

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
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

/** A stable Miuix navigation item. Labels always stay below icons. */
data class MiuixLiquidNavItem(
    val title: String,
    val icon: ImageVector
)

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
        RoundedCornerShape(24.dp)
    } else {
        RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    }
    val activeHazeState = hazeState.takeIf {
        settings.blurEnabled && settings.glassEnabled && !amoled
    }
    val dockColor = when {
        amoled -> Color(0xF2000000)
        activeHazeState != null && dark -> scheme.surface.copy(alpha = .62f)
        activeHazeState != null -> Color.White.copy(alpha = .68f)
        else -> BaiZeTokens.colors.surfaceRaised
    }
    val hazeModifier = activeHazeState?.let { state ->
        Modifier.hazeEffect(
            state = state,
            style = HazeMaterials.ultraThin()
        ) {
            blurRadius = 18.dp
            noiseFactor = .03f
        }
    } ?: Modifier

    Row(
        modifier = modifier
            .then(
                if (floating) {
                    Modifier
                        .padding(horizontal = 14.dp)
                        .padding(bottom = bottomInset + 10.dp)
                } else {
                    Modifier
                }
            )
            .fillMaxWidth()
            .shadow(if (floating) 4.dp else 0.dp, shape, clip = false)
            .clip(shape)
            .then(hazeModifier)
            .background(dockColor)
            .border(
                1.dp,
                if (dark) Color.White.copy(alpha = .07f)
                else scheme.outlineVariant.copy(alpha = .45f),
                shape
            )
            .padding(
                start = 8.dp,
                top = 6.dp,
                end = 8.dp,
                bottom = if (floating) 6.dp else bottomInset + 6.dp
            )
    ) {
        val targetIndex = selectedIndex.coerceIn(items.indices)
        items.forEachIndexed { index, item ->
            val active = index == targetIndex
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelected(index) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 42.dp, height = 28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (active) scheme.primary.copy(alpha = if (dark) .20f else .11f)
                            else Color.Transparent
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.title,
                        modifier = Modifier.size(20.dp),
                        tint = if (active) scheme.primary else scheme.onSurfaceVariant
                    )
                }
                Text(
                    text = item.title,
                    color = if (active) scheme.primary else scheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

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
    val surface = BaiZeTokens.colors.surfaceRaised

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(surface)
            .border(1.dp, scheme.onSurface.copy(alpha = if (dark) .08f else .05f), shape)
            .padding(BaiZeTokens.spacing.xxl)
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
                Text(device, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    "  ·  $android",
                    color = scheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(24.dp))
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

            Spacer(Modifier.height(20.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(BaiZeTokens.corners.medium)
                    .background(scheme.onSurface.copy(alpha = if (dark) .055f else .04f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (positive) Icons.Rounded.CheckCircle else Icons.Rounded.Refresh,
                    contentDescription = null,
                    tint = if (positive) BaiZeTokens.colors.success else scheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(statusTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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

@Composable
fun MiuixLiquidPrimaryButton(
    running: Boolean,
    scanReady: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val shape = BaiZeTokens.corners.full
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

    // 纯色胶囊：默认 primary，运行中用中性面，禁用为淡灰。
    val containerColor = when {
        !enabled -> scheme.onSurface.copy(alpha = .12f)
        running -> scheme.surfaceVariant
        else -> scheme.primary
    }
    val contentColor = when {
        !enabled -> scheme.onSurface.copy(alpha = .45f)
        running -> scheme.onSurface
        else -> scheme.onPrimary
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(shape)
            .background(containerColor)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, color = contentColor, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}
