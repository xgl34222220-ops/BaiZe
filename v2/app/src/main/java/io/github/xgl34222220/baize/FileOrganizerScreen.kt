package io.github.xgl34222220.baize

import android.os.SystemClock
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FileOrganizerScreen(
    state: FileOrganizerUiState,
    schedule: FileOrganizerScheduleSettings,
    scheduleSavedText: String,
    onBack: () -> Unit,
    onOneTap: () -> Unit,
    onUndo: () -> Unit,
    onStop: () -> Unit,
    onScheduleChange: (FileOrganizerScheduleSettings) -> Unit,
    onSaveSchedule: () -> Unit,
    onToggleItem: (String) -> Unit = {},
    onToggleCategory: (String) -> Unit = {},
    onToggleAll: () -> Unit = {},
    onApply: () -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var barHeight by remember { mutableStateOf(96.dp) }
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var inspected by remember { mutableStateOf<OrganizerPreviewItem?>(null) }
    var showSchedule by rememberSaveable { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Triple<String, Set<String>, Int>?>(null) }
    val now by produceState(SystemClock.elapsedRealtime(), state.previewReady, state.expiresAtRealtime) {
        value = SystemClock.elapsedRealtime()
        while (state.previewReady && value < state.expiresAtRealtime) {
            delay(1_000L)
            value = SystemClock.elapsedRealtime()
        }
    }
    val editable = state.canEdit(now)
    val groups = remember(state.items) { state.items.groupBy { it.category } }
    val selected = remember(state.items, state.selectedIds) { state.items.filter { it.id in state.selectedIds } }
    val allSelected = state.items.isNotEmpty() && selected.size == state.items.size
    Box(Modifier.fillMaxSize().background(BaiZeTokens.colors.surfaceBase)) {
        // Keep the scroll viewport above the action bar, including accessibility scrolling.
        // Content padding alone still lets scroll-to-node place a hit target behind the bar.
        LazyColumn(Modifier.fillMaxSize().padding(bottom = barHeight).testTag("organizer-preview-list"),
            contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                DetailPageHeader("文件归类", "先预览，再将文件按类型归位", onBack) {
                    IconButton(onClick = { showSchedule = true }, enabled = !state.restoringReview && !state.running) {
                        Icon(Icons.Rounded.Schedule, "自动归类设置")
                    }
                }
            }
            item {
                DetailGlassPanel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BaiZeIconTile(BaiZeIcons.Folder)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(when {
                                state.restoringReview -> "正在恢复归类记录"
                                state.running -> "正在处理文件"
                                state.items.isNotEmpty() -> if (editable) "归类预览" else "已保留的归类记录"
                                state.lastTotal > 0 -> "已移动 ${state.lastTotal} 个文件"
                                else -> "整理散落文件"
                            }, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (state.items.isNotEmpty()) "${state.items.size} 个文件 · ${groups.size} 个类别" else "下载、接收的文件，各有去处",
                                fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    DetailStatusText(if (state.previewReady && now >= state.expiresAtRealtime) "预览已过期，记录仍可查看；重新扫描后可移动文件" else state.status,
                        Modifier.padding(top = 12.dp))
                    if (state.truncated) Text("本次扫描范围未完整覆盖；归类只处理下方已读取并勾选的文件",
                        Modifier.padding(top = 8.dp), color = BaiZeTokens.colors.warning, fontSize = 12.sp, lineHeight = 18.sp)
                    if (state.running) BaiZeProgress(Modifier.fillMaxWidth().padding(top = 14.dp))
                    if (state.items.isNotEmpty()) {
                        Text("目标：内部存储 / BaiZe归类 / 对应类型", Modifier.padding(top = 12.dp),
                            fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("同名文件：${FileOrganizerWorker.conflictPolicyLabel(schedule.conflictPolicy)}",
                            fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.items.isNotEmpty()) TextButton(onClick = onOneTap, enabled = !state.restoringReview && !state.running) { Text("重新扫描文件") }
                        if (state.undoAvailable && !state.running) TextButton(onClick = onUndo, enabled = state.connected) {
                            Icon(Icons.Rounded.Restore, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("撤销上一次归类")
                        }
                    }
                }
            }
            if (state.items.isEmpty()) {
                item { DetailSectionHeader("归类位置", "内部存储 / BaiZe归类") }
                item { DestinationCard() }
                item { SourceCard() }
                item { DetailSectionHeader("自动归类") }
                item { ScheduleCard(schedule, scheduleSavedText, onScheduleChange, onSaveSchedule) }
            } else {
                item { DetailSectionHeader("按类别选择", if (editable) "展开查看每个文件的来源和去向" else "历史预览可展开查看，移动操作已锁定") }
                groups.forEach { (category, entries) ->
                    item(key = "category:$category") {
                        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(18.dp), color = BaiZeTokens.colors.surfaceRaised) {
                            Row(Modifier.fillMaxWidth().testTag("organizer-category:$category").clickable {
                                expanded = if (category in expanded) expanded - category else expanded + category
                            }.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                BaiZeIconTile(organizerCategoryIcon(category))
                                Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(category, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                    Text("${entries.size} 个 · ${Formatter.formatFileSize(context, entries.sumOf { it.bytes })}",
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                val count = entries.count { it.id in state.selectedIds }
                                TriStateCheckbox(when { count == 0 -> ToggleableState.Off; count == entries.size -> ToggleableState.On; else -> ToggleableState.Indeterminate },
                                    onClick = { onToggleCategory(category) }, enabled = editable,
                                    modifier = Modifier.semantics { contentDescription = "选择${category}类别" })
                                Icon(if (category in expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
                            }
                        }
                    }
                    if (category in expanded) items(entries, key = { "file:${it.id}" }) { item ->
                        OrganizerPreviewRow(item, item.id in state.selectedIds, editable, { onToggleItem(item.id) }, { inspected = item })
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { barHeight = with(density) { it.height.toDp() } }) {
            if (editable) {
                CleanSelectionBar(selected.size, state.items.size, Formatter.formatFileSize(context, selected.sumOf { it.bytes }),
                    allSelected, true, onToggleAll,
                    { confirmation = Triple(state.snapshotId, state.selectedIds.toSet(), schedule.conflictPolicy) },
                    cleanLabel = "归类已选 ${selected.size} 个文件", selectLabel = "全选文件", cleanEnabled = selected.isNotEmpty())
            } else Surface(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp), color = BaiZeTokens.colors.surfaceRaised, shadowElevation = 3.dp) {
                GlassActionButton(if (state.restoringReview) "正在恢复记录" else if (state.running) "停止当前任务" else if (state.items.isEmpty()) "扫描可归类文件" else "重新扫描后归类",
                    if (state.running) onStop else onOneTap, Modifier.fillMaxWidth().padding(8.dp), enabled = !state.restoringReview, secondary = state.running)
            }
        }
    }
    inspected?.let { item ->
        BaiZeDialog(onDismissRequest = { inspected = null }, title = { Text(item.name) }, text = {
            SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${item.category} · ${Formatter.formatFileSize(context, item.bytes)}")
                Text("来源：${item.sourceGroup}\n${item.source}", fontSize = 13.sp, lineHeight = 20.sp)
                Text("归类到：\n${item.destination}", fontSize = 13.sp, lineHeight = 20.sp)
                Text("移动后，原应用可能无法从原路径找到文件；可以撤销上一次归类", fontSize = 12.sp, lineHeight = 18.sp)
            } }
        }, confirmButton = { BaiZeDialogButton({ inspected = null }) { Text("完成") } })
    }
    confirmation?.let { captured ->
        val unchanged = editable && captured.first == state.snapshotId && captured.second == state.selectedIds && captured.third == schedule.conflictPolicy
        BaiZeDialog(onDismissRequest = { confirmation = null }, title = { Text("确认移动 ${captured.second.size} 个文件") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("文件会移至内部存储 / BaiZe归类的对应类型文件夹。移动后，原应用可能无法从原路径找到文件。", fontSize = 13.sp, lineHeight = 21.sp)
                Text("同名文件：${FileOrganizerWorker.conflictPolicyLabel(captured.third)}。可撤销上一次归类。", fontSize = 13.sp, lineHeight = 21.sp)
                if (!unchanged) Text("计划或选择已变化，请返回重新核对", color = MaterialTheme.colorScheme.error)
            } }, confirmButton = { BaiZeDialogButton({ confirmation = null; onApply() }, enabled = unchanged) { Text("确认归类") } },
            dismissButton = { BaiZeDialogButton({ confirmation = null }, primary = false) { Text("返回核对") } })
    }
    if (showSchedule) BaiZeDialog(onDismissRequest = { showSchedule = false }, title = { Text("自动归类设置") },
        text = { ScheduleCard(schedule, scheduleSavedText, onScheduleChange, onSaveSchedule) },
        confirmButton = { BaiZeDialogButton({ showSchedule = false }) { Text("完成") } })
}

@Composable
private fun OrganizerPreviewRow(item: OrganizerPreviewItem, selected: Boolean, enabled: Boolean, onToggle: () -> Unit, onDetails: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 3.dp), shape = RoundedCornerShape(16.dp),
        color = BaiZeTokens.colors.surfaceRaised.copy(alpha = .72f)) {
        Row(Modifier.padding(start = 2.dp, end = 12.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
            Checkbox(selected, { onToggle() }, enabled = enabled, modifier = Modifier.semantics { contentDescription = "选择文件${item.name}" })
            Column(Modifier.weight(1f).clickable(onClickLabel = "查看来源和去向", onClick = onDetails).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(item.name, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${item.sourceGroup} · ${Formatter.formatFileSize(LocalContext.current, item.bytes)}", fontSize = 12.sp,
                    lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("从 ${item.source}", fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("到 ${item.destination}", fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun organizerCategoryIcon(category: String) = when (category) {
    "图片" -> Icons.Rounded.Image
    "视频" -> Icons.Rounded.Movie
    "音频" -> Icons.Rounded.MusicNote
    "文档" -> Icons.Rounded.Description
    "安装包" -> Icons.Rounded.InstallMobile
    "压缩包" -> Icons.Rounded.FolderZip
    "电子书" -> Icons.Rounded.MenuBook
    else -> Icons.Rounded.Folder
}

@Composable
private fun SourceCard() {
    DetailExpandableText(
        "查看归类范围",
        "查找公共下载、蓝牙接收、浏览器、网盘和聊天应用中的用户文件，以及应用的外部 files、media 目录。\n\n应用缓存、数据库、缩略图、贴纸和临时文件会跳过。归类后可以撤销上一次操作。"
    )
}

@Composable
private fun DestinationCard() {
    DetailGlassPanel {
        val categories = listOf(
            "图片" to Icons.Rounded.Image, "视频" to Icons.Rounded.Movie,
            "音频" to Icons.Rounded.MusicNote, "文档" to Icons.Rounded.Description,
            "安装包" to Icons.Rounded.InstallMobile, "压缩包" to Icons.Rounded.FolderZip,
            "电子书" to Icons.Rounded.MenuBook, "其他" to Icons.Rounded.MoreHoriz
        )
        categories.chunked(4).forEachIndexed { index, row ->
            if (index > 0) Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (label, icon) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        BaiZeIconTile(icon)
                        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleCard(
    schedule: FileOrganizerScheduleSettings,
    savedText: String,
    onChange: (FileOrganizerScheduleSettings) -> Unit,
    onSave: () -> Unit
) {
    val intervals = FileOrganizerWorker.ALLOWED_INTERVALS
    var advanced by rememberSaveable { mutableStateOf(false) }
    DetailGlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("定时归类", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                Text("自动整理新下载的文件", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Switch(checked = schedule.enabled, onCheckedChange = { onChange(schedule.copy(enabled = it)) },
                modifier = Modifier.semantics { contentDescription = "定时文件归类" })
        }
        BaiZeIntervalPicker(intervals.toList(), schedule.intervalMinutes, FileOrganizerWorker::intervalLabel,
            { onChange(schedule.copy(intervalMinutes = it)) })
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .055f))
        Text("同名文件", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0..2).forEach { policy ->
                FilterChip(
                    selected = schedule.conflictPolicy == policy,
                    onClick = { onChange(schedule.copy(conflictPolicy = policy)) },
                    label = {
                        Text(
                            FileOrganizerWorker.conflictPolicyLabel(policy),
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    },
                    border = null,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
        Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }.heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("执行条件", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                val conditions = buildList {
                    if (schedule.chargingOnly) add("充电")
                    if (schedule.screenOffOnly) add("息屏")
                    if (schedule.idleOnly) add("设备空闲")
                }
                Text(conditions.joinToString(" · ").ifBlank { "不限制执行条件" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (advanced) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                if (advanced) "收起执行条件" else "展开执行条件", Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (advanced) {
            SettingSwitch("仅充电时执行", schedule.chargingOnly) { onChange(schedule.copy(chargingOnly = it)) }
            SettingSwitch("仅息屏时执行", schedule.screenOffOnly) { onChange(schedule.copy(screenOffOnly = it)) }
            SettingSwitch("仅设备空闲时执行", schedule.idleOnly) { onChange(schedule.copy(idleOnly = it)) }
            SettingSwitch("开启计划后立即执行一次", schedule.runImmediatelyOnEnable) { onChange(schedule.copy(runImmediatelyOnEnable = it)) }
        }
        DetailStatusText("上次执行：${FileOrganizerWorker.lastRunText(LocalContext.current, schedule)}\n${schedule.lastResult}", Modifier.padding(top = 8.dp, bottom = 12.dp))
        if (savedText.isNotBlank()) Text(savedText, Modifier.padding(bottom = 10.dp), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
        GlassActionButton("保存定时归类", onSave, modifier = Modifier.fillMaxWidth(), secondary = true)
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 10.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        Switch(checked = checked, onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = label })
    }
}
