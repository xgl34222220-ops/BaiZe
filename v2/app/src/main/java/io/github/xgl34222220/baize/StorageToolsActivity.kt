package io.github.xgl34222220.baize

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.components.CleanSelectionBar
import io.github.xgl34222220.baize.ui.components.DetailResultRow
import io.github.xgl34222220.baize.ui.components.DetailEmptyState
import io.github.xgl34222220.baize.ui.components.DetailGlassPanel
import io.github.xgl34222220.baize.ui.components.DetailPageHeader
import io.github.xgl34222220.baize.ui.components.DetailSectionHeader
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class StorageToolMode { LARGE, DUPLICATES, ANALYSIS }

class StorageToolsActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private var state by mutableStateOf(StorageToolsUiState())
    private var showDeleteConfirm by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val mode = runCatching {
            StorageToolMode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty())
        }.getOrDefault(StorageToolMode.LARGE)
        state = state.copy(mode = mode)
        setContent {
            val appearance by appearanceViewModel.settings.collectAsState()
            BaiZeTheme(appearance) {
                Surface(Modifier.fillMaxSize(), color = BaiZeTokens.colors.surfaceBase) {
                    StorageToolsScreen(
                        state = state,
                        onBack = ::finish,
                        onScan = ::scan,
                        onToggle = ::toggle,
                        onDelete = { showDeleteConfirm = true },
                        onToggleAll = ::toggleAll,
                        onOpenPermission = ::openAllFilesSettings
                    )
                    if (showDeleteConfirm) AlertDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        title = { Text("删除已选 ${state.selected.size} 个文件？") },
                        text = { Text("共 ${Formatter.formatFileSize(this@StorageToolsActivity, state.selectedBytes)}。删除后无法在白泽内恢复，请确认文件不再需要。") },
                        confirmButton = { TextButton(onClick = { showDeleteConfirm = false; deleteSelected() }) { Text("确认删除") } },
                        dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
                    )
                }
            }
        }
        scan()
    }

    private fun openAllFilesSettings() {
        runCatching {
            startActivity(Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }
    }

    private fun scan() {
        if (state.running) return
        if (!StorageMediaRepository.hasAccess()) {
            state = state.copy(
                running = false,
                permissionRequired = true,
                status = "需要“所有文件访问”才能读取完整存储索引"
            )
            return
        }
        state = state.copy(running = true, permissionRequired = false, status = "正在读取系统文件索引…", selected = emptySet())
        lifecycleScope.launch {
            when (state.mode) {
                StorageToolMode.LARGE -> {
                    val threshold = 100L * 1024L * 1024L
                    val (records, elapsed) = withContext(Dispatchers.IO) { StorageMediaRepository.largeFiles(applicationContext, threshold) }
                    state = state.copy(running = false, records = records, duplicateGroups = emptyList(), buckets = emptyList(),
                        elapsedMs = elapsed, status = "发现 ${records.size} 个大文件")
                }
                StorageToolMode.DUPLICATES -> {
                    val (groups, elapsed) = withContext(Dispatchers.IO) { StorageMediaRepository.duplicates(applicationContext) }
                    state = state.copy(running = false, records = emptyList(), duplicateGroups = groups, buckets = emptyList(),
                        elapsedMs = elapsed, status = "发现 ${groups.size} 组重复文件")
                }
                StorageToolMode.ANALYSIS -> {
                    val (buckets, elapsed) = withContext(Dispatchers.IO) { StorageMediaRepository.analyze(applicationContext) }
                    state = state.copy(running = false, records = emptyList(), duplicateGroups = emptyList(), buckets = buckets,
                        elapsedMs = elapsed, status = "存储分析完成")
                }
            }
        }
    }

    private fun toggle(key: String) {
        if (state.running || state.allRecords.none { it.uri == key }) return
        state = state.toggleSelection(key)
    }

    private fun toggleAll() {
        if (state.running) return
        state = state.toggleAllSelection()
    }

    private fun deleteSelected() {
        if (state.running || state.selected.isEmpty()) return
        val selectedRecords = state.allRecords.filter { it.uri in state.selected }
        if (selectedRecords.isEmpty()) return
        state = state.copy(running = true, status = "正在删除已选择的 ${selectedRecords.size} 个文件…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                var deleted = 0
                var bytes = 0L
                val removed = mutableSetOf<String>()
                selectedRecords.forEach { record ->
                    if (StorageMediaRepository.delete(applicationContext, record)) {
                        deleted++
                        removed += record.uri
                        bytes += record.bytes
                    }
                }
                Triple(deleted, bytes, removed)
            }
            state = state.copy(running = false, selected = state.selected - result.third,
                records = state.records.filterNot { it.uri in result.third },
                duplicateGroups = state.duplicateGroups.map { group ->
                    group.copy(records = group.records.filterNot { it.uri in result.third })
                },
                status = "已删除 ${result.first} 个文件，释放 ${Formatter.formatFileSize(this@StorageToolsActivity, result.second)}" +
                    if (result.first < selectedRecords.size) " · ${selectedRecords.size - result.first} 项未删除" else "")
        }
    }

    companion object {
        const val EXTRA_MODE = "storage_tool_mode"
        fun intent(context: android.content.Context, mode: StorageToolMode) =
            Intent(context, StorageToolsActivity::class.java).putExtra(EXTRA_MODE, mode.name)
    }
}

internal data class StorageToolsUiState(
    val mode: StorageToolMode = StorageToolMode.LARGE,
    val running: Boolean = false,
    val permissionRequired: Boolean = false,
    val status: String = "准备扫描",
    val elapsedMs: Long = 0L,
    val records: List<StorageFileRecord> = emptyList(),
    val duplicateGroups: List<DuplicateFileGroup> = emptyList(),
    val buckets: List<StorageAnalysisBucket> = emptyList(),
    val selected: Set<String> = emptySet(),
) {
    val allRecords: List<StorageFileRecord> get() = when (mode) {
        StorageToolMode.LARGE -> records
        StorageToolMode.DUPLICATES -> duplicateGroups.flatMap { it.records }
        StorageToolMode.ANALYSIS -> emptyList()
    }
    val recommended: Set<String> get() = when (mode) {
        StorageToolMode.LARGE -> records.map { it.uri }.toSet()
        StorageToolMode.DUPLICATES -> duplicateGroups.flatMap { it.records.drop(1) }.map { it.uri }.toSet()
        StorageToolMode.ANALYSIS -> emptySet()
    }
    val allSelected: Boolean get() = recommended.isNotEmpty() && selected.size == recommended.size
    val selectedBytes: Long get() = allRecords.filter { it.uri in selected }.sumOf { it.bytes }
    fun toggleAllSelection() = copy(selected = if (allSelected) emptySet() else recommended)
    fun toggleSelection(key: String): StorageToolsUiState {
        if (key in selected) return copy(selected = selected - key)
        if (allRecords.none { it.uri == key }) return this
        if (mode == StorageToolMode.DUPLICATES) {
            val group = duplicateGroups.firstOrNull { group -> group.records.any { it.uri == key } } ?: return this
            if (group.records.count { it.uri !in selected } <= 1) return copy(status = "每组需保留一份，可先取消另一份的勾选")
        }
        return copy(selected = selected + key)
    }
}

@Composable
internal fun StorageToolsScreen(
    state: StorageToolsUiState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onToggle: (String) -> Unit,
    onDelete: () -> Unit,
    onOpenPermission: () -> Unit,
    onToggleAll: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val title = when (state.mode) {
        StorageToolMode.LARGE -> "大文件"
        StorageToolMode.DUPLICATES -> "重复文件"
        StorageToolMode.ANALYSIS -> "存储分析"
    }
    val subtitle = when (state.mode) {
        StorageToolMode.LARGE -> "找出占用空间最大的文件"
        StorageToolMode.DUPLICATES -> "相同内容归为一组，每组保留一份"
        StorageToolMode.ANALYSIS -> "按文件类型查看空间占用"
    }
    Scaffold(containerColor = BaiZeTokens.colors.surfaceBase,
        topBar = { DetailPageHeader(title, subtitle, onBack) },
        bottomBar = {
            if (state.allRecords.isNotEmpty() && !state.running && !state.permissionRequired) CleanSelectionBar(
                state.selected.size, state.allRecords.size, Formatter.formatFileSize(context, state.selectedBytes),
                state.allSelected, true, onToggleAll, onDelete,
                cleanLabel = "删除已选 ${state.selected.size} 项",
                selectLabel = if (state.mode == StorageToolMode.DUPLICATES) "勾选多余副本" else "全选")
        }
    ) { insets ->
    LazyColumn(
        Modifier.fillMaxSize().padding(insets).background(BaiZeTokens.colors.surfaceBase),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            DetailGlassPanel {
                Text(state.status, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                if (state.elapsedMs > 0L) Text("${state.elapsedMs} ms", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.running) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(14.dp))
                if (state.permissionRequired) {
                    GlassActionButton("开启所有文件访问", onOpenPermission, Modifier.fillMaxWidth())
                } else {
                    GlassActionButton("重新扫描", onScan, Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh, secondary = true, enabled = !state.running)
                }
            }
        }
        when (state.mode) {
            StorageToolMode.LARGE -> {
                item { DetailSectionHeader("大文件", "100 MB 以上") }
                items(state.records, key = { it.uri }) { record ->
                    StorageFileRow(record, record.uri in state.selected, !state.running) { onToggle(record.uri) }
                }
            }
            StorageToolMode.DUPLICATES -> {
                state.duplicateGroups.forEachIndexed { groupIndex, group ->
                    item(key = "header-$groupIndex") {
                        DetailSectionHeader(
                            "重复组 ${groupIndex + 1}",
                            "${group.records.size} 个 · 每个 ${Formatter.formatFileSize(context, group.bytesEach)} · 可释放最多 ${Formatter.formatFileSize(context, group.reclaimableBytes)}"
                        )
                    }
                    items(group.records, key = { "dup-${group.key}-${it.uri}" }) { record ->
                        StorageFileRow(record, record.uri in state.selected, !state.running) { onToggle(record.uri) }
                    }
                }
            }
            StorageToolMode.ANALYSIS -> {
                item { DetailSectionHeader("空间构成") }
                items(state.buckets, key = { it.key }) { bucket -> StorageBucketRow(bucket) }
            }
        }
        if (!state.running && !state.permissionRequired && state.allRecords.isEmpty() && state.buckets.isEmpty()) {
            item { DetailEmptyState("暂无可处理文件", "本次扫描未发现符合条件的文件。") }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
    }
}

@Composable
private fun StorageFileRow(record: StorageFileRecord, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val size = Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current, record.bytes)
    DetailResultRow(title = record.name, value = size, summary = "文件", path = record.path,
        details = "$size\n\n${record.path}", icon = Icons.Rounded.Description,
        first = true, last = true, selected = selected, selectionEnabled = enabled, onToggle = onClick)
}

@Composable
private fun StorageBucketRow(bucket: StorageAnalysisBucket) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val icon: ImageVector = when (bucket.key) {
        "image" -> Icons.Rounded.Image
        "video" -> Icons.Rounded.Movie
        "audio" -> Icons.Rounded.MusicNote
        "apk" -> Icons.Rounded.InstallMobile
        "archive" -> Icons.Rounded.FolderZip
        else -> Icons.Rounded.Description
    }
    DetailGlassPanel() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .08f)) {
                Icon(icon, null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(bucket.label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("${bucket.files} 个文件", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(Formatter.formatFileSize(context, bucket.bytes), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}
