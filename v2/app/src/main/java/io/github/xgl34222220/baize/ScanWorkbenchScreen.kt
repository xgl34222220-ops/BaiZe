package io.github.xgl34222220.baize

import android.os.SystemClock
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class WorkbenchNotice { INFO, SUCCESS, WARNING, ERROR }

internal data class WorkbenchItem(
    val id: String,
    val source: String,
    val profile: String,
    val packageName: String,
    val appName: String,
    val category: String,
    val groupKey: String,
    val groupTitle: String,
    val title: String,
    val risk: String,
    val path: String,
    val bytes: Long,
    val files: Long,
    val directories: Long,
    val reason: String,
    val selectable: Boolean,
    val outcome: String = ""
)

internal data class WorkbenchUiState(
    val profileConnected: Boolean = false,
    val cacheConnected: Boolean = false,
    val cacheRequired: Boolean = true,
    val running: Boolean = false,
    val loadingResults: Boolean = false,
    val scanReady: Boolean = false,
    val phase: String = "等待 Root 服务",
    val progressCurrent: Long = 0L,
    val progressTotal: Long = 0L,
    val currentPath: String = "",
    val items: List<WorkbenchItem> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val expiresAtRealtime: Long = 0L,
    val resultText: String = "",
    val policyTitle: String = CleanupPolicy.BALANCED.title,
    val policyKey: String = CleanupPolicy.BALANCED.key,
    val highRiskMode: String = CleanupPolicy.BALANCED.highRiskMode,
    val notice: WorkbenchNotice = WorkbenchNotice.INFO
) {
    val connected: Boolean get() = profileConnected && (!cacheRequired || cacheConnected)
}

internal data class WorkbenchActions(
    val onBack: () -> Unit,
    val onScan: () -> Unit,
    val onStop: () -> Unit,
    val onClean: () -> Unit,
    val onToggleItem: (String) -> Unit,
    val onToggleGroup: (String) -> Unit,
    val onSelectAll: () -> Unit,
    val onClear: () -> Unit,
    val onProtect: (WorkbenchItem) -> Unit,
    val onQuarantine: (WorkbenchItem) -> Unit
)

private data class WorkbenchGroup(
    val key: String,
    val title: String,
    val items: List<WorkbenchItem>,
    val bytes: Long,
    val selectedCount: Int,
    val selectableCount: Int,
    val bulkSelectedCount: Int
)

private sealed interface WorkbenchRow {
    val key: String

    data class Group(val group: WorkbenchGroup) : WorkbenchRow {
        override val key: String = "group:${group.key}"
    }

    data class Candidate(val item: WorkbenchItem) : WorkbenchRow {
        override val key: String = "item:${item.id}"
    }
}

private data class WorkbenchPresentation(
    val groups: List<WorkbenchGroup> = emptyList(),
    val rows: List<WorkbenchRow> = emptyList(),
    val selectedBytes: Long = 0L,
    val selectableCount: Int = 0,
    val appCount: Int = 0,
    val profileCount: Int = 0,
    val protectedCount: Int = 0
)

private fun workbenchPresentation(
    items: List<WorkbenchItem>, selectedIds: Set<String>, filter: String,
    expandedGroups: Set<String>, loading: Boolean
): WorkbenchPresentation {
    val filtered = items.filter { item ->
        when (filter) {
            "unselected" -> item.id !in selectedIds && !item.outcome.startsWith("已清理") && !item.outcome.startsWith("已按所选")
            "blocked" -> !item.selectable
            "deep" -> item.profile == "deep"
            "cache" -> item.source == "cache"
            "empty" -> item.profile == "empty" || item.category.startsWith("empty")
            "rules" -> item.profile == "rules" || item.category.contains("rule") || item.category.contains("trash")
            "fragments" -> item.profile == "fragments" || item.category.contains("fragment")
            else -> true
        }
    }
    val groups = filtered.groupBy { it.groupKey }.map { (key, entries) ->
        WorkbenchGroup(key, entries.first().groupTitle, entries,
            entries.sumOf { it.bytes.coerceAtLeast(0L) },
            entries.count { it.id in selectedIds },
            entries.count { it.selectable && (it.risk == "low" || it.risk == "medium") },
            entries.count { it.selectable && it.id in selectedIds && it.risk in setOf("low", "medium") })
    }.let { if (loading) it else it.sortedByDescending { group -> group.bytes } }
    val rows = buildList<WorkbenchRow> {
        groups.forEach { group ->
            add(WorkbenchRow.Group(group))
            if (group.key in expandedGroups) group.items.forEach { add(WorkbenchRow.Candidate(it)) }
        }
    }
    return WorkbenchPresentation(groups, rows,
        items.sumOf { if (it.id in selectedIds) it.bytes.coerceAtLeast(0L) else 0L },
        items.count { it.selectable },
        items.asSequence().map { it.packageName }.filter { it.isNotBlank() }.distinct().count(),
        items.count { it.source == "profile" }, items.count { !it.selectable })
}

/** Task outcome, selection and the list have separate roles; connection is never success. */
@Composable
internal fun ScanWorkbenchScreen(
    appearance: AppearanceSettings,
    state: WorkbenchUiState,
    actions: WorkbenchActions
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var bottomBarHeight by remember { mutableStateOf(112.dp) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var expandedGroups by remember { mutableStateOf(emptySet<String>()) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showGuide by rememberSaveable { mutableStateOf(false) }
    var showReport by rememberSaveable { mutableStateOf(false) }
    var inspected by remember { mutableStateOf<WorkbenchItem?>(null) }
    var confirmedSelection by remember { mutableStateOf<Pair<Long, Set<String>>?>(null) }
    val editable = state.connected && state.scanReady && !state.running && !state.loadingResults
    val presentation by produceState(WorkbenchPresentation(), state.items, state.selectedIds,
        filter, expandedGroups, state.loadingResults) {
        value = withContext(Dispatchers.Default) {
            workbenchPresentation(state.items, state.selectedIds, filter, expandedGroups, state.loadingResults)
        }
    }
    val filters = listOf("all" to "全部", "unselected" to "待处理", "blocked" to "不可选",
        "deep" to "深度规则", "cache" to "应用缓存", "empty" to "空项目",
        "rules" to "规则垃圾", "fragments" to "残留碎片")
    val selected = remember(state.items, state.selectedIds) { state.items.filter { it.selectable && it.id in state.selectedIds } }
    val selectedHigh = remember(selected) { selected.filter { it.risk == "high" } }
    val canClean = editable && selected.isNotEmpty()
    val clean: () -> Unit = {
        if (selectedHigh.isNotEmpty()) confirmedSelection = state.expiresAtRealtime to state.selectedIds.toSet()
        else actions.onClean()
    }
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(BaiZeTokens.colors.surfaceBase)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomBarHeight + 12.dp)) {
            item {
                DetailPageHeader("扫描结果", "", actions.onBack) {
                    IconButton(onClick = { showGuide = true }) { Icon(Icons.Rounded.Info, "扫描说明", Modifier.size(22.dp)) }
                }
            }
            item { WorkbenchStatus(state, { showReport = true }) }
            if (state.items.isEmpty()) {
                item {
                    DetailEmptyState(
                        when { state.running -> "正在查找可清理内容"; state.notice == WorkbenchNotice.ERROR -> "扫描未完成";
                            state.notice == WorkbenchNotice.SUCCESS -> "没有发现可清理项目"; else -> "按应用查看清理内容" },
                        when { state.running -> "找到的应用与文件会陆续显示在这里。";
                            state.notice == WorkbenchNotice.ERROR -> "任务信息已保留，可查看详情后重试。";
                            state.notice == WorkbenchNotice.SUCCESS -> "可以稍后重新扫描，或在清理页选择其他范围。";
                            else -> "扫描后可展开应用，核对文件并选择要处理的项目。" }
                    )
                }
            } else {
                item {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 17.dp)) {
                        Text(if (state.scanReady) "已选项目 · 预计释放" else "保留的扫描记录",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (state.scanReady) Formatter.formatFileSize(context, presentation.selectedBytes)
                                else "${state.items.size} 项", fontSize = 29.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium)
                            if (state.scanReady) Text("已选 ${selected.size} 项", Modifier.padding(bottom = 5.dp),
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${presentation.appCount} 个应用 · ${presentation.profileCount} 个其他项目 · ${presentation.protectedCount} 项不可选",
                            Modifier.padding(top = 7.dp), fontSize = 12.sp, lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.scanReady && selected.any { it.bytes < 0L }) Text("部分大小尚未统计，实际释放量以清理结果为准",
                            Modifier.padding(top = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("应用与文件", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        TextButton(onClick = { showFilters = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(filters.first { it.first == filter }.second, fontSize = 13.sp)
                            Icon(Icons.Rounded.ExpandMore, "筛选结果", Modifier.size(18.dp))
                        }
                    }
                    if (editable) Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(actions.onSelectAll) { Text("选中低、中风险", fontSize = 12.sp) }
                        TextButton(actions.onClear, enabled = selected.isNotEmpty()) { Text("清空选择", fontSize = 12.sp) }
                    }
                }
                if (presentation.rows.isEmpty()) item {
                    DetailEmptyState("这个分类下没有项目", "可以切换筛选条件查看其他扫描记录。")
                }
                items(presentation.rows, key = { it.key }, contentType = { if (it is WorkbenchRow.Group) "group" else "candidate" }) { row ->
                    when (row) {
                        is WorkbenchRow.Group -> WorkbenchGroupRow(
                            row.group, row.group.key in expandedGroups, editable,
                            onExpand = { expandedGroups = expandedGroups.toMutableSet().apply {
                                if (!add(row.group.key)) remove(row.group.key)
                            } }, onSelect = { actions.onToggleGroup(row.group.key) }
                        )
                        is WorkbenchRow.Candidate -> WorkbenchCandidateRow(
                            row.item, row.item.id in state.selectedIds, editable,
                            onToggle = { actions.onToggleItem(row.item.id) }, onDetails = { inspected = row.item }
                        )
                    }
                }
            }
        }
        // The action remains reachable even when thousands of results are present.
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .onSizeChanged { bottomBarHeight = with(density) { it.height.toDp() } }, color = BaiZeTokens.colors.surfaceRaised,
            tonalElevation = 0.dp, shadowElevation = 8.dp, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp, bottom = inset + 12.dp)) {
                if (state.scanReady && !state.running) Row(Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (selectedHigh.isNotEmpty()) "包含 ${selectedHigh.size} 个高风险项目，清理前将再次确认" else "核对所选内容后清理",
                        Modifier.weight(1f), fontSize = 11.sp, lineHeight = 16.sp,
                        color = if (selectedHigh.isNotEmpty()) BaiZeTokens.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(actions.onScan, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text("重扫", fontSize = 12.sp)
                    }
                }
                GlassActionButton(
                    label = when { state.running -> "停止当前任务"; state.scanReady -> "清理已选 ${selected.size} 项";
                        !state.connected -> "重新连接并扫描"; state.items.isNotEmpty() || state.notice == WorkbenchNotice.ERROR -> "重新扫描"; else -> "开始扫描" },
                    onClick = when { state.running -> actions.onStop; state.scanReady -> clean; else -> actions.onScan },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = if (state.scanReady && !state.running) canClean else true,
                    secondary = state.running,
                    icon = if (state.running) Icons.Rounded.Stop else if (state.scanReady) Icons.Rounded.CleaningServices else Icons.Rounded.Search
                )
            }
        }
    }
    if (showFilters) AlertDialog(onDismissRequest = { showFilters = false }, title = { Text("筛选结果") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            filters.forEach { (id, label) -> Row(Modifier.fillMaxWidth().selectable(filter == id, role = Role.RadioButton) {
                filter = id; showFilters = false
            }.heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(filter == id, null); Text(label, fontSize = 14.sp)
            } }
        } }, confirmButton = { TextButton({ showFilters = false }) { Text("取消") } })
    if (showGuide) WorkbenchInfoDialog("扫描与选择", buildString {
        append("按应用展开后，可以逐项选择文件。低、中风险支持批量选择，高风险需单独勾选并确认。\n\n")
        append("白名单、关键系统数据和已变化的文件会继续保留。\n\n")
        append("扫描结果保留 30 分钟；过期或执行结果未确认时，需要重新扫描。大小未完成统计的项目仍可显示。\n\n")
        append("当前策略：${state.policyTitle}")
    }) { showGuide = false }
    if (showReport) WorkbenchInfoDialog("任务详情", listOf(state.phase, state.resultText, state.currentPath)
        .filter { it.isNotBlank() }.distinct().joinToString("\n\n")) { showReport = false }
    inspected?.let { item ->
        AlertDialog(onDismissRequest = { inspected = null }, title = { Text(item.title, fontSize = 18.sp) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RiskBadge(item.risk)
                SelectionContainer { Text(listOf(item.groupTitle, item.outcome.ifBlank { item.reason }, item.path)
                    .filter { it.isNotBlank() }.joinToString("\n\n"), fontSize = 13.sp, lineHeight = 20.sp) }
                if (!item.selectable) Text("此项目不可选：${item.reason}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                if (editable && item.selectable && item.risk == "high" && state.highRiskMode != "audit") {
                    TextButton({ inspected = null; actions.onQuarantine(item) }) {
                        Icon(Icons.Rounded.Inventory2, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("移入隔离区")
                    }
                }
                if (editable && item.risk in setOf("low", "medium")) TextButton({ inspected = null; actions.onProtect(item) }) {
                    Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("加入白名单")
                }
            } },
            confirmButton = { TextButton({ inspected = null }) { Text("完成") } })
    }
    confirmedSelection?.let { captured ->
        val unchanged = editable && captured.first == state.expiresAtRealtime && captured.second == state.selectedIds && SystemClock.elapsedRealtime() < state.expiresAtRealtime
        AlertDialog(onDismissRequest = { confirmedSelection = null }, title = { Text("确认清理高风险项目", fontSize = 18.sp) },
            text = { Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("所选内容可能包含离线文件或应用数据，删除后不能直接撤销。请核对以下 ${selectedHigh.size} 个项目；也可返回逐项移入隔离区。",
                    fontSize = 13.sp, lineHeight = 20.sp)
                selectedHigh.forEach { item -> Column {
                    Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    SelectionContainer { Text(item.path, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } }
                if (!unchanged) Text("扫描状态或选择已变化，请返回重新核对。", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            } },
            confirmButton = { TextButton(onClick = { confirmedSelection = null; actions.onClean() }, enabled = unchanged) { Text("确认清理") } },
            dismissButton = { TextButton({ confirmedSelection = null }) { Text("返回核对") } })
    }
}

@Composable
private fun WorkbenchStatus(state: WorkbenchUiState, onDetails: () -> Unit) {
    val color = when { state.notice == WorkbenchNotice.ERROR -> MaterialTheme.colorScheme.error;
        state.notice == WorkbenchNotice.WARNING -> BaiZeTokens.colors.warning;
        state.notice == WorkbenchNotice.SUCCESS && !state.running -> BaiZeTokens.colors.success;
        else -> MaterialTheme.colorScheme.primary }
    val title = when { state.running -> if (state.loadingResults) "正在读取扫描结果" else "正在处理";
        state.notice == WorkbenchNotice.ERROR -> "本次任务未完成";
        state.notice == WorkbenchNotice.WARNING -> "有项目需要核对";
        state.scanReady -> "扫描完成，可以选择项目";
        state.notice == WorkbenchNotice.SUCCESS -> "任务已完成";
        !state.connected -> "等待清理服务连接";
        state.items.isNotEmpty() -> "上次结果已保留，请重新扫描";
        else -> "清理服务已就绪" }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(16.dp))
        .background(color.copy(alpha = .055f)).clickable(onClickLabel = "查看任务详情", onClick = onDetails).padding(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(when { state.running -> Icons.Rounded.Sync; state.notice == WorkbenchNotice.ERROR -> Icons.Rounded.ErrorOutline;
                state.notice == WorkbenchNotice.WARNING -> Icons.Rounded.WarningAmber;
                state.notice == WorkbenchNotice.SUCCESS -> Icons.Rounded.CheckCircle; else -> Icons.Rounded.Info },
                null, Modifier.size(21.dp), tint = color)
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, color = color)
                if (state.running && state.currentPath.isNotBlank()) Text(state.currentPath, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, "查看任务详情", Modifier.size(18.dp), tint = color)
        }
        if (state.running) {
            Spacer(Modifier.height(10.dp))
            if (state.progressTotal > 0L) LinearProgressIndicator(
                progress = { (state.progressCurrent.toFloat() / state.progressTotal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.progressTotal > 0L) Text("${state.progressCurrent.coerceIn(0L, state.progressTotal)} / ${state.progressTotal}",
                Modifier.padding(top = 5.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WorkbenchGroupRow(group: WorkbenchGroup, expanded: Boolean, enabled: Boolean, onExpand: () -> Unit, onSelect: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 7.dp)
        .clip(RoundedCornerShape(17.dp)).background(BaiZeTokens.colors.surfaceRaised)) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) "收起应用明细" else "展开应用明细", onClick = onExpand)
            .padding(start = 4.dp, end = 12.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            TriStateCheckbox(state = when { group.bulkSelectedCount == 0 -> ToggleableState.Off;
                group.bulkSelectedCount == group.selectableCount -> ToggleableState.On; else -> ToggleableState.Indeterminate },
                onClick = onSelect, enabled = enabled && group.selectableCount > 0,
                modifier = Modifier.semantics { contentDescription = "选择${group.title}的低中风险项目" })
            val owner = group.items.firstOrNull()?.packageName.orEmpty()
            if (owner.isNotBlank()) ApplicationIcon(owner, group.title, Modifier.size(34.dp))
            else Icon(categoryIcon(group.items.firstOrNull()?.profile.orEmpty()), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(group.title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${group.items.size} 项 · 已选 ${group.selectedCount}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Formatter.formatFileSize(LocalContext.current, group.bytes), fontSize = 12.sp)
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WorkbenchCandidateRow(item: WorkbenchItem, selected: Boolean, enabled: Boolean, onToggle: () -> Unit, onDetails: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 27.dp).background(BaiZeTokens.colors.surfaceRaised.copy(alpha = .65f))
        .padding(start = 1.dp, end = 3.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.Top) {
        Checkbox(selected, onCheckedChange = { onToggle() }, enabled = enabled && item.selectable,
            modifier = Modifier.semantics { contentDescription = "选择${item.title}" })
        Column(Modifier.weight(1f).clickable(onClickLabel = "查看文件明细", onClick = onDetails).padding(top = 8.dp, bottom = 5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(item.title, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RiskBadge(item.risk)
                Text(if (item.bytes < 0L) "大小待统计" else Formatter.formatFileSize(LocalContext.current, item.bytes),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(item.outcome.ifBlank { if (!item.selectable) item.reason else item.path }, fontSize = 11.sp, lineHeight = 16.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onDetails, Modifier.size(40.dp)) { Icon(Icons.Rounded.MoreHoriz, "${item.title}详情", Modifier.size(19.dp)) }
    }
}

@Composable
private fun RiskBadge(risk: String) {
    val (label, color) = when (risk) { "low" -> "低风险" to BaiZeTokens.colors.success;
        "medium" -> "中风险" to MaterialTheme.colorScheme.primary;
        "high" -> "高风险" to BaiZeTokens.colors.warning; else -> "关键数据" to MaterialTheme.colorScheme.error }
    Text(label, Modifier.clip(RoundedCornerShape(5.dp)).background(color.copy(alpha = .075f)).padding(horizontal = 5.dp, vertical = 2.dp),
        color = color, fontSize = 10.sp, lineHeight = 14.sp)
}

@Composable
private fun WorkbenchInfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title, fontSize = 18.sp) },
        text = { SelectionContainer { Text(text, Modifier.verticalScroll(rememberScrollState()), fontSize = 13.sp, lineHeight = 20.sp) } },
        confirmButton = { TextButton(onDismiss) { Text("完成") } })
}

private fun categoryIcon(profile: String) = when (profile) {
    "empty" -> Icons.Rounded.Folder
    "rules", "deep" -> Icons.Rounded.Rule
    else -> Icons.Rounded.CleaningServices
}
