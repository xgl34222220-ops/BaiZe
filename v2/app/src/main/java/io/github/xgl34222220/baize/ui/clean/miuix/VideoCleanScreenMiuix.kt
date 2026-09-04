package io.github.xgl34222220.baize.ui.clean.miuix

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

private val cleanIntervals = listOf(30, 60, 180, 360, 720, 1_440, 4_320, 10_080, 43_200)

/** 清理中心：计划 / 类别 / 工具三层面板，统一使用 Card、Item、Tabs、Switch 与 Action。 */
@Composable
fun VideoCleanScreenMiuix(
    state: CleanUiState,
    actions: CleanUiActions,
    expandedCategory: String,
    onExpandedCategoryChanged: (String) -> Unit
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val pagePadding = BaiZeTokens.spacing.pageHorizontal
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
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
        contentPadding = PaddingValues(bottom = bottomInset + 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            VideoTopBar(
                title = "清理",
                subtitle = if (state.running) "任务执行中" else state.scheduleSummary,
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
                item {
                    VideoCard(
                        modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth()
                    ) {
                        VideoSwitchRow(
                            icon = Icons.Rounded.CleaningServices,
                            title = if (state.automaticCleaningEnabled) "自动清理已开启" else "自动清理已暂停",
                            subtitle = if (state.engineReady) {
                                "${state.enabledCategoryCount} 个类别 · ${state.serviceText}"
                            } else {
                                "正在连接 Root 清理服务"
                            },
                            checked = state.automaticCleaningEnabled,
                            onCheckedChange = actions.onAutomaticCleaningChanged
                        )
                    }
                }

                item { VideoSectionTitle("执行方式", "决定自动任务何时运行") }
                item {
                    ScheduleCard(
                        state = state,
                        actions = actions,
                        onEditTime = { showDailyTimeDialog = true },
                        onEditGrace = { showDailyGraceDialog = true }
                    )
                }

                item { VideoSectionTitle("附加清理", "安装包使用独立保留策略") }
                item {
                    VideoCard(
                        modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth()
                    ) {
                        VideoSwitchRow(
                            icon = Icons.Rounded.InstallMobile,
                            title = "过期安装包",
                            subtitle = "自动处理超过保留时间的 APK、APKS、XAPK 与 APKM",
                            checked = state.apkPackagesEnabled,
                            onCheckedChange = actions.onApkPackagesChanged
                        )
                        VideoDivider()
                        VideoListRow(
                            icon = Icons.Rounded.CalendarMonth,
                            title = "安装包保留时间",
                            subtitle = if (state.apkPackagesEnabled) "超过该时间才进入后台自动清理" else "开启过期安装包后可修改",
                            value = apkRetentionText(state.apkPackageDays),
                            enabled = state.apkPackagesEnabled,
                            onClick = { if (state.apkPackagesEnabled) showApkRetentionDialog = true }
                        )
                    }
                }

                item {
                    Surface(
                        modifier = Modifier
                            .padding(horizontal = pagePadding)
                            .fillMaxWidth()
                            .height(46.dp)
                            .clickable(enabled = !state.saving, onClick = actions.onSave),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                if (state.saving) "正在保存…" else "保存清理计划",
                                style = BaiZeTokens.type.body.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }
            }

            1 -> {
                item { VideoSectionTitle("自动清理类别", "每个类别有独立开关和基础周期") }
                state.categories.forEach { category ->
                    item(key = category.id.name) {
                        CategoryCard(
                            item = category,
                            expanded = expandedCategory == category.id.name,
                            dailyMode = state.scheduleMode == CleanScheduleMode.FIXED_DAILY && category.id != CleanCategoryId.ORGANIZE,
                            onEnabledChanged = { actions.onCategoryEnabledChanged(category.id, it) },
                            onExpandedChanged = {
                                onExpandedCategoryChanged(if (expandedCategory == category.id.name) "" else category.id.name)
                            },
                            onIntervalChanged = { actions.onCategoryIntervalChanged(category.id, it) }
                        )
                    }
                }
            }

            else -> {
                item { VideoSectionTitle("专项工具", "手动扫描和低频维护集中在这里") }
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = pagePadding),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VideoActionTile(
                                icon = Icons.Rounded.Search,
                                title = "智能扫描",
                                subtitle = "只扫描并生成可清理快照",
                                onClick = actions.onScan,
                                modifier = Modifier.weight(1f),
                                primary = true
                            )
                            VideoActionTile(
                                icon = Icons.Rounded.CleaningServices,
                                title = "应用缓存",
                                subtitle = "立即处理应用缓存",
                                onClick = actions.onInstantCache,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VideoActionTile(
                                icon = Icons.Rounded.FolderCopy,
                                title = "文件归类",
                                subtitle = "整理下载、附件与导出文件",
                                onClick = actions.onFileOrganizer,
                                modifier = Modifier.weight(1f)
                            )
                            VideoActionTile(
                                icon = Icons.Rounded.Security,
                                title = "深度清理",
                                subtitle = "进入受风险上限约束的深度流程",
                                onClick = actions.onDeepClean,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                item {
                    VideoCard(
                        modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth()
                    ) {
                        VideoListRow(
                            icon = Icons.Rounded.InstallMobile,
                            title = "安装包扫描",
                            subtitle = "查找 APK、APKS、XAPK 与 APKM",
                            value = "扫描",
                            onClick = actions.onApkScan
                        )
                        VideoDivider()
                        VideoListRow(
                            icon = Icons.Rounded.FolderDelete,
                            title = "卸载残留",
                            subtitle = "按已安装包索引识别残留目录",
                            value = "检查",
                            onClick = actions.onCorpses
                        )
                        VideoDivider()
                        VideoListRow(
                            icon = Icons.Rounded.Rule,
                            title = "规则与清理明细",
                            subtitle = "查看规则命中、保护项与风险归属",
                            value = "查看",
                            onClick = actions.onAudit
                        )
                    }
                }
            }
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
    val pagePadding = BaiZeTokens.spacing.pageHorizontal
    VideoCard(
        modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth(),
        contentPadding = 14
    ) {
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
                onClick = onEditTime
            )
            VideoDivider(start = 0)
            VideoListRow(
                icon = Icons.Rounded.AutoAwesome,
                title = "补做窗口",
                subtitle = "条件暂时不满足时继续等待",
                value = formatMinutes(state.dailyGraceMinutes),
                onClick = onEditGrace
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
    val pagePadding = BaiZeTokens.spacing.pageHorizontal
    VideoCard(
        modifier = Modifier.padding(horizontal = pagePadding).fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = item.enabled && !dailyMode, onClick = onExpandedChanged)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VideoLeadingIcon(categoryIcon(item.id))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = BaiZeTokens.type.body.copy(fontWeight = FontWeight.SemiBold))
                Text(item.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = BaiZeTokens.type.caption)
                if (item.enabled) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (dailyMode) "跟随每日固定时间" else "每 ${formatMinutes(item.intervalMinutes)}执行",
                        color = MaterialTheme.colorScheme.primary,
                        style = BaiZeTokens.type.caption.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = item.enabled, onCheckedChange = onEnabledChanged)
        }

        if (expanded && item.enabled && !dailyMode) {
            VideoDivider(start = 14)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
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
private fun IntervalChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        modifier = Modifier.height(32.dp).clickable(onClick = onClick),
        shape = shape,
        color = if (selected) BaiZeTokens.colors.surfaceOverlay else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .42f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .58f)
        )
    ) {
        Box(modifier = Modifier.padding(horizontal = 11.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = BaiZeTokens.type.caption.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
            )
        }
    }
}

private fun categoryIcon(id: CleanCategoryId): ImageVector = when (id) {
    CleanCategoryId.CACHE -> Icons.Rounded.CleaningServices
    CleanCategoryId.EMPTY -> Icons.Rounded.FolderDelete
    CleanCategoryId.RULES -> Icons.Rounded.Rule
    CleanCategoryId.FRAGMENTS -> Icons.Rounded.AutoAwesome
    CleanCategoryId.DEEP -> Icons.Rounded.Security
    CleanCategoryId.ORGANIZE -> Icons.Rounded.FolderCopy
}

private fun apkRetentionText(days: Int): String = if (days <= 0) "立即" else "$days 天"
