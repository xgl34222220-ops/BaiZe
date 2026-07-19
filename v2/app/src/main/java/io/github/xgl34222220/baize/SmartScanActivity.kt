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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
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
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.BaiZeRootService
import io.github.xgl34222220.baize.root.IBaiZeRootService
import io.github.xgl34222220.baize.root.IProfileRootService
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

class SmartScanActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private val preferences by lazy { getSharedPreferences("baize_v2", MODE_PRIVATE) }

    private var cacheService: IBaiZeRootService? = null
    private var profileService: IProfileRootService? = null
    private var cacheBindingRequested = false
    private var profileBindingRequested = false
    private var pollJob: Job? = null

    private var cacheSnapshotId = ""
    private var cacheCount = 0
    private var safeSnapshotId = ""
    private var safeCount = 0

    private var showCleanConfirm by mutableStateOf(false)
    private var screenState by mutableStateOf(SmartScanUiState())

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

    private val profileConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            profileService = IProfileRootService.Stub.asInterface(binder)
            profileBindingRequested = true
            updateConnectionState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            profileService = null
            profileBindingRequested = false
            updateConnectionState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val appearance by appearanceViewModel.settings.collectAsState()
            BaiZeTheme(appearance) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SmartScanScreen(
                        state = screenState,
                        onBack = ::finish,
                        onScan = ::startSmartScan,
                        onClean = { showCleanConfirm = true },
                        onStop = ::stopTask,
                        onReconnect = ::bindServices,
                        onToggleOverview = {
                            screenState = screenState.copy(overviewVisible = !screenState.overviewVisible)
                        }
                    )
                    if (showCleanConfirm) {
                        AlertDialog(
                            onDismissRequest = { showCleanConfirm = false },
                            title = { Text("一键清理 ${screenState.totalSafe} 项？") },
                            text = {
                                Text(
                                    "只消费刚才生成的应用缓存与安全项目快照，不进入二级页面，也不会重新扫描。" +
                                        "清理前仍会逐项复核白名单、路径、软链接、挂载点和大文件限制。"
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
                    Intent(this, BaiZeRootService::class.java)
                        .addCategory(RootService.CATEGORY_DAEMON_MODE),
                    cacheConnection
                )
                cacheBindingRequested = true
            }.onFailure {
                cacheBindingRequested = false
                screenState = screenState.copy(phase = it.message ?: "缓存引擎连接失败")
            }
        }
        if (profileService == null && !profileBindingRequested) {
            runCatching {
                RootService.bind(
                    Intent(this, BaiZeProfileRootService::class.java)
                        .addCategory(RootService.CATEGORY_DAEMON_MODE),
                    profileConnection
                )
                profileBindingRequested = true
            }.onFailure {
                profileBindingRequested = false
                screenState = screenState.copy(phase = it.message ?: "分类引擎连接失败")
            }
        }
        updateConnectionState()
    }

    private fun updateConnectionState() {
        val cacheReady = cacheService != null
        val profileReady = profileService != null
        screenState = screenState.copy(
            connected = cacheReady && profileReady,
            status = when {
                cacheReady && profileReady -> "两套 Root 原生引擎已连接"
                cacheReady -> "应用缓存引擎已连接 · 等待分类引擎"
                profileReady -> "分类引擎已连接 · 等待应用缓存引擎"
                else -> "正在连接两套 Root 原生引擎"
            }
        )
    }

    private fun startSmartScan() {
        if (screenState.running) {
            screenState = screenState.copy(phase = "智能扫描任务仍在运行，请先停止或等待完成")
            return
        }
        val cache = cacheService
        val profiles = profileService
        if (cache == null || profiles == null) {
            screenState = screenState.copy(phase = "Root 扫描引擎尚未全部连接，正在重新连接…")
            bindServices()
            return
        }

        clearSnapshotHandles()
        screenState = screenState.copy(
            running = true,
            operation = "scan",
            phase = "正在并行扫描应用缓存与安全项目…",
            totalSafe = 0,
            cleanReady = false,
            scanCompleted = false,
            overviewVisible = true,
            progressCurrent = 0,
            progressTotal = 2,
            cacheSummary = "正在扫描",
            emptySummary = "等待扫描",
            rulesSummary = "等待扫描",
            fragmentsSummary = "等待扫描"
        )
        startPolling()
        val started = SystemClock.elapsedRealtime()

        lifecycleScope.launch {
            var failed = 0
            try {
                val whitelist = preferences.getStringSet("package_whitelist", emptySet()).orEmpty()
                val (cacheJson, safeJson) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val cacheJob = async {
                            JSONObject(cache.scanCandidates(JSONArray(whitelist.toList()).toString()))
                        }
                        val safeJob = async {
                            JSONObject(profiles.scanProfile("safe", optionsJson()))
                        }
                        cacheJob.await() to safeJob.await()
                    }
                }

                val busy = listOf(cacheJson, safeJson).firstOrNull {
                    it.optString("error") == "busy"
                }
                if (busy != null) {
                    screenState = screenState.copy(
                        phase = busy.optString("message", "当前已有扫描或清理任务正在运行")
                    )
                    return@launch
                }

                if (cacheJson.has("error")) {
                    failed++
                    screenState = screenState.copy(
                        cacheSummary = cacheJson.optString("message", "扫描失败")
                    )
                } else {
                    cacheSnapshotId = cacheJson.optString("snapshotId")
                    cacheCount = (
                        cacheJson.optInt("totalCandidates") - cacheJson.optInt("whitelisted")
                    ).coerceAtLeast(0)
                    screenState = screenState.copy(
                        cacheSummary = categorySummary(cacheCount, cacheJson.optLong("elapsedMs"))
                    )
                }

                if (cacheJson.optBoolean("cancelled")) {
                    screenState = screenState.copy(phase = "智能扫描已停止")
                    return@launch
                }

                if (safeJson.has("error")) {
                    failed++
                    val reason = safeJson.optString("message", "扫描失败")
                    screenState = screenState.copy(
                        emptySummary = reason,
                        rulesSummary = reason,
                        fragmentsSummary = reason
                    )
                } else {
                    safeSnapshotId = safeJson.optString("snapshotId")
                    safeCount = (
                        safeJson.optInt("low") + safeJson.optInt("medium")
                    ).coerceAtLeast(0)
                    val elapsed = safeJson.optLong("elapsedMs")
                    screenState = screenState.copy(
                        emptySummary = categorySummary(
                            safeJson.optInt("emptyFiles") + safeJson.optInt("emptyDirs"),
                            elapsed
                        ),
                        rulesSummary = categorySummary(safeJson.optInt("ruleTargets"), elapsed),
                        fragmentsSummary = categorySummary(safeJson.optInt("fragmentFiles"), elapsed)
                    )
                }

                val total = cacheCount + safeCount
                val cancelled = cacheJson.optBoolean("cancelled") || safeJson.optBoolean("cancelled")
                val cleanReady = !cancelled && (
                    (cacheSnapshotId.isNotBlank() && cacheCount > 0) ||
                        (safeSnapshotId.isNotBlank() && safeCount > 0)
                    )
                screenState = screenState.copy(
                    phase = buildString {
                        append(if (cancelled) "智能扫描已停止" else "智能扫描完成")
                        append(" · ${SystemClock.elapsedRealtime() - started}ms")
                        if (!cancelled) {
                            append("\n四类共发现 $total 项可安全清理内容")
                            if (failed > 0) append(" · $failed 类扫描异常")
                            append("\n一键清理只消费本次快照，不会再次扫描。")
                        }
                    },
                    running = false,
                    operation = "",
                    progressCurrent = 2,
                    progressTotal = 2,
                    totalSafe = total,
                    cleanReady = cleanReady,
                    scanCompleted = !cancelled
                )
            } catch (error: Throwable) {
                screenState = screenState.copy(
                    phase = "智能扫描失败：${error.message ?: error.javaClass.simpleName}"
                )
            } finally {
                pollJob?.cancel()
                if (screenState.running) {
                    screenState = screenState.copy(running = false, operation = "")
                }
                updateConnectionState()
            }
        }
    }

    private fun cleanSnapshots() {
        if (screenState.running) {
            screenState = screenState.copy(phase = "智能扫描任务仍在运行，请先停止或等待完成")
            return
        }
        if (!screenState.cleanReady || screenState.totalSafe <= 0 ||
            (cacheSnapshotId.isBlank() && safeSnapshotId.isBlank())
        ) {
            screenState = screenState.copy(phase = "没有可用的扫描快照，请先重新扫描")
            return
        }

        val cache = cacheService
        val profiles = profileService
        if (cache == null || profiles == null) {
            screenState = screenState.copy(phase = "Root 清理引擎尚未全部连接，正在重新连接…")
            bindServices()
            return
        }

        val totalStages = listOf(
            cacheSnapshotId.isNotBlank() && cacheCount > 0,
            safeSnapshotId.isNotBlank() && safeCount > 0
        ).count { it }.coerceAtLeast(1)

        screenState = screenState.copy(
            running = true,
            operation = "clean",
            phase = "正在准备清理扫描快照…",
            progressCurrent = 0,
            progressTotal = totalStages
        )
        startPolling()
        val started = SystemClock.elapsedRealtime()

        lifecycleScope.launch {
            var deletedBytes = 0L
            var deletedFiles = 0L
            var deletedDirectories = 0L
            var deletedEmptyFiles = 0L
            var deletedEmptyDirectories = 0L
            var deletedFragments = 0L
            var cleaned = 0
            var failures = 0
            var cancelled = false
            var completedStages = 0

            try {
                val selectAll = JSONObject().put("__all_safe__", true).toString()
                val whitelist = preferences.getStringSet("package_whitelist", emptySet()).orEmpty()

                if (cacheSnapshotId.isNotBlank() && cacheCount > 0) {
                    screenState = screenState.copy(
                        phase = "正在清理应用缓存快照…",
                        progressCurrent = 0,
                        progressTotal = cacheCount.coerceAtLeast(1)
                    )
                    val json = JSONObject(
                        withContext(Dispatchers.IO) {
                            cache.cleanSelected(
                                cacheSnapshotId,
                                selectAll,
                                JSONArray(whitelist.toList()).toString()
                            )
                        }
                    )
                    if (json.has("error")) {
                        throw IllegalStateException(json.optString("message", "缓存快照清理失败"))
                    }
                    deletedBytes += json.optLong("deletedBytes")
                    deletedFiles += json.optLong("deletedFiles")
                    deletedDirectories += json.optLong("deletedDirectories")
                    cleaned += json.optInt("cleanedCandidates")
                    failures += json.optInt("failures")
                    cancelled = json.optBoolean("cancelled")
                    completedStages++
                }

                if (!cancelled && safeSnapshotId.isNotBlank() && safeCount > 0) {
                    screenState = screenState.copy(
                        phase = "正在清理空项目、规则垃圾与残留碎片快照…",
                        progressCurrent = 0,
                        progressTotal = safeCount.coerceAtLeast(1)
                    )
                    val json = JSONObject(
                        withContext(Dispatchers.IO) {
                            profiles.cleanProfileSelected(
                                safeSnapshotId,
                                selectAll,
                                optionsJson()
                            )
                        }
                    )
                    if (json.has("error")) {
                        throw IllegalStateException(json.optString("message", "安全项目快照清理失败"))
                    }
                    deletedBytes += json.optLong("deletedBytes")
                    deletedFiles += json.optLong("deletedFiles")
                    deletedDirectories += json.optLong("deletedDirectories")
                    val details = json.optJSONArray("details") ?: JSONArray()
                    for (index in 0 until details.length()) {
                        val item = details.optJSONObject(index) ?: continue
                        when (item.optString("profile")) {
                            "empty" -> {
                                deletedEmptyFiles += item.optLong("files")
                                deletedEmptyDirectories += item.optLong("directories")
                            }

                            "fragments" -> deletedFragments += item.optLong("files")
                        }
                    }
                    cleaned += json.optInt("cleanedCandidates")
                    failures += json.optInt("failures")
                    cancelled = json.optBoolean("cancelled")
                    completedStages++
                }

                val report = buildString {
                    append(if (cancelled) "一键清理已停止" else "一键清理完成")
                    append(" · ${SystemClock.elapsedRealtime() - started}ms")
                    append("\n释放 ${Formatter.formatFileSize(this@SmartScanActivity, deletedBytes)}")
                    append(" · 已处理 $cleaned 项 · 文件 $deletedFiles · 目录 $deletedDirectories")
                    if (failures > 0) append(" · 未清理 $failures")
                }

                preferences.edit()
                    .putString("last_report_text", report)
                    .putLong("last_clean_bytes", deletedBytes)
                    .apply()

                runCatching {
                    withContext(Dispatchers.IO) {
                        profiles.recordNativeTask(
                            JSONObject()
                                .put("mode", "smart-clean")
                                .put("success", !cancelled)
                                .put("cancelled", cancelled)
                                .put("bytes", deletedBytes)
                                .put("files", deletedFiles)
                                .put("emptyFiles", deletedEmptyFiles)
                                .put("emptyDirs", deletedEmptyDirectories)
                                .put("fragments", deletedFragments)
                                .put("errors", failures)
                                .put(
                                    "elapsedSeconds",
                                    (SystemClock.elapsedRealtime() - started) / 1000L
                                )
                                .put("result", report.substringBefore('\n'))
                                .toString()
                        )
                    }
                }

                NativeNotifier.showTaskResult(
                    this@SmartScanActivity,
                    if (cancelled) "白泽一键清理已停止" else "白泽一键清理完成",
                    "释放 ${Formatter.formatFileSize(this@SmartScanActivity, deletedBytes)}",
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
                    progressCurrent = completedStages,
                    progressTotal = totalStages
                )
            } catch (error: Throwable) {
                screenState = screenState.copy(
                    phase = "一键清理失败：${error.message ?: error.javaClass.simpleName}"
                )
            } finally {
                pollJob?.cancel()
                if (screenState.running) {
                    screenState = screenState.copy(running = false, operation = "")
                }
                updateConnectionState()
            }
        }
    }

    private fun stopTask() {
        if (!screenState.running) return
        cacheService?.cancelCurrentTask()
        profileService?.cancelCurrentTask()
        screenState = screenState.copy(phase = "已发送停止请求，正在安全结束当前项目…")
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive && screenState.running) {
                val states = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val cacheState = async {
                            cacheService?.getTaskState()
                                ?.takeIf { it.isNotBlank() }
                                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                        }
                        val profileState = async {
                            profileService?.getTaskState()
                                ?.takeIf { it.isNotBlank() }
                                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                        }
                        listOfNotNull(cacheState.await(), profileState.await())
                    }
                }.filter { it.optBoolean("running") }

                if (states.isNotEmpty()) renderRemoteProgress(states)
                delay(350L)
            }
        }
    }

    private fun renderRemoteProgress(states: List<JSONObject>) {
        val preferred = states.firstOrNull {
            val operation = it.optString("operation", it.optString("mode"))
            if (screenState.operation == "clean") {
                operation.contains("clean", ignoreCase = true)
            } else {
                operation.contains("scan", ignoreCase = true)
            }
        } ?: states.first()

        val current = preferred.optInt(
            "progress_current",
            preferred.optInt("current", screenState.progressCurrent)
        )
        val total = preferred.optInt(
            "progress_total",
            preferred.optInt("total", screenState.progressTotal)
        )
        val path = preferred.optString(
            "current_path",
            preferred.optString("currentPath")
        ).trim()
        val phase = preferred.optString("phase").ifBlank {
            if (screenState.operation == "clean") "正在清理扫描快照" else "正在执行智能扫描"
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

    private fun clearSnapshotHandles() {
        cacheSnapshotId = ""
        cacheCount = 0
        safeSnapshotId = ""
        safeCount = 0
    }

    private fun categorySummary(count: Int, elapsed: Long): String =
        "$count 项 · ${elapsed.coerceAtLeast(0L)}ms"

    private fun optionsJson(): String {
        val whitelist = preferences.getStringSet("package_whitelist", emptySet()).orEmpty()
        val pathWhitelist = preferences.getStringSet("path_whitelist", emptySet()).orEmpty()
        val maxMb = preferences.getFloat("large_file_mb", 512f)
            .toLong()
            .coerceIn(64L, 16_384L)
        return JSONObject()
            .put("whitelistPackages", JSONArray(whitelist.toList()))
            .put("whitelistPaths", JSONArray(pathWhitelist.toList()))
            .put("maxFileBytes", maxMb * 1024L * 1024L)
            .put("fragmentDays", preferences.getInt("fragment_days", 7).coerceIn(0, 365))
            .put("allowHighRisk", false)
            .toString()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        if (cacheBindingRequested) runCatching { RootService.unbind(cacheConnection) }
        if (profileBindingRequested) runCatching { RootService.unbind(profileConnection) }
        super.onDestroy()
    }
}

private data class SmartScanUiState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val operation: String = "",
    val status: String = "正在连接两套 Root 原生引擎…",
    val phase: String = "连接完成后可开始智能扫描",
    val totalSafe: Int = 0,
    val cleanReady: Boolean = false,
    val scanCompleted: Boolean = false,
    val overviewVisible: Boolean = false,
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val cacheSummary: String = "等待扫描",
    val emptySummary: String = "等待扫描",
    val rulesSummary: String = "等待扫描",
    val fragmentsSummary: String = "等待扫描"
)

@Composable
private fun SmartScanScreen(
    state: SmartScanUiState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onClean: () -> Unit,
    onStop: () -> Unit,
    onReconnect: () -> Unit,
    onToggleOverview: () -> Unit
) {
    val progress = if (state.progressTotal > 0) {
        (state.progressCurrent.toFloat() / state.progressTotal.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "SMART CLEAN",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Text("智能扫描", fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text(
                        "应用缓存、空项目、规则垃圾与残留碎片",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(30.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(20.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.CleaningServices,
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
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (state.running) {
                        if (state.progressTotal > 0) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (state.operation == "clean") {
                                    "正在清理扫描快照"
                                } else {
                                    "正在执行智能扫描"
                                },
                                maxLines = 1
                            )
                        }
                        OutlinedButton(
                            onClick = onStop,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("停止当前任务", maxLines = 1)
                        }
                    } else {
                        if (state.cleanReady) {
                            Button(
                                onClick = onClean,
                                enabled = state.connected,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text("一键清理 ${state.totalSafe} 项", maxLines = 1)
                            }
                        }

                        if (!state.connected) {
                            OutlinedButton(
                                onClick = onReconnect,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text("重新连接 Root 引擎", maxLines = 1)
                            }
                        } else if (state.scanCompleted || state.totalSafe > 0) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = onScan,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                                    Spacer(Modifier.size(6.dp))
                                    Text("重新扫描", maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = onToggleOverview,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        if (state.overviewVisible) {
                                            Icons.Rounded.ExpandLess
                                        } else {
                                            Icons.Rounded.ExpandMore
                                        },
                                        contentDescription = null
                                    )
                                    Spacer(Modifier.size(6.dp))
                                    Text(
                                        if (state.overviewVisible) "收起详情" else "查看详情",
                                        maxLines = 1
                                    )
                                }
                            }
                        } else {
                            Button(
                                onClick = onScan,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text("开始智能扫描", maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        if (state.overviewVisible) {
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        "SCAN OVERVIEW",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Text("扫描总览", fontSize = 26.sp, fontWeight = FontWeight.Black)
                    Text(
                        if (state.totalSafe > 0) {
                            "四类共 ${state.totalSafe} 项安全候选"
                        } else {
                            "扫描过程中实时更新分类结果"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item { SmartScanOverviewCard("应用缓存", state.cacheSummary) }
            item { SmartScanOverviewCard("空项目", state.emptySummary) }
            item { SmartScanOverviewCard("规则垃圾", state.rulesSummary) }
            item { SmartScanOverviewCard("残留碎片", state.fragmentsSummary) }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .navigationBarsPadding(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Text(
                        "智能扫描完成后可直接在本页清理同一批快照。清理不会重新扫描，" +
                            "扫描后新增或修改的文件仍会受到保护。",
                        modifier = Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        } else if (!state.running && state.scanCompleted) {
            item {
                Text(
                    "已隐藏扫描总览，可通过“查看详情”重新展开。",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SmartScanOverviewCard(
    title: String,
    summary: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 17.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                summary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
