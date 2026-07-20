package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.IBinder
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
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
import java.security.MessageDigest
import java.io.File

/**
 * PackageManager cache-only tool. The package catalog and icons are loaded away from the main
 * thread. Icons are fetched lazily for visible rows and retained in a small memory cache so a list
 * containing hundreds of applications can scroll without repeatedly decoding drawables.
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
        val result = ArrayList<InstantCacheApp>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val packageName = item.optString("packageName").trim()
            if (!PACKAGE_NAME.matches(packageName) || packageName in BLOCKED_PACKAGES) continue
            val appInfo = runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            val label = appInfo
                ?.let { runCatching { packageManager.getApplicationLabel(it).toString() }.getOrNull() }
                ?.trim()
                .orEmpty()
                .ifBlank { packageName }
            val localSystem = appInfo?.let { info ->
                info.flags.and(ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    info.flags.and(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            } ?: false
            result += InstantCacheApp(
                packageName = packageName,
                label = label,
                system = item.optBoolean("system") || localSystem
            )
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
            visible.forEach { if (next.size < MAX_SELECTION) next += it }
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
            groupMatches && (
                needle.isBlank() ||
                    app.label.lowercase().contains(needle) ||
                    app.packageName.lowercase().contains(needle)
                )
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
                        "不会清除账号、设置或应用数据，但应用下次启动可能重新生成缓存。"
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
                tonalElevation = 1.dp,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text(
                        "已选择 ${state.selected.size}/$MAX_VISIBLE_SELECTION 个应用",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    Button(
                        onClick = { if (state.running) onStop() else showConfirmation = true },
                        enabled = state.running || (state.connected && state.selected.isNotEmpty()),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(contentType = "notice") {
                NoticeCard(
                    icon = Icons.Rounded.Info,
                    title = "系统 cache-only 接口",
                    text = "仅处理本次明确选择的应用；应用图标按可见范围异步加载，不会阻塞列表滚动。",
                    warning = true
                )
            }
            state.lastResult?.let { result ->
                item(contentType = "result") {
                    NoticeCard(
                        icon = Icons.Rounded.CheckCircle,
                        title = if (result.cancelled) "任务已停止" else "上次执行结果",
                        text = "成功 ${result.succeeded} 个 · 失败 ${result.failed} 个",
                        warning = result.failed > 0 || result.cancelled
                    )
                }
            }
            item(contentType = "status") {
                Text(state.status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            item(contentType = "search") {
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
            item(contentType = "filters") {
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
            item(contentType = "selection") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("当前显示 ${visible.size} 个", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { onSelectVisible(visible.map { it.packageName }) },
                        enabled = visible.isNotEmpty() && !state.running
                    ) { Text("选择当前") }
                    TextButton(
                        onClick = onClearSelection,
                        enabled = state.selected.isNotEmpty() && !state.running
                    ) { Text("清空") }
                }
            }
            if (state.loading) {
                item(contentType = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (visible.isEmpty()) {
                item(contentType = "empty") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("没有匹配的应用", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(
                    items = visible,
                    key = { it.packageName },
                    contentType = { "app" }
                ) { app ->
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

private const val MAX_VISIBLE_SELECTION = 30

@Composable
private fun NoticeCard(icon: ImageVector, title: String, text: String, warning: Boolean) {
    Surface(
        color = if (warning) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, Modifier.size(21.dp))
            Spacer(Modifier.width(11.dp))
            Column {
                Text(title, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(3.dp))
                Text(text, fontSize = 12.sp, lineHeight = 17.sp)
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
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) scheme.primaryContainer.copy(alpha = .55f) else scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.onSurface.copy(alpha = if (selected) .10f else .045f)),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PackageIcon(packageName = app.packageName, label = app.label)
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
                            color = scheme.secondaryContainer
                        ) {
                            Text("系统", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 9.sp)
                        }
                    }
                }
                Text(
                    app.packageName,
                    color = scheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Checkbox(checked = selected, onCheckedChange = { onClick() }, enabled = enabled)
        }
    }
}

@Composable
private fun PackageIcon(packageName: String, label: String) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<Bitmap?>(
        initialValue = AppIconCache.get(packageName),
        key1 = packageName
    ) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { AppIconCache.load(context, packageName) }
        }
    }
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = .10f)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(label.trim().firstOrNull()?.uppercase() ?: "?", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
        }
    }
}

private object AppIconCache {
    private const val ICON_PX = 96
    private const val MAX_DISK_FILES = 220
    private val cache = object : LruCache<String, Bitmap>(96) {}

    @Synchronized
    fun get(packageName: String): Bitmap? = cache.snapshot().entries
        .firstOrNull { it.key.startsWith("$packageName:") }
        ?.value

    fun load(context: Context, packageName: String): Bitmap? {
        val info = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull() ?: return null
        val packageInfo = runCatching { context.packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val key = "$packageName:${packageInfo?.lastUpdateTime ?: info.sourceDir.hashCode()}"
        synchronized(this) { cache.get(key) }?.let { return it }

        val directory = File(context.cacheDir, "app-icons-v2").apply { mkdirs() }
        val disk = File(directory, sha256(key) + ".png")
        val fromDisk = runCatching { if (disk.isFile) BitmapFactory.decodeFile(disk.path) else null }.getOrNull()
        if (fromDisk != null) {
            synchronized(this) { cache.put(key, fromDisk) }
            disk.setLastModified(System.currentTimeMillis())
            return fromDisk
        }

        val bitmap = runCatching {
            context.packageManager.getApplicationIcon(info)
                .toBitmap(width = ICON_PX, height = ICON_PX, config = Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        synchronized(this) { cache.put(key, bitmap) }
        runCatching {
            val temp = File(directory, disk.name + ".tmp")
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!temp.renameTo(disk)) {
                temp.copyTo(disk, overwrite = true)
                temp.delete()
            }
            directory.listFiles()?.filter(File::isFile)?.sortedByDescending(File::lastModified)
                ?.drop(MAX_DISK_FILES)?.forEach(File::delete)
        }
        return bitmap
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
