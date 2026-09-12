package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.root.RootServiceClients
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
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
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class QuarantineActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private var service: IProfileRootService? = null
    private var bound = false
    private var state by mutableStateOf(QuarantineUiState())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = RootServiceClients.profile(binder, applicationContext.cacheDir)
            bound = true
            state = state.copy(connected = true, failed = false, message = "Root 隔离服务已连接")
            loadItems()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            state = state.copy(connected = false, loading = false, failed = true, message = "Root 隔离服务已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
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
                    QuarantineScreen(
                        state = state,
                        onBack = ::finish,
                        onRefresh = ::loadItems,
                        onRestore = ::restore,
                        onPurge = ::purge,
                        onPurgeExpired = ::purgeExpired
                    )
                }
            }
        }
        connect()
    }

    override fun onDestroy() {
        if (bound) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }

    private fun connect() {
        state = state.copy(failed = false, message = "正在连接 Root 隔离服务…")
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
            bound = true
        }.onFailure {
            bound = false
            state = state.copy(failed = true, message = "Root 隔离服务启动失败：${it.message.orEmpty()}")
        }
    }

    private fun loadItems() {
        val root = service ?: return
        if (state.loading) return
        state = state.copy(loading = true, failed = false, message = "正在读取隔离记录…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { JSONObject(root.getQuarantinePage(0, 200)) }
            }
            result.onSuccess { json ->
                if (!json.optBoolean("success")) {
                    state = state.copy(loading = false, failed = true, message = json.optString("message", "读取隔离区失败"))
                    return@onSuccess
                }
                val array = json.optJSONArray("items") ?: JSONArray()
                val values = buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        add(
                            QuarantineItem(
                                id = item.optString("id"),
                                originalPath = item.optString("originalPath"),
                                label = item.optString("label", item.optString("category", "高风险项目")),
                                profile = item.optString("profile"),
                                risk = item.optString("risk", "high"),
                                createdAt = item.optLong("createdAt"),
                                expiresAt = item.optLong("expiresAt"),
                                bytes = item.optLong("bytes").coerceAtLeast(0L),
                                files = item.optLong("files").coerceAtLeast(0L),
                                directories = item.optLong("directories").coerceAtLeast(0L)
                            )
                        )
                    }
                }
                val purged = json.optInt("expiredPurged")
                state = state.copy(
                    loading = false,
                    failed = false,
                    items = values,
                    retentionDays = json.optInt("retentionDays", 7),
                    message = if (purged > 0) "已自动清理 $purged 个过期隔离项" else if (values.isEmpty()) "隔离区为空" else "共 ${values.size} 个隔离项"
                )
            }.onFailure {
                state = state.copy(loading = false, failed = true, message = "读取隔离区失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun restore(item: QuarantineItem) = mutate("正在恢复 ${item.label}…") { it.restoreQuarantineItem(item.id) }
    private fun purge(item: QuarantineItem) = mutate("正在永久删除 ${item.label}…") { it.purgeQuarantineItem(item.id) }
    private fun purgeExpired() = mutate("正在清理过期隔离项…") { it.purgeExpiredQuarantine() }

    private fun mutate(message: String, block: (IProfileRootService) -> String) {
        val root = service ?: return
        if (state.loading) return
        state = state.copy(loading = true, failed = false, message = message)
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { JSONObject(block(root)) } }
            result.onSuccess { json ->
                val success = json.optBoolean("success")
                state = state.copy(loading = false, failed = !success, message = json.optString("message", if (success) "操作完成" else "操作失败"))
                if (success) loadItems()
            }.onFailure {
                state = state.copy(loading = false, failed = true, message = "操作失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }
}

internal data class QuarantineItem(
    val id: String,
    val originalPath: String,
    val label: String,
    val profile: String,
    val risk: String,
    val createdAt: Long,
    val expiresAt: Long,
    val bytes: Long,
    val files: Long,
    val directories: Long
)

internal data class QuarantineUiState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val items: List<QuarantineItem> = emptyList(),
    val retentionDays: Int = 7,
    val message: String = "等待连接 Root 隔离服务",
    val failed: Boolean = false
) {
    val totalBytes: Long get() = items.sumOf { it.bytes }
}

@Composable
internal fun QuarantineScreen(
    state: QuarantineUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRestore: (QuarantineItem) -> Unit,
    onPurge: (QuarantineItem) -> Unit,
    onPurgeExpired: () -> Unit
) {
    var pending by remember { mutableStateOf<Pair<String, QuarantineItem>?>(null) }
    var detail by remember { mutableStateOf<QuarantineItem?>(null) }
    val context = LocalContext.current
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = BaiZeTokens.colors.surfaceBase,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            DetailPageHeader("隔离区", "", onBack) {
                IconButton(onClick = onRefresh, enabled = state.connected && !state.loading) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                }
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(Formatter.formatFileSize(context, state.totalBytes), fontSize = 28.sp,
                        lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
                    Text("${state.items.size} 项暂存内容 · 保留 ${state.retentionDays} 天",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
                        if (state.failed) Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Text(state.message, fontSize = 13.sp, lineHeight = 19.sp,
                            color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 7.dp))
                }
            }
            if (state.message.length > 85) item { DetailExpandableText("完整状态", state.message) }
            if (state.items.isEmpty() && !state.loading) item {
                DetailEmptyState(
                    if (state.failed) "暂时无法读取隔离记录" else "暂无隔离内容",
                    if (state.failed) "点击右上角刷新重试。" else "扫描后选择隔离的内容会暂存在这里，可在到期前恢复。",
                    icon = if (state.failed) Icons.Rounded.ErrorOutline else Icons.Rounded.Inventory2
                )
            }
            if (state.items.isNotEmpty()) item { DetailSectionHeader("暂存文件", "点击条目查看完整路径与保留时间") }
            itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                QuarantineRow(item, first = index == 0, last = index == state.items.lastIndex,
                    enabled = state.connected && !state.loading, onDetails = { detail = item },
                    onRestore = { pending = "restore" to item }, onPurge = { pending = "purge" to item })
            }
            if (state.items.isNotEmpty()) item {
                GlassActionButton("清理过期项", onClick = onPurgeExpired, secondary = true,
                    enabled = state.connected && !state.loading,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp))
            }
            item {
                DetailExpandableText("隔离与恢复说明", "隔离内容可以在到期前恢复；到期后会永久删除。恢复时若原路径已有内容，会保留现有文件，将隔离内容恢复为带 baize-restored 标记的副本。手动永久删除后无法撤销。")
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
    detail?.let { item -> QuarantineDetails(item, onDismiss = { detail = null }) }
    pending?.let { (action, item) ->
        val restore = action == "restore"
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(if (restore) "恢复隔离内容？" else "永久删除隔离内容？") },
            text = {
                Text(if (restore) "将恢复到原路径；若原路径已有内容，会恢复为带 baize-restored 标记的副本。" else "永久删除后无法撤销。只会删除本次选择的隔离内容。")
            },
            confirmButton = {
                TextButton(onClick = { pending = null; if (restore) onRestore(item) else onPurge(item) }) {
                    Text(if (restore) "确认恢复" else "确认永久删除",
                        color = if (restore) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun QuarantineRow(
    item: QuarantineItem,
    first: Boolean,
    last: Boolean,
    enabled: Boolean,
    onDetails: () -> Unit,
    onRestore: () -> Unit,
    onPurge: () -> Unit
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(topStart = if (first) 18.dp else 0.dp, topEnd = if (first) 18.dp else 0.dp,
        bottomStart = if (last) 18.dp else 0.dp, bottomEnd = if (last) 18.dp else 0.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(shape).background(BaiZeTokens.colors.surfaceRaised)) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = "查看完整路径与详情", onClick = onDetails)
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.Top) {
            Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .075f)) {
                Icon(Icons.Rounded.Inventory2, null, Modifier.padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Text(item.label, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(Formatter.formatFileSize(context, item.bytes), fontSize = 12.sp, lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${quarantineRiskLabel(item.risk)} · ${item.files} 文件 · ${item.directories} 目录",
                    fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(item.originalPath, Modifier.weight(1f), fontSize = 11.sp, lineHeight = 16.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 54.dp, end = 8.dp, bottom = 3.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onRestore, enabled = enabled) { Text("恢复", fontSize = 13.sp) }
            TextButton(onClick = onPurge, enabled = enabled) {
                Text("永久删除", fontSize = 13.sp, color = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!last) HorizontalDivider(Modifier.padding(start = 61.dp, end = 14.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .055f))
    }
}

@Composable
private fun QuarantineDetails(item: QuarantineItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember(item) { mutableStateOf(false) }
    val text = buildString {
        appendLine(item.label)
        appendLine("${quarantineRiskLabel(item.risk)} · ${Formatter.formatFileSize(context, item.bytes)}")
        appendLine("${item.files} 文件 · ${item.directories} 目录")
        appendLine("隔离时间：${quarantineTime(item.createdAt)}")
        appendLine("到期时间：${quarantineTime(item.expiresAt)}")
        if (item.profile.isNotBlank()) appendLine("来源：${item.profile}")
        append("完整路径：${item.originalPath}")
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("隔离详情", fontSize = 18.sp) },
        text = { SelectionContainer { Text(text, Modifier.verticalScroll(rememberScrollState()), fontSize = 13.sp, lineHeight = 20.sp) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(text)); copied = true }) { Text(if (copied) "已复制" else "复制详情") } })
}

private fun quarantineRiskLabel(risk: String): String = when (risk) {
    "critical" -> "关键风险"
    "high" -> "高风险"
    "medium" -> "中风险"
    "low" -> "低风险"
    else -> "风险未标注"
}

private fun quarantineTime(value: Long): String {
    if (value <= 0) return "未记录"
    val milliseconds = if (value < 1_000_000_000_000L) value * 1_000L else value
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(milliseconds))
}
