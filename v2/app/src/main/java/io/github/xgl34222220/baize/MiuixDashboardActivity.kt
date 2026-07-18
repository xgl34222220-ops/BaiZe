package io.github.xgl34222220.baize

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.StatFs
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Compose launcher for Alpha 22. The proven RootService and shell cleaner remain untouched; this
 * activity is intentionally a thin state bridge so UI work cannot change deletion semantics.
 */
class MiuixDashboardActivity : ComponentActivity() {
    private val preferences by lazy { getSharedPreferences("baize_v2", MODE_PRIVATE) }
    private var rootService: IProfileRootService? = null
    private var bound = false
    private var pendingClean = false
    private var pollJob: Job? = null

    private var dashboardState = androidx.compose.runtime.mutableStateOf(DashboardUiState())
    private var schedulerState = androidx.compose.runtime.mutableStateOf(SchedulerUiState())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            rootService = IProfileRootService.Stub.asInterface(binder)
            bound = true
            dashboardState.value = dashboardState.value.copy(connected = true, serviceText = "正在校验模块组件…")
            refreshAll()
            if (pendingClean) {
                pendingClean = false
                runModuleTask("clean")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rootService = null
            bound = false
            pollJob?.cancel()
            dashboardState.value = dashboardState.value.copy(
                connected = false,
                ready = false,
                running = false,
                serviceText = "Root 服务已断开"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        pendingClean = intent.getBooleanExtra(EXTRA_RUN_SMART_CLEAN, false)
        updateStorage()

        setContent {
            BaiZeMiuixApp(
                state = dashboardState.value,
                scheduler = schedulerState.value,
                actions = DashboardActions(
                    refresh = { refreshAll() },
                    clean = { runSmartClean() },
                    scan = { runModuleTask("scan") },
                    stop = { stopTask() },
                    deep = { confirmDeepClean() },
                    corpses = { openProfile("corpses") },
                    audit = { startActivity(Intent(this, CleanCenterActivity::class.java)) },
                    updateScheduler = { schedulerState.value = it },
                    saveScheduler = { saveScheduler(it) },
                    clearHistory = { confirmClearHistory() },
                    whitelist = { startActivity(Intent(this, WhitelistActivity::class.java)) },
                    theme = { startActivity(Intent(this, ThemeSettingsActivity::class.java)) },
                    reconnect = { reconnectService() },
                    crash = { showCrashDialog() }
                )
            )
        }
        connectService()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_RUN_SMART_CLEAN, false)) {
            if (rootService == null) pendingClean = true else runSmartClean()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStorage()
        if (rootService != null) refreshAll()
    }

    private fun refreshAll() {
        updateStorage()
        readServiceStatus()
        loadScheduler()
        refreshModuleState()
        refreshHistory()
        refreshWhitelist()
    }

    private fun connectService() {
        dashboardState.value = dashboardState.value.copy(serviceText = "正在连接 Root 服务…")
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java)
                    .addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
            bound = true
        }.onFailure {
            dashboardState.value = dashboardState.value.copy(serviceText = "Root 服务启动失败：${it.message.orEmpty()}")
        }
    }

    private fun reconnectService() {
        if (bound) runCatching { RootService.unbind(connection) }
        rootService = null
        bound = false
        connectService()
        toast("正在重新连接 Root 服务")
    }

    private fun readServiceStatus() {
        val service = rootService ?: return
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { JSONObject(service.ping()) }.getOrNull()
            } ?: return@launch
            val root = json.optBoolean("root")
            val module = json.optBoolean("module")
            val cleaner = json.optBoolean("cleaner")
            val scheduler = json.optBoolean("scheduler")
            val rules = json.optBoolean("deepRules")
            val ready = root && module && cleaner && scheduler && rules
            val text = when {
                !root -> "服务已连接，但未取得完整 Root"
                !module -> "Root 已连接 · 未检测到白泽模块"
                !cleaner -> "模块已连接 · 清理引擎缺失"
                !scheduler -> "清理引擎已连接 · 调度器缺失"
                !rules -> "自动清理可用 · 深度规则库缺失"
                else -> "Root、自动清理、定时任务与规则库均已就绪"
            }
            dashboardState.value = dashboardState.value.copy(
                connected = true,
                ready = ready,
                serviceText = text,
                device = Build.MODEL,
                android = "Android ${Build.VERSION.RELEASE}"
            )
        }
    }

    private fun updateStorage() {
        runCatching {
            val stat = StatFs(dataDir.absolutePath)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            val used = (total - free).coerceAtLeast(0L)
            dashboardState.value = dashboardState.value.copy(
                storageTotal = total,
                storageFree = free,
                storageUsed = used,
                storagePercent = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
            )
        }
    }

    private fun runSmartClean() {
        if (schedulerState.value.notifyOnComplete) requestNotificationPermission()
        runModuleTask("clean")
    }

    private fun runModuleTask(mode: String) {
        val service = rootService ?: run {
            pendingClean = mode == "clean"
            connectService()
            return
        }
        if (dashboardState.value.running) return
        dashboardState.value = dashboardState.value.copy(
            running = true,
            taskPhase = if (mode == "scan") "正在执行安全扫描…" else "正在智能扫描并清理…"
        )
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive && dashboardState.value.running) {
                val raw = withContext(Dispatchers.IO) { runCatching { service.getTaskState() }.getOrNull() }
                raw?.let { renderTaskState(JSONObject(it)) }
                delay(450)
            }
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { JSONObject(service.runModuleTask(mode)) } }
            pollJob?.cancel()
            result.onSuccess { renderTaskResult(it) }.onFailure {
                dashboardState.value = dashboardState.value.copy(
                    running = false,
                    taskPhase = "任务失败：${it.message ?: it.javaClass.simpleName}"
                )
            }
            refreshModuleState()
            refreshHistory()
            updateStorage()
        }
    }

    private fun renderTaskState(json: JSONObject) {
        val current = json.optInt("progress_current", json.optInt("current", 0))
        val total = json.optInt("progress_total", json.optInt("total", 0))
        val path = json.optString("current_path", json.optString("currentPath"))
        val text = buildString {
            append(json.optString("phase", "任务执行中"))
            if (total > 0) append(" · $current/$total")
            if (path.isNotBlank()) append("\n").append(path.takeLast(64))
            if (json.optBoolean("cancelRequested")) append("\n正在安全停止…")
        }
        dashboardState.value = dashboardState.value.copy(taskPhase = text)
    }

    private fun renderTaskResult(json: JSONObject) {
        val success = json.optBoolean("success")
        val cancelled = json.optBoolean("cancelled")
        val elapsedMs = json.optLong("elapsedMs").coerceAtLeast(0L)
        val latest = json.optJSONObject("latest") ?: JSONObject()
        val bytes = latest.optLong("bytes", 0L).coerceAtLeast(0L)
        val regular = latest.optLong("regular_files", latest.optLong("files", 0L)).coerceAtLeast(0L)
        val emptyFiles = latest.optLong("empty_files", 0L).coerceAtLeast(0L)
        val emptyDirs = latest.optLong("empty_dirs", 0L).coerceAtLeast(0L)
        val fragments = latest.optLong("fragment_files", 0L).coerceAtLeast(0L)
        val message = when {
            cancelled -> "任务已停止"
            success -> json.optString("message", "清理完成")
            else -> json.optString("message", "任务失败")
        }
        dashboardState.value = dashboardState.value.copy(
            running = false,
            taskPhase = "$message · ${formatElapsed(elapsedMs / 1000)}",
            lastReleased = bytes
        )
        preferences.edit().putLong("last_clean_bytes", bytes).apply()
        val config = schedulerState.value
        if (config.notifyOnComplete && (!success || cancelled || bytes > 0 || config.notifyZero)) {
            NativeNotifier.showTaskResult(
                this,
                if (success) "白泽清理完成" else if (cancelled) "白泽任务已停止" else "白泽任务失败",
                "释放 ${formatBytes(bytes)} · 文件 $regular 个",
                "空文件 $emptyFiles 个 · 空目录 $emptyDirs 个 · 碎片 $fragments 个 · ${formatElapsed(elapsedMs / 1000)}"
            )
        }
    }

    private fun stopTask() {
        rootService?.cancelCurrentTask()
        dashboardState.value = dashboardState.value.copy(taskPhase = "正在安全停止当前任务…")
    }

    private fun loadScheduler() {
        val service = rootService ?: return
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { JSONObject(service.getSchedulerConfig()) }.getOrNull()
            } ?: return@launch
            schedulerState.value = SchedulerUiState.fromJson(json)
        }
    }

    private fun saveScheduler(config: SchedulerUiState) {
        val service = rootService ?: return toast("Root 服务尚未连接")
        if (config.notifyOnComplete) requestNotificationPermission()
        schedulerState.value = config.copy(saving = true)
        lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) {
                runCatching { JSONObject(service.saveSchedulerConfig(config.toJson().toString())) }
            }
            val success = response.getOrNull()?.optBoolean("success") == true
            schedulerState.value = config.copy(saving = false)
            toast(if (success) "设置已保存，调度器会自动读取" else "保存失败：${response.exceptionOrNull()?.message ?: "未知错误"}")
            refreshModuleState()
        }
    }

    private fun refreshModuleState() {
        val service = rootService ?: return
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { JSONObject(service.getModuleState()) }.getOrNull()
            } ?: return@launch
            val latest = json.optJSONObject("latest") ?: JSONObject()
            val scheduler = json.optJSONObject("scheduler") ?: JSONObject()
            dashboardState.value = dashboardState.value.copy(
                lastReleased = latest.optLong("bytes", preferences.getLong("last_clean_bytes", 0L)).coerceAtLeast(0L),
                schedulerText = when (scheduler.optString("state", "waiting")) {
                    "running" -> "定时任务正在执行"
                    "completed" -> "最近定时任务已完成"
                    "failed" -> "定时任务失败：${scheduler.optString("reason")}"
                    "disabled" -> "自动清理已关闭"
                    else -> scheduler.optString("reason", "等待调度器首次轮询")
                }
            )
        }
    }

    private fun refreshHistory() {
        val service = rootService ?: return
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { JSONObject(service.getTaskHistory(100)) }.getOrNull()
            } ?: return@launch
            if (!json.optBoolean("success")) return@launch
            val array = json.optJSONArray("entries")
            val entries = buildList {
                if (array != null) for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        HistoryUiItem(
                            title = historyModeTitle(item.optString("mode")),
                            time = item.optString("time"),
                            trigger = historyTrigger(item.optString("trigger")),
                            result = item.optString("result", if (item.optBoolean("cleaned")) "清理完成" else "扫描完成"),
                            bytes = item.optLong("bytes", 0L).coerceAtLeast(0L),
                            files = item.optInt("files", 0).coerceAtLeast(0),
                            emptyDirs = item.optInt("emptyDirs", 0).coerceAtLeast(0),
                            errors = item.optInt("errors", 0).coerceAtLeast(0),
                            cleaned = item.optBoolean("cleaned")
                        )
                    )
                }
            }
            dashboardState.value = dashboardState.value.copy(
                history = entries,
                lifetimeRuns = json.optLong("lifetimeRuns", json.optLong("cleanedRuns", 0L)).coerceAtLeast(0L),
                lifetimeReleased = json.optLong("lifetimeReleased", json.optLong("totalReleased", 0L)).coerceAtLeast(0L),
                lifetimeFiles = json.optLong("lifetimeFiles", 0L).coerceAtLeast(0L),
                lifetimeEmptyFiles = json.optLong("lifetimeEmptyFiles", 0L).coerceAtLeast(0L),
                lifetimeEmptyDirs = json.optLong("lifetimeEmptyDirs", 0L).coerceAtLeast(0L),
                lifetimeFragments = json.optLong("lifetimeFragments", 0L).coerceAtLeast(0L),
                lifetimeElapsed = json.optLong("lifetimeElapsed", 0L).coerceAtLeast(0L)
            )
        }
    }

    private fun refreshWhitelist() {
        val service = rootService ?: return
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching { org.json.JSONArray(service.getWhitelistPackages()).length() }.getOrDefault(0)
            }
            dashboardState.value = dashboardState.value.copy(whitelistCount = count)
        }
    }

    private fun confirmDeepClean() {
        AlertDialog.Builder(this)
            .setTitle("进入深度清理？")
            .setMessage("会扫描 OEM 日志、自定义规则和高风险候选项；进入后仍会先展示候选，不会直接删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("继续") { _, _ -> openProfile("deep") }
            .show()
    }

    private fun openProfile(profile: String) {
        startActivity(Intent(this, ProfileActivity::class.java).putExtra(ProfileActivity.EXTRA_PROFILE, profile))
    }

    private fun confirmClearHistory() {
        val service = rootService ?: return
        AlertDialog.Builder(this)
            .setTitle("清空最近记录？")
            .setMessage("只删除最近任务摘要；累计清理次数与累计释放空间会继续保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    val success = withContext(Dispatchers.IO) {
                        runCatching { JSONObject(service.clearTaskHistory()).optBoolean("success") }.getOrDefault(false)
                    }
                    toast(if (success) "最近记录已清空" else "清空失败")
                    refreshHistory()
                }
            }.show()
    }

    private fun showCrashDialog() {
        AlertDialog.Builder(this)
            .setTitle("崩溃诊断")
            .setMessage(CrashRecorder.read(this) ?: "暂无 App 崩溃记录")
            .setNegativeButton("关闭", null)
            .setPositiveButton("清除记录") { _, _ -> CrashRecorder.clear(this) }
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2201)
        }
    }

    private fun formatBytes(bytes: Long): String = Formatter.formatFileSize(this, bytes.coerceAtLeast(0L))

    private fun formatElapsed(seconds: Long): String = when {
        seconds >= 3600 -> "耗时 ${seconds / 3600}小时${seconds % 3600 / 60}分"
        seconds >= 60 -> "耗时 ${seconds / 60}分${seconds % 60}秒"
        else -> "耗时 ${seconds}秒"
    }

    private fun historyModeTitle(mode: String): String = when (mode) {
        "scan" -> "智能安全扫描"
        "clean" -> "智能自动清理"
        "cache-clean" -> "应用缓存清理"
        "empty-clean" -> "空文件与空目录"
        "rules-clean" -> "规则垃圾与日志"
        "fragment-scan" -> "残留碎片扫描"
        "fragment-clean" -> "残留碎片清理"
        "deep-scan" -> "完整深度扫描"
        "deep-clean" -> "完整深度清理"
        "corpse-scan", "corpse-clean" -> "卸载残留清理"
        else -> "白泽清理任务"
    }

    private fun historyTrigger(trigger: String): String = when {
        trigger.startsWith("scheduled:") -> "自动定时"
        trigger.startsWith("daily:") -> "每日定时"
        trigger == "app" -> "App 手动"
        trigger == "manual" -> "手动执行"
        trigger.isBlank() -> "历史任务"
        else -> trigger
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        pollJob?.cancel()
        if (bound) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RUN_SMART_CLEAN = "io.github.xgl34222220.baize.RUN_SMART_CLEAN"
    }
}
