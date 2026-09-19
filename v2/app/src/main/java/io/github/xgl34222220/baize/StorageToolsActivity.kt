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
                        onDelete = ::deleteSelected,
                        onOpenPermission = ::openAllFilesSettings
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
        state = state.copy(selected = state.selected.toMutableSet().apply { if (!add(key)) remove(key) })
    }

    private fun deleteSelected() {
        if (state.running || state.selected.isEmpty()) return
        val selectedRecords = when (state.mode) {
            StorageToolMode.LARGE -> state.records.filter { it.uri in state.selected }
            StorageToolMode.DUPLICATES -> state.duplicateGroups.flatMap { it.records }.filter { it.uri in state.selected }
            StorageToolMode.ANALYSIS -> emptyList()
        }
        if (selectedRecords.isEmpty()) return
        state = state.copy(running = true, status = "正在删除已选择的 ${selectedRecords.size} 个文件…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                var deleted = 0
                var bytes = 0L
                selectedRecords.forEach { record ->
                    if (StorageMediaRepository.delete(applicationContext, record)) {
                        deleted++
                        bytes += record.bytes
                    }
                }
                deleted to bytes
            }
            state = state.copy(running = false, selected = emptySet(),
                status = "已删除 ${result.first} 个文件，释放 ${Formatter.formatFileSize(this@StorageToolsActivity, result.second)}")
            scan()
        }
    }

    companion object {
        const val EXTRA_MODE = "storage_tool_mode"
        fun intent(context: android.content.Context, mode: StorageToolMode) =
            Intent(context, StorageToolsActivity::class.java).putExtra(EXTRA_MODE, mode.name)
    }
}

data class StorageToolsUiState(
    val mode: StorageToolMode = StorageToolMode.LARGE,
    val running: Boolean = false,
    val permissionRequired: Boolean = false,
    val status: String = "准备扫描",
    val elapsedMs: Long = 0L,
    val records: List<StorageFileRecord> = emptyList(),
    val duplicateGroups: List<DuplicateFileGroup> = emptyList(),
    val buckets: List<StorageAnalysisBucket> = emptyList(),
    val selected: Set<String> = emptySet(),
)

@Composable
private fun StorageToolsScreen(
    state: StorageToolsUiState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onToggle: (String) -> Unit,
    onDelete: () -> Unit,
    onOpenPermission: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val title = when (state.mode) {
        StorageToolMode.LARGE -> "大文件"
        StorageToolMode.DUPLICATES -> "重复文件"
        StorageToolMode.ANALYSIS -> "存储分析"
    }
    val subtitle = when (state.mode) {
        StorageToolMode.LARGE -> "找出占用空间最大的文件"
        StorageToolMode.DUPLICATES -> "按大小、前缀与完整哈希确认重复内容"
        StorageToolMode.ANALYSIS -> "按文件类型查看空间占用"
    }
    LazyColumn(
        Modifier.fillMaxSize().background(BaiZeTokens.colors.surfaceBase),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { DetailPageHeader(title, subtitle, onBack) }
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
                    GlassActionButton("重新扫描", onScan, Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh, secondary = true)
                }
                if (state.selected.isNotEmpty() && state.mode != StorageToolMode.ANALYSIS) {
                    Spacer(Modifier.height(10.dp))
                    GlassActionButton("删除已选 ${state.selected.size} 项", onDelete, Modifier.fillMaxWidth(), icon = Icons.Rounded.DeleteSweep)
                }
            }
        }
        when (state.mode) {
            StorageToolMode.LARGE -> {
                item { DetailSectionHeader("大文件", "100 MB 以上") }
                items(state.records, key = { it.uri }) { record ->
                    StorageFileRow(record, record.uri in state.selected) { onToggle(record.uri) }
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
                        StorageFileRow(record, record.uri in state.selected) { onToggle(record.uri) }
                    }
                }
            }
            StorageToolMode.ANALYSIS -> {
                item { DetailSectionHeader("空间构成") }
                items(state.buckets, key = { it.key }) { bucket -> StorageBucketRow(bucket) }
            }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
private fun StorageFileRow(record: StorageFileRecord, selected: Boolean, onClick: () -> Unit) {
    DetailGlassPanel(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(record.name, maxLines = 1, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(record.path, maxLines = 2, fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current, record.bytes),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
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
    DetailGlassPanel(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
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
