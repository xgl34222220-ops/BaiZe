package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.root.RootServiceClients
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.BaiZeRootService
import io.github.xgl34222220.baize.root.CleanPlanResumeRootService
import io.github.xgl34222220.baize.root.IBaiZeRootService
import io.github.xgl34222220.baize.root.ICleanPlanResumeService
import io.github.xgl34222220.baize.root.IPersistentCleanPlanService
import io.github.xgl34222220.baize.root.PersistentCleanPlanRootService
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
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

/** Smart clean with stage checkpoints and crash-safe resume. */
class ResumableSmartScanActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private val preferences by lazy { getSharedPreferences("baize_v2", MODE_PRIVATE) }

    private var cacheService: IBaiZeRootService? = null
    private var planService: IPersistentCleanPlanService? = null
    private var resumeService: ICleanPlanResumeService? = null
    private var cacheBindingRequested = false
    private var planBindingRequested = false
    private var resumeBindingRequested = false
    private var pollJob: Job? = null

    private var cacheSnapshotId = ""
    private var safeSnapshotId = ""
    private var cacheCount = 0
    private var safeCount = 0
    private var originalCacheCount = 0
    private var originalSafeCount = 0
    private var cleanPlanId = ""
    private var cleanPlanCreatedAt = 0L
    private var runCount = 0
    private var deletedBytes = 0L
    private var deletedFiles = 0L
    private var deletedDirectories = 0L
    private var processedCandidates = 0
    private var cleanedCandidates = 0
    private var changedCandidates = 0
    private var protectedCandidates = 0
    private var partialCandidates = 0
    private var failedCandidates = 0
    private var classifiedDeletedBytes = 0L
    private var unattributedDeletedBytes = 0L
    private var categoryStats = JSONObject()
    private var riskStats = JSONObject()
    private var cumulativeFailures = 0
    private var resumable = false
    private var restoredPlanNeedsValidation = false
    private var validationRunning = false

    private var showCleanConfirm by mutableStateOf(false)
    private var screenState by mutableStateOf(ResumeSmartUiState())

    private val cacheConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            cacheService = RootServiceClients.cache(binder, applicationContext.cacheDir)
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
            planService = RootServiceClients.persistent(binder, applicationContext.cacheDir)
            planBindingRequested = true
            updateConnectionState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            planService = null
            planBindingRequested = false
            updateConnectionState()
        }
    }

    private val resumeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            resumeService = RootServiceClients.resume(binder, applicationContext.cacheDir)
            resumeBindingRequested = true
            updateConnectionState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            resumeService = null
            resumeBindingRequested = false
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
                Surface(modifier = Modifier.fillMaxSize(), color = BaiZeTokens.colors.surfaceBase) {
                    ResumeSmartScreen(
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
                            title = {
                                Text(
                                    if (resumable) "继续清理 ${cacheCount + safeCount} 项？"
                                    else "执行清理计划 ${cleanPlanId.take(8)}？"
                                )
                            },
                            text = {
                                Text(
                                    if (resumable) {
                                        "只继续处理事务日志中剩余的候选。已完成项目不会重复删除，也不会重新扫描。"
                                    } else {
                                        "只处理本次扫描保存的候选。清理前会再次校验路径、白名单和文件状态。"
                                    }
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showCleanConfirm = false
                                    cleanSnapshots()
                                }) { Text(if (resumable) "继续清理" else "立即清理") }
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
                screenState = screenState.copy(phase = "安全项目 Root 服务启动失败：${it.message}")
            }
        }
        if (resumeService == null && !resumeBindingRequested) {
            runCatching {
                RootService.bind(
                    Intent(this, CleanPlanResumeRootService::class.java)
                        .addCategory(RootService.CATEGORY_DAEMON_MODE),
                    resumeConnection
                )
                resumeBindingRequested = true
            }.onFailure {
                resumeBindingRequested = false
                screenState = screenState.copy(phase = "断点事务 Root 服务启动失败：${it.message}")
            }
        }
        updateConnectionState()
    }

    private fun updateConnectionState() {
        val readyCount = listOf(cacheService, planService, resumeService).count { it != null }
        screenState = screenState.copy(
            connected = readyCount == 3,
            status = if (readyCount == 3) "扫描、快照与断点事务引擎已连接" else "正在连接 Root 引擎 · $readyCount/3"
        )
        if (readyCount == 3 && restoredPlanNeedsValidation && !validationRunning) validateRestoredPlan()
    }

    private fun startSmartScan() {
        if (screenState.running) return
        val cache = cacheService
        val plans = planService
        val transactions = resumeService
        if (cache == null || plans == null || transactions == null) {
            screenState = screenState.copy(phase = "Root 引擎尚未全部连接，正在重新连接…")
            bindServices()
            return
        }

        val oldPlan = cleanPlanId
        resetPlanFields()
        if (oldPlan.isNotBlank()) lifecycleScope.launch(Dispatchers.IO) { runCatching { transactions.finish(oldPlan) } }
        screenState = ResumeSmartUiState(
            connected = true,
            running = true,
            operation = "scan",
            status = "扫描、快照与断点事务引擎已连接",
            phase = "正在并行生成应用缓存与安全项目清理计划…",
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
                originalCacheCount = cacheCount
                originalSafeCount = safeCount
                cleanPlanId = UUID.randomUUID().toString()
                cleanPlanCreatedAt = System.currentTimeMillis()

                val total = cacheCount + safeCount
                val cancelled = cacheJson.optBoolean("cancelled") || safeJson.optBoolean("cancelled")
                val ready = !cancelled && total > 0 && (cacheSnapshotId.isNotBlank() || safeSnapshotId.isNotBlank())
                screenState = screenState.copy(
                    running = false,
                    operation = "",
                    phase = buildString {
                        append(if (cancelled) "智能扫描已停止" else "可恢复清理计划生成完成")
                        append(" · ${SystemClock.elapsedRealtime() - started}ms")
                        if (!cancelled) append("\n保存 $total 项；停止或异常中断后可继续，不会重新扫描")
                    },
                    totalSafe = total,
                    cleanReady = ready,
                    scanCompleted = !cancelled,
                    resumable = false,
                    progressCurrent = 2,
                    progressTotal = 2,
                    cacheSummary = if (cacheJson.has("error")) {
                        cacheJson.optString("message", "缓存扫描失败")
                    } else "$cacheCount 项 · ${cacheJson.optLong("elapsedMs")}ms",
                    safeSummary = if (safeJson.has("error")) {
                        safeJson.optString("message", "安全项目扫描失败")
                    } else {
                        "$safeCount 项 · 空项目 ${safeJson.optInt("emptyFiles") + safeJson.optInt("emptyDirs")} · " +
                            "规则 ${safeJson.optInt("ruleTargets")} · 碎片 ${safeJson.optInt("fragmentFiles")}"
                    }
                )
                if (ready) persistCleanPlan() else clearLocalPlan()
            } catch (error: Throwable) {
                screenState = screenState.copy(phase = "智能扫描失败：${error.message ?: error.javaClass.simpleName}")
            } finally {
                pollJob?.cancel()
                if (screenState.running) screenState = screenState.copy(running = false, operation = "")
                updateConnectionState()
            }
        }
    }

    private fun cleanSnapshots() {
        if (screenState.running) return
        val totalBefore = cacheCount + safeCount
        if (!screenState.cleanReady || totalBefore <= 0 || !cleanPlanCurrent()) {
            screenState = screenState.copy(phase = "清理计划已过期、失效或设置已变化，请重新扫描")
            return
        }
        val cache = cacheService
        val plans = planService
        val transactions = resumeService
        if (cache == null || plans == null || transactions == null) {
            screenState = screenState.copy(phase = "Root 引擎尚未全部连接")
            bindServices()
            return
        }

        screenState = screenState.copy(
            running = true,
            operation = "clean",
            phase = if (resumable) "正在继续清理剩余候选…" else "正在启动可恢复清理事务…",
            progressCurrent = 0,
            progressTotal = totalBefore.coerceAtLeast(1)
        )
        startPolling()
        val started = SystemClock.elapsedRealtime()

        lifecycleScope.launch {
            var interrupted = false
            try {
                val begin = JSONObject(withContext(Dispatchers.IO) {
                    transactions.begin(cleanPlanId, cacheSnapshotId, safeSnapshotId, cacheCount, safeCount)
                })
                if (begin.has("error")) throw IllegalStateException(begin.optString("message", "无法启动清理事务"))
                applyTransaction(begin)
                persistCleanPlan()

                val selection = JSONObject().put("__all_safe__", true).toString()
                val whitelist = preferences.getStringSet("package_whitelist", emptySet()).orEmpty()

                if (cacheSnapshotId.isNotBlank() && cacheCount > 0) {
                    screenState = screenState.copy(phase = "正在清理应用缓存 · 已建立事务检查点")
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            JSONObject(cache.cleanSelected(
                                cacheSnapshotId,
                                selection,
                                JSONArray(whitelist.toList().sorted()).toString()
                            ))
                        }.getOrElse { throwableJson(it) }
                    }
                    val checkpoint = JSONObject(withContext(Dispatchers.IO) {
                        transactions.checkpointCache(cleanPlanId, result.toString())
                    })
                    if (checkpoint.has("error")) throw IllegalStateException(checkpoint.optString("message"))
                    applyTransaction(checkpoint)
                    if (checkpoint.optBoolean("cacheComplete")) cacheSnapshotId = ""
                    persistCleanPlan()
                    interrupted = result.has("error") || result.optBoolean("cancelled") || result.optBoolean("timedOut")
                }

                if (!interrupted && safeSnapshotId.isNotBlank() && safeCount > 0) {
                    screenState = screenState.copy(phase = "正在清理安全项目 · 逐候选写入事务日志")
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            JSONObject(plans.cleanSafe(safeSnapshotId, selection, optionsJson()))
                        }.getOrElse { throwableJson(it) }
                    }
                    val checkpoint = JSONObject(withContext(Dispatchers.IO) {
                        transactions.checkpointSafe(cleanPlanId, result.toString())
                    })
                    if (checkpoint.has("error")) throw IllegalStateException(checkpoint.optString("message"))
                    applyTransaction(checkpoint)
                    if (checkpoint.optBoolean("safeComplete")) safeSnapshotId = ""
                    persistCleanPlan()
                    interrupted = result.has("error") || result.optBoolean("cancelled") || result.optBoolean("timedOut")
                }

                val remaining = cacheCount + safeCount
                val elapsed = SystemClock.elapsedRealtime() - started
                val report = buildString {
                    append(if (remaining > 0) {
                        if (interrupted) "清理已安全停止" else "清理部分完成"
                    } else "清理计划执行完成")
                    append(" · ${elapsed}ms")
                    append("\n累计释放 ${Formatter.formatFileSize(this@ResumableSmartScanActivity, deletedBytes)}")
                    append(" · 已处理 $processedCandidates 项 · 实际清理 $cleanedCandidates 项 · 剩余 $remaining 项")
                    if (changedCandidates > 0) append(" · 已变化 $changedCandidates")
                    if (protectedCandidates > 0) append(" · 受保护 $protectedCandidates")
                    if (partialCandidates > 0) append(" · 部分 $partialCandidates")
                    if (failedCandidates > 0) append(" · 失败候选 $failedCandidates")
                    if (cumulativeFailures > 0) append(" · 删除错误 $cumulativeFailures")
                    if (remaining > 0) append("\n可直接点击继续清理，不会重新扫描或重复处理已完成项目")
                }
                preferences.edit()
                    .putString("last_report_text", report)
                    .putLong("last_clean_bytes", deletedBytes)
                    .apply()

                if (remaining <= 0) {
                    withContext(Dispatchers.IO) { runCatching { transactions.finish(cleanPlanId) } }
                    clearLocalPlan()
                } else {
                    resumable = true
                    persistCleanPlan()
                }
                screenState = screenState.copy(
                    running = false,
                    operation = "",
                    phase = report,
                    totalSafe = remaining,
                    cleanReady = remaining > 0,
                    scanCompleted = remaining > 0,
                    resumable = remaining > 0,
                    runCount = runCount,
                    deletedBytes = deletedBytes,
                    processedCandidates = processedCandidates,
                    cleanedCandidates = cleanedCandidates,
                    changedCandidates = changedCandidates,
                    protectedCandidates = protectedCandidates,
                    partialCandidates = partialCandidates,
                    failedCandidates = failedCandidates,
                    classifiedDeletedBytes = classifiedDeletedBytes,
                    unattributedDeletedBytes = unattributedDeletedBytes,
                    categoryStats = categoryStats.toString(),
                    riskStats = riskStats.toString(),
                    failures = cumulativeFailures,
                    progressCurrent = (originalCacheCount + originalSafeCount - remaining).coerceAtLeast(0),
                    progressTotal = (originalCacheCount + originalSafeCount).coerceAtLeast(1),
                    cacheSummary = if (remaining > 0) "应用缓存剩余 $cacheCount 项" else "应用缓存清理完成",
                    safeSummary = if (remaining > 0) "安全项目剩余 $safeCount 项" else "安全项目清理完成"
                )
                NativeNotifier.showTaskResult(
                    this@ResumableSmartScanActivity,
                    if (remaining > 0) "白泽清理已保存断点" else "白泽清理计划完成",
                    if (remaining > 0) "剩余 $remaining 项，可继续清理" else "释放 ${Formatter.formatFileSize(this@ResumableSmartScanActivity, deletedBytes)}",
                    report
                )
            } catch (error: Throwable) {
                val recovered = withContext(Dispatchers.IO) {
                    runCatching { JSONObject(transactions.recover(cleanPlanId)) }.getOrNull()
                }
                if (recovered != null && !recovered.has("error")) applyTransaction(recovered)
                val remaining = cacheCount + safeCount
                resumable = remaining > 0
                if (remaining > 0) persistCleanPlan()
                screenState = screenState.copy(
                    running = false,
                    operation = "",
                    phase = "清理事务中断：${error.message ?: error.javaClass.simpleName}\n已保存剩余 $remaining 项，可直接继续清理",
                    totalSafe = remaining,
                    cleanReady = remaining > 0,
                    scanCompleted = remaining > 0,
                    resumable = remaining > 0,
                    runCount = runCount,
                    deletedBytes = deletedBytes,
                    cleanedCandidates = cleanedCandidates,
                    failures = cumulativeFailures,
                    cacheSummary = "应用缓存剩余 $cacheCount 项",
                    safeSummary = "安全项目剩余 $safeCount 项"
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
        screenState = screenState.copy(phase = "已发送停止请求；完成当前项目后写入断点…")
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
                        val safeState = async {
                            planService?.getTaskState()?.takeIf { it.isNotBlank() }
                                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                        }
                        listOfNotNull(cacheState.await(), safeState.await())
                    }
                }.filter { it.optBoolean("running") }
                if (states.isNotEmpty()) renderProgress(states)
                delay(350L)
            }
        }
    }

    private fun renderProgress(states: List<JSONObject>) {
        val preferred = states.firstOrNull {
            it.optString("operation", it.optString("mode")).contains(screenState.operation, ignoreCase = true)
        } ?: states.first()
        val current = preferred.optInt("current", preferred.optInt("progress_current", screenState.progressCurrent))
        val total = preferred.optInt("total", preferred.optInt("progress_total", screenState.progressTotal))
        val path = preferred.optString("currentPath", preferred.optString("current_path")).trim()
        val phase = preferred.optString("phase").ifBlank {
            if (screenState.operation == "clean") "正在执行可恢复清理" else "正在生成清理计划"
        }
        screenState = screenState.copy(
            phase = buildString {
                append(phase)
                if (total > 0) append(" · $current/$total")
                if (path.isNotBlank()) append("\n").append(path.takeLast(96))
                if (preferred.optBoolean("cancelRequested")) append("\n正在安全停止并保存断点…")
            },
            progressCurrent = current.coerceAtLeast(0),
            progressTotal = total.coerceAtLeast(1)
        )
    }

    private fun restoreCleanPlan() {
        val rawV2 = preferences.getString(CLEAN_PLAN_KEY, null).orEmpty()
        val raw = if (rawV2.isNotBlank()) rawV2 else preferences.getString(LEGACY_PLAN_KEY, null).orEmpty()
        if (raw.isBlank()) return
        val plan = runCatching { JSONObject(raw) }.getOrNull() ?: run {
            clearLocalPlan()
            return
        }
        val createdAt = plan.optLong("createdAt", 0L)
        val age = System.currentTimeMillis() - createdAt
        if (createdAt <= 0L || age !in 0..CLEAN_PLAN_TTL_MS || plan.optString("optionsSha") != sha256(optionsJson())) {
            clearLocalPlan()
            screenState = screenState.copy(phase = "旧清理计划已过期或设置已变化，请重新扫描")
            return
        }

        cleanPlanId = plan.optString("planId")
        cleanPlanCreatedAt = createdAt
        cacheSnapshotId = plan.optString("cacheSnapshotId")
        safeSnapshotId = plan.optString("safeSnapshotId")
        cacheCount = plan.optInt("cacheCount").coerceAtLeast(0)
        safeCount = plan.optInt("safeCount").coerceAtLeast(0)
        originalCacheCount = plan.optInt("originalCacheCount", cacheCount).coerceAtLeast(cacheCount)
        originalSafeCount = plan.optInt("originalSafeCount", safeCount).coerceAtLeast(safeCount)
        runCount = plan.optInt("runCount", 0).coerceAtLeast(0)
        deletedBytes = plan.optLong("deletedBytes", 0L).coerceAtLeast(0L)
        deletedFiles = plan.optLong("deletedFiles", 0L).coerceAtLeast(0L)
        deletedDirectories = plan.optLong("deletedDirectories", 0L).coerceAtLeast(0L)
        processedCandidates = plan.optInt("processedCandidates", 0).coerceAtLeast(0)
        cleanedCandidates = plan.optInt("cleanedCandidates", 0).coerceAtLeast(0)
        changedCandidates = plan.optInt("changedCandidates", 0).coerceAtLeast(0)
        protectedCandidates = plan.optInt("protectedCandidates", 0).coerceAtLeast(0)
        partialCandidates = plan.optInt("partialCandidates", 0).coerceAtLeast(0)
        failedCandidates = plan.optInt("failedCandidates", 0).coerceAtLeast(0)
        classifiedDeletedBytes = plan.optLong("classifiedDeletedBytes", 0L).coerceAtLeast(0L)
        unattributedDeletedBytes = plan.optLong("unattributedDeletedBytes", 0L).coerceAtLeast(0L)
        categoryStats = plan.optJSONObject("categoryStats") ?: JSONObject()
        riskStats = plan.optJSONObject("riskStats") ?: JSONObject()
        cumulativeFailures = plan.optInt("deleteErrors", plan.optInt("failures", 0)).coerceAtLeast(0)
        resumable = plan.optBoolean("resumable", runCount > 0)
        val total = cacheCount + safeCount
        if (cleanPlanId.isBlank() || total <= 0 || (cacheSnapshotId.isBlank() && safeSnapshotId.isBlank())) {
            clearLocalPlan()
            return
        }
        if (rawV2.isBlank()) persistCleanPlan()
        restoredPlanNeedsValidation = true
        screenState = screenState.copy(
            phase = "已恢复清理计划 ${cleanPlanId.take(8)}，连接 Root 引擎后恢复事务断点",
            totalSafe = total,
            cleanReady = true,
            scanCompleted = true,
            resumable = resumable,
            runCount = runCount,
            deletedBytes = deletedBytes,
            processedCandidates = processedCandidates,
            cleanedCandidates = cleanedCandidates,
            changedCandidates = changedCandidates,
            protectedCandidates = protectedCandidates,
            partialCandidates = partialCandidates,
            failedCandidates = failedCandidates,
            classifiedDeletedBytes = classifiedDeletedBytes,
            unattributedDeletedBytes = unattributedDeletedBytes,
            categoryStats = categoryStats.toString(),
            riskStats = riskStats.toString(),
            failures = cumulativeFailures,
            cacheSummary = plan.optString("cacheSummary", "剩余 $cacheCount 项"),
            safeSummary = plan.optString("safeSummary", "剩余 $safeCount 项")
        )
    }

    private fun validateRestoredPlan() {
        val cache = cacheService ?: return
        val plans = planService ?: return
        val transactions = resumeService ?: return
        if (!restoredPlanNeedsValidation || validationRunning) return
        validationRunning = true
        lifecycleScope.launch {
            try {
                if (runCount > 0) {
                    val recovered = withContext(Dispatchers.IO) {
                        runCatching { JSONObject(transactions.recover(cleanPlanId)) }.getOrNull()
                    }
                    if (recovered != null && !recovered.has("error")) applyTransaction(recovered)
                }
                val (cachePage, safePage) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val cacheJob = async {
                            if (cacheSnapshotId.isBlank()) null else runCatching {
                                JSONObject(cache.getResultPage(cacheSnapshotId, 0, 1))
                            }.getOrNull()
                        }
                        val safeJob = async {
                            if (safeSnapshotId.isBlank()) null else runCatching {
                                JSONObject(plans.getPage(safeSnapshotId, 0, 1))
                            }.getOrNull()
                        }
                        cacheJob.await() to safeJob.await()
                    }
                }
                cacheCount = cachePage?.takeIf { !it.has("error") }?.optInt("total", 0)?.coerceAtLeast(0) ?: 0
                safeCount = safePage?.takeIf { !it.has("error") }?.optInt("total", 0)?.coerceAtLeast(0) ?: 0
                if (cacheCount <= 0) cacheSnapshotId = ""
                if (safeCount <= 0) safeSnapshotId = ""
                val remaining = cacheCount + safeCount
                resumable = runCount > 0 && remaining > 0
                if (remaining <= 0) {
                    withContext(Dispatchers.IO) { runCatching { transactions.finish(cleanPlanId) } }
                    clearLocalPlan()
                    screenState = screenState.copy(
                        phase = "已保存的清理计划已经全部完成或失效，请重新扫描",
                        totalSafe = 0,
                        cleanReady = false,
                        scanCompleted = false,
                        resumable = false
                    )
                } else {
                    persistCleanPlan()
                    screenState = screenState.copy(
                        phase = if (resumable) {
                            "事务断点恢复完成 · 剩余 $remaining 项，可直接继续清理"
                        } else "清理计划 ${cleanPlanId.take(8)} 已恢复 · $remaining 项可直接清理",
                        totalSafe = remaining,
                        cleanReady = true,
                        scanCompleted = true,
                        resumable = resumable,
                        runCount = runCount,
                        deletedBytes = deletedBytes,
                        cleanedCandidates = cleanedCandidates,
                        failures = cumulativeFailures,
                        cacheSummary = "应用缓存剩余 $cacheCount 项",
                        safeSummary = "安全项目剩余 $safeCount 项"
                    )
                }
            } finally {
                validationRunning = false
                restoredPlanNeedsValidation = false
            }
        }
    }

    private fun applyTransaction(json: JSONObject) {
        cacheCount = json.optInt("cacheRemaining", cacheCount).coerceAtLeast(0)
        safeCount = json.optInt("safeRemaining", safeCount).coerceAtLeast(0)
        runCount = json.optInt("runCount", runCount).coerceAtLeast(0)
        deletedBytes = json.optLong("deletedBytes", deletedBytes).coerceAtLeast(0L)
        deletedFiles = json.optLong("deletedFiles", deletedFiles).coerceAtLeast(0L)
        deletedDirectories = json.optLong("deletedDirectories", deletedDirectories).coerceAtLeast(0L)
        processedCandidates = json.optInt("processedCandidates", processedCandidates).coerceAtLeast(0)
        cleanedCandidates = json.optInt("cleanedCandidates", cleanedCandidates).coerceAtLeast(0)
        changedCandidates = json.optInt("changedCandidates", changedCandidates).coerceAtLeast(0)
        protectedCandidates = json.optInt("protectedCandidates", protectedCandidates).coerceAtLeast(0)
        partialCandidates = json.optInt("partialCandidates", partialCandidates).coerceAtLeast(0)
        failedCandidates = json.optInt("failedCandidates", failedCandidates).coerceAtLeast(0)
        classifiedDeletedBytes = json.optLong("classifiedDeletedBytes", classifiedDeletedBytes).coerceAtLeast(0L)
        unattributedDeletedBytes = json.optLong("unattributedDeletedBytes", unattributedDeletedBytes).coerceAtLeast(0L)
        categoryStats = json.optJSONObject("categoryStats") ?: categoryStats
        riskStats = json.optJSONObject("riskStats") ?: riskStats
        cumulativeFailures = json.optInt("deleteErrors", json.optInt("failures", cumulativeFailures)).coerceAtLeast(0)
        resumable = json.optBoolean("resumable", cacheCount + safeCount > 0 && runCount > 0)
        screenState = screenState.copy(
            totalSafe = cacheCount + safeCount,
            resumable = resumable,
            runCount = runCount,
            deletedBytes = deletedBytes,
            processedCandidates = processedCandidates,
            cleanedCandidates = cleanedCandidates,
            changedCandidates = changedCandidates,
            protectedCandidates = protectedCandidates,
            partialCandidates = partialCandidates,
            failedCandidates = failedCandidates,
            classifiedDeletedBytes = classifiedDeletedBytes,
            unattributedDeletedBytes = unattributedDeletedBytes,
            categoryStats = categoryStats.toString(),
            riskStats = riskStats.toString(),
            failures = cumulativeFailures,
            cacheSummary = "应用缓存剩余 $cacheCount 项",
            safeSummary = "安全项目剩余 $safeCount 项"
        )
    }

    private fun persistCleanPlan() {
        val total = cacheCount + safeCount
        if (cleanPlanId.isBlank() || total <= 0 || (cacheSnapshotId.isBlank() && safeSnapshotId.isBlank())) return
        val plan = JSONObject()
            .put("version", CLEAN_PLAN_VERSION)
            .put("planId", cleanPlanId)
            .put("createdAt", cleanPlanCreatedAt)
            .put("optionsSha", sha256(optionsJson()))
            .put("cacheSnapshotId", cacheSnapshotId)
            .put("safeSnapshotId", safeSnapshotId)
            .put("cacheCount", cacheCount)
            .put("safeCount", safeCount)
            .put("originalCacheCount", originalCacheCount)
            .put("originalSafeCount", originalSafeCount)
            .put("runCount", runCount)
            .put("deletedBytes", deletedBytes)
            .put("deletedFiles", deletedFiles)
            .put("deletedDirectories", deletedDirectories)
            .put("processedCandidates", processedCandidates)
            .put("cleanedCandidates", cleanedCandidates)
            .put("changedCandidates", changedCandidates)
            .put("protectedCandidates", protectedCandidates)
            .put("partialCandidates", partialCandidates)
            .put("failedCandidates", failedCandidates)
            .put("classifiedDeletedBytes", classifiedDeletedBytes)
            .put("unattributedDeletedBytes", unattributedDeletedBytes)
            .put("categoryStats", categoryStats)
            .put("riskStats", riskStats)
            .put("deleteErrors", cumulativeFailures)
            .put("failures", cumulativeFailures)
            .put("resumable", resumable)
            .put("cacheSummary", screenState.cacheSummary)
            .put("safeSummary", screenState.safeSummary)
        preferences.edit()
            .putString(CLEAN_PLAN_KEY, plan.toString())
            .remove(LEGACY_PLAN_KEY)
            .apply()
    }

    private fun cleanPlanCurrent(): Boolean {
        val age = System.currentTimeMillis() - cleanPlanCreatedAt
        if (cleanPlanId.isBlank() || cleanPlanCreatedAt <= 0L || age !in 0..CLEAN_PLAN_TTL_MS) return false
        val plan = runCatching { JSONObject(preferences.getString(CLEAN_PLAN_KEY, null).orEmpty()) }.getOrNull()
            ?: return false
        return plan.optString("planId") == cleanPlanId && plan.optString("optionsSha") == sha256(optionsJson())
    }

    private fun optionsJson(): String {
        val packages = preferences.getStringSet("package_whitelist", emptySet()).orEmpty().toList().sorted()
        val paths = preferences.getStringSet("path_whitelist", emptySet()).orEmpty().toList().sorted()
        val maxMb = preferences.getFloat("large_file_mb", 512f).toLong().coerceIn(64L, 16_384L)
        return JSONObject()
            .put("whitelistPackages", JSONArray(packages))
            .put("whitelistPaths", JSONArray(paths))
            .put("maxFileBytes", maxMb * 1024L * 1024L)
            .put("fragmentDays", preferences.getInt("fragment_days", 7).coerceIn(0, 365))
            .put("allowHighRisk", false)
            .toString()
    }

    private fun throwableJson(error: Throwable): JSONObject = JSONObject()
        .put("error", "binder_failed")
        .put("message", error.message ?: error.javaClass.simpleName)
        .put("cancelled", false)
        .put("timedOut", false)

    private fun clearLocalPlan() {
        preferences.edit().remove(CLEAN_PLAN_KEY).remove(LEGACY_PLAN_KEY).apply()
        cacheSnapshotId = ""
        safeSnapshotId = ""
        cacheCount = 0
        safeCount = 0
        cleanPlanId = ""
        cleanPlanCreatedAt = 0L
        resumable = false
        restoredPlanNeedsValidation = false
        validationRunning = false
    }

    private fun resetPlanFields() {
        cacheSnapshotId = ""
        safeSnapshotId = ""
        cacheCount = 0
        safeCount = 0
        originalCacheCount = 0
        originalSafeCount = 0
        cleanPlanId = ""
        cleanPlanCreatedAt = 0L
        runCount = 0
        deletedBytes = 0L
        deletedFiles = 0L
        deletedDirectories = 0L
        processedCandidates = 0
        cleanedCandidates = 0
        changedCandidates = 0
        protectedCandidates = 0
        partialCandidates = 0
        failedCandidates = 0
        classifiedDeletedBytes = 0L
        unattributedDeletedBytes = 0L
        categoryStats = JSONObject()
        riskStats = JSONObject()
        cumulativeFailures = 0
        resumable = false
        restoredPlanNeedsValidation = false
        validationRunning = false
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    override fun onDestroy() {
        pollJob?.cancel()
        if (cacheBindingRequested) runCatching { RootService.unbind(cacheConnection) }
        if (planBindingRequested) runCatching { RootService.unbind(planConnection) }
        if (resumeBindingRequested) runCatching { RootService.unbind(resumeConnection) }
        super.onDestroy()
    }

    companion object {
        private const val CLEAN_PLAN_KEY = "smart_clean_plan_v2"
        private const val LEGACY_PLAN_KEY = "smart_clean_plan_v1"
        private const val CLEAN_PLAN_VERSION = 2
        private const val CLEAN_PLAN_TTL_MS = 30L * 60_000L
    }
}

internal data class ResumeSmartUiState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val operation: String = "",
    val status: String = "正在连接 Root 引擎…",
    val phase: String = "连接完成后可开始智能扫描",
    val totalSafe: Int = 0,
    val cleanReady: Boolean = false,
    val scanCompleted: Boolean = false,
    val resumable: Boolean = false,
    val runCount: Int = 0,
    val deletedBytes: Long = 0L,
    val processedCandidates: Int = 0,
    val cleanedCandidates: Int = 0,
    val changedCandidates: Int = 0,
    val protectedCandidates: Int = 0,
    val partialCandidates: Int = 0,
    val failedCandidates: Int = 0,
    val classifiedDeletedBytes: Long = 0L,
    val unattributedDeletedBytes: Long = 0L,
    val categoryStats: String = "{}",
    val riskStats: String = "{}",
    val failures: Int = 0,
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val cacheSummary: String = "等待扫描",
    val safeSummary: String = "等待扫描"
)

@Composable
internal fun ResumeSmartScreen(
    state: ResumeSmartUiState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onClean: () -> Unit,
    onStop: () -> Unit,
    onReconnect: () -> Unit
) {
    val context = LocalContext.current
    val progress = if (state.progressTotal > 0) {
        (state.progressCurrent.toFloat() / state.progressTotal.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val scheme = MaterialTheme.colorScheme
    val metric = when {
        state.running && state.operation == "scan" -> "正在扫描"
        state.running -> "正在清理"
        state.cleanReady -> "${state.totalSafe} 项"
        state.runCount > 0 -> Formatter.formatFileSize(context, state.deletedBytes)
        state.scanCompleted -> "扫描完成"
        !state.connected -> "等待连接"
        else -> "准备扫描"
    }
    val metricLabel = when {
        state.running -> "当前任务"
        state.cleanReady && state.resumable -> "剩余待清理"
        state.cleanReady -> "可清理项目"
        state.runCount > 0 -> "本次已释放"
        else -> "断点续清"
    }
    val executionDetails = buildString {
        append("执行 ${state.runCount} 次 · 授权 ${state.totalSafe + state.processedCandidates} 项")
        append("\n已处理 ${state.processedCandidates} 项 · 实际清理 ${state.cleanedCandidates} 项")
        append("\n文件变化 ${state.changedCandidates} 项 · 受保护 ${state.protectedCandidates} 项")
        append("\n部分完成 ${state.partialCandidates} 项 · 失败 ${state.failedCandidates} 项")
        if (state.unattributedDeletedBytes > 0L) {
            append("\n${Formatter.formatFileSize(context, state.unattributedDeletedBytes)} 已计入释放量，尚无逐项分类。")
        }
        if (state.failures > 0) append("\n累计失败记录 ${state.failures} 项，未完成的项目仍保留在计划中。")
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BaiZeTokens.colors.surfaceBase),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item(contentType = "header") {
            DetailPageHeader("断点续清", "中断后，可从剩余项目继续", onBack)
        }
        item(contentType = "task") {
            DetailGlassPanel {
                Text(metricLabel, fontSize = 12.sp, color = scheme.onSurfaceVariant)
                Text(metric, Modifier.padding(top = 4.dp), fontSize = if (state.cleanReady || state.runCount > 0) 28.sp else 22.sp,
                    lineHeight = 34.sp, fontWeight = FontWeight.Medium, color = scheme.onSurface)
                DetailStatusText(state.phase, Modifier.padding(top = 5.dp, bottom = 14.dp))
                if (state.running) {
                    if (state.progressTotal > 0) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("${state.progressCurrent.coerceAtMost(state.progressTotal)} / ${state.progressTotal}",
                            Modifier.padding(top = 5.dp), fontSize = 12.sp, color = scheme.onSurfaceVariant)
                    } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    GlassActionButton("停止并保存", onStop, Modifier.fillMaxWidth().padding(top = 12.dp),
                        icon = Icons.Rounded.Stop, secondary = true)
                } else if (state.cleanReady) {
                    GlassActionButton(if (state.resumable) "继续清理" else "清理这 ${state.totalSafe} 项",
                        onClean, Modifier.fillMaxWidth(), icon = Icons.Rounded.DeleteSweep, enabled = state.connected)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (!state.connected) TextButton(onClick = onReconnect) { Text("重新连接", fontSize = 13.sp) }
                        TextButton(onClick = onScan, enabled = state.connected) { Text("重新扫描", fontSize = 13.sp) }
                    }
                } else if (!state.connected) {
                    GlassActionButton("重新连接", onReconnect, Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh, secondary = true)
                } else {
                    GlassActionButton("开始扫描", onScan, Modifier.fillMaxWidth(), icon = Icons.Rounded.Search)
                }
            }
        }
        if (state.scanCompleted || state.runCount > 0 || state.running) {
            item(contentType = "sources-title") { DetailSectionHeader("清理范围") }
            item(contentType = "sources") {
                DetailGlassPanel {
                    ResumeSummaryRow("应用缓存", state.cacheSummary)
                    HorizontalDivider(Modifier.padding(vertical = 11.dp), color = scheme.onSurface.copy(alpha = .055f))
                    ResumeSummaryRow("安全项目", state.safeSummary)
                }
            }
        }
        if (state.runCount > 0) {
            item(contentType = "progress-title") { DetailSectionHeader("本次进度") }
            item(contentType = "progress") {
                DetailGlassPanel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("已清理", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                            Text("${state.cleanedCandidates} 项", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = scheme.onSurface)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("已释放", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                            Text(Formatter.formatFileSize(context, state.deletedBytes), fontSize = 20.sp, fontWeight = FontWeight.Medium, color = scheme.onSurface)
                        }
                    }
                    if (state.failedCandidates > 0 || state.partialCandidates > 0) {
                        Text("部分完成 ${state.partialCandidates} 项 · 失败 ${state.failedCandidates} 项",
                            Modifier.padding(top = 10.dp), color = scheme.error, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
            item(contentType = "execution-details") { DetailExpandableText("处理明细", executionDetails) }
            item(contentType = "category-details") { DetailExpandableText("按类别查看", formatMetricBuckets(state.categoryStats)) }
            item(contentType = "risk-details") { DetailExpandableText("按风险查看", formatMetricBuckets(state.riskStats)) }
        }
        item(contentType = "status-details") {
            DetailExpandableText("任务详情", state.status + "\n\n" + state.phase)
        }
        item(contentType = "help") {
            DetailExpandableText("断点续清说明", "扫描后会保存清理计划。任务停止或意外中断时，可继续处理剩余项目，已经完成的项目不会重复清理。\n\n清理前会核对路径、白名单和文件状态。部分完成、失败和未执行的项目会保留；计划失效或设置发生变化时需要重新扫描。")
        }
        item(contentType = "bottom-inset") { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
private fun ResumeSummaryRow(title: String, summary: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        DetailStatusText(summary)
    }
}

private fun formatMetricBuckets(raw: String): String {
    val root = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    if (root.length() == 0) return "尚无已处理项目"
    val labels = mapOf(
        "cache" to "应用缓存",
        "empty" to "空项目",
        "rules" to "规则垃圾",
        "fragment" to "残留碎片",
        "other" to "其他",
        "low" to "低风险",
        "medium" to "中风险",
        "high" to "高风险",
        "critical" to "关键风险"
    )
    val lines = ArrayList<String>()
    val keys = root.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val bucket = root.optJSONObject(key) ?: continue
        lines += buildString {
            append(labels[key] ?: key)
            append("：处理 ").append(bucket.optInt("processed"))
            append(" · 清理 ").append(bucket.optInt("cleaned"))
            val changed = bucket.optInt("changed")
            val protected = bucket.optInt("protected")
            val partial = bucket.optInt("partial")
            val failed = bucket.optInt("failed")
            if (changed > 0) append(" · 变化 ").append(changed)
            if (protected > 0) append(" · 保护 ").append(protected)
            if (partial > 0) append(" · 部分 ").append(partial)
            if (failed > 0) append(" · 失败 ").append(failed)
        }
    }
    return lines.joinToString("\n")
}
