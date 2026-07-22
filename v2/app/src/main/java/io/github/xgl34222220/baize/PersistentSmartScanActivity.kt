package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.BaiZeRootService
import io.github.xgl34222220.baize.root.IBaiZeRootService
import io.github.xgl34222220.baize.root.IPersistentCleanPlanService
import io.github.xgl34222220.baize.root.PersistentCleanPlanRootService
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
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
import java.security.MessageDigest
import java.util.UUID

/**
 * Smart-clean entry backed by two immutable on-disk snapshots.
 *
 * The public component name remains SmartScanActivity through an activity alias, so every existing
 * navigation entry reaches this implementation without changing callers.
 */
class PersistentSmartScanActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private val preferences by lazy { getSharedPreferences("baize_v2", MODE_PRIVATE) }

    private var cacheService: IBaiZeRootService? = null
    private var planService: IPersistentCleanPlanService? = null
    private var cacheBindingRequested = false
    private var planBindingRequested = false
    private var pollJob: Job? = null

    private var cacheSnapshotId = ""
    private var cacheCount = 0
    private var safeSnapshotId = ""
    private var safeCount = 0
    private var cleanPlanId = ""
    private var cleanPlanCreatedAt = 0L
    private var restoredPlanNeedsValidation = false
    private var planValidationRunning = false

    private var showCleanConfirm by mutableStateOf(false)
    private var screenState by mutableStateOf(PersistentSmartUiState())

    private val cacheConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            cacheService = IBaiZeRootService.Stub.asInterface(binder)
            cacheBindingRequested = true
            updateConnectionState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cacheService = null
            cacheBindingRequested = false
            updateConnectionState()
        }
    }

    private val planConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            planService = IPersistentCleanPlanService.Stub.asInterface(binder)
            planBindingRequested = true
            updateConnectionState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            planService = null
            planBindingRequested = false
            updateConnectionState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        restoreCleanPlan()
        setContent {
            val appearance by appearanceViewModel.settings.collectAsState()
            BaiZeTheme(appearance) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PersistentSmartScreen(
                        state = screenState,
                        onBack = ::finish,
                        onScan = ::startSmartScan,
                        onClean = { showCleanConfirm = true },
                        onStop = ::stopTask,
                        onReconnect = ::bindServices
                    )
                    if (showCleanConfirm) {
                        AlertDialog(
                            onDismissRequest = { showCleanConfirm = false },
                            title = { Text("执行清理计划 ${cleanPlanId.take(8)}？") },
                            text = {
                                Text(
                                    "只处理本次扫描保存的 ${screenState.totalSafe} 项候选。" +
                                        "不会重新扫描；路径、白名单、软链接、挂载点和大文件限制仍会逐项复核。"
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showCleanConfirm = false
                                        cleanSnapshots()
                                    }
                                ) { Text("立即清理") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showCleanConfirm = false }) { Text("取消") }
                            }
                        )
                    }
                }
            }
        }
        bindServices()
    }

    private fun bindServices() {
        if (cacheService == null && !cacheBindingRequested) {
            runCatching {
                RootService.bind(
                    Intent(this, BaiZeRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE),
                    cacheConnection
                )
                cacheBindingRequested = true
            }.onFailure {
                cacheBindingRequested = false
                screenState = screenState.copy(phase = "缓存 Root 服务启动失败：${it.message}")
            }
        }
        if (planService == null && !planBindingRequested) {
            runCatching {
                RootService.bind(
                    Intent(this, PersistentCleanPlanRootService::class.java)
                        .addCategory(RootService.CATEGORY_DAEMON_MODE),
                    planConnection
                )
                planBindingRequested = true
            }.onFailure {
                planBindingRequested = false
                screenState = screenState.copy(phase = "清理计划 Root 服务启动失败：${it.message}")
            }
        }
        updateConnectionState()
    }

    private fun updateConnectionState() {
        val cacheReady = cacheService != null
        val planReady = planService != null
        screenState = screenState.copy(
            connected = cacheReady && planReady,
            status = when {
                cacheReady && planReady -> "两套 Root 快照引擎已连接"
                cacheReady -> "缓存引擎已连接 · 等待清理计划引擎"
                planReady -> "清理计划引擎已连接 · 等待缓存引擎"
                else -> "正在连接 Root 快照引擎"
            }
        )
        if (cacheReady && planReady && restoredPlanNeedsValidation && !planValidationRunning) {
            validateRestoredPlan()
        }
    }

    private fun startSmartScan() {
        if (screenState.running) return
        val cache = cacheService
        val plans = planService
        if (cache == null || plans == null) {
            screenState = screenState.copy(phase = "Root 引擎尚未全部连接，正在重新连接…")
            bindServices()
            return
        }

        clearSnapshotHandles()
        screenState = screenState.copy(
            running = true,
            operation = "scan",
            phase = "正在并行生成应用缓存与安全项目清理计划…",
            totalSafe = 0,
            cleanReady = false,
            scanCompleted = false,
            progressCurrent = 0,
            progressTotal = 2,
            cacheSummary = "正在扫描",
            safeSummary = "正在扫描"
        )
        startPolling()
        val started = SystemClock.elapsedRealtime()

        lifecycleScope.launch {
            try {
                val whitelist = preferences.getStringSet("package_whitelist", emptySet()).orEmpty()
                val options = optionsJson()
                val (cacheJson, safeJson) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val cacheJob = async {
                            JSONObject(cache.scanCandidates(JSONArray(whitelist.toList().sorted()).toString()))
                        }
                        val safeJob = async { JSONObject(plans.scanSafe(options)) }
                        cacheJob.await() to safeJob.await()
                    }
                }

                if (cacheJson.optString("error") == "busy" || safeJson.optString("error") == "busy") {
                    screenState = screenState.copy(phase = "当前已有扫描或清理任务正在运行")
                    return@launch
                }

                cacheSnapshotId = if (cacheJson.has("error")) "" else cacheJson.optString("snapshotId")
                cacheCount = if (cacheSnapshotId.isBlank()) 0 else (
                    cacheJson.optInt("totalCandidates") - cacheJson.optInt("whitelisted")
                ).coerceAtLeast(0)
                safeSnapshotId = if (safeJson.has("error")) "" else safeJson.optString("snapshotId")
                safeCount = if (safeSnapshotId.isBlank()) 0 else (
                    safeJson.optInt("low") + safeJson.optInt("medium")
                ).coerceAtLeast(0)

                val total = cacheCount + safeCount
                val cancelled = cacheJson.optBoolean("cancelled") || safeJson.optBoolean("cancelled")
                val ready = !cancelled && total > 0 &&
                    (cacheSnapshotId.isNotBlank() || safeSnapshotId.isNotBlank())
                screenState = screenState.copy(
                    running = false,
                    operation = "",
                    phase = buildString {
                        append(if (cancelled) "智能扫描已停止" else "清理计划生成完成")
                        append(" · ${SystemClock.elapsedRealtime() - started}ms")
                        if (!cancelled) append("\n共保存 $total 项，点击清理不会再次扫描")
                    },
                    totalSafe = total,
                    cleanReady = ready,
                    scanCompleted = !cancelled,
                    progressCurrent = 2,
                    progressTotal = 2,
                    cacheSummary = if (cacheJson.has("error")) {
                        cacheJson.optString("message", "缓存扫描失败")
                    } else {
                        "$cacheCount 项 · ${cacheJson.optLong("elapsedMs")}ms"
                    },
                    safeSummary = if (safeJson.has("error")) {
                        safeJson.optString("message", "安全项目扫描失败")
                    } else {
                        "$safeCount 项 · 空项目 ${safeJson.optInt("emptyFiles") + safeJson.optInt("emptyDirs")} · " +
                            "规则 ${safeJson.optInt("ruleTargets")} · 碎片 ${safeJson.optInt("fragmentFiles")}" 
                    }
                )
                if (ready) persistCleanPlan() else clearPersistedCleanPlan()
            } catch (error: Throwable) {
                screenState = screenState.copy(
                    phase = "智能扫描失败：${error.message ?: error.javaClass.simpleName}"
                )
            } finally {
                pollJob?.cancel()
                if (screenState.running) screenState = screenState.copy(running = false, operation = "")
                updateConnectionState()
            }
        }
    }

    private fun cleanSnapshots() {
        if (screenState.running) return
        if (!screenState.cleanReady || screenState.totalSafe <= 0 ||
            (cacheSnapshotId.isBlank() && safeSnapshotId.isBlank())
        ) {
            screenState = screenState.copy(phase = "没有可用的清理计划，请先扫描")
            return
        }
        if (!cleanPlanCurrent()) {
            clearSnapshotHandles()
            screenState = screenState.copy(
                phase = "清理计划已过期或设置已变化，请重新扫描",
                totalSafe = 0,
                cleanReady = false,
                scanCompleted = false
            )
            return
        }

        val cache = cacheService
        val plans = planService
        if (cache == null || plans == null) {
            screenState = screenState.copy(phase = "Root 清理引擎尚未全部连接")
            bindServices()
            return
        }

        screenState = screenState.copy(
            running = true,
            operation = "clean",
            phase = "正在执行清理计划 ${cleanPlanId.take(8)}…",
            progressCurrent = 0,
            progressTotal = screenState.totalSafe.coerceAtLeast(1)
        )
        startPolling()
        val started = SystemClock.elapsedRealtime()

        lifecycleScope.launch {
            var deletedBytes = 0L
            var deletedFiles = 0L
            var deletedDirectories = 0L
            var cleaned = 0
            var failures = 0
            var cancelled = false
            try {
                val selection = JSONObject().put("__all_safe__", true).toString()
                val whitelist = preferences.getStringSet("package_whitelist", emptySet()).orEmpty()

                if (cacheSnapshotId.isNotBlank() && cacheCount > 0) {
                    screenState = screenState.copy(phase = "正在清理应用缓存计划…")
                    val result = JSONObject(
                        withContext(Dispatchers.IO) {
                            cache.cleanSelected(
                                cacheSnapshotId,
                                selection,
                                JSONArray(whitelist.toList().sorted()).toString()
                            )
                        }
                    )
                    if (result.has("error")) throw IllegalStateException(result.optString("message", "缓存计划清理失败"))
                    deletedBytes += result.optLong("deletedBytes")
                    deletedFiles += result.optLong("deletedFiles")
                    deletedDirectories += result.optLong("deletedDirectories")
                    cleaned += result.optInt("cleanedCandidates")
                    failures += result.optInt("failures")
                    cancelled = result.optBoolean("cancelled")
                }

                if (!cancelled && safeSnapshotId.isNotBlank() && safeCount > 0) {
                    screenState = screenState.copy(phase = "正在清理空项目、规则垃圾与残留碎片计划…")
                    val result = JSONObject(
                        withContext(Dispatchers.IO) {
                            plans.cleanSafe(safeSnapshotId, selection, optionsJson())
                        }
                    )
                    if (result.has("error")) throw IllegalStateException(result.optString("message", "安全项目计划清理失败"))
                    deletedBytes += result.optLong("deletedBytes")
                    deletedFiles += result.optLong("deletedFiles")
                    deletedDirectories += result.optLong("deletedDirectories")
                    cleaned += result.optInt("cleanedCandidates")
                    failures += result.optInt("failures")
                    cancelled = result.optBoolean("cancelled")
                }

                val report = buildString {
                    append(if (cancelled) "清理计划已停止" else "清理计划执行完成")
                    append(" · ${SystemClock.elapsedRealtime() - started}ms")
                    append("\n释放 ${Formatter.formatFileSize(this@PersistentSmartScanActivity, deletedBytes)}")
                    append(" · 已处理 $cleaned 项 · 文件 $deletedFiles · 目录 $deletedDirectories")
                    if (failures > 0) append(" · 未清理 $failures")
                }
                preferences.edit()
                    .putString("last_report_text", report)
                    .putLong("last_clean_bytes", deletedBytes)
                    .apply()
                NativeNotifier.showTaskResult(
                    this@PersistentSmartScanActivity,
                    if (cancelled) "白泽清理计划已停止" else "白泽清理计划完成",
                    "释放 ${Formatter.formatFileSize(this@PersistentSmartScanActivity, deletedBytes)}",
                    report
                )
                clearSnapshotHandles()
                screenState = screenState.copy(
                    running = false,
                    operation = "",
                    phase = report,
                    totalSafe = 0,
                    cleanReady = false,
                    scanCompleted = false,
                    progressCurrent = cleaned,
                    progressTotal = cleaned.coerceAtLeast(1)
                )
            } catch (error: Throwable) {
                screenState = screenState.copy(
                    phase = "清理计划执行失败：${error.message ?: error.javaClass.simpleName}"
                )
            } finally {
                pollJob?.cancel()
                if (screenState.running) screenState = screenState.copy(running = false, operation = "")
                updateConnectionState()
            }
        }
    }

    private fun stopTask() {
        if (!screenState.running) return
        cacheService?.cancelCurrentTask()
        planService?.cancelCurrentTask()
        screenState = screenState.copy(phase = "已发送停止请求，正在安全结束当前项目…")
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive && screenState.running) {
                val states = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val cacheState = async {
                            cacheService?.getTaskState()?.takeIf { it.isNotBlank() }
                                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                        }
                        val planState = async {
                            planService?.getTaskState()?.takeIf { it.isNotBlank() }
                                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                        }
                        listOfNotNull(cacheState.await(), planState.await())
                    }
                }.filter { it.optBoolean("running") }
                if (states.isNotEmpty()) renderProgress(states)
                delay(350L)
            }
        }
    }

    private fun renderProgress(states: List<JSONObject>) {
        val preferred = states.firstOrNull {
            val operation = it.optString("operation", it.optString("mode"))
            operation.contains(screenState.operation, ignoreCase = true)
        } ?: states.first()
        val current = preferred.optInt("current", preferred.optInt("progress_current", screenState.progressCurrent))
        val total = preferred.optInt("total", preferred.optInt("progress_total", screenState.progressTotal))
        val path = preferred.optString("currentPath", preferred.optString("current_path")).trim()
        val phase = preferred.optString("phase").ifBlank {
            if (screenState.operation == "clean") "正在执行清理计划" else "正在生成清理计划"
        }
        screenState = screenState.copy(
            phase = buildString {
                append(phase)
                if (total > 0) append(" · $current/$total")
                if (path.isNotBlank()) append("\n").append(path.takeLast(96))
                if (preferred.optBoolean("cancelRequested")) append("\n正在安全停止…")
            },
            progressCurrent = current.coerceAtLeast(0),
            progressTotal = total.coerceAtLeast(1)
        )
    }

    private fun restoreCleanPlan() {
        val raw = preferences.getString(CLEAN_PLAN_KEY, null).orEmpty()
        if (raw.isBlank()) return
        val plan = runCatching { JSONObject(raw) }.getOrNull() ?: run {
            clearPersistedCleanPlan()
            return
        }
        val createdAt = plan.optLong("createdAt", 0L)
        val age = System.currentTimeMillis() - createdAt
        if (plan.optInt("version", 0) != CLEAN_PLAN_VERSION ||
            createdAt <= 0L || age !in 0..CLEAN_PLAN_TTL_MS ||
            plan.optString("optionsSha") != sha256(optionsJson())
        ) {
            clearPersistedCleanPlan()
            screenState = screenState.copy(phase = "旧清理计划已过期或设置已变化，请重新扫描")
            return
        }

        cleanPlanId = plan.optString("planId")
        cleanPlanCreatedAt = createdAt
        cacheSnapshotId = plan.optString("cacheSnapshotId")
        cacheCount = plan.optInt("cacheCount", 0).coerceAtLeast(0)
        safeSnapshotId = plan.optString("safeSnapshotId")
        safeCount = plan.optInt("safeCount", 0).coerceAtLeast(0)
        val total = cacheCount + safeCount
        if (cleanPlanId.isBlank() || total <= 0 ||
            (cacheSnapshotId.isBlank() && safeSnapshotId.isBlank())
        ) {
            clearSnapshotHandles()
            return
        }
        restoredPlanNeedsValidation = true
        screenState = screenState.copy(
            phase = "已恢复清理计划 ${cleanPlanId.take(8)}，连接 Root 引擎后验证快照",
            totalSafe = total,
            cleanReady = true,
            scanCompleted = true,
            cacheSummary = plan.optString("cacheSummary", "已恢复 $cacheCount 项"),
            safeSummary = plan.optString("safeSummary", "已恢复 $safeCount 项")
        )
    }

    private fun validateRestoredPlan() {
        val cache = cacheService ?: return
        val plans = planService ?: return
        if (!restoredPlanNeedsValidation || planValidationRunning) return
        planValidationRunning = true
        lifecycleScope.launch {
            val (cacheValid, safeValid) = withContext(Dispatchers.IO) {
                val cacheOk = cacheSnapshotId.isNotBlank() && cacheCount > 0 && runCatching {
                    val page = JSONObject(cache.getResultPage(cacheSnapshotId, 0, 1))
                    !page.has("error") && page.optString("snapshotId") == cacheSnapshotId
                }.getOrDefault(false)
                val safeOk = safeSnapshotId.isNotBlank() && safeCount > 0 && runCatching {
                    val page = JSONObject(plans.getPage(safeSnapshotId, 0, 1))
                    !page.has("error") && page.optString("snapshotId") == safeSnapshotId
                }.getOrDefault(false)
                cacheOk to safeOk
            }
            planValidationRunning = false
            restoredPlanNeedsValidation = false
            if (!cacheValid) {
                cacheSnapshotId = ""
                cacheCount = 0
            }
            if (!safeValid) {
                safeSnapshotId = ""
                safeCount = 0
            }
            val total = cacheCount + safeCount
            if (total <= 0) {
                clearSnapshotHandles()
                screenState = screenState.copy(
                    phase = "已保存的清理计划快照均已失效，请重新扫描",
                    totalSafe = 0,
                    cleanReady = false,
                    scanCompleted = false
                )
            } else {
                persistCleanPlan()
                screenState = screenState.copy(
                    phase = "清理计划 ${cleanPlanId.take(8)} 已恢复 · $total 项可直接清理，不会重新扫描",
                    totalSafe = total,
                    cleanReady = true,
                    scanCompleted = true
                )
            }
        }
    }

    private fun persistCleanPlan() {
        val total = cacheCount + safeCount
        if (total <= 0 || (cacheSnapshotId.isBlank() && safeSnapshotId.isBlank())) {
            clearPersistedCleanPlan()
            return
        }
        if (cleanPlanId.isBlank()) cleanPlanId = UUID.randomUUID().toString()
        if (cleanPlanCreatedAt <= 0L) cleanPlanCreatedAt = System.currentTimeMillis()
        val plan = JSONObject()
            .put("version", CLEAN_PLAN_VERSION)
            .put("planId", cleanPlanId)
            .put("createdAt", cleanPlanCreatedAt)
            .put("optionsSha", sha256(optionsJson()))
            .put("cacheSnapshotId", cacheSnapshotId)
            .put("cacheCount", cacheCount)
            .put("safeSnapshotId", safeSnapshotId)
            .put("safeCount", safeCount)
            .put("cacheSummary", screenState.cacheSummary)
            .put("safeSummary", screenState.safeSummary)
        preferences.edit().putString(CLEAN_PLAN_KEY, plan.toString()).apply()
    }

    private fun cleanPlanCurrent(): Boolean {
        val age = System.currentTimeMillis() - cleanPlanCreatedAt
        if (cleanPlanId.isBlank() || cleanPlanCreatedAt <= 0L || age !in 0..CLEAN_PLAN_TTL_MS) return false
        val plan = runCatching {
            JSONObject(preferences.getString(CLEAN_PLAN_KEY, null).orEmpty())
        }.getOrNull() ?: return false
        return plan.optString("planId") == cleanPlanId &&
            plan.optString("optionsSha") == sha256(optionsJson())
    }

    private fun optionsJson(): String {
        val whitelist = preferences.getStringSet("package_whitelist", emptySet()).orEmpty().toList().sorted()
        val pathWhitelist = preferences.getStringSet("path_whitelist", emptySet()).orEmpty().toList().sorted()
        val maxMb = preferences.getFloat("large_file_mb", 512f).toLong().coerceIn(64L, 16_384L)
        return JSONObject()
            .put("whitelistPackages", JSONArray(whitelist))
            .put("whitelistPaths", JSONArray(pathWhitelist))
            .put("maxFileBytes", maxMb * 1024L * 1024L)
            .put("fragmentDays", preferences.getInt("fragment_days", 7).coerceIn(0, 365))
            .put("allowHighRisk", false)
            .toString()
    }

    private fun clearPersistedCleanPlan() {
        preferences.edit().remove(CLEAN_PLAN_KEY).apply()
        cleanPlanId = ""
        cleanPlanCreatedAt = 0L
        restoredPlanNeedsValidation = false
        planValidationRunning = false
    }

    private fun clearSnapshotHandles() {
        cacheSnapshotId = ""
        cacheCount = 0
        safeSnapshotId = ""
        safeCount = 0
        clearPersistedCleanPlan()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    override fun onDestroy() {
        pollJob?.cancel()
        if (cacheBindingRequested) runCatching { RootService.unbind(cacheConnection) }
        if (planBindingRequested) runCatching { RootService.unbind(planConnection) }
        super.onDestroy()
    }

    companion object {
        private const val CLEAN_PLAN_KEY = "smart_clean_plan_v1"
        private const val CLEAN_PLAN_VERSION = 1
        private const val CLEAN_PLAN_TTL_MS = 30L * 60_000L
    }
}

private data class PersistentSmartUiState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val operation: String = "",
    val status: String = "正在连接 Root 快照引擎…",
    val phase: String = "连接完成后可开始智能扫描",
    val totalSafe: Int = 0,
    val cleanReady: Boolean = false,
    val scanCompleted: Boolean = false,
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val cacheSummary: String = "等待扫描",
    val safeSummary: String = "等待扫描"
)

@Composable
private fun PersistentSmartScreen(
    state: PersistentSmartUiState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onClean: () -> Unit,
    onStop: () -> Unit,
    onReconnect: () -> Unit
) {
    val progress = if (state.progressTotal > 0) {
        (state.progressCurrent.toFloat() / state.progressTotal.toFloat()).coerceIn(0f, 1f)
    } else 0f

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "返回") }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "CLEAN PLAN",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Text("智能扫描", fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text("扫描一次，验证后直接清理同一批候选", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(30.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(58.dp).background(
                                MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(20.dp)
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (state.cleanReady) Icons.Rounded.CheckCircle else Icons.Rounded.CleaningServices,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Spacer(Modifier.size(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(state.status, fontWeight = FontWeight.Bold)
                            Text(
                                state.phase,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (state.running) {
                        if (state.progressTotal > 0) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Stop, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("停止当前任务")
                        }
                    } else if (state.cleanReady) {
                        Button(onClick = onClean, enabled = state.connected, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("一键清理 ${state.totalSafe} 项")
                        }
                        OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("放弃计划并重新扫描")
                        }
                    } else if (state.connected) {
                        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("开始智能扫描")
                        }
                    } else {
                        OutlinedButton(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("重新连接 Root 引擎")
                        }
                    }
                }
            }
        }

        item { PlanSummaryCard("应用缓存", state.cacheSummary) }
        item { PlanSummaryCard("安全项目", state.safeSummary) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Text(
                    "清理计划有效期为 30 分钟。退出页面、App 被系统回收或 Root 服务重启后，" +
                        "仍会恢复并验证同一次扫描结果；计划失效时只会提示重新扫描，不会自动扫描。",
                    modifier = Modifier.padding(18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun PlanSummaryCard(title: String, summary: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 17.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                summary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
