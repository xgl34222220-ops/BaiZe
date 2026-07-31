package io.github.xgl34222220.baize.ui.clean.miuix

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import io.github.xgl34222220.baize.ui.miuix.VideoActionTile
import io.github.xgl34222220.baize.ui.miuix.VideoCard
import io.github.xgl34222220.baize.ui.miuix.VideoDivider
import io.github.xgl34222220.baize.ui.miuix.VideoIconButton
import io.github.xgl34222220.baize.ui.miuix.VideoLeadingIcon
import io.github.xgl34222220.baize.ui.miuix.VideoListRow
import io.github.xgl34222220.baize.ui.miuix.VideoSectionTitle
import io.github.xgl34222220.baize.ui.miuix.VideoSwitchRow
import io.github.xgl34222220.baize.ui.miuix.VideoTabs
import io.github.xgl34222220.baize.ui.miuix.VideoTopBar
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

private val videoIntervals = listOf(30, 60, 180, 360, 720, 1_440, 4_320, 10_080, 43_200)

/** 参考视频的“面板”结构：顶部标签切换计划、类别和工具，避免一条无限长设置列表。 */
@Composable
fun VideoCleanScreenMiuix(
    state: CleanUiState,
    actions: CleanUiActions,
    expandedCategory: String,
    onExpandedCategoryChanged: (String) -> Unit
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showDailyTimeDialog by remember { mutableStateOf(false) }
    var showDailyGraceDialog by remember { mutableStateOf(false) }

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
            description = "固定时间到达后，如果条件暂时不满足，会在此时间内继续等待。",
            initialValue = state.dailyGraceMinutes,
            range = 15..720,
            suffix = "分钟",
            onDismiss = { showDailyGraceDialog = false },
            onConfirm = actions.onDailyGraceChanged
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 102.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            VideoTopBar(
                title = "清理",
                subtitle = "计划、类别与专项工具",
                actions = {
                    VideoIconButton(Icons.Rounded.Search, "扫描", actions.onScan)
                    VideoIconButton(Icons.Rounded.InstallMobile, "安装包", actions.onApkScan)
                }
            )
        }
        item {
            VideoTabs(
                labels = listOf("计划", "类别", "工具"),
                selectedIndex = selectedTab,
                onSelected = { selectedTab = it }
            )
        }

        when (selectedTab) {
            0 -> {
                item { AutomaticHero(state, actions) }
                item { VideoSectionTitle("执行方式", state.scheduleSummary) }
                item {
                    ScheduleCard(
                        state = state,
                        actions = actions,
                        onEditTime = { showDailyTimeDialog = true },
                        onEditGrace = { showDailyGraceDialog = true }
                    )
                }
                item { VideoSectionTitle("附加清理", "独立于普通缓存类别") }
                item {
                    VideoCard(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .fillMaxWidth()
                    ) {
                        VideoSwitchRow(
                            icon = Icons.Rounded.InstallMobile,
                            title = "过期安装包",
                            subtitle = "保留 ${state.apkPackageDays} 天后自动清理",
                            checked = state.apkPackagesEnabled,
                            onCheckedChange = actions.onApkPackagesChanged
                        )
                    }
                }
                item { ApplyButton(state.saving, actions.onSave) }
            }

            1 -> {
                item { VideoSectionTitle("自动清理类别", "每个类别独立开关与周期") }
                state.categories.forEach { category ->
                    item(key = category.id.name) {
                        CategoryCard(
                            item = category,
                            expanded = expandedCategory == category.id.name,
                            dailyMode = state.scheduleMode == CleanScheduleMode.FIXED_DAILY && category.id != CleanCategoryId.ORGANIZE,
                            onEnabledChanged = { actions.onCategoryEnabledChanged(category.id, it) },
                            onExpandedChanged = {
                                onExpandedCategoryChanged(
                                    if (expandedCategory == category.id.name) "" else category.id.name
                                )
                            },
                            onIntervalChanged = { actions.onCategoryIntervalChanged(category.id, it) }
                        )
                    }
                }
            }

            else -> {
                item { VideoSectionTitle("专项工具", "低频功能集中放在单独页面") }
                item {
                    ToolGrid(actions)
                }
            }
        }
    }
}

@Composable
private fun AutomaticHero(state: CleanUiState, actions: CleanUiActions) {
    VideoCard(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f),
        contentPadding = 16
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .56f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CleaningServices,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(27.dp)
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (state.automaticCleaningEnabled) BaiZeTokens.colors.success
                                else MaterialTheme.colorScheme.outline
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (state.automaticCleaningEnabled) "自动清理已开启" else "自动清理已暂停",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    "${state.enabledCategoryCount} 个类别",
                    fontSize = 24.sp,
                    lineHeight = 29.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (state.engineReady) state.serviceText else "正在连接 Root 清理服务",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(
                checked = state.automaticCleaningEnabled,
                onCheckedChange = actions.onAutomaticCleaningChanged
            )
        }
    }
}

@Composable
private fun ScheduleCard(
    state: CleanUiState,
    actions: CleanUiActions,
    onEditTime: () -> Unit,
    onEditGrace: () -> Unit
) {
    VideoCard(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
        contentPadding = 14
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            CleanScheduleMode.entries.forEach { mode ->
                val selected = state.scheduleMode == mode
                Surface(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { actions.onScheduleModeChanged(mode) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else BaiZeTokens.colors.surfaceOverlay,
                    border = BorderStroke(
                        1.dp,
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .28f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .22f)
                    )
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 13.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            mode.title,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            state.scheduleMode.description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            lineHeight = 14.sp
        )

        if (state.scheduleMode == CleanScheduleMode.FIXED_DAILY) {
            Spacer(Modifier.height(12.dp))
            VideoDivider(start = 0)
            VideoListRow(
                icon = Icons.Rounded.CalendarMonth,
                title = "执行时间",
                subtitle = "每天固定执行已启用类别",
                value = state.dailyTimeText,
                onClick = onEditTime,
                modifier = Modifier.padding(horizontal = 0.dp)
            )
            VideoDivider(start = 0)
            VideoListRow(
                icon = Icons.Rounded.AutoAwesome,
                title = "补做窗口",
                subtitle = "条件不满足时继续等待",
                value = formatMinutes(state.dailyGraceMinutes),
                onClick = onEditGrace,
                modifier = Modifier.padding(horizontal = 0.dp)
            )
        }
    }
}

@Composable
private fun CategoryCard(
    item: CleanCategoryUiItem,
    expanded: Boolean,
    dailyMode: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onExpandedChanged: () -> Unit,
    onIntervalChanged: (Int) -> Unit
) {
    VideoCard(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VideoLeadingIcon(categoryIcon(item.id))
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = item.enabled, onClick = onExpandedChanged)
            ) {
                Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(1.dp))
                Text(
                    item.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.enabled) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (dailyMode) "跟随每日固定时间" else "每 ${formatMinutes(item.intervalMinutes)}执行",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (item.enabled && !dailyMode) {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(3.dp))
            }
            Switch(checked = item.enabled, onCheckedChange = onEnabledChanged)
        }

        if (expanded && item.enabled && !dailyMode) {
            VideoDivider(start = 15)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 15.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                videoIntervals.forEach { minutes ->
                    val selected = item.intervalMinutes == minutes
                    Surface(
                        modifier = Modifier
                            .height(32.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .clickable { onIntervalChanged(minutes) },
                        shape = RoundedCornerShape(11.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else BaiZeTokens.colors.surfaceOverlay
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 11.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                formatMinutes(minutes),
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolGrid(actions: CleanUiActions) {
    Column(
        modifier = Modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoActionTile(
                icon = Icons.Rounded.Search,
                title = "扫描工作台",
                subtitle = "先扫描，再按快照清理",
                onClick = actions.onScan,
                modifier = Modifier.weight(1f),
                primary = true
            )
            VideoActionTile(
                icon = Icons.Rounded.InstallMobile,
                title = "安装包扫描",
                subtitle = "识别重复与过期 APK",
                onClick = actions.onApkScan,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoActionTile(
                icon = Icons.Rounded.CleaningServices,
                title = "即时缓存",
                subtitle = "快速处理应用缓存",
                onClick = actions.onInstantCache,
                modifier = Modifier.weight(1f)
            )
            VideoActionTile(
                icon = Icons.Rounded.FolderCopy,
                title = "文件归类",
                subtitle = "整理下载目录与文件",
                onClick = actions.onFileOrganizer,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoActionTile(
                icon = Icons.Rounded.AutoAwesome,
                title = "深度清理",
                subtitle = "扩大范围并保留保护规则",
                onClick = actions.onDeepClean,
                modifier = Modifier.weight(1f)
            )
            VideoActionTile(
                icon = Icons.Rounded.FolderDelete,
                title = "卸载残留",
                subtitle = "检查已卸载应用遗留文件",
                onClick = actions.onCorpses,
                modifier = Modifier.weight(1f)
            )
        }
        VideoActionTile(
            icon = Icons.Rounded.Security,
            title = "规则与安全审计",
            subtitle = "检查规则命中、保护项和潜在风险",
            onClick = actions.onAudit,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ApplyButton(saving: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = !saving, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                if (saving) "正在应用…" else "保存并应用",
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun categoryIcon(id: CleanCategoryId): ImageVector = when (id) {
    CleanCategoryId.CACHE -> Icons.Rounded.CleaningServices
    CleanCategoryId.EMPTY -> Icons.Rounded.FolderDelete
    CleanCategoryId.RULES -> Icons.Rounded.Rule
    CleanCategoryId.FRAGMENTS -> Icons.Rounded.FolderDelete
    CleanCategoryId.DEEP -> Icons.Rounded.AutoAwesome
    CleanCategoryId.ORGANIZE -> Icons.Rounded.FolderCopy
}
