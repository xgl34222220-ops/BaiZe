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
import android.os.SystemClock
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.BaiZeRootService
import io.github.xgl34222220.baize.root.IBaiZeRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import io.github.xgl34222220.baize.root.ITaskProgressCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compose launcher backed by two native snapshot engines. A safety scan produces immutable,
 * expiring server-side candidate snapshots; the follow-up clean consumes those snapshots and must
 * never restart discovery.
 */
class MiuixDashboardActivity : ComponentActivity() {
    private data class RemoteTaskProbe(
        val complete: Boolean,
        val runningState: JSONObject?
    )

    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private val preferences by lazy { getSharedPreferences("baize_v2", MODE_PRIVATE) }
    private var rootService: IProfileRootService? = null
    private var cacheService: IBaiZeRootService? = null
    private var profileBound = false
    private var cacheBound = false
    private var pendingSmartClean = false
    private var pendingSnapshotClean = false
    private var pendingScanAfterConnect: Boolean? = null
    private var pendingModuleTask: String? = null
    private var pollJob: Job? = null
    private var recoveryProbeJob: Job? = null
    private var taskCallbackRegistered = false
    private val taskProgressCallback = object : ITaskProgressCallback.Stub() {
        override fun onTaskProgress(stateJson: String?) {
            val json = runCatching { JSONObject(stateJson.orEmpty()) }.getOrNull() ?: return
            runOnUiThread { renderTaskState(json) }
        }
    }
    private var cacheSnapshotId = ""
    private var safeSnapshotId = ""
    private var cacheSnapshotCount = 0
    private var safeSnapshotCount = 0
    private var snapshotExpiresAtElapsed = 0L

    private var dashboardState = androidx.compose.runtime.mutableStateOf(DashboardUiState())
    private var schedulerState = androidx.compose.runtime.mutableStateOf(SchedulerUiState())

    private val profileConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            rootService = IProfileRootService.Stub.asInterface(binder)
            profileBound = true
            taskCallbackRegistered = runCatching { rootService?.registerTaskProgressCallback(taskProgressCallback); true }.getOrDefault(false)
            updateConnectionState()
            recoverRemoteTaskOrRefresh(runPendingActions = true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rootService = null
            profileBound = false
            taskCallbackRegistered = false
            pollJob?.cancel()
            updateConnectionState()
            scheduleServiceRecovery(
                requireCache = pendingScanAfterConnect != null || pendingSnapshotClean || safeSnapshotId.isNotBlank()
            )
        }
    }

    private val cacheConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            cacheService = IBaiZeRootService.Stub.asInterface(binder)
            cacheBound = true
            updateConnectionState()
            recoverRemoteTaskOrRefresh(runPendingActions = true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cacheService = null
            cacheBound = false
            pollJob?.cancel()
            updateConnectionState()
            if (pendingScanAfterConnect != null || pendingSnapshotClean || cacheSnapshotId.isNotBlank()) {
                scheduleServiceRecovery(requireCache = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        pendingSmartClean = intent.getBooleanExtra(EXTRA_RUN_SMART_CLEAN, false)
        updateStorage()
        dashboardState.value = dashboardState.value.copy(
            lastTaskTime = preferences.getString("last_task_time", "").orEmpty(),
            protectedItems = loadProtectedItems()
        )

        setContent {
            val appearance = appearanceViewModel.settings.collectAsStateWithLifecycle().value
            BaiZeMiuixApp(
                state = dashboardState.value,
                scheduler = schedulerState.value,
                actions = DashboardActions(
                    refresh = { refreshAll() },
                    clean = { runSmartClean() },
                    scan = { runNativeScan(cleanAfterScan = false) },
                    apkScan = { runApkScan() },
                    cleanScan = { cleanNativeSnapshots() },
                    dismissScan = { clearScanResult() },
                    stop = { stopTask() },
                    deep = { confirmDeepClean() },
                    corpses = { openProfile("corpses") },
                    audit = { startActivity(Intent(this, CleanCenterActivity::class.java)) },
                    updateScheduler = { schedulerState.value = it },
                    saveScheduler = { saveScheduler(it) },
                    clearHistory = { confirmClearHistory() },
                    clearRawLog = { confirmClearRawLogs() },
                    reviewProtected = { startActivity(Intent(this, ProtectedReviewActivity::class.java)) },
                    whitelist = { startActivity(Intent(this, WhitelistActivity::class.java)) },
                    theme = { startActivity(Intent(this, ThemeSettingsActivity::class.java)) },
                    reconnect = { reconnectService() },
                    resetScanPerformance = { resetScanPerformance() },
                    crash = { showCrashDialog() }
                ),
                appearance = appearance
            )
        }
        connectServices()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_RUN_SMART_CLEAN, false)) {
            if (hasUsableScanSnapshots()) {
                cleanNativeSnapshots()
            } else if (rootService == null) {
                pendingSmartClean = true
                connectPrimaryService()
            } else {
                runSmartClean()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStorage()
        if (rootService != null || cacheService != null) {
            recoverRemoteTaskOrRefresh()
        } else {
            connectServices()
        }
    }

    private fun refreshAll() {
        updateStorage()
        if (dashboardState.value.running) {
            readServiceStatus()
            recoverRemoteTaskOrRefresh()
            return
        }
        readServiceStatus()
        loadScheduler()
        refreshModuleState()
        refreshHistory()
        refreshRawLog()
        refreshWhitelist()
    }

    private fun recoverRemoteTaskOrRefresh(runPendingActions: Boolean = false) {
        recoveryProbeJob?.cancel()
        recoveryProbeJob = lifecycleScope.launch {
            val probe = probeRemoteTask()
            val running = probe.runningState
            when {
                running != null -> {
                    dashboardState.value = dashboardState.value.copy(
                        running = true,
                        scanCompleted = false,
                        serviceText = "后台 Root 任务仍在执行，已恢复实时进度"
                    )
                    renderTaskState(running)
                    startRecoveredTaskPoll()
                }
                !probe.complete -> {
                    dashboardState.value = dashboardState.value.copy(
                        connected = rootService != null,
                        ready = false,
                        serviceText = "正在连接后台任务状态…",
                        taskPhase = if (dashboardState.value.running) {
                            "后台任务仍在执行，正在恢复 Root 连接…"
                        } else {
                            dashboardState.value.taskPhase
                        }
                    )
                    connectServices()
                }
                dashboardState.value.running -> {
                    // A just-started Binder task may need a brief moment before running.env appears.
                    // Use the recovery poll instead of immediately restoring stale historical state.
                    startRecoveredTaskPoll()
                }
                else -> {
                    refreshAll()
                    if (runPendingActions) runPendingActionsIfReady()
                }
            }
        }
    }

    private suspend fun probeRemoteTask(): RemoteTaskProbe = withContext(Dispatchers.IO) {
        val expectProfile = rootService != null || profileBound
        val expectCache = cacheService != null || cacheBound
        var profileResponded = !expectProfile
        var cacheResponded = !expectCache
        var runningState: JSONObject? = null

        rootService?.let { service ->
            val state = runCatching { JSONObject(service.getTaskState()) }.getOrNull()
            if (state != null) {
                profileResponded = true
                if (state.optBoolean("running")) runningState = state
            }
        }
        cacheService?.let { service ->
            val state = runCatching { JSONObject(service.getTaskState()) }.getOrNull()
            if (state != null) {
                cacheResponded = true
                if (runningState == null && state.optBoolean("running")) runningState = state
            }
        }

        RemoteTaskProbe(
            complete = (expectProfile || expectCache) && profileResponded && cacheResponded,
            runningState = runningState
        )
    }

    private fun startRecoveredTaskPoll() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            var idleConfirmations = 0
            delay(250)
            while (isActive) {
                val probe = probeRemoteTask()
                val state = probe.runningState
                if (state != null) {
                    idleConfirmations = 0
                    renderTaskState(state)
                    delay(420)
                    continue
                }
                if (!probe.complete) {
                    idleConfirmations = 0
                    dashboardState.value = dashboardState.value.copy(
                        running = true,
                        connected = rootService != null,
                        ready = false,
                        serviceText = "后台任务连接中断，正在自动恢复…",
                        taskPhase = "后台任务仍由 Root 执行，正在重新连接进度…"
                    )
                    connectServices()
                    delay(700)
                    continue
                }

                idleConfirmations += 1
                if (idleConfirmations < 2) {
                    delay(320)
                    continue
                }

                dashboardState.value = dashboardState.value.copy(
                    running = false,
                    scanCompleted = false,
                    taskPhase = "后台任务已结束，正在读取最终结果…"
                )
                delay(220)
                refreshAll()
                updateStorage()
                break
            }
        }
    }

    private fun connectPrimaryService() {
        if (rootService != null || profileBound) return
        dashboardState.value = dashboardState.value.copy(
            connected = false,
            ready = false,
            serviceText = "正在连接 Root 清理服务…"
        )
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java)
                    .addCategory(RootService.CATEGORY_DAEMON_MODE),
                profileConnection
            )
            profileBound = true
        }.onFailure {
            profileBound = false
            dashboardState.value = dashboardState.value.copy(
                connected = false,
                ready = false,
                serviceText = "Root 清理服务启动失败：${it.message.orEmpty()}"
            )
        }
    }

    private fun connectServices() {
        dashboardState.value = dashboardState.value.copy(serviceText = "正在连接双 Root 快照引擎…")
        if (!profileBound) {
            runCatching {
                RootService.bind(
                    Intent(this, BaiZeProfileRootService::class.java)
                        .addCategory(RootService.CATEGORY_DAEMON_MODE),
                    profileConnection
                )
                profileBound = true
            }.onFailure {
                dashboardState.value = dashboardState.value.copy(serviceText = "分类引擎启动失败：${it.message.orEmpty()}")
            }
        }
        if (!cacheBound) {
            runCatching {
                RootService.bind(
                    Intent(this, BaiZeRootService::class.java)
                        .addCategory(RootService.CATEGORY_DAEMON_MODE),
                    cacheConnection
                )
                cacheBound = true
            }.onFailure {
                dashboardState.value = dashboardState.value.copy(serviceText = "缓存引擎启动失败：${it.message.orEmpty()}")
            }
        }
    }

    private fun reconnectService() {
        if (profileBound) runCatching { RootService.unbind(profileConnection) }
        if (cacheBound) runCatching { RootService.unbind(cacheConnection) }
        rootService = null
        cacheService = null
        profileBound = false
        cacheBound = false
        dashboardState.value = dashboardState.value.copy(
            connected = false,
            ready = false,
            running = false,
            serviceText = "正在重新连接 Root 清理服务…"
        )
        connectPrimaryService()
        toast("正在重新连接 Root 清理服务")
    }

    private fun updateConnectionState() {
        val primaryConnected = rootService != null
        val scanReady = dashboardState.value.scanCompleted && hasUsableScanSnapshots()
        dashboardState.value = dashboardState.value.copy(
            connected = primaryConnected,
            ready = if (primaryConnected) dashboardState.value.ready else false,
            serviceText = when {
                primaryConnected -> "Root 清理服务已连接，正在校验模块组件…"
                scanReady -> "扫描快照已就绪，清理时会自动恢复 Root 服务"
                profileBound -> "正在连接 Root 清理服务…"
                else -> "Root 清理服务已断开，正在自动恢复…"
            }
        )
    }

    private fun runPendingActionsIfReady() {
        if (dashboardState.value.running) return

        if (pendingSnapshotClean) {
            val needsCache = cacheSnapshotId.isNotBlank() && cacheSnapshotCount > 0
            val needsProfile = safeSnapshotId.isNotBlank() && safeSnapshotCount > 0
            val cacheReady = !needsCache || cacheService != null
            val profileReady = !needsProfile || rootService != null
            if (cacheReady && profileReady) {
                pendingSnapshotClean = false
                cleanNativeSnapshots()
                return
            }
        }

        val cleanAfterScan = pendingScanAfterConnect
        if (cleanAfterScan != null && rootService != null && cacheService != null) {
            pendingScanAfterConnect = null
            runNativeScan(cleanAfterScan)
            return
        }

        if (pendingSmartClean && rootService != null) {
            pendingSmartClean = false
            runSmartClean()
            return
        }

        val mode = pendingModuleTask
        if (mode != null && rootService != null) {
            pendingModuleTask = null
            runModuleUtilityTask(requireNotNull(rootService), mode)
        }
    }

    private fun scheduleServiceRecovery(requireCache: Boolean) {
        lifecycleScope.launch {
            delay(350)
            if (isFinishing || isDestroyed) return@launch
            if (requireCache) connectServices() else connectPrimaryService()
        }
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
            val status = when {
                !root -> "服务已连接，但未取得完整 Root"
                !module -> "Root 已连接 · 未检测到白泽模块"
                !cleaner -> "模块已连接 · 清理引擎缺失"
                !scheduler -> "清理引擎已连接 · 调度器缺失"
                !rules -> "自动清理可用 · 深度规则库缺失"
                else -> "Root、完整清理引擎、定时任务与规则库均已就绪"
            }
            dashboardState.value = dashboardState.value.copy(
                connected = true,
                ready = ready,
                serviceText = status,
                device = Build.MODEL,
                android = "Android ${Build.VERSION.RELEASE}"
            )
        }
    }

    private fun updateRawLogFromResponse(json: JSONObject) {
        val output = json.optString("output").trimEnd()
        if (output.isBlank()) return
        dashboardState.value = dashboardState.value.copy(
            rawLogName = json.optString("logName").ifBlank { "本次模块任务.log" },
            rawLog = output.takeLast(RAW_LOG_LIMIT)
        )
    }

    private fun refreshRawLog() {
        val service = rootService ?: return
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { JSONObject(service.getRawLog(RAW_LOG_LIMIT)) }.getOrNull()
            } ?: return@launch
            if (!json.optBoolean("success", true)) return@launch
            dashboardState.value = dashboardState.value.copy(
                rawLogName = json.optString("name"),
                rawLog = json.optString("text").takeLast(RAW_LOG_LIMIT)
            )
        }
    }

    private fun confirmClearRawLogs() {
        val service = rootService ?: return toast("Root 服务尚未连接")
        AlertDialog.Builder(this)
            .setTitle("清空原始日志？")
            .setMessage("只删除 /data/adb/baize-v2/logs 中的模块输出，不影响清理历史和累计统计。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    val json = withContext(Dispatchers.IO) {
                        runCatching { JSONObject(service.clearRawLogs()) }.getOrNull()
                    }
                    val success = json?.optBoolean("success") == true
                    if (success) {
                        dashboardState.value = dashboardState.value.copy(rawLogName = "", rawLog = "")
                    }
                    toast(if (success) "原始日志已清空" else "原始日志清空失败")
                }
            }
            .show()
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

    private fun showTaskBusy(message: String = "当前已有扫描或清理任务正在运行，请先停止或等待完成") {
        dashboardState.value = dashboardState.value.copy(taskPhase = message)
        toast(message)
    }

    private fun runApkScan() {
        if (dashboardState.value.running) {
            showTaskBusy()
            return
        }
        val service = rootService
        if (service == null) {
            pendingModuleTask = "apk-scan"
            dashboardState.value = dashboardState.value.copy(
                connected = false,
                ready = false,
                taskPhase = "正在连接 Root 服务，连接后自动扫描安装包"
            )
            connectPrimaryService()
            return
        }
        runModuleUtilityTask(service, "apk-scan")
    }

    private fun runModuleUtilityTask(service: IProfileRootService, mode: String) {
        if (dashboardState.value.running) {
            showTaskBusy()
            return
        }
        dashboardState.value = dashboardState.value.copy(
            running = true,
            taskPhase = if (mode == "apk-scan") "正在扫描 APK 安装包…" else "正在执行清理任务…"
        )
        startNativePoll()
        lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) {
                runCatching { JSONObject(service.runModuleTask(mode)) }
            }
            pollJob?.cancel()
            if (response.isFailure) {
                rootService = null
                profileBound = false
                dashboardState.value = dashboardState.value.copy(
                    connected = false,
                    ready = false,
                    running = false,
                    serviceText = "Root 服务已断开，正在重新连接…",
                    taskPhase = "安装包扫描失败：${response.exceptionOrNull()?.message ?: "Root 服务异常"}"
                )
                connectPrimaryService()
                return@launch
            }
            val json = response.getOrThrow()
            if (json.optString("error") == "busy" || json.optInt("exitCode") == 3) {
                val message = json.optString("message", "当前已有扫描或清理任务正在运行")
                dashboardState.value = dashboardState.value.copy(running = false, taskPhase = message)
                toast(message)
                return@launch
            }
            updateRawLogFromResponse(json)
            val latest = json.optJSONObject("latest") ?: JSONObject()
            val success = json.optBoolean("success")
            val cancelled = json.optBoolean("cancelled")
            val junk = parseGeneralJunk(json.optJSONArray("otherDetails"))
            val result = latest.optString("result").ifBlank {
                when {
                    cancelled -> "安装包扫描已停止"
                    success && junk.isEmpty() -> "没有发现超过保留期的安装包"
                    success -> "安装包扫描完成，发现 ${junk.sumOf { it.files }} 项"
                    else -> json.optString("message", "安装包扫描失败")
                }
            }
            val taskTime = markTaskTime()
            val protected = protectedFromModule(emptyList(), junk)
            saveProtectedItems(protected)
            dashboardState.value = dashboardState.value.copy(
                running = false,
                recentJunk = junk,
                lastTaskTime = taskTime,
                protectedItems = protected,
                taskPhase = result
            )
            refreshHistory()
            refreshModuleState()
            readServiceStatus()
        }
    }

    private fun runSmartClean() {
        if (dashboardState.value.running) {
            showTaskBusy()
            return
        }
        if (schedulerState.value.notifyOnComplete) requestNotificationPermission()
        val service = rootService
        if (service == null) {
            pendingSmartClean = true
            dashboardState.value = dashboardState.value.copy(
                connected = false,
                ready = false,
                taskPhase = "正在连接 Root 清理服务，连接成功后继续清理"
            )
            connectPrimaryService()
            return
        }
        runModuleClean(service)
    }

    private fun runModuleClean(service: IProfileRootService) {
        if (dashboardState.value.running) {
            showTaskBusy()
            return
        }
        clearSnapshotHandles()
        dashboardState.value = dashboardState.value.copy(
            running = true,
            scanCompleted = false,
            taskPhase = "正在把清理任务交给独立 Root Worker…"
        )
        startNativePoll()
        lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) {
                runCatching { JSONObject(service.runModuleTask("clean")) }
            }
            pollJob?.cancel()
            if (response.isFailure) {
                rootService = null
                profileBound = false
                dashboardState.value = dashboardState.value.copy(
                    connected = false,
                    ready = false,
                    running = false,
                    serviceText = "Root 清理服务已断开，正在重新连接…",
                    taskPhase = "清理启动失败：${response.exceptionOrNull()?.message ?: "Root 服务异常"}"
                )
                connectPrimaryService()
                return@launch
            }

            val json = response.getOrThrow()
            if (json.optString("error") == "busy" || json.optInt("exitCode") == 3) {
                val message = json.optString("message", "当前已有扫描或清理任务正在运行")
                dashboardState.value = dashboardState.value.copy(running = false, taskPhase = message)
                toast(message)
                return@launch
            }
            if (json.optBoolean("accepted")) {
                dashboardState.value = dashboardState.value.copy(
                    running = true,
                    scanCompleted = false,
                    serviceText = "独立 Root Worker 已接管任务，关闭 App 也会继续",
                    taskPhase = json.optString("message", "清理任务已在后台启动") + "\n可返回桌面或划掉最近任务"
                )
                startRecoveredTaskPoll()
                return@launch
            }
            updateRawLogFromResponse(json)
            val latest = json.optJSONObject("latest") ?: JSONObject()
            val success = json.optBoolean("success")
            val cancelled = json.optBoolean("cancelled")
            val bytes = latest.optLong("bytes", 0L).coerceAtLeast(0L)
            val files = latest.optLong("files", 0L).coerceAtLeast(0L)
            val emptyFiles = latest.optLong("empty_files", 0L).coerceAtLeast(0L)
            val emptyDirs = latest.optLong("empty_dirs", 0L).coerceAtLeast(0L)
            val fragments = latest.optLong("fragment_files", 0L).coerceAtLeast(0L)
            val errors = latest.optLong("errors", if (success) 0L else 1L).coerceAtLeast(0L)
            val elapsed = latest.optLong("elapsed", json.optLong("elapsedMs", 0L) / 1000L).coerceAtLeast(0L)
            val resultLine = latest.optString("result").ifBlank {
                json.optString("message", if (success) "清理完成" else "清理失败")
            }
            val appDetails = parseAppDetails(json.optJSONArray("appDetails"))
            val otherDetails = parseGeneralJunk(json.optJSONArray("otherDetails"))
            val detailLine = "文件 $files · 空文件 $emptyFiles · 空目录 $emptyDirs · 碎片 $fragments · 异常 $errors · ${formatElapsed(elapsed)}"
            val title = when {
                cancelled -> "白泽清理已停止"
                success -> "白泽智能清理完成"
                else -> "白泽智能清理失败"
            }

            val taskTime = markTaskTime()
            val protected = protectedFromModule(appDetails, otherDetails)
            saveProtectedItems(protected)
            dashboardState.value = dashboardState.value.copy(
                running = false,
                lastReleased = bytes,
                recentApps = appDetails,
                recentJunk = otherDetails,
                lastTaskTime = taskTime,
                protectedItems = protected,
                taskPhase = "$resultLine\n$detailLine"
            )
            preferences.edit()
                .putLong("last_clean_bytes", bytes)
                .putString("last_report_text", "$resultLine\n$detailLine")
                .apply()
            notifyCleanResult(title, resultLine, detailLine, bytes)
            refreshHistory()
            refreshModuleState()
            updateStorage()
            readServiceStatus()
        }
    }

    private fun runNativeScan(cleanAfterScan: Boolean) {
        if (schedulerState.value.notifyOnComplete) requestNotificationPermission()
        val cache = cacheService
        val profiles = rootService
        if (cache == null || profiles == null) {
            pendingScanAfterConnect = cleanAfterScan
            dashboardState.value = dashboardState.value.copy(
                connected = rootService != null,
                ready = false,
                taskPhase = "正在连接扫描引擎，连接后自动继续垃圾扫描"
            )
            connectServices()
            return
        }
        if (dashboardState.value.running) {
            showTaskBusy()
            return
        }
        clearSnapshotHandles()
        val started = SystemClock.elapsedRealtime()
        dashboardState.value = dashboardState.value.copy(
            running = true,
            scanCompleted = false,
            taskPhase = "正在并行发现应用缓存与安全项目…"
        )
        startNativePoll()
        lifecycleScope.launch {
            val pair = runCatching {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val whitelist = JSONArray(packageWhitelist().toList()).toString()
                        val cacheJob = async { JSONObject(cache.scanCandidates(whitelist)) }
                        val safeJob = async { JSONObject(profiles.scanProfile("safe", optionsJson())) }
                        cacheJob.await() to safeJob.await()
                    }
                }
            }
            pollJob?.cancel()
            if (pair.isFailure) {
                dashboardState.value = dashboardState.value.copy(
                    running = false,
                    taskPhase = "安全扫描失败：${pair.exceptionOrNull()?.message ?: "Root 服务异常"}"
                )
                return@launch
            }

            val (cacheJson, safeJson) = pair.getOrThrow()
            val busy = listOf(cacheJson, safeJson).firstOrNull {
                it.optString("error") == "busy" || it.optInt("exitCode") == 3
            }
            if (busy != null) {
                val message = busy.optString("message", "当前已有扫描或清理任务正在运行")
                dashboardState.value = dashboardState.value.copy(running = false, taskPhase = message)
                toast(message)
                return@launch
            }
            val cacheOk = !cacheJson.has("error") && !cacheJson.optBoolean("cancelled")
            val safeOk = safeJson.optBoolean("success") && !safeJson.optBoolean("cancelled")
            if (cacheOk) {
                cacheSnapshotId = cacheJson.optString("snapshotId")
                cacheSnapshotCount = (cacheJson.optInt("totalCandidates") - cacheJson.optInt("whitelisted")).coerceAtLeast(0)
            }
            if (safeOk) {
                safeSnapshotId = safeJson.optString("snapshotId")
                safeSnapshotCount = (safeJson.optInt("low") + safeJson.optInt("medium")).coerceAtLeast(0)
            }
            val total = cacheSnapshotCount + safeSnapshotCount
            val knownBytes = safeJson.optLong("knownBytes", 0L).coerceAtLeast(0L)
            val emptyFiles = safeJson.optLong("emptyFiles", 0L).coerceAtLeast(0L)
            val emptyDirs = safeJson.optLong("emptyDirs", 0L).coerceAtLeast(0L)
            val fragments = safeJson.optLong("fragmentFiles", 0L).coerceAtLeast(0L)
            val failures = listOf(cacheOk, safeOk).count { !it }.toLong()
            val cancelled = cacheJson.optBoolean("cancelled") || safeJson.optBoolean("cancelled")
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
            val successfulScan = (cacheOk || safeOk) && !cancelled
            if (successfulScan && total > 0) {
                snapshotExpiresAtElapsed = SystemClock.elapsedRealtime() + SNAPSHOT_TTL_MS
            } else {
                clearSnapshotHandles()
            }
            val taskTime = markTaskTime()
            dashboardState.value = dashboardState.value.copy(
                running = false,
                scanCompleted = successfulScan,
                lastTaskTime = taskTime,
                scanBytes = knownBytes,
                scanFiles = total.toLong(),
                scanEmptyFiles = emptyFiles,
                scanEmptyDirs = emptyDirs,
                scanFragments = fragments,
                scanErrors = failures,
                scanElapsed = elapsed / 1000L,
                taskPhase = when {
                    cancelled -> "安全扫描已停止"
                    !successfulScan -> "安全扫描失败：${safeJson.optString("message", cacheJson.optString("message", "引擎没有返回有效快照"))}"
                    total == 0 -> "扫描完成，没有发现可安全清理的项目"
                    else -> "扫描完成，发现 $total 项；快照 30 分钟内有效"
                }
            )
            if (!cleanAfterScan) notifyScanResult(successfulScan, cancelled, total, knownBytes, emptyFiles, emptyDirs, fragments, elapsed)
            if (cleanAfterScan && successfulScan && total > 0) {
                cleanNativeSnapshots()
            } else if (cleanAfterScan && successfulScan) {
                notifyCleanResult("白泽智能清理完成", "没有发现可安全清理的项目", "扫描一次完成 · 未执行删除", 0L)
            }
        }
    }

    private fun cleanNativeSnapshots() {
        if (dashboardState.value.running) {
            showTaskBusy()
            return
        }
        if (!hasUsableScanSnapshots()) {
            clearSnapshotHandles()
            dashboardState.value = dashboardState.value.copy(
                scanCompleted = false,
                taskPhase = "没有可用扫描快照，请先执行安全扫描"
            )
            toast("扫描结果已失效，请重新扫描")
            return
        }

        val needsCacheEngine = cacheSnapshotId.isNotBlank() && cacheSnapshotCount > 0
        val needsProfileEngine = safeSnapshotId.isNotBlank() && safeSnapshotCount > 0
        val cacheEngine = cacheService
        val profileEngine = rootService
        if ((needsCacheEngine && cacheEngine == null) || (needsProfileEngine && profileEngine == null)) {
            pendingSnapshotClean = true
            dashboardState.value = dashboardState.value.copy(
                connected = rootService != null,
                ready = false,
                serviceText = "扫描快照仍有效，正在重连缺失的 Root 引擎…",
                taskPhase = "等待引擎重连后继续按扫描结果清理"
            )
            connectServices()
            toast("扫描快照仍有效，正在重连缺失引擎")
            return
        }

        if (schedulerState.value.notifyOnComplete) requestNotificationPermission()
        val started = SystemClock.elapsedRealtime()
        dashboardState.value = dashboardState.value.copy(running = true, taskPhase = "正在复核并清理刚才的扫描快照…")
        startNativePoll()
        lifecycleScope.launch {
            var deletedBytes = 0L
            var deletedFiles = 0L
            var deletedDirectories = 0L
            var emptyFiles = 0L
            var emptyDirs = 0L
            var fragments = 0L
            var cleanedCandidates = 0
            var failures = 0
            var cancelled = false
            var stale = false
            val protectedItems = ArrayList<ProtectedUiItem>()
            val selection = JSONObject().put("__all_safe__", true).toString()
            val whitelist = JSONArray(packageWhitelist().toList()).toString()

            suspend fun consume(result: JSONObject, profileResult: Boolean) {
                if (result.has("error")) {
                    failures += 1
                    stale = stale || result.optString("error").contains("snapshot")
                    return
                }
                deletedBytes += result.optLong("deletedBytes", 0L).coerceAtLeast(0L)
                deletedFiles += result.optLong("deletedFiles", 0L).coerceAtLeast(0L)
                deletedDirectories += result.optLong("deletedDirectories", 0L).coerceAtLeast(0L)
                cleanedCandidates += result.optInt("cleanedCandidates", 0).coerceAtLeast(0)
                failures += result.optInt("failures", 0).coerceAtLeast(0)
                cancelled = cancelled || result.optBoolean("cancelled")
                if (profileResult) {
                    val details = result.optJSONArray("details") ?: JSONArray()
                    for (index in 0 until details.length()) {
                        val item = details.optJSONObject(index) ?: continue
                        val action = item.optString("action")
                        if (action == "protected" || action == "partial") {
                            val reason = item.optString("reason").ifBlank {
                                if (action == "partial") "部分内容未删除" else "安全策略保护"
                            }
                            protectedItems += ProtectedUiItem(
                                id = item.optString("id").ifBlank { item.optString("path") },
                                category = item.optString("category").ifBlank { "受保护项目" },
                                path = item.optString("path"),
                                reason = reason,
                                risk = item.optString("risk", "high"),
                                selectable = reason == "高风险清理未启用" ||
                                    reason == "仍有受保护或未删除项目" ||
                                    reason.contains("大小限制")
                            )
                        }
                        when (item.optString("profile")) {
                            "empty" -> {
                                emptyFiles += item.optLong("files", 0L).coerceAtLeast(0L)
                                emptyDirs += item.optLong("directories", 0L).coerceAtLeast(0L)
                            }
                            "fragments" -> fragments += item.optLong("files", 0L).coerceAtLeast(0L)
                        }
                    }
                }
            }

            try {
                if (needsCacheEngine) {
                    dashboardState.value = dashboardState.value.copy(taskPhase = "正在清理应用缓存快照…")
                    val result = withContext(Dispatchers.IO) {
                        JSONObject(requireNotNull(cacheEngine).cleanSelected(cacheSnapshotId, selection, whitelist))
                    }
                    consume(result, profileResult = false)
                }
                if (!cancelled && needsProfileEngine) {
                    dashboardState.value = dashboardState.value.copy(taskPhase = "正在清理安全项目快照…")
                    val result = withContext(Dispatchers.IO) {
                        JSONObject(requireNotNull(profileEngine).cleanProfileSelected(safeSnapshotId, selection, optionsJson()))
                    }
                    consume(result, profileResult = true)
                }
            } catch (error: Throwable) {
                failures += 1
                dashboardState.value = dashboardState.value.copy(taskPhase = "快照清理异常：${error.message ?: error.javaClass.simpleName}")
            }

            pollJob?.cancel()
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
            val title = when {
                cancelled -> "白泽快照清理已停止"
                stale -> "部分扫描结果已过期"
                failures > 0 -> "白泽快照清理完成，但有异常"
                else -> "白泽快照清理完成"
            }
            val resultLine = when {
                stale -> "扫描快照已过期，没有重新扫描；请手动再次扫描"
                cancelled -> "任务已安全停止，已释放 ${formatBytes(deletedBytes)}"
                else -> "释放 ${formatBytes(deletedBytes)} · 处理 $cleanedCandidates 项"
            }
            val detailLine = "文件 $deletedFiles · 目录 $deletedDirectories · 空文件 $emptyFiles · 空目录 $emptyDirs · 碎片 $fragments · 异常 $failures · ${formatElapsed(elapsed / 1000L)}"
            val taskTime = markTaskTime()
            saveProtectedItems(protectedItems)
            dashboardState.value = dashboardState.value.copy(
                running = false,
                scanCompleted = false,
                lastReleased = deletedBytes,
                lastTaskTime = taskTime,
                protectedItems = protectedItems,
                taskPhase = "$resultLine\n$detailLine"
            )
            preferences.edit()
                .putLong("last_clean_bytes", deletedBytes)
                .putString("last_report_text", "$resultLine\n$detailLine")
                .apply()
            val recorder = profileEngine ?: rootService
            if (recorder != null) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        recorder.recordNativeTask(
                            JSONObject()
                                .put("mode", "snapshot-clean")
                                .put("success", !cancelled && failures == 0)
                                .put("cancelled", cancelled)
                                .put("bytes", deletedBytes)
                                .put("files", deletedFiles)
                                .put("emptyFiles", emptyFiles)
                                .put("emptyDirs", emptyDirs)
                                .put("fragments", fragments)
                                .put("errors", failures)
                                .put("elapsedSeconds", elapsed / 1000L)
                                .put("result", resultLine)
                                .put(
                                    "categorySummary",
                                    buildList {
                                        if (deletedFiles > 0) add("扫描快照|$deletedBytes|$deletedFiles")
                                        if (emptyFiles > 0) add("空文件|0|$emptyFiles")
                                        if (emptyDirs > 0) add("空目录|0|$emptyDirs")
                                        if (fragments > 0) add("残留碎片|0|$fragments")
                                    }.joinToString(";")
                                )
                                .toString()
                        )
                    }
                }
            }
            notifyCleanResult(title, resultLine, detailLine, deletedBytes)
            clearSnapshotHandles()
            refreshHistory()
            refreshModuleState()
            updateStorage()
        }
    }

    private fun startNativePoll() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive && dashboardState.value.running) {
                val state = withContext(Dispatchers.IO) {
                    val profile = runCatching { rootService?.getTaskState()?.let { JSONObject(it) } }.getOrNull()
                    val cache = runCatching { cacheService?.getTaskState()?.let { JSONObject(it) } }.getOrNull()
                    listOfNotNull(profile, cache).firstOrNull { it.optBoolean("running") }
                }
                if (state != null) renderTaskState(state)
                delay(350)
            }
        }
    }

    private fun notifyScanResult(
        success: Boolean,
        cancelled: Boolean,
        total: Int,
        knownBytes: Long,
        emptyFiles: Long,
        emptyDirs: Long,
        fragments: Long,
        elapsed: Long
    ) {
        val config = schedulerState.value
        if (!config.notifyOnComplete || (success && total == 0 && !config.notifyZero)) return
        NativeNotifier.showTaskResult(
            this,
            when {
                cancelled -> "白泽安全扫描已停止"
                success -> "白泽安全扫描完成"
                else -> "白泽安全扫描失败"
            },
            if (knownBytes > 0) "发现 $total 项 · 已知至少 ${formatBytes(knownBytes)}" else "发现 $total 项可清理内容",
            "空文件 $emptyFiles · 空目录 $emptyDirs · 碎片 $fragments · ${formatElapsed(elapsed / 1000L)}"
        )
    }

    private fun notifyCleanResult(title: String, summary: String, detail: String, bytes: Long) {
        val config = schedulerState.value
        if (!config.notifyOnComplete || (bytes == 0L && !config.notifyZero && !summary.contains("过期"))) return
        NativeNotifier.showTaskResult(this, title, summary, detail)
    }

    private fun hasUsableScanSnapshots(): Boolean {
        if (snapshotExpiresAtElapsed <= SystemClock.elapsedRealtime()) return false
        return (cacheSnapshotId.isNotBlank() && cacheSnapshotCount > 0) ||
            (safeSnapshotId.isNotBlank() && safeSnapshotCount > 0)
    }

    private fun clearSnapshotHandles() {
        cacheSnapshotId = ""
        safeSnapshotId = ""
        cacheSnapshotCount = 0
        safeSnapshotCount = 0
        snapshotExpiresAtElapsed = 0L
    }

    private fun clearScanResult() {
        pendingSnapshotClean = false
        clearSnapshotHandles()
        dashboardState.value = dashboardState.value.copy(scanCompleted = false)
    }

    private fun markTaskTime(): String {
        val value = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        preferences.edit().putString("last_task_time", value).apply()
        return value
    }

    private fun protectedFromModule(
        apps: List<AppJunkUiItem>,
        other: List<GeneralJunkUiItem>
    ): List<ProtectedUiItem> = buildList {
        apps.forEach { app ->
            app.categories.filter { it.errors > 0 && it.samplePath.isNotBlank() }.forEach { category ->
                add(
                    ProtectedUiItem(
                        id = "${app.packageName}:${category.samplePath}",
                        category = "${app.label} · ${category.name}",
                        path = category.samplePath,
                        reason = "模块报告 ${category.errors} 个异常或受保护项目",
                        risk = "high",
                        selectable = true
                    )
                )
            }
        }
        other.filter { it.errors > 0 && it.samplePath.isNotBlank() }.forEach { item ->
            add(
                ProtectedUiItem(
                    id = "${item.name}:${item.samplePath}",
                    category = item.name,
                    path = item.samplePath,
                    reason = "模块报告 ${item.errors} 个异常或受保护项目",
                    risk = "high",
                    selectable = true
                )
            )
        }
    }.distinctBy { "${it.path}|${it.reason}" }.take(120)

    private fun saveProtectedItems(items: List<ProtectedUiItem>) {
        val array = JSONArray()
        items.take(120).forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("category", item.category)
                    .put("path", item.path)
                    .put("reason", item.reason)
                    .put("risk", item.risk)
                    .put("selectable", item.selectable)
            )
        }
        preferences.edit().putString("last_protected_items", array.toString()).apply()
    }

    private fun loadProtectedItems(): List<ProtectedUiItem> = runCatching {
        val array = JSONArray(preferences.getString("last_protected_items", "[]").orEmpty())
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val path = item.optString("path").trim()
                if (path.isBlank()) continue
                add(
                    ProtectedUiItem(
                        id = item.optString("id").ifBlank { path },
                        category = item.optString("category", "受保护项目"),
                        path = path,
                        reason = item.optString("reason", "安全策略保护"),
                        risk = item.optString("risk", "high"),
                        selectable = item.optBoolean("selectable", false)
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun packageWhitelist(): Set<String> =
        preferences.getStringSet("package_whitelist", emptySet()).orEmpty()

    private fun optionsJson(): String {
        val paths = preferences.getStringSet("path_whitelist", emptySet()).orEmpty()
        val maxMb = schedulerState.value.maxFileMb.coerceIn(16, 2048)
        return JSONObject()
            .put("whitelistPackages", JSONArray(packageWhitelist().toList()))
            .put("whitelistPaths", JSONArray(paths.toList()))
            .put("maxFileBytes", maxMb * 1024L * 1024L)
            .put("fragmentDays", preferences.getInt("fragment_days", 7).coerceIn(0, 365))
            .put("allowHighRisk", false)
            .toString()
    }

    private fun parseAppDetails(array: JSONArray?): List<AppJunkUiItem> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val packageName = item.optString("packageName").trim()
            if (!looksLikePackageName(packageName)) continue
            val categoryArray = item.optJSONArray("categories")
            val categories = buildList {
                if (categoryArray != null) for (categoryIndex in 0 until categoryArray.length()) {
                    val category = categoryArray.optJSONObject(categoryIndex) ?: continue
                    add(
                        AppJunkCategoryUiItem(
                            name = category.optString("name").ifBlank { "应用缓存" },
                            files = category.optLong("files", 0L).coerceAtLeast(0L),
                            bytes = category.optLong("bytes", 0L).coerceAtLeast(0L),
                            errors = category.optLong("errors", 0L).coerceAtLeast(0L),
                            samplePath = category.optString("samplePath").trim()
                        )
                    )
                }
            }.sortedByDescending { it.bytes }
            add(
                AppJunkUiItem(
                    packageName = packageName,
                    label = appLabel(packageName),
                    category = item.optString("category").ifBlank { "应用缓存" },
                    files = item.optLong("files", 0L).coerceAtLeast(0L),
                    bytes = item.optLong("bytes", 0L).coerceAtLeast(0L),
                    errors = item.optLong("errors", 0L).coerceAtLeast(0L),
                    categories = categories
                )
            )
        }
    }.sortedWith(compareByDescending<AppJunkUiItem> { it.bytes }.thenByDescending { it.files })

    private fun parseGeneralJunk(array: JSONArray?): List<GeneralJunkUiItem> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            if (name.isBlank()) continue
            add(
                GeneralJunkUiItem(
                    name = name,
                    files = item.optLong("files", 0L).coerceAtLeast(0L),
                    bytes = item.optLong("bytes", 0L).coerceAtLeast(0L),
                    errors = item.optLong("errors", 0L).coerceAtLeast(0L),
                    samplePath = item.optString("samplePath").trim()
                )
            )
        }
    }.sortedWith(compareByDescending<GeneralJunkUiItem> { it.bytes }.thenByDescending { it.files })

    private fun looksLikePackageName(value: String): Boolean =
        value.length in 3..180 && value.contains('.') && value.none { it == '/' || it.isWhitespace() }

    @Suppress("DEPRECATION")
    private fun appLabel(packageName: String): String = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            packageManager.getApplicationInfo(packageName, 0)
        }
        packageManager.getApplicationLabel(info).toString().ifBlank { packageName }
    }.getOrDefault(packageName)

    private fun renderTaskState(json: JSONObject) {
        if (!json.optBoolean("running")) return
        val current = json.optInt("progress_current", json.optInt("current", 0))
        val total = json.optInt("progress_total", json.optInt("total", 0))
        val target = json.optString("current_path", json.optString("currentPath")).trim()
        val targetText = when {
            looksLikePackageName(target) -> "${appLabel(target)} · $target"
            target.isNotBlank() -> target.takeLast(72)
            else -> ""
        }
        val text = buildString {
            append(json.optString("phase", "任务执行中"))
            if (total > 0) append(" · $current/$total")
            if (targetText.isNotBlank()) append("\n").append(targetText)
            if (json.optBoolean("cancelRequested")) append("\n正在停止…")
            append("\n可切到后台，Root 会继续执行")
        }
        dashboardState.value = dashboardState.value.copy(
            running = true,
            scanCompleted = false,
            taskPhase = text
        )
    }

    private fun stopTask() {
        rootService?.cancelCurrentTask()
        cacheService?.cancelCurrentTask()
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

    private fun resetScanPerformance() {
        val service = rootService ?: return toast("Root 服务尚未连接")
        lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) {
                runCatching { JSONObject(service.resetScanWorkerProfile()) }
            }
            val json = response.getOrNull()
            val success = json?.optBoolean("success") == true
            toast(
                if (success) json?.optString("message").orEmpty().ifBlank { "性能基准已清除" }
                else "重置失败：${json?.optString("error").orEmpty().ifBlank { response.exceptionOrNull()?.message ?: "未知错误" }}"
            )
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
            val supervisor = json.optJSONObject("supervisor") ?: JSONObject()
            val appInstall = json.optJSONObject("appInstall") ?: JSONObject()
            val performance = json.optJSONObject("scanPerformance") ?: JSONObject()
            val appDetails = parseAppDetails(json.optJSONArray("appDetails"))
            val otherDetails = parseGeneralJunk(json.optJSONArray("otherDetails"))
            val latestMode = latest.optString("mode")
            val latestReleased = if (latestMode.endsWith("scan") || latestMode == "scan") {
                preferences.getLong("last_clean_bytes", dashboardState.value.lastReleased)
            } else {
                latest.optLong("bytes", preferences.getLong("last_clean_bytes", 0L)).coerceAtLeast(0L)
            }
            val latestTaskText = buildString {
                val result = latest.optString("result").trim()
                if (result.isNotBlank()) append(result)
                val files = latest.optLong("files", latest.optLong("regular_files", 0L)).coerceAtLeast(0L)
                val errors = latest.optLong("errors", 0L).coerceAtLeast(0L)
                val elapsed = latest.optLong("elapsed", 0L).coerceAtLeast(0L)
                if (files > 0L || errors > 0L || elapsed > 0L) {
                    if (isNotEmpty()) append("\n")
                    append("文件 ").append(files)
                    if (errors > 0L) append(" · 异常 ").append(errors)
                    if (elapsed > 0L) append(" · ").append(formatElapsed(elapsed))
                }
                val slowestSeconds = latest.optLong("deep_slowest_seconds", 0L).coerceAtLeast(0L)
                val slowestPath = latest.optString("deep_slowest_path").trim()
                if (slowestSeconds > 0L && slowestPath.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append("最慢目录 ").append(slowestSeconds).append("秒 · ").append(slowestPath.takeLast(72))
                }
            }.ifBlank { dashboardState.value.taskPhase }
            dashboardState.value = dashboardState.value.copy(
                lastReleased = latestReleased,
                recentApps = if (appDetails.isNotEmpty()) appDetails else dashboardState.value.recentApps,
                recentJunk = if (otherDetails.isNotEmpty()) otherDetails else dashboardState.value.recentJunk,
                taskPhase = if (dashboardState.value.running) dashboardState.value.taskPhase else latestTaskText,
                scanPerformance = ScanPerformanceUiState(
                    available = performance.optBoolean("available", false),
                    workerPolicy = performance.optString("workerPolicy", "auto"),
                    workerReason = performance.optString("workerReason", "not_measured"),
                    actualWorkers = performance.optInt("actualWorkers", 1).coerceIn(1, 2),
                    recommendedWorkers = performance.optInt("recommendedWorkers", 1).coerceIn(1, 2),
                    parallelGainPercent = performance.optInt("parallelGainPercent", 0),
                    serialRate = performance.optLong("serialRate", 0L).coerceAtLeast(0L),
                    parallelRate = performance.optLong("parallelRate", 0L).coerceAtLeast(0L),
                    successfulRuns = performance.optInt("successfulRuns", 0).coerceAtLeast(0),
                    nextProbeRun = performance.optInt("nextProbeRun", 0).coerceAtLeast(0),
                    parallelBlockedUntil = performance.optLong("parallelBlockedUntil", 0L).coerceAtLeast(0L)
                ),
                schedulerText = when {
                    appInstall.optString("status") == "signature_mismatch" -> "App 与模块签名不一致，模块定时仍可运行"
                    supervisor.optString("status") == "recovering" -> "调度器正在自动恢复：${supervisor.optString("reason")}"
                    supervisor.optString("status") == "failed" -> "调度器守护异常：${supervisor.optString("reason")}"
                    else -> when (scheduler.optString("state", "waiting")) {
                    "running" -> "定时任务正在执行"
                    "completed" -> "最近定时任务已完成"
                    "failed" -> "定时任务失败：${scheduler.optString("reason")}"
                    "disabled" -> "自动清理已关闭"
                    else -> scheduler.optString("reason", "等待调度器首次轮询")
                    }
                }
            )
        }
    }

    private fun refreshHistory() {
        val service = rootService ?: return
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { JSONObject(service.getTaskHistoryPage(0, 30)) }.getOrNull()
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
                            cleaned = item.optBoolean("cleaned"),
                            categories = parseHistoryCategories(item.optJSONArray("categoryDetails")),
                            apps = parseHistoryApps(item.optJSONArray("appDetails"))
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

    private fun parseHistoryCategories(array: JSONArray?): List<HistoryCategoryUiItem> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            if (name.isBlank()) continue
            add(HistoryCategoryUiItem(name, item.optLong("bytes", 0L).coerceAtLeast(0L), item.optLong("files", 0L).coerceAtLeast(0L)))
        }
    }.sortedByDescending { it.bytes }

    private fun parseHistoryApps(array: JSONArray?): List<HistoryAppUiItem> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val packageName = item.optString("packageName").trim()
            if (!looksLikePackageName(packageName)) continue
            add(
                HistoryAppUiItem(
                    packageName = packageName,
                    label = appLabel(packageName),
                    category = item.optString("category").trim(),
                    bytes = item.optLong("bytes", 0L).coerceAtLeast(0L),
                    files = item.optLong("files", 0L).coerceAtLeast(0L)
                )
            )
        }
    }.sortedByDescending { it.bytes }

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
                    if (success) dashboardState.value = dashboardState.value.copy(history = emptyList(), recentApps = emptyList())
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
        "scan" -> "垃圾扫描"
        "clean" -> "智能自动清理"
        "snapshot-clean" -> "扫描快照清理"
        "smart-clean" -> "原生智能清理"
        "cache-clean" -> "应用缓存清理"
        "empty-clean" -> "空文件与空目录"
        "rules-clean" -> "规则垃圾与日志"
        "fragment-scan" -> "残留碎片扫描"
        "fragment-clean" -> "残留碎片清理"
        "deep-scan" -> "完整深度扫描"
        "deep-clean" -> "完整深度清理"
        "corpse-scan" -> "卸载残留扫描"
        "corpse-clean" -> "卸载残留清理"
        "apk-scan" -> "安装包扫描"
        "apk-clean" -> "安装包清理"
        "profile-scan" -> "分类垃圾扫描"
        "profile-clean" -> "分类垃圾清理"
        else -> if (mode.isBlank()) "未知清理任务" else mode.replace('-', ' ').replaceFirstChar { it.uppercase() }
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
        if (taskCallbackRegistered) runCatching { rootService?.unregisterTaskProgressCallback(taskProgressCallback) }

        pollJob?.cancel()
        recoveryProbeJob?.cancel()
        if (profileBound) runCatching { RootService.unbind(profileConnection) }
        if (cacheBound) runCatching { RootService.unbind(cacheConnection) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RUN_SMART_CLEAN = "io.github.xgl34222220.baize.RUN_SMART_CLEAN"
        private const val SNAPSHOT_TTL_MS = 30L * 60L * 1000L
        private const val RAW_LOG_LIMIT = 16_000
    }
}
