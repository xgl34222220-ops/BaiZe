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

/**
 * Compose launcher backed by two native snapshot engines. A safety scan produces immutable,
 * expiring server-side candidate snapshots; the follow-up clean consumes those snapshots and must
 * never restart discovery.
 */
class MiuixDashboardActivity : ComponentActivity() {
    private val preferences by lazy { getSharedPreferences("baize_v2", MODE_PRIVATE) }
    private var rootService: IProfileRootService? = null
    private var cacheService: IBaiZeRootService? = null
    private var profileBound = false
    private var cacheBound = false
    private var profileBinding = false
    private var cacheBinding = false
    private var destroyed = false
    private var reconnectAttempt = 0
    private var pendingClean = false
    private var pollJob: Job? = null
    private var reconnectJob: Job? = null
    private var bindWatchdogJob: Job? = null
    private var pendingActionJob: Job? = null
    private var cacheSnapshotId = ""
    private var safeSnapshotId = ""
    private var cacheSnapshotCount = 0
    private var safeSnapshotCount = 0

    private var dashboardState = androidx.compose.runtime.mutableStateOf(DashboardUiState())
    private var schedulerState = androidx.compose.runtime.mutableStateOf(SchedulerUiState())

    private val profileConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            profileBinding = false
            profileBound = true
            reconnectAttempt = 0
            rootService = IProfileRootService.Stub.asInterface(binder)
            updateConnectionState()
            refreshAll()
            runPendingCleanIfReady()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handleServiceLoss(profile = true, reason = "分类 Root 引擎已断开")
        }

        override fun onBindingDied(name: ComponentName?) {
            handleServiceLoss(profile = true, reason = "分类 Root 引擎绑定失效")
        }

        override fun onNullBinding(name: ComponentName?) {
            handleServiceLoss(profile = true, reason = "分类 Root 引擎没有返回 Binder")
        }
    }

    private val cacheConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            cacheBinding = false
            cacheBound = true
            reconnectAttempt = 0
            cacheService = IBaiZeRootService.Stub.asInterface(binder)
            updateConnectionState()
            readServiceStatus()
            runPendingCleanIfReady()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handleServiceLoss(profile = false, reason = "应用缓存 Root 引擎已断开")
        }

        override fun onBindingDied(name: ComponentName?) {
            handleServiceLoss(profile = false, reason = "应用缓存 Root 引擎绑定失效")
        }

        override fun onNullBinding(name: ComponentName?) {
            handleServiceLoss(profile = false, reason = "应用缓存 Root 引擎没有返回 Binder")
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
                    scan = { runNativeScan(cleanAfterScan = false) },
                    cleanScan = { cleanNativeSnapshots() },
                    dismissScan = { clearScanResult() },
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
        connectServices()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_RUN_SMART_CLEAN, false)) {
            if (hasUsableScanSnapshots()) {
                cleanNativeSnapshots()
            } else if (rootService == null || cacheService == null) {
                pendingClean = true
                connectServices()
            } else {
                runSmartClean()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStorage()
        if (rootService != null) refreshAll()
        if (rootService == null || cacheService == null) scheduleReconnect(immediate = true)
    }

    private fun refreshAll() {
        updateStorage()
        readServiceStatus()
        loadScheduler()
        refreshModuleState()
        refreshHistory()
        refreshWhitelist()
    }

    private fun connectServices(force: Boolean = false) {
        if (destroyed) return
        if (force) releaseConnections()

        val anyConnected = rootService != null || cacheService != null
        dashboardState.value = dashboardState.value.copy(
            connected = anyConnected,
            ready = false,
            serviceText = when {
                rootService != null && cacheService == null -> "分类引擎已连接，正在恢复应用缓存引擎…"
                cacheService != null && rootService == null -> "应用缓存引擎已连接，正在恢复分类引擎…"
                else -> "正在连接 Root 清理引擎…"
            }
        )

        bindProfileIfNeeded()
        bindCacheIfNeeded()
        startBindWatchdog()
    }

    private fun bindProfileIfNeeded() {
        if (destroyed || rootService != null || profileBinding) return
        profileBinding = true
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java)
                    .addCategory(RootService.CATEGORY_DAEMON_MODE),
                profileConnection
            )
        }.onFailure {
            profileBinding = false
            profileBound = false
            dashboardState.value = dashboardState.value.copy(
                connected = cacheService != null,
                ready = false,
                serviceText = "分类引擎启动失败，正在重试：${it.message.orEmpty()}"
            )
            scheduleReconnect()
        }
    }

    private fun bindCacheIfNeeded() {
        if (destroyed || cacheService != null || cacheBinding) return
        cacheBinding = true
        runCatching {
            RootService.bind(
                Intent(this, BaiZeRootService::class.java)
                    .addCategory(RootService.CATEGORY_DAEMON_MODE),
                cacheConnection
            )
        }.onFailure {
            cacheBinding = false
            cacheBound = false
            dashboardState.value = dashboardState.value.copy(
                connected = rootService != null,
                ready = false,
                serviceText = "应用缓存引擎启动失败，正在重试：${it.message.orEmpty()}"
            )
            scheduleReconnect()
        }
    }

    private fun startBindWatchdog() {
        bindWatchdogJob?.cancel()
        bindWatchdogJob = lifecycleScope.launch {
            delay(7_000)
            var timedOut = false
            if (rootService == null && profileBinding) {
                runCatching { RootService.unbind(profileConnection) }
                profileBinding = false
                profileBound = false
                timedOut = true
            }
            if (cacheService == null && cacheBinding) {
                runCatching { RootService.unbind(cacheConnection) }
                cacheBinding = false
                cacheBound = false
                timedOut = true
            }
            if (timedOut && !destroyed) {
                dashboardState.value = dashboardState.value.copy(
                    connected = rootService != null || cacheService != null,
                    ready = false,
                    serviceText = "Root 引擎连接超时，正在自动重试…"
                )
                scheduleReconnect(immediate = true)
            }
        }
    }

    private fun handleServiceLoss(profile: Boolean, reason: String) {
        if (profile) {
            rootService = null
            profileBound = false
            profileBinding = false
        } else {
            cacheService = null
            cacheBound = false
            cacheBinding = false
        }
        val wasRunning = dashboardState.value.running
        pollJob?.cancel()
        dashboardState.value = dashboardState.value.copy(
            connected = rootService != null || cacheService != null,
            ready = false,
            running = false,
            serviceText = "$reason，正在自动恢复…",
            taskPhase = if (wasRunning) "任务连接中断，正在恢复 Root 服务；已有扫描快照不会重新扫描" else dashboardState.value.taskPhase
        )
        if (!destroyed) scheduleReconnect()
    }

    private fun scheduleReconnect(immediate: Boolean = false) {
        if (destroyed || (rootService != null && cacheService != null)) return
        reconnectJob?.cancel()
        val shift = reconnectAttempt.coerceIn(0, 3)
        val delayMs = if (immediate) 0L else (600L * (1 shl shift)).coerceAtMost(4_800L)
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(8)
        reconnectJob = lifecycleScope.launch {
            delay(delayMs)
            if (!destroyed && (rootService == null || cacheService == null)) connectServices()
        }
    }

    private fun releaseConnections() {
        bindWatchdogJob?.cancel()
        pendingActionJob?.cancel()
        if (profileBound || profileBinding) runCatching { RootService.unbind(profileConnection) }
        if (cacheBound || cacheBinding) runCatching { RootService.unbind(cacheConnection) }
        rootService = null
        cacheService = null
        profileBound = false
        cacheBound = false
        profileBinding = false
        cacheBinding = false
    }

    private fun reconnectService() {
        reconnectJob?.cancel()
        reconnectAttempt = 0
        releaseConnections()
        dashboardState.value = dashboardState.value.copy(
            connected = false,
            ready = false,
            running = false,
            serviceText = "正在重新连接 Root 清理引擎…"
        )
        connectServices()
        toast("正在重新连接 Root 清理引擎")
    }

    private fun updateConnectionState() {
        val profileReady = rootService != null
        val cacheReady = cacheService != null
        val both = profileReady && cacheReady
        if (both) {
            bindWatchdogJob?.cancel()
            reconnectJob?.cancel()
            reconnectAttempt = 0
        } else if (!destroyed) {
            scheduleReconnect()
        }
        dashboardState.value = dashboardState.value.copy(
            connected = profileReady || cacheReady,
            ready = if (both) dashboardState.value.ready else false,
            serviceText = when {
                both -> "双 Root 快照引擎已连接，正在校验模块组件…"
                profileReady -> "分类引擎已连接，正在恢复应用缓存引擎…"
                cacheReady -> "应用缓存引擎已连接，正在恢复分类引擎…"
                else -> "正在连接 Root 清理引擎…"
            }
        )
    }

    private fun runPendingCleanIfReady() {
        if (!pendingClean) return
        val snapshotsReady = hasUsableScanSnapshots()
        if (snapshotsReady) {
            val requiredEnginesReady =
                (cacheSnapshotId.isBlank() || cacheSnapshotCount <= 0 || cacheService != null) &&
                    (safeSnapshotId.isBlank() || safeSnapshotCount <= 0 || rootService != null)
            if (!requiredEnginesReady) return
            pendingActionJob?.cancel()
            pendingClean = false
            runSmartClean()
            return
        }

        val both = rootService != null && cacheService != null
        val any = rootService != null || cacheService != null
        if (both) {
            pendingActionJob?.cancel()
            pendingClean = false
            runSmartClean()
        } else if (any) {
            pendingActionJob?.cancel()
            pendingActionJob = lifecycleScope.launch {
                delay(1_800)
                if (pendingClean && (rootService != null || cacheService != null)) {
                    pendingClean = false
                    runSmartClean()
                }
            }
            scheduleReconnect(immediate = true)
        }
    }

    private fun readServiceStatus() {
        val service = rootService ?: return
        val cacheReady = cacheService != null
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { JSONObject(service.ping()) }.getOrNull()
            } ?: return@launch
            val root = json.optBoolean("root")
            val module = json.optBoolean("module")
            val cleaner = json.optBoolean("cleaner")
            val scheduler = json.optBoolean("scheduler")
            val rules = json.optBoolean("deepRules")
            val ready = root && module && cleaner && scheduler && rules && cacheReady
            val text = when {
                !root -> "服务已连接，但未取得完整 Root"
                !cacheReady -> "分类引擎已连接，等待应用缓存引擎"
                !module -> "Root 已连接 · 未检测到白泽模块"
                !cleaner -> "模块已连接 · 清理引擎缺失"
                !scheduler -> "清理引擎已连接 · 调度器缺失"
                !rules -> "自动清理可用 · 深度规则库缺失"
                else -> "双快照引擎、自动清理、定时任务与规则库均已就绪"
            }
            dashboardState.value = dashboardState.value.copy(
                connected = rootService != null || cacheReady,
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
        if (rootService == null && cacheService == null) {
            pendingClean = true
            dashboardState.value = dashboardState.value.copy(
                connected = false,
                ready = false,
                taskPhase = "正在连接 Root 引擎，连接成功后继续清理"
            )
            connectServices()
            toast("正在连接 Root 引擎，稍后会自动继续")
            return
        }
        if (hasUsableScanSnapshots()) cleanNativeSnapshots() else runNativeScan(cleanAfterScan = true)
    }

    private fun runNativeScan(cleanAfterScan: Boolean) {
        if (schedulerState.value.notifyOnComplete) requestNotificationPermission()
        val cache = cacheService
        val profiles = rootService
        if (cache == null && profiles == null) {
            pendingClean = cleanAfterScan
            dashboardState.value = dashboardState.value.copy(
                connected = false,
                ready = false,
                taskPhase = "正在连接 Root 引擎，连接成功后继续扫描"
            )
            connectServices()
            return
        }
        if (dashboardState.value.running) return
        pendingClean = false
        pendingActionJob?.cancel()
        clearSnapshotHandles()
        val started = SystemClock.elapsedRealtime()
        dashboardState.value = dashboardState.value.copy(
            running = true,
            scanCompleted = false,
            taskPhase = when {
                cache != null && profiles != null -> "正在并行发现应用缓存与安全项目…"
                cache != null -> "分类引擎恢复中，先扫描应用缓存…"
                else -> "应用缓存引擎恢复中，先扫描安全项目…"
            }
        )
        startNativePoll()
        lifecycleScope.launch {
            val pair = runCatching {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val whitelist = JSONArray(packageWhitelist().toList()).toString()
                        val cacheJob = cache?.let { engine -> async { JSONObject(engine.scanCandidates(whitelist)) } }
                        val safeJob = profiles?.let { engine -> async { JSONObject(engine.scanProfile("safe", optionsJson())) } }
                        val cacheJson = cacheJob?.await() ?: JSONObject()
                            .put("error", "cache_engine_unavailable")
                            .put("message", "应用缓存引擎未连接")
                        val safeJson = safeJob?.await() ?: JSONObject()
                            .put("error", "profile_engine_unavailable")
                            .put("message", "分类引擎未连接")
                        cacheJson to safeJson
                    }
                }
            }
            pollJob?.cancel()
            if (pair.isFailure) {
                dashboardState.value = dashboardState.value.copy(
                    running = false,
                    taskPhase = "安全扫描失败：${pair.exceptionOrNull()?.message ?: "Root 服务异常"}"
                )
                scheduleReconnect()
                return@launch
            }

            val (cacheJson, safeJson) = pair.getOrThrow()
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
            dashboardState.value = dashboardState.value.copy(
                running = false,
                scanCompleted = successfulScan,
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
                    failures > 0 && total > 0 -> "部分扫描完成，发现 $total 项；缺失引擎恢复后可再次扫描"
                    failures > 0 -> "部分扫描完成，当前在线引擎未发现可清理项目"
                    total == 0 -> "扫描完成，没有发现可安全清理的项目"
                    else -> "扫描完成，发现 $total 项；快照 30 分钟内有效"
                }
            )
            if (!cleanAfterScan) notifyScanResult(successfulScan, cancelled, total, knownBytes, emptyFiles, emptyDirs, fragments, elapsed)
            if (cleanAfterScan && successfulScan && total > 0) {
                cleanNativeSnapshots()
            } else if (cleanAfterScan && successfulScan) {
                notifyCleanResult(
                    "白泽智能清理完成",
                    if (failures > 0) "部分引擎在线，当前未发现可清理项目" else "没有发现可安全清理的项目",
                    "扫描一次完成 · 未执行删除",
                    0L
                )
            }
            if (rootService == null || cacheService == null) scheduleReconnect()
        }
    }

    private fun cleanNativeSnapshots() {
        if (dashboardState.value.running) return
        if (!hasUsableScanSnapshots()) {
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
            pendingClean = true
            dashboardState.value = dashboardState.value.copy(
                connected = false,
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
            dashboardState.value = dashboardState.value.copy(
                running = false,
                scanCompleted = false,
                lastReleased = deletedBytes,
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

    private fun hasUsableScanSnapshots(): Boolean =
        (cacheSnapshotId.isNotBlank() && cacheSnapshotCount > 0) ||
            (safeSnapshotId.isNotBlank() && safeSnapshotCount > 0)

    private fun clearSnapshotHandles() {
        cacheSnapshotId = ""
        safeSnapshotId = ""
        cacheSnapshotCount = 0
        safeSnapshotCount = 0
    }

    private fun clearScanResult() {
        clearSnapshotHandles()
        dashboardState.value = dashboardState.value.copy(scanCompleted = false)
    }

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

    private fun refreshModuleState() {
        val service = rootService ?: return
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { JSONObject(service.getModuleState()) }.getOrNull()
            } ?: return@launch
            val latest = json.optJSONObject("latest") ?: JSONObject()
            val scheduler = json.optJSONObject("scheduler") ?: JSONObject()
            val latestMode = latest.optString("mode")
            val latestReleased = if (latestMode.endsWith("scan") || latestMode == "scan") {
                preferences.getLong("last_clean_bytes", dashboardState.value.lastReleased)
            } else {
                latest.optLong("bytes", preferences.getLong("last_clean_bytes", 0L)).coerceAtLeast(0L)
            }
            dashboardState.value = dashboardState.value.copy(
                lastReleased = latestReleased,
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
        "snapshot-clean" -> "扫描快照清理"
        "smart-clean" -> "原生智能清理"
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
        destroyed = true
        pollJob?.cancel()
        reconnectJob?.cancel()
        bindWatchdogJob?.cancel()
        pendingActionJob?.cancel()
        releaseConnections()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RUN_SMART_CLEAN = "io.github.xgl34222220.baize.RUN_SMART_CLEAN"
    }
}
