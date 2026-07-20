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
import androidx.compose.foundation.layout.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
            state = state.copy(
                connected = false,
                running = false,
                status = "Root 服务已断开，请重新进入页面"
            )
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
                    onOneTap = ::oneTapOrganize,
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
            state = state.copy(
                status = "Root 服务启动失败：${it.message ?: it.javaClass.simpleName}"
            )
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

    private fun oneTapOrganize() {
        val root = service ?: return
        if (state.running) return
        state = state.copy(
            running = true,
            status = "正在扫描内部存储、公共下载和所有应用用户文件目录…",
            lastTotal = 0,
            lastBytes = 0L
        )

        lifecycleScope.launch {
            val scan = withContext(Dispatchers.IO) {
                runCatching { JSONObject(root.scanFileOrganizer()) }.getOrElse {
                    JSONObject()
                        .put("error", "scan_failed")
                        .put("message", it.message ?: it.javaClass.simpleName)
                }
            }
            if (scan.has("error")) {
                state = state.copy(
                    running = false,
                    status = scan.optString("message", "文件归类扫描失败")
                )
                return@launch
            }
            if (scan.optBoolean("cancelled")) {
                state = state.copy(running = false, status = "一键归类已停止")
                return@launch
            }

            val snapshotId = scan.optString("snapshotId")
            val total = scan.optInt("total")
            val totalBytes = scan.optLong("totalBytes")
            if (snapshotId.isBlank() || total == 0) {
                state = state.copy(
                    running = false,
                    status = "一键归类完成：没有需要移动的新文件",
                    lastTotal = 0,
                    lastBytes = 0L
                )
                return@launch
            }

            state = state.copy(
                status = "扫描到 $total 个文件，正在自动归类，无需二次确认…",
                lastTotal = total,
                lastBytes = totalBytes
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    JSONObject(
                        root.applyFileOrganizer(
                            snapshotId,
                            JSONObject().put("all", true).toString()
                        )
                    )
                }.getOrElse {
                    JSONObject()
                        .put("error", "apply_failed")
                        .put("message", it.message ?: it.javaClass.simpleName)
                }
            }
            if (result.has("error")) {
                state = state.copy(
                    running = false,
                    status = result.optString("message", "文件归类失败")
                )
                return@launch
            }

            val moved = result.optInt("moved")
            val skipped = result.optInt("skipped")
            val failed = result.optInt("failed")
            val bytes = result.optLong("bytes")
            state = state.copy(
                running = false,
                status = "一键归类完成：移动 $moved/$total 个 · 跳过 $skipped 个 · 失败 $failed 个 · ${
                    Formatter.formatFileSize(this@FileOrganizerActivity, bytes)
                }",
                undoAvailable = result.optBoolean("undoAvailable"),
                lastTotal = total,
                lastBytes = bytes
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
                    JSONObject()
                        .put("error", "undo_failed")
                        .put("message", it.message ?: it.javaClass.simpleName)
                }
            }
            if (result.has("error")) {
                state = state.copy(
                    running = false,
                    status = result.optString("message", "撤销失败")
                )
                return@launch
            }
            state = state.copy(
                running = false,
                undoAvailable = result.optBoolean("undoAvailable"),
                status = "撤销完成：恢复 ${result.optInt("restored")} 个 · 跳过 ${
                    result.optInt("skipped")
                } 个 · 失败 ${result.optInt("failed")} 个"
            )
        }
    }

    override fun onDestroy() {
        if (bound) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }
}

private data class FileOrganizerUiState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val status: String = "等待连接",
    val undoAvailable: Boolean = true,
    val lastTotal: Int = 0,
    val lastBytes: Long = 0L
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileOrganizerScreen(
    state: FileOrganizerUiState,
    schedule: FileOrganizerScheduleSettings,
    scheduleSavedText: String,
    onBack: () -> Unit,
    onOneTap: () -> Unit,
    onUndo: () -> Unit,
    onStop: () -> Unit,
    onScheduleChange: (FileOrganizerScheduleSettings) -> Unit,
    onSaveSchedule: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("文件归类", fontWeight = FontWeight.Black, fontSize = 24.sp)
                        Text(
                            "全应用一键归类与定时归类",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.FolderCopy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    state.status,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 18.sp
                                )
                                Text(
                                    "点一次自动扫描并直接归类。Telegram、NagramX、浏览器、网盘等应用不再依赖单独写死路径。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            }
                            if (state.running) {
                                CircularProgressIndicator(
                                    Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }

                        Button(
                            onClick = if (state.running) onStop else onOneTap,
                            enabled = state.connected,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Icon(
                                if (state.running) Icons.Rounded.Stop else Icons.Rounded.AutoAwesome,
                                contentDescription = null
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                if (state.running) "停止当前任务" else "一键归类所有应用下载",
                                fontWeight = FontWeight.Black
                            )
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

            item { SourceCard() }
            item { DestinationCard() }
            item {
                ScheduleCard(
                    schedule,
                    scheduleSavedText,
                    onScheduleChange,
                    onSaveSchedule
                )
            }
        }
    }
}

@Composable
private fun SourceCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("扫描范围", fontWeight = FontWeight.Black, fontSize = 19.sp)
            Text(
                "内部存储 + 全部应用用户文件",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                "包括根目录散落文件、公共 Download/接收目录，以及每个应用的 Android/media/<包名> 和 Android/data/<包名>/files。应用缓存、数据库、缩略图、贴纸和临时文件会跳过。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun DestinationCard() {
    val categories = listOf("图片", "视频", "音频", "文档", "安装包", "压缩包", "电子书", "其他")
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("归类到哪里", fontWeight = FontWeight.Black, fontSize = 19.sp)
            Text(
                "内部存储 / BaiZe归类",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                "按类型移动到对应子目录；遇到同名文件直接跳过，不覆盖。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { category ->
                    FilterChip(
                        selected = false,
                        onClick = {},
                        label = { Text(category) }
                    )
                }
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("定时归类", fontWeight = FontWeight.Black, fontSize = 19.sp)
                    Text(
                        "定时任务使用相同的全应用扫描范围",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = schedule.enabled,
                    onCheckedChange = { onChange(schedule.copy(enabled = it)) }
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                intervals.forEach { hours ->
                    FilterChip(
                        selected = schedule.intervalHours == hours,
                        onClick = { onChange(schedule.copy(intervalHours = hours)) },
                        label = {
                            Text(
                                when (hours) {
                                    24 -> "每天"
                                    72 -> "每3天"
                                    168 -> "每周"
                                    else -> "${hours}小时"
                                }
                            )
                        }
                    )
                }
            }

            HorizontalDivider()
            SettingSwitch("仅充电时执行", schedule.chargingOnly) {
                onChange(schedule.copy(chargingOnly = it))
            }
            SettingSwitch("仅息屏时执行", schedule.screenOffOnly) {
                onChange(schedule.copy(screenOffOnly = it))
            }
            Text(
                "上次执行：${FileOrganizerWorker.lastRunText(LocalContext.current, schedule)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                schedule.lastResult,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (savedText.isNotBlank()) {
                Text(
                    savedText,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("保存定时归类", fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
