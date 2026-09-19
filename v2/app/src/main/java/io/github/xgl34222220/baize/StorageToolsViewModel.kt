package io.github.xgl34222220.baize

import android.app.Application
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

internal class StorageToolsViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(StorageToolsUiState())
    val state = mutableState.asStateFlow()
    private var initialized = false
    private var control = StorageScanControl()
    private val context get() = getApplication<Application>()

    fun initialize(mode: StorageToolMode) {
        if (initialized) return
        initialized = true
        mutableState.value = StorageToolsUiState(mode = mode, minimumBytes = if (mode == StorageToolMode.LARGE) 100 * MIB else 0)
        scan()
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
            selected = emptySet(), records = emptyList(), duplicateGroups = emptyList(), buckets = emptyList(), coverage = "", progress = null) }
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
                    val index = StorageMediaRepository.scanIndex(context, if (mode == StorageToolMode.LARGE) 10 * MIB else 1, taskControl, report)
                    val duplicates = if (mode == StorageToolMode.DUPLICATES)
                        StorageMediaRepository.findDuplicates(context, index.records, taskControl, report) else StorageDuplicateResult(emptyList(), 0)
                    index to duplicates
                }
                taskControl.check()
                val index = result.first
                val duplicates = result.second
                mutableState.update { current -> current.copy(running = false, records = index.records,
                    duplicateGroups = duplicates.groups, buckets = storageBuckets(index.records), progress = null,
                    elapsedMs = SystemClock.elapsedRealtime() - started,
                    status = when (mode) {
                        StorageToolMode.LARGE -> "大文件扫描完成"
                        StorageToolMode.DUPLICATES -> "发现 ${duplicates.groups.size} 组内容相同的文件"
                        StorageToolMode.ANALYSIS -> "存储分析完成"
                    }, coverage = buildString {
                        append("已读取 ${index.records.size} 个已索引文件；不含应用私有数据和未被系统索引的文件。")
                        if (index.truncated) append(" 本次达到 12 万项上限，结果不完整。")
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
            val removed = withContext(Dispatchers.IO) {
                val deleted = mutableSetOf<String>()
                for ((index, record) in selectedRecords.withIndex()) {
                    if (taskControl.cancelled) break
                    try {
                        val group = snapshot.duplicateGroups.firstOrNull { record in it.records }
                        val safe = snapshot.mode != StorageToolMode.DUPLICATES ||
                            (group != null && StorageMediaRepository.duplicateStillSafe(context, record, group, snapshot.selected, taskControl))
                        taskControl.check()
                        if (safe && StorageMediaRepository.delete(context, record)) deleted += record.uri
                    } catch (_: CancellationException) { break
                    } catch (_: Exception) { /* Keep the failed file in the result and selected for review. */ }
                    mutableState.update { it.copy(progress = StorageScanProgress("正在删除", index + 1, selectedRecords.size, record.name)) }
                }
                deleted
            }
            val bytes = selectedRecords.filter { it.uri in removed }.sumOf { it.bytes }
            mutableState.update { current ->
                val remaining = current.records.filterNot { it.uri in removed }
                current.copy(running = false, progress = null, records = remaining, buckets = storageBuckets(remaining),
                    selected = current.selected - removed,
                    duplicateGroups = current.duplicateGroups.map { it.copy(records = it.records.filterNot { r -> r.uri in removed }) },
                    status = "${if (taskControl.cancelled) "已停止 · " else ""}已删除 ${removed.size} 个文件，释放 ${Formatter.formatFileSize(context, bytes)}" +
                        if (removed.size < selectedRecords.size) " · ${selectedRecords.size - removed.size} 项保留" else "",
                    coverage = if (removed.size < selectedRecords.size) "保留项可能已变化、无法访问或未处理；重复文件需有内容一致的保留副本。" else current.coverage)
            }
        }
    }
    override fun onCleared() { control.cancel(); super.onCleared() }
    companion object { const val MIB = 1024L * 1024L }
}
