package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveModerator
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import io.github.xgl34222220.baize.root.RootServiceClients
import io.github.xgl34222220.baize.root.WhitelistFileClient
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.components.BaiZeDialog
import io.github.xgl34222220.baize.ui.components.BaiZeDialogButton
import io.github.xgl34222220.baize.ui.components.BaiZePathText
import io.github.xgl34222220.baize.ui.components.BaiZeProgress
import io.github.xgl34222220.baize.ui.components.DetailEmptyState
import io.github.xgl34222220.baize.ui.components.DetailPageHeader
import io.github.xgl34222220.baize.ui.components.DetailSectionHeader
import io.github.xgl34222220.baize.ui.components.rememberBaiZeHaptics
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 记录详情：一次任务里没有删除的每个项目——路径、大小、分类、中文原因，以及可以对它做什么。
 * 参考 SD Maid SE / App Manager 的结果页：跳过的项目用中性样式分组说明，只有真实失败用错误色；
 * 每项可删除（移入回收站）、打开所在目录、复制路径、加入忽略或取消保护，也可批量选择删除。
 */
class RunDetailActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private var service: IProfileRootService? = null
    private var bound = false
    private var state by mutableStateOf(RunDetailUiState())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = RootServiceClients.profile(binder, applicationContext.cacheDir)
            state = state.copy(connected = true)
            load()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            state = state.copy(connected = false, busy = false, message = "Root 服务已断开，请返回后重试")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        state = RunDetailUiState.fromIntent(intent)
        setContent {
            val appearance = appearanceViewModel.settings.collectAsStateWithLifecycle().value
            val systemDark = isSystemInDarkTheme()
            val dark = when (appearance.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            BaiZeTheme(appearance) {
                CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
                    RunDetailScreen(state, RunDetailActions(
                        onBack = ::finish,
                        onRefresh = ::load,
                        onToggle = ::toggle,
                        onToggleAll = ::toggleAll,
                        onDelete = { items, allowProtected, permanent -> delete(items, allowProtected, permanent) },
                        onIgnore = ::ignore,
                        onUnprotect = ::unprotect,
                        onManageProtection = { item -> CleanerNavigation.open(this, WhitelistActivity.forFile(this, item.path)) },
                        onOpenLocation = ::openLocation,
                        onDismissPermanent = { state = state.copy(pendingPermanent = emptyList()) }
                    ))
                }
            }
        }
        if (state.detailId.isNotBlank()) connect()
    }

    override fun onDestroy() {
        if (bound) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }

    private fun connect() {
        state = state.copy(busy = true, message = "正在读取逐项明细…")
        runCatching {
            RootService.bind(Intent(this, BaiZeProfileRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE), connection)
            bound = true
        }.onFailure { state = state.copy(busy = false, message = "Root 服务启动失败：${it.message.orEmpty()}") }
    }

    private fun call(operation: String, arguments: JSONArray): JSONObject {
        val remote = checkNotNull(service) { "Root 服务未连接" }
        return JSONObject(RootServiceClients.profileExchange(remote, applicationContext.cacheDir, operation, arguments))
    }

    private fun load() {
        if (state.detailId.isBlank() || service == null) return
        state = state.copy(busy = true)
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { call("getRunDetail", JSONArray().put(state.detailId)) } }
            result.onSuccess { json ->
                if (!json.optBoolean("success")) {
                    state = state.copy(busy = false, loaded = true, expired = json.optBoolean("expired"),
                        message = json.optString("message", "读取明细失败"))
                    return@onSuccess
                }
                val items = RunDetailItem.parseAll(json)
                state = state.copy(busy = false, loaded = true, items = items,
                    selected = state.selected.filter { index -> items.any { it.index == index && it.actionable && !it.done } }.toSet(),
                    listedTotal = json.optLong("failed") + json.optLong("skipped"),
                    message = if (state.message.startsWith("正在")) "" else state.message)
            }.onFailure { state = state.copy(busy = false, message = "读取明细失败：${it.message ?: it.javaClass.simpleName}") }
        }
    }

    private fun toggle(item: RunDetailItem) {
        if (!item.actionable || item.done || state.busy) return
        state = state.copy(selected = if (item.index in state.selected) state.selected - item.index else state.selected + item.index)
    }

    private fun toggleAll() {
        if (state.busy) return
        val all = state.items.filter { it.actionable && !it.done }.map { it.index }.toSet()
        state = state.copy(selected = if (state.selected.containsAll(all)) emptySet() else all)
    }

    private fun delete(items: List<RunDetailItem>, allowProtected: Boolean, permanent: Boolean) {
        if (items.isEmpty() || state.busy) return
        state = state.copy(busy = true, pendingPermanent = emptyList(), message = if (permanent) "正在永久删除…" else "正在移入回收站…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    call("deleteRunDetailItems", JSONArray().put(state.detailId)
                        .put(JSONArray(items.map { it.index }).toString()).put(allowProtected).put(permanent))
                }
            }
            result.onSuccess { json ->
                val needs = json.optJSONArray("results")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                        .filter { it.optString("status") == "needs_permanent" }.map { it.optInt("index") }.toSet()
                }.orEmpty()
                state = state.copy(busy = false, selected = emptySet(),
                    pendingPermanent = items.filter { it.index in needs },
                    message = json.optString("message", if (json.optBoolean("success")) "已处理" else "处理失败"))
                load()
            }.onFailure { state = state.copy(busy = false, message = "处理失败：${it.message ?: it.javaClass.simpleName}") }
        }
    }

    private fun ignore(item: RunDetailItem) = mutateProtection("已加入保护名单，以后不会再处理这个路径") {
        it.addWhitelistPath(item.path)
    }

    private fun unprotect(item: RunDetailItem) = mutateProtection("已取消保护：${item.protectionRule}。重新扫描后会按规则处理") {
        WhitelistFileClient.removePath(it, applicationContext.cacheDir, item.protectionRule)
    }

    private fun mutateProtection(done: String, block: (IProfileRootService) -> String) {
        val remote = service ?: return
        if (state.busy) return
        state = state.copy(busy = true)
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { JSONObject(block(remote)) } }
            state = state.copy(busy = false, message = result.fold(
                onSuccess = { json -> if (json.optBoolean("success")) done else json.optString("message", json.optString("error", "保存失败")) },
                onFailure = { "保存失败：${it.message ?: it.javaClass.simpleName}" }))
            load()
        }
    }

    private fun openLocation(item: RunDetailItem) {
        val parent = if (item.directory) item.path else item.path.substringBeforeLast('/', item.path)
        val relative = RunDetailLocations.primaryRelative(parent)
        val opened = relative != null && runCatching {
            val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$relative")
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            true
        }.getOrDefault(false)
        if (!opened) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("path", parent))
            Toast.makeText(this, "这个位置无法用文件管理器打开，已复制所在目录路径", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val EXTRA_DETAIL = "io.github.xgl34222220.baize.RUN_DETAIL_ID"
        private const val EXTRA_TITLE = "io.github.xgl34222220.baize.RUN_DETAIL_TITLE"
        private const val EXTRA_TIME = "io.github.xgl34222220.baize.RUN_DETAIL_TIME"
        private const val EXTRA_FAILED = "io.github.xgl34222220.baize.RUN_DETAIL_FAILED"
        private const val EXTRA_SKIPPED = "io.github.xgl34222220.baize.RUN_DETAIL_SKIPPED"
        private const val EXTRA_GROUPS = "io.github.xgl34222220.baize.RUN_DETAIL_GROUPS"

        internal fun intent(context: Context, record: HistoryUiItem): Intent = Intent(context, RunDetailActivity::class.java)
            .putExtra(EXTRA_DETAIL, record.detailId)
            .putExtra(EXTRA_TITLE, record.title)
            .putExtra(EXTRA_TIME, record.time)
            .putExtra(EXTRA_FAILED, record.failed)
            .putExtra(EXTRA_SKIPPED, record.skipped)
            .putExtra(EXTRA_GROUPS, RunDetailGroups.encode(record))

        internal fun readState(intent: Intent): RunDetailUiState = RunDetailUiState(
            detailId = intent.getStringExtra(EXTRA_DETAIL).orEmpty(),
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            time = intent.getStringExtra(EXTRA_TIME).orEmpty(),
            failed = intent.getLongExtra(EXTRA_FAILED, 0L),
            skipped = intent.getLongExtra(EXTRA_SKIPPED, 0L),
            groups = RunDetailGroups.decode(intent.getStringExtra(EXTRA_GROUPS).orEmpty())
        )
    }
}

/** 没有逐项明细时（旧记录或明细已过期）显示的分组汇总。 */
@Immutable
internal data class RunDetailGroup(val failed: Boolean, val label: String, val count: Long, val samplePath: String)

internal object RunDetailGroups {
    fun from(record: HistoryUiItem): List<RunDetailGroup> =
        record.failures.map { RunDetailGroup(true, listOf(it.category, it.reason).filter(String::isNotBlank).joinToString(" · "), it.count, it.samplePath) } +
            record.skipGroups.map { RunDetailGroup(false, it.label, it.count, it.samplePath) }

    fun encode(record: HistoryUiItem): String = JSONArray().also { array ->
        from(record).forEach { array.put(JSONObject().put("failed", it.failed).put("label", it.label).put("count", it.count).put("sample", it.samplePath)) }
    }.toString()

    fun decode(raw: String): List<RunDetailGroup> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map {
            RunDetailGroup(it.optBoolean("failed"), it.optString("label"), it.optLong("count"), it.optString("sample"))
        }
    }.getOrDefault(emptyList())
}

internal object RunDetailLocations {
    /** /data/media/0/X、/storage/emulated/0/X、/sdcard/X → 主存储内相对路径 X（只支持用户 0 的主存储）。 */
    fun primaryRelative(path: String): String? {
        val prefixes = listOf("/data/media/0", "/storage/emulated/0", "/sdcard", "/storage/self/primary")
        val prefix = prefixes.firstOrNull { path == it || path.startsWith("$it/") } ?: return null
        return path.removePrefix(prefix).trim('/')
    }
}

@Immutable
internal data class RunDetailUiState(
    val detailId: String = "",
    val title: String = "",
    val time: String = "",
    val failed: Long = 0L,
    val skipped: Long = 0L,
    val groups: List<RunDetailGroup> = emptyList(),
    val connected: Boolean = false,
    val loaded: Boolean = false,
    val expired: Boolean = false,
    val busy: Boolean = false,
    val items: List<RunDetailItem> = emptyList(),
    val selected: Set<Int> = emptySet(),
    val listedTotal: Long = 0L,
    val message: String = "",
    val pendingPermanent: List<RunDetailItem> = emptyList()
) {
    val failedItems: List<RunDetailItem> get() = items.filter { it.failed }
    /** 跳过的项目按原因分组（中性样式），组内按大小排序。 */
    val skippedGroups: List<Pair<String, List<RunDetailItem>>> get() = items.filter { !it.failed }
        .groupBy { it.reasonText.ifBlank { "已跳过" } }.entries
        .sortedByDescending { it.value.size }.map { it.key to it.value.sortedByDescending { item -> item.bytes } }
    val selectedItems: List<RunDetailItem> get() = items.filter { it.index in selected }
    val showGroupsOnly: Boolean get() = detailId.isBlank() || expired

    companion object {
        fun fromIntent(intent: Intent): RunDetailUiState = RunDetailActivity.readState(intent)
    }
}

internal data class RunDetailActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onToggle: (RunDetailItem) -> Unit,
    val onToggleAll: () -> Unit,
    val onDelete: (List<RunDetailItem>, Boolean, Boolean) -> Unit,
    val onIgnore: (RunDetailItem) -> Unit,
    val onUnprotect: (RunDetailItem) -> Unit,
    val onManageProtection: (RunDetailItem) -> Unit,
    val onOpenLocation: (RunDetailItem) -> Unit,
    val onDismissPermanent: () -> Unit
)

@Composable
internal fun RunDetailScreen(state: RunDetailUiState, actions: RunDetailActions) {
    var confirmDelete by remember { mutableStateOf<List<RunDetailItem>>(emptyList()) }
    var confirmIgnore by remember { mutableStateOf<RunDetailItem?>(null) }
    var confirmUnprotect by remember { mutableStateOf<RunDetailItem?>(null) }
    val haptics = rememberBaiZeHaptics()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = BaiZeTokens.colors.surfaceBase,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            DetailPageHeader("记录详情", listOf(state.title, state.time).filter(String::isNotBlank).joinToString(" · "), actions.onBack) {
                if (!state.showGroupsOnly && state.items.any { it.actionable && !it.done }) {
                    IconButton(onClick = actions.onToggleAll, enabled = !state.busy) { Icon(Icons.Rounded.SelectAll, contentDescription = "全选") }
                }
                if (!state.showGroupsOnly) IconButton(onClick = actions.onRefresh, enabled = state.connected && !state.busy) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                }
            }
        },
        bottomBar = {
            if (state.selected.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().background(BaiZeTokens.colors.surfaceBase).navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${state.selected.size} 项", Modifier.weight(1f), fontSize = 14.sp)
                    GlassActionButton("删除所选", onClick = { confirmDelete = state.selectedItems }, enabled = !state.busy,
                        icon = Icons.Rounded.DeleteOutline, compact = true, modifier = Modifier.testTag("run-detail-delete-selected"))
                }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 28.dp)) {
            item(key = "summary") { RunDetailSummary(state) }
            if (state.showGroupsOnly) {
                item(key = "groups-note") {
                    DetailEmptyState(
                        if (state.expired) "逐项明细已过期" else "这条记录没有逐项明细",
                        if (state.expired) "只保留最近 30 次任务的逐项明细。下面是这次任务的汇总；重新扫描后可在新记录里逐项处理。"
                        else "这是旧版本留下的记录，当时没有保存每个项目。下面是汇总；新的任务会保存逐项明细。",
                        icon = Icons.Rounded.SkipNext)
                }
                if (state.groups.isNotEmpty()) item(key = "groups-title") { DetailSectionHeader("汇总") }
                itemsIndexed(state.groups, key = { index, _ -> "g$index" }, contentType = { _, _ -> "run-detail-group" }) { _, group -> RunDetailGroupRow(group) }
            } else {
                if (state.failedItems.isNotEmpty()) {
                    item(key = "failed-title") { DetailSectionHeader("删除失败 ${state.failedItems.size} 项", "白泽尝试删除，但系统拒绝了") }
                    items(state.failedItems, key = { "f${it.index}" }, contentType = { "run-detail-item" }) { item ->
                        RunDetailRow(item, item.index in state.selected, !state.busy && state.connected, actions,
                            onDelete = { confirmDelete = listOf(item) }, onIgnore = { confirmIgnore = item }, onUnprotect = { confirmUnprotect = item })
                    }
                }
                state.skippedGroups.forEach { (reason, rows) ->
                    item(key = "s-title-$reason") { DetailSectionHeader("已跳过 · $reason", "${rows.size} 项，白泽有意保留，不是错误") }
                    items(rows, key = { "s${it.index}" }, contentType = { "run-detail-item" }) { item ->
                        RunDetailRow(item, item.index in state.selected, !state.busy && state.connected, actions,
                            onDelete = { confirmDelete = listOf(item) }, onIgnore = { confirmIgnore = item }, onUnprotect = { confirmUnprotect = item })
                    }
                }
                if (state.loaded && state.items.isEmpty() && !state.busy) item(key = "empty") {
                    DetailEmptyState("没有需要处理的项目", "这次任务没有留下未删除的项目。")
                }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
    if (confirmDelete.isNotEmpty()) {
        val reviewed = confirmDelete
        BaiZeDialog(onDismissRequest = { confirmDelete = emptyList() },
            title = { Text(RunDetailConfirm.title(reviewed)) },
            text = { Text(RunDetailConfirm.body(reviewed), fontSize = 14.sp, lineHeight = 21.sp) },
            confirmButton = {
                BaiZeDialogButton(onClick = {
                    haptics.confirmDelete(); confirmDelete = emptyList()
                    actions.onDelete(reviewed, reviewed.any { it.protectedByUser }, false)
                }) { Text(if (reviewed.any { it.protectedByUser }) "临时允许并删除" else "移入回收站", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { BaiZeDialogButton(onClick = { confirmDelete = emptyList() }) { Text("取消") } })
    }
    if (state.pendingPermanent.isNotEmpty()) {
        val reviewed = state.pendingPermanent
        BaiZeDialog(onDismissRequest = actions.onDismissPermanent,
            title = { Text("无法移入回收站") },
            text = { Text(RunDetailConfirm.permanentBody(reviewed.size), fontSize = 14.sp, lineHeight = 21.sp) },
            confirmButton = {
                BaiZeDialogButton(onClick = { haptics.confirmDelete(); actions.onDelete(reviewed, reviewed.any { it.protectedByUser }, true) }) {
                    Text("永久删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { BaiZeDialogButton(onClick = actions.onDismissPermanent) { Text("保留") } })
    }
    confirmIgnore?.let { item ->
        BaiZeDialog(onDismissRequest = { confirmIgnore = null },
            title = { Text("加入忽略？") },
            text = { Text("以后清理会跳过这个${if (item.directory) "目录" else "文件"}（加入保护名单），可在保护名单里随时移除。\n\n${item.path}", fontSize = 14.sp, lineHeight = 21.sp) },
            confirmButton = { BaiZeDialogButton(onClick = { confirmIgnore = null; actions.onIgnore(item) }) { Text("加入忽略") } },
            dismissButton = { BaiZeDialogButton(onClick = { confirmIgnore = null }) { Text("取消") } })
    }
    confirmUnprotect?.let { item ->
        BaiZeDialog(onDismissRequest = { confirmUnprotect = null },
            title = { Text("取消保护？") },
            text = { Text("这个项目被「${item.protectionText}」保护。" +
                if (item.protectionKind == "path") "取消后，这条规则保护的所有内容都会按清理规则处理。" else "应用保护请在保护名单里管理。", fontSize = 14.sp, lineHeight = 21.sp) },
            confirmButton = {
                BaiZeDialogButton(onClick = {
                    confirmUnprotect = null
                    if (item.protectionKind == "path") actions.onUnprotect(item) else actions.onManageProtection(item)
                }) { Text(if (item.protectionKind == "path") "取消这条保护" else "打开保护名单") }
            },
            dismissButton = { BaiZeDialogButton(onClick = { confirmUnprotect = null }) { Text("取消") } })
    }
}

@Composable
private fun RunDetailSummary(state: RunDetailUiState) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.failed > 0L) RunDetailPill("失败 ${state.failed} 项", error = true)
            if (state.skipped > 0L) RunDetailPill("已跳过 ${state.skipped} 项", error = false)
        }
        Text(when {
            state.failed > 0L -> "失败是白泽尝试删除但被系统拒绝的项目；跳过是白泽按规则有意保留的项目，不是错误。"
            else -> "这些项目是白泽按规则有意保留的，不是错误。需要时可以逐项删除、加入忽略或取消保护。"
        }, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.message.isNotBlank()) Text(state.message, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.primary)
        if (state.busy) BaiZeProgress(Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

@Composable
private fun RunDetailPill(text: String, error: Boolean) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = if (error) .12f else .10f)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 12.sp, color = color)
    }
}

@Composable
private fun RunDetailGroupRow(group: RunDetailGroup) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp).clip(RoundedCornerShape(16.dp))
        .background(BaiZeTokens.colors.surfaceRaised).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group.label.ifBlank { if (group.failed) "删除失败" else "已跳过" }, Modifier.weight(1f), fontSize = 14.sp,
                fontWeight = FontWeight.Medium, color = if (group.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${group.count} 项", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (group.samplePath.startsWith('/')) BaiZePathText(group.samplePath, Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun RunDetailRow(
    item: RunDetailItem,
    selected: Boolean,
    enabled: Boolean,
    actions: RunDetailActions,
    onDelete: () -> Unit,
    onIgnore: () -> Unit,
    onUnprotect: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val canAct = enabled && item.actionable && !item.done
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp).clip(RoundedCornerShape(16.dp))
        .background(BaiZeTokens.colors.surfaceRaised)
        .clickable(enabled = canAct, onClickLabel = "选择") { actions.onToggle(item) }
        .padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            if (item.actionable && !item.done) {
                Checkbox(checked = selected, onCheckedChange = { actions.onToggle(item) }, enabled = enabled)
            } else {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(if (item.failed) Icons.Rounded.ErrorOutline else Icons.Rounded.SkipNext, null, Modifier.size(20.dp),
                        tint = if (item.failed && !item.done) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (item.hasPath) item.path.substringAfterLast('/').ifBlank { item.path } else item.path.ifBlank { item.category },
                        Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val size = when {
                        item.bytes > 0L -> Formatter.formatFileSize(context, item.bytes)
                        item.count > 1L -> "${item.count} 项"
                        item.directory -> "目录"
                        else -> ""
                    }
                    if (size.isNotBlank()) Text(size, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item.hasPath) BaiZePathText(item.path)
                Text(item.statusLine, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    color = if (item.failed && !item.done) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.protectedByUser) Text("受保护：${item.protectionText}", fontSize = 12.sp, lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.blockedReason.isNotBlank() && !item.done) Text(item.blockedReason, fontSize = 12.sp, lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (item.hasPath && !item.done) {
            Row(Modifier.fillMaxWidth().padding(start = 42.dp), horizontalArrangement = Arrangement.End) {
                if (item.actionable) TextButton(onClick = onDelete, enabled = enabled) {
                    Text("删除", fontSize = 13.sp, color = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { actions.onOpenLocation(item) }) {
                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("位置", fontSize = 13.sp)
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(item.path))
                    Toast.makeText(context, "已复制路径", Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("复制", fontSize = 13.sp) }
                if (item.protectedByUser) TextButton(onClick = onUnprotect, enabled = enabled) {
                    Icon(Icons.Rounded.RemoveModerator, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("取消保护", fontSize = 13.sp)
                } else if (item.actionable) TextButton(onClick = onIgnore, enabled = enabled) {
                    Icon(Icons.Rounded.Block, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("忽略", fontSize = 13.sp)
                }
            }
        } else Spacer(Modifier.height(6.dp))
    }
}
