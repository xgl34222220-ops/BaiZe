package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class FileOrganizerActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private var service: IProfileRootService? = null
    private var bound = false
    private var state by mutableStateOf(FileOrganizerUiState())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IProfileRootService.Stub.asInterface(binder)
            bound = true
            state = state.copy(connected = true, status = "已连接 Root 文件归类服务")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            state = state.copy(connected = false, running = false, status = "Root 服务已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val appearance = appearanceViewModel.settings.collectAsStateWithLifecycle().value
            BaiZeTheme(appearance) {
                FileOrganizerScreen(
                    state = state,
                    onBack = ::finish,
                    onScan = ::scan,
                    onToggle = ::toggle,
                    onSelectAll = ::selectAll,
                    onCategory = { state = state.copy(category = it) },
                    onApply = ::applySelected,
                    onUndo = ::undoLast,
                    onStop = { service?.cancelCurrentTask() }
                )
            }
        }
        bindService()
    }

    private fun bindService() {
        state = state.copy(status = "正在连接 Root 文件归类服务…")
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java)
                    .addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
            bound = true
        }.onFailure {
            bound = false
            state = state.copy(status = "Root 服务启动失败：${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun scan() {
        val root = service ?: return
        if (state.running) return
        state = state.copy(running = true, status = "正在扫描本机所有下载目录…", items = emptyList(), selected = emptySet())
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { JSONObject(root.scanFileOrganizer()) }.getOrElse {
                    JSONObject().put("error", "scan_failed").put("message", it.message)
                }
            }
            if (json.has("error")) {
                state = state.copy(running = false, status = json.optString("message", "文件归类扫描失败"))
                return@launch
            }
            val items = parseItems(json.optJSONArray("items"))
            state = state.copy(
                running = false,
                snapshotId = json.optString("snapshotId"),
                items = items,
                selected = items.mapTo(linkedSetOf()) { it.id },
                category = "全部",
                status = buildString {
                    append("扫描完成：${json.optInt("roots")} 个下载目录 · ${items.size} 个文件")
                    if (json.optBoolean("truncated")) append(" · 已达到本次安全上限")
                }
            )
        }
    }

    private fun applySelected() {
        val root = service ?: return
        if (state.running || state.snapshotId.isBlank() || state.selected.isEmpty()) return
        val request = JSONObject().put("ids", JSONArray(state.selected.toList())).toString()
        state = state.copy(running = true, status = "正在按不可变计划归类所选文件…")
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { JSONObject(root.applyFileOrganizer(state.snapshotId, request)) }.getOrElse {
                    JSONObject().put("error", "apply_failed").put("message", it.message)
                }
            }
            if (json.has("error")) {
                state = state.copy(running = false, status = json.optString("message", "文件归类失败"))
                return@launch
            }
            val moved = json.optInt("moved")
            val skipped = json.optInt("skipped")
            val failed = json.optInt("failed")
            val bytes = json.optLong("bytes")
            state = state.copy(
                running = false,
                snapshotId = "",
                items = emptyList(),
                selected = emptySet(),
                undoAvailable = json.optBoolean("undoAvailable"),
                status = "归类完成：移动 $moved 个 · 跳过 $skipped 个 · 失败 $failed 个 · ${Formatter.formatFileSize(this@FileOrganizerActivity, bytes)}"
            )
        }
    }

    private fun undoLast() {
        val root = service ?: return
        if (state.running) return
        state = state.copy(running = true, status = "正在撤销上一次文件归类…")
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { JSONObject(root.undoFileOrganizer()) }.getOrElse {
                    JSONObject().put("error", "undo_failed").put("message", it.message)
                }
            }
            if (json.has("error")) {
                state = state.copy(running = false, status = json.optString("message", "撤销失败"))
                return@launch
            }
            state = state.copy(
                running = false,
                undoAvailable = json.optBoolean("undoAvailable"),
                status = "撤销完成：恢复 ${json.optInt("restored")} 个 · 跳过 ${json.optInt("skipped")} 个 · 失败 ${json.optInt("failed")} 个"
            )
        }
    }

    private fun toggle(id: String) {
        if (state.running) return
        val next = state.selected.toMutableSet()
        if (!next.add(id)) next.remove(id)
        state = state.copy(selected = next)
    }

    private fun selectAll(visible: List<String>) {
        if (state.running) return
        val next = state.selected.toMutableSet()
        val allSelected = visible.isNotEmpty() && visible.all { it in next }
        if (allSelected) next.removeAll(visible.toSet()) else next.addAll(visible)
        state = state.copy(selected = next)
    }

    private fun parseItems(array: JSONArray?): List<FileOrganizerItem> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            add(
                FileOrganizerItem(
                    id = id,
                    name = item.optString("name"),
                    category = item.optString("category", "其他"),
                    bytes = item.optLong("bytes"),
                    sourceGroup = item.optString("sourceGroup", "公共下载"),
                    sourceDisplay = item.optString("sourceDisplay")
                )
            )
        }
    }

    override fun onDestroy() {
        if (bound) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }
}

private data class FileOrganizerItem(
    val id: String,
    val name: String,
    val category: String,
    val bytes: Long,
    val sourceGroup: String,
    val sourceDisplay: String
)

private data class FileOrganizerUiState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val status: String = "等待连接",
    val snapshotId: String = "",
    val items: List<FileOrganizerItem> = emptyList(),
    val selected: Set<String> = emptySet(),
    val category: String = "全部",
    val undoAvailable: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileOrganizerScreen(
    state: FileOrganizerUiState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: (List<String>) -> Unit,
    onCategory: (String) -> Unit,
    onApply: () -> Unit,
    onUndo: () -> Unit,
    onStop: () -> Unit
) {
    val categories = listOf("全部", "图片", "视频", "音频", "文档", "安装包", "压缩包", "电子书", "其他")
    val visible = if (state.category == "全部") state.items else state.items.filter { it.category == state.category }
    val visibleIds = visible.map { it.id }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("文件归类", fontWeight = FontWeight.Black)
                        Text("所有下载目录 · 不覆盖同名文件", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.FolderCopy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(state.status, fontWeight = FontWeight.Bold)
                                Text(
                                    "扫描公共 Download、应用自建下载目录、Android/data、Android/media 及 Root 可读取的应用私有下载子目录。扫描后变化的文件会自动跳过。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                )
                            }
                            if (state.running) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onScan, enabled = state.connected && !state.running, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("扫描下载目录")
                            }
                            OutlinedButton(onClick = onUndo, enabled = state.connected && !state.running && state.undoAvailable) {
                                Icon(Icons.Rounded.Restore, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("撤销")
                            }
                            if (state.running) {
                                OutlinedButton(onClick = onStop) {
                                    Icon(Icons.Rounded.Stop, contentDescription = null)
                                }
                            }
                        }
                    }
                }
            }

            if (state.items.isNotEmpty()) {
                item {
                    LazyColumnLikeChipRow(categories, state.category, onCategory)
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${visible.size} 个文件 · 已选 ${state.selected.size}", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (visibleIds.isNotEmpty() && visibleIds.all { it in state.selected }) "取消本页" else "选择本页",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onSelectAll(visibleIds) }.padding(8.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                items(visible, key = { it.id }) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onToggle(item.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = item.id in state.selected, onCheckedChange = { onToggle(item.id) })
                            Column(Modifier.weight(1f)) {
                                Text(item.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${item.category} · ${item.sourceGroup} · ${Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current, item.bytes)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    item.sourceDisplay,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = onApply,
                        enabled = state.connected && !state.running && state.selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("归类所选 ${state.selected.size} 个文件", fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

@Composable
private fun LazyColumnLikeChipRow(
    categories: List<String>,
    selected: String,
    onCategory: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        categories.take(5).forEach { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onCategory(category) },
                label = { Text(category, fontSize = 10.sp) }
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        categories.drop(5).forEach { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onCategory(category) },
                label = { Text(category, fontSize = 10.sp) }
            )
        }
    }
}
