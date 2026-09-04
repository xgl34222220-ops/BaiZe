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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/**
 * 白泽主界面共享组件。
 *
 * 设计语言参考 shadcn/ui：统一语义、低噪音中性容器、清晰 1dp 边界、紧凑圆角、
 * Tabs/Card/Item/Switch/Action 使用一致的组合接口。Android 继续使用 Compose 原生
 * 交互和无障碍语义，不照搬 Web DOM。
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
private fun componentBorder(alpha: Float = .72f): BorderStroke = BorderStroke(
    1.dp,
    MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha)
)

@Composable
fun VideoTopBar(
    title: String,
    subtitle: String? = null,
    start: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 64.dp)
            .padding(horizontal = BaiZeTokens.spacing.pageHorizontal, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = start
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = BaiZeTokens.type.headline,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = BaiZeTokens.type.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(
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
    val shape = RoundedCornerShape(10.dp)
    val container = if (primary) MaterialTheme.colorScheme.primary else BaiZeTokens.colors.surfaceRaised
    val content = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier
            .size(40.dp)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = container,
        contentColor = content,
        border = if (primary) null else componentBorder()
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.size(19.dp),
                tint = content
            )
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
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = BaiZeTokens.spacing.pageHorizontal),
        shape = RoundedCornerShape(11.dp),
        color = BaiZeTokens.colors.surfaceOverlay,
        border = componentBorder(.45f)
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val shape = RoundedCornerShape(8.dp)
                Surface(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(shape)
                        .clickable { onSelected(index) },
                    shape = shape,
                    color = if (selected) BaiZeTokens.colors.surfaceRaised else Color.Transparent,
                    contentColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    border = if (selected) componentBorder(.58f) else null,
                    shadowElevation = if (selected) 1.dp else 0.dp
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                        )
                    }
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
    Column(modifier.padding(horizontal = BaiZeTokens.spacing.pageHorizontal)) {
        Text(
            text = title,
            style = BaiZeTokens.type.title,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val resolvedColor = containerColor ?: BaiZeTokens.colors.surfaceRaised
    Surface(
        modifier = modifier,
        shape = BaiZeTokens.corners.large,
        color = resolvedColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = componentBorder(.62f),
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
        MaterialTheme.colorScheme.primary.copy(alpha = .10f)
    } else {
        BaiZeTokens.colors.surfaceOverlay
    }
    val tint = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = tint
        )
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
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VideoLeadingIcon(icon = icon, primary = enabled)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = BaiZeTokens.type.body.copy(fontWeight = FontWeight.SemiBold),
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .5f),
                    style = BaiZeTokens.type.caption,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!value.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
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
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
}

@Composable
fun VideoDivider(start: Int = 60) {
    HorizontalDivider(
        modifier = Modifier.padding(start = start.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)
    )
}

@Composable
fun VideoMetricTile(
    label: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = BaiZeTokens.corners.medium,
        color = BaiZeTokens.colors.surfaceOverlay,
        border = componentBorder(.48f)
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = BaiZeTokens.type.caption)
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val shape = BaiZeTokens.corners.large
    val container = if (primary) MaterialTheme.colorScheme.primary else BaiZeTokens.colors.surfaceRaised
    val content = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = container,
        contentColor = content,
        border = if (primary) null else componentBorder(.62f)
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (primary) content.copy(alpha = .14f) else MaterialTheme.colorScheme.primary.copy(alpha = .10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (primary) content else MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(title, color = content, style = BaiZeTokens.type.body.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = content.copy(alpha = .72f),
                style = BaiZeTokens.type.caption,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
