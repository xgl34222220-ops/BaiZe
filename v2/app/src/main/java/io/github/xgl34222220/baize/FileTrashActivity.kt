package io.github.xgl34222220.baize

import android.app.Application
import android.media.MediaScannerConnection
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.components.BaiZeDialog
import io.github.xgl34222220.baize.ui.components.BaiZeDialogButton
import io.github.xgl34222220.baize.ui.components.BaiZePathText
import io.github.xgl34222220.baize.ui.components.DetailPageHeader
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

class FileTrashActivity : ComponentActivity() {
    private val appearance: AppearanceViewModel by viewModels()
    private val trash: FileTrashViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by appearance.settings.collectAsState()
            BaiZeTheme(settings) {
                FileTrashScreen(trash.state, FileTrashActions(
                    onBack = ::finish, onRefresh = trash::refresh,
                    onToggle = trash::toggle, onToggleAll = trash::toggleAll,
                    onRestore = trash::restore, onPurge = trash::requestPurge,
                    onPurgeAll = { trash.requestPurge(null) },
                    onConfirmPurge = trash::confirmPurge, onDismissPurge = trash::dismissPurge,
                    onRecoverChanged = trash::requestChanged, onConfirmChanged = trash::confirmChanged,
                    onDismissChanged = trash::dismissChanged, onForget = trash::forgetMissing,
                    onCancel = trash::cancelRemaining, onBudget = trash::setBudget
                ))
            }
        }
    }
}

internal data class FileTrashUiState(
    val entries: List<TrashEntry> = emptyList(),
    val occupied: Long = 0L,
    val budget: Long = OrdinaryFileTrash.DEFAULT_BUDGET,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val selected: Set<String> = emptySet(),
    val message: String = "",
    val action: TrashBatchAction? = null,
    val progress: TrashBatchProgress? = null,
    val result: TrashBatchResult? = null,
    val cancelRequested: Boolean = false,
    val pendingPurge: TrashBatchSnapshot? = null,
    val pendingChanged: TrashEntry? = null
) {
    val editable: Boolean get() = loaded && !loading && !busy && pendingPurge == null && pendingChanged == null
}

/** Survives configuration changes. Finishing the screen stops remaining work after the current safe item. */
@androidx.annotation.Keep
internal class FileTrashViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(FileTrashUiState(budget = OrdinaryFileTrash.budget(application)))
        private set
    private val cancelRequested = AtomicBoolean(false)
    private val context get() = getApplication<Application>()

    init { refresh() }

    fun refresh() {
        if (state.busy || state.loading || state.pendingPurge != null || state.pendingChanged != null) return
        state = state.copy(loading = true, message = state.result?.let { result ->
            state.action?.let { summary(it, result) }
        }.orEmpty())
        viewModelScope.launch {
            try { reload() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                state = state.copy(loaded = false, message = "列表读取失败：${failure.message ?: "请稍后刷新"}")
            } finally { state = state.copy(loading = false) }
        }
    }

    private suspend fun reload() {
        val snapshot = withContext(Dispatchers.IO) {
            OrdinaryFileTrash.forContext(context).let { it.entries() to it.occupiedBytes() }
        }
        state = state.copy(entries = snapshot.first, occupied = snapshot.second, loaded = true,
            selected = state.selected.intersect(snapshot.first.map { it.id }.toSet()))
    }

    fun toggle(id: String) {
        if (!state.editable || state.entries.none { it.id == id }) return
        state = state.copy(selected = if (id in state.selected) state.selected - id else state.selected + id)
    }

    fun toggleAll() {
        if (!state.editable) return
        val ids = state.entries.map { it.id }.toSet()
        state = state.copy(selected = if (state.selected.containsAll(ids)) emptySet() else ids)
    }

    fun setBudget(value: Long) {
        if (!state.editable || value !in listOf(1L, 5L, 10L).map { it * GIB }) return
        context.getSharedPreferences("ordinary-trash", Application.MODE_PRIVATE).edit().putLong("budget", value).apply()
        state = state.copy(budget = value)
    }

    fun restore(ids: Set<String>) {
        if (state.editable) start(TrashBatchAction.RESTORE, TrashBatchSnapshot.capture(state.entries, ids))
    }

    fun requestPurge(ids: Set<String>?) {
        if (!state.editable) return
        val reviewed = TrashBatchSnapshot.capture(state.entries, ids)
        if (reviewed.size > 0) state = state.copy(pendingPurge = reviewed)
    }

    fun dismissPurge() { if (!state.busy) state = state.copy(pendingPurge = null) }
    fun confirmPurge() {
        if (state.busy || state.loading) return
        val reviewed = state.pendingPurge ?: return
        state = state.copy(pendingPurge = null)
        start(TrashBatchAction.PURGE, reviewed)
    }

    fun requestChanged(id: String) {
        if (state.editable) state = state.copy(pendingChanged = state.entries.firstOrNull { it.id == id })
    }
    fun dismissChanged() { if (!state.busy) state = state.copy(pendingChanged = null) }
    fun confirmChanged() {
        if (state.busy || state.loading) return
        val reviewed = state.pendingChanged ?: return
        state = state.copy(pendingChanged = null)
        start(TrashBatchAction.RESTORE_CHANGED, TrashBatchSnapshot.capture(listOf(reviewed)))
    }
    fun forgetMissing(id: String) {
        if (!state.editable) return
        val entry = state.entries.firstOrNull { it.id == id && it.payloadState == TrashPayloadState.MISSING } ?: return
        start(TrashBatchAction.FORGET_MISSING, TrashBatchSnapshot.capture(listOf(entry)))
    }

    fun cancelRemaining() {
        if (!state.busy) return
        cancelRequested.set(true)
        state = state.copy(cancelRequested = true)
    }

    private fun start(action: TrashBatchAction, reviewed: TrashBatchSnapshot) {
        if (!state.editable || reviewed.size == 0) return
        cancelRequested.set(false)
        // Set synchronously, before launch, so repeated taps cannot start another batch.
        state = state.copy(busy = true, cancelRequested = false, action = action,
            progress = TrashBatchProgress(0, reviewed.size), result = null, message = "")
        viewModelScope.launch {
            val job = currentCoroutineContext()
            try {
                val result = runTrashBatch(reviewed, { cancelRequested.get() || !job.isActive },
                    perform = { entry -> withContext(Dispatchers.IO) {
                        val repository = OrdinaryFileTrash.forContext(context)
                        when (action) {
                            TrashBatchAction.RESTORE, TrashBatchAction.RESTORE_CHANGED -> {
                                val restored = repository.restore(entry.id,
                                    allowChanged = action == TrashBatchAction.RESTORE_CHANGED, expected = entry)
                                // An index notification failure must not turn a verified restore into a failed restore.
                                val indexed = runCatching {
                                    MediaScannerConnection.scanFile(context, arrayOf(restored.path), null, null)
                                }.isSuccess
                                TrashItemSuccess("已恢复至 ${restored.path}" + if (indexed) "" else "；媒体索引通知失败，可稍后刷新")
                            }
                            TrashBatchAction.PURGE -> {
                                // Ambiguous protection migration must be reviewed before any irreversible batch.
                                ApkProtectionStore.legacyRules(context)
                                val bytes = repository.purge(entry.id, expected = entry)
                                TrashItemSuccess("已永久删除 ${Formatter.formatFileSize(context, bytes)} 内容")
                            }
                            TrashBatchAction.FORGET_MISSING -> {
                                repository.forgetMissing(entry.id, expected = entry)
                                TrashItemSuccess("已移除无内容记录，原文件未操作")
                            }
                        }
                    } }, onProgress = { state = state.copy(progress = it) })
                state = state.copy(result = result, message = summary(action, result))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                state = state.copy(message = "任务未确认完成：${failure.message ?: "请核对文件与记录"}")
            } finally {
                try {
                    if (job.isActive) reload()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    state = state.copy(loaded = false,
                        message = state.message + "\n列表刷新失败，请重新刷新后继续：${failure.message ?: "读取失败"}")
                } finally { state = state.copy(busy = false, progress = null, cancelRequested = false) }
            }
        }
    }

    private fun summary(action: TrashBatchAction, result: TrashBatchResult): String = buildString {
        append("${action.label}完成 ${result.succeeded} 项")
        if (result.failed > 0) append(" · 未确认完成 ${result.failed} 项")
        if (result.remaining > 0) append(" · 已停止剩余 ${result.remaining} 项")
        if (action == TrashBatchAction.PURGE) append("。实际可用空间以系统统计为准。")
    }

    override fun onCleared() {
        cancelRequested.set(true)
        super.onCleared()
    }

    private companion object { const val GIB = 1024L * 1024 * 1024 }
}

internal data class FileTrashActions(
    val onBack: () -> Unit = {}, val onRefresh: () -> Unit = {},
    val onToggle: (String) -> Unit = {}, val onToggleAll: () -> Unit = {},
    val onRestore: (Set<String>) -> Unit = {}, val onPurge: (Set<String>?) -> Unit = {},
    val onPurgeAll: () -> Unit = {}, val onConfirmPurge: () -> Unit = {}, val onDismissPurge: () -> Unit = {},
    val onRecoverChanged: (String) -> Unit = {}, val onConfirmChanged: () -> Unit = {}, val onDismissChanged: () -> Unit = {},
    val onForget: (String) -> Unit = {}, val onCancel: () -> Unit = {}, val onBudget: (Long) -> Unit = {}
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FileTrashScreen(state: FileTrashUiState, actions: FileTrashActions) {
    var helpExpanded by rememberSaveable { mutableStateOf(false) }
    var resultsExpanded by rememberSaveable(state.result) { mutableStateOf(false) }
    var leaving by rememberSaveable { mutableStateOf(false) }
    val back = { if (state.busy) leaving = true else actions.onBack() }
    BackHandler(enabled = state.busy, onBack = back)
    Scaffold(containerColor = BaiZeTokens.colors.surfaceBase,
        topBar = { DetailPageHeader("文件回收站", "", back) {
            IconButton(actions.onRefresh,
                enabled = !state.busy && !state.loading && state.pendingPurge == null && state.pendingChanged == null) {
                Icon(Icons.Rounded.Refresh, "刷新回收站")
            }
        } },
        bottomBar = { if (state.entries.isNotEmpty() || state.busy) TrashSelectionBar(state, actions) }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("trash-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { TrashPanel {
                Text("${state.entries.size} 项 · ${fileSize(state.occupied)}", style = MaterialTheme.typography.titleLarge)
                Text("记录容量 / 上限 ${fileSize(state.budget)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("移入回收站不释放空间；永久删除无法撤销。", Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ helpExpanded = !helpExpanded }) {
                        Text("说明与容量设置")
                        Icon(if (helpExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
                    }
                    TextButton(actions.onPurgeAll, enabled = state.editable && state.entries.isNotEmpty()) { Text("清空回收站") }
                }
                if (helpExpanded) TrashHelp(state, actions)
            } }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.message.isNotBlank()) item { TrashPanel {
                Text(state.message, style = MaterialTheme.typography.bodyMedium)
                if (state.result != null) TextButton({ resultsExpanded = !resultsExpanded }) {
                    Text(if (resultsExpanded) "收起逐项结果" else "查看逐项结果")
                }
            } }
            if (resultsExpanded) state.result?.let { result ->
                items(result.items, key = { "result:${it.entry.id}" }) { item -> TrashPanel {
                    Text(if (item.succeeded) "已完成" else "未确认完成", style = MaterialTheme.typography.labelLarge,
                        color = if (item.succeeded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    SelectionContainer { Text(item.entry.original, style = MaterialTheme.typography.bodySmall) }
                    SelectionContainer { Text(item.detail, style = MaterialTheme.typography.bodySmall) }
                } }
                if (result.remaining > 0) item { Text("剩余 ${result.remaining} 项未开始；已完成的操作不会撤销。",
                    style = MaterialTheme.typography.bodySmall) }
            }
            if (state.entries.isEmpty() && !state.loading) item { TrashPanel {
                Text(if (state.loaded) "回收站为空" else "回收站尚未读取", style = MaterialTheme.typography.titleMedium)
                Text(if (state.loaded) "手动清理的普通文件会在这里保留，便于恢复。" else "请刷新后再操作。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            items(state.entries, key = { "entry:${it.id}" }) { entry -> TrashFileRow(entry, state, actions) }
        }
    }
    state.pendingPurge?.let { reviewed ->
        BaiZeDialog(onDismissRequest = actions.onDismissPurge, title = { Text("永久删除 ${reviewed.size} 项？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("将永久删除下列 ${reviewed.size} 项，共 ${fileSize(reviewed.bytes)}。此操作无法撤销。",
                    color = MaterialTheme.colorScheme.onSurface)
                Text("仅处理本次列出的记录，之后进入回收站的文件不会包含在内。每项仍会核对路径和内容，无法安全核对的项目会保留并报告。")
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).testTag("trash-reviewed-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(reviewed.entries, key = { it.id }) { entry ->
                        SelectionContainer { Text("${entry.original}\n${fileSize(entry.bytes)}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Text("停止仅影响尚未开始的项目，已永久删除的内容无法恢复。实际可用空间以系统统计为准。")
            } },
            confirmButton = { BaiZeDialogButton(actions.onConfirmPurge, enabled = !state.busy && !state.loading) { Text("永久删除这 ${reviewed.size} 项") } },
            dismissButton = { BaiZeDialogButton(actions.onDismissPurge) { Text("保留文件") } })
    }
    state.pendingChanged?.let { entry ->
        BaiZeDialog(onDismissRequest = actions.onDismissChanged, title = { Text("恢复当前内容副本？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectionContainer { Text(entry.original) }
                Text("内容可能在移动后被其他应用改写，无法保证与原扫描相同。将另存当前可读内容并核对完整副本；已有文件不会被覆盖。")
            } }, confirmButton = { BaiZeDialogButton(actions.onConfirmChanged, enabled = !state.busy && !state.loading) { Text("恢复当前内容") } },
            dismissButton = { BaiZeDialogButton(actions.onDismissChanged) { Text("取消") } })
    }
    if (leaving) BaiZeDialog(onDismissRequest = { leaving = false }, title = { Text("停止剩余操作并返回？") },
        text = { Text("当前文件会完成安全处理，尚未开始的项目将停止。已完成的恢复或永久删除不会撤销。") },
        confirmButton = { BaiZeDialogButton({ leaving = false; actions.onCancel(); actions.onBack() }) { Text("停止并返回") } },
        dismissButton = { BaiZeDialogButton({ leaving = false }) { Text("继续查看") } })
}

@Composable
private fun TrashPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = BaiZeTokens.colors.surfaceRaised) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrashHelp(state: FileTrashUiState, actions: FileTrashActions) {
    val context = LocalContext.current
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
    Text("前台手动处理的 APK、下载、大文件和重复副本统一保留。模块自动清理仍按原配置执行，不进入此回收站。30 天后标为到期，仍需手动确认永久删除。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("普通共享文件保留在同卷隐藏目录，其他有文件权限的应用仍可能访问；应用专属目录不会移到公共区域。卸载或清空白泽数据可能丢失回收内容或恢复记录，请先恢复或清空。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("恢复不会覆盖已有文件；降低容量上限不会自动删除内容。", style = MaterialTheme.typography.bodySmall)
    Text("旧版保护尚待确认时，永久删除会暂停。可先在本地核对历史记录，恢复文件不受影响。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton({ CleanerNavigation.openFrom(context,
        android.content.Intent(context, LegacyProtectionRecoveryActivity::class.java)) }, enabled = !state.busy,
        modifier = Modifier.testTag("trash-legacy-recovery")) { Text("检查旧版保护") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1L, 5L, 10L).forEach { gib ->
            FilterChip(selected = state.budget == gib * 1024 * 1024 * 1024,
                onClick = { actions.onBudget(gib * 1024 * 1024 * 1024) }, enabled = state.editable,
                label = { Text("$gib GiB") })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrashFileRow(entry: TrashEntry, state: FileTrashUiState, actions: FileTrashActions) {
    var more by rememberSaveable(entry.id) { mutableStateOf(false) }
    val readable = entry.payloadState == TrashPayloadState.READABLE
    TrashPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(entry.id in state.selected, { actions.onToggle(entry.id) }, enabled = state.editable,
                modifier = Modifier.semantics { contentDescription = "选择${entry.original}" })
            Column(Modifier.weight(1f)) {
                Text(entry.original.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${fileSize(entry.bytes)} · ${if (entry.expires <= System.currentTimeMillis()) "已到期" else "保留至 " + DateFormat.getDateInstance().format(Date(entry.expires))}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton({ more = true }, enabled = state.editable) { Icon(Icons.Rounded.MoreVert, "更多操作：${entry.original}") }
                DropdownMenu(expanded = more && state.editable, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("恢复内容变化的副本") }, enabled = readable,
                        onClick = { more = false; actions.onRecoverChanged(entry.id) })
                    DropdownMenuItem(text = { Text("清理无内容记录") }, enabled = entry.payloadState == TrashPayloadState.MISSING,
                        onClick = { more = false; actions.onForget(entry.id) })
                }
            }
        }
        BaiZePathText(entry.original)
        if (!readable) Text(entry.payloadState.label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ actions.onRestore(setOf(entry.id)) }, enabled = state.editable && readable) { Text("恢复") }
            TextButton({ actions.onPurge(setOf(entry.id)) }, enabled = state.editable && readable) { Text("永久删除") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrashSelectionBar(state: FileTrashUiState, actions: FileTrashActions) {
    Surface(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp), color = BaiZeTokens.colors.surfaceRaised, shadowElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            if (state.busy) {
                val progress = state.progress ?: TrashBatchProgress(0, 0)
                Text("${state.action?.label.orEmpty()} ${progress.completed} / ${progress.total}", style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(progress = { if (progress.total == 0) 0f else progress.completed.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                progress.current?.let { BaiZePathText(it.original) }
                Text(if (state.cancelRequested) "正在停止；当前文件处理完成后不再开始下一项。" else "停止仅跳过剩余项目，已完成的操作不会撤销。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(actions.onCancel, enabled = !state.cancelRequested) { Text(if (state.cancelRequested) "正在停止…" else "停止剩余操作") }
            } else {
                val selected = TrashBatchSnapshot.capture(state.entries, state.selected)
                val all = state.entries.isNotEmpty() && selected.size == state.entries.size
                val checked = when { all -> ToggleableState.On; selected.size > 0 -> ToggleableState.Indeterminate; else -> ToggleableState.Off }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("已选 ${selected.size} 项 · ${fileSize(selected.bytes)}", Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.triStateToggleable(checked, enabled = state.editable,
                        role = Role.Checkbox, onClick = actions.onToggleAll).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (all) "取消全选" else "全选", style = MaterialTheme.typography.labelLarge)
                        TriStateCheckbox(checked, onClick = null, enabled = state.editable)
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ actions.onRestore(state.selected) }, enabled = state.editable && selected.size > 0,
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = BaiZeTokens.colors.surfaceOverlay,
                            contentColor = MaterialTheme.colorScheme.onSurface)) { Text("恢复已选") }
                    OutlinedButton({ actions.onPurge(state.selected) }, enabled = state.editable && selected.size > 0) { Text("永久删除已选") }
                }
            }
        }
    }
}

@Composable
private fun fileSize(bytes: Long): String = Formatter.formatFileSize(LocalContext.current, bytes)
