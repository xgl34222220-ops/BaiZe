package io.github.xgl34222220.baize.ui.clean.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.FolderDelete
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
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
import io.github.xgl34222220.baize.ui.miuix.LuoShuGroup
import io.github.xgl34222220.baize.ui.miuix.LuoShuGroupDivider
import io.github.xgl34222220.baize.ui.miuix.LuoShuNavigationRow
import io.github.xgl34222220.baize.ui.miuix.LuoShuPageHeader
import io.github.xgl34222220.baize.ui.miuix.LuoShuSection
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

private val luoShuIntervalOptions = listOf(30, 60, 180, 360, 720, 1_440, 10_080, 43_200)

@Composable
fun CleanScreenMiuix(
    state: CleanUiState,
    actions: CleanUiActions,
    expandedCategory: String,
    onExpandedCategoryChanged: (String) -> Unit
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var showDailyTimeDialog by remember { mutableStateOf(false) }
    var showDailyGraceDialog by remember { mutableStateOf(false) }
    var showApkDaysDialog by remember { mutableStateOf(false) }
    var automationExpanded by rememberSaveable { mutableStateOf(expandedCategory == "__open_plan__") }

    LaunchedEffect(expandedCategory) {
        if (expandedCategory == "__open_plan__") {
            automationExpanded = true
        }
    }

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
            description = "固定时间到达后，如果执行条件暂时不满足，会在此时间内继续等待。",
            initialValue = state.dailyGraceMinutes,
            range = 15..720,
            suffix = "分钟",
            onDismiss = { showDailyGraceDialog = false },
            onConfirm = actions.onDailyGraceChanged
        )
    }
    if (showApkDaysDialog) {
        IntValueDialog(
            title = "安装包保留时间",
            description = "超过保留天数的安装包才会进入自动清理范围；0 天表示允许清理当天发现的安装包。",
            initialValue = state.apkPackageDays,
            range = 0..365,
            suffix = "天",
            onDismiss = { showApkDaysDialog = false },
            onConfirm = actions.onApkPackageDaysChanged
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("clean-scroll"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomInset + 132.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "clean-header") {
            LuoShuPageHeader("清理")
        }
        item(key = "clean-manual-title") {
            LuoShuSection("手动工具")
        }
        item(key = "clean-manual") {
            LuoShuGroup {
                LuoShuNavigationRow(Icons.Rounded.Search, "扫描工作台", "分类管理与清理", actions.onScan)
                LuoShuGroupDivider()
                LuoShuNavigationRow(Icons.Rounded.InstallMobile, "安装包清理", "安装包扫描", actions.onApkScan)
                LuoShuGroupDivider()
                LuoShuNavigationRow(Icons.Rounded.CleaningServices, "即时缓存", "缓存快速检查", actions.onInstantCache)
                LuoShuGroupDivider()
                LuoShuNavigationRow(Icons.Rounded.FolderCopy, "文件归类", "下载与散落文件", actions.onFileOrganizer)
                LuoShuGroupDivider()
                LuoShuNavigationRow(Icons.Rounded.Security, "深度清理", "扩展扫描范围", actions.onDeepClean)
                LuoShuGroupDivider()
                LuoShuNavigationRow(Icons.Rounded.FolderDelete, "卸载残留", "残留文件检查", actions.onCorpses)
                LuoShuGroupDivider()
                LuoShuNavigationRow(Icons.Rounded.Rule, "规则审计", "规则命中与保护", actions.onAudit)
            }
        }
        item(key = "clean-auto-title") {
            LuoShuSection("自动化策略", if (automationExpanded) "执行方式与周期" else "")
        }
        item(key = "clean-auto") {
            AutomaticCleaningHero(
                state = state,
                actions = actions,
                expanded = automationExpanded,
                onExpandedChanged = { automationExpanded = !automationExpanded }
            )
        }
        if (automationExpanded) {
            item(key = "clean-schedule-title") {
                LuoShuSection("执行方式")
            }
            item(key = "clean-schedule") {
                ScheduleGroup(
                    state = state,
                    actions = actions,
                    onEditTime = { showDailyTimeDialog = true },
                    onEditGrace = { showDailyGraceDialog = true }
                )
            }
            item(key = "clean-task-title") {
                LuoShuSection("任务计划", "分类周期")
            }
            item(key = "clean-tasks") {
                TaskGroup(
                    state = state,
                    actions = actions,
                    expandedCategory = expandedCategory,
                    onExpandedCategoryChanged = onExpandedCategoryChanged
                )
            }
            item(key = "clean-extra-title") {
                LuoShuSection("附加项目")
            }
            item(key = "clean-extra") {
                LuoShuGroup {
                    SwitchRow(
                        icon = Icons.Rounded.InstallMobile,
                        title = "过期安装包",
                        subtitle = "保留 ${state.apkPackageDays} 天后自动清理",
                        checked = state.apkPackagesEnabled,
                        onCheckedChange = actions.onApkPackagesChanged
                    )
                    if (state.apkPackagesEnabled) {
                        LuoShuGroupDivider()
                        ValueRow("保留时间", "${state.apkPackageDays} 天") { showApkDaysDialog = true }
                    }
                }
            }
            item(key = "clean-auto-save-note") {
                Text(
                    if (state.saving) "正在保存设置…" else "修改后自动保存",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun AutomaticCleaningHero(
    state: CleanUiState,
    actions: CleanUiActions,
    expanded: Boolean,
    onExpandedChanged: () -> Unit
) {
    val colors = BaiZeTokens.colors
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = colors.surfaceRaised,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(
                            scheme.primaryContainer.copy(alpha = .46f),
                            colors.surfaceRaised
                        )
                    )
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(15.dp),
                color = colors.surfaceOverlay
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.CleaningServices,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = scheme.primary
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("自动清理", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        !state.engineReady -> state.serviceText.ifBlank { "清理服务尚未就绪" }
                        state.running -> "正在执行清理任务"
                        state.automaticCleaningEnabled -> "${state.enabledCategoryCount} 项任务已启用"
                        else -> "所有自动任务已暂停"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onExpandedChanged) {
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "收起自动清理设置" else "展开自动清理设置",
                    tint = scheme.onSurfaceVariant
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
private fun ScheduleGroup(
    state: CleanUiState,
    actions: CleanUiActions,
    onEditTime: () -> Unit,
    onEditGrace: () -> Unit
) {
    LuoShuGroup {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.CalendarMonth)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("选择执行方式", style = MaterialTheme.typography.titleSmall)
                    Text(
                        state.scheduleSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CleanScheduleMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.scheduleMode == mode,
                        onClick = { actions.onScheduleModeChanged(mode) },
                        label = { Text(mode.title, style = MaterialTheme.typography.labelSmall) },
                        trailingIcon = {
                            Icon(
                                Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .30f),
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .68f)
                        )
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                state.scheduleMode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.scheduleMode == CleanScheduleMode.FIXED_DAILY) {
            LuoShuGroupDivider()
            ValueRow("执行时间", state.dailyTimeText, onEditTime)
            LuoShuGroupDivider()
            ValueRow("补做窗口", formatMinutes(state.dailyGraceMinutes), onEditGrace)
            Text(
                "文件自动归类继续使用独立周期。",
                modifier = Modifier.padding(start = 74.dp, end = 18.dp, bottom = 15.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TaskGroup(
    state: CleanUiState,
    actions: CleanUiActions,
    expandedCategory: String,
    onExpandedCategoryChanged: (String) -> Unit
) {
    LuoShuGroup {
        state.categories.forEachIndexed { index, item ->
            if (index == 0) TaskGroupLabel("常规清理")
            if (item.id == CleanCategoryId.FRAGMENTS) {
                LuoShuGroupDivider()
                TaskGroupLabel("维护与深度")
            }
            val key = item.id.name
            CategoryRow(
                item = item,
                expanded = expandedCategory == key,
                dailyEnabled = state.scheduleMode == CleanScheduleMode.FIXED_DAILY && item.id != CleanCategoryId.ORGANIZE,
                onEnabledChanged = { actions.onCategoryEnabledChanged(item.id, it) },
                onExpandedChanged = {
                    onExpandedCategoryChanged(if (expandedCategory == key) "" else key)
                },
                onIntervalChanged = { actions.onCategoryIntervalChanged(item.id, it) }
            )
            if (index != state.categories.lastIndex && state.categories.getOrNull(index + 1)?.id != CleanCategoryId.FRAGMENTS) LuoShuGroupDivider()
        }
    }
}

@Composable
private fun CategoryRow(
    item: CleanCategoryUiItem,
    expanded: Boolean,
    dailyEnabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onExpandedChanged: () -> Unit,
    onIntervalChanged: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(categoryIcon(item.id))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = item.enabled, onCheckedChange = onEnabledChanged)
        }
        if (item.enabled) {
            Surface(
                modifier = Modifier
                    .padding(start = 74.dp, end = 16.dp, bottom = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = !dailyEnabled, onClick = onExpandedChanged),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .34f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (dailyEnabled) "跟随每日固定时间" else "每 ${formatMinutes(item.intervalMinutes)}执行一次",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!dailyEnabled) {
                        Icon(
                            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (expanded) "收起周期选项" else "展开周期选项",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (expanded && !dailyEnabled) {
                Column(
                    modifier = Modifier.padding(start = 74.dp, end = 16.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    luoShuIntervalOptions.chunked(4).forEach { rowItems ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowItems.forEach { minutes ->
                                FilterChip(
                                    selected = item.intervalMinutes == minutes,
                                    onClick = { onIntervalChanged(minutes) },
                                    label = {
                                        Text(
                                            formatMinutes(minutes),
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Center,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskGroupLabel(label: String) {
    Text(
        label,
        modifier = Modifier.padding(start = 74.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ValueRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = 74.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun IconTile(icon: ImageVector) {
    Surface(
        modifier = Modifier.size(42.dp),
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun categoryIcon(id: CleanCategoryId): ImageVector = when (id) {
    CleanCategoryId.APK -> Icons.Rounded.InstallMobile
    CleanCategoryId.CACHE -> Icons.Rounded.CleaningServices
    CleanCategoryId.EMPTY -> Icons.Rounded.FolderDelete
    CleanCategoryId.RULES -> Icons.Rounded.Rule
    CleanCategoryId.FRAGMENTS -> Icons.Rounded.AutoAwesome
    CleanCategoryId.DEEP -> Icons.Rounded.Security
    CleanCategoryId.ORGANIZE -> Icons.Rounded.FolderCopy
}
