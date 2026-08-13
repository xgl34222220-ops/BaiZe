package io.github.xgl34222220.baize

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class StorageToolsActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private var service: IProfileRootService? = null
    private var bound = false
    private var state by mutableStateOf(StorageToolsState())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IProfileRootService.Stub.asInterface(binder)
            bound = true
            state = state.copy(connected = true, status = "已连接 Root 存储引擎")
            runTool("storage-analysis")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            state = state.copy(connected = false, running = false, status = "Root 存储引擎已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val appearance = appearanceViewModel.settings.collectAsStateWithLifecycle().value
            BaiZeTheme(appearance) {
                CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
                    StorageToolsScreen(state, ::finish, ::runTool, ::copyPath)
                }
            }
        }
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
            bound = true
        }.onFailure { state = state.copy(status = "Root 服务连接失败：${it.message.orEmpty()}") }
    }

    private fun runTool(tool: String) {
        val root = service ?: run {
            state = state.copy(status = "Root 服务尚未连接")
            return
        }
        if (state.running) return
        state = state.copy(running = true, tool = tool, status = when (tool) {
            "duplicates" -> "正在按大小、快速哈希和完整哈希查找重复文件…"
            "large-files" -> "正在读取大文件索引…"
            else -> "正在分析存储占用…"
        })
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    JSONObject(root.runMaintenanceTool(tool, JSONObject().put("value", 100).toString()))
                }
            }
            result.onSuccess { json ->
                val rows = jsonObjects(json.optJSONArray("items"))
                val coverage = jsonObjects(json.optJSONArray("coverage"))
                state = state.copy(
                    running = false,
                    items = rows,
                    coverage = coverage,
                    status = if (json.optBoolean("success")) "分析完成，共 ${rows.size} 条结果；本页不会自动删除用户文件" else
                        json.optString("message", json.optString("output", "分析失败"))
                )
            }.onFailure { state = state.copy(running = false, status = "分析失败：${it.message.orEmpty()}") }
        }
    }

    private fun jsonObjects(array: JSONArray?): List<JSONObject> = buildList {
        if (array != null) for (index in 0 until array.length()) array.optJSONObject(index)?.let(::add)
    }

    private fun copyPath(path: String) {
        if (path.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("BaiZe path", path))
        Toast.makeText(this, "路径已复制", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        if (bound) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }
}

private data class StorageToolsState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val tool: String = "storage-analysis",
    val status: String = "正在连接 Root 存储引擎…",
    val items: List<JSONObject> = emptyList(),
    val coverage: List<JSONObject> = emptyList()
)

@Composable
private fun StorageToolsScreen(
    state: StorageToolsState,
    onBack: () -> Unit,
    onTool: (String) -> Unit,
    onCopy: (String) -> Unit
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "返回") }
                    Column(Modifier.weight(1f)) {
                        Text("存储分析", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("只分析与预览，不把大文件或重复文件自动判定为垃圾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.running) CircularProgressIndicator()
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("storage-analysis" to "分类", "large-files" to "大文件", "duplicates" to "重复文件").forEach { (id, title) ->
                        FilterChip(selected = state.tool == id, onClick = { onTool(id) }, label = { Text(title) })
                    }
                }
            }
            item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) { Text(state.status, Modifier.padding(16.dp)) }
            }
            if (state.coverage.any { it.optString("status") != "reused" && it.optString("status") != "scanned" }) {
                item {
                    Text("部分区域未完整覆盖，结果不会把这些区域计入可释放空间", Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.error)
                }
            }
            items(state.items, key = { item -> item.toString() }) { item ->
                StorageResultCard(state.tool, item, onCopy)
            }
            item { Column(Modifier.padding(20.dp)) {} }
        }
    }
}

@Composable
private fun StorageResultCard(tool: String, item: JSONObject, onCopy: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val title: String
    val subtitle: String
    val path: String
    when (tool) {
        "large-files" -> {
            path = item.optString("path")
            title = java.io.File(path).name.ifBlank { path }
            subtitle = "${Formatter.formatFileSize(context, item.optLong("size"))} · 点击复制路径"
        }
        "duplicates" -> {
            path = item.optString("duplicate")
            title = "重复副本 · ${Formatter.formatFileSize(context, item.optLong("size"))}"
            subtitle = "建议保留：${item.optString("keeper")}\n重复项：$path"
        }
        else -> {
            path = ""
            title = item.optString("group", "未分类")
            subtitle = "${item.optLong("files")} 个文件 · ${Formatter.formatFileSize(context, item.optLong("bytes"))}"
        }
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(enabled = path.isNotBlank()) { onCopy(path) },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (tool == "storage-analysis") Icons.Rounded.Storage else Icons.Rounded.Folder, null)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (path.isNotBlank()) Icon(Icons.Rounded.ContentCopy, "复制路径")
        }
    }
}
