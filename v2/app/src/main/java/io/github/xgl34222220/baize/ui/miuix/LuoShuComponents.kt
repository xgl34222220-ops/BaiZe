package io.github.xgl34222220.baize.ui.miuix

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/** Adapted from LuoShu's ACTIVE compact layout, icon system and settings hub at fe4df5f.
 * Not HomeScreenMiuix.kt: that legacy screen is no longer selected by HomeRoute.
 * These components are only used by the MIUIX routes. See docs/UI-LUOSHU-BASELINE.md.
 */
@Composable
internal fun LuoShuPageHeader(title: String, onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) LuoShuHeaderButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack)
        Text(title, Modifier.weight(1f), fontSize = if (onBack == null) 26.sp else 22.sp,
            lineHeight = if (onBack == null) 34.sp else 30.sp,
            fontWeight = if (onBack == null) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp), content = actions)
    }
}

@Composable
internal fun LuoShuHeaderButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Surface(Modifier.size(44.dp), shape = CircleShape, color = BaiZeTokens.colors.surfaceRaised,
            contentColor = MaterialTheme.colorScheme.primary, shadowElevation = 1.dp) {
            IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
                Icon(icon, label, Modifier.size(21.dp))
            }
        }
    }
}

@Composable
internal fun LuoShuSection(title: String, subtitle: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun LuoShuGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = BaiZeTokens.colors.surfaceRaised, tonalElevation = 0.dp) {
        Column(content = content)
    }
}

@Composable
internal fun LuoShuGroupDivider() {
    HorizontalDivider(Modifier.padding(start = 74.dp, end = 18.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .20f))
}

@Composable
internal fun LuoShuNavigationRow(icon: ImageVector, title: String, subtitle: String,
    onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(42.dp), shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 12.sp, lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .66f))
        }
    }
}

@Composable
internal fun LuoShuShortcut(title: String, subtitle: String, icon: ImageVector,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(24.dp),
        color = BaiZeTokens.colors.surfaceRaised, shadowElevation = 1.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(15.dp), color = BaiZeTokens.colors.surfaceOverlay) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
