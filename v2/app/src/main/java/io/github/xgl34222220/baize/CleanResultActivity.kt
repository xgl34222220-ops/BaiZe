package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.CleanResultRootService
import io.github.xgl34222220.baize.root.ICleanResultService
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Stage five read-only result viewer. */
class CleanResultActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private val preferences by lazy { getSharedPreferences("baize_v2", MODE_PRIVATE) }
    private var service: ICleanResultService? = null
    private var binding = false
    private var reportId = ""
    private var state by mutableStateOf(CleanResultUiState())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = ICleanResultService.Stub.asInterface(binder)
            binding = true
            state = state.copy(connected = true, status = "清理报告引擎已连接")
            loadReport(reset = true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            state = state.copy(connected = false, status = "清理报告引擎连接已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        reportId = intent.getStringExtra(EXTRA_REPORT_ID).orEmpty()
            .ifBlank { preferences.getString(PREF_LAST_REPORT_ID, "").orEmpty() }
        setContent {
            val appearance by appearanceViewModel.settings.collectAsState()
            BaiZeTheme(appearance) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CleanResultScreen(
                        state = state,
                        onBack = ::finish,
                        onReconnect = ::bindService,
                        onRefresh = { loadReport(reset = true) },
                        onAction = ::selectAction,
                        onLoadMore = { loadReport(reset = false) }
                    )
                }
            }
        }
        bindService()
    }

    private fun bindService() {
        if (service != null || binding) return
        runCatching {
            RootService.bind(
                Intent(this, CleanResultRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
            binding = true
            state = state.copy(status = "正在连接清理报告引擎…")
        }.onFailure {
            binding = false
            state = state.copy(status = "清理报告引擎启动失败：${it.message}")
        }
    }

    private fun selectAction(action: String) {
        if (state.action == action || state.loading) return
        state = state.copy(action = action)
        loadReport(reset = true)
    }

    private fun loadReport(reset: Boolean) {
        val resultService = service ?: return
        if (state.loading) return
        val offset = if (reset) 0 else state.items.size
        state = state.copy(loading = true, message = if (reset) "正在读取清理报告…" else "正在加载更多结果…")
        lifecycleScope.launch {
            try {
                val (summary, page) = withContext(Dispatchers.IO) {
                    val summaryJson = JSONObject(resultService.getSummary(reportId.ifBlank { "latest" }))
                    if (summaryJson.has("error")) throw IllegalStateException(summaryJson.optString("message"))
                    val resolvedId = summaryJson.optString("reportId")
                    val filters = JSONObject().put("action", state.action)
                    val pageJson = JSONObject(resultService.getPage(resolvedId, offset, PAGE_SIZE, filters.toString()))
                    if (pageJson.has("error")) throw IllegalStateException(pageJson.optString("message"))
                    summaryJson to pageJson
                }
                reportId = summary.optString("reportId", reportId)
                if (reportId.isNotBlank()) preferences.edit().putString(PREF_LAST_REPORT_ID, reportId).apply()
                val parsed = parseItems(page.optJSONArray("items"))
                state = state.copy(
                    connected = true,
                    loading = false,
                    status = if (summary.optBoolean("live")) "清理事务实时报告" else "已归档清理报告",
                    message = if (page.optInt("total") == 0) "当前筛选下没有结果" else "共 ${page.optInt("total")} 项结果",
                    reportId = reportId,
                    authorizedCandidates = summary.optInt("authorizedCandidates").coerceAtLeast(0),
                    processedCandidates = summary.optInt("processedCandidates").coerceAtLeast(0),
                    remainingCandidates = summary.optInt("remainingCandidates").coerceAtLeast(0),
                    cleanedCandidates = summary.optInt("cleanedCandidates").coerceAtLeast(0),
                    changedCandidates = summary.optInt("changedCandidates").coerceAtLeast(0),
                    protectedCandidates = summary.optInt("protectedCandidates").coerceAtLeast(0),
                    partialCandidates = summary.optInt("partialCandidates").coerceAtLeast(0),
                    failedCandidates = summary.optInt("failedCandidates").coerceAtLeast(0),
                    estimatedBytes = summary.optLong("estimatedBytes").coerceAtLeast(0L),
                    deletedBytes = summary.optLong("actualDeletedBytes", summary.optLong("deletedBytes")).coerceAtLeast(0L),
                    completionPercent = summary.optInt("completionPercent").coerceIn(0, 100),
                    spaceRecoveryPercent = summary.optInt("spaceRecoveryPercent").coerceAtLeast(0),
                    items = if (reset) parsed else state.items + parsed,
                    totalItems = page.optInt("total").coerceAtLeast(0),
                    hasMore = page.optBoolean("hasMore")
                )
            } catch (throwable: Throwable) {
                state = state.copy(loading = false, message = "清理报告读取失败：${throwable.message ?: throwable.javaClass.simpleName}")
            }
        }
    }

    private fun parseItems(array: JSONArray?): List<CleanResultItem> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    CleanResultItem(
                        id = item.optString("id", item.optString("path")),
                        path = item.optString("path"),
                        category = item.optString("category", "other"),
                        risk = item.optString("risk", "low"),
                        action = item.optString("action", "changed"),
                        reason = item.optString("reason"),
                        estimatedBytes = item.optLong("estimatedBytes").coerceAtLeast(0L),
                        deletedBytes = item.optLong("deletedBytes").coerceAtLeast(0L),
                        deletedFiles = item.optLong("deletedFiles").coerceAtLeast(0L),
                        deletedDirectories = item.optLong("deletedDirectories").coerceAtLeast(0L)
                    )
                )
            }
        }
    }

    override fun onDestroy() {
        if (binding) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REPORT_ID = "reportId"
        private const val PREF_LAST_REPORT_ID = "last_clean_result_id"
        private const val PAGE_SIZE = 60
    }
}

private data class CleanResultItem(
    val id: String,
    val path: String,
    val category: String,
    val risk: String,
    val action: String,
    val reason: String,
    val estimatedBytes: Long,
    val deletedBytes: Long,
    val deletedFiles: Long,
    val deletedDirectories: Long
)

private data class CleanResultUiState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val status: String = "正在连接清理报告引擎…",
    val message: String = "等待读取最近一次清理报告",
    val reportId: String = "",
    val action: String = "all",
    val authorizedCandidates: Int = 0,
    val processedCandidates: Int = 0,
    val remainingCandidates: Int = 0,
    val cleanedCandidates: Int = 0,
    val changedCandidates: Int = 0,
    val protectedCandidates: Int = 0,
    val partialCandidates: Int = 0,
    val failedCandidates: Int = 0,
    val estimatedBytes: Long = 0L,
    val deletedBytes: Long = 0L,
    val completionPercent: Int = 0,
    val spaceRecoveryPercent: Int = 0,
    val items: List<CleanResultItem> = emptyList(),
    val totalItems: Int = 0,
    val hasMore: Boolean = false
)

@Composable
private fun CleanResultScreen(
    state: CleanResultUiState,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    onRefresh: () -> Unit,
    onAction: (String) -> Unit,
    onLoadMore: () -> Unit
) {
    val context = LocalContext.current
    val actions = listOf(
        "all" to "全部",
        "cleaned" to "已清理 ${state.cleanedCandidates}",
        "changed" to "已变化 ${state.changedCandidates}",
        "protected" to "受保护 ${state.protectedCandidates}",
        "partial" to "部分 ${state.partialCandidates}",
        "failed" to "失败 ${state.failedCandidates}"
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 34.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "返回") }
                Column(modifier = Modifier.weight(1f)) {
                    Text("CLEAN REPORT", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("清理报告", fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text("清理前后对比 · 逐项结果 · 原因可追踪", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onRefresh, enabled = state.connected && !state.loading) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(30.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(58.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (state.processedCandidates > 0) Icons.Rounded.CheckCircle else Icons.Rounded.CleaningServices,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Spacer(Modifier.size(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(state.status, fontWeight = FontWeight.Bold)
                            Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (state.reportId.isNotBlank()) {
                                Text("报告 ${state.reportId.take(8)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                        }
                    }
                    if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (!state.connected) {
                        OutlinedButton(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) { Text("重新连接 Root 引擎") }
                    }
                }
            }
        }

        if (state.authorizedCandidates > 0 || state.processedCandidates > 0) {
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text("BEFORE / AFTER", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("清理前后对比", fontSize = 26.sp, fontWeight = FontWeight.Black)
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("计划预计 ${Formatter.formatFileSize(context, state.estimatedBytes)}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("实际释放 ${Formatter.formatFileSize(context, state.deletedBytes)} · 空间兑现 ${state.spaceRecoveryPercent}%", color = MaterialTheme.colorScheme.primary)
                        Text(
                            "授权 ${state.authorizedCandidates} 项 · 已处理 ${state.processedCandidates} 项 · 剩余 ${state.remainingCandidates} 项 · 完成度 ${state.completionPercent}%",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(actions, key = { it.first }) { (key, label) ->
                        FilterChip(selected = state.action == key, onClick = { onAction(key) }, label = { Text(label) })
                    }
                }
            }
        }

        items(state.items, key = { it.id + it.action }) { item ->
            CleanResultItemCard(item)
        }

        if (state.hasMore) {
            item {
                Button(onClick = onLoadMore, enabled = !state.loading, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text("加载更多 · ${state.items.size}/${state.totalItems}")
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Text(
                    "“已变化”表示扫描后目标不存在或属性变化；“受保护”表示白名单、安全边界或系统保护生效；“部分”和“失败”会继续保留在断点计划中，不会被伪装成清理成功。",
                    modifier = Modifier.padding(18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun CleanResultItemCard(item: CleanResultItem) {
    val context = LocalContext.current
    val actionLabel = when (item.action) {
        "cleaned" -> "已清理"
        "protected" -> "受保护"
        "partial" -> "部分完成"
        "failed" -> "清理失败"
        else -> "已变化"
    }
    val categoryLabel = when (item.category) {
        "cache" -> "应用缓存"
        "empty" -> "空项目"
        "rules" -> "规则垃圾"
        "fragment" -> "残留碎片"
        else -> "其他"
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(actionLabel, color = if (item.action == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("$categoryLabel · ${riskLabel(item.risk)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Text(item.path, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(item.reason, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text(
                "预计 ${Formatter.formatFileSize(context, item.estimatedBytes)} · 实际 ${Formatter.formatFileSize(context, item.deletedBytes)} · 文件 ${item.deletedFiles} · 目录 ${item.deletedDirectories}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}

private fun riskLabel(value: String): String = when (value) {
    "medium" -> "中风险"
    "high" -> "高风险"
    "critical" -> "关键风险"
    else -> "低风险"
}
