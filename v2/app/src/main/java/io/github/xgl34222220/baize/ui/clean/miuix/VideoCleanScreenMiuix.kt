package io.github.xgl34222220.baize.ui.clean.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.FolderDelete
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
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
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
import io.github.xgl34222220.baize.ui.miuix.VideoCard
import io.github.xgl34222220.baize.ui.miuix.VideoDivider
import io.github.xgl34222220.baize.ui.miuix.VideoLeadingIcon
import io.github.xgl34222220.baize.ui.miuix.VideoListRow
import io.github.xgl34222220.baize.ui.miuix.VideoSectionTitle
import io.github.xgl34222220.baize.ui.miuix.VideoTabs
import io.github.xgl34222220.baize.ui.miuix.VideoStatusPill
import io.github.xgl34222220.baize.ui.miuix.VideoTopBar
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

/** Fast manual entry points and an independently editable background plan. */
@Composable
fun VideoCleanScreenMiuix(
    state: CleanUiState,
    actions: CleanUiActions,
    expandedCategory: String,
    onExpandedCategoryChanged: (String) -> Unit
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var selectedPage by rememberSaveable { mutableIntStateOf(0) }
    var showDailyTimeDialog by remember { mutableStateOf(false) }
    var showDailyGraceDialog by remember { mutableStateOf(false) }
    var showApkRetentionDialog by remember { mutableStateOf(false) }
    var intervalCategory by remember { mutableStateOf<CleanCategoryUiItem?>(null) }

    LaunchedEffect(expandedCategory) {
        if (expandedCategory == "__open_plan__") {
            selectedPage = 1
            onExpandedCategoryChanged("")
        }
    }

    if (showDailyTimeDialog) {
        TimeValueDialog(
            initialHour = state.dailyHour, initialMinute = state.dailyMinute,
            onDismiss = { showDailyTimeDialog = false }, onConfirm = actions.onDailyTimeChanged
        )
    }
    if (showDailyGraceDialog) {
        IntValueDialog(
            title = "等待执行的时限", description = "到点后条件暂时不满足，会在此时间内继续等待。",
            initialValue = state.dailyGraceMinutes, range = 15..720, suffix = "分钟",
            onDismiss = { showDailyGraceDialog = false }, onConfirm = actions.onDailyGraceChanged
        )
    }
    if (showApkRetentionDialog) {
        IntValueDialog(
            title = "安装包保留时间", description = "保留期内的安装包不会自动删除。0 天表示不保留；手动扫描始终显示所有安装包。",
            initialValue = state.apkPackageDays, range = 0..365, suffix = "天",
            onDismiss = { showApkRetentionDialog = false }, onConfirm = actions.onApkPackageDaysChanged
        )
    }
    intervalCategory?.let { category ->
        IntValueDialog(
            title = "${category.title}执行间隔", description = "独立设置这个类别多久执行一次。执行时仍需满足电量、息屏等条件。",
            initialValue = category.intervalMinutes,
            range = if (category.id == CleanCategoryId.ORGANIZE) 15..43_200 else 5..43_200,
            suffix = "分钟", onDismiss = { intervalCategory = null },
            onConfirm = { actions.onCategoryIntervalChanged(category.id, it) }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { VideoTopBar(title = "清理") }
        item {
            VideoTabs(listOf("清理工具", "自动计划"), selectedPage, { selectedPage = it })
        }
        if (selectedPage == 0) {
            item { ScanSummary(state, actions) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    VideoSectionTitle("常用清理")
                    Row(
                        Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ToolTile(Icons.Rounded.CleaningServices, "应用缓存", "释放日常缓存",
                            MaterialTheme.colorScheme.primary, actions.onInstantCache,
                            Modifier.weight(1f).fillMaxHeight())
                        ToolTile(Icons.Rounded.InstallMobile, "安装包", "查找已下载安装包",
                            MaterialTheme.colorScheme.secondary, actions.onApkScan,
                            Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
            item {
                VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                    VideoListRow(Icons.Rounded.AutoAwesome, "深度清理", "日志、碎片与更多残留", onClick = actions.onDeepClean)
                    VideoDivider()
                    VideoListRow(Icons.Rounded.FolderDelete, "卸载残留", "找出应用卸载后的目录", onClick = actions.onCorpses)
                    VideoDivider()
                    VideoListRow(Icons.Rounded.FolderCopy, "文件归类", "整理下载目录", onClick = actions.onFileOrganizer)
                    VideoDivider()
                    VideoListRow(Icons.Rounded.Security, "清理规则与保护", "规则、清理明细与保留项", onClick = actions.onAudit)
                }
            }
            item {
                VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                    VideoListRow(Icons.Rounded.CalendarMonth, "自动清理计划",
                        if (state.automaticCleaningEnabled) "${state.enabledCategoryCount} 个类别 · ${state.scheduleMode.title}" else "设置周期与保留时间",
                        value = if (state.automaticCleaningEnabled) "已开启" else "已暂停",
                        onClick = { selectedPage = 1 })
                }
            }
        } else {
            item { AutomaticSummary(state, actions) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    VideoSectionTitle("执行方式")
                    ScheduleCard(state, actions, { showDailyTimeDialog = true }, { showDailyGraceDialog = true })
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    VideoSectionTitle("清理类别", "点按周期可直接调整")
                    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                        state.categories.forEachIndexed { index, category ->
                            if (index != 0) VideoDivider(start = 16)
                            CategoryRow(
                                item = category, expanded = expandedCategory == category.id.name,
                                dailyMode = state.scheduleMode == CleanScheduleMode.FIXED_DAILY && category.id != CleanCategoryId.ORGANIZE,
                                retentionDays = state.apkPackageDays,
                                onEnabledChanged = { actions.onCategoryEnabledChanged(category.id, it) },
                                onExpandedChanged = { onExpandedCategoryChanged(if (expandedCategory == category.id.name) "" else category.id.name) },
                                onEditInterval = { intervalCategory = category },
                                onEditRetention = { showApkRetentionDialog = true }
                            )
                        }
                    }
                }
            }
            item {
                GlassActionButton(
                    label = if (state.saving) "正在应用…" else "重新应用当前计划",
                    onClick = actions.onSave,
                    enabled = !state.saving,
                    secondary = true,
                    modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ScanSummary(state: CleanUiState, actions: CleanUiActions) {
    val primary = MaterialTheme.colorScheme.primary
    VideoCard(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        containerColor = lerp(BaiZeTokens.colors.surfaceRaised, primary, .04f),
        contentPadding = 20
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("空间扫描", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            VideoStatusPill(when {
                state.running -> "进行中"
                state.scanSnapshotReady -> "结果已就绪"
                state.engineReady -> "服务已就绪"
                else -> "待连接"
            }, positive = state.engineReady || state.running || state.scanSnapshotReady)
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(when {
                    state.running -> "任务进行中"
                    state.scanSnapshotReady -> "扫描已完成"
                    state.engineReady -> "检查可清理空间"
                    else -> "连接后开始扫描"
                }, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-.5).sp)
                Text(when {
                    state.running -> "点按下方查看任务进度"
                    state.scanSnapshotReady -> "查看明细，选择需要清理的内容"
                    state.engineReady -> "一次检查缓存、垃圾与临时文件"
                    else -> state.serviceText.ifBlank { "等待清理服务连接" }
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(primary.copy(alpha = .07f)),
                contentAlignment = Alignment.Center) {
                Icon(if (state.scanSnapshotReady) Icons.Rounded.CheckCircle else Icons.Rounded.Search,
                    null, Modifier.size(32.dp), tint = primary)
            }
        }
        Spacer(Modifier.height(20.dp))
        if (state.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)))
            Spacer(Modifier.height(12.dp))
        }
        GlassActionButton(
            label = when {
                state.running -> "查看任务进度"
                state.scanSnapshotReady -> "查看扫描结果"
                else -> "开始扫描"
            },
            onClick = actions.onScan,
            enabled = state.engineReady || state.running || state.scanSnapshotReady,
            icon = if (state.scanSnapshotReady) Icons.Rounded.CheckCircle else Icons.Rounded.Search,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ToolTile(
    icon: ImageVector,
    title: String,
    caption: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    VideoCard(modifier.clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClick = onClick), contentPadding = 18) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = .08f)),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(22.dp), tint = tint)
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        Text(caption, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AutomaticSummary(state: CleanUiState, actions: CleanUiActions) {
    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        containerColor = lerp(BaiZeTokens.colors.surfaceRaised, MaterialTheme.colorScheme.primary, .04f), contentPadding = 20) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VideoLeadingIcon(Icons.Rounded.CalendarMonth)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("自动清理", fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
                Text(when {
                    state.saving -> "正在保存计划…"
                    state.automaticCleaningEnabled -> "${state.enabledCategoryCount} 个类别已开启 · 自动保存"
                    else -> "已暂停 · 手动清理可用"
                }, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = state.automaticCleaningEnabled, onCheckedChange = actions.onAutomaticCleaningChanged,
                modifier = Modifier.semantics { contentDescription = "自动清理" })
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = .045f)).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("执行方式", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.scheduleMode.title, style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("已启用类别", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${state.enabledCategoryCount} / ${state.categories.size}", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ScheduleCard(state: CleanUiState, actions: CleanUiActions, onEditTime: () -> Unit, onEditGrace: () -> Unit) {
    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
        Column(Modifier.selectableGroup()) {
            CleanScheduleMode.entries.forEachIndexed { index, mode ->
                val selected = state.scheduleMode == mode
                if (index != 0) VideoDivider(start = 16)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 54.dp)
                        .selectable(selected = selected, role = Role.RadioButton) { actions.onScheduleModeChanged(mode) }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(mode.title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
                        if (selected) Text(when (mode) {
                            CleanScheduleMode.SMART -> "低收益时延长周期，空间紧张时恢复"
                            CleanScheduleMode.STRICT_INTERVAL -> "按各类别设置的间隔执行"
                            CleanScheduleMode.FIXED_DAILY -> "每天在指定时间执行"
                        }, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    RadioButton(selected = selected, onClick = null, modifier = Modifier.size(24.dp))
                }
            }
        }
        if (state.scheduleMode == CleanScheduleMode.FIXED_DAILY) {
            VideoDivider(start = 16)
            PlanValueRow("每日执行时间", state.dailyTimeText, onEditTime)
            VideoDivider(start = 16)
            PlanValueRow("等待执行的时限", formatMinutes(state.dailyGraceMinutes), onEditGrace)
        }
    }
}

@Composable
private fun CategoryRow(
    item: CleanCategoryUiItem, expanded: Boolean, dailyMode: Boolean, retentionDays: Int,
    onEnabledChanged: (Boolean) -> Unit, onExpandedChanged: () -> Unit,
    onEditInterval: () -> Unit, onEditRetention: () -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VideoLeadingIcon(categoryIcon(item.id), primary = item.enabled)
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onExpandedChanged)
                    .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "收起说明" else "展开说明", modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!dailyMode) {
                    Row(Modifier.clickable(role = Role.Button, onClickLabel = "修改${item.title}执行间隔", onClick = onEditInterval)
                        .heightIn(min = 36.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (item.enabled) "每 ${formatMinutes(item.intervalMinutes)}" else "已关闭 · ${formatMinutes(item.intervalMinutes)}",
                            Modifier.weight(1f, fill = false), fontSize = 12.sp, lineHeight = 18.sp,
                            color = if (item.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text(if (item.enabled) "每日固定时间执行" else "已关闭", fontSize = 12.sp, lineHeight = 18.sp,
                        color = if (item.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp))
                }
            }
            Switch(checked = item.enabled, onCheckedChange = onEnabledChanged,
                modifier = Modifier.semantics { contentDescription = "${item.title}自动计划" })
        }
        if (expanded) {
            Text(if (item.id == CleanCategoryId.APK) "仅清理安装文件，不卸载应用。" else item.description,
                Modifier.padding(start = 18.dp, end = 18.dp, bottom = 12.dp), fontSize = 12.sp, lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Keep retention separate from frequency and visible even while this category is disabled.
        if (item.id == CleanCategoryId.APK) {
            PlanValueRow("安装包保留时间", if (retentionDays == 0) "不保留" else "$retentionDays 天", onEditRetention,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    .clip(RoundedCornerShape(16.dp)).background(BaiZeTokens.colors.surfaceOverlay.copy(alpha = .62f)))
        }
    }
}

@Composable
private fun PlanValueRow(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun categoryIcon(id: CleanCategoryId): ImageVector = when (id) {
    CleanCategoryId.APK -> Icons.Rounded.InstallMobile
    CleanCategoryId.CACHE -> Icons.Rounded.CleaningServices
    CleanCategoryId.EMPTY -> Icons.Rounded.FolderDelete
    CleanCategoryId.RULES -> Icons.Rounded.Rule
    CleanCategoryId.FRAGMENTS -> Icons.Rounded.FolderDelete
    CleanCategoryId.DEEP -> Icons.Rounded.AutoAwesome
    CleanCategoryId.ORGANIZE -> Icons.Rounded.FolderCopy
}
