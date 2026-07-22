package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.IRulePackService
import io.github.xgl34222220.baize.root.RulePackRootService
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class RulePackActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private var service: IRulePackService? = null
    private var bindingRequested = false
    private var showApplyConfirm by mutableStateOf(false)
    private var showRollbackConfirm by mutableStateOf(false)
    private var state by mutableStateOf(RulePackUiState())

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPackage(uri)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IRulePackService.Stub.asInterface(binder)
            bindingRequested = true
            state = state.copy(connected = true, message = "签名验证与回滚引擎已连接")
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bindingRequested = false
            state = state.copy(connected = false, working = false, message = "Root 规则服务已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val appearance = appearanceViewModel.settings.collectAsStateWithLifecycle().value
            BaiZeTheme(appearance) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RulePackScreen(
                        state = state,
                        onBack = ::finish,
                        onImport = {
                            openDocument.launch(
                                arrayOf(
                                    "application/java-archive",
                                    "application/zip",
                                    "application/octet-stream"
                                )
                            )
                        },
                        onRefresh = ::refresh,
                        onApply = { showApplyConfirm = true },
                        onRollback = { showRollbackConfirm = true },
                        onReconnect = ::bindService
                    )
                    if (showApplyConfirm) {
                        AlertDialog(
                            onDismissRequest = { showApplyConfirm = false },
                            title = { Text("安装已验证规则包？") },
                            text = {
                                Text(
                                    "安装前会备份当前托管规则，并原子替换 app、external、hidden 与 deep 规则。custom.rules 不会被修改。"
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showApplyConfirm = false
                                    applyPreview()
                                }) { Text("安装更新") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showApplyConfirm = false }) { Text("取消") }
                            }
                        )
                    }
                    if (showRollbackConfirm) {
                        AlertDialog(
                            onDismissRequest = { showRollbackConfirm = false },
                            title = { Text("回滚到上一版规则？") },
                            text = { Text("将恢复最近一次安装前的完整规则备份。正在执行扫描或清理时不会允许回滚。") },
                            confirmButton = {
                                TextButton(onClick = {
                                    showRollbackConfirm = false
                                    rollback()
                                }) { Text("立即回滚") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRollbackConfirm = false }) { Text("取消") }
                            }
                        )
                    }
                }
            }
        }
        bindService()
    }

    private fun bindService() {
        if (service != null || bindingRequested) return
        state = state.copy(message = "正在连接 Root 规则服务…")
        runCatching {
            RootService.bind(
                Intent(this, RulePackRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
            bindingRequested = true
        }.onFailure {
            bindingRequested = false
            state = state.copy(message = "Root 规则服务启动失败：${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun refresh() {
        val root = service ?: run {
            bindService()
            return
        }
        state = state.copy(working = true, message = "正在读取当前规则包与回滚记录…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    JSONObject(root.getCurrent()) to JSONObject(root.getHistory(12))
                }
            }
            result.onSuccess { (current, history) ->
                state = state.copy(
                    connected = true,
                    working = false,
                    current = parseCurrent(current),
                    history = parseHistory(history.optJSONArray("items")),
                    message = if (state.preview == null) "规则包状态已更新" else state.message
                )
            }.onFailure {
                state = state.copy(working = false, message = "读取规则包状态失败：${it.message}")
            }
        }
    }

    private fun importPackage(uri: Uri) {
        val root = service ?: run {
            state = state.copy(message = "Root 规则服务尚未连接")
            bindService()
            return
        }
        state = state.copy(working = true, preview = null, message = "正在复制并验证签名规则包…")
        lifecycleScope.launch {
            var localFile: File? = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    localFile = copyImport(uri)
                    JSONObject(root.previewPackage(localFile!!.absolutePath))
                }
            }
            runCatching { localFile?.delete() }
            result.onSuccess { json ->
                if (json.has("error")) {
                    state = state.copy(
                        working = false,
                        message = "规则包验证失败：${json.optString("message", json.optString("error"))}"
                    )
                } else {
                    state = state.copy(
                        working = false,
                        preview = parsePreview(json),
                        message = "签名与规则内容验证通过，请核对更新差异"
                    )
                }
            }.onFailure {
                state = state.copy(working = false, message = "导入失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun applyPreview() {
        val root = service ?: return
        val previewId = state.preview?.previewId.orEmpty()
        if (previewId.isBlank()) return
        state = state.copy(working = true, message = "正在备份当前规则并原子安装更新…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { JSONObject(root.applyPreview(previewId)) } }
            result.onSuccess { json ->
                if (json.has("error")) {
                    state = state.copy(working = false, message = "安装失败：${json.optString("message")}")
                } else {
                    state = state.copy(
                        working = false,
                        preview = null,
                        current = parseCurrent(json),
                        message = "规则包 ${json.optString("version")} 已安装，可一键回滚"
                    )
                    refresh()
                }
            }.onFailure {
                state = state.copy(working = false, message = "安装失败：${it.message}")
            }
        }
    }

    private fun rollback() {
        val root = service ?: return
        state = state.copy(working = true, message = "正在恢复上一版规则…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { JSONObject(root.rollback()) } }
            result.onSuccess { json ->
                if (json.has("error")) {
                    state = state.copy(working = false, message = "回滚失败：${json.optString("message")}")
                } else {
                    state = state.copy(
                        working = false,
                        preview = null,
                        current = parseCurrent(json),
                        message = "已回滚到 ${json.optString("version", "上一版规则")}"
                    )
                    refresh()
                }
            }.onFailure {
                state = state.copy(working = false, message = "回滚失败：${it.message}")
            }
        }
    }

    private fun copyImport(uri: Uri): File {
        val directory = File(cacheDir, "rule-pack-imports").apply { mkdirs() }
        directory.listFiles()?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > IMPORT_TTL_MS }
            ?.forEach { it.delete() }
        val target = File(directory, "${UUID.randomUUID()}.jar")
        val input = contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
        input.use { source ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_IMPORT_BYTES) error("规则包超过 ${MAX_IMPORT_BYTES / 1024 / 1024}MB")
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        return target
    }

    private fun parseCurrent(json: JSONObject): CurrentRulePack = CurrentRulePack(
        packId = json.optString("packId", "baize-bundled"),
        version = json.optString("version", "bundled"),
        digest = json.optString("digest"),
        totalRules = json.optInt("totalRules", 0),
        totalBytes = json.optLong("totalBytes", 0L),
        installedAt = json.optLong("installedAt", 0L),
        signerSha256 = json.optString("signerSha256"),
        rollbackAvailable = json.optBoolean("rollbackAvailable")
    )

    private fun parsePreview(json: JSONObject): RulePackPreview {
        val files = buildList {
            val array = json.optJSONArray("files") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    RuleFileDiff(
                        name = item.optString("name"),
                        status = item.optString("status"),
                        currentRules = item.optInt("currentRules"),
                        incomingRules = item.optInt("incomingRules"),
                        ruleDelta = item.optInt("ruleDelta")
                    )
                )
            }
        }
        return RulePackPreview(
            previewId = json.optString("previewId"),
            packId = json.optString("packId"),
            version = json.optString("version"),
            releaseNotes = json.optString("releaseNotes"),
            signerSha256 = json.optString("signerSha256"),
            currentVersion = json.optString("currentVersion"),
            currentRules = json.optInt("currentRules"),
            incomingRules = json.optInt("incomingRules"),
            files = files
        )
    }

    private fun parseHistory(array: JSONArray?): List<RulePackHistory> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(
                RulePackHistory(
                    action = item.optString("action"),
                    version = item.optString("version"),
                    time = item.optLong("time"),
                    digest = item.optString("digest")
                )
            )
        }
    }

    override fun onDestroy() {
        if (bindingRequested) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }

    companion object {
        private const val MAX_IMPORT_BYTES = 32L * 1024L * 1024L
        private const val IMPORT_TTL_MS = 60L * 60_000L
    }
}

private data class RulePackUiState(
    val connected: Boolean = false,
    val working: Boolean = false,
    val message: String = "正在连接 Root 规则服务…",
    val current: CurrentRulePack = CurrentRulePack(),
    val preview: RulePackPreview? = null,
    val history: List<RulePackHistory> = emptyList()
)

private data class CurrentRulePack(
    val packId: String = "baize-bundled",
    val version: String = "bundled",
    val digest: String = "",
    val totalRules: Int = 0,
    val totalBytes: Long = 0L,
    val installedAt: Long = 0L,
    val signerSha256: String = "",
    val rollbackAvailable: Boolean = false
)

private data class RulePackPreview(
    val previewId: String,
    val packId: String,
    val version: String,
    val releaseNotes: String,
    val signerSha256: String,
    val currentVersion: String,
    val currentRules: Int,
    val incomingRules: Int,
    val files: List<RuleFileDiff>
)

private data class RuleFileDiff(
    val name: String,
    val status: String,
    val currentRules: Int,
    val incomingRules: Int,
    val ruleDelta: Int
)

private data class RulePackHistory(
    val action: String,
    val version: String,
    val time: Long,
    val digest: String
)

@Composable
private fun RulePackScreen(
    state: RulePackUiState,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
    onApply: () -> Unit,
    onRollback: () -> Unit,
    onReconnect: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "返回") }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("RULE PACK", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.3.sp)
                    Text("规则管理中心", fontSize = 32.sp, fontWeight = FontWeight.Black)
                    Text("证书校验 · 更新预览 · 原子安装 · 一键回滚", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(58.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = if (state.connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (state.connected) Icons.Rounded.Security else Icons.Rounded.Warning,
                                    contentDescription = null,
                                    tint = if (state.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(29.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (state.connected) "受信任规则引擎" else "规则引擎未连接", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (state.working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (!state.connected) {
                        OutlinedButton(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("重新连接 Root 服务")
                        }
                    }
                }
            }
        }

        item { CurrentRulePackCard(state.current) }

        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("规则包操作", fontWeight = FontWeight.Black, fontSize = 20.sp)
                    Text(
                        "仅接受由当前白泽 APK 同一证书签名的完整 JAR/ZIP 规则包。导入后先显示差异，不会立即覆盖。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    Button(
                        onClick = onImport,
                        enabled = state.connected && !state.working,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.Rounded.Rule, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("导入已签名规则包", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onRollback,
                        enabled = state.connected && !state.working && state.current.rollbackAvailable,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(17.dp)
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.current.rollbackAvailable) "回滚到上一版规则" else "暂无可回滚版本")
                    }
                }
            }
        }

        state.preview?.let { preview ->
            item { PreviewCard(preview, state.working, onApply) }
        }

        if (state.history.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("HISTORY", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("安装与回滚记录", fontSize = 24.sp, fontWeight = FontWeight.Black)
                }
            }
            item { HistoryCard(state.history) }
        }

        item {
            OutlinedButton(
                onClick = onRefresh,
                enabled = state.connected && !state.working,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("刷新规则状态")
            }
        }
    }
}

@Composable
private fun CurrentRulePackCard(current: CurrentRulePack) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("当前规则包", fontWeight = FontWeight.Black, fontSize = 19.sp)
            }
            Text(current.version, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text(
                "${current.totalRules} 条有效规则 · ${Formatter.formatFileSize(context, current.totalBytes)}",
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text("规则集指纹 ${current.digest.take(16).ifBlank { "尚未生成" }}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (current.signerSha256.isNotBlank()) {
                Text("签名证书 ${current.signerSha256.take(16)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            } else {
                Text("模块内置规则 · 尚未通过管理中心安装", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun PreviewCard(preview: RulePackPreview, working: Boolean, onApply: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("SIGNED PREVIEW", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("${preview.currentVersion} → ${preview.version}", fontSize = 25.sp, fontWeight = FontWeight.Black)
            Text(
                "证书 ${preview.signerSha256.take(16)} · 规则 ${preview.currentRules} → ${preview.incomingRules}",
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontSize = 12.sp
            )
            if (preview.releaseNotes.isNotBlank()) {
                Text(preview.releaseNotes, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            HorizontalDivider()
            preview.files.forEach { file -> RuleDiffRow(file) }
            FilledTonalButton(
                onClick = onApply,
                enabled = !working,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("备份并安装此规则包", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RuleDiffRow(file: RuleFileDiff) {
    val label = when (file.status) {
        "added" -> "新增"
        "removed" -> "移除"
        "changed" -> "更新"
        else -> "不变"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(file.name, fontWeight = FontWeight.Bold)
            Text("${file.currentRules} → ${file.incomingRules} 条", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Text(
            if (file.ruleDelta == 0) label else "$label ${if (file.ruleDelta > 0) "+" else ""}${file.ruleDelta}",
            color = MaterialTheme.colorScheme.secondary,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun HistoryCard(history: List<RulePackHistory>) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(25.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            history.forEachIndexed { index, item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (item.action == "rollback") Icons.Rounded.Refresh else Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (item.action == "rollback") "规则回滚" else "规则安装", fontWeight = FontWeight.Bold)
                        Text(item.version.ifBlank { "未知版本" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Text(item.digest.take(8), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                if (index != history.lastIndex) HorizontalDivider()
            }
        }
    }
}
