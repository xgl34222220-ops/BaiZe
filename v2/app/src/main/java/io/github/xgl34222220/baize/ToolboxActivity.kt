package io.github.xgl34222220.baize

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.*
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

class ToolboxActivity : ComponentActivity() {
    private val appearance: AppearanceViewModel by viewModels()
    private val model: ToolboxViewModel by viewModels()
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.export { value -> contentResolver.openOutputStream(uri)?.use { it.write(value.toByteArray()) } }
    }
    private val importConfig = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.import { contentResolver.openInputStream(uri)?.use { input ->
            val bytes = ByteArray(128001); val output = java.io.ByteArrayOutputStream()
            while (true) { val n = input.read(bytes); if (n < 0) break; require(output.size() + n <= 128000) { "配置文件过大" }; output.write(bytes, 0, n) }
            output.toString("UTF-8")
        }.orEmpty() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        model.bind()
        setContent {
            val settings by appearance.settings.collectAsState()
            BaiZeTheme(settings) {
                CompositionLocalProvider(LocalAppearanceSettings provides settings) {
                    ToolboxScreen(model, ::finish,
                        onWhitelist = { startActivity(Intent(this, WhitelistActivity::class.java)) },
                        onAppearance = { startActivity(Intent(this, ThemeSettingsActivity::class.java)) },
                        onExport = { export.launch("BaiZe-2.0.0-功能配置.json") },
                        onImport = { importConfig.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        onNotification = { if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) })
                }
            }
        }
    }
}

class ToolboxViewModel(application: Application) : AndroidViewModel(application) {
    var snapshot by mutableStateOf(JSONObject()); private set
    var connected by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf("正在连接 Root 服务…"); private set
    var packages by mutableStateOf<List<Pair<String, String>>>(emptyList()); private set
    private var remote: IProfileRootService? = null
    private var polling: Job? = null
    private var binding = false
    private var lastResultId = ""
    private val context get() = getApplication<Application>()
    val running get() = snapshot.optJSONObject("state")?.optBoolean("running") == true
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = RootServiceClients.profile(binder, context.cacheDir)
            connected = true; message = "已连接"; binding = false
            polling?.cancel()
            polling = viewModelScope.launch {
                while (connected) { refresh(); delay(if (running) 1000 else 5000) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { connected = false; remote = null; message = "Root 连接已断开，点击重连" }
        override fun onBindingDied(name: ComponentName?) { onServiceDisconnected(name); binding = false }
        override fun onNullBinding(name: ComponentName?) { onServiceDisconnected(name); binding = false }
    }
    fun bind() {
        if (connected || binding) return
        binding = true
        runCatching { RootService.bind(Intent(context, BaiZeProfileRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE), connection) }
            .onFailure { binding = false; message = it.message.orEmpty() }
        viewModelScope.launch { delay(20000); if (!connected) { binding = false; message = "Root 连接超时，请检查授权并重连" } }
        FileOrganizerWorker.ensureWatchdog(context)
    }
    private suspend fun call(operation: String, args: JSONArray = JSONArray()): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(RootServiceClients.profileExchange(requireNotNull(remote) { "Root 尚未连接" }, context.cacheDir, operation, args))
    }
    private suspend fun refresh() {
        runCatching { call("toolboxSnapshot") }.onSuccess {
            if (it.optBoolean("success")) {
                snapshot = it
                val result = it.optJSONObject("state")?.optJSONObject("result")
                if (result != null && result.optString("taskId") != lastResultId) {
                    lastResultId = result.optString("taskId")
                    message = result.optString("message", "任务已结束")
                }
                ToolboxNotifications.show(context, it)
            }
            else message = it.optString("message", "读取失败")
        }.onFailure { message = it.message.orEmpty() }
    }
    fun action(operation: String, argument: String? = null) {
        if (busy || !connected) return
        busy = true
        viewModelScope.launch {
            try {
                val result = call(operation, JSONArray().apply { if (argument != null) put(argument) })
                message = result.optString("message", if (result.optBoolean("success")) "已完成" else "操作失败")
                refresh()
            } catch (e: Exception) { message = e.message.orEmpty() }
            finally { busy = false }
        }
    }
    fun save(change: (JSONObject) -> Unit) {
        val config = JSONObject((snapshot.optJSONObject("config") ?: return).toString())
        change(config); action("toolboxSave", config.toString())
    }
    fun loadPackages() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                context.packageManager.getInstalledApplications(0).filter { it.packageName != context.packageName }
                    .map { it.packageName to context.packageManager.getApplicationLabel(it).toString() }.sortedBy { it.second }
            } }.onSuccess { packages = it }
        }
    }
    fun export(write: (String) -> Unit) {
        val config = snapshot.optJSONObject("config")?.toString(2) ?: return
        viewModelScope.launch { message = withContext(Dispatchers.IO) { runCatching { write(config); "配置已导出" }.getOrElse { it.message.orEmpty() } } }
    }
    fun import(read: () -> String) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { read() } }.onSuccess { action("toolboxSave", it) }.onFailure { message = it.message.orEmpty() }
        }
    }
    override fun onCleared() { polling?.cancel(); runCatching { RootService.unbind(connection) }; super.onCleared() }
}

@Composable
internal fun ToolboxScreen(model: ToolboxViewModel, onBack: () -> Unit, onWhitelist: () -> Unit, onAppearance: () -> Unit,
                          onExport: () -> Unit, onImport: () -> Unit, onNotification: () -> Unit) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var edit by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    val config = model.snapshot.optJSONObject("config") ?: JSONObject()
    val enabled = model.connected && !model.busy && !model.running
    val stats = model.snapshot.optJSONObject("statistics") ?: JSONObject()
    Scaffold(containerColor = BaiZeTokens.colors.surfaceBase,
        topBar = { DetailPageHeader("功能控制台", "专项清理 · 系统维护 · 独立计划", onBack) {
            IconButton(onClick = onWhitelist) { Icon(Icons.Rounded.Shield, "保护白名单") }
        } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                DetailGlassPanel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text("今日删除文件"); Text(Formatter.formatFileSize(context, stats.optLong("todayBytes")), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
                        Column { Text("累计删除文件"); Text(Formatter.formatFileSize(context, stats.optLong("totalBytes")), style = MaterialTheme.typography.headlineSmall) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(model.message, style = MaterialTheme.typography.bodySmall)
                    if (model.running) {
                        val progress = model.snapshot.optJSONObject("progress") ?: JSONObject()
                        Text(progress.optString("phase", "正在执行"), Modifier.padding(top = 8.dp))
                        Text(progress.optString("currentPath"), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!model.connected) Button(onClick = model::bind) { Text("连接 Root") }
                        else if (model.running) Button(onClick = { model.action("toolboxCancel") }, enabled = !model.busy) { Text("停止任务") }
                        else Button(onClick = { model.action("toolboxRun", "all") }, enabled = enabled) { Text("执行已勾选功能") }
                        if (model.busy) CircularProgressIndicator(Modifier.size(24.dp))
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("全部功能", "独立计划", "配置与规则", "结果记录").forEachIndexed { i, title -> FilterChip(tab == i, { tab = i }, { Text(title) }) }
                }
            }
            when (tab) {
                0 -> {
                    item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, label = { Text("搜索功能") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }) }
                    items(ToolboxCatalog.tasks.filter { query.isBlank() || (it.title + it.description + it.group).contains(query, true) }, key = { it.id }) { task ->
                        val settings = config.optJSONObject("tasks")?.optJSONObject(task.id) ?: JSONObject()
                        DetailGlassPanel {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(task.title, style = MaterialTheme.typography.titleMedium); Text(task.description, style = MaterialTheme.typography.bodySmall) }
                                Checkbox(settings.optBoolean("enabled"), { value -> model.save { it.getJSONObject("tasks").getJSONObject(task.id).put("enabled", value) } }, enabled = enabled)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(if (settings.optBoolean("enabled")) "已加入一键执行" else "单独执行 / 勾选加入队列", style = MaterialTheme.typography.labelSmall)
                                TextButton(onClick = { model.action("toolboxRun", task.id) }, enabled = enabled) { Text("立即执行") }
                            }
                        }
                    }
                    item { DetailGlassPanel { TextButton(onClick = { model.action("toolboxRun", "undo") }, enabled = enabled) { Text("撤销最近一次归类 / 下载转移") } } }
                    item { DetailGlassPanel { TextButton(onClick = { model.action("toolboxRun", "thaw") }, enabled = enabled) { Text("恢复白泽冻结 / 调整过的进程") } } }
                    item { DetailGlassPanel { TextButton(onClick = { model.action("toolboxRun", "unmount") }, enabled = enabled) { Text("解除白泽目录重定向（保留目标文件）") } } }
                    item { DevicePanel(model.snapshot.optJSONObject("device") ?: JSONObject()) }
                }
                1 -> {
                    item { DetailGlassPanel { Text("每项独立设置执行时间和间隔天数。Root 服务每 30 秒检查一次；重启后由系统后台任务恢复。六小时内补做，关屏/充电条件不满足时等待。", style = MaterialTheme.typography.bodySmall)
                        Toggle("仅关屏时自动执行", config.optBoolean("screenOffOnly", true), enabled) { model.save { c -> c.put("screenOffOnly", it) } }
                        Toggle("仅充电时自动执行", config.optBoolean("chargingOnly"), enabled) { model.save { c -> c.put("chargingOnly", it) } }
                    } }
                    items(ToolboxCatalog.tasks, key = { it.id }) { task ->
                        val settings = config.optJSONObject("tasks")?.optJSONObject(task.id) ?: JSONObject()
                        DetailGlassPanel {
                            Toggle(task.title, settings.optBoolean("scheduled"), enabled) { value -> model.save { it.getJSONObject("tasks").getJSONObject(task.id).put("scheduled", value) } }
                            TextButton(onClick = { edit = "schedule:${task.id}" }, enabled = enabled) { Text("每 ${settings.optInt("intervalDays", 1)} 天 · ${settings.optString("time", "02:30")}") }
                        }
                    }
                }
                2 -> {
                    item { DetailGlassPanel {
                        Text("运行设置", style = MaterialTheme.typography.titleMedium)
                        Setting("进程模式（kill / freeze / oom）", "processMode", config, enabled) { edit = it }
                        Toggle("保留已冻结进程", config.optBoolean("skipFrozen", true), enabled) { model.save { c -> c.put("skipFrozen", it) } }
                        Toggle("持续按阈值管理后台", config.optBoolean("processContinuous"), enabled) { model.save { c -> c.put("processContinuous", it) } }
                        Setting("后台检查间隔（30–3600 秒）", "pressureIntervalSeconds", config, enabled) { edit = it }
                        Setting("进程管理应用", "processPackages", config, enabled) { edit = it; model.loadPackages() }
                        Setting("进程保护名单", "processWhitelist", config, enabled) { edit = it; model.loadPackages() }
                        Setting("数据库应用", "databasePackages", config, enabled) { edit = it; model.loadPackages() }
                        Setting("编译应用", "compilePackages", config, enabled) { edit = it; model.loadPackages() }
                        Setting("内存占用阈值（%）", "memoryThreshold", config, enabled) { edit = it }
                        Setting("F2FS 完成阈值（脏段）", "dirtyThreshold", config, enabled) { edit = it }
                        Setting("F2FS 最长运行秒数", "gcSeconds", config, enabled) { edit = it }
                        Setting("碎片保留天数", "fragmentDays", config, enabled) { edit = it }
                        Setting("编译模式", "compilerFilter", config, enabled) { edit = it }
                        Toggle("强制重新编译", config.optBoolean("forceCompile"), enabled) { model.save { c -> c.put("forceCompile", it) } }
                        Toggle("SQLite 额外压缩（VACUUM）", config.optBoolean("vacuum"), enabled) { model.save { c -> c.put("vacuum", it) } }
                    } }
                    item { DetailGlassPanel {
                        Text("文件规则", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { edit = "organizerRules" }, enabled = enabled) { Text("编辑归类规则（来源 + 类型 + 目标）") }
                        Toggle("转移后启用目录重定向", config.optBoolean("bindRedirect"), enabled) { model.save { c -> c.put("bindRedirect", it) } }
                        Text("绑定重定向需先填写完整目录规则：来源+目标；重启后由计划或手动再次执行。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { edit = "downloadRules" }, enabled = enabled) { Text("编辑下载转移规则") }
                        TextButton(onClick = { edit = "customRules" }, enabled = enabled) { Text("额外清理名单 / 自定义规则") }
                        TextButton(onClick = onWhitelist) { Text("管理应用与路径白名单") }
                    } }
                    item { DetailGlassPanel {
                        Text("前台规则库", style = MaterialTheme.typography.titleMedium)
                        val rules = model.snapshot.optJSONObject("rules") ?: JSONObject()
                        val meta = rules.optJSONObject("metadata") ?: JSONObject()
                        Text("${meta.optString("rules_version", "内置版本")} · ${meta.optString("rules_count", "—")} 条")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { model.action("toolboxRulesUpdate") }, enabled = enabled) { Text("检查并更新") }
                            TextButton(onClick = { model.action("toolboxRulesRollback") }, enabled = enabled && rules.optBoolean("rollbackAvailable")) { Text("回退上版") }
                        }
                    } }
                    item { DetailGlassPanel {
                        Text("显示、统计与配置", style = MaterialTheme.typography.titleMedium)
                        Toggle("记录清理统计", config.optBoolean("statistics", true), enabled) { model.save { c -> c.put("statistics", it) } }
                        Toggle("完成通知", config.optBoolean("notifications", true), enabled) { value -> model.save { c -> c.put("notifications", value) }; if (value) onNotification() }
                        Setting("执行次数清零阈值（0 表示不清零）", "counterReset", config, enabled) { edit = it }
                        TextButton(onClick = onAppearance) { Text("主题 / 背景 / 卡片模糊") }
                        Row { TextButton(onClick = onExport, enabled = enabled) { Text("导出配置") }; TextButton(onClick = onImport, enabled = enabled) { Text("导入配置") } }
                        TextButton(onClick = { model.action("toolboxResetStats") }, enabled = enabled) { Text("清零控制台统计") }
                    } }
                }
                3 -> {
                    val history = model.snapshot.optJSONArray("history") ?: JSONArray()
                    if (history.length() == 0) item { DetailGlassPanel { Text("尚无任务结果。提交后的任务会在实际结束后写入记录。") } }
                    items((0 until history.length()).map { history.getJSONObject(it) }, key = { it.optString("taskId") }) { row ->
                        DetailGlassPanel(Modifier.clickable { detail = row }) {
                            Text(row.optString("message"), style = MaterialTheme.typography.titleSmall)
                            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.optLong("finishedAt"))), style = MaterialTheme.typography.bodySmall)
                            Text("删除 ${Formatter.formatFileSize(context, row.optLong("deletedBytes"))} · ${row.optLong("deletedFiles")} 个文件 · ${row.optLong("elapsedMs") / 1000} 秒")
                            val results = row.optJSONArray("results") ?: JSONArray()
                            for (i in 0 until results.length()) {
                                val result = results.getJSONObject(i)
                                Text("${if (result.optBoolean("success")) "✓" else "!"} ${result.optString("title")} · ${result.optString("message", if (result.optBoolean("success")) "完成" else "未完成")}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("点击查看详细记录", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    item { TextButton(onClick = { model.action("toolboxClearHistory") }, enabled = enabled, modifier = Modifier.padding(horizontal = 16.dp)) { Text("清空控制台记录") } }
                }
            }
        }
    }
    edit?.let { key ->
        ToolboxEditor(key, config, model.snapshot.optString("customRules"), model.packages, { edit = null }) { value ->
            if (key == "customRules") model.action("toolboxRulesSave", value)
            else if (key.startsWith("schedule:")) model.save { it.getJSONObject("tasks").getJSONObject(key.substringAfter(':')).apply {
                val parts = value.split('|'); put("time", parts[0]); put("intervalDays", parts[1].toIntOrNull() ?: 1)
            } } else model.save { it.put(key, value) }
            edit = null
        }
    }
    detail?.let { row -> BaiZeDialog(onDismissRequest = { detail = null }, title = { Text("任务详细记录") },
        text = { LazyColumn(Modifier.heightIn(max = 450.dp)) { item { SelectionContainer { Text(row.toString(2), style = MaterialTheme.typography.bodySmall) } } } },
        confirmButton = { BaiZeDialogButton(onClick = { detail = null }) { Text("关闭") } }) }
}

@Composable private fun Toggle(title: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(checked, change, enabled = enabled) }
}
@Composable private fun Setting(title: String, key: String, config: JSONObject, enabled: Boolean, edit: (String) -> Unit) {
    TextButton(onClick = { edit(key) }, enabled = enabled, contentPadding = PaddingValues(vertical = 8.dp)) {
        Column(Modifier.fillMaxWidth()) { Text(title); Text(config.optString(key).replace('\n', ' ').take(70).ifBlank { "尚未设置" }, style = MaterialTheme.typography.bodySmall) }
    }
}
@Composable private fun DevicePanel(device: JSONObject) {
    val context = LocalContext.current
    DetailGlassPanel {
        Text("实时设备状态", style = MaterialTheme.typography.titleMedium)
        val mem = device.optJSONObject("memory") ?: JSONObject()
        Text("可用内存 ${Formatter.formatFileSize(context, mem.optLong("availableKb") * 1024)} / ${Formatter.formatFileSize(context, mem.optLong("totalKb") * 1024)}")
        val partitions = device.optJSONArray("partitions") ?: JSONArray()
        for (i in 0 until partitions.length()) { val p = partitions.getJSONObject(i); Text("${p.optString("path")} · 可用 ${Formatter.formatFileSize(context, p.optLong("freeBytes"))} / ${Formatter.formatFileSize(context, p.optLong("totalBytes"))}", style = MaterialTheme.typography.bodySmall) }
        val nodes = device.optJSONArray("f2fs") ?: JSONArray()
        for (i in 0 until nodes.length()) { val n = nodes.getJSONObject(i); Text("F2FS ${n.optString("device")} · 脏段 ${n.optLong("dirtySegments")} · 空闲段 ${n.optLong("freeSegments")}", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun ToolboxEditor(key: String, config: JSONObject, customRules: String, packages: List<Pair<String, String>>, dismiss: () -> Unit, save: (String) -> Unit) {
    val schedule = key.startsWith("schedule:")
    val packageList = key.endsWith("Packages") || key == "processWhitelist"
    val task = if (schedule) config.optJSONObject("tasks")?.optJSONObject(key.substringAfter(':')) ?: JSONObject() else JSONObject()
    var value by remember(key) { mutableStateOf(if (schedule) task.optString("time", "02:30") else if (key == "customRules") customRules else config.optString(key)) }
    var days by remember(key) { mutableStateOf(task.optInt("intervalDays", 1).toString()) }
    var query by remember(key) { mutableStateOf("") }
    val titles = mapOf("processPackages" to "进程管理应用", "processWhitelist" to "进程保护名单", "databasePackages" to "数据库应用", "compilePackages" to "编译应用",
        "memoryThreshold" to "内存占用阈值（10–99%）", "dirtyThreshold" to "F2FS 脏段阈值", "gcSeconds" to "F2FS 运行时限（1–60 秒）", "fragmentDays" to "碎片保留天数",
        "processMode" to "进程模式", "pressureIntervalSeconds" to "后台检查间隔（秒）", "compilerFilter" to "编译模式", "organizerRules" to "文件归类规则", "downloadRules" to "下载转移规则", "customRules" to "额外清理规则", "counterReset" to "次数清零阈值")
    BaiZeDialog(onDismissRequest = dismiss, title = { Text(if (schedule) "${ToolboxCatalog.task(key.substringAfter(':')).title}计划" else titles[key].orEmpty()) },
        text = { LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                if (key in listOf("organizerRules", "downloadRules")) Text("每行一条：来源+*.apk&&*.zip+目标。支持来源目录通配符。例：/sdcard/Download+*.apk+/sdcard/BaiZe归类/安装包。转移后可在功能页撤销。", style = MaterialTheme.typography.bodySmall)
                if (key == "customRules") Text("每行：目录绝对路径|保留天数。此名单增加清理规则；全局保护名单继续优先。", style = MaterialTheme.typography.bodySmall)
                if (key == "processMode") Text("kill：系统结束后台进程；freeze：系统冻结缓存进程，前台由系统恢复；oom：调整缓存进程回收优先级。功能页可恢复。", style = MaterialTheme.typography.bodySmall)
                if (key == "compilerFilter") Text("verify、speed-profile、speed、everything；系统不支持的模式会返回实际错误。", style = MaterialTheme.typography.bodySmall)
                if (key == "databasePackages") Text("只处理未运行应用的标准 SQLite 数据库；跳过加密数据库和未提交事务。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text(if (schedule) "时间 HH:mm" else if (packageList) "应用包名（每行一个）" else "设置值") }, minLines = if (packageList || key.endsWith("Rules")) 3 else 1)
                if (schedule) OutlinedTextField(days, { days = it }, Modifier.fillMaxWidth(), label = { Text("间隔天数（1–30）") })
                if (packageList) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索已安装应用") })
            }
            if (packageList) items(packages.filter { (it.first + it.second).contains(query, true) }, key = { it.first }) { (pkg, label) ->
                val selected = value.split(Regex("[\\s,，;；]+")).filter(String::isNotBlank).toSet()
                Row(Modifier.fillMaxWidth().clickable { value = (if (pkg in selected) selected - pkg else selected + pkg).sorted().joinToString("\n") }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(pkg in selected, null); Column { Text(label); Text(pkg, style = MaterialTheme.typography.labelSmall) }
                }
            }
        } },
        confirmButton = { BaiZeDialogButton(onClick = { save(if (schedule) "$value|$days" else value) }) { Text("保存") } },
        dismissButton = { BaiZeDialogButton(onClick = dismiss) { Text("取消") } })
}

internal object ToolboxNotifications {
    fun show(context: Context, snapshot: JSONObject) {
        if (snapshot.optJSONObject("config")?.optBoolean("notifications", true) == false) return
        val result = snapshot.optJSONObject("state")?.optJSONObject("result") ?: return
        val id = result.optString("taskId"); if (id.isBlank()) return
        val prefs = context.getSharedPreferences("toolbox-notifications", Context.MODE_PRIVATE)
        if (prefs.getString("last", "") == id) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("toolbox-results", "功能任务结果", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(context, 302, Intent(context, ToolboxActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching { manager.notify(302, NotificationCompat.Builder(context, "toolbox-results").setSmallIcon(R.mipmap.ic_baize)
            .setContentTitle("白泽功能任务结果").setContentText(result.optString("message")).setContentIntent(pending).setAutoCancel(true).build()) }
            .onSuccess { prefs.edit().putString("last", id).apply() }
    }
}
