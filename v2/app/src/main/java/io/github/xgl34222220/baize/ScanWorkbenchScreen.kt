package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.ui.components.BaiZeDialog
import io.github.xgl34222220.baize.ui.components.BaiZeDialogButton
import android.os.SystemClock
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.lerp
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
import io.github.xgl34222220.baize.ui.components.CleanSelectionBar
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
    val onManageWhitelist: () -> Unit = {},
    val onResumeSavedScan: () -> Unit = {}
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
    val context = LocalContext.current
    var showMenu by rememberSaveable { mutableStateOf(false) }
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
    val historicalSnapshot = state.items.isNotEmpty() && !liveSnapshot && !state.running
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
    val bulkIds = remember(state.items) { reviewRiskSelection(state.items, setOf("low", "medium")) }
    val allBulkSelected = bulkIds.isNotEmpty() && state.selectedIds.containsAll(bulkIds)
    val clean: () -> Unit = {
        if (selectedHigh.isNotEmpty()) confirmedSelection = state.expiresAtRealtime to state.selectedIds.toSet()
        else actions.onClean()
    }
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(BaiZeTokens.colors.surfaceBase)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                bottom = if (historicalSnapshot) inset + 24.dp else bottomBarHeight + 16.dp
            )
        ) {
            item {
                DetailPageHeader("扫描结果", "", actions.onBack) {
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Rounded.MoreVert, "更多操作") }
                        DropdownMenu(showMenu, { showMenu = false }) {
                            if (liveSnapshot) DropdownMenuItem(text = { Text("仅选中风险") }, enabled = editable,
                                onClick = { showMenu = false; actions.onSelectMedium() })
                            DropdownMenuItem(text = { Text("重新扫描") }, enabled = !state.running && !state.loadingResults,
                                onClick = { showMenu = false; actions.onScan() })
                            DropdownMenuItem(text = { Text("管理白名单") }, enabled = !state.running && !state.loadingResults,
                                onClick = { showMenu = false; actions.onManageWhitelist() })
                            DropdownMenuItem(text = { Text("扫描说明") }, onClick = { showMenu = false; showGuide = true })
                        }
                    }
                }
            }
            if (state.items.isEmpty()) {
                item { WorkbenchEmptyCard(visibleState, onDetails = { showReport = true }) }
            } else if (historicalSnapshot) {
                item {
                    HistoricalResultGate(
                        state = visibleState,
                        onResume = actions.onResumeSavedScan,
                        onRescan = actions.onScan
                    )
                }
            } else {
                item {
                    WorkbenchSummaryCard(visibleState, presentation, selected.size, selectedHigh.size,
                        selected.any { it.bytes < 0L }, onDetails = { showReport = true })
                }
                item {
                    Column(Modifier.padding(horizontal = 16.dp).padding(top = 18.dp, bottom = 4.dp)) {
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
                        Text(lockedReason ?: "批量选择作用于全部扫描结果；高风险请展开后逐项选择。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
        if (!historicalSnapshot) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { bottomBarHeight = with(density) { it.height.toDp() } }) {
                if (liveSnapshot && !state.running) {
                    CleanSelectionBar(selected.size, state.items.count { it.selectable },
                        if (selected.any { it.bytes < 0 }) "容量待确认" else Formatter.formatFileSize(context, selected.sumOf { it.bytes }),
                        allBulkSelected, editable && bulkIds.isNotEmpty(),
                        onToggleAll = { if (allBulkSelected) actions.onClear() else actions.onSelectAll() },
                        onClean = clean, cleanLabel = "清理已选 ${selected.size} 项", selectLabel = "全选低、中风险", cleanEnabled = canClean)
                } else Surface(modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp, bottom = inset + 16.dp),
                    color = BaiZeTokens.colors.surfaceRaised.copy(alpha = .97f),
                    tonalElevation = 0.dp, shadowElevation = 8.dp, shape = RoundedCornerShape(20.dp)) {
                    GlassActionButton(
                        label = when { state.running -> "停止当前任务"; !state.connected -> "重新连接并扫描";
                            state.items.isNotEmpty() || state.notice == WorkbenchNotice.ERROR -> "重新扫描"; else -> "开始扫描" },
                        onClick = if (state.running) actions.onStop else actions.onScan,
                        modifier = Modifier.fillMaxWidth().padding(8.dp), secondary = state.running,
                        icon = if (state.running) Icons.Rounded.Stop else Icons.Rounded.Search)
                }
            }
        }
    }

    if (showFilters) BaiZeDialog(onDismissRequest = { showFilters = false }, title = { Text("筛选结果") },
        text = { Column(Modifier) {
            filters.forEach { (id, label) -> Row(Modifier.fillMaxWidth().selectable(filter == id, role = Role.RadioButton) {
                filter = id; showFilters = false
            }.heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(filter == id, null); Text(label, fontSize = 14.sp)
            } }
        } }, confirmButton = { BaiZeDialogButton({ showFilters = false }) { Text("取消") } })
    if (showGuide) WorkbenchInfoDialog("扫描与选择", buildString {
        append("按应用展开后，可以逐项选择文件。低、中风险支持批量选择，高风险需单独勾选并确认。\n\n")
        append("应用行复选框只批量选择低、中风险；全部是高风险的分组请点“逐项选择”。\n\n")
        append("白名单、关键系统数据和已变化的文件会继续保留，具体原因在每项下方显示。\n\n")
        append("应用及路径白名单都可在右上角菜单中管理。取消保护不等于立即删除，之后需重新扫描。\n\n")
        append("扫描结果保留 30 分钟；过期或执行结果未确认时，需要重新扫描。大小未完成统计的项目仍可显示。\n\n")
        append("当前策略：${state.policyTitle}")
    }) { showGuide = false }
    if (showReport) WorkbenchInfoDialog(if (liveSnapshot || state.running) "任务详情" else "上次任务详情",
        listOf(visibleState.phase, if (!liveSnapshot && state.items.isNotEmpty()) REVIEW_HISTORY_HINT else "", state.resultText, state.currentPath)
        .filter { it.isNotBlank() }.distinct().joinToString("\n\n")) { showReport = false }
    inspected?.let { item ->
        BaiZeDialog(onDismissRequest = { inspected = null }, title = { Text(item.title, fontSize = 18.sp) },
            text = { Column(Modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RiskBadge(item.risk)
                SelectionContainer { Text(listOf(item.groupTitle, item.outcome.ifBlank { item.reason }, item.path)
                    .filter { it.isNotBlank() }.joinToString("\n\n"), fontSize = 13.sp, lineHeight = 20.sp) }
                reviewItemRestriction(item, lockedReason)?.let { reason ->
                    Text(reason, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
                if (!state.running && !state.loadingResults) BaiZeDialogButton({ inspected = null; actions.onManageWhitelist() }) {
                    Text("管理应用 / 路径白名单")
                }
                if (editable && item.selectable && item.risk == "high" && state.highRiskMode != "audit") {
                    BaiZeDialogButton({ inspected = null; actions.onQuarantine(item) }) {
                        Icon(Icons.Rounded.Inventory2, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("移入隔离区")
                    }
                }
                if (editable && item.risk in setOf("low", "medium")) BaiZeDialogButton({ inspected = null; actions.onProtect(item) }) {
                    Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("加入白名单")
                }
            } },
            confirmButton = { BaiZeDialogButton({ inspected = null }) { Text("完成") } })
    }
    confirmedSelection?.let { captured ->
        val unchanged = editable && captured.first == state.expiresAtRealtime && captured.second == state.selectedIds && SystemClock.elapsedRealtime() < state.expiresAtRealtime
        BaiZeDialog(onDismissRequest = { confirmedSelection = null }, title = { Text("确认清理高风险项目", fontSize = 18.sp) },
            text = { Column(Modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("所选内容可能包含离线文件或应用数据，删除后不能直接撤销。请核对以下 ${selectedHigh.size} 个项目；也可返回逐项移入隔离区。",
                    fontSize = 13.sp, lineHeight = 20.sp)
                selectedHigh.forEach { item -> Column {
                    Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    SelectionContainer { Text(item.path, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } }
                if (!unchanged) Text("扫描状态或选择已变化，请返回重新核对。", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            } },
            confirmButton = { BaiZeDialogButton(onClick = { confirmedSelection = null; actions.onClean() }, enabled = unchanged) { Text("确认清理") } },
            dismissButton = { BaiZeDialogButton({ confirmedSelection = null }) { Text("返回核对") } })
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
private fun HistoricalResultGate(
    state: WorkbenchUiState,
    onResume: () -> Unit,
    onRescan: () -> Unit
) {
    val warning = BaiZeTokens.colors.warning
    val surface = BaiZeTokens.colors.surfaceRaised
    val warningSurface = lerp(surface, warning, .08f)
    val warningAction = lerp(surface, warning, .18f)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = warningSurface
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = warningAction
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.History,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = warning
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("上次扫描结果仅作为历史缓存", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        workbenchStatusTitle(state),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = warning
                    )
                }
            }
            Text(
                REVIEW_HISTORY_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onResume,
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = warningAction,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("继续上次扫描")
                }
                OutlinedButton(
                    onClick = onRescan,
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    border = BorderStroke(1.dp, warning.copy(alpha = .45f))
                ) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重新完整扫描")
                }
            }
        }
    }
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
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp), color = BaiZeTokens.colors.surfaceRaised) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = "查看任务详情", onClick = onDetails)
                .semantics { contentDescription = "查看任务详情" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.notice == WorkbenchNotice.SUCCESS && !state.running) BaiZeSuccessMark()
                else Box(Modifier.size(8.dp).background(color, CircleShape))
                Text(workbenchStatusTitle(state), Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(20.dp))
            Text(if (state.scanReady) "已选项目 · 预计释放" else "上次扫描缓存",
                style = BaiZeTokens.type.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BaiZeMetric(if (state.scanReady) Formatter.formatFileSize(LocalContext.current, presentation.selectedBytes)
                else "需重新扫描", Modifier.padding(top = 3.dp))
            Row(Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(16.dp))
                .background(BaiZeTokens.colors.surfaceBase).padding(vertical = 12.dp)) {
                WorkbenchStat("${presentation.appCount}", "应用", Modifier.weight(1f))
                WorkbenchStat(if (state.scanReady) "$selectedCount" else "${state.items.size}",
                    if (state.scanReady) "已选项目" else "历史项目", Modifier.weight(1f))
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
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 17.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum"
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(label, fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkbenchEmptyCard(state: WorkbenchUiState, onDetails: () -> Unit) {
    val color = workbenchStatusColor(state)
    val error = state.notice == WorkbenchNotice.ERROR
    val complete = state.notice == WorkbenchNotice.SUCCESS && !state.running
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(20.dp),
        color = BaiZeTokens.colors.surfaceRaised) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 8.dp, bottom = 20.dp).size(72.dp)
                .background(color.copy(alpha = .07f), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
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
        BaiZeProgress(progress = if (state.progressTotal > 0L)
            (state.progressCurrent.toFloat() / state.progressTotal).coerceIn(0f, 1f) else null)
        CurrentScanTarget(sampledScanText(state.currentPath))
        if (state.progressTotal > 0L) Text(
            "${state.progressCurrent.coerceIn(0L, state.progressTotal)} / ${state.progressTotal}",
            Modifier.padding(top = 5.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontFeatureSettings = "tnum"
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CurrentScanTarget(path: String) {
    val context = LocalContext.current.applicationContext
    val packageName = remember(path) { packageNameFromScanPath(path) }
    val label by produceState(initialValue = packageName.orEmpty(), packageName) {
        value = packageName?.let { pkg ->
            withContext(Dispatchers.IO) {
                runCatching {
                    @Suppress("DEPRECATION")
                    val info = context.packageManager.getApplicationInfo(pkg, 0)
                    context.packageManager.getApplicationLabel(info).toString()
                }.getOrDefault(pkg)
            }
        }.orEmpty()
    }
    val kind = remember(path) { scanTargetKind(path) }
    val location = remember(path) { scanPathContext(path) }
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 38.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (packageName != null) {
            ApplicationIcon(packageName, label.ifBlank { packageName }, Modifier.size(34.dp))
            Spacer(Modifier.width(9.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (packageName != null) "${label.ifBlank { packageName }} · $kind" else "$location · $kind",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BaiZePathText(path.ifBlank { "正在读取文件…" })
        }
    }
}

private fun packageNameFromScanPath(path: String): String? {
    val normalized = path.replace('\\', '/')
    val patterns = listOf(
        Regex("""/data/user(?:_de)?/\d+/([^/]+)"""),
        Regex("""/data/data/([^/]+)"""),
        Regex("""/Android/(?:data|media|obb)/([^/]+)""")
    )
    return patterns.firstNotNullOfOrNull { pattern ->
        pattern.find(normalized)?.groupValues?.getOrNull(1)
    }?.takeIf { it.contains('.') }
}

private fun scanTargetKind(path: String): String {
    val normalized = path.lowercase()
    return when {
        "/code_cache" in normalized -> "代码缓存"
        "/cache" in normalized -> "缓存临时文件"
        "thumbnail" in normalized -> "缩略图缓存"
        "log" in normalized -> "日志与临时记录"
        "/files/" in normalized -> "应用文件"
        else -> "正在扫描文件"
    }
}

private fun scanPathContext(path: String): String {
    val normalized = path.replace('\\', '/').lowercase()
    val root = when {
        normalized.startsWith("/storage/emulated/") || normalized.startsWith("/data/media/") -> "内部存储"
        normalized.startsWith("/data/user/") || normalized.startsWith("/data/user_de/") || normalized.startsWith("/data/data/") -> "应用内部"
        normalized.startsWith("/data/") -> "系统数据"
        else -> "文件系统"
    }
    val category = when {
        "/.recycle/" in normalized || "/recycle/" in normalized || "/.trash/" in normalized -> "回收站缓存"
        "/dcim/" in normalized || "/pictures/" in normalized -> "相册与图片"
        "/download/" in normalized -> "下载目录"
        "/android/data/" in normalized -> "应用数据"
        "/android/media/" in normalized -> "应用媒体"
        "/android/obb/" in normalized -> "应用资源"
        "/cache/" in normalized || normalized.endsWith("/cache") -> "缓存目录"
        "/log/" in normalized || "/logs/" in normalized -> "日志目录"
        else -> "扫描目录"
    }
    return "$root > $category"
}

private fun compactScanPath(path: String): String {
    val normalized = path.replace('\\', '/').trim()
    val parts = normalized.split('/').filter(String::isNotBlank)
    if (normalized.length <= 44) return normalized
    if (parts.size <= 2) return normalized.take(18) + "…" + normalized.takeLast(18)
    val head = parts.first()
    val tail = parts.takeLast(2).joinToString("/")
    return "$head/…/$tail"
}

@Composable
private fun WorkbenchGroupRow(group: WorkbenchGroup, expanded: Boolean, enabled: Boolean, onExpand: () -> Unit, onSelect: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 4.dp),
        shape = RoundedCornerShape(20.dp), color = BaiZeTokens.colors.surfaceRaised) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) "收起应用明细" else "展开应用明细", onClick = onExpand)
            .padding(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            val owner = group.items.firstOrNull()?.packageName.orEmpty()
            if (owner.isNotBlank()) {
                ApplicationIcon(owner, group.title, Modifier.size(42.dp))
            } else {
                Box(
                    Modifier.size(42.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .08f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        group.title.filterNot(Char::isWhitespace).take(2).ifBlank { "系统" },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 4.dp)
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
            BaiZePathText(item.outcome.ifBlank { item.path })
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
    BaiZeDialog(onDismissRequest = onDismiss, title = { Text(title, fontSize = 18.sp) },
        text = { SelectionContainer { Text(text, Modifier, fontSize = 13.sp, lineHeight = 20.sp) } },
        confirmButton = { BaiZeDialogButton(onDismiss) { Text("完成") } })
}

private fun categoryIcon(profile: String) = when (profile) {
    "empty" -> Icons.Rounded.Folder
    "rules", "deep" -> Icons.Rounded.Rule
    else -> Icons.Rounded.CleaningServices
}
