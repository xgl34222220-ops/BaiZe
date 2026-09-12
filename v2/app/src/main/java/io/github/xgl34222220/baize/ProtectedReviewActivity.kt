package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.root.RootServiceClients
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.common.AppPackageIcon
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

class ProtectedReviewActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private val preferences by lazy { getSharedPreferences("baize_v2", MODE_PRIVATE) }
    private var service: IProfileRootService? = null
    private var bound = false
    private var snapshotId = ""
    private var total = 0
    private var page = 0
    private var state by mutableStateOf(ProtectedReviewState())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = RootServiceClients.profile(binder, applicationContext.cacheDir)
            bound = true
            state = state.copy(connected = true, failed = false, status = "Root 审计引擎已连接")
            scan()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            state = state.copy(connected = false, running = false, failed = true, status = "Root 服务已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
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
                    ProtectedReviewScreen(
                        state = state,
                        onBack = ::finish,
                        onRefresh = ::scan,
                        onToggle = ::toggle,
                        onPrevious = { loadPage(page - 1) },
                        onNext = { loadPage(page + 1) },
                        onClean = ::cleanSelected
                    )
                }
            }
        }
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java)
                    .addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
            bound = true
        }.onFailure {
            state = state.copy(failed = true, status = "Root 服务启动失败：${it.message.orEmpty()}")
        }
    }

    private fun scan() {
        val root = service ?: return
        state = state.copy(
            running = true,
            status = "正在扫描可审计项目…",
            failed = false,
            items = emptyList(),
            selected = emptySet()
        )
        lifecycleScope.launch {
            val response = runCatching {
                withContext(Dispatchers.IO) {
                    JSONObject(root.scanProfile("safe", optionsJson(false)))
                }
            }
            state = state.copy(running = false)
            response.onSuccess { json ->
                if (json.has("error") || !json.optBoolean("success", true)) {
                    state = state.copy(failed = true, status = json.optString("message", "扫描失败"))
                    return@onSuccess
                }
                snapshotId = json.optString("snapshotId")
                total = json.optInt("totalCandidates")
                page = 0
                state = state.copy(
                    failed = false,
                    total = total,
                    page = 0,
                    pageCount = pageCount(),
                    status = "扫描完成：共 $total 项。低、中、高风险可由你选择；硬保护项仅展示原因。"
                )
                if (total > 0) loadPage(0)
            }.onFailure {
                state = state.copy(failed = true, status = "扫描失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun loadPage(target: Int) {
        val root = service ?: return
        if (snapshotId.isBlank() || state.running || target !in 0 until pageCount()) return
        state = state.copy(running = true)
        lifecycleScope.launch {
            val response = runCatching {
                withContext(Dispatchers.IO) {
                    JSONObject(root.getProfilePage(snapshotId, target * PAGE_SIZE, PAGE_SIZE))
                }
            }
            state = state.copy(running = false)
            response.onSuccess { json ->
                if (json.has("error") || !json.optBoolean("success", true)) {
                    state = state.copy(failed = true, status = json.optString("message", "读取项目失败"))
                    return@onSuccess
                }
                val array = json.optJSONArray("items") ?: JSONArray()
                val values = buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val risk = item.optString("risk", "low")
                        val reason = item.optString("protectedReason")
                        add(
                            ProtectedReviewItem(
                                id = item.optString("id"),
                                title = item.optString("appName", item.optString("categoryLabel", "清理项目")),
                                packageName = item.optString("packageName"),
                                category = item.optString("categoryLabel", "清理项目"),
                                path = item.optString("path"),
                                risk = risk,
                                reason = reason,
                                bytes = item.optLong("bytes", -1L),
                                selectable = item.optBoolean("selectable", risk != "critical") &&
                                    reason.isBlank() && risk != "critical"
                            )
                        )
                    }
                }
                page = target
                state = state.copy(failed = false, items = values, page = page, pageCount = pageCount())
            }.onFailure {
                state = state.copy(failed = true, status = "读取项目失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun toggle(id: String) {
        val next = state.selected.toMutableSet()
        if (!next.add(id)) next.remove(id)
        state = state.copy(selected = next)
    }

    private fun cleanSelected() {
        val root = service ?: return
        if (snapshotId.isBlank() || state.selected.isEmpty() || state.running) return
        val selection = JSONObject().apply { state.selected.forEach { put(it, true) } }.toString()
        state = state.copy(running = true, failed = false, status = "正在复核并清理 ${state.selected.size} 个手动选择项目…")
        lifecycleScope.launch {
            val response = runCatching {
                withContext(Dispatchers.IO) {
                    JSONObject(root.cleanProfileSelected(snapshotId, selection, optionsJson(true)))
                }
            }
            state = state.copy(running = false)
            response.onSuccess { json ->
                if (json.has("error") || !json.optBoolean("success", true)) {
                    state = state.copy(failed = true, status = json.optString("message", "清理失败"))
                    return@onSuccess
                }
                val protected = json.optJSONArray("details")?.let { details ->
                    buildList {
                        for (index in 0 until details.length()) {
                            val item = details.optJSONObject(index) ?: continue
                            if (item.optString("action") == "protected" || item.optString("action") == "partial") {
                                add("${item.optString("path")}：${item.optString("reason")}")
                            }
                        }
                    }
                }.orEmpty()
                state = state.copy(
                    failed = false,
                    status = buildString {
                        append("清理完成：删除 ${json.optLong("deletedFiles")} 个文件，释放 ${formatBytes(json.optLong("deletedBytes"))}")
                        if (protected.isNotEmpty()) {
                            append("\n仍受保护：\n")
                            append(protected.take(8).joinToString("\n"))
                        }
                    },
                    selected = emptySet(),
                    items = emptyList(),
                    total = 0,
                    page = 0,
                    pageCount = 1
                )
                preferences.edit()
                    .putString("last_task_time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                    .apply()
                snapshotId = ""
                total = 0
            }.onFailure {
                state = state.copy(failed = true, status = "清理失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun optionsJson(allowHighRisk: Boolean): String = JSONObject()
        .put("whitelistPackages", JSONArray(preferences.getStringSet("package_whitelist", emptySet()).orEmpty().toList()))
        .put("whitelistPaths", JSONArray(preferences.getStringSet("path_whitelist", emptySet()).orEmpty().toList()))
        .put("maxFileBytes", preferences.getFloat("large_file_mb", 512f).toLong().coerceIn(64L, 16_384L) * 1024L * 1024L)
        .put("fragmentDays", preferences.getInt("fragment_days", 7).coerceIn(0, 365))
        .put("allowHighRisk", allowHighRisk)
        .toString()

    private fun pageCount(): Int = ceil(total / PAGE_SIZE.toDouble()).toInt().coerceAtLeast(1)

    override fun onDestroy() {
        if (bound) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }

    companion object {
        private const val PAGE_SIZE = 40
    }
}

internal data class ProtectedReviewItem(
    val id: String,
    val title: String,
    val packageName: String,
    val category: String,
    val path: String,
    val risk: String,
    val reason: String,
    val bytes: Long,
    val selectable: Boolean
)

internal data class ProtectedReviewState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val status: String = "正在连接 Root 审计引擎…",
    val failed: Boolean = false,
    val items: List<ProtectedReviewItem> = emptyList(),
    val selected: Set<String> = emptySet(),
    val total: Int = 0,
    val page: Int = 0,
    val pageCount: Int = 1
)

@Composable
internal fun ProtectedReviewScreen(
    state: ProtectedReviewState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggle: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClean: () -> Unit
) {
    var confirm by remember(state.selected) { mutableStateOf(false) }
    var detail by remember { mutableStateOf<ProtectedReviewItem?>(null) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = BaiZeTokens.colors.surfaceBase,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            DetailPageHeader("受保护项目", "", onBack) {
                IconButton(onClick = onRefresh, enabled = state.connected && !state.running) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                }
            }
        },
        bottomBar = {
            Surface(color = BaiZeTokens.colors.surfaceBase) {
                GlassActionButton(
                    if (state.selected.isEmpty()) "选择要清理的项目" else "清理所选 ${state.selected.size} 项",
                    onClick = { confirm = true },
                    enabled = state.connected && !state.running && state.selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            item {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${state.total} 项内容", fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
                    Text("已选 ${state.selected.size} 项 · 当前页 ${state.items.size} 项",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
                        if (state.failed) Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Text(state.status, fontSize = 13.sp, lineHeight = 19.sp,
                            color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 7.dp))
                }
            }
            if (state.status.length > 85) item {
                DetailExpandableText("完整状态", state.status)
            }
            if (state.items.isEmpty() && !state.running) item {
                DetailEmptyState(
                    title = if (state.failed) "暂时无法显示项目" else "暂无待审核内容",
                    description = if (state.failed) "点击右上角刷新重试。" else "需要保留或手动处理的内容会显示在这里。",
                    icon = if (state.failed) Icons.Rounded.ErrorOutline else Icons.Rounded.Shield
                )
            }
            if (state.items.isNotEmpty()) item { DetailSectionHeader("项目明细", "勾选要处理的项目，点击条目查看完整信息") }
            itemsIndexed(state.items, key = { _, item -> item.id.ifBlank { item.path } }) { index, item ->
                ProtectedItemRow(
                    item, item.id in state.selected, !state.running,
                    first = index == 0, last = index == state.items.lastIndex,
                    onToggle = { onToggle(item.id) }, onDetails = { detail = item }
                )
            }
            if (state.pageCount > 1) item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onPrevious, enabled = state.page > 0 && !state.running) {
                        Icon(Icons.Rounded.ChevronLeft, null, Modifier.size(18.dp))
                        Text("上一页", fontSize = 13.sp)
                    }
                    Text("${state.page + 1} / ${state.pageCount}", Modifier.weight(1f),
                        fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onNext, enabled = state.page + 1 < state.pageCount && !state.running) {
                        Text("下一页", fontSize = 13.sp)
                        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp))
                    }
                }
            }
            item {
                DetailExpandableText("保留与清理说明", "勾选仅代表本次处理意愿。白名单、系统关键路径、挂载点与符号链接仍会保留；带锁项目不能选择。清理前会再次核对高风险项目。")
            }
        }
    }
    detail?.let { item ->
        ProtectedItemDetails(item, onDismiss = { detail = null })
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("清理 ${state.selected.size} 个所选项目？") },
            text = { Text("白名单、系统核心路径、挂载点、符号链接和关键风险仍会保留。高风险项目会在删除前重新校验。") },
            confirmButton = {
                TextButton(onClick = { confirm = false; onClean() }) { Text("确认清理") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun ProtectedItemRow(
    item: ProtectedReviewItem,
    selected: Boolean,
    enabled: Boolean,
    first: Boolean,
    last: Boolean,
    onToggle: () -> Unit,
    onDetails: () -> Unit
) {
    val shape = RoundedCornerShape(topStart = if (first) 18.dp else 0.dp, topEnd = if (first) 18.dp else 0.dp,
        bottomStart = if (last) 18.dp else 0.dp, bottomEnd = if (last) 18.dp else 0.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(shape)
        .background(BaiZeTokens.colors.surfaceRaised)) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = "查看完整路径与详情", onClick = onDetails)
            .padding(start = 13.dp, end = 6.dp, top = 13.dp, bottom = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            if (item.packageName.isNotBlank()) AppPackageIcon(item.packageName, item.title, size = 36.dp, corner = 11.dp)
            else Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .075f)) {
                Icon(if (item.selectable) Icons.Rounded.Shield else Icons.Rounded.Lock, null,
                    Modifier.padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.reason.ifBlank { "${riskLabel(item.risk)} · ${item.category}" },
                    fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (item.risk == "high") BaiZeTokens.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.path, Modifier.weight(1f), fontSize = 11.sp, lineHeight = 16.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Rounded.ChevronRight, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item.bytes >= 0L) Text(formatBytes(item.bytes), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            if (item.selectable) Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "选择${item.title}" })
            else Icon(Icons.Rounded.Lock, "受保护，无法选择", Modifier.padding(horizontal = 12.dp, vertical = 10.dp).size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!last) HorizontalDivider(Modifier.padding(start = 66.dp, end = 14.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .055f))
    }
}

@Composable
private fun ProtectedItemDetails(item: ProtectedReviewItem, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(item) { mutableStateOf(false) }
    val text = buildString {
        appendLine(item.title)
        appendLine("${item.category} · ${riskLabel(item.risk)}")
        if (item.bytes >= 0L) appendLine("大小：${formatBytes(item.bytes)}")
        appendLine(if (item.selectable) "可手动选择清理" else "受保护，无法选择")
        if (item.reason.isNotBlank()) appendLine("保留原因：${item.reason}")
        if (item.packageName.isNotBlank()) appendLine("应用：${item.packageName}")
        append("完整路径：${item.path}")
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("项目详情", fontSize = 18.sp) },
        text = { SelectionContainer { Text(text, Modifier.verticalScroll(rememberScrollState()), fontSize = 13.sp, lineHeight = 20.sp) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(text)); copied = true }) { Text(if (copied) "已复制" else "复制详情") } })
}

private fun riskLabel(risk: String): String = when (risk) {
    "critical" -> "关键风险"
    "high" -> "高风险"
    "medium" -> "中风险"
    else -> "低风险"
}

private fun formatBytes(bytes: Long): String {
    var value = bytes.coerceAtLeast(0L).toDouble()
    val units = arrayOf("B", "KB", "MB", "GB")
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index += 1
    }
    return if (index == 0) "${value.toLong()} ${units[index]}" else String.format(Locale.US, "%.2f %s", value, units[index])
}
