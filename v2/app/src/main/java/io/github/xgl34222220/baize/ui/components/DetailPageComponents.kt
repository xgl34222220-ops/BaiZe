package io.github.xgl34222220.baize.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
import io.github.xgl34222220.baize.ui.miuix.glassSurface
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/** Compact native navigation, shared by all secondary routes. */
@Composable
fun DetailPageHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    statusBarInset: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(modifier.fillMaxWidth()
        .then(if (statusBarInset) Modifier.statusBarsPadding() else Modifier)
        .padding(horizontal = 16.dp).padding(bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", Modifier.size(23.dp),
                    tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(title, Modifier.weight(1f).padding(horizontal = 8.dp),
                fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface)
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { actions() }
        }
        if (subtitle.isNotBlank()) Text(subtitle, Modifier.padding(horizontal = 4.dp),
            fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun DetailSectionHeader(title: String, subtitle: String = "", modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 18.dp, bottom = 9.dp)) {
        Text(title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface)
        if (subtitle.isNotBlank()) Text(subtitle, Modifier.padding(top = 3.dp),
            fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun DetailGlassPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = BaiZeTokens.corners.large
    val surface = BaiZeTokens.colors.surfaceRaised
    val dark = surface.luminance() < .3f
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp)
        .glassSurface(color = surface, shape = shape, dark = dark)
        .padding(16.dp), content = content)
}

@Composable
fun DetailTaskCard(
    metric: String,
    metricLabel: String,
    phase: String,
    running: Boolean,
    ready: Boolean,
    scanEnabled: Boolean,
    cleanEnabled: Boolean,
    onScan: () -> Unit,
    onClean: () -> Unit,
    onStop: () -> Unit,
    onReconnect: () -> Unit,
    scanLabel: String = "开始扫描",
    cleanLabel: String = "清理这些项目",
    modifier: Modifier = Modifier
) {
    DetailGlassPanel(modifier) {
        Text(metricLabel, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(metric, Modifier.padding(top = 3.dp), fontSize = if (metric.any { it.isDigit() }) 26.sp else 22.sp,
            lineHeight = 32.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        DetailStatusText(phase, Modifier.padding(top = 6.dp, bottom = 14.dp))
        if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp))
            GlassActionButton("停止当前任务", onStop, modifier = Modifier.fillMaxWidth(), secondary = true)
        } else {
            GlassActionButton(if (ready) cleanLabel else scanLabel,
                if (ready) onClean else onScan,
                enabled = if (ready) cleanEnabled else scanEnabled,
                modifier = Modifier.fillMaxWidth())
            if (ready || !scanEnabled || !cleanEnabled) {
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.End) {
                    if (!scanEnabled || !cleanEnabled) TextButton(onClick = onReconnect) { Text("重新连接", fontSize = 13.sp) }
                    if (ready) TextButton(onClick = onScan, enabled = scanEnabled) { Text("重新扫描", fontSize = 13.sp) }
                }
            }
        }
    }
}

/** Long task output remains accessible without making the summary occupy the screen. */
@Composable
fun DetailStatusText(text: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflow by remember(text) { mutableStateOf(false) }
    Column(modifier) {
        Text(text, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow })
        if (overflow || expanded) TextButton(onClick = { expanded = !expanded },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
            Text(if (expanded) "收起状态" else "查看完整状态", fontSize = 12.sp)
        }
    }
}

@Composable
fun DetailEmptyState(title: String, description: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Rounded.Search) {
    Row(modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.Top) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = .075f)) {
            Icon(icon, null, Modifier.padding(8.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(description, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DetailExpandableText(title: String, text: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp)
        .clip(RoundedCornerShape(16.dp)).background(BaiZeTokens.colors.surfaceRaised.copy(alpha = .78f))) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 52.dp).padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Icon(if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                if (expanded) "收起" else "展开", Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) SelectionContainer {
            Text(text, Modifier.padding(start = 15.dp, end = 15.dp, bottom = 15.dp),
                fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Adjacent lazy rows form one section; full paths and measurement notes open on tap. */
@Composable
fun DetailResultRow(
    title: String,
    value: String,
    summary: String,
    path: String,
    details: String,
    icon: ImageVector,
    first: Boolean,
    last: Boolean,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    var showDetails by rememberSaveable(title, path) { mutableStateOf(false) }
    val shape = RoundedCornerShape(topStart = if (first) 18.dp else 0.dp, topEnd = if (first) 18.dp else 0.dp,
        bottomStart = if (last) 18.dp else 0.dp, bottomEnd = if (last) 18.dp else 0.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(shape)
        .background(BaiZeTokens.colors.surfaceRaised.copy(alpha = .92f))
        .clickable(onClickLabel = "查看完整路径与详情") { showDetails = true }) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Surface(shape = RoundedCornerShape(11.dp), color = accent.copy(alpha = .08f)) {
                Icon(icon, null, Modifier.padding(8.dp).size(20.dp), tint = accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, Modifier.weight(1f), fontSize = 14.5.sp, lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (value.isNotBlank()) Text(value, Modifier.widthIn(max = 94.dp), fontSize = 12.sp, lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                }
                if (summary.isNotBlank()) Text(summary, fontSize = 12.sp, lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (path.isNotBlank()) Text(path, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else Spacer(Modifier.weight(1f))
                    Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f))
                }
            }
        }
        if (!last) HorizontalDivider(Modifier.padding(start = 61.dp, end = 14.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .055f))
    }
    if (showDetails) AlertDialog(
        onDismissRequest = { showDetails = false },
        title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium) },
        text = {
            SelectionContainer {
                Text(details, Modifier.verticalScroll(rememberScrollState()), fontSize = 13.sp, lineHeight = 20.sp)
            }
        },
        confirmButton = { TextButton(onClick = { showDetails = false }) { Text("完成") } }
    )
}
