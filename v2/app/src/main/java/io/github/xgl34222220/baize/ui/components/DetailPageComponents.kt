package io.github.xgl34222220.baize.ui.components

import io.github.xgl34222220.baize.ui.theme.baizeAnimateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
import io.github.xgl34222220.baize.ui.miuix.VideoIconButton
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
    /** 标题区下方的紧凑附加控件（如视图下拉），放在页头里而不是列表内容中。 */
    extra: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(modifier.fillMaxWidth()
        .then(if (statusBarInset) Modifier.statusBarsPadding() else Modifier)
        .padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            VideoIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack)
            Text(title, Modifier.weight(1f).padding(horizontal = 12.dp),
                fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { actions() }
        }
        if (subtitle.isNotBlank()) Text(subtitle, Modifier.padding(horizontal = 4.dp),
            fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (extra != null) extra()
    }
}

@Composable
fun DetailSectionHeader(title: String, subtitle: String = "", modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 8.dp)) {
        Text(title, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (subtitle.isNotBlank()) Text(subtitle, Modifier.padding(top = 2.dp),
            fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun DetailGlassPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val surface = BaiZeTokens.colors.surfaceRaised
    val dark = surface.luminance() < .3f
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)
        .glassSurface(color = surface, shape = shape, dark = dark)
        .padding(18.dp), content = content)
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
    modifier: Modifier = Modifier,
    showAction: Boolean = true
) {
    DetailGlassPanel(modifier) {
        Text(metricLabel, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BaiZeMetric(metric, Modifier.padding(top = 3.dp))
        if (running) BaiZePathText(phase, Modifier.padding(top = 6.dp, bottom = 14.dp), live = true)
        else DetailStatusText(phase, Modifier.padding(top = 6.dp, bottom = 14.dp))
        if (running) {
            BaiZeProgress(Modifier.padding(bottom = 12.dp))
            GlassActionButton("停止当前任务", onStop, modifier = Modifier.fillMaxWidth(), secondary = true)
        } else if (showAction) {
            val haptics = rememberBaiZeHaptics()
            GlassActionButton(if (ready) cleanLabel else scanLabel,
                if (ready) onClean else { { haptics.scanStart(); onScan() } },
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
fun DetailEmptyState(title: String, description: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Rounded.Search,
    actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BaiZeEmptyIllustration()
        Text(title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
        Text(description, fontSize = 12.sp, lineHeight = 17.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // 筛选把结果全部隐藏时，直接给出改回的入口，不让用户误以为“没有内容”。
        if (actionLabel != null && onAction != null) {
            androidx.compose.material3.TextButton(onClick = onAction,
                modifier = Modifier.testTag("detail-empty-action")) { Text(actionLabel) }
        }
    }
}

@Composable
fun DetailExpandableText(title: String, text: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp)
        .clip(RoundedCornerShape(24.dp)).background(BaiZeTokens.colors.surfaceRaised).baizeAnimateContentSize()) {
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
    accent: Color = MaterialTheme.colorScheme.primary,
    selected: Boolean? = null,
    selectionEnabled: Boolean = true,
    onToggle: () -> Unit = {},
    onOpen: (() -> Unit)? = null,
    onDetails: (() -> Unit)? = null
) {
    var showDetails by rememberSaveable(title, path) { mutableStateOf(false) }
    val shape = RoundedCornerShape(topStart = if (first) 24.dp else 0.dp, topEnd = if (first) 24.dp else 0.dp,
        bottomStart = if (last) 24.dp else 0.dp, bottomEnd = if (last) 24.dp else 0.dp)
    // 长按勾选（参考 SD Maid SE / HyperOS）：与圆形勾选同一入口、同一启用条件，不绕过任何锁定。
    // combinedClickable 在长按时自带系统长按触感反馈。
    val longPressSelect: (() -> Unit)? = if (selected != null && selectionEnabled) onToggle else null
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(shape)
        .background(BaiZeTokens.colors.surfaceRaised)
        .combinedClickable(onClickLabel = "查看完整路径与详情",
            onLongClickLabel = if (longPressSelect != null) (if (selected == true) "取消选择" else "选择") else null,
            onLongClick = longPressSelect) { if (onDetails != null) onDetails() else showDetails = true }) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            if (selected != null) BaiZeRoundCheck(if (selected) androidx.compose.ui.state.ToggleableState.On
                else androidx.compose.ui.state.ToggleableState.Off, onClick = onToggle,
                enabled = selectionEnabled, modifier = Modifier.size(48.dp))
            else Surface(shape = RoundedCornerShape(12.dp), color = accent.copy(alpha = .12f)) {
                Icon(icon, null, Modifier.padding(9.dp).size(22.dp), tint = accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, Modifier.weight(1f), fontSize = 14.5.sp, lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (value.isNotBlank()) Text(value, Modifier.widthIn(max = 94.dp), fontSize = 13.sp, lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"))
                }
                if (summary.isNotBlank()) Text(summary, fontSize = 12.sp, lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (path.isNotBlank()) BaiZePathText(path, Modifier.weight(1f))
                    else Spacer(Modifier.weight(1f))
                    Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f))
                }
            }
        }
        if (!last) HorizontalDivider(Modifier.padding(start = 61.dp, end = 14.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .055f))
    }
    if (showDetails) BaiZeDialog(
        onDismissRequest = { showDetails = false },
        title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium) },
        text = {
            SelectionContainer {
                Text(details, Modifier, fontSize = 13.sp, lineHeight = 20.sp)
            }
        },
        confirmButton = { BaiZeDialogButton(onClick = { showDetails = false }) { Text("完成") } },
        dismissButton = if (onOpen != null) ({ BaiZeDialogButton(onClick = { showDetails = false; onOpen() }) { Text("打开文件") } }) else null
    )
}
