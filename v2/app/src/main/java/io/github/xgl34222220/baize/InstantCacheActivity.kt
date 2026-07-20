package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit PackageManager cache-only tool.
 *
 * This screen deliberately does not consume or create BaiZe scan snapshots. It operates only on
 * packages selected by the user after a second confirmation and is never called by the scheduler.
 */
class InstantCacheActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private var service: IProfileRootService? = null
    private var serviceBound = false
    private var uiState by mutableStateOf(InstantCacheUiState())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IProfileRootService.Stub.asInterface(binder)
            serviceBound = true
            uiState = uiState.copy(connected = true, status = "正在读取已安装应用…")
            loadCatalog()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            serviceBound = false
            uiState = uiState.copy(
                connected = false,
                running = false,
                status = "Root 服务已断开，请返回后重试"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val appearance = appearanceViewModel.settings.collectAsStateWithLifecycle().value
            BaiZeTheme(appearance) {
                InstantCacheScreen(
                    state = uiState,
                    onBack = ::finish,
                    onToggle = ::togglePackage,
                    onSelectVisible = ::selectVisible,
                    onClearSelection = { uiState = uiState.copy(selected = emptySet()) },
                    onRun = ::runInstantCache,
                    onStop = { runCatching { service?.cancelCurrentTask() } }
                )
            }
        }
        bindRootService()
    }

    private fun bindRootService() {
        uiState = uiState.copy(status = "正在连接 Root PackageManager 服务…")
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java)
                    .addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
            serviceBound = true
        }.onFailure {
            serviceBound = false
            uiState = uiState.copy(status = "Root 服务启动失败：${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun loadCatalog() {
        val root = service ?: return
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val raw = runCatching { root.getInstalledPackageCatalog() }.getOrNull()
                parseCatalog(raw)
            }
            uiState = uiState.copy(
                loading = false,
                apps = apps,
                status = if (apps.isEmpty()) {
                    "没有读取到可处理应用，请确认模块和 Root 服务正常"
                } else {
                    "已读取 ${apps.count { !it.system }} 个用户应用 · ${apps.count { it.system }} 个系统应用"
                }
            )
        }
    }

    private fun parseCatalog(raw: String?): List<InstantCacheApp> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONObject(raw).optJSONArray("packages") }.getOrNull() ?: JSONArray()
        val result = mutableListOf<InstantCacheApp>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val packageName = item.optString("packageName").trim()
            if (!PACKAGE_NAME.matches(packageName) || packageName in BLOCKED_PACKAGES) continue
            val appInfo = runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            val label = appInfo?.let { runCatching { packageManager.getApplicationLabel(it).toString() }.getOrNull() }
                ?.trim()
                .orEmpty()
                .ifBlank { packageName }
            val localSystem = appInfo?.let { info ->
                info.flags.and(ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    info.flags.and(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            } ?: false
            val system = item.optBoolean("system") || localSystem
            result += InstantCacheApp(packageName, label, system)
        }
        return result.distinctBy { it.packageName }
            .sortedWith(compareBy<InstantCacheApp> { it.system }.thenBy { it.label.lowercase() })
    }

    private fun togglePackage(packageName: String) {
        if (uiState.running) return
        val selected = uiState.selected.toMutableSet()
        if (!selected.add(packageName)) selected.remove(packageName)
        if (selected.size > MAX_SELECTION) {
            selected.remove(packageName)
            uiState = uiState.copy(status = "单次最多选择 $MAX_SELECTION 个应用")
            return
        }
        uiState = uiState.copy(selected = selected)
    }

    private fun selectVisible(packages: List<String>) {
        if (uiState.running) return
        val visible = packages.filter { packageName -> uiState.apps.any { it.packageName == packageName } }
        val allSelected = visible.isNotEmpty() && visible.all { it in uiState.selected }
        val next = uiState.selected.toMutableSet()
        if (allSelected) {
            next.removeAll(visible.toSet())
        } else {
            visible.forEach {
                if (next.size < MAX_SELECTION) next += it
            }
        }
        uiState = uiState.copy(
            selected = next,
            status = if (!allSelected && visible.size > MAX_SELECTION) {
                "已按单次上限选择前 $MAX_SELECTION 个应用"
            } else uiState.status
        )
    }

    private fun runInstantCache(packages: Set<String>) {
        val root = service ?: run {
            uiState = uiState.copy(status = "Root 服务尚未连接")
            return
        }
        if (packages.isEmpty() || uiState.running) return
        uiState = uiState.copy(running = true, status = "正在请求系统逐个清除当前缓存…", lastResult = null)
        lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) {
                val payload = JSONObject()
                    .put("userId", currentUserId())
                    .put("packages", JSONArray(packages.sorted()))
                runCatching { root.clearPackageCaches(payload.toString()) }
                    .getOrElse { JSONObject().put("success", false).put("message", it.message).toString() }
            }
            val result = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            val succeeded = result.optInt("succeeded", 0)
            val failed = result.optInt("failed", 0)
            val cancelled = result.optBoolean("cancelled")
            uiState = uiState.copy(
                running = false,
                selected = if (succeeded > 0) emptySet() else uiState.selected,
                status = result.optString("message").ifBlank {
                    result.optString("error", "系统即时清缓存失败")
                },
                lastResult = InstantCacheResult(succeeded, failed, cancelled)
            )
        }
    }

    override fun onDestroy() {
        if (serviceBound) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }

    companion object {
        private const val MAX_SELECTION = 30
        private val PACKAGE_NAME = Regex("""^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$""")
        private val BLOCKED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "io.github.xgl34222220.baize"
        )
    }
}

private data class InstantCacheApp(
    val packageName: String,
    val label: String,
    val system: Boolean
)

private data class InstantCacheResult(
    val succeeded: Int,
    val failed: Int,
    val cancelled: Boolean
)

private data class InstantCacheUiState(
    val connected: Boolean = false,
    val loading: Boolean = true,
    val running: Boolean = false,
    val apps: List<InstantCacheApp> = emptyList(),
    val selected: Set<String> = emptySet(),
    val status: String = "正在准备系统即时清缓存…",
    val lastResult: InstantCacheResult? = null
)

private fun currentUserId(): Int = (android.os.Process.myUid() / 100_000).coerceAtLeast(0)

private enum class InstantCacheFilter(val title: String) {
    USER("用户应用"),
    SYSTEM("系统应用"),
    ALL("全部")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstantCacheScreen(
    state: InstantCacheUiState,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onSelectVisible: (List<String>) -> Unit,
    onClearSelection: () -> Unit,
    onRun: (Set<String>) -> Unit,
    onStop: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(InstantCacheFilter.USER) }
    var showConfirmation by rememberSaveable { mutableStateOf(false) }
    val visible = remember(state.apps, query, filter) {
        val needle = query.trim().lowercase()
        state.apps.filter { app ->
            val groupMatches = when (filter) {
                InstantCacheFilter.USER -> !app.system
                InstantCacheFilter.SYSTEM -> app.system
                InstantCacheFilter.ALL -> true
            }
            groupMatches && (needle.isBlank() || app.label.lowercase().contains(needle) ||
                app.packageName.lowercase().contains(needle))
        }
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!state.running) showConfirmation = false },
            icon = { Icon(Icons.Rounded.Bolt, null) },
            title = { Text("直接清除当前缓存？") },
            text = {
                Text(
                    "将立即调用 Android 系统清除所选 ${state.selected.size} 个应用的当前缓存。" +
                        "这不是扫描快照；不会清除账号、设置或应用数据，但应用下次启动可能重新下载或生成缓存。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmation = false
                    onRun(state.selected)
                }) { Text("确认执行") }
            },
            dismissButton = { TextButton(onClick = { showConfirmation = false }) { Text("取消") } }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = {
                    Column {
                        Text("系统即时清缓存", fontWeight = FontWeight.Black)
                        Text("PackageManager · 当前用户 ${currentUserId()}", fontSize = 10.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "已选择 ${state.selected.size}/30 个应用",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    Button(
                        onClick = {
                            if (state.running) onStop() else showConfirmation = true
                        },
                        enabled = state.running || (state.connected && state.selected.isNotEmpty()),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = if (state.running) {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        } else ButtonDefaults.buttonColors()
                    ) {
                        if (state.running) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("停止即时清缓存")
                        } else {
                            Icon(Icons.Rounded.Bolt, null)
                            Spacer(Modifier.width(8.dp))
                            Text("清除所选应用当前缓存")
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                NoticeCard(
                    icon = Icons.Rounded.Info,
                    title = "与精准快照完全分离",
                    text = "本工具不会先扫描，也不会进入后台自动清理。它只处理你本次明确选择的应用，并调用系统 cache-only 接口。",
                    warning = true
                )
            }
            state.lastResult?.let { result ->
                item {
                    NoticeCard(
                        icon = Icons.Rounded.CheckCircle,
                        title = if (result.cancelled) "任务已停止" else "上次执行结果",
                        text = "成功 ${result.succeeded} 个 · 失败 ${result.failed} 个",
                        warning = result.failed > 0 || result.cancelled
                    )
                }
            }
            item {
                Text(state.status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索应用或包名") },
                    singleLine = true,
                    enabled = !state.running,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InstantCacheFilter.entries.forEach { item ->
                        FilterChip(
                            selected = filter == item,
                            onClick = { filter = item },
                            label = { Text(item.title) },
                            enabled = !state.running
                        )
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("当前显示 ${visible.size} 个", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { onSelectVisible(visible.map { it.packageName }) },
                        enabled = visible.isNotEmpty() && !state.running
                    ) { Text("选择当前") }
                    TextButton(onClick = onClearSelection, enabled = state.selected.isNotEmpty() && !state.running) {
                        Text("清空")
                    }
                }
            }
            if (state.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (visible.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("没有匹配的应用", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(visible, key = { it.packageName }) { app ->
                    InstantCacheAppRow(
                        app = app,
                        selected = app.packageName in state.selected,
                        enabled = !state.running,
                        onClick = { onToggle(app.packageName) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(icon: ImageVector, title: String, text: String, warning: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (warning) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(4.dp))
                Text(text, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun InstantCacheAppRow(
    app: InstantCacheApp,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = .12f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Apps, null) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        app.label,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (app.system) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text("系统", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 9.sp)
                        }
                    }
                }
                Text(
                    app.packageName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Checkbox(checked = selected, onCheckedChange = { onClick() }, enabled = enabled)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .04f))
    }
}
