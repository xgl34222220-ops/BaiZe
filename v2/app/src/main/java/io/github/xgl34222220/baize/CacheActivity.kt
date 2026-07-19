package io.github.xgl34222220.baize

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

class CacheActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private val preferences by lazy { getSharedPreferences("baize_v2", MODE_PRIVATE) }

    private var cacheService: IBaiZeRootService? = null
    private var moduleService: IProfileRootService? = null
    private var cacheBindingRequested = false
    private var moduleBindingRequested = false
    private var recovering = false
    private var pollJob: Job? = null
    private var showCleanConfirm by mutableStateOf(false)
    private var screenState by mutableStateOf(CacheUiState())

    private val cacheConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            cacheService = IBaiZeRootService.Stub.asInterface(binder)
            cacheBindingRequested = true
            updateConnectionState()
            recoverRemoteOrSnapshot()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cacheService = null
            cacheBindingRequested = false
            updateConnectionState()
        }
    }

    private val moduleConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            moduleService = IProfileRootService.Stub.asInterface(binder)
            moduleBindingRequested = true
            updateConnectionState()
            recoverRemoteOrSnapshot()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            moduleService = null
            moduleBindingRequested = false
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
                    CacheScreen(
                        state = screenState,
                        onBack = ::finish,
                        onScan = ::scan,
                        onClean = { showCleanConfirm = true },
                        onStop = ::stopTask,
                        onReconnect = ::connectServices,
                        onPrevious = { loadPage(screenState.page - 1) },
                        onNext = { loadPage(screenState.page + 1) }
                    )
                    if (showCleanConfirm) {
                        AlertDialog(
                            onDismissRequest = { showCleanConfirm = false },
                            title = { Text("清理刚才扫描到的缓存？") },
                            text = {
                                Text(
                                    "不会再次扫描。只处理当前 30 分钟快照中的安全缓存；" +
                                        "扫描后新建或修改的文件、白名单路径、软链接、挂载点和大文件会自动跳过。"
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showCleanConfirm = false
                                    quickClean()
                                }) { Text("立即清理") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showCleanConfirm = false }) { Text("取消") }
                            }
                        )
                    }
                }
            }
        }
        connectServices()
    }

    private fun cacheIntent(): Intent = Intent(this, BaiZeRootService::class.java)
        .addCategory(RootService.CATEGORY_DAEMON_MODE)

    private fun moduleIntent(): Intent = Intent(this, BaiZeProfileRootService::class.java)
        .addCategory(RootService.CATEGORY_DAEMON_MODE)

    private fun connectServices() {
        if (cacheService == null && !cacheBindingRequested) {
            runCatching {
                RootService.bind(cacheIntent(), cacheConnection)
                cacheBindingRequested = true
            }.onFailure {
                screenState = screenState.copy(phase = it.message ?: "缓存扫描服务启动失败")
            }
        }
        if (moduleService == null && !moduleBindingRequested) {
            runCatching {
                RootService.bind(moduleIntent(), moduleConnection)
                moduleBindingRequested = true
            }.onFailure {
                screenState = screenState.copy(phase = it.message ?: "快照清理服务启动失败")
            }
        }
        updateConnectionState()
    }

    private fun updateConnectionState() {
        val status = when {
            cacheService != null && moduleService != null -> "Root 持久扫描与快照清理引擎已连接"
            cacheService != null -> "缓存扫描已连接 · 正在连接快照清理"
            moduleService != null -> "快照清理已连接 · 正在连接缓存扫描"
            else -> "正在连接 Root 缓存引擎…"
        }
        screenState = screenState.copy(
            connected = cacheService != null && moduleService != null,
            scanConnected = cacheService != null,
            cleanConnected = moduleService != null,
            status = status
        )
    }

    private fun scan() {
        if (screenState.running) {
            screenState = screenState.copy(phase = "缓存任务仍在运行，请先停止或等待完成")
            return
        }
        val root = cacheService
        if (root == null) {
            screenState = screenState.copy(phase = "缓存扫描引擎尚未连接，正在重新连接…")
            connectServices()
            return
        }

        screenState = screenState.copy(
            running = true,
            operation = "scan",
            phase = "正在扫描应用缓存…",
            snapshotId = "",
            total = 0,
            page = 0,
            pages = 1,
            items = emptyList(),
            quickCleanReady = false,
            totalFiles = 0,
            totalBytes = 0,
            whitelisted = 0
        )
        startPolling()

        lifecycleScope.launch {
            val whitelist = preferences.getStringSet("package_whitelist", emptySet()).orEmpty()
            val result = runCatching {
                withContext(Dispatchers.IO) { root.scanCandidates(JSONArray(whitelist.toList()).toString()) }
            }
            pollJob?.cancel()
            if (result.isFailure) {
                screenState = screenState.copy(
                    running = false,
                    operation = "",
                    phase = "扫描失败：${result.exceptionOrNull()?.message ?: "缓存引擎异常"}"
                )
                return@launch
            }

            val json = JSONObject(result.getOrThrow())
            if (json.optString("error") == "busy") {
                screenState = screenState.copy(phase = "检测到后台任务，正在恢复真实进度…")
                recoverRemoteOrSnapshot()
                return@launch
            }
            if (json.has("error")) {
                screenState = screenState.copy(
                    running = false,
                    operation = "",
                    phase = json.optString("message", "扫描失败")
                )
                return@launch
            }
            if (json.optBoolean("cancelled")) {
                screenState = screenState.copy(
                    running = false,
                    operation = "",
                    phase = "缓存扫描已停止"
                )
                restoreSnapshot(showEmptyMessage = false)
                return@launch
            }
            applyScanResult(json)
        }
    }

    private fun applyScanResult(json: JSONObject) {
        val snapshotId = json.optString("snapshotId")
        val total = json.optInt("totalCandidates").coerceAtLeast(0)
        val files = json.optLong("totalFiles", 0L).coerceAtLeast(0L)
        val bytes = json.optLong("totalBytes", 0L).coerceAtLeast(0L)
        val protected = json.optInt("whitelisted", 0).coerceAtLeast(0)
        val ready = snapshotId.isNotBlank() && total > 0
        screenState = screenState.copy(
            running = false,
            operation = "",
            phase = buildString {
                append("扫描完成，发现 $total 项真实非空缓存")
                if (files > 0) append(" · $files 个文件")
                if (bytes > 0) append(" · ${Formatter.formatFileSize(this@CacheActivity, bytes)}")
                if (protected > 0) append(" · 白名单保护 $protected 项")
                append("\n已保存扫描快照，清理时不会再次扫描。")
            },
            snapshotId = snapshotId,
            total = total,
            page = 0,
            pages = pageCount(total),
            quickCleanReady = ready,
            totalFiles = files,
            totalBytes = bytes,
            whitelisted = protected,
            items = emptyList()
        )
        if (ready) loadPage(0)
    }

    private fun loadPage(targetPage: Int) {
        val root = cacheService ?: return
        val current = screenState
        if (current.snapshotId.isBlank() || current.running || targetPage !in 0 until current.pages) return
        screenState = current.copy(loadingPage = true)
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    root.getResultPage(current.snapshotId, targetPage * PAGE_SIZE, PAGE_SIZE)
                }
            }
            if (result.isFailure) {
                screenState = screenState.copy(
                    loadingPage = false,
                    phase = "读取缓存明细失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                )
                return@launch
            }
            val json = JSONObject(result.getOrThrow())
            if (json.has("error")) {
                screenState = screenState.copy(
                    loadingPage = false,
                    quickCleanReady = false,
                    phase = json.optString("message", "读取扫描结果失败")
                )
                return@launch
            }
            val array = json.optJSONArray("items") ?: JSONArray()
            val values = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        CacheCandidateUi(
                            appName = item.optString("appName", item.optString("packageName")),
                            packageName = item.optString("packageName"),
                            category = item.optString("categoryLabel", "缓存"),
                            path = item.optString("path"),
                            bytes = item.optLong("bytes", -1L),
                            files = item.optLong("files", -1L),
                            directories = item.optLong("directories", -1L)
                        )
                    )
                }
            }
            screenState = screenState.copy(
                loadingPage = false,
                page = targetPage,
                items = values
            )
        }
    }

    private fun quickClean() {
        if (screenState.running || !screenState.quickCleanReady) return
        val root = moduleService
        if (root == null) {
            screenState = screenState.copy(phase = "快照清理引擎尚未连接，正在重新连接…")
            connectServices()
            return
        }

        screenState = screenState.copy(
            running = true,
            operation = "clean",
            phase = "正在清理刚才扫描到的缓存，不会重新扫描…"
        )
        startPolling()
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { root.runModuleTask("cache-clean") }
            }
            pollJob?.cancel()
            if (result.isFailure) {
                screenState = screenState.copy(
                    running = false,
                    operation = "",
                    phase = "缓存清理失败：${result.exceptionOrNull()?.message ?: "Root 服务异常"}"
                )
                return@launch
            }
            val json = JSONObject(result.getOrThrow())
            if (json.optString("error") == "busy" || json.optInt("exitCode") == 3) {
                screenState = screenState.copy(phase = "检测到后台任务，正在恢复真实进度…")
                recoverRemoteOrSnapshot()
                return@launch
            }
            val output = json.optString("output")
                .lineSequence()
                .filter { it.isNotBlank() }
                .takeLast(5)
                .joinToString("\n")
            val success = json.optBoolean("success") && !json.optBoolean("cancelled")
            val phase = buildString {
                append(
                    when {
                        json.optBoolean("cancelled") -> "缓存清理已停止，扫描快照仍保留"
                        success -> "缓存扫描快照清理完成"
                        else -> json.optString("message", "缓存清理失败")
                    }
                )
                if (output.isNotBlank()) append("\n").append(output)
            }
            if (success) {
                preferences.edit().putString("last_report_text", phase).apply()
                clearSnapshotUi(phase)
            } else {
                screenState = screenState.copy(running = false, operation = "", phase = phase)
                restoreSnapshot(showEmptyMessage = false)
            }
        }
    }

    private fun stopTask() {
        if (!screenState.running) {
            screenState = screenState.copy(phase = "当前没有正在运行的缓存任务")
            return
        }
        cacheService?.cancelCurrentTask()
        moduleService?.cancelCurrentTask()
        screenState = screenState.copy(phase = "已发送停止请求，正在安全结束当前目录…")
        startPolling()
    }

    private fun recoverRemoteOrSnapshot() {
        if (recovering || screenState.running || (cacheService == null && moduleService == null)) return
        recovering = true
        lifecycleScope.launch {
            val task = readRemoteTaskState()
            recovering = false
            if (task?.optBoolean("running") == true) {
                val mode = task.optString("mode", task.optString("operation"))
                if (mode.contains("cache", ignoreCase = true)) {
                    screenState = screenState.copy(
                        running = true,
                        operation = if (mode.contains("clean", true)) "clean" else "scan"
                    )
                    renderRemoteTaskState(task)
                    startRecoveryPolling()
                } else {
                    screenState = screenState.copy(phase = "其他清理任务正在运行：${task.optString("phase", mode)}")
                }
            } else {
                restoreSnapshot(showEmptyMessage = true)
            }
        }
    }

    private fun startRecoveryPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive && screenState.running) {
                val task = readRemoteTaskState()
                if (task?.optBoolean("running") == true) {
                    renderRemoteTaskState(task)
                    delay(350L)
                    continue
                }
                screenState = screenState.copy(running = false, operation = "")
                restoreSnapshot(showEmptyMessage = false)
                break
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive && screenState.running) {
                val task = readRemoteTaskState()
                if (task?.optBoolean("running") == true) renderRemoteTaskState(task)
                delay(300L)
            }
        }
    }

    private suspend fun readRemoteTaskState(): JSONObject? = runCatching {
        withContext(Dispatchers.IO) {
            val raw = cacheService?.getTaskState().orEmpty()
                .ifBlank { moduleService?.getTaskState().orEmpty() }
            if (raw.isBlank()) null else JSONObject(raw)
        }
    }.getOrNull()

    private fun renderRemoteTaskState(json: JSONObject) {
        val current = json.optInt("progress_current", json.optInt("current"))
        val total = json.optInt("progress_total", json.optInt("total"))
        val path = json.optString("current_path", json.optString("currentPath"))
        screenState = screenState.copy(
            running = true,
            phase = buildString {
                append(json.optString("phase", "后台缓存任务正在执行"))
                if (total > 0) append(" · $current/$total")
                if (path.isNotBlank()) append("\n").append(path.takeLast(92))
                if (json.optBoolean("cancelRequested")) append("\n正在安全停止…")
            }
        )
    }

    private fun restoreSnapshot(showEmptyMessage: Boolean) {
        val root = cacheService ?: return
        lifecycleScope.launch {
            val info = runCatching {
                withContext(Dispatchers.IO) { JSONObject(root.ping()) }
            }.getOrNull() ?: return@launch
            if (!info.optBoolean("snapshotReady")) {
                if (showEmptyMessage) clearSnapshotUi("等待开始缓存扫描")
                else clearSnapshotUi(screenState.phase)
                return@launch
            }
            val snapshotId = info.optString("snapshotId")
            val total = info.optInt("snapshotItems").coerceAtLeast(0)
            val files = info.optLong("snapshotFiles", 0L).coerceAtLeast(0L)
            val bytes = info.optLong("snapshotBytes", 0L).coerceAtLeast(0L)
            val ready = snapshotId.isNotBlank() && total > 0
            screenState = screenState.copy(
                running = false,
                operation = "",
                phase = buildString {
                    append("已恢复最近一次缓存扫描快照")
                    if (total > 0) append("\n$total 项 · $files 个文件 · ${Formatter.formatFileSize(this@CacheActivity, bytes)}")
                    else append("\n没有可清理缓存")
                    append("\n可直接一键清理，不会重新扫描。")
                },
                snapshotId = snapshotId,
                total = total,
                page = 0,
                pages = pageCount(total),
                quickCleanReady = ready,
                totalFiles = files,
                totalBytes = bytes,
                items = emptyList()
            )
            if (ready) loadPage(0)
        }
    }

    private fun clearSnapshotUi(message: String) {
        screenState = screenState.copy(
            running = false,
            operation = "",
            phase = message,
            snapshotId = "",
            total = 0,
            page = 0,
            pages = 1,
            quickCleanReady = false,
            totalFiles = 0,
            totalBytes = 0,
            whitelisted = 0,
            items = emptyList(),
            loadingPage = false
        )
    }

    private fun pageCount(total: Int): Int = ceil(total / PAGE_SIZE.toDouble()).toInt().coerceAtLeast(1)

    override fun onDestroy() {
        pollJob?.cancel()
        if (cacheBindingRequested) runCatching { RootService.unbind(cacheConnection) }
        if (moduleBindingRequested) runCatching { RootService.unbind(moduleConnection) }
        super.onDestroy()
    }

    companion object {
        private const val PAGE_SIZE = 30
    }
}

private data class CacheUiState(
    val connected: Boolean = false,
    val scanConnected: Boolean = false,
    val cleanConnected: Boolean = false,
    val running: Boolean = false,
    val operation: String = "",
    val status: String = "正在连接 Root 缓存引擎…",
    val phase: String = "等待开始缓存扫描",
    val snapshotId: String = "",
    val total: Int = 0,
    val page: Int = 0,
    val pages: Int = 1,
    val totalFiles: Long = 0,
    val totalBytes: Long = 0,
    val whitelisted: Int = 0,
    val quickCleanReady: Boolean = false,
    val loadingPage: Boolean = false,
    val items: List<CacheCandidateUi> = emptyList()
)

private data class CacheCandidateUi(
    val appName: String,
    val packageName: String,
    val category: String,
    val path: String,
    val bytes: Long,
    val files: Long,
    val directories: Long
)

@Composable
private fun CacheScreen(
    state: CacheUiState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onClean: () -> Unit,
    onStop: () -> Unit,
    onReconnect: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
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
                        "CACHE SNAPSHOT",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Text("应用缓存", fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text(
                        "内部 cache、code_cache 与 Android/data 外部缓存",
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(18.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Storage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
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
                    if (state.running || state.loadingPage) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (state.quickCleanReady && !state.running) {
                        Button(
                            onClick = onClean,
                            enabled = state.cleanConnected,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                "一键清理 ${state.total} 项 · ${Formatter.formatFileSize(context, state.totalBytes)}"
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onScan,
                            enabled = state.scanConnected && !state.running,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(if (state.snapshotId.isBlank()) "扫描缓存" else "重新扫描")
                        }
                        OutlinedButton(
                            onClick = if (state.running) onStop else onReconnect,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                if (state.running) Icons.Rounded.Stop else Icons.Rounded.Refresh,
                                contentDescription = null
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(if (state.running) "停止" else "重新连接")
                        }
                    }
                }
            }
        }

        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    "CACHE DETAILS",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Text("缓存明细", fontSize = 26.sp, fontWeight = FontWeight.Black)
                Text(
                    when {
                        state.total <= 0 -> "扫描后在这里查看应用、路径、文件数与大小"
                        else -> "共 ${state.total} 项 · ${state.totalFiles} 个文件 · 第 ${state.page + 1}/${state.pages} 页"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.items.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Text(
                        if (state.running) "缓存任务正在执行，请稍候…" else "尚未生成缓存扫描明细",
                        modifier = Modifier.padding(22.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(state.items, key = { "${it.packageName}|${it.path}" }) { item ->
                CacheCandidateCard(item)
            }
            if (state.pages > 1) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onPrevious,
                            enabled = !state.running && !state.loadingPage && state.page > 0,
                            modifier = Modifier.weight(1f)
                        ) { Text("上一页") }
                        OutlinedButton(
                            onClick = onNext,
                            enabled = !state.running && !state.loadingPage && state.page + 1 < state.pages,
                            modifier = Modifier.weight(1f)
                        ) { Text("下一页") }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Rounded.CleaningServices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text("快照安全策略", fontWeight = FontWeight.Bold)
                        Text(
                            "扫描只执行一次并保存 30 分钟快照；一键清理只消费刚才的缓存目标。" +
                                "退出页面后任务进度和结果仍可恢复。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CacheCandidateCard(item: CacheCandidateUi) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(item.appName.ifBlank { item.packageName }, fontWeight = FontWeight.Bold)
            if (item.appName.isNotBlank() && item.appName != item.packageName) {
                Text(
                    item.packageName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
            Text(
                buildString {
                    append(item.category)
                    if (item.bytes >= 0) append(" · ${Formatter.formatFileSize(context, item.bytes)}")
                    if (item.files >= 0) append(" · ${item.files} 个文件")
                    if (item.directories >= 0) append(" · ${item.directories} 个目录")
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            if (item.path.isNotBlank()) {
                Spacer(Modifier.height(7.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
                Spacer(Modifier.height(7.dp))
                Text(
                    item.path,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
