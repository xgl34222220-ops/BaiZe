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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

class RuleQualityActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private var service: IProfileRootService? = null
    private var bound = false
    private var state by mutableStateOf(RuleQualityUiState())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IProfileRootService.Stub.asInterface(binder)
            bound = true
            state = state.copy(connected = true, message = "Root 规则质量服务已连接")
            load()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            state = state.copy(connected = false, loading = false, message = "Root 规则质量服务已断开")
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
                    RuleQualityScreen(
                        state = state,
                        miuix = appearance.uiStyle == UiStyle.MIUIX,
                        onBack = ::finish,
                        onRefresh = ::load,
                        onReview = ::submitReview
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
        state = state.copy(message = "正在连接 Root 规则质量服务…")
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
            bound = true
        }.onFailure {
            bound = false
            state = state.copy(message = "Root 服务启动失败：${it.message.orEmpty()}")
        }
    }

    private fun load() {
        val root = service ?: return
        if (state.loading) return
        state = state.copy(loading = true, message = "正在分析最近 45 天规则质量…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    JSONObject(root.getAuditTimelinePage(0, 100)).optJSONObject("ruleQuality") ?: JSONObject()
                }
            }
            result.onSuccess { json ->
                val report = parseReport(json)
                state = state.copy(
                    connected = true,
                    loading = false,
                    report = report,
                    message = if (report.available) report.summary else "暂无足够的规则审计数据"
                )
            }.onFailure {
                state = state.copy(loading = false, message = "读取规则质量失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun submitReview(item: RuleQualityItem, action: String, note: String) {
        val root = service ?: return
        if (state.loading) return
        state = state.copy(loading = true, message = "正在保存 ${item.category} 的审核记录…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val update = JSONObject(root.updateRuleQualityReview(item.key, action, note))
                    if (!update.optBoolean("success", false)) {
                        error(update.optString("message").ifBlank { update.optString("error", "审核记录保存失败") })
                    }
                    val reportJson = JSONObject(root.getAuditTimelinePage(0, 100)).optJSONObject("ruleQuality") ?: JSONObject()
                    update to reportJson
                }
            }
            result.onSuccess { (update, reportJson) ->
                val report = parseReport(reportJson)
                state = state.copy(
                    loading = false,
                    report = report,
                    message = update.optString("message", "审核记录已保存")
                )
            }.onFailure {
                state = state.copy(loading = false, message = "保存审核记录失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun parseReport(json: JSONObject): RuleQualityReport {
        val queue = buildList {
            val array = json.optJSONArray("reviewQueue")
            if (array != null) for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    RuleQualityItem(
                        key = item.optString("key"),
                        category = item.optString("category", "未命名分类"),
                        risk = item.optString("risk"),
                        type = item.optString("type"),
                        severity = item.optString("severity", "medium"),
                        recommendation = item.optString("recommendation", "observe"),
                        message = item.optString("message"),
                        events = item.optInt("events").coerceAtLeast(0),
                        observations = item.optInt("observations").coerceAtLeast(0),
                        processed = item.optInt("processed").coerceAtLeast(0),
                        protected = item.optInt("protected").coerceAtLeast(0),
                        failures = item.optInt("failures").coerceAtLeast(0),
                        protectionRate = item.optInt("protectionRate").coerceIn(0, 100),
                        failureRate = item.optInt("failureRate").coerceIn(0, 100),
                        bytes = item.optLong("bytes").coerceAtLeast(0L),
                        averageBytes = item.optLong("averageBytes").coerceAtLeast(0L),
                        reviewState = item.optString("reviewState", "pending"),
                        reviewNote = item.optString("reviewNote"),
                        reviewedAt = item.optLong("reviewedAt").coerceAtLeast(0L),
                        newEventsSinceReview = item.optInt("newEventsSinceReview").coerceAtLeast(0),
                        newObservationsSinceReview = item.optInt("newObservationsSinceReview").coerceAtLeast(0),
                        reopened = item.optBoolean("reopened", false),
                        reopenedAt = item.optLong("reopenedAt").coerceAtLeast(0L),
                        reopenReason = item.optString("reopenReason"),
                        previousReviewState = item.optString("previousReviewState")
                    )
                )
            }
        }
        return RuleQualityReport(
            available = json.optBoolean("available", false),
            summary = json.optString("summary", "暂无足够的规则审计数据"),
            lookbackDays = json.optInt("lookbackDays", 45),
            eventSampleCount = json.optInt("eventSampleCount").coerceAtLeast(0),
            ruleCount = json.optInt("ruleCount").coerceAtLeast(0),
            needsReview = json.optInt("needsReview").coerceAtLeast(0),
            pendingCount = json.optInt("pendingCount").coerceAtLeast(0),
            reopenedCount = json.optInt("reopenedCount").coerceAtLeast(0),
            observingCount = json.optInt("observingCount").coerceAtLeast(0),
            keptCount = json.optInt("keptCount").coerceAtLeast(0),
            ignoredCount = json.optInt("ignoredCount").coerceAtLeast(0),
            reviewedCount = json.optInt("reviewedCount").coerceAtLeast(0),
            highPriorityCount = json.optInt("highPriorityCount").coerceAtLeast(0),
            healthyCount = json.optInt("healthyCount").coerceAtLeast(0),
            insufficientCount = json.optInt("insufficientCount").coerceAtLeast(0),
            reviewQueue = queue,
            readOnly = json.optBoolean("readOnly", true),
            reviewStateWritable = json.optBoolean("reviewStateWritable", false)
        )
    }
}

private data class RuleQualityUiState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val report: RuleQualityReport = RuleQualityReport(),
    val message: String = "等待连接 Root 规则质量服务"
)

private data class RuleQualityReport(
    val available: Boolean = false,
    val summary: String = "暂无足够的规则审计数据",
    val lookbackDays: Int = 45,
    val eventSampleCount: Int = 0,
    val ruleCount: Int = 0,
    val needsReview: Int = 0,
    val pendingCount: Int = 0,
    val reopenedCount: Int = 0,
    val observingCount: Int = 0,
    val keptCount: Int = 0,
    val ignoredCount: Int = 0,
    val reviewedCount: Int = 0,
    val highPriorityCount: Int = 0,
    val healthyCount: Int = 0,
    val insufficientCount: Int = 0,
    val reviewQueue: List<RuleQualityItem> = emptyList(),
    val readOnly: Boolean = true,
    val reviewStateWritable: Boolean = false
)

private data class RuleQualityItem(
    val key: String,
    val category: String,
    val risk: String,
    val type: String,
    val severity: String,
    val recommendation: String,
    val message: String,
    val events: Int,
    val observations: Int,
    val processed: Int,
    val protected: Int,
    val failures: Int,
    val protectionRate: Int,
    val failureRate: Int,
    val bytes: Long,
    val averageBytes: Long,
    val reviewState: String,
    val reviewNote: String,
    val reviewedAt: Long,
    val newEventsSinceReview: Int,
    val newObservationsSinceReview: Int,
    val reopened: Boolean,
    val reopenedAt: Long,
    val reopenReason: String,
    val previousReviewState: String
)

@Composable
private fun RuleQualityScreen(
    state: RuleQualityUiState,
    miuix: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onReview: (RuleQualityItem, String, String) -> Unit
) {
    var stateFilter by remember { mutableStateOf("pending") }
    var typeFilter by remember { mutableStateOf("all") }
    val horizontal = if (miuix) 18.dp else 20.dp
    val shape = if (miuix) RoundedCornerShape(27.dp) else MaterialTheme.shapes.extraLarge
    val filtered = state.report.reviewQueue.filter { item ->
        val stateMatches = when (stateFilter) {
            "all" -> true
            "reopened" -> item.reopened
            else -> item.reviewState == stateFilter
        }
        val typeMatches = when (typeFilter) {
            "high" -> item.severity == "high"
            "protected" -> item.type == "frequently_protected"
            "failure" -> item.type == "high_failure"
            "zero" -> item.type == "zero_hit"
            "low" -> item.type == "low_value"
            else -> true
        }
        stateMatches && typeMatches
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item { RuleQualityHeader(state.message, state.loading, onBack, onRefresh) }
        item { RuleQualitySummary(state.report, horizontal, shape, state.loading) }
        item { ReadOnlyRuleCard(horizontal, shape) }
        item {
            FilterRow(
                horizontal = horizontal,
                selected = stateFilter,
                values = listOf(
                    "reopened" to "重新打开 ${state.report.reopenedCount}",
                    "pending" to "待审核 ${state.report.pendingCount}",
                    "observing" to "观察中 ${state.report.observingCount}",
                    "kept" to "已保留 ${state.report.keptCount}",
                    "ignored" to "已忽略 ${state.report.ignoredCount}",
                    "all" to "全部"
                ),
                onSelected = { stateFilter = it }
            )
        }
        item {
            FilterRow(
                horizontal = horizontal,
                selected = typeFilter,
                values = listOf(
                    "all" to "全部类型",
                    "high" to "高优先级",
                    "failure" to "高失败",
                    "protected" to "频繁保护",
                    "zero" to "零命中",
                    "low" to "低收益"
                ),
                onSelected = { typeFilter = it }
            )
        }
        if (filtered.isEmpty() && !state.loading) {
            item { RuleQualityEmpty(horizontal, shape, state.report.available, stateFilter, typeFilter) }
        } else {
            items(filtered, key = { it.key }) { item ->
                RuleQualityCard(
                    item = item,
                    horizontal = horizontal,
                    shape = shape,
                    saving = state.loading,
                    reviewEnabled = state.report.reviewStateWritable,
                    onReview = onReview
                )
            }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
private fun FilterRow(
    horizontal: androidx.compose.ui.unit.Dp,
    selected: String,
    values: List<Pair<String, String>>,
    onSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = horizontal),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        values.forEach { (id, label) ->
            FilterChip(selected = selected == id, onClick = { onSelected(id) }, label = { Text(label) })
        }
    }
}

@Composable
private fun RuleQualityHeader(
    message: String,
    loading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "返回") }
        Spacer(Modifier.width(5.dp))
        Column(Modifier.weight(1f)) {
            Text("规则质量中心", fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onRefresh, enabled = !loading) {
            Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
        }
    }
}

@Composable
private fun RuleQualitySummary(
    report: RuleQualityReport,
    horizontal: androidx.compose.ui.unit.Dp,
    shape: androidx.compose.ui.graphics.Shape,
    loading: Boolean
) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(50.dp),
                    shape = RoundedCornerShape(17.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = .13f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Rule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text("${report.pendingCount} 项待审核", fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text(report.summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                if (loading) CircularProgressIndicator(Modifier.size(24.dp))
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityMetric("重新打开", report.reopenedCount.toString(), Modifier.weight(1f))
                QualityMetric("高优先级", report.highPriorityCount.toString(), Modifier.weight(1f))
                QualityMetric("观察中", report.observingCount.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "最近 ${report.lookbackDays} 天 ${report.eventSampleCount} 条审计事件 · ${report.healthyCount} 项正常 · ${report.insufficientCount} 项样本不足",
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun ReadOnlyRuleCard(horizontal: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("只读人工审核", fontWeight = FontWeight.Bold)
                Text(
                    "仅保存审核状态和备注；证据明显恶化时只自动重新打开审核状态，不会停用规则、删除文件、修改清理策略或改变任何定时周期。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

@Composable
private fun RuleQualityCard(
    item: RuleQualityItem,
    horizontal: androidx.compose.ui.unit.Dp,
    shape: androidx.compose.ui.graphics.Shape,
    saving: Boolean,
    reviewEnabled: Boolean,
    onReview: (RuleQualityItem, String, String) -> Unit
) {
    val visual = qualityVisual(item)
    val context = androidx.compose.ui.platform.LocalContext.current
    val stateTint = if (item.reopened) MaterialTheme.colorScheme.error else reviewStateColor(item.reviewState)
    var pendingAction by remember(item.key, item.reviewState) { mutableStateOf<String?>(null) }
    var note by remember(item.key, item.reviewNote) { mutableStateOf(item.reviewNote) }

    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(15.dp),
                    color = visual.tint.copy(alpha = .13f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(visual.icon, contentDescription = null, tint = visual.tint)
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.category, fontWeight = FontWeight.Black, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(typeLabel(item.type), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                }
                Surface(shape = CircleShape, color = stateTint.copy(alpha = .13f)) {
                    Text(
                        if (item.reopened) "重新审核" else reviewStateLabel(item.reviewState),
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = stateTint,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(item.message, fontSize = 12.sp, lineHeight = 18.sp)
            Text(
                "系统建议：${recommendationLabel(item.recommendation)}",
                color = visual.tint,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
            Spacer(Modifier.height(11.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityMetric("任务", item.events.toString(), Modifier.weight(1f))
                QualityMetric("异常率", "${item.failureRate}%", Modifier.weight(1f))
                QualityMetric("保护率", "${item.protectionRate}%", Modifier.weight(1f))
            }
            Spacer(Modifier.height(9.dp))
            Text(
                "处理 ${item.processed} · 保护 ${item.protected} · 累计 ${Formatter.formatFileSize(context, item.bytes)}" +
                    if (item.averageBytes > 0L) " · 平均 ${Formatter.formatFileSize(context, item.averageBytes)}" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
            if (item.risk.isNotBlank()) {
                Text("风险级别：${riskLabel(item.risk)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
            }
            if (item.reopened) {
                Spacer(Modifier.height(9.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Column(Modifier.padding(11.dp)) {
                        Text("审核已自动重新打开", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Text(item.reopenReason, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 10.sp)
                        Text(
                            "原状态：${reviewStateLabel(item.previousReviewState)} · 审核后新增 ${item.newEventsSinceReview} 次任务 / ${item.newObservationsSinceReview} 条记录",
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = .75f),
                            fontSize = 9.sp
                        )
                        if (item.reopenedAt > 0L) {
                            Text("重新打开时间：${formatReviewTime(item.reopenedAt)}", color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = .75f), fontSize = 9.sp)
                        }
                    }
                }
            }
            if (item.reviewNote.isNotBlank() || item.reviewedAt > 0L) {
                Spacer(Modifier.height(9.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(Modifier.padding(11.dp)) {
                        if (item.reviewNote.isNotBlank()) {
                            Text("审核备注：${item.reviewNote}", fontSize = 10.sp)
                        }
                        if (item.reviewedAt > 0L) {
                            Text(
                                "审核时间：${formatReviewTime(item.reviewedAt)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(enabled = reviewEnabled && !saving, onClick = { pendingAction = "keep" }) { Text("保留规则") }
                TextButton(enabled = reviewEnabled && !saving, onClick = { pendingAction = "observe" }) { Text("继续观察") }
                TextButton(enabled = reviewEnabled && !saving, onClick = { pendingAction = "ignore" }) { Text("忽略提醒") }
                if (item.reviewState != "pending") {
                    TextButton(enabled = reviewEnabled && !saving, onClick = { pendingAction = "reset" }) { Text("重置审核") }
                }
            }
        }
    }

    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(reviewActionTitle(action)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${item.category} · ${typeLabel(item.type)}")
                    if (action != "reset") {
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it.take(200) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("审核备注（可选）") },
                            maxLines = 4
                        )
                        Text("${note.length}/200", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
                    } else {
                        Text("重置后该项目会重新回到待审核列表，原备注会一并清除。")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingAction = null
                    onReview(item, action, if (action == "reset") "" else note)
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun RuleQualityEmpty(
    horizontal: androidx.compose.ui.unit.Dp,
    shape: androidx.compose.ui.graphics.Shape,
    available: Boolean,
    stateFilter: String,
    typeFilter: String
) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(42.dp), tint = BaiZeTokens.colors.success)
            Spacer(Modifier.height(10.dp))
            Text(if (!available) "暂无足够样本" else "当前筛选没有项目", fontWeight = FontWeight.Bold)
            Text(
                if (!available) {
                    "继续正常扫描和清理后，系统会基于脱敏审计记录形成规则质量报告。"
                } else {
                    "状态 ${reviewStateLabel(stateFilter)}、类型 ${typeFilterLabel(typeFilter)} 下没有规则。"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun QualityMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .58f)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
            Text(value, fontWeight = FontWeight.Black, fontSize = 15.sp)
        }
    }
}

private data class QualityVisual(val icon: ImageVector, val tint: androidx.compose.ui.graphics.Color)

@Composable
private fun qualityVisual(item: RuleQualityItem): QualityVisual = when (item.type) {
    "high_failure" -> QualityVisual(Icons.Rounded.ErrorOutline, MaterialTheme.colorScheme.error)
    "frequently_protected" -> QualityVisual(Icons.Rounded.Security, MaterialTheme.colorScheme.tertiary)
    "zero_hit" -> QualityVisual(Icons.Rounded.Block, MaterialTheme.colorScheme.secondary)
    else -> QualityVisual(Icons.Rounded.Visibility, MaterialTheme.colorScheme.primary)
}

@Composable
private fun reviewStateColor(value: String): androidx.compose.ui.graphics.Color = when (value) {
    "kept" -> BaiZeTokens.colors.success
    "observing" -> MaterialTheme.colorScheme.tertiary
    "ignored" -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.error
}

private fun typeLabel(type: String): String = when (type) {
    "high_failure" -> "高失败规则"
    "frequently_protected" -> "频繁受保护"
    "zero_hit" -> "长期零命中"
    "low_value" -> "长期低收益"
    else -> "规则观察"
}

private fun typeFilterLabel(type: String): String = when (type) {
    "high" -> "高优先级"
    "failure" -> "高失败"
    "protected" -> "频繁保护"
    "zero" -> "零命中"
    "low" -> "低收益"
    else -> "全部类型"
}

private fun recommendationLabel(value: String): String = when (value) {
    "consider_disable" -> "建议评估停用"
    "narrow_scope" -> "建议缩小范围"
    "observe" -> "继续观察"
    else -> "建议保留"
}

private fun reviewStateLabel(value: String): String = when (value) {
    "reopened" -> "重新打开"
    "observing" -> "观察中"
    "kept" -> "已保留"
    "ignored" -> "已忽略"
    "all" -> "全部"
    else -> "待审核"
}

private fun reviewActionTitle(action: String): String = when (action) {
    "keep" -> "保留规则"
    "observe" -> "继续观察"
    "ignore" -> "忽略提醒"
    else -> "重置审核"
}

private fun riskLabel(value: String): String = when (value) {
    "critical" -> "关键"
    "high" -> "高"
    "medium" -> "中"
    "low" -> "低"
    else -> value
}

private fun formatReviewTime(epoch: Long): String = runCatching {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epoch))
}.getOrDefault("-")
