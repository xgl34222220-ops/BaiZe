package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
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
            loadRootSchedule()
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
        if (service == null) schedule = FileOrganizerWorker.loadSettings(this)
        else loadRootSchedule()
    }

    private fun loadRootSchedule() {
        val root = service ?: return
        lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) {
                runCatching { JSONObject(root.getSchedulerConfig()) }.getOrNull()
            } ?: return@launch
            schedule = FileOrganizerWorker.fromRootConfig(this@FileOrganizerActivity, config)
            FileOrganizerWorker.cacheUiSettings(this@FileOrganizerActivity, schedule)
            FileOrganizerWorker.ensureWatchdog(this@FileOrganizerActivity)
        }
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
        val root = service ?: run {
            scheduleSavedText = "Root 服务尚未连接，计划没有保存"
            return
        }
        scheduleSavedText = "正在保存到 Root Supervisor…"
        lifecycleScope.launch {
            val payload = JSONObject()
                .put("schedule_organize_enabled", if (schedule.enabled) 1 else 0)
                .put("schedule_organize_minutes", schedule.intervalMinutes)
                .put("schedule_organize_hours", ((schedule.intervalMinutes + 59) / 60).coerceAtLeast(1))
                .put("organize_charging_only", if (schedule.chargingOnly) 1 else 0)
                .put("organize_screen_off_only", if (schedule.screenOffOnly) 1 else 0)
                .put("organize_device_idle_only", if (schedule.idleOnly) 1 else 0)
                .put("organize_run_immediately", if (schedule.runImmediatelyOnEnable) 1 else 0)
                .put("organizer_conflict_policy", schedule.conflictPolicy.coerceIn(0, 2))
            if (schedule.enabled) payload.put("enabled", 1)
            val saved = withContext(Dispatchers.IO) {
                runCatching { JSONObject(root.saveSchedulerConfig(payload.toString())) }.getOrElse {
                    JSONObject().put("error", "save_failed").put("message", it.message ?: it.javaClass.simpleName)
                }
            }
            if (!saved.optBoolean("success", false)) {
                scheduleSavedText = saved.optString("message", "Root 计划保存失败")
                return@launch
            }
            FileOrganizerWorker.cacheUiSettings(this@FileOrganizerActivity, schedule)
            FileOrganizerWorker.ensureWatchdog(this@FileOrganizerActivity)
            val immediateText = if (schedule.enabled && schedule.runImmediatelyOnEnable) "，开启时会加入立即执行队列" else ""
            scheduleSavedText = if (schedule.enabled) {
                "Root 计划已保存：每 ${FileOrganizerWorker.intervalLabel(schedule.intervalMinutes)}检查一次$immediateText"
            } else "定时归类已关闭"
            FileOrganizerWorker.recordResult(this@FileOrganizerActivity, scheduleSavedText)
            loadRootSchedule()
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
                            JSONObject().put("all", true).put("conflictPolicy", schedule.conflictPolicy).toString()
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
            val renamed = result.optInt("renamed")
            val deduplicated = result.optInt("deduplicated")
            val bytes = result.optLong("bytes")
            state = state.copy(
                running = false,
                status = "一键归类完成：移动 $moved/$total 个 · 重命名 $renamed 个 · 重复 $deduplicated 个 · 跳过 $skipped 个 · 失败 $failed 个 · ${
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

internal data class FileOrganizerUiState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val status: String = "等待连接",
    val undoAvailable: Boolean = true,
    val lastTotal: Int = 0,
    val lastBytes: Long = 0L
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileOrganizerScreen(
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
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BaiZeTokens.colors.surfaceBase),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { DetailPageHeader("文件归类", "把分散的下载文件整理到一起", onBack) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                colors = CardDefaults.cardColors(containerColor = BaiZeTokens.colors.surfaceRaised),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FolderCopy, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.size(12.dp))
                        Text(if (state.running) "正在整理文件" else "按文件类型整理", style = MaterialTheme.typography.titleLarge)
                    }
                    if (state.lastTotal > 0) {
                        Text("${state.lastTotal} 个文件 · ${android.text.format.Formatter.formatFileSize(LocalContext.current, state.lastBytes)}",
                            style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(state.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Button(
                        onClick = if (state.running) onStop else onOneTap,
                        enabled = state.connected,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) { Text(if (state.running) "停止当前任务" else "一键归类", style = MaterialTheme.typography.titleMedium) }
                    if (state.undoAvailable && !state.running) TextButton(
                        onClick = onUndo, enabled = state.connected, modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Restore, null)
                        Spacer(Modifier.size(8.dp))
                        Text("撤销上一次归类")
                    }
                }
            }
        }
        item { DestinationCard() }
        item { ScheduleCard(schedule, scheduleSavedText, onScheduleChange, onSaveSchedule) }
        item { SourceCard() }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
private fun SourceCard() {
    DetailExpandableText(
        "查看归类范围",
        "查找公共下载、蓝牙接收、浏览器、网盘和聊天应用中的用户文件，以及应用的外部 files、media 目录。\n\n应用缓存、数据库、缩略图、贴纸和临时文件会跳过。归类后可以撤销上一次操作。"
    )
}

@Composable
private fun DestinationCard() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        colors = CardDefaults.cardColors(containerColor = BaiZeTokens.colors.surfaceRaised),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("归类位置", style = MaterialTheme.typography.titleLarge)
            Text("内部存储 / BaiZe归类", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("按类型放进对应文件夹。同名文件会按下方设置处理。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(listOf("图片", "视频", "音频", "文档"), listOf("安装包", "压缩包", "电子书", "其他")).forEach { categories ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { category ->
                        Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Text(category, Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
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
    val intervals = FileOrganizerWorker.ALLOWED_INTERVALS
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        colors = CardDefaults.cardColors(
            containerColor = BaiZeTokens.colors.surfaceRaised
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
                    Text("定时归类", fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                    Text(
                        "自动整理新下载的文件",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
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
                intervals.forEach { minutes ->
                    FilterChip(
                        selected = schedule.intervalMinutes == minutes,
                        onClick = { onChange(schedule.copy(intervalMinutes = minutes)) },
                        label = { Text(FileOrganizerWorker.intervalLabel(minutes)) }
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
            SettingSwitch("仅设备空闲时执行", schedule.idleOnly) {
                onChange(schedule.copy(idleOnly = it))
            }
            Text("同名文件处理", fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (0..2).forEach { policy ->
                    FilterChip(
                        selected = schedule.conflictPolicy == policy,
                        onClick = { onChange(schedule.copy(conflictPolicy = policy)) },
                        label = { Text(FileOrganizerWorker.conflictPolicyLabel(policy)) }
                    )
                }
            }
            SettingSwitch("开启计划后立即执行一次", schedule.runImmediatelyOnEnable) {
                onChange(schedule.copy(runImmediatelyOnEnable = it))
            }
            Text(
                "上次执行：${FileOrganizerWorker.lastRunText(LocalContext.current, schedule)}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                schedule.lastResult,
                fontSize = 13.sp,
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
                Text("保存定时归类", fontWeight = FontWeight.SemiBold)
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
