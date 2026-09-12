package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

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
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item { DetailPageHeader("文件归类", "让下载、接收的文件各归其位", onBack) }
        item {
            DetailGlassPanel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .08f)) {
                        Icon(Icons.Rounded.FolderCopy, null, Modifier.padding(11.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(if (state.running) "正在整理文件" else if (state.lastTotal > 0) "已整理 ${state.lastTotal} 个文件" else "整理散落文件",
                            fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(if (state.lastTotal > 0) android.text.format.Formatter.formatFileSize(LocalContext.current, state.lastBytes) else "按类型自动放入对应文件夹",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                DetailStatusText(state.status, Modifier.padding(top = 10.dp, bottom = 14.dp))
                if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp))
                GlassActionButton(if (state.running) "停止当前任务" else "一键归类",
                    if (state.running) onStop else onOneTap, enabled = state.connected,
                    modifier = Modifier.fillMaxWidth(), secondary = state.running)
                if (state.undoAvailable && !state.running) TextButton(
                    onClick = onUndo, enabled = state.connected, modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Icon(Icons.Rounded.Restore, null, Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("撤销上一次归类", fontSize = 13.sp)
                }
            }
        }
        item { DetailSectionHeader("归类位置", "内部存储 / BaiZe归类") }
        item { DestinationCard() }
        item { DetailSectionHeader("自动归类") }
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
    DetailGlassPanel {
        val categories = listOf(
            "图片" to Icons.Rounded.Image, "视频" to Icons.Rounded.Movie,
            "音频" to Icons.Rounded.MusicNote, "文档" to Icons.Rounded.Description,
            "安装包" to Icons.Rounded.InstallMobile, "压缩包" to Icons.Rounded.FolderZip,
            "电子书" to Icons.Rounded.MenuBook, "其他" to Icons.Rounded.MoreHoriz
        )
        categories.chunked(4).forEachIndexed { index, row ->
            if (index > 0) Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (label, icon) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .8f))
                        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleCard(
    schedule: FileOrganizerScheduleSettings,
    savedText: String,
    onChange: (FileOrganizerScheduleSettings) -> Unit,
    onSave: () -> Unit
) {
    val intervals = FileOrganizerWorker.ALLOWED_INTERVALS
    var advanced by rememberSaveable { mutableStateOf(false) }
    DetailGlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("定时归类", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                Text("自动整理新下载的文件", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Switch(checked = schedule.enabled, onCheckedChange = { onChange(schedule.copy(enabled = it)) },
                modifier = Modifier.semantics { contentDescription = "定时文件归类" })
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            intervals.forEach { minutes ->
                FilterChip(selected = schedule.intervalMinutes == minutes,
                    onClick = { onChange(schedule.copy(intervalMinutes = minutes)) },
                    label = { Text(FileOrganizerWorker.intervalLabel(minutes), fontSize = 12.sp) },
                    border = null, shape = RoundedCornerShape(12.dp))
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .055f))
        Text("同名文件", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            (0..2).forEach { policy ->
                FilterChip(selected = schedule.conflictPolicy == policy,
                    onClick = { onChange(schedule.copy(conflictPolicy = policy)) },
                    label = { Text(FileOrganizerWorker.conflictPolicyLabel(policy), fontSize = 12.sp) },
                    border = null, shape = RoundedCornerShape(12.dp))
            }
        }
        Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }.heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("执行条件", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                val conditions = buildList {
                    if (schedule.chargingOnly) add("充电")
                    if (schedule.screenOffOnly) add("息屏")
                    if (schedule.idleOnly) add("设备空闲")
                }
                Text(conditions.joinToString(" · ").ifBlank { "不限制执行条件" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (advanced) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                if (advanced) "收起执行条件" else "展开执行条件", Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (advanced) {
            SettingSwitch("仅充电时执行", schedule.chargingOnly) { onChange(schedule.copy(chargingOnly = it)) }
            SettingSwitch("仅息屏时执行", schedule.screenOffOnly) { onChange(schedule.copy(screenOffOnly = it)) }
            SettingSwitch("仅设备空闲时执行", schedule.idleOnly) { onChange(schedule.copy(idleOnly = it)) }
            SettingSwitch("开启计划后立即执行一次", schedule.runImmediatelyOnEnable) { onChange(schedule.copy(runImmediatelyOnEnable = it)) }
        }
        DetailStatusText("上次执行：${FileOrganizerWorker.lastRunText(LocalContext.current, schedule)}\n${schedule.lastResult}", Modifier.padding(top = 8.dp, bottom = 12.dp))
        if (savedText.isNotBlank()) Text(savedText, Modifier.padding(bottom = 10.dp), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
        GlassActionButton("保存定时归类", onSave, modifier = Modifier.fillMaxWidth(), secondary = true)
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 10.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        Switch(checked = checked, onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = label })
    }
}
