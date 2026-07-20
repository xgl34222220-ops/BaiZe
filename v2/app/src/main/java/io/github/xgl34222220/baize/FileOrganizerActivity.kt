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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    private var schedule by mutableStateOf(FileOrganizerScheduleSettings())
    private var scheduleSavedText by mutableStateOf("")

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IProfileRootService.Stub.asInterface(binder)
            bound = true
            state = state.copy(connected = true, status = "文件归类服务已就绪")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            state = state.copy(connected = false, running = false, status = "Root 服务已断开，请重新进入页面")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        schedule = FileOrganizerWorker.loadSettings(this)
        setContent {
            val appearance = appearanceViewModel.settings.collectAsStateWithLifecycle().value
            BaiZeTheme(appearance) {
                FileOrganizerScreen(
                    state = state,
                    schedule = schedule,
                    scheduleSavedText = scheduleSavedText,
                    onBack = ::finish,
                    onScan = ::scan,
                    onToggle = ::toggle,
                    onToggleAll = ::toggleAll,
                    onApply = ::applySelected,
                    onUndo = ::undoLast,
                    onStop = {
                        service?.cancelCurrentTask()
                        state = state.copy(status = "正在停止当前任务…")
                    },
                    onScheduleChange = {
                        schedule = it
                        scheduleSavedText = ""
                    },
                    onSaveSchedule = ::saveSchedule
                )
            }
        }
        bindService()
    }

    override fun onResume() {
        super.onResume()
        schedule = FileOrganizerWorker.loadSettings(this)
    }

    private fun bindService() {
        state = state.copy(status = "正在连接 Root 文件归类服务…")
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
            bound = true
        }.onFailure {
            bound = false
            state = state.copy(status = "Root 服务启动失败：${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun saveSchedule() {
        FileOrganizerWorker.saveAndSchedule(this, schedule)
        schedule = FileOrganizerWorker.loadSettings(this)
        scheduleSavedText = if (schedule.enabled) "已保存：每 ${schedule.intervalHours} 小时自动归类" else "定时归类已关闭"
    }

    private fun scan() {
        val root = service ?: return
        if (state.running) return
        state = FileOrganizerUiState(
            connected = true,
            running = true,
            status = "正在扫描下载目录、QQ/TIM 接收目录…",
            undoAvailable = state.undoAvailable
        )
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                runCatching { JSONObject(root.scanFileOrganizer()) }.getOrElse {
                    JSONObject().put("error", "scan_failed").put("message", it.message ?: it.javaClass.simpleName)
                }
            }
            if (summary.has("error")) {
                state = state.copy(running = false, status = summary.optString("message", "文件归类扫描失败"))
                return@launch
            }
            if (summary.optBoolean("cancelled")) {
                state = state.copy(running = false, status = "文件归类扫描已停止")
                return@launch
            }
            val snapshotId = summary.optString("snapshotId")
            val total = summary.optInt("total")
            val preview = parseItems(summary.optJSONArray("items"))
            state = state.copy(
                running = false,
                snapshotId = snapshotId,
                total = total,
                totalBytes = summary.optLong("totalBytes"),
                roots = summary.optInt("roots"),
                truncated = summary.optBoolean("truncated"),
                items = preview,
                allSelected = true,
                selected = emptySet(),
                excluded = emptySet(),
                status = buildString {
                    if (total == 0) append("扫描完成：没有需要归类的新文件")
                    else append("扫描完成：${summary.optInt("roots")} 个目录 · $total 个文件")
                    if (summary.optBoolean("truncated")) append(" · 已达到安全上限")
                }
            )
        }
    }

    private fun applySelected() {
        val root = service ?: return
        if (state.running || state.snapshotId.isBlank() || selectedCount(state) <= 0) return
        val request = JSONObject()
            .put("all", state.allSelected)
            .put("ids", JSONArray(state.selected.toList()))
            .put("excludeIds", JSONArray(state.excluded.toList()))
            .toString()
        state = state.copy(running = true, status = "正在归类 ${selectedCount(state)} 个文件…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { JSONObject(root.applyFileOrganizer(state.snapshotId, request)) }.getOrElse {
                    JSONObject().put("error", "apply_failed").put("message", it.message ?: it.javaClass.simpleName)
                }
            }
            if (result.has("error")) {
                state = state.copy(running = false, status = result.optString("message", "文件归类失败"))
                return@launch
            }
            val text = "归类完成：移动 ${result.optInt("moved")} 个 · 跳过 ${result.optInt("skipped")} 个 · 失败 ${result.optInt("failed")} 个 · ${Formatter.formatFileSize(this@FileOrganizerActivity, result.optLong("bytes"))}"
            state = FileOrganizerUiState(
                connected = true,
                running = false,
                status = text,
                undoAvailable = result.optBoolean("undoAvailable")
            )
        }
    }

    private fun undoLast() {
        val root = service ?: return
        if (state.running) return
        state = state.copy(running = true, status = "正在撤销上一次文件归类…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { JSONObject(root.undoFileOrganizer()) }.getOrElse {
                    JSONObject().put("error", "undo_failed").put("message", it.message ?: it.javaClass.simpleName)
                }
            }
            if (result.has("error")) {
                state = state.copy(running = false, status = result.optString("message", "撤销失败"))
                return@launch
            }
            state = state.copy(
                running = false,
                undoAvailable = result.optBoolean("undoAvailable"),
                status = "撤销完成：恢复 ${result.optInt("restored")} 个 · 跳过 ${result.optInt("skipped")} 个 · 失败 ${result.optInt("failed")} 个"
            )
        }
    }

    private fun toggle(id: String) {
        if (state.running) return
        if (state.allSelected) {
            val next = state.excluded.toMutableSet()
            if (!next.add(id)) next.remove(id)
            state = state.copy(excluded = next)
        } else {
            val next = state.selected.toMutableSet()
            if (!next.add(id)) next.remove(id)
            state = state.copy(selected = next)
        }
    }

    private fun toggleAll() {
        if (state.running || state.total == 0) return
        state = if (state.allSelected) {
            state.copy(allSelected = false, selected = emptySet(), excluded = emptySet())
        } else {
            state.copy(allSelected = true, selected = emptySet(), excluded = emptySet())
        }
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
                    sourceDisplay = item.optString("sourceDisplay"),
                    destinationDisplay = item.optString("destinationDisplay")
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
    val sourceDisplay: String,
    val destinationDisplay: String
)

private data class FileOrganizerUiState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val status: String = "等待连接",
    val snapshotId: String = "",
    val roots: Int = 0,
    val total: Int = 0,
    val totalBytes: Long = 0,
    val truncated: Boolean = false,
    val items: List<FileOrganizerItem> = emptyList(),
    val allSelected: Boolean = true,
    val selected: Set<String> = emptySet(),
    val excluded: Set<String> = emptySet(),
    val undoAvailable: Boolean = true
)

private fun selectedCount(state: FileOrganizerUiState): Int =
    if (state.allSelected) (state.total - state.excluded.size).coerceAtLeast(0) else state.selected.size

private fun isChecked(state: FileOrganizerUiState, id: String): Boolean =
    if (state.allSelected) id !in state.excluded else id in state.selected

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileOrganizerScreen(
    state: FileOrganizerUiState,
    schedule: FileOrganizerScheduleSettings,
    scheduleSavedText: String,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onToggle: (String) -> Unit,
    onToggleAll: () -> Unit,
    onApply: () -> Unit,
    onUndo: () -> Unit,
    onStop: () -> Unit,
    onScheduleChange: (FileOrganizerScheduleSettings) -> Unit,
    onSaveSchedule: () -> Unit
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("文件归类", fontWeight = FontWeight.Black, fontSize = 24.sp)
                        Text("下载、QQ/TIM 接收文件与定时归类", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.FolderCopy, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(state.status, fontWeight = FontWeight.Black, fontSize = 18.sp)
                                Text("支持 Download、Downloads、下载、QQfile_recv 与 TIMfile_recv；大量文件只预览前 60 个，完整计划保留在 Root 端。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                            if (state.running) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                        Button(
                            onClick = if (state.running) onStop else onScan,
                            enabled = state.connected,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Icon(if (state.running) Icons.Rounded.Stop else Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(if (state.running) "停止当前任务" else "扫描下载与 QQ/TIM 接收目录", fontWeight = FontWeight.Black)
                        }
                        OutlinedButton(
                            onClick = onUndo,
                            enabled = state.connected && !state.running && state.undoAvailable,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Icon(Icons.Rounded.Restore, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("撤销上一次归类", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item { DestinationCard() }
            item { ScheduleCard(schedule, scheduleSavedText, onScheduleChange, onSaveSchedule) }

            if (state.snapshotId.isNotBlank()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("扫描结果", fontWeight = FontWeight.Black, fontSize = 19.sp)
                            Text("${state.total} 个文件 · ${Formatter.formatFileSize(context, state.totalBytes)} · 预览前 ${state.items.size} 个", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                if (state.allSelected) "默认归类全部；当前排除 ${state.excluded.size} 个" else "当前只归类手动勾选的 ${state.selected.size} 个",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            OutlinedButton(onClick = onToggleAll, enabled = !state.running, modifier = Modifier.fillMaxWidth()) {
                                Text(if (state.allSelected) "取消全选" else "选择全部 ${state.total} 个")
                            }
                        }
                    }
                }

                items(state.items, key = { it.id }) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !state.running) { onToggle(item.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = isChecked(state, item.id), onCheckedChange = { onToggle(item.id) }, enabled = !state.running)
                            Column(Modifier.weight(1f)) {
                                Text(item.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${item.category} · ${item.sourceGroup} · ${Formatter.formatFileSize(context, item.bytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                Text("来源：${item.sourceDisplay}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("去向：${item.destinationDisplay}", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                item {
                    Button(
                        onClick = onApply,
                        enabled = state.connected && !state.running && selectedCount(state) > 0,
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("归类 ${selectedCount(state)} 个文件", fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationCard() {
    val categories = listOf("图片", "视频", "音频", "文档", "安装包", "压缩包", "电子书", "其他")
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("归类到哪里", fontWeight = FontWeight.Black, fontSize = 19.sp)
            Text("内部存储 / BaiZe归类", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("每个文件按类型移动到对应子目录；遇到同名文件直接跳过，不覆盖。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { category -> FilterChip(selected = false, onClick = {}, label = { Text(category) }) }
            }
        }
    }
}

@Composable
private fun ScheduleCard(
    schedule: FileOrganizerScheduleSettings,
    savedText: String,
    onChange: (FileOrganizerScheduleSettings) -> Unit,
    onSave: () -> Unit
) {
    val intervals = listOf(6, 12, 24, 72, 168)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("定时归类", fontWeight = FontWeight.Black, fontSize = 19.sp)
                    Text("由 WorkManager 实际执行扫描与归类", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                Switch(checked = schedule.enabled, onCheckedChange = { onChange(schedule.copy(enabled = it)) })
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                intervals.forEach { hours ->
                    FilterChip(
                        selected = schedule.intervalHours == hours,
                        onClick = { onChange(schedule.copy(intervalHours = hours)) },
                        label = { Text(when (hours) { 24 -> "每天"; 72 -> "每3天"; 168 -> "每周"; else -> "${hours}小时" }) }
                    )
                }
            }
            HorizontalDivider()
            SettingSwitch("仅充电时执行", schedule.chargingOnly) { onChange(schedule.copy(chargingOnly = it)) }
            SettingSwitch("仅息屏时执行", schedule.screenOffOnly) { onChange(schedule.copy(screenOffOnly = it)) }
            Text("上次执行：${FileOrganizerWorker.lastRunText(LocalContext.current, schedule)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(schedule.lastResult, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (savedText.isNotBlank()) Text(savedText, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("保存定时归类", fontWeight = FontWeight.Black) }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
