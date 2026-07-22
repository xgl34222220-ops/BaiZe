package io.github.xgl34222220.baize

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
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
            service = IProfileRootService.Stub.asInterface(binder)
            bound = true
            state = state.copy(connected = true, status = "Root 审计引擎已连接")
            scan()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            state = state.copy(connected = false, running = false, status = "Root 服务已断开")
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
            state = state.copy(status = "Root 服务启动失败：${it.message.orEmpty()}")
        }
    }

    private fun scan() {
        val root = service ?: return
        state = state.copy(
            running = true,
            status = "正在扫描可审计项目…",
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
                if (json.has("error")) {
                    state = state.copy(status = json.optString("message", "扫描失败"))
                    return@onSuccess
                }
                snapshotId = json.optString("snapshotId")
                total = json.optInt("totalCandidates")
                page = 0
                state = state.copy(
                    total = total,
                    page = 0,
                    pageCount = pageCount(),
                    status = "扫描完成：共 $total 项。低、中、高风险可由你选择；硬保护项仅展示原因。"
                )
                if (total > 0) loadPage(0)
            }.onFailure {
                state = state.copy(status = "扫描失败：${it.message ?: it.javaClass.simpleName}")
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
                if (json.has("error")) {
                    state = state.copy(status = json.optString("message", "读取项目失败"))
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
                state = state.copy(items = values, page = page, pageCount = pageCount())
            }.onFailure {
                state = state.copy(status = "读取项目失败：${it.message ?: it.javaClass.simpleName}")
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
        state = state.copy(running = true, status = "正在复核并清理 ${state.selected.size} 个手动选择项目…")
        lifecycleScope.launch {
            val response = runCatching {
                withContext(Dispatchers.IO) {
                    JSONObject(root.cleanProfileSelected(snapshotId, selection, optionsJson(true)))
                }
            }
            state = state.copy(running = false)
            response.onSuccess { json ->
                if (json.has("error")) {
                    state = state.copy(status = json.optString("message", "清理失败"))
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
                state = state.copy(status = "清理失败：${it.message ?: it.javaClass.simpleName}")
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

private data class ProtectedReviewItem(
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

private data class ProtectedReviewState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val status: String = "正在连接 Root 审计引擎…",
    val items: List<ProtectedReviewItem> = emptyList(),
    val selected: Set<String> = emptySet(),
    val total: Int = 0,
    val page: Int = 0,
    val pageCount: Int = 1
)

@Composable
private fun ProtectedReviewScreen(
    state: ProtectedReviewState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggle: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClean: () -> Unit
) {
    var confirm by remember(state.selected) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "返回") }
                    Column(Modifier.weight(1f)) {
                        Text("PROTECTED REVIEW", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, letterSpacing = 2.sp)
                        Text("受保护项目复查", fontSize = 28.sp, fontWeight = FontWeight.Black)
                        Text("具体路径、保护原因与手动选择", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                    IconButton(onClick = onRefresh, enabled = state.connected && !state.running) {
                        Icon(Icons.Rounded.Refresh, "重新扫描")
                    }
                }
            }
            item {
                Surface(
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text(state.status, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
                        if (state.running) {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "已选择 ${state.selected.size} 项 · 当前页 ${state.items.size} 项 · 总计 ${state.total} 项",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { confirm = true },
                            enabled = state.connected && !state.running && state.selected.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().height(54.dp)
                        ) {
                            Icon(Icons.Rounded.CleaningServices, null)
                            Spacer(Modifier.width(8.dp))
                            Text("清理所选项目", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            items(state.items, key = { it.id.ifBlank { it.path } }) { item ->
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .clickable(enabled = item.selectable && !state.running) { onToggle(item.id) },
                    shape = RoundedCornerShape(25.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.Top) {
                        if (item.packageName.isNotBlank()) {
                            AppPackageIcon(item.packageName, item.title, size = 44.dp, corner = 15.dp)
                        } else {
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(15.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .11f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(if (item.selectable) Icons.Rounded.Shield else Icons.Rounded.Lock, null)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                item.reason.ifBlank { "${riskLabel(item.risk)} · 可由用户决定" },
                                color = if (item.selectable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(item.category, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
                            Text(
                                item.path,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                lineHeight = 14.sp
                            )
                            if (item.bytes >= 0L) Text(formatBytes(item.bytes), color = MaterialTheme.colorScheme.primary, fontSize = 9.sp)
                        }
                        if (item.selectable) {
                            Checkbox(
                                checked = state.selected.contains(item.id),
                                onCheckedChange = { onToggle(item.id) },
                                enabled = !state.running
                            )
                        } else {
                            Icon(Icons.Rounded.Lock, "硬保护", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            if (state.pageCount > 1) {
                item {
                    Row(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onPrevious,
                            enabled = state.page > 0 && !state.running,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.ChevronLeft, null)
                            Text("上一页")
                        }
                        FilledTonalButton(
                            onClick = onNext,
                            enabled = state.page + 1 < state.pageCount && !state.running,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("下一页")
                            Icon(Icons.Rounded.ChevronRight, null)
                        }
                    }
                }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("清理 ${state.selected.size} 个手动选择项目？") },
            text = {
                Text("白名单、系统核心路径、挂载点、符号链接和关键风险仍然无法绕过。高风险项目会在删除前重新校验。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onClean()
                }) { Text("确认清理") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } }
        )
    }
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
