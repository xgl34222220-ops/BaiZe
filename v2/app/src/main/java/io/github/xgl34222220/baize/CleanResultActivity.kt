package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
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
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.CleanResultRootService
import io.github.xgl34222220.baize.root.ICleanResultService
import io.github.xgl34222220.baize.root.IProfileRootService
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Stage six read-only result viewer with explainable rules and safe whitelist shortcuts. */
class CleanResultActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private val preferences by lazy { getSharedPreferences("baize_v2", MODE_PRIVATE) }

    private var reportService: ICleanResultService? = null
    private var whitelistService: IProfileRootService? = null
    private var reportBinding = false
    private var whitelistBinding = false
    private var reportId = ""
    private var state by mutableStateOf(CleanResultUiState())

    private val reportConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            reportService = ICleanResultService.Stub.asInterface(binder)
            reportBinding = true
            state = state.copy(connected = true, status = "清理报告引擎已连接")
            loadReport(reset = true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            reportService = null
            reportBinding = false
            state = state.copy(connected = false, status = "清理报告引擎连接已断开")
        }
    }

    private val whitelistConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            whitelistService = IProfileRootService.Stub.asInterface(binder)
            whitelistBinding = true
            state = state.copy(whitelistConnected = true)
            refreshProtectedPackages()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            whitelistService = null
            whitelistBinding = false
            state = state.copy(whitelistConnected = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        reportId = intent.getStringExtra(EXTRA_REPORT_ID).orEmpty()
            .ifBlank { preferences.getString(PREF_LAST_REPORT_ID, "").orEmpty() }
        state = state.copy(
            protectedPackages = preferences.getStringSet(PREF_PACKAGE_WHITELIST, emptySet()).orEmpty(),
            protectedPaths = preferences.getStringSet(PREF_PATH_WHITELIST, emptySet()).orEmpty()
        )
        setContent {
            val appearance by appearanceViewModel.settings.collectAsState()
            BaiZeTheme(appearance) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CleanResultScreen(
                        state = state,
                        onBack = ::finish,
                        onReconnect = ::bindRootServices,
                        onRefresh = { loadReport(reset = true) },
                        onAction = ::selectAction,
                        onLoadMore = { loadReport(reset = false) },
                        onProtectPackage = ::protectPackage,
                        onProtectPath = ::protectPath
                    )
                }
            }
        }
        bindRootServices()
    }

    private fun bindRootServices() {
        if (reportService == null && !reportBinding) {
            runCatching {
                RootService.bind(
                    Intent(this, CleanResultRootService::class.java)
                        .addCategory(RootService.CATEGORY_DAEMON_MODE),
                    reportConnection
                )
                reportBinding = true
                state = state.copy(status = "正在连接清理报告引擎…")
            }.onFailure {
                reportBinding = false
                state = state.copy(status = "清理报告引擎启动失败：${it.message}")
            }
        }
        if (whitelistService == null && !whitelistBinding) {
            runCatching {
                RootService.bind(
                    Intent(this, BaiZeProfileRootService::class.java)
                        .addCategory(RootService.CATEGORY_DAEMON_MODE),
                    whitelistConnection
                )
                whitelistBinding = true
            }.onFailure {
                whitelistBinding = false
                state = state.copy(
                    whitelistConnected = false,
                    actionMessage = "应用白名单 Root 服务暂不可用；路径保护仍可使用"
                )
            }
        }
    }

    private fun selectAction(action: String) {
        if (state.action == action || state.loading) return
        state = state.copy(action = action)
        loadReport(reset = true)
    }

    private fun loadReport(reset: Boolean) {
        val service = reportService ?: return
        if (state.loading) return
        val offset = if (reset) 0 else state.items.size
        val selectedAction = state.action
        state = state.copy(
            loading = true,
            message = if (reset) "正在读取清理报告…" else "正在加载更多结果…"
        )
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val summary = JSONObject(service.getSummary(reportId.ifBlank { "latest" }))
                    if (summary.has("error")) {
                        throw IllegalStateException(summary.optString("message", "清理报告不存在"))
                    }
                    val resolvedId = summary.optString("reportId")
                    val filters = JSONObject().put("action", selectedAction)
                    val page = JSONObject(service.getPage(resolvedId, offset, PAGE_SIZE, filters.toString()))
                    if (page.has("error")) {
                        throw IllegalStateException(page.optString("message", "清理报告分页读取失败"))
                    }
                    LoadedReport(summary, page, parseItems(page.optJSONArray("items")))
                }
                val summary = loaded.summary
                val page = loaded.page
                reportId = summary.optString("reportId", reportId)
                if (reportId.isNotBlank()) {
                    preferences.edit().putString(PREF_LAST_REPORT_ID, reportId).apply()
                }
                state = state.copy(
                    connected = true,
                    loading = false,
                    status = if (summary.optBoolean("live")) "清理事务实时报告" else "已归档清理报告",
                    message = if (page.optInt("total") == 0) "当前筛选下没有结果" else "共 ${page.optInt("total")} 项结果",
                    reportId = reportId,
                    live = summary.optBoolean("live"),
                    authorizedCandidates = summary.optInt("authorizedCandidates").coerceAtLeast(0),
                    processedCandidates = summary.optInt("processedCandidates").coerceAtLeast(0),
                    remainingCandidates = summary.optInt("remainingCandidates").coerceAtLeast(0),
                    cleanedCandidates = summary.optInt("cleanedCandidates").coerceAtLeast(0),
                    changedCandidates = summary.optInt("changedCandidates").coerceAtLeast(0),
                    protectedCandidates = summary.optInt("protectedCandidates").coerceAtLeast(0),
                    partialCandidates = summary.optInt("partialCandidates").coerceAtLeast(0),
                    failedCandidates = summary.optInt("failedCandidates").coerceAtLeast(0),
                    estimatedBytes = summary.optLong("estimatedBytes").coerceAtLeast(0L),
                    deletedBytes = summary.optLong(
                        "actualDeletedBytes",
                        summary.optLong("deletedBytes")
                    ).coerceAtLeast(0L),
                    completionPercent = summary.optInt("completionPercent").coerceIn(0, 100),
                    spaceRecoveryPercent = summary.optInt("spaceRecoveryPercent").coerceAtLeast(0),
                    items = if (reset) loaded.items else state.items + loaded.items,
                    totalItems = page.optInt("total").coerceAtLeast(0),
                    hasMore = page.optBoolean("hasMore")
                )
            } catch (throwable: Throwable) {
                state = state.copy(
                    loading = false,
                    message = "清理报告读取失败：${throwable.message ?: throwable.javaClass.simpleName}"
                )
            }
        }
    }

    private fun refreshProtectedPackages() {
        val service = whitelistService ?: return
        lifecycleScope.launch {
            val remote = withContext(Dispatchers.IO) {
                runCatching { parsePackageWhitelist(service.getWhitelistPackages()) }.getOrDefault(emptySet())
            }
            if (remote.isNotEmpty()) {
                val merged = state.protectedPackages + remote
                preferences.edit().putStringSet(PREF_PACKAGE_WHITELIST, merged).apply()
                state = state.copy(protectedPackages = merged)
            }
        }
    }

    private fun protectPackage(item: CleanResultItem) {
        if (!settingsCanChange()) {
            state = state.copy(actionMessage = "当前事务仍有剩余项目，请先完成或放弃清理计划，再修改白名单")
            return
        }
        val packageName = item.packageName.trim()
        if (!PACKAGE_NAME.matches(packageName)) {
            state = state.copy(actionMessage = "无法从该路径识别有效应用包名")
            return
        }
        if (packageName in state.protectedPackages) {
            state = state.copy(actionMessage = "${item.appName} 已在应用白名单中")
            return
        }
        val service = whitelistService
        if (service == null) {
            state = state.copy(actionMessage = "应用白名单 Root 服务尚未连接")
            bindRootServices()
            return
        }

        state = state.copy(actionRunningId = item.id, actionMessage = "正在保护 ${item.appName}…")
        lifecycleScope.launch {
            try {
                val next = withContext(Dispatchers.IO) {
                    val remote = runCatching { parsePackageWhitelist(service.getWhitelistPackages()) }
                        .getOrDefault(emptySet())
                    (remote + state.protectedPackages + packageName).toSortedSet()
                }
                val result = withContext(Dispatchers.IO) {
                    JSONObject(service.saveWhitelistPackages(JSONArray(next.toList()).toString()))
                }
                if (!result.optBoolean("success")) {
                    throw IllegalStateException(
                        result.optString("message", result.optString("error", "白名单保存失败"))
                    )
                }
                preferences.edit().putStringSet(PREF_PACKAGE_WHITELIST, next).apply()
                state = state.copy(
                    actionRunningId = "",
                    protectedPackages = next,
                    actionMessage = "已保护 ${item.appName}；下一次扫描会跳过该应用相关候选"
                )
            } catch (throwable: Throwable) {
                state = state.copy(
                    actionRunningId = "",
                    actionMessage = "保护应用失败：${throwable.message ?: throwable.javaClass.simpleName}"
                )
            }
        }
    }

    private fun protectPath(item: CleanResultItem) {
        if (!settingsCanChange()) {
            state = state.copy(actionMessage = "当前事务仍有剩余项目，请先完成或放弃清理计划，再修改白名单")
            return
        }
        if (!item.canProtectPath) {
            state = state.copy(actionMessage = "该路径不允许加入自定义保护列表")
            return
        }
        val path = item.path.trimEnd('/').ifBlank { "/" }
        if (!path.startsWith("/") || path.length !in 2..4096 || path.contains('\u0000')) {
            state = state.copy(actionMessage = "路径格式无效，未修改白名单")
            return
        }
        if (path in state.protectedPaths) {
            state = state.copy(actionMessage = "该路径已经受到保护")
            return
        }
        val next = (state.protectedPaths + path).toSortedSet()
        preferences.edit().putStringSet(PREF_PATH_WHITELIST, next).apply()
        state = state.copy(
            protectedPaths = next,
            actionMessage = "已保护此路径及其子项；下一次智能扫描立即生效"
        )
    }

    private fun settingsCanChange(): Boolean = !state.live || state.remainingCandidates <= 0

    private fun parseItems(array: JSONArray?): List<CleanResultItem> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val packageName = item.optString("packageName").trim()
                add(
                    CleanResultItem(
                        id = item.optString("id", item.optString("path")),
                        path = item.optString("path"),
                        packageName = packageName,
                        appName = resolveAppName(packageName),
                        category = item.optString("category", "other"),
                        risk = item.optString("risk", "low"),
                        action = item.optString("action", "changed"),
                        reason = item.optString("reason"),
                        reasonCode = item.optString("reasonCode", "state_changed"),
                        ruleId = item.optString("ruleId", "generic-clean-candidate"),
                        ruleLabel = item.optString("ruleLabel", "清理候选"),
                        ruleSource = item.optString("ruleSource", "扫描快照"),
                        matchReason = item.optString("matchReason", "该路径在扫描时满足对应清理条件。"),
                        protectionHint = item.optString("protectionHint"),
                        canProtectPackage = item.optBoolean("canProtectPackage", packageName.isNotBlank()),
                        canProtectPath = item.optBoolean("canProtectPath", false),
                        estimatedBytes = item.optLong("estimatedBytes").coerceAtLeast(0L),
                        deletedBytes = item.optLong("deletedBytes").coerceAtLeast(0L),
                        deletedFiles = item.optLong("deletedFiles").coerceAtLeast(0L),
                        deletedDirectories = item.optLong("deletedDirectories").coerceAtLeast(0L)
                    )
                )
            }
        }
    }

    private fun resolveAppName(packageName: String): String {
        if (packageName.isBlank()) return "系统与共享存储"
        return runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    android.content.pm.PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(info).toString().ifBlank { packageName }
        }.getOrDefault(packageName)
    }

    private fun parsePackageWhitelist(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            buildSet {
                for (index in 0 until array.length()) {
                    val value = array.optString(index).trim()
                    if (PACKAGE_NAME.matches(value)) add(value)
                }
            }
        }.getOrDefault(emptySet())
    }

    override fun onDestroy() {
        if (reportBinding) runCatching { RootService.unbind(reportConnection) }
        if (whitelistBinding) runCatching { RootService.unbind(whitelistConnection) }
        super.onDestroy()
    }

    private data class LoadedReport(
        val summary: JSONObject,
        val page: JSONObject,
        val items: List<CleanResultItem>
    )

    companion object {
        const val EXTRA_REPORT_ID = "reportId"
        private const val PREF_LAST_REPORT_ID = "last_clean_result_id"
        private const val PREF_PACKAGE_WHITELIST = "package_whitelist"
        private const val PREF_PATH_WHITELIST = "path_whitelist"
        private const val PAGE_SIZE = 60
        private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
    }
}

private data class CleanResultItem(
    val id: String,
    val path: String,
    val packageName: String,
    val appName: String,
    val category: String,
    val risk: String,
    val action: String,
    val reason: String,
    val reasonCode: String,
    val ruleId: String,
    val ruleLabel: String,
    val ruleSource: String,
    val matchReason: String,
    val protectionHint: String,
    val canProtectPackage: Boolean,
    val canProtectPath: Boolean,
    val estimatedBytes: Long,
    val deletedBytes: Long,
    val deletedFiles: Long,
    val deletedDirectories: Long
)

private data class CleanResultUiState(
    val connected: Boolean = false,
    val whitelistConnected: Boolean = false,
    val loading: Boolean = false,
    val status: String = "正在连接清理报告引擎…",
    val message: String = "等待读取最近一次清理报告",
    val actionMessage: String = "",
    val actionRunningId: String = "",
    val reportId: String = "",
    val live: Boolean = false,
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
    val hasMore: Boolean = false,
    val protectedPackages: Set<String> = emptySet(),
    val protectedPaths: Set<String> = emptySet()
)

@Composable
private fun CleanResultScreen(
    state: CleanResultUiState,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    onRefresh: () -> Unit,
    onAction: (String) -> Unit,
    onLoadMore: () -> Unit,
    onProtectPackage: (CleanResultItem) -> Unit,
    onProtectPath: (CleanResultItem) -> Unit
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
    val settingsLocked = state.live && state.remainingCandidates > 0

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
                    Text("EXPLAINABLE CLEAN", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("清理报告", fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text("应用归属 · 规则来源 · 一键保护", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    if (state.actionMessage.isNotBlank()) {
                        Text(
                            state.actionMessage,
                            color = if (state.actionMessage.contains("失败") || state.actionMessage.contains("无效")) {
                                MaterialTheme.colorScheme.error
                            } else MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp
                        )
                    }
                    if (settingsLocked) {
                        Text(
                            "当前事务还剩 ${state.remainingCandidates} 项。为保证快照一致性，应用和路径保护将在计划完成后开放。",
                            color = MaterialTheme.colorScheme.tertiary,
                            fontSize = 12.sp
                        )
                    }
                    if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (!state.connected) {
                        OutlinedButton(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) {
                            Text("重新连接 Root 引擎")
                        }
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
            CleanResultItemCard(
                item = item,
                settingsLocked = settingsLocked,
                whitelistConnected = state.whitelistConnected,
                actionRunning = state.actionRunningId == item.id,
                packageProtected = item.packageName in state.protectedPackages,
                pathProtected = item.path.trimEnd('/') in state.protectedPaths,
                onProtectPackage = { onProtectPackage(item) },
                onProtectPath = { onProtectPath(item) }
            )
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
                    "规则解释来自已经保存的清理结果，不会重新扫描。保护应用会同步写入 Root 清理引擎；保护路径会写入智能清理设置。两者都只影响下一次扫描，不会恢复已经删除的文件。",
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
private fun CleanResultItemCard(
    item: CleanResultItem,
    settingsLocked: Boolean,
    whitelistConnected: Boolean,
    actionRunning: Boolean,
    packageProtected: Boolean,
    pathProtected: Boolean,
    onProtectPackage: () -> Unit,
    onProtectPath: () -> Unit
) {
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
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    actionLabel,
                    color = if (item.action == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Text("$categoryLabel · ${riskLabel(item.risk)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }

            Text(item.appName, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            if (item.packageName.isNotBlank()) {
                Text(item.packageName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Text(item.path, fontWeight = FontWeight.SemiBold, maxLines = 4, overflow = TextOverflow.Ellipsis)

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.ruleLabel, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("${item.ruleSource} · ${item.ruleId}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    Text(item.matchReason, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }

            Text("处理原因：${item.reason}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text("原因代码：${item.reasonCode}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            Text(
                "预计 ${Formatter.formatFileSize(context, item.estimatedBytes)} · 实际 ${Formatter.formatFileSize(context, item.deletedBytes)} · 文件 ${item.deletedFiles} · 目录 ${item.deletedDirectories}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
            if (item.protectionHint.isNotBlank()) {
                Text(item.protectionHint, color = MaterialTheme.colorScheme.tertiary, fontSize = 11.sp)
            }

            if (item.canProtectPackage || item.canProtectPath) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.canProtectPackage) {
                        OutlinedButton(
                            onClick = onProtectPackage,
                            enabled = !settingsLocked && whitelistConnected && !actionRunning && !packageProtected,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                when {
                                    packageProtected -> "应用已保护"
                                    actionRunning -> "正在保护…"
                                    else -> "保护整个应用"
                                },
                                maxLines = 1
                            )
                        }
                    }
                    if (item.canProtectPath) {
                        OutlinedButton(
                            onClick = onProtectPath,
                            enabled = !settingsLocked && !actionRunning && !pathProtected,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (pathProtected) "路径已保护" else "保护此路径", maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private fun riskLabel(value: String): String = when (value) {
    "medium" -> "中风险"
    "high" -> "高风险"
    "critical" -> "关键风险"
    else -> "低风险"
}
