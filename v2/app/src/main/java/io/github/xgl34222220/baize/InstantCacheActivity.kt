package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.root.RootServiceClients
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
import io.github.xgl34222220.baize.ui.miuix.VideoTabs
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
            service = RootServiceClients.profile(binder, applicationContext.cacheDir)
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

internal data class InstantCacheApp(
    val packageName: String,
    val label: String,
    val system: Boolean
)

internal data class InstantCacheResult(
    val succeeded: Int,
    val failed: Int,
    val cancelled: Boolean
)

internal data class InstantCacheUiState(
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

@Composable
internal fun InstantCacheScreen(
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
    var showHelp by rememberSaveable { mutableStateOf(false) }
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
    val allVisibleSelected = visible.isNotEmpty() && visible.all { it.packageName in state.selected }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!state.running) showConfirmation = false },
            title = { Text("清除 ${state.selected.size} 个应用的缓存？", fontSize = 18.sp) },
            text = { Text("保留账号、设置和应用数据。应用下次启动时可能重新生成缓存。") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmation = false
                    onRun(state.selected)
                }) { Text("清除缓存") }
            },
            dismissButton = { TextButton(onClick = { showConfirmation = false }) { Text("取消") } }
        )
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("即时清缓存", fontSize = 18.sp) },
            text = {
                Text("通过系统清除所选应用的当前缓存，保留账号、设置和应用数据。单次最多选择 $MAX_VISIBLE_SELECTION 个应用。\n\n部分系统应用可能不允许清除缓存，失败时可以查看任务结果。")
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = BaiZeTokens.colors.surfaceBase,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            DetailPageHeader("即时清缓存", "", onBack, actions = {
                IconButton(onClick = { showHelp = true }) { Icon(Icons.Rounded.Info, "清理说明", Modifier.size(21.dp)) }
            })
        },
        bottomBar = {
            Surface(color = BaiZeTokens.colors.surfaceBase, modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("已选 ${state.selected.size} 个应用", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.weight(1f))
                        Text("单次最多 $MAX_VISIBLE_SELECTION 个", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    GlassActionButton(
                        label = if (state.running) "停止清理" else "清除所选缓存",
                        onClick = { if (state.running) onStop() else showConfirmation = true },
                        enabled = state.running || (state.connected && state.selected.isNotEmpty()),
                        secondary = state.running,
                        icon = if (state.running) Icons.Rounded.Stop else Icons.Rounded.Bolt,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item(contentType = "status") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 14.dp)) {
                    Text(
                        if (state.running) "正在清除所选缓存" else "选择应用，清除当前缓存",
                        fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    DetailStatusText(state.status, Modifier.padding(top = 4.dp))
                    if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
                }
            }
            state.lastResult?.let { result ->
                item(contentType = "result") {
                    val failed = result.failed > 0 || (!result.cancelled && result.succeeded == 0)
                    val title = when {
                        result.cancelled -> "清理已停止"
                        failed && result.succeeded > 0 -> "部分应用未能清理"
                        failed -> "清理未完成"
                        else -> "缓存已清理"
                    }
                    val icon = when {
                        result.cancelled -> Icons.Rounded.PauseCircleOutline
                        failed -> Icons.Rounded.ErrorOutline
                        else -> Icons.Rounded.CheckCircle
                    }
                    val tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(16.dp)).background(tint.copy(alpha = .055f)).padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(icon, null, Modifier.size(22.dp), tint = tint)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Text("成功 ${result.succeeded} 个 · 失败 ${result.failed} 个", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item(contentType = "search") {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索应用或包名", fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(21.dp)) },
                    singleLine = true,
                    enabled = !state.running,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = BaiZeTokens.colors.surfaceRaised,
                        unfocusedContainerColor = BaiZeTokens.colors.surfaceRaised,
                        disabledContainerColor = BaiZeTokens.colors.surfaceRaised,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )
            }
            item(contentType = "filters") {
                VideoTabs(
                    labels = listOf("用户", "系统", "全部"),
                    selectedIndex = InstantCacheFilter.entries.indexOf(filter),
                    onSelected = { if (!state.running) filter = InstantCacheFilter.entries[it] },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
            }
            item(contentType = "selection") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${visible.size} 个应用", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { onSelectVisible(visible.map { it.packageName }) },
                        enabled = visible.isNotEmpty() && !state.running,
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) { Text(if (allVisibleSelected) "取消当前" else "选择当前", fontSize = 12.sp) }
                    TextButton(
                        onClick = onClearSelection,
                        enabled = state.selected.isNotEmpty() && !state.running,
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) { Text("清空", fontSize = 12.sp) }
                }
            }
            if (state.loading) {
                item(contentType = "loading") {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("正在读取应用…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (visible.isEmpty()) {
                item(contentType = "empty") {
                    DetailEmptyState("没有匹配的应用", "试试其他分类，或调整搜索内容。")
                }
            } else {
                itemsIndexed(items = visible, key = { _, app -> app.packageName }, contentType = { _, _ -> "app" }) { index, app ->
                    InstantCacheAppRow(
                        app = app,
                        selected = app.packageName in state.selected,
                        enabled = !state.running,
                        first = index == 0,
                        last = index == visible.lastIndex,
                        onClick = { onToggle(app.packageName) }
                    )
                }
            }
        }
    }
}

private const val MAX_VISIBLE_SELECTION = 30

@Composable
private fun InstantCacheAppRow(
    app: InstantCacheApp,
    selected: Boolean,
    enabled: Boolean,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = if (first) 18.dp else 0.dp, topEnd = if (first) 18.dp else 0.dp,
        bottomStart = if (last) 18.dp else 0.dp, bottomEnd = if (last) 18.dp else 0.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(shape)
        .background(if (selected) scheme.primary.copy(alpha = .045f) else BaiZeTokens.colors.surfaceRaised)
        .toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 13.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PackageIcon(packageName = app.packageName, label = app.label)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(app.label, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = scheme.onSurface)
                Text(app.packageName, color = scheme.onSurfaceVariant, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (app.system) Text("系统应用", color = scheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Checkbox(checked = selected, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(12.dp))
        }
        if (!last) HorizontalDivider(Modifier.padding(start = 64.dp, end = 14.dp), color = scheme.onSurface.copy(alpha = .05f))
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
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
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
            Text(label.trim().firstOrNull()?.uppercase() ?: "?", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
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
