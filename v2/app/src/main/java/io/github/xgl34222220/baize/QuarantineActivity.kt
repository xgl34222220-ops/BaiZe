package io.github.xgl34222220.baize

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
            service = IProfileRootService.Stub.asInterface(binder)
            bound = true
            state = state.copy(connected = true, message = "Root 隔离服务已连接")
            loadItems()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            state = state.copy(connected = false, loading = false, message = "Root 隔离服务已断开")
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
        state = state.copy(message = "正在连接 Root 隔离服务…")
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
            bound = true
        }.onFailure {
            bound = false
            state = state.copy(message = "Root 隔离服务启动失败：${it.message.orEmpty()}")
        }
    }

    private fun loadItems() {
        val root = service ?: return
        if (state.loading) return
        state = state.copy(loading = true, message = "正在读取隔离记录…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { JSONObject(root.getQuarantinePage(0, 200)) }
            }
            result.onSuccess { json ->
                if (!json.optBoolean("success")) {
                    state = state.copy(loading = false, message = json.optString("message", "读取隔离区失败"))
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
                    items = values,
                    retentionDays = json.optInt("retentionDays", 7),
                    message = if (purged > 0) "已自动清理 $purged 个过期隔离项" else if (values.isEmpty()) "隔离区为空" else "共 ${values.size} 个隔离项"
                )
            }.onFailure {
                state = state.copy(loading = false, message = "读取隔离区失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun restore(item: QuarantineItem) = mutate("正在恢复 ${item.label}…") { it.restoreQuarantineItem(item.id) }
    private fun purge(item: QuarantineItem) = mutate("正在永久删除 ${item.label}…") { it.purgeQuarantineItem(item.id) }
    private fun purgeExpired() = mutate("正在清理过期隔离项…") { it.purgeExpiredQuarantine() }

    private fun mutate(message: String, block: (IProfileRootService) -> String) {
        val root = service ?: return
        if (state.loading) return
        state = state.copy(loading = true, message = message)
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { JSONObject(block(root)) } }
            result.onSuccess { json ->
                state = state.copy(loading = false, message = json.optString("message", if (json.optBoolean("success")) "操作完成" else "操作失败"))
                loadItems()
            }.onFailure {
                state = state.copy(loading = false, message = "操作失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }
}

private data class QuarantineItem(
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

private data class QuarantineUiState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val items: List<QuarantineItem> = emptyList(),
    val retentionDays: Int = 7,
    val message: String = "等待连接 Root 隔离服务"
) {
    val totalBytes: Long get() = items.sumOf { it.bytes }
}

@Composable
private fun QuarantineScreen(
    state: QuarantineUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRestore: (QuarantineItem) -> Unit,
    onPurge: (QuarantineItem) -> Unit,
    onPurgeExpired: () -> Unit
) {
    var pending by remember { mutableStateOf<Pair<String, QuarantineItem>?>(null) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "返回") }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text("隔离区", fontSize = 30.sp, fontWeight = FontWeight.Black)
                        Text("高风险内容可恢复，过期后自动永久删除", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    IconButton(onClick = onRefresh, enabled = state.connected && !state.loading) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                    }
                }
            }
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(Modifier.size(50.dp), RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)) {
                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Inventory2, null, tint = MaterialTheme.colorScheme.primary) }
                            }
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${state.items.size} 个隔离项", fontSize = 21.sp, fontWeight = FontWeight.Black)
                                Text("占用 ${Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current, state.totalBytes)} · 保留 ${state.retentionDays} 天", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        if (state.loading) {
                            Spacer(Modifier.height(10.dp))
                            CircularProgressIndicator(Modifier.size(24.dp))
                        }
                        if (state.items.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = onPurgeExpired, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                                Text("立即清理过期项")
                            }
                        }
                    }
                }
            }
            if (state.items.isEmpty() && !state.loading) {
                item {
                    Card(
                        modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Inventory2, null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(10.dp))
                            Text("暂无隔离内容", fontWeight = FontWeight.Bold)
                            Text("扫描结果中的高风险项目可选择“隔离”，不会直接永久删除。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                }
            }
            items(state.items, key = { it.id }) { item ->
                Card(
                    modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "${Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current, item.bytes)} · ${item.files} 文件 · ${item.directories} 目录",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.error.copy(alpha = .12f)) {
                                Text("高风险", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(item.originalPath, color = MaterialTheme.colorScheme.outline, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            FilledTonalButton(onClick = { pending = "restore" to item }, modifier = Modifier.weight(1f), enabled = !state.loading) {
                                Icon(Icons.Rounded.Restore, null)
                                Spacer(Modifier.width(5.dp))
                                Text("恢复")
                            }
                            OutlinedButton(onClick = { pending = "purge" to item }, modifier = Modifier.weight(1f), enabled = !state.loading) {
                                Icon(Icons.Rounded.DeleteForever, null)
                                Spacer(Modifier.width(5.dp))
                                Text("永久删除")
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }

    pending?.let { (action, item) ->
        val restore = action == "restore"
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(if (restore) "恢复隔离内容？" else "永久删除隔离内容？") },
            text = {
                Text(if (restore) "将恢复到原路径；若原路径已有内容，会恢复为带 baize-restored 标记的安全副本。" else "永久删除后无法撤销。此操作只会删除服务器记录的隔离载荷。")
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    if (restore) onRestore(item) else onPurge(item)
                }) { Text(if (restore) "恢复" else "永久删除") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } }
        )
    }
}
