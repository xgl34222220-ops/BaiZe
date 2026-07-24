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
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
                        onRefresh = ::load
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
                        averageBytes = item.optLong("averageBytes").coerceAtLeast(0L)
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
            highPriorityCount = json.optInt("highPriorityCount").coerceAtLeast(0),
            healthyCount = json.optInt("healthyCount").coerceAtLeast(0),
            insufficientCount = json.optInt("insufficientCount").coerceAtLeast(0),
            reviewQueue = queue,
            readOnly = json.optBoolean("readOnly", true)
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
    val highPriorityCount: Int = 0,
    val healthyCount: Int = 0,
    val insufficientCount: Int = 0,
    val reviewQueue: List<RuleQualityItem> = emptyList(),
    val readOnly: Boolean = true
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
    val averageBytes: Long
)

@Composable
private fun RuleQualityScreen(
    state: RuleQualityUiState,
    miuix: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    var filter by remember { mutableStateOf("all") }
    val horizontal = if (miuix) 18.dp else 20.dp
    val shape = if (miuix) RoundedCornerShape(27.dp) else MaterialTheme.shapes.extraLarge
    val filtered = state.report.reviewQueue.filter { item ->
        when (filter) {
            "high" -> item.severity == "high"
            "protected" -> item.type == "frequently_protected"
            "failure" -> item.type == "high_failure"
            "zero" -> item.type == "zero_hit"
            "low" -> item.type == "low_value"
            else -> true
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item {
            RuleQualityHeader(state.message, state.loading, onBack, onRefresh)
        }
        item {
            RuleQualitySummary(state.report, horizontal, shape, state.loading)
        }
        item {
            ReadOnlyRuleCard(horizontal, shape)
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = horizontal),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "all" to "全部 ${state.report.needsReview}",
                    "high" to "高优先级",
                    "failure" to "高失败",
                    "protected" to "频繁保护",
                    "zero" to "零命中",
                    "low" to "低收益"
                ).forEach { (id, label) ->
                    FilterChip(selected = filter == id, onClick = { filter = id }, label = { Text(label) })
                }
            }
        }
        if (filtered.isEmpty() && !state.loading) {
            item { RuleQualityEmpty(horizontal, shape, state.report.available, filter) }
        } else {
            items(filtered, key = { it.key }) { item ->
                RuleQualityCard(item, horizontal, shape)
            }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
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
                    Text("${report.needsReview} 项待审核", fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text(report.summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                if (loading) CircularProgressIndicator(Modifier.size(24.dp))
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityMetric("规则分类", report.ruleCount.toString(), Modifier.weight(1f))
                QualityMetric("高优先级", report.highPriorityCount.toString(), Modifier.weight(1f))
                QualityMetric("表现正常", report.healthyCount.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "基于最近 ${report.lookbackDays} 天 ${report.eventSampleCount} 条审计事件；${report.insufficientCount} 项因样本不足暂不评价。",
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
                    "这里只汇总建议，不会自动停用规则、删除文件、修改清理策略或改变任何定时周期。",
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
    shape: androidx.compose.ui.graphics.Shape
) {
    val visual = qualityVisual(item)
    val context = androidx.compose.ui.platform.LocalContext.current
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
                Surface(shape = CircleShape, color = visual.tint.copy(alpha = .12f)) {
                    Text(
                        recommendationLabel(item.recommendation),
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = visual.tint,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(item.message, fontSize = 12.sp, lineHeight = 18.sp)
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
        }
    }
}

@Composable
private fun RuleQualityEmpty(
    horizontal: androidx.compose.ui.unit.Dp,
    shape: androidx.compose.ui.graphics.Shape,
    available: Boolean,
    filter: String
) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Security, contentDescription = null, modifier = Modifier.size(42.dp), tint = BaiZeTokens.colors.success)
            Spacer(Modifier.height(10.dp))
            Text(if (!available) "暂无足够样本" else if (filter == "all") "当前没有待审核规则" else "该筛选没有项目", fontWeight = FontWeight.Bold)
            Text(
                if (!available) "继续正常扫描和清理后，系统会基于脱敏审计记录形成规则质量报告。" else "当前规则表现正常，或尚未达到人工审核阈值。",
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

private fun typeLabel(type: String): String = when (type) {
    "high_failure" -> "高失败规则"
    "frequently_protected" -> "频繁受保护"
    "zero_hit" -> "长期零命中"
    "low_value" -> "长期低收益"
    else -> "规则观察"
}

private fun recommendationLabel(value: String): String = when (value) {
    "consider_disable" -> "建议评估停用"
    "narrow_scope" -> "建议缩小范围"
    "observe" -> "继续观察"
    else -> "建议保留"
}

private fun riskLabel(value: String): String = when (value) {
    "critical" -> "关键"
    "high" -> "高"
    "medium" -> "中"
    "low" -> "低"
    else -> value
}
