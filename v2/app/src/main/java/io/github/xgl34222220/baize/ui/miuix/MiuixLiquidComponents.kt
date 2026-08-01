package io.github.xgl34222220.baize.ui.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
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
import top.yukonga.miuix.kmp.basic.Button as NativeButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as NativeButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as NativeCard
import top.yukonga.miuix.kmp.basic.CardDefaults as NativeCardDefaults
import top.yukonga.miuix.kmp.basic.Icon as NativeIcon
import top.yukonga.miuix.kmp.basic.NavigationBar as NativeNavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem as NativeNavigationBarItem
import top.yukonga.miuix.kmp.basic.Text as NativeText
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme as NativeMiuixTheme

/** Bottom navigation item used by the real Miuix NavigationBar. */
data class MiuixLiquidNavItem(
    val title: String,
    val icon: ImageVector
)

/**
 * Real Miuix navigation bar. Insets, item sizing, press feedback, selected
 * typography and the main Home/Settings glyphs are supplied by compose-miuix-ui.
 */
@Composable
fun MiuixLiquidDock(
    selectedIndex: Int,
    items: List<MiuixLiquidNavItem>,
    onSelected: (Int) -> Unit,
    @Suppress("UNUSED_PARAMETER") hazeState: HazeState? = null,
    floating: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val targetIndex = selectedIndex.coerceIn(items.indices)
    val colors = NativeMiuixTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (floating) 14.dp else 0.dp)
            .padding(bottom = if (floating) bottomInset + 10.dp else 0.dp)
    ) {
        NativeCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = if (floating) 30.dp else 0.dp,
            insideMargin = PaddingValues(0.dp),
            colors = NativeCardDefaults.defaultColors(
                color = colors.surfaceContainer.copy(alpha = if (floating) .97f else 1f),
                contentColor = colors.onSurfaceContainer
            )
        ) {
            NativeNavigationBar(
                color = Color.Transparent,
                showDivider = false,
                defaultWindowInsetsPadding = !floating,
                mode = NavigationBarDisplayMode.IconAndText
            ) {
                items.forEachIndexed { index, item ->
                    val nativeIcon = when (item.title) {
                        "首页" -> MiuixIcons.Home
                        "设置" -> MiuixIcons.Settings
                        else -> item.icon
                    }
                    NativeNavigationBarItem(
                        selected = index == targetIndex,
                        onClick = { onSelected(index) },
                        icon = nativeIcon,
                        label = item.title
                    )
                }
            }
        }
    }
}

/** Main status card backed by the actual Miuix squircle Card. */
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
    val colors = NativeMiuixTheme.colorScheme

    NativeCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 24.dp,
        insideMargin = PaddingValues(20.dp),
        colors = NativeCardDefaults.defaultColors(
            color = colors.surfaceContainer,
            contentColor = colors.onSurfaceContainer
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (positive) BaiZeTokens.colors.success else BaiZeTokens.colors.warning)
            )
            Spacer(Modifier.width(8.dp))
            NativeText(device, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            NativeText(
                "  ·  $android",
                color = colors.onSurfaceContainer.copy(alpha = .62f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(20.dp))
        NativeText(
            "最近一次释放",
            color = colors.onSurfaceContainer.copy(alpha = .62f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        NativeText(
            releasedText,
            color = colors.onSurfaceContainer,
            style = BaiZeTokens.type.hero
        )

        Spacer(Modifier.height(16.dp))
        NativeCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            colors = NativeCardDefaults.defaultColors(
                color = colors.surfaceContainerHigh,
                contentColor = colors.onSurfaceContainer
            )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NativeIcon(
                    if (positive) Icons.Rounded.CheckCircle else Icons.Rounded.Refresh,
                    contentDescription = null,
                    tint = if (positive) BaiZeTokens.colors.success else colors.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    NativeText(statusTitle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    NativeText(
                        taskPhase,
                        color = colors.onSurfaceContainer.copy(alpha = .62f),
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Primary action backed by the real Miuix Button and squircle press feedback. */
@Composable
fun MiuixLiquidPrimaryButton(
    running: Boolean,
    scanReady: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
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

    NativeButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        enabled = enabled,
        cornerRadius = 18.dp,
        minHeight = 52.dp,
        colors = NativeButtonDefaults.buttonColorsPrimary(),
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
    ) {
        NativeIcon(icon, contentDescription = null, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(9.dp))
        NativeText(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
