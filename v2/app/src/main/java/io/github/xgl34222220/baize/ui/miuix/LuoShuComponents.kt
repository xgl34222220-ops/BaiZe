package io.github.xgl34222220.baize.ui.miuix

import io.github.xgl34222220.baize.ui.components.BaiZeIconTile
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/**
 * Shared LuoShu component hierarchy used by all BaiZe MIUIX routes.
 * Keep geometry aligned with LuoShu's active compact layout and settings hub.
 */
@Composable
internal fun LuoShuPageHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (onBack != null) LuoShuHeaderButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack)
        Text(
            title,
            Modifier.weight(1f),
            fontSize = if (onBack == null) 26.sp else 20.sp,
            lineHeight = if (onBack == null) 34.sp else 30.sp,
            fontWeight = if (onBack == null) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = actions
        )
    }
}

@Composable
internal fun LuoShuHeaderButton(icon: ImageVector, label: String, onClick: () -> Unit) =
    io.github.xgl34222220.baize.ui.components.BaiZeFloatingIconButton(icon, label, onClick)

/** 分组标题：卡片上方的小号灰字；副标题为一行说明。 */
@Composable
internal fun LuoShuSection(title: String, subtitle: String = "") {
    Column(Modifier.padding(start = 4.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun LuoShuGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = BaiZeTokens.colors.surfaceRaised,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(content = content)
    }
}

@Composable
internal fun LuoShuGroupDivider() {
    HorizontalDivider(
        Modifier.padding(start = 70.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)
    )
}

@Composable
internal fun LuoShuNavigationRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LuoShuIconTile(icon)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Rounded.ChevronRight,
                null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .66f)
            )
        }
    }
}

@Composable
internal fun LuoShuSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .alpha(if (enabled) 1f else .45f)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LuoShuIconTile(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    // 与 LuoShuNavigationRow 使用同一副标题字号，设置页开关行与跳转行不再一大一小。
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        val haptic = io.github.xgl34222220.baize.ui.components.rememberBaiZeHaptic()
        Switch(
            checked = checked,
            onCheckedChange = { haptic(); onCheckedChange(it) },
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title }
        )
    }
}

@Composable
internal fun LuoShuShortcut(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: io.github.xgl34222220.baize.ui.theme.BaiZeTone = io.github.xgl34222220.baize.ui.theme.BaiZeTones.blue,
    actionLabel: String? = null
) = io.github.xgl34222220.baize.ui.components.BaiZeToolTile(icon, tone, title, subtitle, onClick, modifier, actionLabel)

@Composable
private fun LuoShuIconTile(icon: ImageVector) = BaiZeIconTile(icon)
