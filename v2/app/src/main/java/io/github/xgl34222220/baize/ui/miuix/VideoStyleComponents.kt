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
 * 两套外观共用同一信息架构和页面骨架，只替换组件皮肤。
 * MIUIX 更轻、更圆润、更接近 HyperOS 系统面板；Material 3 使用标准色彩容器与控件层级。
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
fun VideoTopBar(
    title: String,
    subtitle: String? = null,
    start: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    val material = LocalVideoSkin.current == VideoSkin.MATERIAL3
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = if (material) 72.dp else 68.dp)
            .padding(horizontal = if (material) 16.dp else 12.dp, vertical = 8.dp)
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
                .padding(horizontal = 76.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontSize = if (material) 22.sp else 20.sp,
                lineHeight = if (material) 27.sp else 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = if (material) 11.sp else 10.sp,
                    lineHeight = if (material) 14.sp else 13.sp,
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
    val material = LocalVideoSkin.current == VideoSkin.MATERIAL3
    val shape = if (material) CircleShape else RoundedCornerShape(14.dp)
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val container = when {
        material && primary -> MaterialTheme.colorScheme.primary
        material -> MaterialTheme.colorScheme.surfaceContainerHigh
        primary -> MaterialTheme.colorScheme.primaryContainer
        else -> BaiZeTokens.colors.surfaceRaised
    }
    val content = when {
        material && primary -> MaterialTheme.colorScheme.onPrimary
        material -> MaterialTheme.colorScheme.onSurfaceVariant
        primary -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = Modifier
            .size(if (material) 42.dp else 40.dp)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = container,
        contentColor = content,
        border = if (material) null else BorderStroke(
            1.dp,
            Color.White.copy(alpha = if (dark) .10f else .62f)
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.size(if (material) 21.dp else 20.dp),
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
    val material = LocalVideoSkin.current == VideoSkin.MATERIAL3
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = if (material) 16.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (material) 8.dp else 7.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val shape = RoundedCornerShape(if (material) 18.dp else 12.dp)
            val container = when {
                material && selected -> MaterialTheme.colorScheme.primary
                material -> MaterialTheme.colorScheme.surfaceContainerHigh
                selected -> MaterialTheme.colorScheme.primaryContainer
                else -> BaiZeTokens.colors.surfaceRaised
            }
            val content = when {
                material && selected -> MaterialTheme.colorScheme.onPrimary
                material -> MaterialTheme.colorScheme.onSurfaceVariant
                selected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Surface(
                modifier = Modifier
                    .height(if (material) 38.dp else 34.dp)
                    .clip(shape)
                    .clickable { onSelected(index) },
                shape = shape,
                color = container,
                contentColor = content,
                border = if (material) null else BorderStroke(
                    1.dp,
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .28f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)
                )
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = if (material) 18.dp else 15.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = content,
                        fontSize = if (material) 13.sp else 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
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
    val material = LocalVideoSkin.current == VideoSkin.MATERIAL3
    Column(modifier.padding(horizontal = if (material) 20.dp else 16.dp)) {
        Text(
            text = title,
            fontSize = if (material) 18.sp else 16.sp,
            lineHeight = if (material) 23.sp else 21.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(1.dp))
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = if (material) 11.sp else 10.sp,
                lineHeight = if (material) 15.sp else 14.sp
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
    val material = LocalVideoSkin.current == VideoSkin.MATERIAL3
    val resolvedColor = containerColor ?: if (material) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        BaiZeTokens.colors.surfaceRaised
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(if (material) 20.dp else 22.dp),
        color = resolvedColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = if (material) null else BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = .24f)
        ),
        shadowElevation = if (material) 1.dp else 0.dp
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
    val material = LocalVideoSkin.current == VideoSkin.MATERIAL3
    val background = when {
        material && primary -> MaterialTheme.colorScheme.primaryContainer
        material -> MaterialTheme.colorScheme.surfaceContainerHighest
        primary -> MaterialTheme.colorScheme.primary.copy(alpha = .11f)
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = .055f)
    }
    val tint = when {
        material && primary -> MaterialTheme.colorScheme.onPrimaryContainer
        material -> MaterialTheme.colorScheme.onSurfaceVariant
        primary -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .size(if (material) 42.dp else 40.dp)
            .clip(RoundedCornerShape(if (material) 14.dp else 13.dp))
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(if (material) 21.dp else 20.dp),
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
    val material = LocalVideoSkin.current == VideoSkin.MATERIAL3
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick)
                else Modifier
            )
            .padding(
                horizontal = if (material) 18.dp else 15.dp,
                vertical = if (material) 14.dp else 12.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VideoLeadingIcon(icon = icon, primary = enabled)
        Spacer(Modifier.width(if (material) 14.dp else 12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = if (material) 15.sp else 14.sp,
                lineHeight = if (material) 20.sp else 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .5f),
                fontSize = if (material) 11.sp else 10.sp,
                lineHeight = if (material) 15.sp else 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!value.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = value,
                color = if (material) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary,
                fontSize = if (material) 12.sp else 11.sp,
                lineHeight = if (material) 16.sp else 15.sp,
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
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
}

@Composable
fun VideoDivider(start: Int = 67) {
    val material = LocalVideoSkin.current == VideoSkin.MATERIAL3
    HorizontalDivider(
        modifier = Modifier.padding(start = if (material) (start + 7).dp else start.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (material) .55f else .34f)
    )
}

@Composable
fun VideoMetricTile(
    label: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier
) {
    val material = LocalVideoSkin.current == VideoSkin.MATERIAL3
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(if (material) 16.dp else 17.dp),
        color = if (material) MaterialTheme.colorScheme.surfaceContainer else BaiZeTokens.colors.surfaceOverlay,
        border = if (material) null else BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = .18f)
        )
    ) {
        Column(Modifier.padding(horizontal = if (material) 15.dp else 13.dp, vertical = if (material) 13.dp else 11.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = if (material) 11.sp else 10.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                value,
                fontSize = if (material) 18.sp else 17.sp,
                lineHeight = if (material) 22.sp else 21.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = if (material) 10.sp else 9.sp,
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
    val material = LocalVideoSkin.current == VideoSkin.MATERIAL3
    val shape = RoundedCornerShape(if (material) 18.dp else 19.dp)
    val container = when {
        material && primary -> MaterialTheme.colorScheme.primaryContainer
        material -> MaterialTheme.colorScheme.secondaryContainer
        primary -> MaterialTheme.colorScheme.primaryContainer
        else -> BaiZeTokens.colors.surfaceRaised
    }
    val content = when {
        material && primary -> MaterialTheme.colorScheme.onPrimaryContainer
        material -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = modifier
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = container,
        contentColor = content,
        border = if (material) null else BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = .25f)
        )
    ) {
        Column(Modifier.padding(if (material) 16.dp else 14.dp)) {
            VideoLeadingIcon(icon = icon, primary = true)
            Spacer(Modifier.height(if (material) 12.dp else 10.dp))
            Text(title, color = content, fontSize = if (material) 15.sp else 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = content.copy(alpha = .72f),
                fontSize = if (material) 11.sp else 10.sp,
                lineHeight = if (material) 15.sp else 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
