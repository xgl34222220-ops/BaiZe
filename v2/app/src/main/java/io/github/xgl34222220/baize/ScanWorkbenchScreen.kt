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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlinx.coroutines.delay
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
    val onQuarantine: (WorkbenchItem) -> Unit,
    val onSelectMedium: () -> Unit = {},
    val onManageWhitelist: () -> Unit = {}
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
            "medium" -> item.risk == "medium"
            "high" -> item.risk == "high"
            "unfinished" -> item.outcome.isNotBlank() && item.outcome != "未勾选，保留" &&
                item.outcome !in setOf("已清理", "已按所选缓存执行清理")
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScanWorkbenchScreen(
    appearance: AppearanceSettings,
    state: WorkbenchUiState,
    actions: WorkbenchActions
) {
    val density = LocalDensity.current
    var bottomBarHeight by remember { mutableStateOf(88.dp) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var expandedGroups by remember { mutableStateOf(emptySet<String>()) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showGuide by rememberSaveable { mutableStateOf(false) }
    var showReport by rememberSaveable { mutableStateOf(false) }
    var inspected by remember { mutableStateOf<WorkbenchItem?>(null) }
    var confirmedSelection by remember { mutableStateOf<Pair<Long, Set<String>>?>(null) }
    val now by produceState(SystemClock.elapsedRealtime(), state.scanReady, state.expiresAtRealtime) {
        value = SystemClock.elapsedRealtime()
        while (state.scanReady && value < state.expiresAtRealtime) {
            delay(1_000L)
            value = SystemClock.elapsedRealtime()
        }
    }
    val liveSnapshot = state.scanReady && now < state.expiresAtRealtime
    val lockedReason = reviewSelectionBlockReason(state, now)
    val editable = lockedReason == null
    val visibleState = if (state.scanReady && !liveSnapshot && !state.running) state.copy(
        scanReady = false, notice = WorkbenchNotice.WARNING, phase = "扫描结果已过期，请重新扫描") else state
    val presentation by produceState(WorkbenchPresentation(), state.items, state.selectedIds,
        filter, expandedGroups, state.loadingResults) {
        value = withContext(Dispatchers.Default) {
            workbenchPresentation(state.items, state.selectedIds, filter, expandedGroups, state.loadingResults)
        }
    }
    val filters = listOf("all" to "全部", "medium" to "中风险", "high" to "高风险",
        "unselected" to "待处理", "unfinished" to "未完成", "blocked" to "不可选",
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
                    IconButton(onClick = actions.onManageWhitelist, enabled = !state.running && !state.loadingResults) {
                        Icon(Icons.Rounded.Shield, "管理白名单", Modifier.size(22.dp))
                    }
                    IconButton(onClick = { showGuide = true }) { Icon(Icons.Rounded.Info, "扫描说明", Modifier.size(22.dp)) }
                }
            }
            if (state.items.isEmpty()) {
                item { WorkbenchEmptyCard(visibleState, onDetails = { showReport = true }) }
            } else {
                item {
                    WorkbenchSummaryCard(visibleState, presentation, selected.size, selectedHigh.size,
                        selected.any { it.bytes < 0L }, onDetails = { showReport = true })
                }
                item {
                    Column(Modifier.padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("应用与文件", Modifier.weight(1f), style = BaiZeTokens.type.title)
                            TextButton(onClick = { showFilters = true },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                contentPadding = PaddingValues(horizontal = 10.dp)) {
                                Text(filters.first { it.first == filter }.second, fontSize = 12.sp)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Rounded.Tune, "筛选结果", Modifier.size(17.dp))
                            }
                        }
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(actions.onSelectAll, enabled = editable, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text("全选低、中风险", fontSize = 13.sp)
                            }
                            TextButton(actions.onSelectMedium, enabled = editable, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text("仅选中风险", fontSize = 13.sp)
                            }
                            TextButton(actions.onClear, enabled = editable && selected.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text("清空选择", fontSize = 13.sp)
                            }
                        }
                        Text(lockedReason ?: "批量选择作用于全部扫描结果；高风险请展开后逐项选择。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.items.any { it.outcome.isNotBlank() && it.outcome != "未勾选，保留" &&
                                it.outcome !in setOf("已清理", "已按所选缓存执行清理") }) {
                            TextButton(onClick = { filter = "unfinished" }) { Text("查看未完成项目") }
                        }
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
                            row.item, row.item.id in state.selectedIds, editable, lockedReason,
                            onToggle = { actions.onToggleItem(row.item.id) }, onDetails = { inspected = row.item }
                        )
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .onSizeChanged { bottomBarHeight = with(density) { it.height.toDp() } }
            .padding(horizontal = 20.dp).padding(top = 12.dp, bottom = inset + 12.dp)) {
            Surface(color = BaiZeTokens.colors.surfaceRaised.copy(alpha = .97f),
                tonalElevation = 0.dp, shadowElevation = 8.dp, shape = RoundedCornerShape(24.dp)) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (liveSnapshot && !state.running) IconButton(onClick = actions.onScan,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                            .background(BaiZeTokens.colors.surfaceOverlay)) {
                        Icon(Icons.Rounded.Refresh, "重扫", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    GlassActionButton(
                        label = when { state.running -> "停止当前任务"; liveSnapshot -> "清理已选 ${selected.size} 项";
                            !state.connected -> "重新连接并扫描"; state.items.isNotEmpty() || state.notice == WorkbenchNotice.ERROR -> "重新扫描"; else -> "开始扫描" },
                        onClick = when { state.running -> actions.onStop; liveSnapshot -> clean; else -> actions.onScan },
                        modifier = Modifier.weight(1f),
                        enabled = if (liveSnapshot && !state.running) canClean else true,
                        secondary = state.running,
                        icon = if (state.running) Icons.Rounded.Stop else if (liveSnapshot) Icons.Rounded.CleaningServices else Icons.Rounded.Search
                    )
                }
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
        append("应用行复选框只批量选择低、中风险；全部是高风险的分组请点“逐项选择”。\n\n")
        append("白名单、关键系统数据和已变化的文件会继续保留，具体原因在每项下方显示。\n\n")
        append("应用及路径白名单都可在右上角盾牌入口管理。取消保护不等于立即删除，之后需重新扫描。\n\n")
        append("扫描结果保留 30 分钟；过期或执行结果未确认时，需要重新扫描。大小未完成统计的项目仍可显示。\n\n")
        append("当前策略：${state.policyTitle}")
    }) { showGuide = false }
    if (showReport) WorkbenchInfoDialog(if (liveSnapshot || state.running) "任务详情" else "上次任务详情",
        listOf(visibleState.phase, if (!liveSnapshot && state.items.isNotEmpty()) REVIEW_HISTORY_HINT else "", state.resultText, state.currentPath)
        .filter { it.isNotBlank() }.distinct().joinToString("\n\n")) { showReport = false }
    inspected?.let { item ->
        AlertDialog(onDismissRequest = { inspected = null }, title = { Text(item.title, fontSize = 18.sp) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RiskBadge(item.risk)
                SelectionContainer { Text(listOf(item.groupTitle, item.outcome.ifBlank { item.reason }, item.path)
                    .filter { it.isNotBlank() }.joinToString("\n\n"), fontSize = 13.sp, lineHeight = 20.sp) }
                reviewItemRestriction(item, lockedReason)?.let { reason ->
                    Text(reason, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
                if (!state.running && !state.loadingResults) TextButton({ inspected = null; actions.onManageWhitelist() }) {
                    Text("管理应用 / 路径白名单")
                }
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

private fun workbenchErrorSummary(state: WorkbenchUiState): String {
    val details = "${state.phase}\n${state.resultText}"
    if (listOf("transaction failed", "deadobjectexception", "binder buffer", "failed binder transaction")
            .any { details.contains(it, ignoreCase = true) }) {
        return "清理服务通信失败，结果未确认"
    }
    if (details.contains("服务结果未确认")) return "清理服务结果未确认"
    val summary = state.phase.ifBlank { state.resultText }.replace(Regex("\\s+"), " ").trim()
    return when {
        summary.isBlank() -> "本次任务未完成"
        summary.length > 72 -> summary.take(72) + "…"
        else -> summary
    }
}

@Composable
private fun workbenchStatusColor(state: WorkbenchUiState) = when {
    state.notice == WorkbenchNotice.ERROR -> MaterialTheme.colorScheme.error
    state.notice == WorkbenchNotice.WARNING -> BaiZeTokens.colors.warning
    state.notice == WorkbenchNotice.SUCCESS && !state.running -> BaiZeTokens.colors.success
    else -> MaterialTheme.colorScheme.primary
}

private fun workbenchStatusTitle(state: WorkbenchUiState) = when {
    state.running -> if (state.loadingResults) "正在读取扫描结果" else "正在处理"
    state.notice == WorkbenchNotice.ERROR -> workbenchErrorSummary(state)
    !state.scanReady && state.items.isNotEmpty() -> reviewRecordTitle(state)
    state.notice == WorkbenchNotice.WARNING -> state.phase.ifBlank { "扫描未完成，请查看原因" }
    state.scanReady -> "扫描完成"
    state.notice == WorkbenchNotice.SUCCESS -> "任务已完成"
    !state.connected -> "等待清理服务连接"
    state.items.isNotEmpty() -> "上次结果已保留，请重新扫描"
    else -> "清理服务已就绪"
}

@Composable
private fun WorkbenchSummaryCard(
    state: WorkbenchUiState,
    presentation: WorkbenchPresentation,
    selectedCount: Int,
    highRiskCount: Int,
    hasUnknownSize: Boolean,
    onDetails: () -> Unit
) {
    val color = workbenchStatusColor(state)
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(24.dp), color = BaiZeTokens.colors.surfaceRaised) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = "查看任务详情", onClick = onDetails)
                .semantics { contentDescription = "查看任务详情" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Text(workbenchStatusTitle(state), Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(20.dp))
            Text(if (state.scanReady) "已选项目 · 预计释放" else "上次扫描记录 · 非剩余垃圾量",
                style = BaiZeTokens.type.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (state.scanReady) Formatter.formatFileSize(LocalContext.current, presentation.selectedBytes)
                else "${state.items.size} 项", Modifier.padding(top = 3.dp),
                style = BaiZeTokens.type.hero, color = MaterialTheme.colorScheme.onSurface)
            Row(Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(16.dp))
                .background(BaiZeTokens.colors.surfaceBase).padding(vertical = 12.dp)) {
                WorkbenchStat("${presentation.appCount}", "应用", Modifier.weight(1f))
                WorkbenchStat("$selectedCount", if (state.scanReady) "已选项目" else "原选择", Modifier.weight(1f))
                WorkbenchStat("${presentation.protectedCount}", "不可选", Modifier.weight(1f))
            }
            when {
                highRiskCount > 0 -> Text("含 $highRiskCount 个高风险项目，清理前需再次确认", Modifier.padding(top = 12.dp),
                    fontSize = 12.sp, lineHeight = 18.sp, color = BaiZeTokens.colors.warning)
                state.scanReady && hasUnknownSize -> Text("部分大小待统计，释放量以清理结果为准", Modifier.padding(top = 12.dp),
                    fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                !state.scanReady && !state.running -> Text(REVIEW_HISTORY_HINT, Modifier.padding(top = 12.dp),
                    fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.running) WorkbenchProgress(state)
        }
    }
}

@Composable
private fun WorkbenchStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface)
        Text(label, fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkbenchEmptyCard(state: WorkbenchUiState, onDetails: () -> Unit) {
    val color = workbenchStatusColor(state)
    val error = state.notice == WorkbenchNotice.ERROR
    val complete = state.notice == WorkbenchNotice.SUCCESS && !state.running
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(24.dp),
        color = BaiZeTokens.colors.surfaceRaised) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 8.dp, bottom = 20.dp).size(72.dp)
                .background(color.copy(alpha = .07f), RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                Icon(when { state.running -> Icons.Rounded.ManageSearch; error -> Icons.Rounded.ErrorOutline;
                    complete -> Icons.Rounded.CheckCircle; else -> Icons.Rounded.ManageSearch },
                    null, Modifier.size(34.dp), tint = color)
            }
            Text(when { state.running -> "正在查找可清理内容"; error -> workbenchErrorSummary(state);
                complete -> "没有发现可清理项目"; else -> "按应用查看清理内容" },
                fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
            Text(when { state.running -> "找到的应用与文件会陆续显示。";
                error -> "任务信息已保留。查看详情后，可以重新发起扫描。";
                complete -> "当前扫描范围内暂无可清理内容，可以稍后再试。";
                else -> "展开应用，核对文件，再选择需要清理的内容。" },
                Modifier.padding(top = 10.dp), fontSize = 13.sp, lineHeight = 21.sp,
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.running) WorkbenchProgress(state)
            else {
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(BaiZeTokens.colors.surfaceBase)
                    .clickable(onClickLabel = "查看任务详情", onClick = onDetails)
                    .semantics { contentDescription = "查看任务详情" }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (error) Icons.Rounded.Description else Icons.Rounded.Info, null,
                        Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (error || complete) "查看任务详情" else workbenchStatusTitle(state), Modifier.weight(1f),
                        fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun WorkbenchProgress(state: WorkbenchUiState) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        if (state.progressTotal > 0L) LinearProgressIndicator(
            progress = { (state.progressCurrent.toFloat() / state.progressTotal).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().clip(CircleShape))
        else LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
        if (state.currentPath.isNotBlank()) Text(state.currentPath, Modifier.padding(top = 8.dp),
            fontSize = 11.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.progressTotal > 0L) Text("${state.progressCurrent.coerceIn(0L, state.progressTotal)} / ${state.progressTotal}",
            Modifier.padding(top = 4.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkbenchGroupRow(group: WorkbenchGroup, expanded: Boolean, enabled: Boolean, onExpand: () -> Unit, onSelect: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 4.dp),
        shape = RoundedCornerShape(24.dp), color = BaiZeTokens.colors.surfaceRaised) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) "收起应用明细" else "展开应用明细", onClick = onExpand)
            .padding(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            val owner = group.items.firstOrNull()?.packageName.orEmpty()
            if (owner.isNotBlank()) ApplicationIcon(owner, group.title, Modifier.size(42.dp))
            else Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .075f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center) {
                Icon(categoryIcon(group.items.firstOrNull()?.profile.orEmpty()), null,
                    Modifier.size(23.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(group.title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${Formatter.formatFileSize(LocalContext.current, group.bytes)} · ${group.items.size} 项",
                    fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (group.selectedCount > 0) Text("已选 ${group.selectedCount} 项", fontSize = 11.sp, lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (group.selectableCount > 0) {
                TriStateCheckbox(state = when { group.bulkSelectedCount == 0 -> ToggleableState.Off;
                    group.bulkSelectedCount == group.selectableCount -> ToggleableState.On; else -> ToggleableState.Indeterminate },
                    onClick = onSelect, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = "选择${group.title}的低中风险项目" })
            } else TextButton(onClick = onExpand, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(if (group.items.any { it.selectable && it.risk == "high" }) "逐项选择" else "查看原因", fontSize = 12.sp)
            }
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null,
                Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WorkbenchCandidateRow(item: WorkbenchItem, selected: Boolean, enabled: Boolean, lockedReason: String?, onToggle: () -> Unit, onDetails: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(bottom = 4.dp)
        .clip(RoundedCornerShape(16.dp)).background(BaiZeTokens.colors.surfaceRaised.copy(alpha = .72f))
        .padding(start = 2.dp, end = 3.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
        Checkbox(selected, onCheckedChange = { onToggle() }, enabled = enabled && item.selectable && item.risk != "critical",
            modifier = Modifier.semantics { contentDescription = "选择${item.title}" })
        Column(Modifier.weight(1f).clickable(onClickLabel = "查看文件明细", onClick = onDetails).padding(top = 7.dp, bottom = 5.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(item.title, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RiskBadge(item.risk)
                Text(if (item.bytes < 0L) "大小待统计" else Formatter.formatFileSize(LocalContext.current, item.bytes),
                    fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            reviewItemRestriction(item, lockedReason)?.let { reason ->
                Text(reason, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.risk == "high" && item.selectable && enabled) Text("可单独勾选，删除前须确认路径", fontSize = 12.sp,
                color = BaiZeTokens.colors.warning)
            Text(item.outcome.ifBlank { item.path }, fontSize = 11.sp, lineHeight = 17.sp,
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
    Text(label, Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = .075f)).padding(horizontal = 6.dp, vertical = 3.dp),
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
