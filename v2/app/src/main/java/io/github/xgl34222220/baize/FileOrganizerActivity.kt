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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
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
import androidx.compose.material3.Surface
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
            state = state.copy(connected = false, running = false, status = "Root 服务已断开")
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
                    onSelectAll = ::selectAll,
                    onCategory = { state = state.copy(category = it) },
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

    private fun saveSchedule() {
        FileOrganizerWorker.saveAndSchedule(this, schedule)
        schedule = FileOrganizerWorker.loadSettings(this)
        scheduleSavedText = if (schedule.enabled) {
            "已保存：每 ${schedule.intervalHours} 小时自动归类"
        } else {
            "定时归类已关闭"
        }
    }

    private fun scan() {
        val root = service ?: return
        if (state.running) return
        state = state.copy(
            running = true,
            status = "正在扫描本机所有下载目录…",
            items = emptyList(),
            selected = emptySet()
        )
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { JSONObject(root.scanFileOrganizer()) }.getOrElse {
                    JSONObject().put("error", "scan_failed").put("message", it.message)
                }
            }
            if (json.has("error")) {
                state = state.copy(
                    running = false,
                    status = json.optString("message", "文件归类扫描失败")
                )
                return@launch
            }
            if (json.optBoolean("cancelled")) {
                state = state.copy(running = false, status = "文件归类扫描已停止")
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
        state = state.copy(running = true, status = "正在归类所选文件…")
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { JSONObject(root.applyFileOrganizer(state.snapshotId, request)) }.getOrElse {
                    JSONObject().put("error", "apply_failed").put("message", it.message)
                }
            }
            if (json.has("error")) {
                state = state.copy(
                    running = false,
                    status = json.optString("message", "文件归类失败")
                )
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
                status = "归类完成：移动 $moved 个 · 跳过 $skipped 个 · 失败 $failed 个 · ${
                    Formatter.formatFileSize(this@FileOrganizerActivity, bytes)
                }"
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
                status = "撤销完成：恢复 ${json.optInt("restored")} 个 · 跳过 ${
                    json.optInt("skipped")
                } 个 · 失败 ${json.optInt("failed")} 个"
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
                    sourceDisplay = item.optString("sourceDisplay"),
                    destinationName = item.optString("destinationName", item.optString("name"))
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
    val destinationName: String
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
    schedule: FileOrganizerScheduleSettings,
    scheduleSavedText: String,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: (List<String>) -> Unit,
    onCategory: (String) -> Unit,
    onApply: () -> Unit,
    onUndo: () -> Unit,
    onStop: () -> Unit,
    onScheduleChange: (FileOrganizerScheduleSettings) -> Unit,
    onSaveSchedule: () -> Unit
) {
    val categories = listOf("全部", "图片", "视频", "音频", "文档", "安装包", "压缩包", "电子书", "其他")
    val visible = if (state.category == "全部") state.items else state.items.filter { it.category == state.category }
    val visibleIds = visible.map { it.id }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("文件归类", fontWeight = FontWeight.Black, fontSize = 24.sp)
                        Text("扫描、预览、归类与定时", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { OrganizerHeroCard(state, onScan, onUndo, onStop) }
            item { DestinationCard() }
            item { ScheduleCard(schedule, scheduleSavedText, onScheduleChange, onSaveSchedule) }
            if (state.items.isNotEmpty()) {
                item { CategoryStrip(categories, state.category, onCategory) }
                item {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("${visible.size} 个文件", fontWeight = FontWeight.Black, fontSize = 18.sp)
                            Text("已选 ${state.selected.size} 个", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        Surface(modifier = Modifier.clickable { onSelectAll(visibleIds) }, color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50)) {
                            Text(
                                if (visibleIds.isNotEmpty() && visibleIds.all { it in state.selected }) "取消本页" else "选择本页",
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                items(visible, key = { it.id }) { item -> OrganizerFileCard(item, item.id in state.selected) { onToggle(item.id) } }
                item {
                    Button(
                        onClick = onApply,
                        enabled = state.connected && !state.running && state.selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("归类所选 ${state.selected.size} 个文件", fontWeight = FontWeight.Black, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun OrganizerHeroCard(state: FileOrganizerUiState, onScan: () -> Unit, onUndo: () -> Unit, onStop: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(30.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Box(modifier = Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.FolderCopy, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.status, fontWeight = FontWeight.Black, fontSize = 18.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("只读取明确命名为 Download、Downloads 或“下载”的目录；扫描后变化的文件会自动跳过。", color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f), fontSize = 12.sp, lineHeight = 18.sp)
                }
                if (state.running) CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(18.dp))
            if (state.running) {
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(20.dp)) {
                    Icon(Icons.Rounded.Stop, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("停止当前任务", fontWeight = FontWeight.Black, maxLines = 1)
                }
            } else {
                Button(onClick = onScan, enabled = state.connected, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(20.dp)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("扫描所有下载目录", fontWeight = FontWeight.Black, maxLines = 1)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onUndo, enabled = state.connected && state.undoAvailable, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Rounded.Restore, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("撤销上一次归类", fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun DestinationCard() {
    val destinationCategories = listOf("图片", "视频", "音频", "文档", "安装包", "压缩包", "电子书", "其他")
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp))
                Column {
                    Text("归类到哪里", fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Text("内部存储 / BaiZe归类", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("每个文件会移动到对应分类子目录，原文件名保持不变；目标中已有同名文件时直接跳过，不会覆盖。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(14.dp))
            destinationCategories.chunked(4).forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { category ->
                        Surface(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp)) {
                            Text(category, modifier = Modifier.padding(vertical = 9.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleCard(settings: FileOrganizerScheduleSettings, savedText: String, onChange: (FileOrganizerScheduleSettings) -> Unit, onSave: () -> Unit) {
    val context = LocalContext.current
    val intervals = listOf(6, 12, 24, 72, 168)
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Box(modifier = Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("定时归类", fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Text(if (settings.enabled) "每 ${settings.intervalHours} 小时执行一次" else "当前未启用", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                Switch(checked = settings.enabled, onCheckedChange = { onChange(settings.copy(enabled = it)) })
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(14.dp))
            Text("执行间隔", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                intervals.forEach { hours ->
                    FilterChip(
                        selected = settings.intervalHours == hours,
                        onClick = { onChange(settings.copy(intervalHours = hours)) },
                        label = { Text(when (hours) { 24 -> "每天"; 72 -> "每 3 天"; 168 -> "每周"; else -> "每 $hours 小时" }, maxLines = 1) },
                        leadingIcon = if (settings.intervalHours == hours) ({ Icon(Icons.Rounded.Timer, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            ScheduleToggleRow("仅息屏时执行", "亮屏时等待，避免打扰正在使用的应用", settings.screenOffOnly) { onChange(settings.copy(screenOffOnly = it)) }
            ScheduleToggleRow("仅充电时执行", "交给系统调度器等待充电条件满足", settings.chargingOnly) { onChange(settings.copy(chargingOnly = it)) }
            Spacer(Modifier.height(8.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("上次执行：${FileOrganizerWorker.lastRunText(context, settings)}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(settings.lastResult, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (settings.enabled) "保存定时归类" else "保存并关闭定时", fontWeight = FontWeight.Black, maxLines = 1)
            }
            if (savedText.isNotBlank()) Text(savedText, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ScheduleToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CategoryStrip(categories: List<String>, selected: String, onCategory: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        categories.forEach { category -> FilterChip(selected = selected == category, onClick = { onCategory(category) }, label = { Text(category, maxLines = 1) }) }
    }
}

@Composable
private fun OrganizerFileCard(item: FileOrganizerItem, checked: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(22.dp)) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Spacer(Modifier.size(4.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${item.category} · ${item.sourceGroup} · ${Formatter.formatFileSize(context, item.bytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("来源：${item.sourceDisplay}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("去向：BaiZe归类/${item.category}/${item.destinationName}", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
