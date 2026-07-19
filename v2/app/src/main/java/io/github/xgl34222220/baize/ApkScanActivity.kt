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
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
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

class ApkScanActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private var service: IProfileRootService? = null
    private var serviceBound = false
    private var pollJob: Job? = null
    private var screenState by mutableStateOf(ApkScanUiState())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IProfileRootService.Stub.asInterface(binder)
            serviceBound = true
            screenState = screenState.copy(
                connected = true,
                status = "Root 安装包扫描引擎已连接",
                phase = if (screenState.running) screenState.phase else "点击下方按钮开始扫描"
            )
            recoverRunningTask()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            serviceBound = false
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
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ApkScanScreen(
                        state = screenState,
                        onBack = ::finish,
                        onScan = ::startScan,
                        onStop = ::stopTask,
                        onReconnect = ::connectService
                    )
                }
            }
        }
        connectService()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        if (serviceBound) runCatching { RootService.unbind(connection) }
        serviceBound = false
        service = null
        super.onDestroy()
    }

    private fun connectService() {
        if (service != null || serviceBound) return
        screenState = screenState.copy(
            connected = false,
            status = "正在连接 Root 安装包扫描引擎…",
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
            screenState = screenState.copy(phase = "安装包扫描仍在运行，请先停止或等待完成")
            return
        }
        val root = service
        if (root == null) {
            screenState = screenState.copy(phase = "Root 服务尚未连接，正在重新连接…")
            connectService()
            return
        }

        screenState = screenState.copy(
            running = true,
            phase = "正在扫描 APK / APKS / XAPK…",
            items = emptyList(),
            output = ""
        )
        startPolling()
        lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) {
                runCatching { JSONObject(root.runModuleTask("apk-scan")) }
            }
            pollJob?.cancel()
            if (response.isFailure) {
                screenState = screenState.copy(
                    running = false,
                    phase = "安装包扫描失败：${response.exceptionOrNull()?.message ?: "Root 服务异常"}"
                )
                return@launch
            }

            val json = response.getOrThrow()
            if (json.optString("error") == "busy" || json.optInt("exitCode") == 3) {
                screenState = screenState.copy(
                    running = false,
                    phase = json.optString("message", "当前已有其他扫描或清理任务正在运行")
                )
                return@launch
            }

            val latest = json.optJSONObject("latest") ?: JSONObject()
            val items = parseItems(json.optJSONArray("otherDetails"))
            val success = json.optBoolean("success")
            val cancelled = json.optBoolean("cancelled")
            val result = latest.optString("result").ifBlank {
                when {
                    cancelled -> "安装包扫描已停止"
                    success && items.isEmpty() -> "没有发现超过保留期的安装包"
                    success -> "安装包扫描完成，发现 ${items.sumOf { it.files }} 项"
                    else -> json.optString("message", "安装包扫描失败")
                }
            }
            screenState = screenState.copy(
                running = false,
                phase = result,
                items = items,
                output = json.optString("output").trim().takeLast(6000)
            )
        }
    }

    private fun stopTask() {
        if (!screenState.running) {
            screenState = screenState.copy(phase = "当前没有正在运行的安装包扫描")
            return
        }
        service?.cancelCurrentTask()
        screenState = screenState.copy(phase = "正在安全停止安装包扫描…")
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
            screenState = screenState.copy(running = true)
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
                delay(350)
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
                append(json.optString("phase", "正在扫描安装包"))
                if (total > 0) append(" · $current/$total")
                if (path.isNotBlank()) append("\n").append(path.takeLast(96))
                if (json.optBoolean("cancelRequested")) append("\n正在停止…")
            }
        )
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

private data class ApkScanUiState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val status: String = "正在等待 Root 服务…",
    val phase: String = "准备扫描安装包",
    val items: List<ApkScanItem> = emptyList(),
    val output: String = ""
)

private data class ApkScanItem(
    val name: String,
    val files: Long,
    val bytes: Long,
    val errors: Long,
    val samplePath: String
)

@Composable
private fun ApkScanScreen(
    state: ApkScanUiState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onStop: () -> Unit,
    onReconnect: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 28.dp),
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
                        "APK PACKAGE SCAN",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Text("安装包扫描", fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text(
                        "查找 APK、APKS 与 XAPK，不在进入页面时自动扫描",
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
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(18.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.InstallMobile,
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
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (state.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onScan,
                            enabled = state.connected && !state.running,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(if (state.items.isEmpty()) "开始扫描" else "重新扫描")
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
                    "SCAN RESULTS",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Text("扫描结果", fontSize = 26.sp, fontWeight = FontWeight.Black)
                Text(
                    if (state.items.isEmpty()) "完成扫描后在这里查看安装包路径、数量与大小" else "发现 ${state.items.sumOf { it.files }} 项安装包",
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
                        if (state.running) "正在扫描，请稍候…" else "尚未生成安装包扫描结果",
                        modifier = Modifier.padding(22.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(state.items, key = { "${it.name}|${it.samplePath}" }) { item ->
                ApkResultCard(item)
            }
        }

        if (state.output.isNotBlank()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .navigationBarsPadding(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("任务输出", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.output,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ApkResultCard(item: ApkScanItem) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Rounded.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${item.files} 项 · ${Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current, item.bytes)}" +
                        if (item.errors > 0) " · 异常 ${item.errors}" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                if (item.samplePath.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        item.samplePath,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
