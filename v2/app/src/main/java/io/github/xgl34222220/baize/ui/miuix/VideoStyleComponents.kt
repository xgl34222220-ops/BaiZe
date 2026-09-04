package io.github.xgl34222220.baize.ui.miuix

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/**
 * 白泽共享组件。
 * shadcn 负责“怎么组织”，MIUIX / HyperOS 负责“长什么样”：更圆润、更柔和、
 * 更少硬边框，利用动态色、浅阴影和半透明层级建立原生移动端质感。
 */
enum class VideoSkin {
    MIUIX,
    MATERIAL3
}

val LocalVideoSkin = staticCompositionLocalOf { VideoSkin.MIUIX }

@Composable
fun ProvideVideoSkin(
    skin: VideoSkin,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalVideoSkin provides skin, content = content)
}

@Composable
private fun softBorder(alpha: Float = .32f): BorderStroke {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    return BorderStroke(
        1.dp,
        if (dark) Color.White.copy(alpha = alpha * .45f)
        else Color.White.copy(alpha = (.55f + alpha).coerceAtMost(.88f))
    )
}

@Composable
fun VideoTopBar(
    title: String,
    subtitle: String? = null,
    start: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 70.dp)
            .padding(horizontal = BaiZeTokens.spacing.pageHorizontal, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = start
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 74.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = BaiZeTokens.type.headline.copy(fontSize = 21.sp, lineHeight = 26.sp),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .82f),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = actions
        )
    }
}

@Composable
fun VideoIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    primary: Boolean = false
) {
    val shape = RoundedCornerShape(14.dp)
    val container = if (primary) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        BaiZeTokens.colors.surfaceRaised.copy(alpha = .94f)
    }
    val content = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = Modifier
            .size(42.dp)
            .shadow(2.dp, shape, clip = false)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = container,
        contentColor = content,
        border = softBorder(.28f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, description, modifier = Modifier.size(20.dp), tint = content)
        }
    }
}

@Composable
fun VideoTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = BaiZeTokens.spacing.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val shape = RoundedCornerShape(15.dp)
            Surface(
                modifier = Modifier
                    .height(38.dp)
                    .clip(shape)
                    .clickable { onSelected(index) },
                shape = shape,
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    BaiZeTokens.colors.surfaceRaised.copy(alpha = .86f)
                },
                contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .16f)) else softBorder(.18f),
                shadowElevation = if (selected) 2.dp else 0.dp
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun VideoSectionTitle(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(horizontal = BaiZeTokens.spacing.pageHorizontal + 2.dp)) {
        Text(title, style = BaiZeTokens.type.title, color = MaterialTheme.colorScheme.onBackground)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .78f),
                style = BaiZeTokens.type.caption
            )
        }
    }
}

@Composable
fun VideoCard(
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    contentPadding: Int = 0,
    content: @Composable ColumnScope.() -> Unit
) {
    val resolvedColor = containerColor ?: BaiZeTokens.colors.surfaceRaised.copy(alpha = .96f)
    Surface(
        modifier = modifier.shadow(1.dp, BaiZeTokens.corners.large, clip = false),
        shape = BaiZeTokens.corners.large,
        color = resolvedColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = softBorder(.20f),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = if (contentPadding > 0) Modifier.padding(contentPadding.dp) else Modifier,
            content = content
        )
    }
}

@Composable
fun VideoLeadingIcon(
    icon: ImageVector,
    primary: Boolean = true,
    modifier: Modifier = Modifier
) {
    val background = if (primary) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = .78f)
    } else {
        BaiZeTokens.colors.surfaceOverlay
    }
    val tint = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, modifier = Modifier.size(20.dp), tint = tint)
    }
}

@Composable
fun VideoListRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VideoLeadingIcon(icon, primary = enabled)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = BaiZeTokens.type.body.copy(fontWeight = FontWeight.SemiBold),
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) .78f else .40f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!value.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                value,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun VideoSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    VideoListRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}

@Composable
fun VideoDivider(start: Int = 67) {
    HorizontalDivider(
        modifier = Modifier.padding(start = start.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .24f)
    )
}

@Composable
fun VideoMetricTile(
    label: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier,
        shape = shape,
        color = BaiZeTokens.colors.surfaceOverlay.copy(alpha = .90f),
        border = softBorder(.14f)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .78f), style = BaiZeTokens.type.caption)
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                fontSize = 19.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .68f),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun VideoActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false
) {
    val shape = RoundedCornerShape(21.dp)
    val container = if (primary) MaterialTheme.colorScheme.primaryContainer else BaiZeTokens.colors.surfaceRaised.copy(alpha = .96f)
    val content = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier
            .shadow(if (primary) 3.dp else 1.dp, shape, clip = false)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = container,
        contentColor = content,
        border = if (primary) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .14f)) else softBorder(.18f)
    ) {
        Column(Modifier.padding(15.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(
                        if (primary) MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(12.dp))
            Text(title, color = content, style = BaiZeTokens.type.body.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                color = if (primary) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .76f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .76f),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
