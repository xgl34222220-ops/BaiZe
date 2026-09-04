package io.github.xgl34222220.baize.ui.clean.miuix

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.FolderDelete
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.clean.CleanCategoryId
import io.github.xgl34222220.baize.ui.clean.CleanCategoryUiItem
import io.github.xgl34222220.baize.ui.clean.CleanScheduleMode
import io.github.xgl34222220.baize.ui.clean.CleanUiActions
import io.github.xgl34222220.baize.ui.clean.CleanUiState
import io.github.xgl34222220.baize.ui.clean.IntValueDialog
import io.github.xgl34222220.baize.ui.clean.TimeValueDialog
import io.github.xgl34222220.baize.ui.clean.formatMinutes
import io.github.xgl34222220.baize.ui.miuix.LuoShuPageHeader
import io.github.xgl34222220.baize.ui.miuix.LuoShuSectionTitle
import io.github.xgl34222220.baize.ui.miuix.VideoCard
import io.github.xgl34222220.baize.ui.miuix.VideoDivider
import io.github.xgl34222220.baize.ui.miuix.VideoLeadingIcon
import io.github.xgl34222220.baize.ui.miuix.VideoListRow
import io.github.xgl34222220.baize.ui.miuix.VideoSwitchRow
import io.github.xgl34222220.baize.ui.miuix.VideoTabs
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

private val cleanIntervals = listOf(30, 60, 180, 360, 720, 1_440, 4_320, 10_080, 43_200)

@Composable
fun VideoCleanScreenMiuix(
    state: CleanUiState,
    actions: CleanUiActions,
    expandedCategory: String,
    onExpandedCategoryChanged: (String) -> Unit
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val pagePadding = 16.dp
    var showDailyTimeDialog by remember { mutableStateOf(false) }
    var showDailyGraceDialog by remember { mutableStateOf(false) }
    var showApkRetentionDialog by remember { mutableStateOf(false) }

    if (showDailyTimeDialog) {
        TimeValueDialog(
            initialHour = state.dailyHour,
            initialMinute = state.dailyMinute,
            onDismiss = { showDailyTimeDialog = false },
            onConfirm = actions.onDailyTimeChanged
        )
    }
    if (showDailyGraceDialog) {
        IntValueDialog(
            title = "补做窗口",
            description = "固定执行时间到达后，如果系统条件暂时不满足，会在这个窗口内继续等待。",
            initialValue = state.dailyGraceMinutes,
            range = 15..720,
            suffix = "分钟",
            onDismiss = { showDailyGraceDialog = false },
            onConfirm = actions.onDailyGraceChanged
        )
    }
    if (showApkRetentionDialog) {
        IntValueDialog(
            title = "安装包保留时间",
            description = "只影响后台自动清理。手动安装包扫描仍显示全部结果；0 天表示扫描到后即可进入自动清理范围。",
            initialValue = state.apkPackageDays,
            range = 0..365,
            suffix = "天",
            onDismiss = { showApkRetentionDialog = false },
            onConfirm = actions.onApkPackageDaysChanged
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            LuoShuPageHeader(
                eyebrow = "CLEAN CENTER",
                title = "清理",
                subtitle = state.scheduleSummary,
                actionIcon = Icons.Rounded.Search,
                actionDescription = "安全扫描",
                onAction = actions.onScan
            )
        }

        item {
            CleanStatusHero(
                state = state,
                onAutomaticCleaningChanged = actions.onAutomaticCleaningChanged
            )
        }

        item {
            LuoShuSectionTitle(
                eyebrow = "AUTOMATION",
                title = "自动计划",
                subtitle = "选择调度方式和后台清理策略"
            )
        }
        item {
            VideoCard(
                modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth(),
                contentPadding = 15
            ) {
                Text(
                    text = "执行方式",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                VideoTabs(
                    labels = CleanScheduleMode.entries.map { it.title },
                    selectedIndex = CleanScheduleMode.entries.indexOf(state.scheduleMode).coerceAtLeast(0),
                    onSelected = { index -> actions.onScheduleModeChanged(CleanScheduleMode.entries[index]) },
                    modifier = Modifier.padding(horizontal = 0.dp)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    state.scheduleMode.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = BaiZeTokens.type.caption
                )

                if (state.scheduleMode == CleanScheduleMode.FIXED_DAILY) {
                    Spacer(Modifier.height(10.dp))
                    VideoDivider(start = 0)
                    VideoListRow(
                        icon = Icons.Rounded.CalendarMonth,
                        title = "执行时间",
                        subtitle = "每天固定执行已启用类别",
                        value = state.dailyTimeText,
                        onClick = { showDailyTimeDialog = true }
                    )
                    VideoDivider(start = 0)
                    VideoListRow(
                        icon = Icons.Rounded.AutoAwesome,
                        title = "补做窗口",
                        subtitle = "条件暂时不满足时继续等待",
                        value = formatMinutes(state.dailyGraceMinutes),
                        onClick = { showDailyGraceDialog = true }
                    )
                }

                VideoDivider(start = 0)
                VideoSwitchRow(
                    icon = Icons.Rounded.InstallMobile,
                    title = "过期安装包",
                    subtitle = "后台自动处理超过保留时间的安装包",
                    checked = state.apkPackagesEnabled,
                    onCheckedChange = actions.onApkPackagesChanged
                )
                if (state.apkPackagesEnabled) {
                    VideoDivider(start = 0)
                    VideoListRow(
                        icon = Icons.Rounded.CalendarMonth,
                        title = "安装包保留时间",
                        subtitle = "0 天表示扫描到后即可进入自动清理范围",
                        value = apkRetentionText(state.apkPackageDays),
                        onClick = { showApkRetentionDialog = true }
                    )
                }
            }
        }

        item {
            LuoShuSectionTitle(
                eyebrow = "CATEGORIES",
                title = "清理类别",
                subtitle = "每个类别独立开关；非每日模式可单独调整周期"
            )
        }
        item {
            VideoCard(
                modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth(),
                contentPadding = 0
            ) {
                state.categories.forEachIndexed { index, category ->
                    CategoryRow(
                        item = category,
                        expanded = expandedCategory == category.id.name,
                        dailyMode = state.scheduleMode == CleanScheduleMode.FIXED_DAILY && category.id != CleanCategoryId.ORGANIZE,
                        onEnabledChanged = { actions.onCategoryEnabledChanged(category.id, it) },
                        onExpandedChanged = {
                            onExpandedCategoryChanged(if (expandedCategory == category.id.name) "" else category.id.name)
                        },
                        onIntervalChanged = { actions.onCategoryIntervalChanged(category.id, it) }
                    )
                    if (index != state.categories.lastIndex) VideoDivider()
                }
            }
        }

        item {
            LuoShuSectionTitle(
                eyebrow = "TOOLS",
                title = "专项工具",
                subtitle = "扫描、归类、深度清理和规则检查"
            )
        }
        item {
            VideoCard(
                modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth(),
                contentPadding = 0
            ) {
                ToolRow(Icons.Rounded.Search, "智能扫描", "只扫描并生成可清理快照", "扫描", actions.onScan)
                VideoDivider()
                ToolRow(Icons.Rounded.CleaningServices, "应用缓存", "立即处理应用缓存", "清理", actions.onInstantCache)
                VideoDivider()
                ToolRow(Icons.Rounded.FolderCopy, "文件归类", "整理下载、附件与导出文件", "整理", actions.onFileOrganizer)
                VideoDivider()
                ToolRow(Icons.Rounded.Security, "深度清理", "继续受风险上限、白名单与保护规则约束", "进入", actions.onDeepClean)
                VideoDivider()
                ToolRow(Icons.Rounded.InstallMobile, "安装包扫描", "查找 APK、APKS、XAPK 与 APKM", "扫描", actions.onApkScan)
                VideoDivider()
                ToolRow(Icons.Rounded.FolderDelete, "卸载残留", "按已安装包索引识别残留目录", "检查", actions.onCorpses)
                VideoDivider()
                ToolRow(Icons.Rounded.Rule, "规则与清理明细", "查看规则命中、保护项与风险归属", "查看", actions.onAudit)
            }
        }

        item {
            Surface(
                modifier = Modifier
                    .padding(horizontal = pagePadding)
                    .fillMaxWidth()
                    .height(54.dp)
                    .clickable(enabled = !state.saving, onClick = actions.onSave),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primary,
                tonalElevation = 1.dp,
                shadowElevation = 5.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (state.saving) "正在保存…" else "保存清理计划",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun CleanStatusHero(
    state: CleanUiState,
    onAutomaticCleaningChanged: (Boolean) -> Unit
) {
    VideoCard(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        contentPadding = 20
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = .11f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.CleaningServices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(27.dp)
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.automaticCleaningEnabled) "自动清理已开启" else "自动清理已暂停",
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    when {
                        state.running -> "当前任务正在执行"
                        !state.engineReady -> "正在连接 Root 清理服务"
                        else -> "${state.enabledCategoryCount} 个类别已启用 · 清理引擎正常"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
            Switch(
                checked = state.automaticCleaningEnabled,
                onCheckedChange = onAutomaticCleaningChanged
            )
        }
        Spacer(Modifier.height(14.dp))
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .045f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        state.scanSnapshotReady -> "扫描快照已就绪"
                        state.engineReady -> "安全保护与白名单已加载"
                        else -> "等待清理引擎连接"
                    },
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (state.scanSnapshotReady) "可清理" else if (state.engineReady) "正常" else "恢复中",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(
    item: CleanCategoryUiItem,
    expanded: Boolean,
    dailyMode: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onExpandedChanged: () -> Unit,
    onIntervalChanged: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = item.enabled && !dailyMode, onClick = onExpandedChanged)
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VideoLeadingIcon(categoryIcon(item.id))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    item.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                if (item.enabled) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (dailyMode) "跟随每日固定时间" else "每 ${formatMinutes(item.intervalMinutes)}执行",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = item.enabled, onCheckedChange = onEnabledChanged)
        }

        if (expanded && item.enabled && !dailyMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 66.dp, end = 14.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                cleanIntervals.forEach { minutes ->
                    IntervalChip(
                        label = formatMinutes(minutes),
                        selected = item.intervalMinutes == minutes,
                        onClick = { onIntervalChanged(minutes) }
                    )
                }
            }
        }
    }
}

@Composable
private fun IntervalChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(15.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else BaiZeTokens.colors.surfaceOverlay
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun ToolRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: String,
    onClick: () -> Unit
) {
    VideoListRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        value = value,
        onClick = onClick
    )
}

private fun categoryIcon(id: CleanCategoryId): ImageVector = when (id) {
    CleanCategoryId.CACHE -> Icons.Rounded.CleaningServices
    CleanCategoryId.EMPTY -> Icons.Rounded.FolderDelete
    CleanCategoryId.RULES -> Icons.Rounded.Rule
    CleanCategoryId.FRAGMENTS -> Icons.Rounded.AutoAwesome
    CleanCategoryId.DEEP -> Icons.Rounded.Security
    CleanCategoryId.ORGANIZE -> Icons.Rounded.FolderCopy
}

private fun apkRetentionText(days: Int): String = when (days) {
    0 -> "立即"
    1 -> "1 天"
    else -> "$days 天"
}
