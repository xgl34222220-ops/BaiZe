package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.ui.components.BaiZeDialog
import io.github.xgl34222220.baize.ui.components.BaiZeDialogButton
import io.github.xgl34222220.baize.root.RootServiceClients
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.provider.Settings
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Scaffold
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import java.io.File
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
    private var apkSnapshot: List<SmartApkSnapshot> = emptyList()
    private var cacheCount = 0
    private var safeCount = 0
    private var apkCount = 0
    private var originalCacheCount = 0
    private var originalSafeCount = 0
    private var originalApkCount = 0
    private var apkBytes = 0L
    private var cleanPlanId = ""
    private var cleanPlanCreatedAt = 0L
    private var estimatedBytes = 0L
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
                        onReconnect = ::bindServices,
                        onToggleCategory = ::toggleCategory,
                        onToggleAll = ::toggleAllCategories
                    )
                    if (showCleanConfirm) {
                        BaiZeDialog(
                            onDismissRequest = { showCleanConfirm = false },
                            title = {
                                Text(if (resumable) "确认继续清理？" else "确认执行清理？")
                            },
                            text = {
                                val estimate = if (screenState.selectedBytes != null) {
                                    Formatter.formatFileSize(this@ResumableSmartScanActivity, screenState.selectedBytes!!)
                                } else null
                                Text(
                                    buildString {
                                        if (estimate != null) append("预计释放 ").append(estimate).append("。")
                                        else append("剩余项目容量将在执行后确认。")
                                        append("已选择 ${screenState.selectedCount} 项。")
                                        append("清理前会再次验证白名单、路径与文件状态。")
                                        if (resumable) append(" 已完成项目不会重复处理。")
                                    }
                                )
                            },
                            confirmButton = {
                                BaiZeDialogButton(onClick = {
                                    showCleanConfirm = false
                                    cleanSnapshots()
                                }) { Text(if (resumable) "继续清理" else "立即清理") }
                            },
                            dismissButton = {
                                BaiZeDialogButton(onClick = { showCleanConfirm = false }) { Text("取消") }
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
                screenState = screenState.copy(phase = "缓存服务连接失败，请重试")
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
                screenState = screenState.copy(phase = "扫描服务连接失败，请重试")
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
                screenState = screenState.copy(phase = "续清服务连接失败，请重试")
            }
        }
        updateConnectionState()
    }

    private fun updateConnectionState() {
        val readyCount = listOf(cacheService, planService, resumeService).count { it != null }
        screenState = screenState.copy(
            connected = readyCount == 3,
            status = if (readyCount == 3) "清理服务已就绪" else "正在连接清理服务…"
        )
        if (readyCount == 3 && restoredPlanNeedsValidation && !validationRunning) validateRestoredPlan()
    }

    private fun startSmartScan() {
        if (screenState.running) return
        if (!ApkMediaStoreIndex.hasAllFilesAccess()) {
            screenState = screenState.copy(
                phase = "需要“所有文件访问”才能完成安装包、大文件和存储扫描"
            )
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            return
        }
        val cache = cacheService
        val plans = planService
        val transactions = resumeService
        if (cache == null || plans == null || transactions == null) {
            screenState = screenState.copy(phase = "清理服务尚未就绪，正在重新连接…")
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
            status = "清理服务已就绪",
            phase = "正在扫描可清理内容…",
            progressCurrent = 0,
            progressTotal = 3,
            cacheSummary = "正在扫描",
            apkSummary = "正在扫描",
            safeSummary = "正在扫描"
        )
        startPolling()

        lifecycleScope.launch {
            try {
                val whitelist = preferences.getStringSet("package_whitelist", emptySet()).orEmpty()
                val options = optionsJson()
                val scanStarted = SystemClock.elapsedRealtime()
                val scanBundle = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val cacheJob = async {
                            JSONObject(cache.scanCandidates(JSONArray(whitelist.toList().sorted()).toString()))
                        }
                        val safeJob = async { JSONObject(plans.scanSafe(options)) }
                        val apkJob = async { scanApkForSmartClean() }
                        Triple(cacheJob.await(), safeJob.await(), apkJob.await())
                    }
                }
                val cacheJson = scanBundle.first
                val safeJson = scanBundle.second
                val apkResult = scanBundle.third
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
                apkSnapshot = apkResult.items
                apkCount = apkSnapshot.size
                apkBytes = apkSnapshot.sumOf { it.bytes }
                originalCacheCount = cacheCount
                originalSafeCount = safeCount
                originalApkCount = apkCount
                cleanPlanId = UUID.randomUUID().toString()
                cleanPlanCreatedAt = System.currentTimeMillis()
                persistApkSnapshot()

                val total = cacheCount + safeCount + apkCount
                val cacheBytes = cacheJson.optLong("totalBytes", 0L).coerceAtLeast(0L)
                val safeBytes = safeJson.optLong("knownBytes", 0L).coerceAtLeast(0L)
                estimatedBytes = cacheBytes + safeBytes + apkBytes
                val cancelled = cacheJson.optBoolean("cancelled") || safeJson.optBoolean("cancelled")
                val ready = !cancelled && total > 0 &&
                    (cacheSnapshotId.isNotBlank() || safeSnapshotId.isNotBlank() || apkCount > 0)
                val totalElapsed = (SystemClock.elapsedRealtime() - scanStarted).coerceAtLeast(0L)
                screenState = screenState.copy(
                    running = false,
                    operation = "",
                    phase = if (cancelled) {
                        "扫描已停止"
                    } else {
                        "扫描完成 · 发现 $total 项可清理内容 · ${totalElapsed} ms"
                    },
                    totalSafe = total,
                    cacheCount = cacheCount, safeCount = safeCount, apkCount = apkCount,
                    cleanReady = ready,
                    scanCompleted = !cancelled,
                    resumable = false,
                    estimatedBytes = estimatedBytes,
                    cacheBytes = cacheBytes, safeBytes = safeBytes, apkBytes = apkBytes,
                    progressCurrent = 3,
                    progressTotal = 3,
                    cacheSummary = if (cacheJson.has("error")) {
                        cacheJson.optString("message", "缓存扫描失败")
                    } else {
                        buildString {
                            append("$cacheCount 项")
                            if (cacheBytes > 0L) append(" · ").append(Formatter.formatFileSize(this@ResumableSmartScanActivity, cacheBytes))
                        }
                    },
                    apkSummary = buildString {
                        if (apkResult.error.isNotBlank()) append(apkResult.error)
                        else {
                            append("$apkCount 个")
                            if (apkBytes > 0L) append(" · ").append(Formatter.formatFileSize(this@ResumableSmartScanActivity, apkBytes))
                            append(" · ${apkResult.elapsedMs} ms")
                        }
                    },
                    cacheSelected = cacheCount > 0,
                    apkSelected = apkCount > 0,
                    safeSelected = safeCount > 0,
                    safeSummary = if (safeJson.has("error")) {
                        safeJson.optString("message", "安全项目扫描失败")
                    } else {
                        buildString {
                            append("$safeCount 项")
                            if (safeBytes > 0L) append(" · ").append(Formatter.formatFileSize(this@ResumableSmartScanActivity, safeBytes))
                            append(" · 空项目 ${safeJson.optInt("emptyFiles") + safeJson.optInt("emptyDirs")}")
                            append(" · 规则 ${safeJson.optInt("ruleTargets")}")
                            append(" · 碎片 ${safeJson.optInt("fragmentFiles")}")
                        }
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

    private fun toggleCategory(category: SmartCleanCategory) {
        if (screenState.running || !screenState.cleanReady) return
        screenState = when (category) {
            SmartCleanCategory.CACHE -> screenState.copy(cacheSelected = cacheCount > 0 && !screenState.cacheSelected)
            SmartCleanCategory.APK -> screenState.copy(apkSelected = apkCount > 0 && !screenState.apkSelected)
            SmartCleanCategory.SAFE -> screenState.copy(safeSelected = safeCount > 0 && !screenState.safeSelected)
        }
        persistCleanPlan()
    }

    private fun toggleAllCategories() {
        if (screenState.running || !screenState.cleanReady) return
        screenState = screenState.selectAll(!screenState.allSelected)
        persistCleanPlan()
    }

    private fun cleanSnapshots() {
        if (screenState.running) return
        val totalBefore =
            (if (screenState.cacheSelected) cacheCount else 0) +
            (if (screenState.safeSelected) safeCount else 0) +
            (if (screenState.apkSelected) apkCount else 0)
        if (!screenState.cleanReady || totalBefore <= 0 || !cleanPlanCurrent()) {
            screenState = screenState.copy(phase = "清理计划已过期、失效或设置已变化，请重新扫描")
            return
        }
        val cache = cacheService
        val plans = planService
        val transactions = resumeService
        if (cache == null || plans == null || transactions == null) {
            screenState = screenState.copy(phase = "清理服务尚未就绪")
            bindServices()
            return
        }

        val cleanStarted = SystemClock.elapsedRealtime()
        val beforeDeletedBytes = deletedBytes
        val beforeDeletedFiles = deletedFiles
        val beforeCleanedCandidates = cleanedCandidates

        screenState = screenState.copy(
            running = true,
            operation = "clean",
            cacheBytes = null, safeBytes = null,
            phase = if (resumable) "正在继续清理剩余项目…" else "正在准备清理…",
            progressCurrent = 0,
            progressTotal = totalBefore.coerceAtLeast(1)
        )
        startPolling()

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

                val apkJob = if (screenState.apkSelected && apkCount > 0 && apkSnapshot.isNotEmpty()) {
                    screenState = screenState.copy(phase = "正在并行清理安装包与其他垃圾")
                    async(Dispatchers.IO) { cleanApkForSmartClean() }
                } else null

                if (screenState.cacheSelected && cacheSnapshotId.isNotBlank() && cacheCount > 0) {
                    screenState = screenState.copy(phase = "正在清理应用缓存")
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

                if (!interrupted && screenState.safeSelected && safeSnapshotId.isNotBlank() && safeCount > 0) {
                    screenState = screenState.copy(phase = "正在清理安全项目")
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

                val apkResult = apkJob?.await() ?: SmartApkCleanResult.EMPTY
                if (apkResult.processed > 0) {
                    mergeApkMetrics(apkResult)
                    persistCleanPlan()
                    screenState = screenState.copy(
                        apkSummary = if (apkCount > 0) "安装包剩余 $apkCount 个" else
                            "安装包清理完成 · ${apkResult.elapsedMs} ms"
                    )
                }

                val remaining = cacheCount + safeCount + apkCount
                val report = buildString {
                    append(if (remaining > 0) {
                        if (interrupted) "清理已安全停止" else "清理部分完成"
                    } else "清理完成")
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

                val runReleased = (deletedBytes - beforeDeletedBytes).coerceAtLeast(0L)
                val runFiles = (deletedFiles - beforeDeletedFiles).coerceAtLeast(0L)
                val runCleaned = (cleanedCandidates - beforeCleanedCandidates).coerceAtLeast(0)
                val runElapsed = (SystemClock.elapsedRealtime() - cleanStarted).coerceAtLeast(0L)
                val historyCategories = historyCategoriesForRun()
                AppTaskHistoryStore.append(
                    context = this@ResumableSmartScanActivity,
                    title = if (remaining > 0) "一键清理（部分完成）" else "一键清理",
                    result = report,
                    bytes = runReleased,
                    files = maxOf(runFiles.toInt(), runCleaned),
                    elapsedMs = runElapsed,
                    categories = historyCategories,
                    cleaned = runReleased > 0L || runCleaned > 0
                )
                LastCleanupStore.save(
                    this@ResumableSmartScanActivity,
                    emptyList(),
                    historyCategories.map { item ->
                        GeneralJunkUiItem(
                            name = item.name,
                            files = item.files,
                            bytes = item.bytes,
                            errors = 0,
                            samplePath = ""
                        )
                    }
                )

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
                    cacheCount = cacheCount, safeCount = safeCount, apkCount = apkCount,
                    cacheSelected = screenState.cacheSelected && cacheCount > 0,
                    apkSelected = screenState.apkSelected && apkCount > 0,
                    safeSelected = screenState.safeSelected && safeCount > 0,
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
                    progressCurrent = (originalCacheCount + originalSafeCount + originalApkCount - remaining).coerceAtLeast(0),
                    progressTotal = (originalCacheCount + originalSafeCount + originalApkCount).coerceAtLeast(1),
                    cacheBytes = if (cacheCount == 0) 0L else null,
                    safeBytes = if (safeCount == 0) 0L else null,
                    apkBytes = apkBytes,
                    cacheSummary = if (cacheCount > 0) "应用缓存剩余 $cacheCount 项" else "应用缓存清理完成",
                    apkSummary = if (apkCount > 0) "安装包剩余 $apkCount 个" else "安装包清理完成",
                    safeSummary = if (safeCount > 0) "安全项目剩余 $safeCount 项" else "安全项目清理完成"
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
                val remaining = cacheCount + safeCount + apkCount
                resumable = remaining > 0
                if (remaining > 0) persistCleanPlan()
                screenState = screenState.copy(
                    running = false,
                    operation = "",
                    phase = "清理已中断\n已保存剩余 $remaining 项，可直接继续清理",
                    totalSafe = remaining,
                    cacheCount = cacheCount, safeCount = safeCount, apkCount = apkCount,
                    cacheSelected = screenState.cacheSelected && cacheCount > 0,
                    apkSelected = screenState.apkSelected && apkCount > 0,
                    safeSelected = screenState.safeSelected && safeCount > 0,
                    cleanReady = remaining > 0,
                    scanCompleted = remaining > 0,
                    resumable = remaining > 0,
                    runCount = runCount,
                    deletedBytes = deletedBytes,
                    cleanedCandidates = cleanedCandidates,
                    failures = cumulativeFailures,
                    cacheBytes = if (cacheCount == 0) 0L else null,
            safeBytes = if (safeCount == 0) 0L else null,
            apkBytes = apkBytes,
            cacheSummary = "应用缓存剩余 $cacheCount 项",
                    apkSummary = "安装包剩余 $apkCount 个",
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
        screenState = screenState.copy(phase = "正在安全停止；完成当前项目后保存进度…")
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
        apkSnapshot = loadApkSnapshot(cleanPlanId)
        cacheCount = plan.optInt("cacheCount").coerceAtLeast(0)
        safeCount = plan.optInt("safeCount").coerceAtLeast(0)
        apkCount = apkSnapshot.size
        apkBytes = apkSnapshot.sumOf { it.bytes }
        originalCacheCount = plan.optInt("originalCacheCount", cacheCount).coerceAtLeast(cacheCount)
        originalSafeCount = plan.optInt("originalSafeCount", safeCount).coerceAtLeast(safeCount)
        originalApkCount = plan.optInt("originalApkCount", apkCount).coerceAtLeast(apkCount)
        estimatedBytes = plan.optLong("estimatedBytes", 0L).coerceAtLeast(0L)
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
        val total = cacheCount + safeCount + apkCount
        if (cleanPlanId.isBlank() || total <= 0 || (cacheSnapshotId.isBlank() && safeSnapshotId.isBlank() && apkCount <= 0)) {
            clearLocalPlan()
            return
        }
        restoredPlanNeedsValidation = true
        screenState = screenState.copy(
            phase = "已恢复上次清理进度，连接服务后可继续",
            totalSafe = total,
            cacheCount = cacheCount, safeCount = safeCount, apkCount = apkCount,
            cleanReady = true,
            scanCompleted = true,
            resumable = resumable,
            estimatedBytes = estimatedBytes,
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
            apkSummary = plan.optString("apkSummary", "剩余 $apkCount 个"),
            safeSummary = plan.optString("safeSummary", "剩余 $safeCount 项"),
            cacheBytes = if (plan.has("cacheBytes")) plan.optLong("cacheBytes") else null,
            safeBytes = if (plan.has("safeBytes")) plan.optLong("safeBytes") else null,
            apkBytes = apkBytes,
            cacheSelected = cacheCount > 0 && plan.optBoolean("cacheSelected", true),
            apkSelected = apkCount > 0 && plan.optBoolean("apkSelected", true),
            safeSelected = safeCount > 0 && plan.optBoolean("safeSelected", true)
        )
        if (rawV2.isBlank()) persistCleanPlan()
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
                apkSnapshot = loadApkSnapshot(cleanPlanId)
                apkCount = apkSnapshot.size
                apkBytes = apkSnapshot.sumOf { it.bytes }
                if (cacheCount <= 0) cacheSnapshotId = ""
                if (safeCount <= 0) safeSnapshotId = ""
                val remaining = cacheCount + safeCount + apkCount
                resumable = runCount > 0 && remaining > 0
                if (remaining <= 0) {
                    withContext(Dispatchers.IO) { runCatching { transactions.finish(cleanPlanId) } }
                    clearLocalPlan()
                    screenState = screenState.copy(
                        phase = "已保存的清理计划已经全部完成或失效，请重新扫描",
                        totalSafe = 0,
                        cacheCount = cacheCount, safeCount = safeCount, apkCount = apkCount,
                        cacheSelected = screenState.cacheSelected && cacheCount > 0,
                        apkSelected = screenState.apkSelected && apkCount > 0,
                        safeSelected = screenState.safeSelected && safeCount > 0,
                        cleanReady = false,
                        scanCompleted = false,
                        resumable = false
                    )
                } else {
                    persistCleanPlan()
                    screenState = screenState.copy(
                        phase = if (resumable) {
                            "已恢复上次清理进度 · 剩余 $remaining 项"
                        } else "已恢复上次扫描结果 · $remaining 项可清理",
                        totalSafe = remaining,
                        cacheCount = cacheCount, safeCount = safeCount, apkCount = apkCount,
                        cacheSelected = screenState.cacheSelected && cacheCount > 0,
                        apkSelected = screenState.apkSelected && apkCount > 0,
                        safeSelected = screenState.safeSelected && safeCount > 0,
                        cleanReady = true,
                        scanCompleted = true,
                        resumable = resumable,
                        estimatedBytes = estimatedBytes,
                        runCount = runCount,
                        deletedBytes = deletedBytes,
                        cleanedCandidates = cleanedCandidates,
                        failures = cumulativeFailures,
                        cacheBytes = if (cacheCount == 0) 0L else null,
                        safeBytes = if (safeCount == 0) 0L else null,
                        apkBytes = apkBytes,
                        cacheSummary = "应用缓存剩余 $cacheCount 项",
                        apkSummary = "安装包剩余 $apkCount 个",
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
        resumable = json.optBoolean("resumable", cacheCount + safeCount + apkCount > 0 && runCount > 0)
        screenState = screenState.copy(
            totalSafe = cacheCount + safeCount + apkCount,
            cacheCount = cacheCount, safeCount = safeCount, apkCount = apkCount,
            cacheSelected = screenState.cacheSelected && cacheCount > 0,
            apkSelected = screenState.apkSelected && apkCount > 0,
            safeSelected = screenState.safeSelected && safeCount > 0,
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
            cacheBytes = if (cacheCount == 0) 0L else null,
            safeBytes = if (safeCount == 0) 0L else null,
            apkBytes = apkBytes,
            cacheSummary = "应用缓存剩余 $cacheCount 项",
            apkSummary = "安装包剩余 $apkCount 个",
            safeSummary = "安全项目剩余 $safeCount 项"
        )
    }

    private fun persistCleanPlan() {
        val total = cacheCount + safeCount + apkCount
        if (cleanPlanId.isBlank() || total <= 0 || (cacheSnapshotId.isBlank() && safeSnapshotId.isBlank() && apkCount <= 0)) return
        val plan = JSONObject()
            .put("version", CLEAN_PLAN_VERSION)
            .put("planId", cleanPlanId)
            .put("createdAt", cleanPlanCreatedAt)
            .put("optionsSha", sha256(optionsJson()))
            .put("cacheSnapshotId", cacheSnapshotId)
            .put("safeSnapshotId", safeSnapshotId)
            .put("cacheSelected", screenState.cacheSelected)
            .put("apkSelected", screenState.apkSelected)
            .put("safeSelected", screenState.safeSelected)
            .put("cacheBytes", screenState.cacheBytes)
            .put("safeBytes", screenState.safeBytes)
            .put("cacheCount", cacheCount)
            .put("safeCount", safeCount)
            .put("apkCount", apkCount)
            .put("apkBytes", apkBytes)
            .put("originalCacheCount", originalCacheCount)
            .put("originalSafeCount", originalSafeCount)
            .put("originalApkCount", originalApkCount)
            .put("estimatedBytes", estimatedBytes)
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
            .put("apkSummary", screenState.apkSummary)
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

    private fun scanApkForSmartClean(): SmartApkScanResult {
        val started = SystemClock.elapsedRealtime()
        if (!ApkMediaStoreIndex.hasAllFilesAccess()) {
            return SmartApkScanResult(
                items = emptyList(),
                elapsedMs = 0L,
                error = "安装包未扫描：需要开启“所有文件访问”"
            )
        }
        val indexed = ApkMediaStoreIndex.query(applicationContext)
        if (indexed.error != null) {
            return SmartApkScanResult(
                items = emptyList(),
                elapsedMs = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L),
                error = "安装包扫描失败：${indexed.error}"
            )
        }
        val items = indexed.candidates.map { candidate ->
            SmartApkSnapshot(
                uri = candidate.uri,
                path = candidate.path,
                name = candidate.name,
                bytes = candidate.bytes,
                modifiedSeconds = candidate.modifiedSeconds
            )
        }
        return SmartApkScanResult(
            items = items,
            elapsedMs = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L),
            error = ""
        )
    }

    private fun cleanApkForSmartClean(): SmartApkCleanResult {
        if (apkSnapshot.isEmpty()) return SmartApkCleanResult.EMPTY
        val started = SystemClock.elapsedRealtime()
        val remaining = ArrayList<SmartApkSnapshot>()
        var deleted = 0
        var deletedBytesNow = 0L
        var changed = 0
        var failed = 0
        apkSnapshot.forEach { item ->
            when (ApkMediaStoreIndex.deleteIfUnchanged(
                context = applicationContext,
                uriString = item.uri,
                expectedPath = item.path,
                expectedBytes = item.bytes,
                expectedModifiedSeconds = item.modifiedSeconds
            )) {
                ApkIndexedDeleteResult.DELETED -> {
                    deleted += 1
                    deletedBytesNow += item.bytes
                }
                ApkIndexedDeleteResult.CHANGED -> {
                    changed += 1
                    remaining += item
                }
                ApkIndexedDeleteResult.FAILED -> {
                    failed += 1
                    remaining += item
                }
            }
        }
        apkSnapshot = remaining
        apkCount = remaining.size
        apkBytes = remaining.sumOf { it.bytes }
        persistApkSnapshot()
        return SmartApkCleanResult(
            deleted = deleted,
            deletedBytes = deletedBytesNow,
            changed = changed,
            failed = failed,
            elapsedMs = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
        )
    }

    private fun apkSnapshotFile(planId: String = cleanPlanId): File? {
        if (planId.isBlank()) return null
        val dir = File(filesDir, "smart-apk-plans").apply { mkdirs() }
        return File(dir, "$planId.json")
    }

    private fun persistApkSnapshot() {
        val target = apkSnapshotFile() ?: return
        if (apkSnapshot.isEmpty()) {
            target.delete()
            return
        }
        val payload = JSONObject()
            .put("createdAt", cleanPlanCreatedAt)
            .put("items", JSONArray().apply {
                apkSnapshot.forEach { item ->
                    put(JSONObject()
                        .put("uri", item.uri)
                        .put("path", item.path)
                        .put("name", item.name)
                        .put("bytes", item.bytes)
                        .put("modifiedSeconds", item.modifiedSeconds))
                }
            })
            .toString()
        val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        runCatching {
            temp.writeText(payload)
            if (!temp.renameTo(target)) {
                target.writeText(payload)
                temp.delete()
            }
        }.onFailure { temp.delete() }
    }

    private fun loadApkSnapshot(planId: String): List<SmartApkSnapshot> {
        val file = apkSnapshotFile(planId) ?: return emptyList()
        if (!file.isFile) return emptyList()
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyList()
        val createdAt = root.optLong("createdAt", 0L)
        if (createdAt <= 0L || System.currentTimeMillis() - createdAt !in 0..CLEAN_PLAN_TTL_MS) {
            file.delete()
            return emptyList()
        }
        val array = root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val uri = item.optString("uri").trim()
                val path = item.optString("path").trim()
                if (uri.isBlank() || path.isBlank()) continue
                add(SmartApkSnapshot(
                    uri = uri,
                    path = path,
                    name = item.optString("name").ifBlank { path.substringAfterLast('/') },
                    bytes = item.optLong("bytes", 0L).coerceAtLeast(0L),
                    modifiedSeconds = item.optLong("modifiedSeconds", 0L).coerceAtLeast(0L)
                ))
            }
        }
    }

    private fun deleteApkSnapshot(planId: String) {
        apkSnapshotFile(planId)?.delete()
    }

    private fun mergeApkMetrics(result: SmartApkCleanResult) {
        if (result.processed <= 0) return
        processedCandidates += result.processed
        cleanedCandidates += result.deleted
        changedCandidates += result.changed
        failedCandidates += result.failed
        deletedBytes += result.deletedBytes
        deletedFiles += result.deleted.toLong()
        cumulativeFailures += result.failed
        mergeMetricBucket(categoryStats, "apk", result)
        mergeMetricBucket(riskStats, "low", result)
    }

    private fun mergeMetricBucket(root: JSONObject, key: String, result: SmartApkCleanResult) {
        val bucket = root.optJSONObject(key) ?: JSONObject().also { root.put(key, it) }
        bucket.put("processed", bucket.optInt("processed") + result.processed)
            .put("cleaned", bucket.optInt("cleaned") + result.deleted)
            .put("changed", bucket.optInt("changed") + result.changed)
            .put("protected", bucket.optInt("protected"))
            .put("partial", bucket.optInt("partial"))
            .put("failed", bucket.optInt("failed") + result.failed)
            .put("bytes", bucket.optLong("bytes") + result.deletedBytes)
    }

    private fun historyCategoriesForRun(): List<HistoryCategoryUiItem> {
        val labels = mapOf(
            "cache" to "应用缓存",
            "apk" to "安装包",
            "empty" to "空文件与空目录",
            "rules" to "规则垃圾与日志",
            "fragment" to "残留碎片",
            "other" to "其他垃圾"
        )
        return buildList {
            val keys = categoryStats.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val bucket = categoryStats.optJSONObject(key) ?: continue
                val bytes = bucket.optLong("bytes", bucket.optLong("deletedBytes", 0L)).coerceAtLeast(0L)
                val files = bucket.optLong("cleaned", bucket.optLong("deletedFiles", 0L)).coerceAtLeast(0L)
                if (bytes <= 0L && files <= 0L) continue
                add(HistoryCategoryUiItem(labels[key] ?: key, bytes, files))
            }
        }.sortedByDescending { it.bytes }
    }

    private fun throwableJson(error: Throwable): JSONObject = JSONObject()
        .put("error", "binder_failed")
        .put("message", error.message ?: error.javaClass.simpleName)
        .put("cancelled", false)
        .put("timedOut", false)

    private fun clearLocalPlan() {
        val oldPlanId = cleanPlanId
        preferences.edit().remove(CLEAN_PLAN_KEY).remove(LEGACY_PLAN_KEY).apply()
        deleteApkSnapshot(oldPlanId)
        cacheSnapshotId = ""
        safeSnapshotId = ""
        apkSnapshot = emptyList()
        cacheCount = 0
        safeCount = 0
        apkCount = 0
        apkBytes = 0L
        cleanPlanId = ""
        cleanPlanCreatedAt = 0L
        estimatedBytes = 0L
        resumable = false
        restoredPlanNeedsValidation = false
        validationRunning = false
    }

    private fun resetPlanFields() {
        if (cleanPlanId.isNotBlank()) deleteApkSnapshot(cleanPlanId)
        cacheSnapshotId = ""
        safeSnapshotId = ""
        apkSnapshot = emptyList()
        cacheCount = 0
        safeCount = 0
        apkCount = 0
        apkBytes = 0L
        originalCacheCount = 0
        originalSafeCount = 0
        originalApkCount = 0
        cleanPlanId = ""
        cleanPlanCreatedAt = 0L
        estimatedBytes = 0L
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

internal enum class SmartCleanCategory { CACHE, APK, SAFE }

internal data class SmartApkSnapshot(
    val uri: String,
    val path: String,
    val name: String,
    val bytes: Long,
    val modifiedSeconds: Long
)

internal data class SmartApkScanResult(
    val items: List<SmartApkSnapshot>,
    val elapsedMs: Long,
    val error: String
)

internal data class SmartApkCleanResult(
    val deleted: Int,
    val deletedBytes: Long,
    val changed: Int,
    val failed: Int,
    val elapsedMs: Long
) {
    val processed: Int get() = deleted + changed + failed
    companion object {
        val EMPTY = SmartApkCleanResult(0, 0L, 0, 0, 0L)
    }
}

internal data class ResumeSmartUiState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val operation: String = "",
    val status: String = "正在连接清理服务…",
    val phase: String = "连接完成后可开始智能扫描",
    val totalSafe: Int = 0,
    val cleanReady: Boolean = false,
    val scanCompleted: Boolean = false,
    val resumable: Boolean = false,
    val estimatedBytes: Long = 0L,
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
    val apkSummary: String = "等待扫描",
    val safeSummary: String = "等待扫描",
    val cacheCount: Int = 0,
    val apkCount: Int = 0,
    val safeCount: Int = 0,
    val cacheBytes: Long? = null,
    val apkBytes: Long? = null,
    val safeBytes: Long? = null,
    val cacheSelected: Boolean = false,
    val apkSelected: Boolean = false,
    val safeSelected: Boolean = false
) {
    val selectedCount: Int
        get() = (if (cacheSelected) cacheCount else 0) +
            (if (apkSelected) apkCount else 0) + (if (safeSelected) safeCount else 0)
    val allSelected: Boolean
        get() = totalSafe > 0 && (cacheCount == 0 || cacheSelected) &&
            (apkCount == 0 || apkSelected) && (safeCount == 0 || safeSelected)
    val selectedBytes: Long?
        get() {
            val sizes = listOfNotNull(
                if (cacheSelected && cacheCount > 0) cacheBytes else 0L,
                if (apkSelected && apkCount > 0) apkBytes else 0L,
                if (safeSelected && safeCount > 0) safeBytes else 0L)
            return if (sizes.size == 3) sizes.sum() else null
        }
    fun selectAll(selected: Boolean) = copy(
        cacheSelected = selected && cacheCount > 0,
        apkSelected = selected && apkCount > 0,
        safeSelected = selected && safeCount > 0)
}

@Composable
internal fun ResumeSmartScreen(
    state: ResumeSmartUiState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onClean: () -> Unit,
    onStop: () -> Unit,
    onReconnect: () -> Unit,
    onToggleCategory: (SmartCleanCategory) -> Unit,
    onToggleAll: () -> Unit = {}
) {
    val context = LocalContext.current
    val progress = if (state.progressTotal > 0) {
        (state.progressCurrent.toFloat() / state.progressTotal.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val scheme = MaterialTheme.colorScheme
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 320),
        label = "resumeProgress"
    )
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
        else -> "一键清理"
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

    Scaffold(containerColor = BaiZeTokens.colors.surfaceBase,
        topBar = { DetailPageHeader("一键清理", "缓存、安装包与规则垃圾一次扫描", onBack) },
        bottomBar = {
            if (state.cleanReady && !state.running) CleanSelectionBar(
                state.selectedCount, state.totalSafe,
                state.selectedBytes?.let { Formatter.formatFileSize(context, it) } ?: "剩余容量待确认",
                state.allSelected, true, onToggleAll, onClean,
                cleanLabel = if (state.resumable) "继续清理已选 ${state.selectedCount} 项" else "清理已选 ${state.selectedCount} 项",
                cleanEnabled = state.connected)
        }
    ) { insets ->
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(insets).background(BaiZeTokens.colors.surfaceBase),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item(contentType = "task") {
            DetailGlassPanel(Modifier.animateContentSize()) {
                Text(metricLabel, fontSize = 12.sp, color = scheme.onSurfaceVariant)
                Text(
                    metric,
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = if (state.cleanReady || state.runCount > 0) 28.sp else 22.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum"
                    ),
                    color = scheme.onSurface
                )
                DetailStatusText(state.phase, Modifier.padding(top = 5.dp, bottom = 14.dp))
                if (state.running) {
                    if (state.progressTotal > 0) {
                        LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth().height(8.dp))
                        Text("${state.progressCurrent.coerceAtMost(state.progressTotal)} / ${state.progressTotal}",
                            Modifier.padding(top = 5.dp), fontSize = 12.sp, color = scheme.onSurfaceVariant)
                    } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp))
                    GlassActionButton("停止并保存", onStop, Modifier.fillMaxWidth().padding(top = 12.dp),
                        icon = Icons.Rounded.Stop, secondary = true)
                } else if (state.cleanReady) {
                    Text("选择下方分类，确认后从底部清理", style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant)
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
                    ResumeSelectableRow(
                        title = "应用缓存",
                        summary = state.cacheSummary,
                        checked = state.cacheSelected,
                        enabled = state.cleanReady && !state.running && state.cacheCount > 0,
                        onClick = { onToggleCategory(SmartCleanCategory.CACHE) }
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = scheme.onSurface.copy(alpha = .055f))
                    ResumeSelectableRow(
                        title = "安装包",
                        summary = state.apkSummary,
                        checked = state.apkSelected,
                        enabled = state.cleanReady && !state.running && state.apkCount > 0,
                        onClick = { onToggleCategory(SmartCleanCategory.APK) }
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = scheme.onSurface.copy(alpha = .055f))
                    ResumeSelectableRow(
                        title = "规则垃圾与残留",
                        summary = state.safeSummary,
                        checked = state.safeSelected,
                        enabled = state.cleanReady && !state.running && state.safeCount > 0,
                        onClick = { onToggleCategory(SmartCleanCategory.SAFE) }
                    )
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
        item(contentType = "bottom-inset") { Spacer(Modifier.height(8.dp)) }
    }
    }
}

@Composable
private fun ResumeSelectableRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
            .padding(vertical = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(summary, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
        "apk" to "安装包",
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
