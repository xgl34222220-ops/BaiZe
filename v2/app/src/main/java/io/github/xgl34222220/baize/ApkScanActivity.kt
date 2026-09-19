package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.root.RootServiceClients
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.media.MediaScannerConnection
import android.system.Os
import android.system.OsConstants
import android.provider.Settings
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Refresh
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
import io.github.xgl34222220.baize.root.IProfileRootService
import io.github.xgl34222220.baize.root.ITaskProgressCallback
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

class ApkScanActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private var service: IProfileRootService? = null
    private var serviceBound = false
    private var pollJob: Job? = null
    private var taskCallbackRegistered = false
    private val taskProgressCallback = object : ITaskProgressCallback.Stub() {
        override fun onTaskProgress(stateJson: String?) {
            val state = runCatching { JSONObject(stateJson.orEmpty()) }.getOrNull() ?: return
            runOnUiThread { if (state.optBoolean("running")) renderTaskState(state) }
        }
    }
    private var screenState by mutableStateOf(ApkScanUiState())
    private var showCleanConfirm by mutableStateOf(false)
    private var directSnapshot: List<DirectApkSnapshot> = emptyList()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = RootServiceClients.profile(binder, applicationContext.cacheDir)
            serviceBound = true
            taskCallbackRegistered = runCatching { service?.registerTaskProgressCallback(taskProgressCallback); true }.getOrDefault(false)
            screenState = screenState.copy(
                connected = true,
                status = "Root 安装包扫描与快照清理引擎已连接",
                phase = if (screenState.running) screenState.phase else "点击下方按钮开始扫描"
            )
            recoverRunningTask()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            serviceBound = false
            taskCallbackRegistered = false
            pollJob?.cancel()
            screenState = screenState.copy(
                connected = false,
                running = false,
                status = "Root 服务已断开",
                phase = "请重新连接后再扫描"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val appearance by appearanceViewModel.settings.collectAsState()
            BaiZeTheme(appearance) {
                Surface(modifier = Modifier.fillMaxSize(), color = BaiZeTokens.colors.surfaceBase) {
                    ApkScanScreen(
                        state = screenState,
                        onBack = ::finish,
                        onScan = ::startScan,
                        onClean = { showCleanConfirm = true },
                        onStop = ::stopTask,
                        onReconnect = ::connectService
                    )
                    if (showCleanConfirm) {
                        AlertDialog(
                            onDismissRequest = { showCleanConfirm = false },
                            title = { Text("清理刚才扫描到的安装包？") },
                            text = {
                                Text(
                                    "只删除当前扫描快照中的 ${screenState.totalFiles} 个安装包，不会重新扫描。" +
                                        "扫描后新增或修改的文件、白名单路径、软链接和异常路径会自动跳过。"
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showCleanConfirm = false
                                    cleanSnapshot()
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
        connectService()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        if (taskCallbackRegistered) runCatching { service?.unregisterTaskProgressCallback(taskProgressCallback) }
        if (serviceBound) runCatching { RootService.unbind(connection) }
        serviceBound = false
        service = null
        super.onDestroy()
    }

    private fun connectService() {
        if (service != null || serviceBound) return
        screenState = screenState.copy(
            connected = false,
            status = "正在连接 Root 安装包引擎…",
            phase = "连接完成后即可开始扫描"
        )
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java)
                    .addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
            serviceBound = true
        }.onFailure {
            serviceBound = false
            screenState = screenState.copy(
                connected = false,
                status = "Root 服务启动失败",
                phase = it.message ?: "未知错误"
            )
        }
    }

    private fun startScan() {
        if (screenState.running) {
            screenState = screenState.copy(phase = "安装包任务仍在运行，请先停止或等待完成")
            return
        }
        screenState = screenState.copy(
            running = true,
            operation = "scan",
            phase = "正在读取 Android 系统文件索引…",
            items = emptyList(),
            coverage = emptyList(),
            totalFiles = 0,
            totalBytes = 0,
            cleanReady = false,
            output = ""
        )

        lifecycleScope.launch {
            if (!ApkMediaStoreIndex.hasAllFilesAccess()) {
                service?.let { root ->
                    withContext(Dispatchers.IO) {
                        runCatching {
                            RootServiceClients.profileExchange(
                                root, applicationContext.cacheDir, "ensureAllFilesAccess"
                            )
                        }
                    }
                    delay(80)
                }
            }

            if (!ApkMediaStoreIndex.hasAllFilesAccess()) {
                screenState = screenState.copy(
                    running = false,
                    operation = "",
                    phase = "需要“所有文件访问”才能读取系统文件索引，已打开授权页面"
                )
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                return@launch
            }

            val started = SystemClock.elapsedRealtime()
            val indexed = withContext(Dispatchers.IO) { ApkMediaStoreIndex.query(applicationContext) }
            if (indexed.error != null) {
                directSnapshot = emptyList()
                screenState = screenState.copy(
                    running = false,
                    operation = "",
                    phase = "系统文件索引读取失败：${indexed.error}",
                    output = indexed.error
                )
                return@launch
            }

            val snapshots = withContext(Dispatchers.IO) {
                indexed.candidates.mapNotNull { candidate ->
                    val stat = runCatching { Os.lstat(candidate.path) }.getOrNull() ?: return@mapNotNull null
                    if (!OsConstants.S_ISREG(stat.st_mode) || OsConstants.S_ISLNK(stat.st_mode)) return@mapNotNull null
                    DirectApkSnapshot(
                        path = candidate.path,
                        name = candidate.name,
                        bytes = stat.st_size.coerceAtLeast(0L),
                        identity = directIdentity(stat)
                    )
                }
            }
            directSnapshot = snapshots
            val totalBytes = indexed.candidates.sumOf { it.bytes }
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
            val items = indexed.candidates.take(500).map { candidate ->
                ApkScanItem(
                    name = candidate.name,
                    files = 1,
                    bytes = candidate.bytes,
                    errors = 0,
                    samplePath = candidate.path
                )
            }
            val coverage = listOf(
                ScanCoverageItem(
                    status = "scanned",
                    group = "MediaStore.Files 系统索引",
                    files = indexed.candidates.size.toLong(),
                    bytes = totalBytes,
                    path = "content://media/external/file",
                    reason = "系统索引 ${indexed.candidates.size} 条 · App 直校验 ${snapshots.size} 条 · 未经过 Root 二次过滤"
                )
            )
            screenState = screenState.copy(
                running = false,
                operation = "",
                phase = if (indexed.candidates.isEmpty()) "快速索引完成：发现 0 个安装包" else
                    "快速索引完成：发现 ${indexed.candidates.size} 个安装包 · ${elapsed} ms",
                items = items,
                coverage = coverage,
                totalFiles = indexed.candidates.size.toLong(),
                totalBytes = totalBytes,
                cleanReady = snapshots.isNotEmpty(),
                output = "MediaStore.Files ${indexed.elapsedMs} ms · App lstat ${elapsed - indexed.elapsedMs} ms · Root 未参与前台扫描"
            )
        }
    }

    private fun cleanSnapshot() {
        if (screenState.running || !screenState.cleanReady) return
        val snapshot = directSnapshot
        if (snapshot.isEmpty()) {
            screenState = screenState.copy(cleanReady = false, phase = "当前没有可清理的安装包快照，请重新扫描")
            return
        }

        screenState = screenState.copy(
            running = true,
            operation = "clean",
            phase = "正在快速删除 ${snapshot.size} 个安装包…"
        )
        lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) {
                var deletedFiles = 0
                var deletedBytes = 0L
                var skipped = 0
                var failed = 0
                val deletedPaths = ArrayList<String>(snapshot.size)
                snapshot.forEach { item ->
                    val stat = runCatching { Os.lstat(item.path) }.getOrNull()
                    if (stat == null || !OsConstants.S_ISREG(stat.st_mode) || OsConstants.S_ISLNK(stat.st_mode)) {
                        skipped += 1
                        return@forEach
                    }
                    if (directIdentity(stat) != item.identity) {
                        skipped += 1
                        return@forEach
                    }
                    val removed = runCatching { Os.remove(item.path); true }.getOrDefault(false)
                    if (removed) {
                        deletedFiles += 1
                        deletedBytes += item.bytes
                        deletedPaths += item.path
                    } else {
                        failed += 1
                    }
                }
                DirectCleanResult(deletedFiles, deletedBytes, skipped, failed, deletedPaths)
            }
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
            if (result.deletedPaths.isNotEmpty()) {
                MediaScannerConnection.scanFile(
                    applicationContext,
                    result.deletedPaths.toTypedArray(),
                    null,
                    null
                )
            }

            directSnapshot = emptyList()
            val phase = when {
                result.failed > 0 || result.skipped > 0 ->
                    "清理完成：删除 ${result.deletedFiles} 个，跳过 ${result.skipped} 个，失败 ${result.failed} 个 · ${elapsed} ms"
                else ->
                    "清理完成：删除 ${result.deletedFiles} 个，释放 ${Formatter.formatFileSize(this@ApkScanActivity, result.deletedBytes)} · ${elapsed} ms"
            }
            screenState = screenState.copy(
                running = false,
                operation = "",
                cleanReady = false,
                phase = phase,
                items = emptyList(),
                coverage = emptyList(),
                totalFiles = 0,
                totalBytes = 0,
                output = "App 直删：lstat → remove；媒体库刷新已异步提交；总耗时 ${elapsed} ms"
            )
        }
    }

    private fun stopTask() {
        if (!screenState.running) {
            screenState = screenState.copy(phase = "当前没有正在运行的安装包任务")
            return
        }
        service?.cancelCurrentTask()
        screenState = screenState.copy(phase = "正在安全停止安装包任务…")
    }

    private fun recoverRunningTask() {
        val root = service ?: return
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                runCatching { JSONObject(root.getTaskState()) }.getOrNull()
            } ?: return@launch
            if (!state.optBoolean("running")) return@launch
            val operation = state.optString("operation", state.optString("mode"))
            if (!operation.contains("apk", ignoreCase = true)) {
                screenState = screenState.copy(phase = "当前已有其他扫描或清理任务正在运行")
                return@launch
            }
            screenState = screenState.copy(
                running = true,
                operation = if (operation.contains("clean", true)) "clean" else "scan"
            )
            renderTaskState(state)
            startPolling()
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive && screenState.running) {
                val root = service ?: break
                val state = withContext(Dispatchers.IO) {
                    runCatching { JSONObject(root.getTaskState()) }.getOrNull()
                }
                if (state != null && state.optBoolean("running")) renderTaskState(state)
                delay(if (taskCallbackRegistered) 1800 else 350)
            }
        }
    }

    private fun renderTaskState(json: JSONObject) {
        val current = json.optInt("progress_current", json.optInt("current", 0))
        val total = json.optInt("progress_total", json.optInt("total", 0))
        val path = json.optString("current_path", json.optString("currentPath")).trim()
        screenState = screenState.copy(
            running = true,
            phase = buildString {
                append(json.optString("phase", if (screenState.operation == "clean") "正在清理安装包" else "正在扫描安装包"))
                if (total > 0) append(" · $current/$total")
                if (path.isNotBlank()) append("\n").append(path.takeLast(96))
                if (json.optBoolean("cancelRequested")) append("\n正在停止…")
            }
        )
    }

    private fun directIdentity(stat: android.system.StructStat): String =
        "${stat.st_dev}:${stat.st_ino}:${stat.st_size}:" +
            "${stat.st_mtim.tv_sec}:${stat.st_mtim.tv_nsec}:${stat.st_ctim.tv_sec}:${stat.st_ctim.tv_nsec}"

    private fun parseCoverage(array: JSONArray?): List<ScanCoverageItem> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(ScanCoverageItem(
                status = item.optString("status"),
                group = item.optString("group"),
                files = item.optLong("files", 0L),
                bytes = item.optLong("bytes", 0L),
                path = item.optString("path"),
                reason = item.optString("reason")
            ))
        }
    }

    private fun parseItems(array: JSONArray?): List<ApkScanItem> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            if (name.isBlank()) continue
            add(
                ApkScanItem(
                    name = name,
                    files = item.optLong("files", 0L).coerceAtLeast(0L),
                    bytes = item.optLong("bytes", 0L).coerceAtLeast(0L),
                    errors = item.optLong("errors", 0L).coerceAtLeast(0L),
                    samplePath = item.optString("samplePath").trim()
                )
            )
        }
    }.sortedWith(compareByDescending<ApkScanItem> { it.bytes }.thenByDescending { it.files })
}

internal data class DirectApkSnapshot(
    val path: String,
    val name: String,
    val bytes: Long,
    val identity: String
)

internal data class DirectCleanResult(
    val deletedFiles: Int,
    val deletedBytes: Long,
    val skipped: Int,
    val failed: Int,
    val deletedPaths: List<String>
)

internal data class ApkScanUiState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val operation: String = "",
    val status: String = "正在等待 Root 服务…",
    val phase: String = "准备扫描安装包",
    val items: List<ApkScanItem> = emptyList(),
    val coverage: List<ScanCoverageItem> = emptyList(),
    val totalFiles: Long = 0,
    val totalBytes: Long = 0,
    val cleanReady: Boolean = false,
    val output: String = ""
)

internal data class ScanCoverageItem(
    val status: String,
    val group: String,
    val files: Long,
    val bytes: Long,
    val path: String,
    val reason: String
)

internal data class ApkScanItem(
    val name: String,
    val files: Long,
    val bytes: Long,
    val errors: Long,
    val samplePath: String
)

@Composable
internal fun ApkScanScreen(
    state: ApkScanUiState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onClean: () -> Unit,
    onStop: () -> Unit,
    onReconnect: () -> Unit
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BaiZeTokens.colors.surfaceBase),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item { DetailPageHeader("安装包", "找出下载后留在手机里的安装文件", onBack) }
        item {
            DetailTaskCard(
                metric = if (state.totalFiles > 0) Formatter.formatFileSize(context, state.totalBytes) else "扫描安装包",
                metricLabel = if (state.totalFiles > 0) "${state.totalFiles} 个安装文件" else "APK · APKS · XAPK · APKM",
                phase = state.phase,
                running = state.running,
                ready = state.cleanReady,
                scanEnabled = state.connected,
                cleanEnabled = state.connected,
                onScan = onScan, onClean = onClean, onStop = onStop, onReconnect = onReconnect,
                cleanLabel = "清理 ${state.totalFiles} 个安装包"
            )
        }
        item {
            DetailSectionHeader("安装包明细", if (state.totalFiles > 0) {
                "${state.totalFiles} 个文件" + if (state.totalFiles > state.items.size) " · 展示前 ${state.items.size} 项" else " · 仅删除本次扫描到的文件"
            } else "不会影响已经安装的应用")
        }
        if (state.items.isEmpty()) {
            item {
                DetailEmptyState(
                    title = when { state.running -> "正在查找安装包"; state.coverage.isNotEmpty() -> "没有发现安装包"; else -> "还没有扫描结果" },
                    description = if (state.running) "正在检查手机存储与可用的外部存储。" else "完成扫描后，文件名称、位置和大小会显示在这里。",
                    icon = Icons.Rounded.InstallMobile
                )
            }
        } else itemsIndexed(state.items, key = { _, item -> "${item.name}|${item.samplePath}" }) { index, item ->
            ApkResultCard(item, first = index == 0, last = index == state.items.lastIndex)
        }
        if (state.coverage.isNotEmpty()) {
            item { DetailSectionHeader("扫描范围", "已读取 ${state.coverage.count { it.status == "scanned" || it.status == "partial" }} 个来源") }
            itemsIndexed(state.coverage.take(40), key = { _, item -> "${item.group}|${item.path}" }) { index, item ->
                val status = if (item.status == "scanned") "已读取" else "部分可用"
                val summary = "${item.files} 个文件 · ${Formatter.formatFileSize(context, item.bytes)}"
                DetailResultRow(
                    title = item.group, value = status, summary = item.reason.ifBlank { summary }, path = item.path,
                    details = listOf(status, summary, item.path, item.reason).filter { it.isNotBlank() }.joinToString("\n\n"),
                    icon = Icons.Rounded.Folder, first = index == 0, last = index == minOf(state.coverage.size, 40) - 1,
                    accent = if (item.status == "scanned") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }
        if (state.output.isNotBlank()) item { DetailExpandableText("查看任务详情", state.output) }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
private fun ApkResultCard(item: ApkScanItem, first: Boolean, last: Boolean) {
    val context = LocalContext.current
    val size = Formatter.formatFileSize(context, item.bytes)
    val summary = "${item.files} 项" + if (item.errors > 0) " · 异常 ${item.errors}" else " · 安装文件"
    DetailResultRow(
        title = item.name, value = size, summary = summary, path = item.samplePath,
        details = listOf("$summary · $size", item.samplePath).filter { it.isNotBlank() }.joinToString("\n\n"),
        icon = Icons.Rounded.InstallMobile, first = first, last = last
    )
}
