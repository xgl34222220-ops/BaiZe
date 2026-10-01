package io.github.xgl34222220.baize

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.text.format.Formatter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import io.github.xgl34222220.baize.root.RootServiceClients
import org.json.JSONObject

internal class StorageToolsViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(StorageToolsUiState())
    val state = mutableState.asStateFlow()
    private var initialized = false
    private var control = StorageScanControl()
    private val context get() = getApplication<Application>()
    @Volatile private var remote: IProfileRootService? = null
    private var bound = false
    private var closed = false
    private val connection = object : RootService.Connection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (closed) return
            if (binder == null) { onBindingFailed(name, RootService.BindingFailure.NULL_BINDING); return }
            remote = RootServiceClients.profile(binder, context.cacheDir)
            bound = true
            refreshProtection()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            if (closed) return
            remote = null; bound = false; refreshProtection()
        }
        override fun onBindingFailed(name: ComponentName?, reason: RootService.BindingFailure) {
            if (closed) return
            remote = null; bound = false
            mutableState.update { it.copy(protectionMessage = RootConnectionMessages.bindingFailure(reason)) }
        }
        override fun onNullBinding(name: ComponentName?) = onBindingFailed(name, RootService.BindingFailure.NULL_BINDING)
    }

    fun initialize(mode: StorageToolMode) {
        if (initialized) return
        initialized = true
        mutableState.value = StorageToolsUiState(mode = mode, minimumBytes = if (mode == StorageToolMode.LARGE) 100 * MIB else 0)
        connect()
        refreshProtection()
        scan()
    }
    fun connect() {
        if (closed || remote != null || bound) return
        bound = true
        runCatching { RootService.bind(Intent(context, BaiZeProfileRootService::class.java)
            .addCategory(RootService.CATEGORY_DAEMON_MODE), connection) }.onFailure {
            bound = false; refreshProtection()
        }
    }
    private fun protection() = ApkProtectionStore.refresh(context, ApkProtectionStore.source(context, remote))
    private fun refreshProtection() {
        val source = remote
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) { ApkProtectionStore.refresh(context, ApkProtectionStore.source(context, source)) }
            if (closed || remote !== source) return@launch
            mutableState.update { it.copy(protectionMessage = (state as? ApkProtectionState.Unknown)?.reason.orEmpty(),
                localModeAvailable = state is ApkProtectionState.Unknown && !ApkProtectionStore.rootWasUsed(context)) }
        }
    }
    fun enableLocalMode() {
        if (state.value.running) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ApkProtectionStore.enableLocalOnly(context) }
            refreshProtection()
        }
    }
    fun diagnose(record: StorageFileRecord) {
        if (state.value.diagnosticBusy) return
        mutableState.update { it.copy(diagnostic = "", diagnosticUri = record.uri, diagnosticBusy = true) }
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { runCatching {
                ApkFileReadDiagnostics.collect(context, record.uri, record.path, remote, record.identity, indexedFile = true)
            }.getOrElse { JSONObject().put("error", it.javaClass.simpleName).put("message", it.message.orEmpty()).toString(2) } }
            mutableState.update { it.copy(diagnosticBusy = false, diagnostic = report) }
        }
    }
    fun resumePermission() { if (state.value.permissionRequired && StorageMediaRepository.hasAccess()) scan() }
    fun stop() { control.cancel(); mutableState.update { it.copy(status = "正在停止…") } }
    fun toggle(key: String) { if (!state.value.running) mutableState.update { it.toggleSelection(key) } }
    fun toggleAll() { if (!state.value.running) mutableState.update { it.toggleAllSelection() } }
    fun filter(query: String = state.value.query, category: String? = state.value.category,
               sort: StorageSort = state.value.sort, minimumBytes: Long = state.value.minimumBytes) {
        if (state.value.running) return
        mutableState.update { it.copy(query = query, category = category, sort = sort, minimumBytes = minimumBytes, selected = emptySet()) }
    }

    fun scan() {
        if (state.value.running) return
        if (!StorageMediaRepository.hasAccess()) {
            mutableState.update { it.copy(permissionRequired = true, status = "开启文件访问后，开始分析共享存储") }
            return
        }
        control = StorageScanControl()
        val taskControl = control
        val mode = state.value.mode
        mutableState.update { it.copy(running = true, permissionRequired = false, failed = false, status = "正在读取系统文件索引…",
            selected = emptySet(), records = emptyList(), duplicateGroups = emptyList(), buckets = emptyList(), coverage = "", progress = null,
            outcomes = emptyMap(), diagnostic = "", diagnosticUri = "") }
        viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            try {
                val result = withContext(Dispatchers.IO) {
                    var lastProgress = 0L
                    var lastPhase = ""
                    val report: (StorageScanProgress) -> Unit = { progress ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastProgress >= 120 || progress.phase != lastPhase || progress.completed == progress.total) {
                            lastProgress = now; lastPhase = progress.phase
                            mutableState.update { it.copy(progress = progress, status = progress.phase) }
                        }
                    }
                    val rawIndex = StorageMediaRepository.scanIndex(context, if (mode == StorageToolMode.LARGE) 10 * MIB else 1, taskControl, report)
                    val index = StorageMediaRepository.reviewPresence(context, rawIndex, remote, taskControl)
                    val duplicates = if (mode == StorageToolMode.DUPLICATES)
                        StorageMediaRepository.findDuplicates(context, index.records, taskControl, report) else StorageDuplicateResult(emptyList(), 0)
                    Triple(index, duplicates, storageBuckets(index.records))
                }
                taskControl.check()
                val index = result.first
                val duplicates = result.second
                mutableState.update { current -> current.copy(running = false, records = index.records,
                    duplicateGroups = duplicates.groups, buckets = result.third, progress = null,
                    elapsedMs = SystemClock.elapsedRealtime() - started,
                    status = when (mode) {
                        StorageToolMode.LARGE -> "大文件扫描完成"
                        StorageToolMode.DUPLICATES -> "发现 ${duplicates.groups.size} 组内容相同的文件"
                        StorageToolMode.ANALYSIS -> "存储分析完成"
                    }, coverage = buildString {
                        append("已读取 ${index.records.size} 个已索引文件；不含应用私有数据和未被系统索引的文件。")
                        if (index.truncated) append(" 本次达到 12 万项上限，结果不完整。")
                        if (index.confirmedMissing > 0) append(" 已排除 ${index.confirmedMissing} 条不存在文件的旧索引，未计入占用或释放空间。")
                        val unknown = index.records.count { it.verifiedBytes == 0L }
                        if (unknown > 0) append(" $unknown 项尚未核对文件身份，不能勾选，不计入可处理容量；点文件查看诊断。")
                        if (index.presenceIncomplete) append(" 文件存在性核对未全部完成，可连接服务后重新扫描。")
                        if (duplicates.skipped > 0) append(" ${duplicates.skipped} 次文件校验因不可读或文件变化而跳过。")
                    }) }
            } catch (error: Exception) {
                val stopped = taskControl.cancelled || error is CancellationException || error is android.os.OperationCanceledException
                mutableState.update { it.copy(running = false, progress = null, failed = !stopped,
                    elapsedMs = SystemClock.elapsedRealtime() - started,
                    status = if (stopped) "扫描已停止" else "扫描未完成：${error.message ?: "文件读取失败"}",
                    coverage = if (stopped) "未完成的扫描不会作为完整结果显示。可以重新扫描。" else "请检查文件访问权限后重试。") }
            }
        }
    }

    fun deleteSelected() {
        val snapshot = state.value
        if (snapshot.running || snapshot.selected.isEmpty()) return
        val selectedRecords = snapshot.allRecords.filter { it.uri in snapshot.selected }
        if (selectedRecords.isEmpty()) return
        control = StorageScanControl()
        val taskControl = control
        mutableState.update { it.copy(running = true, failed = false, status = "正在删除已选文件…") }
        viewModelScope.launch {
            val outcomes = withContext(Dispatchers.IO) {
                val results = linkedMapOf<String, StorageDeleteOutcome>()
                for ((index, record) in selectedRecords.withIndex()) {
                    if (taskControl.cancelled) break
                    try {
                        val group = snapshot.duplicateGroups.firstOrNull { record in it.records }
                        val safe = snapshot.mode != StorageToolMode.DUPLICATES ||
                            (group != null && StorageMediaRepository.duplicateStillSafe(context, record, group, snapshot.selected, taskControl))
                        taskControl.check()
                        results[record.uri] = if (safe) StorageMediaRepository.delete(context, record, ::protection,
                            { taskControl.cancelled }) else StorageDeleteOutcome(ApkIndexedDeleteResult.CHANGED,
                            "重复组没有经内容核对的保留副本，请重新扫描")
                    } catch (_: CancellationException) { break
                    } catch (error: Exception) { results[record.uri] = StorageDeleteOutcome(ApkIndexedDeleteResult.FAILED,
                        "操作未完成：${error.javaClass.simpleName}") }
                    mutableState.update { it.copy(progress = StorageScanProgress("正在删除", index + 1, selectedRecords.size, record.name)) }
                }
                selectedRecords.forEach { results.putIfAbsent(it.uri, StorageDeleteOutcome(ApkIndexedDeleteResult.CANCELLED)) }
                results
            }
            val removed = outcomes.filterValues { it.deleted }.keys
            val bytes = selectedRecords.filter { it.uri in removed }.sumOf { it.bytes }
            mutableState.update { current ->
                val remaining = current.records.filterNot { it.uri in removed }.map { record ->
                    if (outcomes[record.uri]?.result in setOf(ApkIndexedDeleteResult.CHANGED, ApkIndexedDeleteResult.UNVERIFIED))
                        record.copy(identity = null) else record
                }
                val remainingByUri = remaining.associateBy { it.uri }
                current.copy(running = false, progress = null, records = remaining, buckets = storageBuckets(remaining),
                    selected = emptySet(), outcomes = current.outcomes + outcomes,
                    duplicateGroups = current.duplicateGroups.map { it.copy(records = it.records.mapNotNull { r -> remainingByUri[r.uri] }) },
                    status = "${if (taskControl.cancelled) "已停止 · " else ""}" +
                        (if (removed.isEmpty()) "未删除文件" else "已验证删除 ${removed.size} 个文件，释放 ${Formatter.formatFileSize(context, bytes)}") +
                        if (removed.size < selectedRecords.size) " · ${selectedRecords.size - removed.size} 项保留" else "",
                    coverage = if (removed.size < selectedRecords.size) "保留原因已显示在每项下方；点文件可查看完整路径与读取诊断。重新处理前请核对原因并勾选。" else current.coverage)
            }
        }
    }
    override fun onCleared() {
        closed = true; control.cancel()
        if (bound) runCatching { RootService.unbind(connection) }
        remote = null; bound = false
        super.onCleared()
    }
    companion object { const val MIB = 1024L * 1024L }
}
