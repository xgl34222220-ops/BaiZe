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
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

import android.app.Application
import android.content.ContextWrapper
import android.os.CancellationSignal
import androidx.activity.compose.BackHandler
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class ApkScanActivity : ComponentActivity() {
    private val storagePermission = StoragePermissionRequest(this) { session.resumePermission() }
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private val scanViewModel: ApkScanViewModel by viewModels()
    internal val session get() = scanViewModel.session
    private val screenState: ApkScanUiState get() = session.screenState
    private var showLocalModeConfirm by mutableStateOf(false)
    private var confirmStop by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session.initialize()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val appearance by appearanceViewModel.settings.collectAsState()
            BackHandler { requestBack() }
            LaunchedEffect(session) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    session.permissionEvents.collect {
                        storagePermission.launch()
                    }
                }
            }
            BaiZeTheme(appearance) {
                Surface(modifier = Modifier.fillMaxSize(), color = BaiZeTokens.colors.surfaceBase) {
                    ApkScanScreen(state = screenState, onBack = ::requestBack,
                        onScan = ::startScan, onClean = session::prepareCleanReview,
                        onStop = session::stopTask, onReconnect = session::reconnectService,
                        onLocalMode = { showLocalModeConfirm = true },
                        onToggle = ::toggleItem, onToggleAll = session::toggleAll,
                        onQuery = session::query, onFilter = session::filter,
                        loadArchive = session::loadArchivePreview, diagnoseFile = session::fileReadDiagnostics,
                        onReviewLegacyProtection = {
                            session.requireProtectionRescan()
                            CleanerNavigation.openFrom(this, Intent(this, LegacyProtectionRecoveryActivity::class.java))
                        },
                        onManageProtection = { item ->
                            session.requireProtectionRescan()
                            CleanerNavigation.openFrom(this, WhitelistActivity.forFile(this, item.samplePath))
                        })
                    if (screenState.reviewRequested) IndexedCleanupReviewDialog(screenState.running, screenState.selected.size,
                        screenState.reviewMessage, session::cleanSnapshot, session::dismissCleanReview)
                    if (confirmStop == session.operationToken && screenState.running) BaiZeDialog(
                        onDismissRequest = { confirmStop = null }, title = { Text("停止当前任务并返回？") },
                        text = { Text("已经完成的移动可在回收站恢复，其余文件会保留。也可以继续查看当前进度。") },
                        confirmButton = { BaiZeDialogButton(onClick = { confirmStop = null; session.stopTask(); finish() }) { Text("停止并返回") } },
                        dismissButton = { BaiZeDialogButton(onClick = { confirmStop = null }) { Text("继续查看") } })
                    if (showLocalModeConfirm) BaiZeDialog(
                        onDismissRequest = { showLocalModeConfirm = false }, title = { Text("仅使用本地安装包清理？") },
                        text = { Text("适用于从未使用 Root 保护设置的手机。此模式只核对本地文件和本地保护规则；如你在旧版本设置过 Root 白名单，请连接 Root 后再清理。文件仍需手动勾选并确认删除。") },
                        confirmButton = { BaiZeDialogButton(onClick = { showLocalModeConfirm = false; session.enableLocalMode() }) { Text("使用本地模式") } },
                        dismissButton = { BaiZeDialogButton(onClick = { showLocalModeConfirm = false }) { Text("取消") } })
                }
            }
        }
    }
    override fun onResume() { super.onResume(); session.resumePermission() }
    private fun requestBack() { if (screenState.running) confirmStop = session.operationToken else finish() }
    private fun startScan() = session.startScan()
    private fun toggleItem(uri: String) = session.toggleItem(uri)
}

internal class ApkScanViewModel(application: Application) : AndroidViewModel(application) {
    val session = ApkScanSession(application, viewModelScope)
    override fun onCleared() { session.close() }
}

/** Local scan/review/cleanup owns its lifetime; optional Root callbacks never replace it. */
internal class ApkScanSession(application: Application, private val lifecycleScope: CoroutineScope) : ContextWrapper(application) {
    private var initialized = false
    private var closed = false
    private var waitingPermission = false
    @Volatile private var service: IProfileRootService? = null
    private var serviceBound = false
    @Volatile private var stopRequested = false
    private var scanCancellation: CancellationSignal? = null
    private var directSnapshot: List<DirectApkSnapshot> = emptyList()
    private var contentReview: Map<String, IndexedContentProof> = emptyMap()
    private val permissionRequests = Channel<Unit>(Channel.CONFLATED)
    val permissionEvents = permissionRequests.receiveAsFlow()
    var operationToken = 0L
        private set
    var screenState by mutableStateOf(ApkScanUiState())
        private set
    private val previewCache = ApkPreviewCache { item ->
        ApkArchiveMetadata.inspect(application, item.uri, item.samplePath, item.bytes, item.modifiedSeconds)
    }
    private val connection = object : RootService.Connection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (closed) return
            if (binder == null) { onBindingFailed(name, RootService.BindingFailure.NULL_BINDING); return }
            service = RootServiceClients.profile(binder, applicationContext.cacheDir)
            serviceBound = true
            screenState = screenState.copy(connected = true, status = "Root 保护服务可用")
            refreshProtectionPresentation()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            if (closed) return
            service = null; serviceBound = false
            screenState = screenState.copy(connected = false, status = "Root 保护服务未连接")
            refreshProtectionPresentation()
        }
        override fun onBindingFailed(name: ComponentName?, reason: RootService.BindingFailure) {
            if (closed) return
            service = null; serviceBound = false
            screenState = screenState.copy(connected = false, status = RootConnectionMessages.bindingFailure(reason))
            refreshProtectionPresentation()
        }
        override fun onNullBinding(name: ComponentName?) = onBindingFailed(name, RootService.BindingFailure.NULL_BINDING)
    }
    fun initialize() {
        if (initialized) return
        initialized = true
        screenState = screenState.copy(localModeAvailable = !ApkProtectionStore.rootWasUsed(this))
        refreshProtectionPresentation()
        connectService()
    }
    private fun refreshProtectionPresentation() {
        val remote = service
        lifecycleScope.launch {
            val protection = withContext(Dispatchers.IO) {
                ApkProtectionStore.refresh(applicationContext, ApkProtectionStore.source(applicationContext, remote))
            }
            if (closed || service !== remote) return@launch
            screenState = screenState.copy(
                localModeAvailable = protection is ApkProtectionState.Unknown && !ApkProtectionStore.rootWasUsed(applicationContext),
                protectionNeedsAction = protection is ApkProtectionState.Unknown,
                protectionMessage = when (protection) {
                    is ApkProtectionState.KnownRoot -> ""
                    is ApkProtectionState.LocalOnly -> "本地模式 · 删除前核对文件身份与本地保护规则"
                    is ApkProtectionState.Unknown -> protection.reason
                })
        }
    }
    fun enableLocalMode() {
        if (closed || screenState.running) return
        lifecycleScope.launch {
            val enabled = withContext(Dispatchers.IO) { runCatching { ApkProtectionStore.enableLocalOnly(applicationContext) }.getOrDefault(false) }
            screenState = screenState.copy(localModeAvailable = false,
                protectionNeedsAction = !enabled,
                protectionMessage = if (enabled) "本地模式 · 删除前核对文件身份与本地保护规则"
                    else "本地模式未启用。请先检查旧版保护；若曾使用 Root 保护，还需连接 Root 后再核对。")
        }
    }
    fun connectService() {
        if (closed || service != null || serviceBound) return
        serviceBound = true
        runCatching { RootService.bind(Intent(this, BaiZeProfileRootService::class.java)
            .addCategory(RootService.CATEGORY_DAEMON_MODE), connection) }.onFailure {
            serviceBound = false
            screenState = screenState.copy(connected = false, status = "Root 保护服务暂不可用")
        }
    }
    fun reconnectService() {
        if (screenState.running || closed) return
        if (serviceBound) runCatching { RootService.unbind(connection) }
        service = null; serviceBound = false
        connectService()
    }
    fun resumePermission() {
        if (waitingPermission && ApkMediaStoreIndex.hasAllFilesAccess(this@ApkScanSession)) startScan()
        else if (screenState.protectionReviewRequired && !screenState.running) refreshProtectionPresentation()
    }
    /** Returning from management must never present an old decision as current permission. */
    fun requireProtectionRescan() {
        if (closed || screenState.running) return
        contentReview = emptyMap()
        screenState = screenState.afterProtectionManagement()
    }
    fun permissionScreenUnavailable() { screenState = screenState.copy(phase = "请在系统设置中为白泽开启${SharedStorageAccess.label}") }
    fun query(value: String) { if (!screenState.running) screenState = screenState.copy(query = value, selected = emptySet()) }
    fun filter(value: ApkInstallStatus?) { if (!screenState.running) screenState = screenState.copy(filter = value, selected = emptySet()) }
    fun close() {
        closed = true; stopRequested = true; scanCancellation?.cancel()
        if (serviceBound) runCatching { RootService.unbind(connection) }
        service = null; serviceBound = false; previewCache.clear(); permissionRequests.close()
    }

    fun startScan() {
        if (closed) return
        if (screenState.running) {
            screenState = screenState.copy(phase = "安装包任务仍在运行，请先停止或等待完成")
            return
        }
        stopRequested = false
        operationToken++
        waitingPermission = false
        val cancellation = CancellationSignal().also { scanCancellation = it }
        previewCache.clear()
        directSnapshot = emptyList()
        contentReview = emptyMap()
        screenState = screenState.copy(
            reviewRequested = false,
            selected = emptySet(),
            running = true,
            operation = "scan",
            phase = "正在读取 Android 系统文件索引…",
            items = emptyList(),
            coverage = emptyList(),
            totalFiles = 0,
            totalBytes = 0,
            cleanReady = false,
            output = "",
            scanFailed = false,
            coverageIncomplete = false,
            confirmedMissingRecords = 0,
            protectionReviewRequired = false
        )

        lifecycleScope.launch {
            if (!ApkMediaStoreIndex.hasAllFilesAccess(this@ApkScanSession)) {
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

            if (closed || stopRequested || cancellation.isCanceled) {
                if (!closed) screenState = screenState.copy(running = false, operation = "", phase = "安装包扫描已停止")
                return@launch
            }
            if (!ApkMediaStoreIndex.hasAllFilesAccess(this@ApkScanSession)) {
                screenState = screenState.copy(
                    running = false,
                    operation = "",
                    phase = "需要${SharedStorageAccess.label}才能扫描，请在系统设置中授权"
                )
                waitingPermission = true
                permissionRequests.trySend(Unit)
                return@launch
            }

            val started = SystemClock.elapsedRealtime()
            val indexed = withContext(Dispatchers.IO) { ApkIndexPresenceReview.query(applicationContext, cancellation, service) }
            if (closed) return@launch
            scanCancellation = null
            if (indexed.cancelled || stopRequested) {
                directSnapshot = emptyList()
                screenState = screenState.copy(running = false, operation = "", cleanReady = false,
                    phase = "安装包扫描已停止", output = "未完成的扫描不会作为完整结果使用")
                return@launch
            }
            if (indexed.error != null) {
                directSnapshot = emptyList()
                screenState = screenState.copy(
                    running = false,
                    operation = "",
                    phase = "暂时无法读取文件索引，请检查权限后重试",
                    output = indexed.error,
                    scanFailed = true
                )
                return@launch
            }

            val protection = withContext(Dispatchers.IO) {
                ApkProtectionStore.refresh(applicationContext, ApkProtectionStore.source(applicationContext, service))
            }
            if (closed) return@launch
            val snapshots = indexed.candidates.map { candidate ->
                DirectApkSnapshot(
                    uri = candidate.uri,
                    path = candidate.path,
                    name = candidate.name,
                    bytes = candidate.bytes,
                    modifiedSeconds = candidate.modifiedSeconds,
                    identity = candidate.identity
                )
            }
            directSnapshot = snapshots
            val totalBytes = indexed.candidates.sumOf { it.bytes }
            // Publish indexed files immediately; only visible rows decode archive resources.
            val protectionGuard = ApkDeletionGuard.forContext(applicationContext)
            val items = withContext(Dispatchers.Default) { indexed.candidates.map { candidate ->
                ApkScanItem(
                    name = candidate.name,
                    files = 1,
                    bytes = candidate.bytes,
                    errors = 0,
                    samplePath = candidate.path,
                    uri = candidate.uri,
                    modifiedSeconds = candidate.modifiedSeconds,
                    protectionDetails = protectionGuard.protectionDetails(candidate.path, protection)
                )
            } }
            if (stopRequested) {
                directSnapshot = emptyList()
                screenState = screenState.copy(running = false, operation = "", phase = "安装包扫描已停止")
                return@launch
            }
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
            val coverage = listOf(
                ScanCoverageItem(
                    status = if (indexed.truncated || indexed.missingCheckIncomplete) "partial" else "scanned",
                    group = "MediaStore.Files 系统索引",
                    files = indexed.candidates.size.toLong(),
                    bytes = totalBytes,
                    path = "content://media/external/file",
                    reason = "系统索引 ${indexed.candidates.size} 条 · 当前快照 ${snapshots.size} 条。" +
                        (if (indexed.truncated) "达到 1 万项上限，结果不完整；处理已选后重新扫描可继续查看。" else "已读完本次系统索引。") +
                        (if (indexed.confirmedMissingRecords > 0) "已排除 ${indexed.confirmedMissingRecords} 条文件已不存在的旧记录，未删除文件，也不计入容量。" else "") +
                        (if (indexed.missingCheckIncomplete) "部分文件存在状态尚未核对，已保留这些项目。" else "")
                )
            )
            screenState = screenState.copy(
                running = false,
                operation = "",
                phase = when {
                    indexed.truncated -> "已读取前 ${indexed.candidates.size} 个安装包 · 达到本次上限"
                    indexed.confirmedMissingRecords > 0 -> "当前 ${indexed.candidates.size} 个安装包 · 已排除 ${indexed.confirmedMissingRecords} 条不存在的旧记录"
                    indexed.candidates.isEmpty() -> "快速索引完成：未发现安装包"
                    else -> "快速索引完成：发现 ${indexed.candidates.size} 个安装包 · ${elapsed} ms"
                },
                items = items,
                selected = emptySet(),
                coverage = coverage,
                totalFiles = indexed.candidates.size.toLong(),
                totalBytes = totalBytes,
                cleanReady = snapshots.isNotEmpty(),
                coverageIncomplete = indexed.truncated || indexed.missingCheckIncomplete,
                confirmedMissingRecords = indexed.confirmedMissingRecords,
                localModeAvailable = protection is ApkProtectionState.Unknown && !ApkProtectionStore.rootWasUsed(applicationContext),
                protectionNeedsAction = protection is ApkProtectionState.Unknown,
                protectionMessage = when (protection) {
                    is ApkProtectionState.KnownRoot -> ""
                    is ApkProtectionState.LocalOnly -> "本地模式 · 删除前核对文件身份与本地保护规则"
                    is ApkProtectionState.Unknown -> protection.reason
                },
                output = "MediaStore.Files ${indexed.elapsedMs} ms · 清理快照 ${snapshots.size} 条 · Root 未参与前台扫描"
            )
        }
    }

    fun toggleItem(uri: String) {
        if (screenState.running || screenState.protectionReviewRequired || screenState.selectableVisibleItems.none { it.uri == uri }) return
        screenState = screenState.copy(selected = screenState.selected.toMutableSet().apply {
            if (!add(uri)) remove(uri)
        })
    }

    suspend fun loadArchivePreview(item: ApkScanItem): ApkArchiveInfo {
        val result = try { previewCache.load(item) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ApkArchiveInfo(parseStatus = ApkArchiveParseStatus.FAILED) }
        currentCoroutineContext().ensureActive()
        // Artwork stays in the bounded cache and visible compositions, not every scan row.
        val metadata = result.copy(iconBitmap = null)
        val current = screenState.items.firstOrNull { it.previewKey == item.previewKey } ?: return result
        if (current.archive == metadata) return result
        screenState = screenState.copy(items = screenState.items.map { current ->
            if (current.previewKey == item.previewKey) current.copy(archive = metadata) else current
        })
        return result
    }

    suspend fun fileReadDiagnostics(item: ApkScanItem): String = withContext(Dispatchers.IO) {
        ApkFileReadDiagnostics.collect(applicationContext, item.uri, item.samplePath, service,
            directSnapshot.firstOrNull { it.uri == item.uri && it.path == item.samplePath }?.identity)
    }

    fun toggleAll() {
        if (screenState.running || !screenState.cleanReady) return
        screenState = screenState.toggleAllSelection()
    }

    fun prepareCleanReview() {
        if (closed || screenState.running || !screenState.cleanReady || screenState.selected.isEmpty()) return
        val selected = directSnapshot.filter { it.uri in screenState.selected }
        val token = ++operationToken
        stopRequested = false; contentReview = emptyMap()
        screenState = screenState.copy(running = true, operation = "review", reviewRequested = true,
            reviewMessage = "只读取已选文件内容，可随时取消。", phase = "正在核对已选文件内容…")
        lifecycleScope.launch {
            try {
                val batch = withContext(Dispatchers.IO) { IndexedContentReview.prepare(selected.map {
                    IndexedApkCandidate(0, it.uri, it.path, it.name, it.bytes, it.modifiedSeconds, it.identity)
                }, ApkDeletionGuard.forContext(applicationContext), { closed || stopRequested || operationToken != token }) { done, total ->
                    lifecycleScope.launch { if (operationToken == token && screenState.reviewRequested && screenState.running)
                        screenState = screenState.copy(reviewMessage = "正在核对 $done / $total 个文件，仅核对所选内容…") }
                } }
                if (closed || operationToken != token || stopRequested) return@launch
                contentReview = batch.proofs
                screenState = screenState.copy(running = false, operation = "", selected = batch.proofs.keys,
                    items = screenState.items.map { item -> item.copy(retainedReason = batch.rejected[item.uri]?.let { "已保留 · $it" }
                        ?: if (item.uri in batch.proofs) "" else item.retainedReason) },
                    reviewMessage = "已核对 ${batch.proofs.size} 个文件的当前内容。" +
                        if (batch.rejected.isEmpty()) "确认后移入回收站，共享文件进入同卷隐藏目录，仍可能被其他有文件权限的应用访问。保留 30 天，不立即释放空间；卸载或清空白泽数据会丢失恢复记录，请先处理回收站。内容再变化会保留。" else "${batch.rejected.size} 个无法核对，已保留并取消勾选，原因见列表。",
                    phase = "所选内容已核对，等待确认")
            } catch (_: CancellationException) {
                if (operationToken == token) dismissCleanReview()
            }
        }
    }

    fun dismissCleanReview() {
        if (!screenState.reviewRequested) return
        stopRequested = true; operationToken++; contentReview = emptyMap()
        screenState = screenState.copy(running = false, operation = "", reviewRequested = false,
            reviewMessage = "", phase = "已取消清理确认，文件未删除")
    }

    fun cleanSnapshot() {
        if (closed || screenState.running || !screenState.cleanReady) return
        if (!screenState.reviewRequested || contentReview.isEmpty() || contentReview.keys != screenState.selected) return
        val snapshot = directSnapshot.filter { it.uri in screenState.selected }
        val reviewed = contentReview
        contentReview = emptyMap()
        if (snapshot.isEmpty()) return
        stopRequested = false
        operationToken++

        screenState = screenState.copy(
            running = true,
            reviewRequested = false,
            operation = "clean",
            phase = "正在移入回收站 ${snapshot.size} 个安装包…"
        )
        lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) {
                var deletedFiles = 0
                var deletedBytes = 0L
                var skipped = 0
                var failed = 0
                val removed = mutableSetOf<String>()
                val retained = mutableMapOf<String, String>()
                val protectionDetails = mutableMapOf<String, ApkProtectionDetails>()
                val guard = ApkDeletionGuard.forContext(applicationContext)
                for ((index, item) in snapshot.withIndex()) {
                    if (stopRequested) break
                    var checkedProtection: ApkProtectionState = ApkProtectionState.Unknown("本次未完成保护核对，文件保留。")
                    val moveOutcome = OrdinaryFileTrash.moveReviewed(applicationContext,
                        StorageFileRecord(-1L, item.uri, item.path, item.name, item.bytes, item.modifiedSeconds, "application/vnd.android.package-archive", identity = item.identity),
                        reviewed[item.uri], {
                            ApkProtectionStore.refresh(applicationContext, ApkProtectionStore.source(applicationContext, service)).also {
                                checkedProtection = it
                            }
                        },
                        { stopRequested || closed })
                    val outcome = moveOutcome.result
                    when (outcome) {
                        ApkIndexedDeleteResult.DELETED -> {
                            removed += item.uri
                            deletedFiles += 1
                            deletedBytes += item.bytes
                        }
                        ApkIndexedDeleteResult.CHANGED, ApkIndexedDeleteResult.PROTECTED,
                        ApkIndexedDeleteResult.PROTECTION_UNAVAILABLE, ApkIndexedDeleteResult.UNVERIFIED,
                        ApkIndexedDeleteResult.INVALID -> skipped += 1
                        ApkIndexedDeleteResult.CANCELLED -> Unit
                        ApkIndexedDeleteResult.FAILED -> failed += 1
                    }
                    if (outcome != ApkIndexedDeleteResult.DELETED) {
                        // Keep the rules from the actual move guard; do not explain a later, different snapshot.
                        val details = guard.protectionDetails(item.path, checkedProtection)
                        protectionDetails[item.uri] = details
                        retained[item.uri] = if (outcome == ApkIndexedDeleteResult.PROTECTED) details.retainedLabel
                            else moveOutcome.reason
                    }
                    if (outcome == ApkIndexedDeleteResult.PROTECTION_UNAVAILABLE) {
                        skipped += snapshot.size - index - 1
                        snapshot.drop(index + 1).forEach {
                            retained[it.uri] = outcome.retainedReason()
                            protectionDetails[it.uri] = ApkProtectionDetails(unavailableReason = "本次保护名单未能核对，文件保留。")
                        }
                        break
                    }
                    if (outcome == ApkIndexedDeleteResult.CANCELLED) break
                }
                DirectCleanResult(deletedFiles, deletedBytes, skipped, failed, removed, retained, protectionDetails)
            }
            if (closed) return@launch
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
            directSnapshot = directSnapshot.filterNot { it.uri in result.removed }
            val phase = when {
                result.deletedFiles == 0 && (result.failed > 0 || result.skipped > 0) ->
                    "未确认移入回收站 · 保留 ${result.skipped} 个，${result.failed} 个结果未确认，查看条目原因 · ${elapsed} ms"
                result.failed > 0 || result.skipped > 0 ->
                    "已移入回收站 ${result.deletedFiles} 个，保留 ${result.skipped} 个，${result.failed} 个结果未确认 · ${elapsed} ms"
                else ->
                    "移入回收站 ${result.deletedFiles} 个，尚未释放空间，占用 ${Formatter.formatFileSize(this@ApkScanSession, result.deletedBytes)} · ${elapsed} ms"
            }
            screenState = screenState.copy(
                running = false,
                operation = "",
                cleanReady = directSnapshot.isNotEmpty(),
                phase = if (stopRequested) "清理已停止 · 已移入回收站 ${result.deletedFiles} 个，其余保留" else phase,
                items = screenState.items.filterNot { it.uri in result.removed }.map {
                    it.copy(retainedReason = result.retained[it.uri] ?: it.retainedReason,
                        protectionDetails = result.protectionDetails[it.uri] ?: it.protectionDetails)
                },
                selected = emptySet(),
                totalFiles = directSnapshot.size.toLong(),
                totalBytes = directSnapshot.sumOf { it.bytes },
                protectionMessage = if (result.retained.values.any { it.contains("尚未核对") })
                    "保护名单未核对，已保留文件。可先检查旧版保护，再连接 Root 核对并重新扫描。" else screenState.protectionMessage,
                protectionNeedsAction = result.retained.values.any { it.contains("尚未核对") },
                localModeAvailable = !ApkProtectionStore.rootWasUsed(applicationContext),
                output = "已移入回收站 ${result.deletedFiles} 个，尚未释放空间，占用 ${Formatter.formatFileSize(this@ApkScanSession, result.deletedBytes)}；总耗时 ${elapsed} ms"
            )
        }
    }

    fun stopTask() {
        if (screenState.reviewRequested) { dismissCleanReview(); return }
        if (!screenState.running) {
            screenState = screenState.copy(phase = "当前没有正在运行的安装包任务")
            return
        }
        stopRequested = true
        scanCancellation?.cancel()
        screenState = screenState.copy(phase = "正在安全停止安装包任务…")
    }

}

internal data class DirectApkSnapshot(
    val uri: String,
    val path: String,
    val name: String,
    val bytes: Long,
    val modifiedSeconds: Long,
    val identity: ApkFileIdentity? = null
)

internal data class DirectCleanResult(
    val deletedFiles: Int,
    val deletedBytes: Long,
    val skipped: Int,
    val failed: Int,
    val removed: Set<String> = emptySet(),
    val retained: Map<String, String> = emptyMap(),
    val protectionDetails: Map<String, ApkProtectionDetails> = emptyMap()
)

internal data class ApkScanUiState(
    val reviewRequested: Boolean = false,
    val reviewMessage: String = "",
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
    val output: String = "",
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val filter: ApkInstallStatus? = null,
    val scanFailed: Boolean = false,
    val coverageIncomplete: Boolean = false,
    val localModeAvailable: Boolean = false,
    val protectionMessage: String = "",
    val protectionNeedsAction: Boolean = false,
    val confirmedMissingRecords: Int = 0,
    val protectionReviewRequired: Boolean = false
) {
    fun afterProtectionManagement(): ApkScanUiState = copy(protectionReviewRequired = true,
        cleanReady = false, selected = emptySet(), reviewRequested = false, reviewMessage = "",
        phase = "已打开保护管理；以下为上次核对结果，请重新扫描后再清理")
    val visibleItems: List<ApkScanItem> get() = items.filter { item ->
        matchesCriteria(item) || (item.archive.awaitingInspection && (filter != null || query.isNotBlank()))
    }
    fun matchesCriteria(item: ApkScanItem): Boolean =
        (filter == null || (!item.archive.awaitingInspection && item.archive.status == filter)) && (query.isBlank() ||
            listOf(item.name, item.samplePath, item.archive.appName, item.archive.packageName).any { it.contains(query.trim(), true) })
    val selectableVisibleItems: List<ApkScanItem> get() = if (protectionReviewRequired) emptyList() else visibleItems.filter(::matchesCriteria)
    val allSelected: Boolean get() = selectableVisibleItems.isNotEmpty() && selectableVisibleItems.all { it.uri in selected }
    fun toggleAllSelection(): ApkScanUiState {
        if (running || protectionReviewRequired) return this
        val visible = selectableVisibleItems.map { it.uri }.toSet()
        return copy(selected = if (allSelected) selected - visible else selected + visible)
    }
    val selectedBytes: Long get() = items.filter { it.uri in selected }.sumOf { it.bytes }
}

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
    val samplePath: String,
    val uri: String = samplePath,
    val archive: ApkArchiveInfo = ApkArchiveInfo(),
    val modifiedSeconds: Long = 0L,
    val retainedReason: String = "",
    val protectionDetails: ApkProtectionDetails? = null
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApkScanScreen(
    state: ApkScanUiState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onClean: () -> Unit,
    onStop: () -> Unit,
    onReconnect: () -> Unit,
    onLocalMode: () -> Unit = {},
    onToggle: (String) -> Unit = {},
    onToggleAll: () -> Unit = {},
    onQuery: (String) -> Unit = {},
    onFilter: (ApkInstallStatus?) -> Unit = {},
    loadArchive: (suspend (ApkScanItem) -> ApkArchiveInfo)? = null,
    diagnoseFile: (suspend (ApkScanItem) -> String)? = null,
    onManageProtection: (ApkScanItem) -> Unit = {},
    onReviewLegacyProtection: () -> Unit = {}
) {
    val context = LocalContext.current
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val loader by rememberUpdatedState(loadArchive)
    val visible = state.visibleItems
    val completedScan = state.coverage.isNotEmpty() && !state.running && !state.scanFailed
    if (showFilters) {
        var filter by remember { mutableStateOf(state.filter) }
        FileFilterDialog({ showFilters = false }, {
            if (filter != state.filter) onFilter(filter)
            showFilters = false
        }) { FileFilterChoices("安装状态", listOf<ApkInstallStatus?>(null).map { it to "全部状态" } +
            ApkInstallStatus.entries.map { it to it.label }, filter) { filter = it } }
    }
    Scaffold(containerColor = BaiZeTokens.colors.surfaceBase,
        topBar = { DetailPageHeader("安装包", "找出下载后留在手机里的安装文件", onBack) {
            TextButton(onClick = { CleanerNavigation.openFrom(context, Intent(context, FileTrashActivity::class.java)) }, enabled = !state.running) { Text("回收站") }
            if (state.cleanReady && !state.running) IconButton(onClick = onScan) { Icon(Icons.Rounded.Refresh, "重新扫描") }
        } },
        bottomBar = {
            if (state.cleanReady && !state.running) CleanSelectionBar(
                state.selected.size, state.selectableVisibleItems.size, Formatter.formatFileSize(context, state.selectedBytes),
                state.allSelected, true, onToggleAll, onClean,
                cleanLabel = "移入回收站 ${state.selected.size} 个安装包")
        }
    ) { insets ->
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(insets).background(BaiZeTokens.colors.surfaceBase)
            .testTag("apk-results-list"),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            DetailTaskCard(
                metric = if (state.totalFiles > 0 || completedScan) Formatter.formatFileSize(context, state.totalBytes) else "扫描安装包",
                metricLabel = if (state.totalFiles > 0 || completedScan) "${state.totalFiles} 个安装文件" else "APK · APKS · XAPK · APKM",
                phase = state.phase,
                running = state.running,
                ready = false,
                scanEnabled = true,
                cleanEnabled = true,
                onScan = onScan, onClean = onClean, onStop = onStop, onReconnect = onReconnect,
                scanLabel = if (state.cleanReady || completedScan) "重新扫描" else "开始扫描",
                cleanLabel = "移入回收站 ${state.selected.size} 个安装包",
                showAction = !state.cleanReady
            )
        }
        if (state.items.isNotEmpty()) item {
            FileQueryBar(state.query, onQuery, !state.running, "搜索安装包", "筛选安装包", state.filter?.label.orEmpty()) { showFilters = true }
        }
        if (state.protectionReviewRequired) item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("上次保护结果，仅供查看。保护设置可能已变化；重新扫描后才能选择清理。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onScan, enabled = !state.running) { Text("重新扫描并核对保护") }
            }
        }
        if (state.protectionMessage.isNotBlank()) item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(state.protectionMessage, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!state.running && state.protectionNeedsAction) FlowRow {
                    TextButton(onClick = onReviewLegacyProtection,
                        modifier = Modifier.testTag("apk-legacy-recovery")) { Text("检查旧版保护") }
                    TextButton(onClick = onReconnect) { Text("重连保护服务") }
                    if (state.localModeAvailable) TextButton(onClick = onLocalMode) { Text("仅本地清理") }
                }
            }
        }
        item {
            DetailSectionHeader("安装包明细", if (state.totalFiles > 0) {
                "当前 ${visible.size} / 共 ${state.totalFiles} 个文件" + if (state.totalFiles > state.items.size) " · 展示前 ${state.items.size} 项" else " · 勾选清理，点按查看详情"
            } else "不会影响已经安装的应用")
        }
        if ((state.filter != null || state.query.isNotBlank()) && visible.any { it.archive.awaitingInspection }) item {
            Text("正在核对可见安装包的应用信息，待识别项暂不可按此筛选勾选", Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (visible.isEmpty()) {
            item {
                DetailEmptyState(
                    title = when { state.scanFailed -> "扫描未完成"; state.items.isNotEmpty() -> "没有符合筛选条件的安装包"; state.running -> "正在查找安装包"; state.coverage.isNotEmpty() -> "没有发现安装包"; else -> "还没有扫描结果" },
                    description = when { state.scanFailed -> "文件索引暂不可用，这不代表存储中没有安装包。请检查权限后重试。"; state.running -> "正在读取系统文件索引。"; state.confirmedMissingRecords > 0 -> "已确认 ${state.confirmedMissingRecords} 条旧记录对应的文件不存在，已从结果和容量中排除；没有删除文件。"; completedScan -> "当前结果没有安装包，可在扫描详情查看本次扫描范围。"; else -> "完成扫描后，文件名称、位置和大小会显示在这里。" },
                    icon = Icons.Rounded.InstallMobile
                )
            }
        } else itemsIndexed(visible, key = { _, item -> item.previewKey }) { _, item ->
            val archive = if (loader == null) item.archive else produceState(item.archive, item.previewKey) {
                value = requireNotNull(loader).invoke(item)
            }.value
            ApkArchiveResultCard(item.copy(archive = archive),
                selected = item.uri in state.selected, enabled = !state.running && !state.protectionReviewRequired && state.matchesCriteria(item),
                onToggle = { onToggle(item.uri) }, diagnoseFile = diagnoseFile)
            item.protectionDetails?.takeIf { it.isProtected || it.unavailableReason != null }?.let { details ->
                ApkProtectionResultPanel(details, historical = state.protectionReviewRequired, enabled = !state.running,
                    onManage = { onManageProtection(item) },
                    onTrash = { CleanerNavigation.openFrom(context, Intent(context, FileTrashActivity::class.java)) })
            }
        }
        if (state.coverage.isNotEmpty() || state.output.isNotBlank()) item {
            DetailExpandableText("扫描详情", state.coverage.joinToString("\n\n") {
                "${it.group} · ${if (it.status == "scanned") "已读取" else "部分可用"}\n${it.files} 个文件 · ${Formatter.formatFileSize(context, it.bytes)}\n${it.path}\n${it.reason}"
            } + if (state.output.isNotBlank()) "\n\n${state.output}" else "")
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
    }
}
