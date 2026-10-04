package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.ui.components.BaiZeDialog
import io.github.xgl34222220.baize.ui.components.BaiZeDialogButton
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

enum class StorageToolMode { LARGE, DUPLICATES, ANALYSIS }

class StorageToolsActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private val model: StorageToolsViewModel by viewModels()
    private val storagePermission = StoragePermissionRequest(this) { model.resumePermission() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val mode = runCatching { StorageToolMode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty()) }.getOrDefault(StorageToolMode.LARGE)
        model.initialize(mode)
        setContent {
            val appearance by appearanceViewModel.settings.collectAsState()
            val state by model.state.collectAsState()
            var detailUri by rememberSaveable { mutableStateOf<String?>(null) }
            val detail = state.allRecords.firstOrNull { it.uri == detailUri }
            BaiZeTheme(appearance) {
                StorageToolsScreen(state, ::finish, model::scan, model::toggle, model::prepareDeleteReview, ::openAllFilesSettings,
                    onToggleAll = model::toggleAll, onStop = model::stop, onQuery = { model.filter(query = it) },
                    onCategory = { model.filter(category = it) }, onSort = { model.filter(sort = it) },
                    onThreshold = { model.filter(minimumBytes = it) }, onOpen = { detailUri = it.uri },
                    onReconnect = model::connect, onLocalMode = model::enableLocalMode,
                    onKeeperPreference = model::setKeeperPreference, onKeep = model::keepCopy, onDirectory = model::directory)
                if (detail != null) StorageFileDialog(detail, state.outcomes[detail.uri],
                    state.diagnosticBusy && state.diagnosticUri == detail.uri,
                    state.diagnostic.takeIf { state.diagnosticUri == detail.uri }.orEmpty(),
                    onDismiss = { detailUri = null }, onOpen = { openFile(detail) },
                    onDiagnose = { model.diagnose(detail) }, onCopy = {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("白泽单文件读取诊断", it))
                        Toast.makeText(this, "已复制当前文件的读取诊断", Toast.LENGTH_SHORT).show()
                    })
                if (state.reviewRequested) IndexedCleanupReviewDialog(state.running, state.selected.size,
                    state.reviewMessage + "\n共 ${Formatter.formatFileSize(this, state.selectedBytes)}。" +
                        if (state.mode == StorageToolMode.DUPLICATES) "每组至少保留一份，移入回收站前会再次核对内容。" else "",
                    model::deleteSelected, model::dismissDeleteReview)
            }
        }
    }
    override fun onResume() { super.onResume(); model.resumePermission() }
    private fun openAllFilesSettings() = storagePermission.launch()
    private fun openFile(record: StorageFileRecord) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(record.uri), record.mime.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            .onFailure { Toast.makeText(this, "没有可打开此文件的应用，或文件已不可用", Toast.LENGTH_SHORT).show() }
    }
    companion object {
        const val EXTRA_MODE = "storage_tool_mode"
        fun intent(context: android.content.Context, mode: StorageToolMode) = Intent(context, StorageToolsActivity::class.java).putExtra(EXTRA_MODE, mode.name)
    }
}

internal data class StorageToolsUiState(
    val reviewRequested: Boolean = false, val reviewMessage: String = "",
    val mode: StorageToolMode = StorageToolMode.LARGE, val running: Boolean = false,
    val permissionRequired: Boolean = false, val status: String = "准备扫描", val elapsedMs: Long = 0L,
    val records: List<StorageFileRecord> = emptyList(), val duplicateGroups: List<DuplicateFileGroup> = emptyList(),
    val buckets: List<StorageAnalysisBucket> = emptyList(), val selected: Set<String> = emptySet(),
    val directoryUsage: DirectoryUsage? = null,
    val directory: String? = null, val growth: List<StorageGrowth> = emptyList(), val growthDescription: String = "",
    val query: String = "", val category: String? = null, val sort: StorageSort = StorageSort.SIZE,
    val minimumBytes: Long = 0, val coverage: String = "", val progress: StorageScanProgress? = null, val failed: Boolean = false,
    val outcomes: Map<String, StorageDeleteOutcome> = emptyMap(),
    val protectionMessage: String = "", val localModeAvailable: Boolean = false,
    val keeperPreference: DuplicateKeeperPreference = DuplicateKeeperPreference.NEWEST, val keeperDirectory: String = "",
    val diagnosticBusy: Boolean = false, val diagnosticUri: String = "", val diagnostic: String = ""
) {
    val allRecords: List<StorageFileRecord> get() = if (mode == StorageToolMode.DUPLICATES) duplicateGroups.flatMap { it.records } else records
    // Keep each duplicate group intact: filtering must not hide its retained copy.
    val visibleGroups: List<DuplicateFileGroup> get() = duplicateGroups.filter { group ->
        filterStorageRecords(group.records, query, category, 0, sort).isNotEmpty()
    }.let { groups -> when (sort) {
        StorageSort.SIZE -> groups.sortedByDescending { it.reclaimableBytes }
        StorageSort.NEWEST -> groups.sortedByDescending { it.records.maxOfOrNull { r -> r.modifiedSeconds } ?: 0 }
        StorageSort.OLDEST -> groups.sortedBy { it.records.minOfOrNull { r -> r.modifiedSeconds } ?: 0 }
        StorageSort.NAME -> groups.sortedBy { it.records.firstOrNull()?.name.orEmpty().lowercase() }
    } }
    val visibleRecords: List<StorageFileRecord> get() = when {
        mode == StorageToolMode.DUPLICATES -> visibleGroups.flatMap { it.records }
        mode == StorageToolMode.ANALYSIS && directory != null -> filterStorageRecords(records.filter { java.io.File(it.path).parent == directory }, query, category, 0, sort)
        mode == StorageToolMode.ANALYSIS && category == null && query.isBlank() -> emptyList()
        else -> filterStorageRecords(records, query, category, if (mode == StorageToolMode.LARGE) minimumBytes else 0, sort)
    }
    val recommended: Set<String> get() = if (mode == StorageToolMode.DUPLICATES) visibleGroups.flatMap { group ->
        val available = group.records.filter { it.uri !in selected }
        val keeper = if (group.records.any { it.uri in selected }) available.firstOrNull()
            else preferredDuplicateKeeper(group.records, keeperPreference, keeperDirectory)
        group.records.filter { it.uri != keeper?.uri }
    }.filter { it.verifiedBytes > 0 }.map { it.uri }.toSet() else visibleRecords.filter { it.verifiedBytes > 0 }.map { it.uri }.toSet()
    val allSelected: Boolean get() = recommended.isNotEmpty() && selected.containsAll(recommended)
    val selectedBytes: Long get() = allRecords.filter { it.uri in selected }.sumOf { it.verifiedBytes }
    fun keepCopy(key: String): StorageToolsUiState {
        if (running) return this
        val group = visibleGroups.firstOrNull { it.records.any { record -> record.uri == key && record.verifiedBytes > 0 } } ?: return this
        val groupUris = group.records.map { it.uri }.toSet()
        return copy(selected = (selected - groupUris) + group.records.filter { it.uri != key && it.verifiedBytes > 0 }.map { it.uri }, status = "已明确保留所选副本；其余副本等待你的清理确认")
    }
    fun toggleAllSelection(): StorageToolsUiState {
        if (running) return this
        val visible = visibleRecords.map { it.uri }.toSet()
        return copy(selected = if (allSelected) selected - visible else selected + recommended)
    }
    fun toggleSelection(key: String): StorageToolsUiState {
        if (running) return this
        if (key in selected) return copy(selected = selected - key)
        if (visibleRecords.none { it.uri == key && it.verifiedBytes > 0 }) return this
        if (mode == StorageToolMode.DUPLICATES) {
            val group = duplicateGroups.firstOrNull { it.records.any { r -> r.uri == key } } ?: return this
            if (group.records.count { it.uri !in selected } <= 1) return copy(status = "每组需保留一份，可先取消另一份的勾选")
        }
        return copy(selected = selected + key)
    }
}

@Composable
internal fun StorageToolsScreen(
    state: StorageToolsUiState, onBack: () -> Unit, onScan: () -> Unit, onToggle: (String) -> Unit,
    onDelete: () -> Unit, onOpenPermission: () -> Unit, onToggleAll: () -> Unit = {}, onStop: () -> Unit = {},
    onQuery: (String) -> Unit = {}, onCategory: (String?) -> Unit = {}, onSort: (StorageSort) -> Unit = {},
    onThreshold: (Long) -> Unit = {}, onOpen: (StorageFileRecord) -> Unit = {},
    onReconnect: () -> Unit = {}, onLocalMode: () -> Unit = {},
    onKeeperPreference: (DuplicateKeeperPreference, String) -> Unit = { _, _ -> }, onKeep: (String) -> Unit = {},
    onDirectory: (String?) -> Unit = {}
) {
    val context = LocalContext.current
    val visible = remember(state) { state.visibleRecords }
    val title = when (state.mode) { StorageToolMode.LARGE -> "大文件"; StorageToolMode.DUPLICATES -> "重复文件"; StorageToolMode.ANALYSIS -> "存储分析" }
    val subtitle = when (state.mode) { StorageToolMode.LARGE -> "找到占用，留下需要的"; StorageToolMode.DUPLICATES -> "完整内容比对 · 每组保留一份"; StorageToolMode.ANALYSIS -> "空间去哪了，一目了然" }
    fun backDirectory() {
        val current = state.directory ?: return
        val volume = state.directoryUsage?.roots?.firstOrNull { current == it || current.startsWith("$it/") } ?: storageVolume("$current/file")
        onDirectory(if (current == volume) null else java.io.File(current).parent)
    }
    BackHandler(enabled = state.directory != null && !state.running) { backDirectory() }
    BackHandler(enabled = state.mode == StorageToolMode.ANALYSIS && state.category != null && !state.running) { onCategory(null) }
    Scaffold(containerColor = BaiZeTokens.colors.surfaceBase,
        topBar = { DetailPageHeader(title, subtitle, { if (state.directory != null && !state.running) backDirectory() else if (state.mode == StorageToolMode.ANALYSIS && state.category != null && !state.running) onCategory(null) else onBack() }) {
            TextButton(onClick = { CleanerNavigation.openFrom(context, Intent(context, FileTrashActivity::class.java)) }, enabled = !state.running) { Text("回收站") }
            if (state.allRecords.isNotEmpty() && !state.running && !state.permissionRequired) IconButton(onClick = onScan) {
                Icon(Icons.Rounded.Refresh, "重新扫描")
            }
        } },
        bottomBar = { if (visible.isNotEmpty() && !state.running && !state.permissionRequired) CleanSelectionBar(
            state.selected.size, visible.size, Formatter.formatFileSize(context, state.selectedBytes), state.allSelected,
            state.recommended.isNotEmpty(), onToggleAll, onDelete,
            cleanLabel = "移入回收站 ${state.selected.size} 项", selectLabel = if (state.mode == StorageToolMode.DUPLICATES) "勾选多余副本" else "全选当前结果") }
    ) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                if (state.mode == StorageToolMode.ANALYSIS && state.category != null && !state.running && !state.failed && !state.permissionRequired && state.records.isNotEmpty()) {
                    DetailGlassPanel {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(storageCategoryLabel(state.category), style = MaterialTheme.typography.titleMedium)
                                Text("${visible.size} 个文件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            BaiZeMetric(Formatter.formatFileSize(context, visible.sumOf { it.verifiedBytes }), Modifier.widthIn(max = 168.dp))
                        }
                        if (state.outcomes.isNotEmpty() || state.status.startsWith("已停止"))
                            Text(state.status, style = MaterialTheme.typography.bodySmall)
                        if (state.coverage.isNotBlank()) Text(state.coverage, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else DetailGlassPanel {
                    val currentDirectory = state.directoryUsage?.directories?.firstOrNull { it.path == state.directory }
                    val directorySelected = state.mode == StorageToolMode.ANALYSIS && state.directory != null
                    val bytes = if (state.mode == StorageToolMode.DUPLICATES) state.duplicateGroups.sumOf { it.reclaimableBytes }
                        else if (directorySelected) currentDirectory?.bytes ?: 0L
                        else if (state.mode == StorageToolMode.ANALYSIS) state.directoryUsage?.bytes ?: state.records.sumOf { it.verifiedBytes } else visible.sumOf { it.verifiedBytes }
                    Text(if (state.mode == StorageToolMode.DUPLICATES) "多余副本占用" else if (directorySelected) "当前目录占用" else if (state.mode == StorageToolMode.ANALYSIS && state.directoryUsage != null) "已遍历目录占用" else "已核对文件占用",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BaiZeMetric(if (directorySelected && currentDirectory == null) "尚未统计" else Formatter.formatFileSize(context, bytes))
                    if (directorySelected && currentDirectory != null)
                        Text("${currentDirectory.files} 个文件（含子目录）", style = MaterialTheme.typography.bodySmall)
                    Text(state.status, style = MaterialTheme.typography.bodyMedium, color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (state.running) {
                        Spacer(Modifier.height(12.dp))
                        val progress = state.progress
                        BaiZeProgress(progress = if (progress != null && progress.total > 0)
                            (progress.completed.toFloat() / progress.total).coerceIn(0f, 1f) else null)
                        BaiZePathText(if (progress != null) "${progress.completed}${if (progress.total > 0) " / ${progress.total}" else " 项"} · ${progress.name}" else "正在读取文件…",
                            Modifier.padding(top = 8.dp), live = true)
                    }
                    if (state.coverage.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(state.coverage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.elapsedMs > 0) Text("用时 ${"%.1f".format(state.elapsedMs / 1000.0)} 秒", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.permissionRequired || state.running || state.allRecords.isEmpty()) Spacer(Modifier.height(14.dp))
                    when { state.permissionRequired -> GlassActionButton("开启${SharedStorageAccess.label}", onOpenPermission, Modifier.fillMaxWidth())
                        state.running -> GlassActionButton("停止", onStop, Modifier.fillMaxWidth(), secondary = true)
                        state.allRecords.isEmpty() -> GlassActionButton("重新扫描", onScan, Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh, secondary = true) }
                }
            }
            if (state.protectionMessage.isNotBlank() && !state.running) item {
                DetailGlassPanel {
                    Text(state.protectionMessage, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onReconnect) { Text("连接保护服务") }
                    if (state.localModeAvailable) TextButton(onClick = onLocalMode) { Text("仅使用本地保护规则") }
                }
            }
            if (state.mode == StorageToolMode.ANALYSIS && state.category == null && state.directory == null) item { DetailGlassPanel {
                Text("照片瘦身", style = MaterialTheme.typography.titleMedium)
                Text("预览 JPEG 压缩效果，原图始终保留", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { CleanerNavigation.openFrom(context, Intent(context, PhotoCompressionActivity::class.java)) }, enabled = !state.running) { Text("打开照片瘦身") }
            } }
            if (state.mode == StorageToolMode.ANALYSIS && state.buckets.isNotEmpty() && state.category == null && state.directory == null) {
                item { StorageComposition(state.buckets) }
                item { DetailSectionHeader("空间构成", "点击分类，查看具体文件") }
                items(state.buckets, key = { "bucket-${it.key}" }) { bucket -> StorageBucketRow(bucket, state.category == bucket.key) { onCategory(if (state.category == bucket.key) null else bucket.key) } }
            }
            if (state.mode == StorageToolMode.ANALYSIS && state.category == null && (state.records.isNotEmpty() || state.directoryUsage != null)) {
                item { DetailSectionHeader("目录占用", state.directory ?: "点击存储卷逐层查看") }
                if (state.directory != null) item { DetailGlassPanel { BaiZePathText(state.directory); TextButton(onClick = { backDirectory() }, enabled = !state.running) { Text("返回上级目录") } } }
                items(state.directoryUsage?.children(state.directory) ?: storageDirectories(state.records, state.directory), key = { "dir-${it.path}" }) { dir ->
                    DetailGlassPanel(Modifier.clickable(enabled = !state.running, onClickLabel = "打开目录 ${dir.path}") { onDirectory(dir.path) }) {
                        Text(when {
                            dir.path.matches(Regex("/data/user/[0-9]+")) -> "应用私有数据"
                            dir.path.matches(Regex("/data/user_de/[0-9]+")) -> "设备保护应用数据"
                            else -> dir.path.substringAfterLast('/')
                        }, style = MaterialTheme.typography.titleMedium)
                        if (state.directory == null) BaiZePathText(dir.path)
                        Text("${dir.files} 个文件 · ${Formatter.formatFileSize(context, dir.bytes)}（含子目录）", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.directory == null && state.growthDescription.isNotBlank()) item { DetailGlassPanel {
                    Text("最近谁长胖了", style = MaterialTheme.typography.titleMedium)
                    Text(state.growthDescription, style = MaterialTheme.typography.bodySmall)
                    state.growth.take(10).forEach { change ->
                        BaiZePathText(change.label)
                        Text("${if (change.delta > 0) "+" else "−"}${Formatter.formatFileSize(context, kotlin.math.abs(change.delta))}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (state.growth.isEmpty()) Text("暂无可比较的变化", style = MaterialTheme.typography.bodySmall)
                } }
            }
            if (state.allRecords.isNotEmpty()) {
                item { StorageFilters(state, onQuery, onCategory, onSort, onThreshold) }
                item { DetailSectionHeader(if (state.mode == StorageToolMode.ANALYSIS) storageCategoryLabel(state.category.orEmpty()) else "文件明细",
                    if (state.mode == StorageToolMode.ANALYSIS && state.directory == null && state.category == null && state.query.isBlank()) "选择上方分类，或搜索文件" else "${visible.size} 个文件 · 筛选变化后重新勾选") }
            }
            if (state.mode == StorageToolMode.DUPLICATES) {
                item { DuplicateKeeperControls(state, onKeeperPreference) }
                state.visibleGroups.forEachIndexed { index, group ->
                    item(key = "group-${group.key}-${group.bytesEach}") { DetailSectionHeader("重复组 ${index + 1}", "${group.records.size} 个 · 多余副本占用 ${Formatter.formatFileSize(context, group.reclaimableBytes)}") }
                    items(group.records, key = { "dup-${it.uri}" }) { record ->
                        val keeper = record.uri !in state.selected && group.records.count { it.uri !in state.selected } == 1
                        Column {
                            if (storageCategory(record) == "image") StorageComparisonThumbnail(record)
                            StorageFileRow(record, record.uri in state.selected, !state.running, keeper, { onToggle(record.uri) }, { onOpen(record) }, state.outcomes[record.uri])
                            TextButton(onClick = { onKeep(record.uri) }, enabled = !state.running && record.verifiedBytes > 0) { Text(if (keeper) "已保留这份" else "保留这份") }
                        }
                    }
                }
            } else items(visible, key = { it.uri }) { record -> StorageFileRow(record, record.uri in state.selected, !state.running, false, { onToggle(record.uri) }, { onOpen(record) }, state.outcomes[record.uri]) }
            if (!state.running && !state.permissionRequired && visible.isEmpty() && !(state.mode == StorageToolMode.ANALYSIS && state.directory == null && state.category == null && state.query.isBlank() && state.buckets.isNotEmpty())) {
                val directoryFiles = if (state.mode == StorageToolMode.ANALYSIS && state.directory != null)
                    state.directoryUsage?.directories?.firstOrNull { it.path == state.directory }?.files ?: 0 else 0
                item { DetailEmptyState(if (state.failed) "扫描未完成" else if (directoryFiles > 0) "目录文件尚不可操作" else "没有符合条件的文件",
                    if (state.failed) "请检查权限并重新扫描。" else if (directoryFiles > 0)
                        "目录统计包含 $directoryFiles 个文件。当前系统索引与筛选未提供可操作文件，仅展示目录占用；可调整筛选或稍后重新扫描。"
                    else "可调整筛选条件，或重新扫描。") }
            }
        }
    }
}

@Composable
private fun StorageFilters(state: StorageToolsUiState, onQuery: (String) -> Unit, onCategory: (String?) -> Unit,
                           onSort: (StorageSort) -> Unit, onThreshold: (Long) -> Unit) {
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val summary = buildList {
        if (state.mode != StorageToolMode.ANALYSIS && state.category != null) add(storageCategoryLabel(state.category))
        if (state.mode == StorageToolMode.LARGE) add("≥ ${state.minimumBytes / StorageToolsViewModel.MIB} MB")
        if (state.sort != StorageSort.SIZE) add("按${state.sort.label}排序")
    }.joinToString(" · ")
    FileQueryBar(state.query, onQuery, !state.running, "搜索文件", "筛选文件", summary) { showFilters = true }
    if (showFilters) {
        var category by remember { mutableStateOf(state.category) }
        var sort by remember { mutableStateOf(state.sort) }
        var minimum by remember { mutableStateOf(state.minimumBytes) }
        FileFilterDialog(onDismiss = { showFilters = false }, onApply = {
            if (category != state.category) onCategory(category)
            if (sort != state.sort) onSort(sort)
            if (minimum != state.minimumBytes) onThreshold(minimum)
            showFilters = false
        }) {
            if (state.mode != StorageToolMode.ANALYSIS) FileFilterChoices("文件类型",
                listOf<String?>(null).map { it to "全部类型" } + state.buckets.map { it.key to it.label }, category) { category = it }
            if (state.mode == StorageToolMode.LARGE) FileFilterChoices("文件大小",
                listOf(10, 100, 500).map { it * StorageToolsViewModel.MIB to "≥ $it MB" }, minimum) { minimum = it }
            FileFilterChoices("排序", StorageSort.entries.map { it to it.label }, sort) { sort = it }
        }
    }
}

@Composable
private fun StorageFileRow(record: StorageFileRecord, selected: Boolean, enabled: Boolean, keeper: Boolean, onClick: () -> Unit, onOpen: () -> Unit,
    outcome: StorageDeleteOutcome? = null) {
    val size = Formatter.formatFileSize(LocalContext.current, record.bytes)
    val date = if (record.modifiedSeconds > 0) android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", record.modifiedSeconds * 1000).toString() else "时间未知"
    val reason = outcome?.reason ?: if (record.verifiedBytes == 0L) "待核对 · 文件身份未取得，不可勾选" else ""
    val summary = if (reason.isNotBlank()) reason else "${if (keeper) "保留副本 · " else ""}${storageSource(record)} · $date"
    DetailResultRow(record.name, size, summary, record.path, "$size · ${storageCategoryLabel(storageCategory(record))}\n$summary\n\n${record.path}",
        storageIcon(storageCategory(record)), first = true, last = true, selected = selected, selectionEnabled = enabled && record.verifiedBytes > 0, onToggle = onClick, onDetails = onOpen)
}

@Composable
internal fun StorageFileDialog(record: StorageFileRecord, outcome: StorageDeleteOutcome?, diagnosticBusy: Boolean,
    diagnostic: String, onDismiss: () -> Unit, onOpen: () -> Unit, onDiagnose: () -> Unit, onCopy: (String) -> Unit) {
    val context = LocalContext.current
    BaiZeDialog(onDismissRequest = onDismiss, title = { Text("文件详情") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(record.name, style = MaterialTheme.typography.titleMedium)
            Text("${Formatter.formatFileSize(LocalContext.current, record.bytes)} · ${storageCategoryLabel(storageCategory(record))}")
            Text(outcome?.reason ?: if (record.verifiedBytes > 0) "扫描时已核对文件身份，删除前会再次验证" else "文件身份尚未核对，未计入可处理容量")
            androidx.compose.foundation.text.selection.SelectionContainer { Text(record.path, style = MaterialTheme.typography.bodySmall) }
            Text("诊断仅包含这一个文件的路径、读取结果、权限和版本；复制后可发来排查。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onOpen, enabled = !diagnosticBusy) { Text("打开文件") }
            if (storageCategory(record) == "image") TextButton(onClick = { CleanerNavigation.openFrom(context, Intent(context, PhotoCompressionActivity::class.java).putExtra("photo_uri", record.uri)) }, enabled = !diagnosticBusy) { Text("照片瘦身预览") }
            TextButton(onClick = if (diagnostic.isBlank()) onDiagnose else { { onCopy(diagnostic) } }, enabled = !diagnosticBusy) {
                Text(if (diagnosticBusy) "正在核对…" else if (diagnostic.isBlank()) "核对读取诊断" else "复制读取诊断")
            }
        }
    }, confirmButton = { BaiZeDialogButton(onClick = onDismiss) { Text("关闭") } })
}

private fun storageIcon(key: String): ImageVector = when (key) { "image" -> Icons.Rounded.Image; "video" -> Icons.Rounded.Movie
    "audio" -> Icons.Rounded.MusicNote; "apk" -> Icons.Rounded.InstallMobile; "archive" -> Icons.Rounded.FolderZip; else -> Icons.Rounded.Description }
private fun storageColor(key: String): Color = when (key) { "image" -> Color(0xFF3978F6); "video" -> Color(0xFF8A6BEF); "audio" -> Color(0xFFE6A13D)
    "apk" -> Color(0xFF34A88B); "archive" -> Color(0xFFDC759B); "document" -> Color(0xFF5AABC0); else -> Color(0xFF8A94A5) }

@Composable
private fun StorageComposition(buckets: List<StorageAnalysisBucket>) {
    val total = buckets.sumOf { it.bytes }.coerceAtLeast(1)
    DetailGlassPanel {
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            buckets.filter { it.bytes > 0 }.forEach { bucket -> Box(Modifier.weight((bucket.bytes.toDouble() / total).toFloat().coerceAtLeast(.001f)).fillMaxHeight().background(storageColor(bucket.key))) }
        }
        Spacer(Modifier.height(10.dp))
        Text("按共享存储中的文件类型统计", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StorageBucketRow(bucket: StorageAnalysisBucket, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    DetailGlassPanel(Modifier.clickable(onClickLabel = "查看${bucket.label}", onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = storageColor(bucket.key).copy(alpha = .12f)) {
                Icon(storageIcon(bucket.key), null, Modifier.padding(9.dp).size(22.dp), tint = storageColor(bucket.key))
            }
            Column(Modifier.weight(1f)) {
                Text(bucket.label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("${bucket.files} 个文件", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(Formatter.formatFileSize(context, bucket.bytes), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
