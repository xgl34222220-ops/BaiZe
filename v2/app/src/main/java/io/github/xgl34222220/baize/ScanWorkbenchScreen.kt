package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.ui.components.BaiZeDialog
import io.github.xgl34222220.baize.ui.components.BaiZeDialogButton
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
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
    val notice: WorkbenchNotice = WorkbenchNotice.INFO,
    val coverageSummary: String = "",
    val coverageIncomplete: Boolean = false,
    val restoringReview: Boolean = false,
    val scanProfile: String = "safe",
    val operation: String = "idle",
    val cleanedBytes: Long = 0L,
    val cleanedFiles: Long = 0L,
    val cleanupCompleted: Boolean = false
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
    val onResumeSavedScan: () -> Unit = {},
    val onToggleVisibleItems: ((Set<String>) -> Unit)? = null
)

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
    val focusManager = LocalFocusManager.current
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var bottomBarHeight by remember { mutableStateOf(88.dp) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf("") }
    var expandedGroupKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
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
    val liveSnapshot = state.scanReady && !state.cleanupCompleted && now < state.expiresAtRealtime
    val historicalSnapshot = state.items.isNotEmpty() && !liveSnapshot && !state.running && !state.loadingResults && !state.cleanupCompleted
    val lockedReason = if (state.cleanupCompleted) "清理记录已保留；重新扫描后可继续选择" else reviewSelectionBlockReason(state, now)
    val editable = lockedReason == null
    val visibleState = if (state.scanReady && !liveSnapshot && !state.running && !state.cleanupCompleted) state.copy(
        scanReady = false, notice = WorkbenchNotice.WARNING, phase = "扫描结果已过期，请重新扫描") else state
    val activeFilter = filter
    val activeExpandedGroups = expandedGroupKeys.toSet()
    // Capture one presentation during composition. Reading the State delegate inside
    // LazyColumn's deferred content can change its item count independently of the
    // composed item provider while asynchronous grouping completes.
    val presentation = produceState(WorkbenchPresentation(), state.items, state.selectedIds,
        activeFilter, activeExpandedGroups, state.loadingResults, query) {
        value = withContext(Dispatchers.Default) {
            workbenchPresentation(state.items, state.selectedIds, activeFilter, activeExpandedGroups, state.loadingResults, query)
        }
    }.value
    val filters = listOf("all" to "全部", "selected" to "已选", "low" to "低风险", "medium" to "中风险", "high" to "高风险",
        "unselected" to "待处理", "unfinished" to "未完成", "blocked" to "不可选",
        "deep" to "深度规则", "cache" to "应用缓存", "empty" to "空项目",
        "rules" to "规则垃圾", "fragments" to "残留碎片", "corpses" to "卸载残留", "other" to "其他项目")
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

    Box(Modifier.fillMaxSize().background(BaiZeTokens.colors.surfaceBase).imePadding()) {
        LazyColumn(
            // A scroll-to-item must not place its checkbox underneath the fixed action bar.
            Modifier.fillMaxSize().padding(bottom = bottomBarHeight)
                .testTag("scan-workbench-list"),
            contentPadding = PaddingValues(
                bottom = 16.dp
            )
        ) {
            item {
                DetailPageHeader(workbenchPageTitle(state.scanProfile), "", actions.onBack) {
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Rounded.MoreVert, "更多操作") }
                        DropdownMenu(showMenu, { showMenu = false }) {
                            if (liveSnapshot) DropdownMenuItem(text = { Text("仅选中风险") }, enabled = editable,
                                onClick = { showMenu = false; actions.onSelectMedium() })
                            DropdownMenuItem(text = { Text("重新扫描") }, enabled = !state.restoringReview && !state.running && !state.loadingResults,
                                onClick = { showMenu = false; actions.onScan() })
                            DropdownMenuItem(text = { Text("管理白名单") }, enabled = !state.restoringReview && !state.running && !state.loadingResults,
                                onClick = { showMenu = false; actions.onManageWhitelist() })
                            DropdownMenuItem(text = { Text("扫描说明") }, onClick = { showMenu = false; showGuide = true })
                        }
                    }
                }
            }
            item { WorkbenchStages(state, liveSnapshot) }
            if (state.cleanupCompleted) {
                item { WorkbenchCompletionCard(state, onDetails = { showReport = true }) }
            } else if (state.items.isEmpty()) {
                item { WorkbenchEmptyCard(visibleState, onDetails = { showReport = true }) }
            } else if (historicalSnapshot) {
                item {
                    HistoricalResultGate(
                        state = visibleState,
                        onDetails = { showReport = true }
                    )
                }
            } else {
                item {
                    WorkbenchSummaryCard(visibleState, presentation, selected.size, selectedHigh.size,
                        selected.any { it.bytes < 0L }, onDetails = { showReport = true })
                }
            }
            if (state.items.isNotEmpty()) {
                if (!state.running && !historicalSnapshot && !state.cleanupCompleted) item {
                    WorkbenchCategories(presentation.categories, activeFilter) { filter = if (filter == it) "all" else it }
                }
                item {
                    Column(Modifier.padding(horizontal = 16.dp).padding(top = 18.dp, bottom = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (state.cleanupCompleted) "本次处理记录" else "应用与文件", Modifier.weight(1f), style = BaiZeTokens.type.title)
                            TextButton(onClick = { showFilters = true },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                contentPadding = PaddingValues(horizontal = 10.dp)) {
                                Text(filters.first { it.first == activeFilter }.second, fontSize = 12.sp)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Rounded.Tune, "筛选结果", Modifier.size(17.dp))
                            }
                        }
                        Text(lockedReason ?: "展开核对文件，高风险需逐项确认",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 10.dp).testTag("workbench-search"),
                            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }), placeholder = { Text("搜索应用、文件或路径", fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(20.dp)) },
                            trailingIcon = if (query.isNotEmpty()) {{ IconButton(onClick = { query = "" }) {
                                Icon(Icons.Rounded.Close, "清除搜索", Modifier.size(18.dp))
                            } }} else null,
                            shape = RoundedCornerShape(18.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = BaiZeTokens.colors.surfaceRaised,
                                unfocusedContainerColor = BaiZeTokens.colors.surfaceRaised,
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            filters.filter { it.first in setOf("all", "selected", "high", "blocked") }.forEach { (id, label) ->
                                FilterChip(activeFilter == id, { filter = id }, modifier = Modifier.testTag("workbench-filter:$id"), label = { Text(label, fontSize = 12.sp) },
                                    border = null, shape = RoundedCornerShape(12.dp))
                            }
                        }
                        Text("显示 ${presentation.visibleCount} / ${state.items.size} 项" +
                            if (presentation.hiddenSelectedCount > 0 && liveSnapshot)
                                " · 其他筛选下已选 ${presentation.hiddenSelectedCount} 项" else "",
                            Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (presentation.rows.isEmpty()) item {
                    DetailEmptyState("这个分类下没有项目", "可以切换筛选条件查看其他扫描记录。")
                }
                items(presentation.rows, key = { it.key }, contentType = { if (it is WorkbenchRow.Group) "group" else "candidate" }) { row ->
                    when (row) {
                        is WorkbenchRow.Group -> WorkbenchGroupRow(
                            row.group, row.group.key in activeExpandedGroups, editable, historicalSnapshot || state.cleanupCompleted,
                            onExpand = { expandedGroupKeys = if (row.group.key in activeExpandedGroups)
                                expandedGroupKeys - row.group.key else expandedGroupKeys + row.group.key }, onSelect = {
                                val visibleIds = reviewRiskSelection(row.group.items, setOf("low", "medium"))
                                actions.onToggleVisibleItems?.invoke(visibleIds) ?: actions.onToggleGroup(row.group.key)
                            }
                        )
                        is WorkbenchRow.Candidate -> WorkbenchCandidateRow(
                            row.item, row.item.id in state.selectedIds, editable, lockedReason,
                            onToggle = { actions.onToggleItem(row.item.id) }, onDetails = { inspected = row.item }
                        )
                    }
                }
            }
        }
        run {
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
                    tonalElevation = 0.dp, shadowElevation = 3.dp, shape = RoundedCornerShape(24.dp)) {
                    GlassActionButton(
                        label = when { state.restoringReview -> "正在恢复记录"; state.running -> "停止当前任务";
                            historicalSnapshot -> "重新完整扫描"; state.cleanupCompleted -> "重新扫描";
                            !state.connected -> "重新连接并扫描";
                            state.items.isNotEmpty() || state.notice in setOf(WorkbenchNotice.ERROR, WorkbenchNotice.WARNING) -> "重新扫描"; else -> "开始扫描" },
                        onClick = if (state.running) actions.onStop else actions.onScan,
                        modifier = Modifier.fillMaxWidth().padding(8.dp), enabled = !state.restoringReview, secondary = state.running,
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
        } }, confirmButton = { BaiZeDialogButton({ showFilters = false }, primary = false) { Text("取消") } })
    if (showGuide) WorkbenchInfoDialog("扫描与选择", buildString {
        append("按应用展开后，可以逐项选择文件。低、中风险支持批量选择，高风险需单独勾选并确认。\n\n")
        append("底部全选作用于全部结果，应用复选框只选择当前筛选下的低、中风险项目；全部是高风险的分组请点“逐项选择”。\n\n")
        append("白名单、关键系统数据和已变化的文件会继续保留，具体原因在每项下方显示。\n\n")
        append("应用及路径白名单都可在右上角菜单中管理。取消保护不等于立即删除，之后需重新扫描。\n\n")
        append("扫描结果保留 30 分钟；过期或执行结果未确认时，需要重新扫描。大小未完成统计的项目仍可显示。\n\n")
        append("当前策略：${state.policyTitle}")
    }) { showGuide = false }
    if (showReport) WorkbenchInfoDialog(if (liveSnapshot || state.running || state.cleanupCompleted) "任务详情" else "上次任务详情",
        listOf(visibleState.phase, if (historicalSnapshot) REVIEW_HISTORY_HINT else "", state.resultText, state.currentPath)
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
    state.restoringReview -> "正在恢复扫描记录"
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

private fun workbenchPageTitle(profile: String) = when (profile) {
    "cache" -> "应用缓存"
    "deep" -> "深度清理"
    "corpses" -> "卸载残留"
    "rules" -> "规则清理"
    "empty" -> "空项目清理"
    "fragments" -> "残留清理"
    else -> "空间清理"
}

@Composable
private fun WorkbenchStages(state: WorkbenchUiState, liveSnapshot: Boolean) {
    val active = when {
        state.cleanupCompleted -> 2
        liveSnapshot || (state.running && state.operation in setOf("clean", "protect")) -> 1
        else -> 0
    }
    val labels = listOf(if (state.running && state.operation == "scan") "扫描中" else "扫描",
        when { state.running && state.operation == "clean" -> "清理中"
            state.running && state.operation == "protect" -> "保护中"
            else -> "选择" }, "完成")
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 14.dp).testTag("workbench-stages"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            val selected = active == index
            val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                .background(if (selected) color.copy(alpha = .085f) else androidx.compose.ui.graphics.Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                if (index < active) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = BaiZeTokens.colors.success)
                else Text("${index + 1}", fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = color, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkbenchCategories(categories: List<WorkbenchCategorySummary>, activeFilter: String, onFilter: (String) -> Unit) {
    if (categories.isEmpty()) return
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp)) {
        Text("发现的内容", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 2,
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { category ->
                val selected = activeFilter == category.id
                Surface(Modifier.weight(1f).testTag("workbench-category:${category.id}"), shape = RoundedCornerShape(18.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .09f) else BaiZeTokens.colors.surfaceRaised) {
                    Column(Modifier.fillMaxWidth().clickable(onClickLabel = "查看${category.label}") { onFilter(category.id) }
                        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(when(category.id) {
                                "cache" -> Icons.Rounded.CleaningServices
                                "empty" -> Icons.Rounded.FolderOpen
                                "corpses", "fragments" -> Icons.Rounded.FolderDelete
                                else -> Icons.Rounded.Rule
                            }, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(category.label, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
                        }
                        Text(if (category.hasUnknownSize) "${category.count} 项 · 部分大小待统计"
                            else "${category.count} 项 · ${Formatter.formatFileSize(context, category.bytes)}",
                            fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkbenchCompletionCard(state: WorkbenchUiState, onDetails: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("workbench-completion"),
        shape = RoundedCornerShape(22.dp), color = BaiZeTokens.colors.surfaceRaised) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.notice == WorkbenchNotice.SUCCESS) BaiZeSuccessMark()
                else Icon(Icons.Rounded.Info, null, Modifier.size(20.dp), tint = BaiZeTokens.colors.warning)
                Text(if (state.notice == WorkbenchNotice.SUCCESS) "清理完成" else "本次清理已结束",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Column {
                Text("本次实际释放", style = BaiZeTokens.type.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BaiZeMetric(Formatter.formatFileSize(LocalContext.current, state.cleanedBytes.coerceAtLeast(0L)), Modifier.padding(top = 4.dp))
            }
            Text("已清理 ${state.cleanedFiles.coerceAtLeast(0L)} 个文件", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.phase, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onDetails, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("查看本次处理详情") }
        }
    }
}

@Composable
private fun HistoricalResultGate(state: WorkbenchUiState, onDetails: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(20.dp),
        color = BaiZeTokens.colors.surfaceRaised) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BaiZeIconTile(Icons.Rounded.History)
                Column(Modifier.weight(1f)) {
                    Text("上次结果已保留", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(workbenchStatusTitle(state), fontSize = 12.sp, lineHeight = 18.sp, color = BaiZeTokens.colors.warning)
                }
            }
            Text("${state.items.size} 项记录可查看，重新扫描后可继续选择", fontSize = 13.sp, lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            WorkbenchCoverage(state)
            TextButton(onClick = onDetails, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("查看任务详情") }
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
        shape = RoundedCornerShape(22.dp), color = BaiZeTokens.colors.surfaceRaised) {
        Column(Modifier.padding(18.dp)) {
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
            Text(when { state.scanReady -> "已选项目 · 预计释放"; state.running -> "已发现项目 · 已统计容量"; else -> "上次扫描缓存" },
                style = BaiZeTokens.type.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BaiZeMetric(when {
                state.scanReady -> Formatter.formatFileSize(LocalContext.current, presentation.selectedBytes)
                state.running -> Formatter.formatFileSize(LocalContext.current, state.items.sumOf { it.bytes.coerceAtLeast(0L) })
                else -> "需重新扫描"
            }, Modifier.padding(top = 3.dp))
            Row(Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(16.dp))
                .background(BaiZeTokens.colors.surfaceBase).padding(vertical = 12.dp)) {
                WorkbenchStat("${presentation.appCount}", "应用", Modifier.weight(1f))
                WorkbenchStat(if (state.scanReady) "$selectedCount" else "${state.items.size}",
                    when { state.scanReady -> "已选项目"; state.running -> "已发现项目"; else -> "历史项目" }, Modifier.weight(1f))
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
            WorkbenchCoverage(state)
            if (state.running) WorkbenchProgress(state)
        }
    }
}

@Composable
private fun WorkbenchCoverage(state: WorkbenchUiState) {
    if (state.coverageSummary.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top) {
        Icon(if (state.coverageIncomplete) Icons.Rounded.Info else Icons.Rounded.FolderOpen, null,
            Modifier.size(17.dp), tint = if (state.coverageIncomplete) BaiZeTokens.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(state.coverageSummary, fontSize = 12.sp, lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp),
        color = BaiZeTokens.colors.surfaceRaised) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 8.dp, bottom = 20.dp).size(72.dp)
                .background(color.copy(alpha = .07f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Icon(when { state.running -> Icons.Rounded.ManageSearch; error -> Icons.Rounded.ErrorOutline;
                    complete -> Icons.Rounded.CheckCircle; else -> Icons.Rounded.ManageSearch },
                    null, Modifier.size(34.dp), tint = color)
            }
            Text(when { state.restoringReview -> "正在恢复扫描记录"; state.running -> "正在查找可清理内容"; error -> workbenchErrorSummary(state);
                state.coverageIncomplete -> "扫描范围尚未完整覆盖"; complete -> "没有发现可清理项目"; else -> "先扫描，再决定清理什么" },
                fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
            Text(when { state.restoringReview -> "上次结果读取完成后，就能继续查看。"; state.running -> if (state.loadingResults) "正在读取应用与文件，已读取的项目会陆续显示。" else "正在检查目录，完成后会按应用列出文件。";
                error -> "任务信息已保留。查看详情后，可以重新发起扫描。";
                state.coverageIncomplete -> "已检查的范围内暂无项目；未检查的目录不代表没有可清理内容。";
                complete -> "当前扫描范围内暂无可清理内容，可以稍后再试。";
                else -> "按应用查看缓存与文件，保留你需要的内容。" },
                Modifier.padding(top = 10.dp), fontSize = 13.sp, lineHeight = 21.sp,
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            WorkbenchCoverage(state)
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
private fun WorkbenchGroupRow(group: WorkbenchGroup, expanded: Boolean, enabled: Boolean, historical: Boolean, onExpand: () -> Unit, onSelect: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 4.dp),
        shape = RoundedCornerShape(16.dp), color = BaiZeTokens.colors.surfaceRaised) {
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
                if (group.selectedCount > 0) Text(if (historical) "上次已选 ${group.selectedCount} 项" else "已选 ${group.selectedCount} 项", fontSize = 11.sp, lineHeight = 15.sp,
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

@OptIn(ExperimentalLayoutApi::class)
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RiskBadge(item.risk)
                Text(if (item.bytes < 0L) "大小待统计" else Formatter.formatFileSize(LocalContext.current, item.bytes),
                    fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            reviewItemRestriction(item, null)?.let { reason ->
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
