package io.github.xgl34222220.baize.ui.home.miuix

import android.text.format.Formatter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.xgl34222220.baize.HomePresentation
import io.github.xgl34222220.baize.HomeScanSummary
import io.github.xgl34222220.baize.HomeScanSummaryStore
import io.github.xgl34222220.baize.HomeScanTile
import io.github.xgl34222220.baize.HomeTrashSummary
import io.github.xgl34222220.baize.OrdinaryFileTrash
import io.github.xgl34222220.baize.ui.components.BaiZeIconTile
import io.github.xgl34222220.baize.ui.components.baiZeLineIcon
import io.github.xgl34222220.baize.ui.components.rememberBaiZeHaptic
import io.github.xgl34222220.baize.ui.components.rememberMotionEnabled
import io.github.xgl34222220.baize.ui.miuix.LuoShuGroup
import io.github.xgl34222220.baize.ui.miuix.LuoShuNavigationRow
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 每次首页回到前台 +1，用来在从专项页 / 回收站返回后重新读取摘要（只读小文件，不扫描）。 */
@Composable
private fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    return tick
}

/** null 表示仍在读取。读取失败按「未扫描」处理，绝不填充估算值。 */
@Composable
internal fun rememberHomeScanSummary(): HomeScanSummary? {
    val context = LocalContext.current
    val revision by HomeScanSummaryStore.changes.collectAsState()
    val tick = rememberResumeTick()
    return produceState<HomeScanSummary?>(null, revision, tick) {
        value = withContext(Dispatchers.IO) { runCatching { HomeScanSummaryStore.read(context) }.getOrNull() } ?: HomeScanSummary()
    }.value
}

/** 只读回收站记录（与「记录 → 回收站」同一数据源）；不移动、不删除任何文件。 */
@Composable
internal fun rememberHomeTrashSummary(): HomeTrashSummary? {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    return produceState<HomeTrashSummary?>(null, tick) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val trash = OrdinaryFileTrash.forContext(context)
                val entries = trash.entries()
                val now = System.currentTimeMillis()
                HomeTrashSummary(entries.size, if (entries.isEmpty()) 0L else trash.occupiedBytes(),
                    entries.filter { it.expires > now }.minOfOrNull { it.expires } ?: 0L,
                    entries.count { it.expires <= now })
            }.getOrNull()
        } ?: HomeTrashSummary(0, 0L, 0L, 0)
    }.value
}

/** 首页「上次扫描」：四个专项工具最近一次结果，点格子进对应详情页，「查看全部」进清理 Tab。 */
@Composable
internal fun HomeLastScanSection(
    summary: HomeScanSummary?,
    nowMillis: Long,
    onChat: () -> Unit,
    onApk: () -> Unit,
    onLarge: () -> Unit,
    onDuplicates: () -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val format: (Long) -> String = { Formatter.formatFileSize(context, it) }
    val divider = scheme.onSurface.copy(alpha = .06f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("上次扫描", Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                color = scheme.onSurfaceVariant)
            Text(when {
                summary == null -> ""
                summary.scanned -> HomePresentation.relativeTime(summary.latestAtMillis, nowMillis)
                else -> "未扫描"
            }, fontSize = 13.sp, lineHeight = 18.sp, color = scheme.onSurfaceVariant)
        }
        LuoShuGroup {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                HomeScanTileCell(Icons.Rounded.Forum, "聊天媒体", summary, summary?.chat, format, onChat, Modifier.weight(1f))
                VerticalDivider(color = divider)
                HomeScanTileCell(Icons.Rounded.Inventory2, "安装包", summary, summary?.apk, format, onApk, Modifier.weight(1f))
            }
            HorizontalDivider(color = divider)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                HomeScanTileCell(Icons.Rounded.Folder, "大文件", summary, summary?.large, format, onLarge, Modifier.weight(1f))
                VerticalDivider(color = divider)
                HomeScanTileCell(Icons.Rounded.ContentCopy, "重复文件", summary, summary?.duplicates, format, onDuplicates, Modifier.weight(1f))
            }
            HorizontalDivider(color = divider)
            Row(Modifier.fillMaxWidth().clickable(onClickLabel = "查看全部清理项目", role = Role.Button, onClick = onViewAll)
                .heightIn(min = 52.dp).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(when {
                    summary == null -> "共可清理 —"
                    summary.scanned -> "共可清理 ${format(summary.totalBytes)}"
                    else -> "共可清理 未扫描"
                }, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("查看全部", fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = scheme.primary)
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = scheme.primary)
            }
        }
    }
}

@Composable
private fun HomeScanTileCell(
    icon: ImageVector,
    title: String,
    summary: HomeScanSummary?,
    tile: HomeScanTile?,
    format: (Long) -> String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val value = when {
        summary == null -> "—"
        tile == null -> "未扫描"
        tile.partial -> "≥ ${format(tile.bytes)}"
        else -> format(tile.bytes)
    }
    Row(modifier.fillMaxHeight().clickable(onClickLabel = "打开$title", role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        BaiZeIconTile(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(value, fontSize = if (tile == null) 13.sp else 16.sp, lineHeight = 22.sp,
                fontWeight = if (tile == null) FontWeight.Normal else FontWeight.SemiBold,
                color = if (tile == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 首页「回收站」入口：打开「记录」Tab 里的同一个回收站页面。 */
@Composable
internal fun HomeTrashCard(summary: HomeTrashSummary?, nowMillis: Long, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LuoShuGroup(modifier) {
        LuoShuNavigationRow(Icons.Rounded.Delete, "回收站",
            HomePresentation.trashSubtitle(summary, nowMillis) { Formatter.formatFileSize(context, it) }, onOpen)
    }
}

/**
 * 首页主按钮：同色系细微纵向明暗（顶部约亮 6%）、顶部 1px 内高光、柔和同色阴影；
 * 按压缩放 0.97 并收紧阴影。任务进行中在按钮内显示细进度条与百分比，点按即取消（沿用原停止动作）。
 */
@Composable
internal fun HomeScanButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    running: Boolean = false,
    runningLabel: String = "",
    progress: Float? = null
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(percent = 50)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = rememberMotionEnabled()
    val active = enabled || running
    val scale by animateFloatAsState(if (pressed && active && motion) .97f else 1f,
        tween(if (motion) 140 else 0), label = "homeScanPress")
    val elevation by animateDpAsState(when { !active -> 0.dp; pressed -> 4.dp; else -> 12.dp },
        tween(if (motion) 160 else 0), label = "homeScanShadow")
    val base = if (active) scheme.primary else BaiZeTokens.colors.surfaceOverlay
    val foreground = if (active) scheme.onPrimary else scheme.onSurfaceVariant.copy(alpha = .55f)
    val haptic = rememberBaiZeHaptic()
    val percent = progress?.let { (it.coerceIn(0f, 1f) * 100).toInt() }
    val text = if (running) runningLabel + (percent?.let { " $it%" } ?: "…") else label
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(elevation, shape, clip = false,
                ambientColor = scheme.primary.copy(alpha = .22f), spotColor = scheme.primary.copy(alpha = .30f))
            .clip(shape)
            .background(if (active) Brush.verticalGradient(listOf(lerp(base, Color.White, .06f), base))
                else Brush.verticalGradient(listOf(base, base)))
            .then(if (active) Modifier.border(1.dp,
                Brush.verticalGradient(0f to Color.White.copy(alpha = .26f), .32f to Color.Transparent), shape) else Modifier)
            .clickable(interaction, ripple(color = scheme.onPrimary), enabled = active, role = Role.Button,
                onClickLabel = if (running) "取消当前任务" else label) { haptic(); onClick() }
            .heightIn(min = 56.dp)
            .semantics { if (running) contentDescription = "$text，点按取消" },
        contentAlignment = Alignment.Center
    ) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = if (running) 12.dp else 15.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Icon(baiZeLineIcon(icon), null, Modifier.size(20.dp), tint = foreground)
                Spacer(Modifier.width(10.dp))
            }
            Text(text, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = foreground,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (running) {
                Spacer(Modifier.width(8.dp))
                Text("点按取消", fontSize = 12.sp, lineHeight = 16.sp, color = foreground.copy(alpha = .72f), maxLines = 1)
            }
        }
        if (running) {
            Box(Modifier.align(Alignment.BottomCenter).padding(start = 32.dp, end = 32.dp, bottom = 7.dp)
                .fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(foreground.copy(alpha = .22f))) {
                if (progress != null) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(50)).background(foreground.copy(alpha = .92f)))
                } else {
                    // 无总量时的不确定进度：一段 30% 宽的亮条往返移动；关闭动效时静止。
                    val sweep = if (motion) rememberInfiniteTransition(label = "homeScanSweep").animateFloat(0f, 1f,
                        infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "homeScanSweepX").value else 0f
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val segment = maxWidth * .3f
                        Box(Modifier.offset(x = (maxWidth - segment) * sweep).width(segment).fillMaxHeight()
                            .clip(RoundedCornerShape(50)).background(foreground.copy(alpha = .92f)))
                    }
                }
            }
        }
    }
}
