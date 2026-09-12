package io.github.xgl34222220.baize.ui.clean.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.FolderDelete
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import io.github.xgl34222220.baize.ui.miuix.VideoCard
import io.github.xgl34222220.baize.ui.miuix.VideoDivider
import io.github.xgl34222220.baize.ui.miuix.VideoLeadingIcon
import io.github.xgl34222220.baize.ui.miuix.VideoListRow
import io.github.xgl34222220.baize.ui.miuix.VideoSectionTitle
import io.github.xgl34222220.baize.ui.miuix.VideoStatusPill
import io.github.xgl34222220.baize.ui.miuix.VideoTopBar

/** The daily tools and the background plan have distinct, complete entry points. */
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

    if (showDailyTimeDialog) TimeValueDialog(
        initialHour = state.dailyHour, initialMinute = state.dailyMinute,
        onDismiss = { showDailyTimeDialog = false }, onConfirm = actions.onDailyTimeChanged
    )
    if (showDailyGraceDialog) IntValueDialog(
        title = "等待执行的时限", description = "到点后条件暂时不满足，会在此时间内继续等待。",
        initialValue = state.dailyGraceMinutes, range = 15..720, suffix = "分钟",
        onDismiss = { showDailyGraceDialog = false }, onConfirm = actions.onDailyGraceChanged
    )
    if (showApkRetentionDialog) IntValueDialog(
        title = "安装包保留时间", description = "保留期内的安装包不会自动删除。0 天表示不保留；手动扫描始终显示所有安装包。",
        initialValue = state.apkPackageDays, range = 0..365, suffix = "天",
        onDismiss = { showApkRetentionDialog = false }, onConfirm = actions.onApkPackageDaysChanged
    )
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
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { VideoTopBar(title = "清理", subtitle = "让空间回到有用的地方") }
        item {
            Row(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("清理工具", "自动计划").forEachIndexed { index, title ->
                    Surface(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { selectedPage = index },
                        shape = RoundedCornerShape(14.dp),
                        color = if (selectedPage == index) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Box(Modifier.heightIn(min = 48.dp).padding(10.dp), contentAlignment = Alignment.Center) {
                            Text(title, fontSize = 15.sp, fontWeight = if (selectedPage == index) FontWeight.Bold else FontWeight.Medium,
                                color = if (selectedPage == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (selectedPage == 0) {
            item { ScanHero(state, actions) }
            item { VideoSectionTitle("专项清理", "按需要处理，查看明细再决定") }
            item {
                VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 0) {
                    VideoListRow(Icons.Rounded.InstallMobile, "安装包", "查找 APK、APKS、XAPK 和 APKM", onClick = actions.onApkScan)
                    VideoDivider()
                    VideoListRow(Icons.Rounded.CleaningServices, "应用缓存", "查看各应用缓存并选择清理", onClick = actions.onInstantCache)
                    VideoDivider()
                    VideoListRow(Icons.Rounded.FolderDelete, "卸载残留", "检查已卸载应用留下的文件", onClick = actions.onCorpses)
                    VideoDivider()
                    VideoListRow(Icons.Rounded.AutoAwesome, "深度清理", "进一步检查日志、临时文件与残留", onClick = actions.onDeepClean)
                }
            }
            item { VideoSectionTitle("文件与规则") }
            item {
                VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 0) {
                    VideoListRow(Icons.Rounded.FolderCopy, "文件归类", "按类型整理下载文件，先预览后移动", onClick = actions.onFileOrganizer)
                    VideoDivider()
                    VideoListRow(Icons.Rounded.Security, "清理规则与保护", "查看命中规则、清理明细和保留内容", onClick = actions.onAudit)
                }
            }
            item {
                VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 0) {
                    VideoListRow(Icons.Rounded.CalendarMonth, "自动清理计划",
                        if (state.automaticCleaningEnabled) "${state.enabledCategoryCount} 个类别已开启 · ${state.scheduleMode.title}" else "设置每个类别的执行周期与保留时间",
                        value = if (state.automaticCleaningEnabled) "已开启" else "已暂停", onClick = { selectedPage = 1 })
                }
            }
        } else {
            item { AutomaticHero(state, actions) }
            item { VideoSectionTitle("何时执行", "周期决定多久检查，保留期决定哪些文件可删") }
            item {
                ScheduleCard(state, actions, { showDailyTimeDialog = true }, { showDailyGraceDialog = true })
            }
            item { VideoSectionTitle("清理类别", "开启的类别各自执行，保护设置始终生效") }
            state.categories.forEach { category ->
                item(key = category.id.name) {
                    CategoryCard(
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
            item {
                Button(onClick = actions.onSave, enabled = !state.saving,
                    modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().heightIn(min = 54.dp),
                    shape = RoundedCornerShape(18.dp)) {
                    Text(if (state.saving) "正在应用…" else "重新应用当前计划", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ScanHero(state: CleanUiState, actions: CleanUiActions) {
    VideoCard(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.primaryContainer, contentPadding = 24
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            VideoLeadingIcon(Icons.Rounded.CleaningServices)
            VideoStatusPill(when { state.running -> "任务进行中"; state.engineReady -> "清理服务就绪"; else -> "等待连接" }, state.engineReady)
        }
        Spacer(Modifier.height(22.dp))
        Text(when { state.running -> "正在整理空间"; state.scanSnapshotReady -> "扫描结果已就绪"; else -> "找回更多可用空间" },
            fontSize = 27.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(8.dp))
        Text(when {
            state.running -> "任务结束后可查看处理结果与保留原因。"
            state.scanSnapshotReady -> "打开工作台，核对分类结果与文件明细后再清理。"
            else -> "检查缓存、规则垃圾与临时文件。扫描不会删除文件。"
        }, fontSize = 14.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .78f))
        Spacer(Modifier.height(22.dp))
        if (state.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
        }
        Button(
            onClick = actions.onScan, enabled = state.engineReady || state.running || state.scanSnapshotReady,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(if (state.scanSnapshotReady) Icons.Rounded.CheckCircle else Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(when { state.running -> "查看任务进度"; state.scanSnapshotReady -> "查看扫描结果"; else -> "开始扫描" }, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AutomaticHero(state: CleanUiState, actions: CleanUiActions) {
    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), containerColor = MaterialTheme.colorScheme.primaryContainer, contentPadding = 24) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("自动清理", fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(if (state.automaticCleaningEnabled) "${state.enabledCategoryCount} 个类别 · ${state.scheduleMode.title}" else "已暂停，手动清理仍可使用",
                    fontSize = 14.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = state.automaticCleaningEnabled, onCheckedChange = actions.onAutomaticCleaningChanged)
        }
        Spacer(Modifier.height(16.dp))
        Text("计划修改后自动保存。息屏、充电和电量条件可在设置中修改。", fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ScheduleCard(state: CleanUiState, actions: CleanUiActions, onEditTime: () -> Unit, onEditGrace: () -> Unit) {
    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 0) {
        CleanScheduleMode.entries.forEachIndexed { index, mode ->
            Row(Modifier.fillMaxWidth().clickable { actions.onScheduleModeChanged(mode) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = state.scheduleMode == mode, onClick = { actions.onScheduleModeChanged(mode) })
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(mode.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(mode.description, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (index != CleanScheduleMode.entries.lastIndex) VideoDivider()
        }
        if (state.scheduleMode == CleanScheduleMode.FIXED_DAILY) {
            VideoDivider()
            VideoListRow(Icons.Rounded.CalendarMonth, "每日执行时间", "执行已开启的清理类别", value = state.dailyTimeText, onClick = onEditTime)
            VideoDivider()
            VideoListRow(Icons.Rounded.AutoAwesome, "等待执行的时限", "条件暂时不满足时继续等待", value = formatMinutes(state.dailyGraceMinutes), onClick = onEditGrace)
        }
    }
}

@Composable
private fun CategoryCard(
    item: CleanCategoryUiItem, expanded: Boolean, dailyMode: Boolean, retentionDays: Int,
    onEnabledChanged: (Boolean) -> Unit, onExpandedChanged: () -> Unit,
    onEditInterval: () -> Unit, onEditRetention: () -> Unit
) {
    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 0) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            VideoLeadingIcon(categoryIcon(item.id))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).clickable(onClick = onExpandedChanged).padding(vertical = 4.dp)) {
                Text(item.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(if (!item.enabled) "已关闭" else if (dailyMode) "每日固定时间执行" else "每 ${formatMinutes(item.intervalMinutes)}执行",
                    fontSize = 13.sp, lineHeight = 20.sp, color = if (item.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = item.enabled, onCheckedChange = onEnabledChanged)
        }
        // Always expose APK retention next to its schedule; it must never be hidden behind a different tab.
        if (item.enabled || expanded) {
            Text(if (item.id == CleanCategoryId.APK) "仅清理存储中的安装文件，不卸载应用。" else item.description,
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp), fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!dailyMode) {
                VideoDivider(start = 20)
                VideoListRow(Icons.Rounded.CalendarMonth, "执行间隔", "独立设置此类别的检查周期", value = formatMinutes(item.intervalMinutes), onClick = onEditInterval)
            }
            if (item.id == CleanCategoryId.APK) {
                VideoDivider(start = 20)
                VideoListRow(Icons.Rounded.InstallMobile, "安装包保留时间",
                    if (retentionDays == 0) "扫描到的安装包可进入自动清理范围" else "${retentionDays} 天内下载的安装包会保留",
                    value = if (retentionDays == 0) "不保留" else "$retentionDays 天", onClick = onEditRetention)
            }
        }
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
