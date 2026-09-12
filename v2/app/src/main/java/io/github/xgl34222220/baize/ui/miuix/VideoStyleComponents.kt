package io.github.xgl34222220.baize.ui.miuix

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/** Shared information structure, with native controls and one quiet surface hierarchy. */
enum class VideoSkin { MIUIX, MATERIAL3 }
val LocalVideoSkin = staticCompositionLocalOf { VideoSkin.MIUIX }

@Composable
fun ProvideVideoSkin(skin: VideoSkin, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalVideoSkin provides skin, content = content)
}

@Composable
fun VideoTopBar(
    title: String,
    subtitle: String? = null,
    start: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        start()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

@Composable
fun VideoIconButton(icon: ImageVector, description: String, onClick: () -> Unit, primary: Boolean = false) {
    Surface(
        modifier = Modifier.size(48.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        shape = CircleShape,
        color = if (primary) MaterialTheme.colorScheme.primaryContainer else BaiZeTokens.colors.surfaceRaised,
        contentColor = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(22.dp))
        }
    }
}

@Composable
fun VideoTabs(labels: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val shape = RoundedCornerShape(16.dp)
            Surface(
                modifier = Modifier.heightIn(min = 48.dp).clip(shape)
                    .selectable(selected, role = Role.Tab) { onSelected(index) },
                shape = shape,
                color = if (selected) MaterialTheme.colorScheme.primary else BaiZeTokens.colors.surfaceRaised,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun VideoSectionTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 22.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
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
    Surface(modifier = modifier, shape = RoundedCornerShape(24.dp),
        color = containerColor ?: BaiZeTokens.colors.surfaceRaised,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 1.dp) {
        Column(Modifier.padding(contentPadding.dp), content = content)
    }
}

@Composable
fun VideoLeadingIcon(icon: ImageVector, primary: Boolean = true, modifier: Modifier = Modifier) {
    Box(modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
        .background(if (primary) MaterialTheme.colorScheme.primary.copy(alpha = .10f)
        else BaiZeTokens.colors.surfaceOverlay), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(23.dp),
            tint = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
        modifier = modifier.fillMaxWidth().heightIn(min = 80.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VideoLeadingIcon(icon, primary = enabled)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .45f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .5f),
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!value.isNullOrBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(value, modifier = Modifier.widthIn(max = 88.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        } else if (onClick != null) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f))
        }
    }
}

@Composable
fun VideoSwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean,
    onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    VideoListRow(icon, title, subtitle, modifier,
        trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange) })
}

@Composable
fun VideoDivider(start: Int = 76) {
    HorizontalDivider(Modifier.padding(start = start.dp, end = 18.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .25f))
}

@Composable
fun VideoMetricTile(label: String, value: String, caption: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp), color = BaiZeTokens.colors.surfaceRaised) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (caption.isNotBlank()) Text(caption, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun VideoActionTile(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, primary: Boolean = false) {
    val shape = RoundedCornerShape(24.dp)
    Surface(modifier = modifier.clip(shape).clickable(role = Role.Button, onClick = onClick),
        shape = shape,
        color = if (primary) BaiZeTokens.colors.surfaceOverlay else BaiZeTokens.colors.surfaceRaised) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                VideoLeadingIcon(icon)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun VideoStatusPill(text: String, positive: Boolean = true, modifier: Modifier = Modifier) {
    val color = if (positive) BaiZeTokens.colors.success else BaiZeTokens.colors.warning
    Surface(modifier, shape = CircleShape, color = color.copy(alpha = .09f)) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Text(text, color = color, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun VideoEmptyState(icon: ImageVector, title: String, description: String, modifier: Modifier = Modifier,
    actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(64.dp).background(BaiZeTokens.colors.surfaceOverlay, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (!actionLabel.isNullOrBlank() && onAction != null) Button(onClick = onAction) { Text(actionLabel) }
    }
}
