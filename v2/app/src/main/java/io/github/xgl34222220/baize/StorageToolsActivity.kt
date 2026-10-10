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
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import io.github.xgl34222220.baize.ui.theme.BaiZeTone
import io.github.xgl34222220.baize.ui.theme.BaiZeTones

enum class StorageToolMode {
    LARGE, DUPLICATES, ANALYSIS,
    /** 旧截图与录屏（参考 Files by Google「旧截图」、HyperOS 手机管家截图清理）。 */
    SCREENSHOTS,
    /** 下载目录中久未改动的文件（参考 Files by Google「已下载文件」）。 */
    OLD_DOWNLOADS,
    /** 微信 / QQ 等聊天软件已保存到公共目录的媒体；不含任何数据库或账号目录。 */
    CHAT_MEDIA,
    /** 用户自定义路径规则（参考 SD Maid SE SystemCleaner 自定义过滤器）。 */
    CUSTOM,
    /** 根目录整理：第一层文件夹的归属、空文件夹与已卸载残留，并可禁止重建。 */
    ROOT;
    val review: Boolean get() = this == SCREENSHOTS || this == OLD_DOWNLOADS || this == CHAT_MEDIA || this == CUSTOM
}

class StorageToolsActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private val model: StorageToolsViewModel by viewModels()
    private val rootModel: RootTidyViewModel by viewModels()
    private var mode = StorageToolMode.LARGE
    private val storagePermission = StoragePermissionRequest(this) { if (mode == StorageToolMode.ROOT) rootModel.resumePermission() else model.resumePermission() }
    /** 视图切换：同一个“存储分析”页面的不同视图，替换当前页而不是叠加新页面。 */
    private fun switchView(next: StorageToolMode) {
        if (next == mode) return
        runCatching { startActivity(Companion.intent(this, next)) }.onSuccess { finish() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        mode = runCatching { StorageToolMode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty()) }.getOrDefault(StorageToolMode.LARGE)
        if (mode == StorageToolMode.ROOT) {
            rootModel.initialize()
            setContent {
                val appearance by appearanceViewModel.settings.collectAsState()
                val state by rootModel.state.collectAsState()
                BaiZeTheme(appearance) {
                    RootTidyScreen(state, ::finish, ::switchView, { rootModel.scan() }, rootModel::stop, rootModel::toggle,
                        rootModel::toggleAll, rootModel::removeSelected, rootModel::undo, rootModel::setAllowed,
                        rootModel::block, rootModel::unblock, ::openAllFilesSettings)
                }
            }
            return
        }
        model.initialize(mode)
        setContent {
            val appearance by appearanceViewModel.settings.collectAsState()
            val state by model.state.collectAsState()
            val privateState by model.privateState.collectAsState()
            var detailUri by rememberSaveable { mutableStateOf<String?>(null) }
            val detail = state.allRecords.firstOrNull { it.uri == detailUri }
            BaiZeTheme(appearance) {
                StorageToolsScreen(state, ::finish, model::scan, model::toggle, model::prepareDeleteReview, ::openAllFilesSettings,
                    onToggleAll = model::toggleAll, onStop = model::stop, onQuery = { model.filter(query = it) },
                    onCategory = { model.filter(category = it) }, onSort = { model.filter(sort = it) },
                    onThreshold = { model.filter(minimumBytes = it) }, onOpen = { detailUri = it.uri },
                    onReconnect = model::connect, onLocalMode = model::enableLocalMode,
                    onKeeperPreference = model::setKeeperPreference, onKeep = model::keepCopy, onDirectory = model::directory,
                    onAge = { model.filter(minimumAgeDays = it) }, onUndo = model::undoLastTrash,
                    onSaveFilter = model::saveCustomFilter, onDeleteFilter = model::deleteCustomFilter,
                    onActiveFilter = model::selectCustomFilter, onView = ::switchView,
                    chatPrivate = if (mode == StorageToolMode.CHAT_MEDIA) { {
                        ChatPrivateMediaPanel(privateState, ChatPrivateActions(onScan = { model.scanPrivate() }, onToggle = model::togglePrivateFolder,
                            onToggleApp = model::togglePrivateApp, onAge = model::setPrivateAge, onClean = model::requestPrivateClean,
                            onStop = model::stopPrivate), enabled = !state.running)
                    } } else null)
                if (privateState.confirmRequested) ChatPrivateCleanDialog(privateState, model::confirmPrivateClean, model::dismissPrivateClean)
                if (detail != null) StorageFileDialog(detail, state.outcomes[detail.uri],
                    state.diagnosticBusy && state.diagnosticUri == detail.uri,
                    state.diagnostic.takeIf { state.diagnosticUri == detail.uri }.orEmpty(),
                    onDismiss = { detailUri = null }, onOpen = { openFile(detail) },
                    onDiagnose = { model.diagnose(detail) },
                    onExclude = { detailUri = null; CleanerNavigation.openFrom(this, WhitelistActivity.forFile(this, detail.path)) }, onCopy = {
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
    override fun onResume() { super.onResume(); if (mode == StorageToolMode.ROOT) rootModel.resumePermission() else model.resumePermission() }
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
    val diagnosticBusy: Boolean = false, val diagnosticUri: String = "", val diagnostic: String = "",
    val minimumAgeDays: Int = 0, val nowSeconds: Long = 0L,
    val customFilters: List<StorageCustomFilter> = emptyList(), val activeFilterId: String? = null,
    val lastTrashed: List<String> = emptyList(), val lastTrashedBytes: Long = 0L
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
        mode.review -> filterStorageRecords(records.filter {
            StorageReviewFilters.visible(mode, it, nowSeconds, minimumAgeDays, customFilters, activeFilterId)
        }, query, category, 0, sort)
        else -> filterStorageRecords(records, query, category, if (mode == StorageToolMode.LARGE) minimumBytes else 0, sort)
    }
    val recommended: Set<String> get() = if (mode == StorageToolMode.DUPLICATES) visibleGroups.flatMap { group ->
        val available = group.records.filter { it.uri !in selected }
        val keeper = if (group.records.any { it.uri in selected }) available.firstOrNull()
            else preferredDuplicateKeeper(group.records, keeperPreference, keeperDirectory)
        group.records.filter { it.uri != keeper?.uri }
    }.filter { it.verifiedBytes > 0 && StorageReviewFilters.bulkSelectable(mode, it) }.map { it.uri }.toSet()
        // 相机与相册原件、应用目录只读记录不进入默认勾选与“全选”，只能逐项勾选或仅查看。
        else visibleRecords.filter { it.verifiedBytes > 0 && StorageReviewFilters.bulkSelectable(mode, it) }.map { it.uri }.toSet()
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
        val target = visibleRecords.firstOrNull { it.uri == key } ?: return this
        StorageReviewFilters.rowLock(mode, target)?.let { return copy(status = "$it，不能在这里勾选") }
        if (target.verifiedBytes <= 0) return this
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
    onDirectory: (String?) -> Unit = {}, onAge: (Int) -> Unit = {}, onUndo: () -> Unit = {},
    onSaveFilter: (String, String, Int, Long) -> String = { _, _, _, _ -> "" }, onDeleteFilter: (String) -> Unit = {},
    onActiveFilter: (String?) -> Unit = {}, onView: (StorageToolMode) -> Unit = {},
    /** 聊天媒体页：应用私有数据（Root）分区；为空时不显示。 */
    chatPrivate: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val visible = remember(state) { state.visibleRecords }
    // 底栏与重复组列表的派生值每次状态变化只算一次，避免一次重组里多次遍历全部结果。
    val recommended = remember(state) { state.recommended }
    val allSelected = recommended.isNotEmpty() && state.selected.containsAll(recommended)
    val selectedBytes = remember(state.selected, state.records, state.duplicateGroups, state.mode) { state.selectedBytes }
    val visibleGroups = remember(state) { if (state.mode == StorageToolMode.DUPLICATES) state.visibleGroups else emptyList() }
    // 时间筛选把全部候选文件排除时，说明原因（例如都在 90 天内），而不是只显示 0 B 与通用空状态。
    val ageHiddenCount = remember(state, visible) {
        if (state.running || visible.isNotEmpty() || state.minimumAgeDays <= 0 || state.query.isNotBlank() || state.category != null ||
            state.mode !in setOf(StorageToolMode.SCREENSHOTS, StorageToolMode.OLD_DOWNLOADS, StorageToolMode.CHAT_MEDIA)) 0
        else state.records.count { StorageReviewFilters.visible(state.mode, it, state.nowSeconds, 0, state.customFilters, state.activeFilterId) }
    }
    // 每个时间档位的候选数：默认“超过 90 天”为空时，用户能看到文件其实落在哪个档位。
    val ageCounts = remember(state.records, state.nowSeconds, state.mode, state.customFilters) {
        if (state.mode in setOf(StorageToolMode.SCREENSHOTS, StorageToolMode.OLD_DOWNLOADS, StorageToolMode.CHAT_MEDIA))
            StorageReviewFilters.ageBucketCounts(state.mode, state.records, state.nowSeconds, state.customFilters) else emptyMap()
    }
    val title = storageToolTitle(state.mode)
    val subtitle = when (state.mode) { StorageToolMode.LARGE -> "找到占用，留下需要的"; StorageToolMode.DUPLICATES -> "完整内容比对 · 每组保留一份"; StorageToolMode.ANALYSIS -> "空间去哪了，一目了然"
        StorageToolMode.SCREENSHOTS -> "旧截图与录屏，看过再清"; StorageToolMode.OLD_DOWNLOADS -> "下载目录里久未动的文件"
        StorageToolMode.CHAT_MEDIA -> "聊天软件已保存的图片、视频与文件 · 不碰聊天记录"; StorageToolMode.CUSTOM -> "按你的路径规则预览，再决定"
        StorageToolMode.ROOT -> "根目录文件夹归属与整理" }
    fun backDirectory() {
        val current = state.directory ?: return
        val volume = state.directoryUsage?.roots?.firstOrNull { current == it || current.startsWith("$it/") } ?: storageVolume("$current/file")
        onDirectory(if (current == volume) null else java.io.File(current).parent)
    }
    BackHandler(enabled = state.directory != null && !state.running) { backDirectory() }
    // 目录钻取：子目录来自扫描线程预建的层级索引，按页展开；环形图只计算当前层与下一层。
    val directoryRows = remember(state.directoryUsage, state.records, state.directory) {
        state.directoryUsage?.children(state.directory) ?: storageDirectories(state.records, state.directory)
    }
    val sunburst = remember(state.directoryUsage, state.directory) {
        state.directoryUsage?.tree?.sunburst(state.directory).orEmpty()
    }
    var directoryPages by rememberSaveable(state.directory) { mutableIntStateOf(1) }
    val shownDirectories = directoryRows.take(directoryPages * DirectoryUsageTree.PAGE_SIZE)
    BackHandler(enabled = state.mode == StorageToolMode.ANALYSIS && state.category != null && !state.running) { onCategory(null) }
    // 移入回收站后弹出「撤销」：只恢复刚才这一批，走回收站原有的恢复核对。
    val undoSnackbar = remember { SnackbarHostState() }
    TrashUndoSnackbarEffect(undoSnackbar, state.lastTrashed.takeIf { it.isNotEmpty() },
        TrashUndo.message(state.lastTrashed.size, if (state.lastTrashedBytes > 0) Formatter.formatFileSize(context, state.lastTrashedBytes) else ""),
        onUndo = onUndo)
    Scaffold(containerColor = BaiZeTokens.colors.surfaceBase,
        snackbarHost = { SnackbarHost(undoSnackbar) },
        topBar = { DetailPageHeader(title, subtitle, { if (state.directory != null && !state.running) backDirectory() else if (state.mode == StorageToolMode.ANALYSIS && state.category != null && !state.running) onCategory(null) else onBack() },
            extra = { StorageViewDropdown(state.mode, !state.running, onView) }) {
            TextButton(onClick = { CleanerNavigation.openFrom(context, Intent(context, FileTrashActivity::class.java)) }, enabled = !state.running) { Text("回收站") }
            if (state.allRecords.isNotEmpty() && !state.running && !state.permissionRequired) IconButton(onClick = onScan) {
                Icon(Icons.Rounded.Refresh, "重新扫描")
            }
        } },
        bottomBar = { if (visible.isNotEmpty() && !state.running && !state.permissionRequired) CleanSelectionBar(
            state.selected.size, visible.size, Formatter.formatFileSize(context, selectedBytes), allSelected,
            recommended.isNotEmpty(), onToggleAll, onDelete,
            cleanLabel = "移入回收站 ${state.selected.size} 项", selectLabel = if (state.mode == StorageToolMode.DUPLICATES) "勾选多余副本" else "全选当前结果",
            // 相机原件不进入“全选”，但逐项勾选后仍可确认删除。
            cleanEnabled = state.selected.isNotEmpty()) }
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
                    BaiZeMetric(if (directorySelected && currentDirectory == null) "尚未统计" else if (ageHiddenCount > 0) "均在 ${state.minimumAgeDays} 天内" else Formatter.formatFileSize(context, bytes))
                    if (directorySelected && currentDirectory != null)
                        Text("${currentDirectory.files} 个文件（含子目录）", style = MaterialTheme.typography.bodySmall)
                    Text(state.status, style = MaterialTheme.typography.bodyMedium, color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (!state.running && ageCounts.isNotEmpty() && (ageCounts[0] ?: 0) > 0)
                        Text("按时间：${StorageReviewFilters.ageBucketSummary(ageCounts)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!state.running && state.lastTrashed.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        GlassActionButton("撤销本次（恢复 ${state.lastTrashed.size} 个文件）", onUndo, Modifier.fillMaxWidth(),
                            icon = Icons.AutoMirrored.Rounded.Undo, secondary = true)
                    }
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
            if (state.mode == StorageToolMode.CHAT_MEDIA && chatPrivate != null && !state.permissionRequired) item(key = "chat-private") { chatPrivate() }
            if (state.mode == StorageToolMode.ANALYSIS && state.category == null && state.directory == null) item { DetailGlassPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BaiZeTintedIcon(Icons.Rounded.PhotoSizeSelectLarge, BaiZeTones.purple)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("照片瘦身", style = MaterialTheme.typography.titleMedium)
                        Text("预览 JPEG 压缩效果，原图始终保留", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    val swipe: @Composable (Modifier) -> Unit = { m -> BaiZeChipButton("打开滑动整理",
                        { CleanerNavigation.openFrom(context, Intent(context, SwipeReviewActivity::class.java)) }, primary = false, modifier = m, enabled = !state.running) }
                    val photo: @Composable (Modifier) -> Unit = { m -> BaiZeChipButton("打开照片瘦身",
                        { CleanerNavigation.openFrom(context, Intent(context, PhotoCompressionActivity::class.java)) }, primary = true, modifier = m, enabled = !state.running) }
                    // 窄屏或大字号时两个按钮各占一行，避免文字折行。
                    if (maxWidth.value / LocalDensity.current.fontScale < 260f) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        photo(Modifier.fillMaxWidth()); swipe(Modifier.fillMaxWidth())
                    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        swipe(Modifier); photo(Modifier)
                    }
                }
            } }
            if (state.mode == StorageToolMode.ANALYSIS && state.buckets.isNotEmpty() && state.category == null && state.directory == null) {
                item { StorageComposition(state.buckets) }
                item { DetailSectionHeader("空间构成", "点击分类，查看具体文件") }
                item(key = "buckets") {
                    // 空间构成合并为一张分组卡（HyperOS「推荐清理」样式），不再一类一卡。
                    BaiZeCard(Modifier.padding(horizontal = 16.dp)) {
                        state.buckets.forEachIndexed { index, bucket ->
                            if (index > 0) BaiZeInsetDivider()
                            StorageBucketRow(bucket, state.category == bucket.key) { onCategory(if (state.category == bucket.key) null else bucket.key) }
                        }
                    }
                }
            }
            if (state.mode == StorageToolMode.ANALYSIS && state.category == null && (state.records.isNotEmpty() || state.directoryUsage != null)) {
                item { DetailSectionHeader("目录占用", state.directory ?: "点击存储卷逐层查看") }
                if (state.directory != null) item { DetailGlassPanel { BaiZePathText(state.directory); TextButton(onClick = { backDirectory() }, enabled = !state.running) { Text("返回上级目录") } } }
                if (sunburst.isNotEmpty() && !state.running) item(key = "sunburst") {
                    StorageSunburst(sunburst, state.directory != null, { if (!state.running) onDirectory(it) }, { if (!state.running) backDirectory() })
                }
                items(shownDirectories, key = { "dir-${it.path}" }, contentType = { "storage-directory" }) { dir ->
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
                if (directoryRows.size > shownDirectories.size) item(key = "dir-more") {
                    TextButton(onClick = { directoryPages++ }, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text("显示更多目录（还有 ${directoryRows.size - shownDirectories.size} 个）")
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
            if (state.mode == StorageToolMode.CUSTOM) item(key = "custom-filters") {
                StorageCustomFilterPanel(state, onSaveFilter, onDeleteFilter, onActiveFilter)
            }
            if (state.allRecords.isNotEmpty()) {
                item { StorageFilters(state, onQuery, onCategory, onSort, onThreshold, onAge) }
                item { DetailSectionHeader(if (state.mode == StorageToolMode.ANALYSIS) storageCategoryLabel(state.category.orEmpty()) else "文件明细",
                    if (state.mode == StorageToolMode.ANALYSIS && state.directory == null && state.category == null && state.query.isBlank()) "选择上方分类，或搜索文件" else "${visible.size} 个文件 · 筛选变化后重新勾选") }
            }
            if (state.mode == StorageToolMode.DUPLICATES) {
                item { DuplicateKeeperControls(state, onKeeperPreference) }
                visibleGroups.forEachIndexed { index, group ->
                    item(key = "group-${group.key}-${group.bytesEach}") { DetailSectionHeader("重复组 ${index + 1}", "${group.records.size} 个 · 多余副本占用 ${Formatter.formatFileSize(context, group.reclaimableBytes)}") }
                    items(group.records, key = { "dup-${it.uri}" }, contentType = { "storage-duplicate" }) { record ->
                        val keeper = record.uri !in state.selected && group.records.count { it.uri !in state.selected } == 1
                        Column {
                            if (storageCategory(record) == "image") StorageComparisonThumbnail(record)
                            StorageFileRow(record, record.uri in state.selected, !state.running, keeper, { onToggle(record.uri) }, { onOpen(record) }, state.outcomes[record.uri],
                                lock = StorageReviewFilters.rowLock(state.mode, record))
                            TextButton(onClick = { onKeep(record.uri) }, enabled = !state.running && record.verifiedBytes > 0) { Text(if (keeper) "已保留这份" else "保留这份") }
                        }
                    }
                }
            } else items(visible, key = { it.uri }, contentType = { "storage-file" }) { record -> StorageFileRow(record, record.uri in state.selected, !state.running, false, { onToggle(record.uri) }, { onOpen(record) }, state.outcomes[record.uri],
                StorageReviewFilters.sourceLabel(state.mode, record) ?: if (UserMediaGuard.isUserMedia(record.path)) UserMediaGuard.INDIVIDUAL_LABEL else null,
                lock = StorageReviewFilters.rowLock(state.mode, record)) }
            if (!state.running && !state.permissionRequired && visible.isEmpty() && !(state.mode == StorageToolMode.ANALYSIS && state.directory == null && state.category == null && state.query.isBlank() && state.buckets.isNotEmpty())) {
                val directoryFiles = if (state.mode == StorageToolMode.ANALYSIS && state.directory != null)
                    state.directoryUsage?.directories?.firstOrNull { it.path == state.directory }?.files ?: 0 else 0
                item { DetailEmptyState(if (state.failed) "扫描未完成" else if (directoryFiles > 0) "目录文件尚不可操作"
                    else if (ageHiddenCount > 0) "$ageHiddenCount 个文件都在 ${state.minimumAgeDays} 天内" else "没有符合条件的文件",
                    if (state.failed) "请检查权限并重新扫描。" else if (ageHiddenCount > 0)
                        "当前筛选为「${StorageReviewFilters.ageLabel(state.minimumAgeDays)}」，可在筛选中改为更短时间或「全部时间」。"
                    else if (directoryFiles > 0)
                        "目录统计包含 $directoryFiles 个文件。当前系统索引与筛选未提供可操作文件，仅展示目录占用；可调整筛选或稍后重新扫描。"
                    else "可调整筛选条件，或重新扫描。") }
            }
        }
    }
}

@Composable
private fun StorageFilters(state: StorageToolsUiState, onQuery: (String) -> Unit, onCategory: (String?) -> Unit,
                           onSort: (StorageSort) -> Unit, onThreshold: (Long) -> Unit, onAge: (Int) -> Unit = {}) {
    val ageFilter = state.mode.review && state.mode != StorageToolMode.CUSTOM
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val summary = buildList {
        if (state.mode != StorageToolMode.ANALYSIS && state.category != null) add(storageCategoryLabel(state.category))
        if (state.mode == StorageToolMode.LARGE) add("≥ ${state.minimumBytes / StorageToolsViewModel.MIB} MB")
        if (ageFilter) add(StorageReviewFilters.ageLabel(state.minimumAgeDays))
        if (state.sort != StorageSort.SIZE) add("按${state.sort.label}排序")
    }.joinToString(" · ")
    FileQueryBar(state.query, onQuery, !state.running, "搜索文件", "筛选文件", summary) { showFilters = true }
    if (showFilters) {
        var category by remember { mutableStateOf(state.category) }
        var sort by remember { mutableStateOf(state.sort) }
        var minimum by remember { mutableStateOf(state.minimumBytes) }
        var age by remember { mutableStateOf(state.minimumAgeDays) }
        FileFilterDialog(onDismiss = { showFilters = false }, onApply = {
            if (age != state.minimumAgeDays) onAge(age)
            if (category != state.category) onCategory(category)
            if (sort != state.sort) onSort(sort)
            if (minimum != state.minimumBytes) onThreshold(minimum)
            showFilters = false
        }) {
            if (state.mode != StorageToolMode.ANALYSIS) FileFilterChoices("文件类型",
                listOf<String?>(null).map { it to "全部类型" } + state.buckets.map { it.key to it.label }, category) { category = it }
            if (state.mode == StorageToolMode.LARGE) FileFilterChoices("文件大小",
                listOf(10, 100, 500).map { it * StorageToolsViewModel.MIB to "≥ $it MB" }, minimum) { minimum = it }
            val ageCounts = if (ageFilter) StorageReviewFilters.ageBucketCounts(state.mode, state.records, state.nowSeconds, state.customFilters) else emptyMap()
            if (ageFilter) FileFilterChoices("文件时间", StorageReviewFilters.AGE_CHOICES.map { it to "${StorageReviewFilters.ageLabel(it)} · ${ageCounts[it] ?: 0}" }, age) { age = it }
            FileFilterChoices("排序", StorageSort.entries.map { it to it.label }, sort) { sort = it }
        }
    }
}

@Composable
private fun StorageFileRow(record: StorageFileRecord, selected: Boolean, enabled: Boolean, keeper: Boolean, onClick: () -> Unit, onOpen: () -> Unit,
    outcome: StorageDeleteOutcome? = null, source: String? = null, lock: String? = null) {
    val size = Formatter.formatFileSize(LocalContext.current, record.bytes)
    val date = if (record.modifiedSeconds > 0) android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", record.modifiedSeconds * 1000).toString() else "时间未知"
    val reason = outcome?.reason ?: lock ?: if (record.verifiedBytes == 0L) "待核对 · 文件身份未取得，不可勾选" else ""
    val summary = if (reason.isNotBlank()) reason else "${if (keeper) "保留副本 · " else ""}${source ?: storageSource(record)} · $date"
    DetailResultRow(record.name, size, summary, record.path, "$size · ${storageCategoryLabel(storageCategory(record))}\n$summary\n\n${record.path}",
        storageIcon(storageCategory(record)), first = true, last = true, selected = selected, selectionEnabled = enabled && lock == null && record.verifiedBytes > 0, onToggle = onClick, onDetails = onOpen)
}

@Composable
internal fun StorageFileDialog(record: StorageFileRecord, outcome: StorageDeleteOutcome?, diagnosticBusy: Boolean,
    diagnostic: String, onDismiss: () -> Unit, onOpen: () -> Unit, onDiagnose: () -> Unit, onCopy: (String) -> Unit,
    onExclude: (() -> Unit)? = null) {
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
            if (onExclude != null) TextButton(onClick = onExclude, enabled = !diagnosticBusy) { Text("加入白名单，保护此文件") }
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
    val color = storageColor(bucket.key)
    BaiZeListRow(bucket.label, onClick, subtitle = "${bucket.files} 个文件",
        value = Formatter.formatFileSize(context, bucket.bytes),
        leading = { BaiZeTintedIcon(storageIcon(bucket.key), BaiZeTone(color, color)) }, chevron = !selected)
}

private val sunburstPalette = listOf(Color(0xFF3978F6), Color(0xFF8A6BEF), Color(0xFFE6A13D), Color(0xFF34A88B),
    Color(0xFFDC759B), Color(0xFF5AABC0))

/** 环形占用图（参考 XClean / SD Maid SE StorageAnalyzer）：内圈子目录、外圈孙目录，点按扇区钻取，点中心返回上一层。 */
@Composable
private fun StorageSunburst(segments: List<SunburstSegment>, nested: Boolean, onSegment: (String) -> Unit, onCenter: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val other = Color(0xFF8A94A5)
    val colored = remember(segments) {
        var top = -1
        segments.map { segment ->
            if (segment.level == 0) top++
            segment to if (segment.path == null) other else sunburstPalette[top.coerceAtLeast(0) % sunburstPalette.size]
        }
    }
    val select by rememberUpdatedState(onSegment)
    val back by rememberUpdatedState(onCenter)
    val firstRing = segments.count { it.level == 0 && it.path != null }
    DetailGlassPanel {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(6.dp)
            .semantics { contentDescription = "目录占用环形图，点按扇区进入目录" + if (nested) "，点按中心返回上一层" else "" }
            .pointerInput(segments, nested) {
                detectTapGestures { position ->
                    val cx = size.width / 2f; val cy = size.height / 2f
                    val outer = minOf(cx, cy); val inner = outer * SUNBURST_HOLE
                    val dx = position.x - cx; val dy = position.y - cy
                    val hit = sunburstHit(segments, dx, dy, inner, (outer - inner) / 2f)
                    if (hit?.path != null) select(hit.path)
                    else if (nested && kotlin.math.sqrt(dx * dx + dy * dy) < inner) back()
                }
            }, contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val outer = size.minDimension / 2f
                val inner = outer * SUNBURST_HOLE
                val ring = (outer - inner) / 2f
                colored.forEach { (segment, color) ->
                    val sweep = (segment.sweep * 360.0).toFloat()
                    if (sweep < .4f) return@forEach
                    val gap = if (sweep > 3f) 1.2f else 0f
                    val radius = inner + ring * segment.level + ring / 2f
                    drawArc(color.copy(alpha = if (segment.level == 0) .92f else .5f),
                        startAngle = -90f + (segment.start * 360.0).toFloat() + gap / 2f, sweepAngle = sweep - gap, useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius),
                        size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = ring - 2.dp.toPx()))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$firstRing 个子目录", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(if (nested) "点中心回上层" else "点扇区逐层看", fontSize = 11.sp, color = scheme.onSurfaceVariant)
            }
        }
        Text("内圈为子目录，外圈为下一层；占比过小的目录合并为灰色。", style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant)
    }
}

private const val SUNBURST_HOLE = .38f

internal fun storageToolTitle(mode: StorageToolMode): String = when (mode) {
    StorageToolMode.LARGE -> "大文件"; StorageToolMode.DUPLICATES -> "重复文件"; StorageToolMode.ANALYSIS -> "存储分析"
    StorageToolMode.SCREENSHOTS -> "截图录屏"; StorageToolMode.OLD_DOWNLOADS -> "旧下载"
    StorageToolMode.CHAT_MEDIA -> "聊天媒体"; StorageToolMode.CUSTOM -> "自定义规则"; StorageToolMode.ROOT -> "根目录"
}

/** 自定义规则列表：选中一条只筛选当前结果；新增或删除规则后重新扫描。 */
@Composable
private fun StorageCustomFilterPanel(state: StorageToolsUiState, onSave: (String, String, Int, Long) -> String,
    onDelete: (String) -> Unit, onActive: (String?) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    DetailGlassPanel {
        Text("我的规则", style = MaterialTheme.typography.titleMedium)
        Text("路径相对存储根目录，* 匹配一层、** 跨目录。结果先预览，勾选后才会移入回收站；Android、隐藏目录、聊天记录与数据库文件始终跳过。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.customFilters.isEmpty()) Text("还没有规则，先新建一条。", style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp))
        else FileFilterChoices("当前显示", listOf<String?>(null).map { it to "全部规则" } +
            state.customFilters.map { it.id to it.name }, state.activeFilterId) { if (!state.running) onActive(it) }
        state.customFilters.forEach { filter ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(filter.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BaiZePathText(filter.summary)
                }
                TextButton(onClick = { pendingDelete = filter.id }, enabled = !state.running) { Text("删除") }
            }
        }
        Spacer(Modifier.height(8.dp))
        GlassActionButton("新建规则", { editing = true }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add, secondary = true,
            enabled = !state.running && state.customFilters.size < StorageReviewFilters.MAX_CUSTOM_FILTERS)
    }
    pendingDelete?.let { id ->
        val filter = state.customFilters.firstOrNull { it.id == id }
        if (filter == null) pendingDelete = null else BaiZeDialog(onDismissRequest = { pendingDelete = null },
            title = { Text("删除规则") }, text = { Text("只删除规则“${filter.name}”，不会删除任何文件。") },
            confirmButton = { BaiZeDialogButton(onClick = { pendingDelete = null; onDelete(id) }) { Text("删除") } },
            dismissButton = { BaiZeDialogButton(onClick = { pendingDelete = null }) { Text("取消") } })
    }
    if (editing) StorageCustomFilterDialog(onDismiss = { editing = false }, onSave = onSave)
}

private val customFilterTemplates = listOf(
    Triple("下载里的压缩包", "Download/**/*.zip", 30), Triple("下载里的视频", "Download/**/*.mp4", 90),
    Triple("旧录音", "Recordings/**", 180)
)

@Composable
private fun StorageCustomFilterDialog(onDismiss: () -> Unit, onSave: (String, String, Int, Long) -> String) {
    var name by rememberSaveable { mutableStateOf("") }
    var pattern by rememberSaveable { mutableStateOf("") }
    var days by rememberSaveable { mutableStateOf("0") }
    var megabytes by rememberSaveable { mutableStateOf("0") }
    var error by rememberSaveable { mutableStateOf("") }
    BaiZeDialog(onDismissRequest = onDismiss, title = { Text("新建规则") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FileFilterChoices("常用模板", customFilterTemplates.map { it to it.first }, null as Triple<String, String, Int>?) { template ->
                if (template != null) { name = template.first; pattern = template.second; days = template.third.toString(); error = "" }
            }
            OutlinedTextField(name, { name = it.take(24) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("名称（可选）") })
            OutlinedTextField(pattern, { pattern = it; error = "" }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("路径规则") }, placeholder = { Text("Download/**/*.zip") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(days, { value -> days = value.filter(Char::isDigit).take(4) }, Modifier.weight(1f), singleLine = true,
                    label = { Text("超过天数") })
                OutlinedTextField(megabytes, { value -> megabytes = value.filter(Char::isDigit).take(7) }, Modifier.weight(1f), singleLine = true,
                    label = { Text("至少 MB") })
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { BaiZeDialogButton(onClick = {
        error = onSave(name, pattern, days.toIntOrNull() ?: 0, megabytes.toLongOrNull() ?: 0L)
        if (error.isBlank()) onDismiss()
    }) { Text("保存并预览") } }, dismissButton = { BaiZeDialogButton(onClick = onDismiss) { Text("取消") } })
}
