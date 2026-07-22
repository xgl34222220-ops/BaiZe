package io.github.xgl34222220.baize

import android.os.Bundle
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

class RuleUpdateActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private var showInstallConfirm by mutableStateOf(false)
    private var state by mutableStateOf(RuleUpdateUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val settings = RuleUpdateClient.loadSettings(this)
        state = state.copy(settings = settings, message = settings.lastResult)
        setContent {
            val appearance = appearanceViewModel.settings.collectAsStateWithLifecycle().value
            BaiZeTheme(appearance) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RuleUpdateScreen(
                        state = state,
                        onBack = ::finish,
                        onRefresh = ::refreshCurrent,
                        onCheck = ::checkUpdate,
                        onDownload = ::downloadAndPreview,
                        onInstall = { showInstallConfirm = true },
                        onChannel = ::changeChannel,
                        onPolicy = ::changePolicy
                    )
                    if (showInstallConfirm) {
                        AlertDialog(
                            onDismissRequest = { showInstallConfirm = false },
                            title = { Text("安装在线规则更新？") },
                            text = {
                                Text(
                                    "签名索引和规则包均已通过当前 APK 同证书校验。安装前仍会创建 Root 私有备份，可在规则管理中心一键回滚。"
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showInstallConfirm = false
                                    installPreview()
                                }) { Text("立即安装") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showInstallConfirm = false }) { Text("取消") }
                            }
                        )
                    }
                }
            }
        }
        refreshCurrent()
    }

    private fun refreshCurrent() {
        if (state.working) return
        state = state.copy(working = true, message = "正在读取当前规则与签名索引检查点…")
        lifecycleScope.launch {
            val session = runCatching { RuleUpdateClient.connect(this@RuleUpdateActivity) }.getOrElse {
                state = state.copy(working = false, connected = false, message = "Root 规则服务连接失败：${it.message}")
                return@launch
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    val current = JSONObject(session.pack.getCurrent())
                    val checkpoint = JSONObject(session.index.getCheckpoint(state.settings.channel))
                    current to checkpoint
                }
                state = state.copy(
                    working = false,
                    connected = true,
                    currentVersion = result.first.optString("version", "bundled"),
                    currentVersionCode = result.first.optLong("versionCode", 0L),
                    currentRules = result.first.optInt("totalRules", 0),
                    checkpointAt = result.second.optLong("generatedAt", 0L),
                    message = state.settings.lastResult
                )
            } catch (error: Throwable) {
                state = state.copy(working = false, connected = false, message = "读取规则状态失败：${error.message}")
            } finally {
                session.close()
            }
        }
    }

    private fun checkUpdate() {
        if (state.working) return
        state = state.copy(
            working = true,
            progress = 0f,
            release = null,
            previewId = "",
            message = "正在断点下载并验证 ${RuleUpdateWorker.channelLabel(state.settings.channel)} 签名索引…"
        )
        lifecycleScope.launch {
            val session = runCatching { RuleUpdateClient.connect(this@RuleUpdateActivity) }.getOrElse {
                state = state.copy(working = false, connected = false, message = "Root 规则服务连接失败：${it.message}")
                return@launch
            }
            try {
                val check = RuleUpdateClient.check(
                    this@RuleUpdateActivity,
                    session,
                    state.settings.channel
                ) { progress -> updateProgress(progress, "正在下载签名索引") }
                val text = if (check.release == null) {
                    "${RuleUpdateWorker.channelLabel(check.channel)}规则已是最新版本 ${check.currentVersion}"
                } else {
                    "发现可信规则 ${check.release.version}，索引和下载地址已通过 Root 签名校验"
                }
                RuleUpdateClient.recordResult(this@RuleUpdateActivity, text)
                val settings = RuleUpdateClient.loadSettings(this@RuleUpdateActivity)
                state = state.copy(
                    working = false,
                    connected = true,
                    progress = 1f,
                    currentVersion = check.currentVersion,
                    currentVersionCode = check.currentVersionCode,
                    currentRules = check.currentRules,
                    indexGeneratedAt = check.generatedAt,
                    indexExpiresAt = check.expiresAt,
                    signerSha256 = check.signerSha256,
                    release = check.release,
                    settings = settings,
                    message = text
                )
            } catch (error: Throwable) {
                val text = "在线规则检查失败：${error.message ?: error.javaClass.simpleName}"
                RuleUpdateClient.recordResult(this@RuleUpdateActivity, text)
                state = state.copy(working = false, progress = 0f, message = text)
            } finally {
                session.close()
            }
        }
    }

    private fun downloadAndPreview() {
        if (state.working) return
        val release = state.release ?: return
        state = state.copy(working = true, progress = 0f, previewId = "", message = "正在断点下载 ${release.version}…")
        lifecycleScope.launch {
            val session = runCatching { RuleUpdateClient.connect(this@RuleUpdateActivity) }.getOrElse {
                state = state.copy(working = false, message = "Root 规则服务连接失败：${it.message}")
                return@launch
            }
            try {
                val ready = RuleUpdateClient.downloadRelease(
                    this@RuleUpdateActivity,
                    release
                ) { progress -> updateProgress(progress, if (progress.resumed) "正在续传规则包" else "正在下载规则包") }
                state = state.copy(message = "下载 SHA-256 通过，正在执行 APK 同证书复验…")
                val preview = RuleUpdateClient.previewRelease(this@RuleUpdateActivity, session, release, ready)
                val text = "${release.version} 已下载并通过签名索引、SHA-256 与 APK 同证书复验"
                RuleUpdateClient.recordResult(this@RuleUpdateActivity, text)
                state = state.copy(
                    working = false,
                    connected = true,
                    progress = 1f,
                    previewId = preview.optString("previewId"),
                    incomingRules = preview.optInt("incomingRules", 0),
                    message = text,
                    settings = RuleUpdateClient.loadSettings(this@RuleUpdateActivity)
                )
            } catch (error: Throwable) {
                val text = "规则包下载或复验失败：${error.message ?: error.javaClass.simpleName}"
                RuleUpdateClient.recordResult(this@RuleUpdateActivity, text)
                state = state.copy(working = false, progress = 0f, previewId = "", message = text)
            } finally {
                session.close()
            }
        }
    }

    private fun installPreview() {
        if (state.working || state.previewId.isBlank()) return
        state = state.copy(working = true, message = "正在备份当前规则并原子安装更新…")
        lifecycleScope.launch {
            val session = runCatching { RuleUpdateClient.connect(this@RuleUpdateActivity) }.getOrElse {
                state = state.copy(working = false, message = "Root 规则服务连接失败：${it.message}")
                return@launch
            }
            try {
                val result = withContext(Dispatchers.IO) { JSONObject(session.pack.applyPreview(state.previewId)) }
                if (result.has("error")) error(result.optString("message", result.optString("error")))
                val version = result.optString("version", state.release?.version.orEmpty())
                val text = "规则 $version 已安装，旧版本已进入 Root 回滚备份"
                RuleUpdateClient.recordResult(this@RuleUpdateActivity, text)
                state = state.copy(
                    working = false,
                    progress = 1f,
                    previewId = "",
                    release = null,
                    currentVersion = version,
                    currentVersionCode = result.optLong("versionCode", state.currentVersionCode),
                    currentRules = result.optInt("totalRules", state.incomingRules),
                    message = text,
                    settings = RuleUpdateClient.loadSettings(this@RuleUpdateActivity)
                )
            } catch (error: Throwable) {
                state = state.copy(working = false, message = "规则安装失败：${error.message}")
            } finally {
                session.close()
            }
        }
    }

    private fun changeChannel(channel: String) {
        if (state.working) return
        val normalized = if (channel == "beta") "beta" else "stable"
        val policy = if (normalized == "beta" && state.settings.policy == "install") "download" else state.settings.policy
        val settings = state.settings.copy(channel = normalized, policy = policy)
        RuleUpdateWorker.configure(this, settings)
        state = state.copy(
            settings = RuleUpdateClient.loadSettings(this),
            release = null,
            previewId = "",
            progress = 0f,
            message = "已切换到 ${RuleUpdateWorker.channelLabel(normalized)}，请重新检查签名索引"
        )
    }

    private fun changePolicy(policy: String) {
        if (state.working) return
        val normalized = when (policy) {
            "notify", "download", "install" -> policy
            else -> "manual"
        }
        if (state.settings.channel == "beta" && normalized == "install") {
            state = state.copy(message = "Beta 通道禁止静默安装，可选择自动下载后人工确认")
            return
        }
        RuleUpdateWorker.configure(this, state.settings.copy(policy = normalized))
        state = state.copy(
            settings = RuleUpdateClient.loadSettings(this),
            message = "自动更新策略：${RuleUpdateWorker.policyLabel(normalized)}"
        )
    }

    private fun updateProgress(progress: RuleDownloadProgress, phase: String) {
        val ratio = if (progress.total > 0L) {
            (progress.downloaded.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f)
        } else 0f
        runOnUiThread {
            state = state.copy(
                progress = ratio,
                downloadedBytes = progress.downloaded,
                totalBytes = progress.total,
                message = "$phase · ${Formatter.formatFileSize(this, progress.downloaded)}" +
                    if (progress.total > 0L) " / ${Formatter.formatFileSize(this, progress.total)}" else ""
            )
        }
    }
}

private data class RuleUpdateUiState(
    val connected: Boolean = false,
    val working: Boolean = false,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val message: String = "正在读取在线规则更新状态…",
    val currentVersion: String = "bundled",
    val currentVersionCode: Long = 0L,
    val currentRules: Int = 0,
    val checkpointAt: Long = 0L,
    val indexGeneratedAt: Long = 0L,
    val indexExpiresAt: Long = 0L,
    val signerSha256: String = "",
    val release: RuleRelease? = null,
    val previewId: String = "",
    val incomingRules: Int = 0,
    val settings: RuleUpdateSettings = RuleUpdateSettings()
)

@Composable
private fun RuleUpdateScreen(
    state: RuleUpdateUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onChannel: (String) -> Unit,
    onPolicy: (String) -> Unit
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
                    Text("TRUSTED UPDATE", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.3.sp)
                    Text("官方规则更新", fontSize = 32.sp, fontWeight = FontWeight.Black)
                    Text("签名索引 · 防回放 · 断点续传 · 双重 Root 复验", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item { RuleUpdateStatusCard(state, onRefresh) }
        item { CurrentOnlineRuleCard(state) }
        item { ChannelPolicyCard(state, onChannel, onPolicy) }
        item {
            Button(
                onClick = onCheck,
                enabled = !state.working,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(54.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(Icons.Rounded.SystemUpdate, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("检查签名规则索引", fontWeight = FontWeight.Bold)
            }
        }

        state.release?.let { release ->
            item { ReleaseCard(state, release, onDownload, onInstall) }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("更新安全边界", fontWeight = FontWeight.Black, fontSize = 17.sp)
                    Text("• 在线索引与规则包必须由当前 APK 同一证书签名。", fontSize = 12.sp)
                    Text("• Root 保存通道最高索引时间，拒绝旧索引回放和同时间不同内容。", fontSize = 12.sp)
                    Text("• 下载只允许官方 HTTPS 域名，并校验索引声明的体积与 SHA-256。", fontSize = 12.sp)
                    Text("• 自动安装仅限稳定通道、Wi-Fi、充电且设备空闲；Root 忙时会延后。", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun RuleUpdateStatusCard(state: RuleUpdateUiState, onRefresh: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(19.dp),
                    color = if (state.connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (state.connected) Icons.Rounded.Security else Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = if (state.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (state.connected) "可信更新引擎" else "更新引擎未连接", fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            if (state.working) {
                if (state.progress > 0f) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            OutlinedButton(onClick = onRefresh, enabled = !state.working, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("刷新当前状态")
            }
        }
    }
}

@Composable
private fun CurrentOnlineRuleCard(state: RuleUpdateUiState) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("当前规则", fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
            Text(state.currentVersion, fontWeight = FontWeight.Black, fontSize = 27.sp)
            Text("版本序号 ${state.currentVersionCode} · ${state.currentRules} 条规则", color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (state.checkpointAt > 0L) {
                Text("防回放检查点 ${formatTime(state.checkpointAt)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun ChannelPolicyCard(
    state: RuleUpdateUiState,
    onChannel: (String) -> Unit,
    onPolicy: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("更新通道", fontWeight = FontWeight.Black, fontSize = 19.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.settings.channel == "stable", onClick = { onChannel("stable") }, label = { Text("稳定版") })
                FilterChip(selected = state.settings.channel == "beta", onClick = { onChannel("beta") }, label = { Text("Beta") })
            }
            Text("自动更新策略", fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("manual", "notify", "download", "install").forEach { policy ->
                    FilterChip(
                        selected = state.settings.policy == policy,
                        onClick = { onPolicy(policy) },
                        enabled = policy != "install" || state.settings.channel == "stable",
                        label = { Text(RuleUpdateWorker.policyLabel(policy)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Text("后台每 12 小时检查一次；下载策略仅使用非计费网络。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ReleaseCard(
    state: RuleUpdateUiState,
    release: RuleRelease,
    onDownload: () -> Unit,
    onInstall: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (release.mandatory) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (release.mandatory) "重要规则更新" else "发现可信规则更新", fontWeight = FontWeight.Black, fontSize = 19.sp)
            }
            Text("${state.currentVersion} → ${release.version}", fontWeight = FontWeight.Black, fontSize = 25.sp)
            Text("版本序号 ${release.versionCode} · ${Formatter.formatFileSize(context, release.bytes)}")
            Text(release.releaseNotes.ifBlank { "此版本未提供额外说明" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("发布时间 ${formatTime(release.publishedAt)}", fontSize = 12.sp)
            Text("包指纹 ${release.sha256.take(16)}", fontSize = 12.sp)
            if (state.signerSha256.isNotBlank()) Text("索引签名 ${state.signerSha256.take(16)}", fontSize = 12.sp)
            Button(
                onClick = onDownload,
                enabled = !state.working,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(17.dp)
            ) {
                Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.previewId.isBlank()) "断点下载并双重验证" else "重新下载并验证")
            }
            OutlinedButton(
                onClick = onInstall,
                enabled = !state.working && state.previewId.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(17.dp)
            ) {
                Icon(Icons.Rounded.SystemUpdate, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.previewId.isNotBlank()) "安装已验证规则" else "下载验证后可安装")
            }
        }
    }
}

private fun formatTime(value: Long): String = if (value <= 0L) "未知" else
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
