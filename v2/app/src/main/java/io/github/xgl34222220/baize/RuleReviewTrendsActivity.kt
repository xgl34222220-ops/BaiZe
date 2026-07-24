package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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

class RuleReviewTrendsActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private var service: IProfileRootService? = null
    private var bound = false
    private var state by mutableStateOf(RuleReviewTrendsUiState())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IProfileRootService.Stub.asInterface(binder)
            bound = true
            state = state.copy(connected = true, message = "Root 审核趋势服务已连接")
            load()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            state = state.copy(connected = false, loading = false, message = "Root 审核趋势服务已断开")
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
                    RuleReviewTrendsScreen(
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
        state = state.copy(message = "正在连接 Root 审核趋势服务…")
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
        state = state.copy(loading = true, message = "正在分析最近 90 天审核历史…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    JSONObject(root.getAuditTimelinePage(0, 100)).optJSONObject("ruleReviewTrends") ?: JSONObject()
                }
            }
            result.onSuccess { json ->
                val report = parseReport(json)
                state = state.copy(
                    connected = true,
                    loading = false,
                    report = report,
                    message = if (report.available) report.summary else "暂无审核历史"
                )
            }.onFailure {
                state = state.copy(loading = false, message = "读取审核趋势失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun parseReport(json: JSONObject): RuleReviewTrendReport {
        val reasons = buildList {
            val array = json.optJSONArray("reasonBreakdown")
            if (array != null) for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    RuleReviewReason(
                        type = item.optString("type"),
                        label = item.optString("label", "其他原因"),
                        count = item.optInt("count").coerceAtLeast(0),
                        percent = item.optInt("percent").coerceIn(0, 100)
                    )
                )
            }
        }
        val weekly = buildList {
            val array = json.optJSONArray("weekly")
            if (array != null) for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    RuleReviewWeek(
                        label = item.optString("label"),
                        reopens = item.optInt("reopens").coerceAtLeast(0),
                        reviews = item.optInt("reviews").coerceAtLeast(0),
                        resolved = item.optInt("resolved").coerceAtLeast(0)
                    )
                )
            }
        }
        val items = buildList {
            val array = json.optJSONArray("items")
            if (array != null) for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    RuleReviewTrendItem(
                        key = item.optString("key"),
                        category = item.optString("category", "未命名分类"),
                        reopenCount = item.optInt("reopenCount").coerceAtLeast(0),
                        manualReviewCount = item.optInt("manualReviewCount").coerceAtLeast(0),
                        resolvedCount = item.optInt("resolvedCount").coerceAtLeast(0),
                        activeReopened = item.optBoolean("activeReopened", false),
                        repeated = item.optBoolean("repeated", false),
                        lastState = item.optString("lastState", "pending"),
                        lastActionAt = item.optLong("lastActionAt").coerceAtLeast(0L),
                        lastReopenAt = item.optLong("lastReopenAt").coerceAtLeast(0L),
                        lastReason = item.optString("lastReason"),
                        averageResolutionMs = item.optLong("averageResolutionMs").coerceAtLeast(0L)
                    )
                )
            }
        }
        return RuleReviewTrendReport(
            available = json.optBoolean("available", false),
            summary = json.optString("summary", "暂无审核历史"),
            lookbackDays = json.optInt("lookbackDays", 90),
            eventSampleCount = json.optInt("eventSampleCount").coerceAtLeast(0),
            manualReviewCount = json.optInt("manualReviewCount").coerceAtLeast(0),
            reopenCount = json.optInt("reopenCount").coerceAtLeast(0),
            resolvedReopenCount = json.optInt("resolvedReopenCount").coerceAtLeast(0),
            activeReopenCount = json.optInt("activeReopenCount").coerceAtLeast(0),
            repeatedlyReopenedCount = json.optInt("repeatedlyReopenedCount").coerceAtLeast(0),
            resolutionRate = json.optInt("resolutionRate").coerceIn(0, 100),
            averageResolutionMs = json.optLong("averageResolutionMs").coerceAtLeast(0L),
            medianResolutionMs = json.optLong("medianResolutionMs").coerceAtLeast(0L),
            trendWindowDays = json.optInt("trendWindowDays", 14),
            recentReopenCount = json.optInt("recentReopenCount").coerceAtLeast(0),
            previousReopenCount = json.optInt("previousReopenCount").coerceAtLeast(0),
            trend = json.optString("trend", "flat"),
            reasons = reasons,
            weekly = weekly,
            items = items,
            readOnly = json.optBoolean("readOnly", true)
        )
    }
}

private data class RuleReviewTrendsUiState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val report: RuleReviewTrendReport = RuleReviewTrendReport(),
    val message: String = "等待连接 Root 审核趋势服务"
)

private data class RuleReviewTrendReport(
    val available: Boolean = false,
    val summary: String = "暂无审核历史",
    val lookbackDays: Int = 90,
    val eventSampleCount: Int = 0,
    val manualReviewCount: Int = 0,
    val reopenCount: Int = 0,
    val resolvedReopenCount: Int = 0,
    val activeReopenCount: Int = 0,
    val repeatedlyReopenedCount: Int = 0,
    val resolutionRate: Int = 0,
    val averageResolutionMs: Long = 0L,
    val medianResolutionMs: Long = 0L,
    val trendWindowDays: Int = 14,
    val recentReopenCount: Int = 0,
    val previousReopenCount: Int = 0,
    val trend: String = "flat",
    val reasons: List<RuleReviewReason> = emptyList(),
    val weekly: List<RuleReviewWeek> = emptyList(),
    val items: List<RuleReviewTrendItem> = emptyList(),
    val readOnly: Boolean = true
)

private data class RuleReviewReason(val type: String, val label: String, val count: Int, val percent: Int)
private data class RuleReviewWeek(val label: String, val reopens: Int, val reviews: Int, val resolved: Int)
private data class RuleReviewTrendItem(
    val key: String,
    val category: String,
    val reopenCount: Int,
    val manualReviewCount: Int,
    val resolvedCount: Int,
    val activeReopened: Boolean,
    val repeated: Boolean,
    val lastState: String,
    val lastActionAt: Long,
    val lastReopenAt: Long,
    val lastReason: String,
    val averageResolutionMs: Long
)

@Composable
private fun RuleReviewTrendsScreen(
    state: RuleReviewTrendsUiState,
    miuix: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    var filter by remember { mutableStateOf("all") }
    val horizontal = if (miuix) 18.dp else 20.dp
    val shape = if (miuix) RoundedCornerShape(27.dp) else MaterialTheme.shapes.extraLarge
    val filtered = state.report.items.filter { item ->
        when (filter) {
            "repeated" -> item.repeated
            "active" -> item.activeReopened
            "resolved" -> item.resolvedCount > 0 && !item.activeReopened
            else -> true
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item { RuleReviewTrendHeader(state.message, state.loading, onBack, onRefresh) }
        item { RuleReviewTrendSummary(state.report, horizontal, shape, state.loading) }
        item { RuleReviewTrendSafetyCard(horizontal, shape) }
        item { RuleReviewTrendComparison(state.report, horizontal, shape) }
        if (state.report.reasons.isNotEmpty()) item { RuleReviewReasonCard(state.report.reasons, horizontal, shape) }
        if (state.report.weekly.isNotEmpty()) item { RuleReviewWeeklyCard(state.report.weekly, horizontal, shape) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = horizontal),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "all" to "全部 ${state.report.items.size}",
                    "repeated" to "反复重开 ${state.report.repeatedlyReopenedCount}",
                    "active" to "等待处理 ${state.report.activeReopenCount}",
                    "resolved" to "已完成"
                ).forEach { (id, label) ->
                    FilterChip(selected = filter == id, onClick = { filter = id }, label = { Text(label) })
                }
            }
        }
        if (filtered.isEmpty() && !state.loading) {
            item { RuleReviewTrendEmpty(state.report.available, filter, horizontal, shape) }
        } else {
            items(filtered, key = { it.key }) { item ->
                RuleReviewTrendItemCard(item, horizontal, shape)
            }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
private fun RuleReviewTrendHeader(message: String, loading: Boolean, onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "返回") }
        Spacer(Modifier.width(5.dp))
        Column(Modifier.weight(1f)) {
            Text("审核历史与趋势", fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Rounded.Refresh, contentDescription = "刷新") }
    }
}

@Composable
private fun RuleReviewTrendSummary(
    report: RuleReviewTrendReport,
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
                Surface(Modifier.size(50.dp), RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .13f)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Timeline, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text("${report.reopenCount} 次重新打开", fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text(report.summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                if (loading) CircularProgressIndicator(Modifier.size(24.dp))
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrendMetric("反复重开", report.repeatedlyReopenedCount.toString(), Modifier.weight(1f))
                TrendMetric("等待处理", report.activeReopenCount.toString(), Modifier.weight(1f))
                TrendMetric("完成率", "${report.resolutionRate}%", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "最近 ${report.lookbackDays} 天 ${report.eventSampleCount} 条审核事件 · 人工处理 ${report.manualReviewCount} 次",
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun RuleReviewTrendSafetyCard(horizontal: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Security, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("只读趋势分析", fontWeight = FontWeight.Bold)
                Text(
                    "仅统计脱敏审核事件和当前审核状态，不会自动处理审核、停用规则、删除文件、切换策略或改变任何定时周期。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

@Composable
private fun RuleReviewTrendComparison(report: RuleReviewTrendReport, horizontal: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Replay, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(9.dp))
                Text("处理效率", fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrendMetric("近 ${report.trendWindowDays} 天", report.recentReopenCount.toString(), Modifier.weight(1f))
                TrendMetric("此前 ${report.trendWindowDays} 天", report.previousReopenCount.toString(), Modifier.weight(1f))
                TrendMetric("趋势", trendLabel(report.trend), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "平均处理 ${formatDuration(report.averageResolutionMs)} · 中位处理 ${formatDuration(report.medianResolutionMs)} · 已完成 ${report.resolvedReopenCount} 项",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun RuleReviewReasonCard(reasons: List<RuleReviewReason>, horizontal: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text("主要恶化原因", fontWeight = FontWeight.Black, fontSize = 16.sp)
            reasons.forEach { reason ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(reason.label, modifier = Modifier.weight(1f), fontSize = 11.sp)
                        Text("${reason.count} 次 · ${reason.percent}%", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                    LinearProgressIndicator(
                        progress = { reason.percent / 100f },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleReviewWeeklyCard(weeks: List<RuleReviewWeek>, horizontal: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape) {
    val max = weeks.maxOfOrNull { maxOf(it.reopens, it.reviews, 1) } ?: 1
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("近八周审核趋势", fontWeight = FontWeight.Black, fontSize = 16.sp)
            weeks.forEach { week ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(week.label, modifier = Modifier.width(42.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        LinearProgressIndicator(
                            progress = { week.reopens.toFloat() / max },
                            modifier = Modifier.fillMaxWidth().height(5.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                        Text("重开 ${week.reopens} · 审核 ${week.reviews} · 完成 ${week.resolved}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleReviewTrendItemCard(item: RuleReviewTrendItem, horizontal: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape) {
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
                    color = if (item.activeReopened) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (item.activeReopened) Icons.Rounded.Replay else Icons.Rounded.History,
                            contentDescription = null,
                            tint = if (item.activeReopened) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.category, fontWeight = FontWeight.Black, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (item.activeReopened) "重新打开，等待人工审核" else "当前状态：${reviewStateLabel(item.lastState)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
                if (item.repeated || item.activeReopened) {
                    Surface(
                        shape = CircleShape,
                        color = if (item.activeReopened) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            if (item.activeReopened) "待处理" else "反复重开",
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(Modifier.height(11.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrendMetric("重开", item.reopenCount.toString(), Modifier.weight(1f))
                TrendMetric("人工审核", item.manualReviewCount.toString(), Modifier.weight(1f))
                TrendMetric("已完成", item.resolvedCount.toString(), Modifier.weight(1f))
            }
            if (item.lastReason.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                Spacer(Modifier.height(9.dp))
                Text("最近原因：${item.lastReason}", fontSize = 11.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(7.dp))
            val time = maxOf(item.lastReopenAt, item.lastActionAt)
            Text(
                buildString {
                    if (time > 0L) append("最近变化 ${formatTime(time)}")
                    if (item.averageResolutionMs > 0L) {
                        if (isNotEmpty()) append(" · ")
                        append("平均处理 ${formatDuration(item.averageResolutionMs)}")
                    }
                }.ifBlank { "暂无可计算的处理周期" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp
            )
        }
    }
}

@Composable
private fun RuleReviewTrendEmpty(available: Boolean, filter: String, horizontal: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(42.dp), tint = BaiZeTokens.colors.success)
            Spacer(Modifier.height(10.dp))
            Text(if (!available) "暂无审核历史" else "当前筛选没有项目", fontWeight = FontWeight.Bold)
            Text(
                if (!available) "在规则质量中心完成人工审核后，这里会形成处理周期和重开趋势。" else "筛选 ${filterLabel(filter)} 下没有规则分类。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun TrendMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .58f)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
            Text(value, fontWeight = FontWeight.Black, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun trendLabel(value: String): String = when (value) {
    "up" -> "上升"
    "down" -> "下降"
    else -> "持平"
}

private fun reviewStateLabel(value: String): String = when (value) {
    "kept" -> "已保留"
    "observing" -> "观察中"
    "ignored" -> "已忽略"
    else -> "待审核"
}

private fun filterLabel(value: String): String = when (value) {
    "repeated" -> "反复重开"
    "active" -> "等待处理"
    "resolved" -> "已完成"
    else -> "全部"
}

private fun formatDuration(ms: Long): String = when {
    ms <= 0L -> "暂无"
    ms < 60_000L -> "不到 1 分钟"
    ms < 3_600_000L -> "${ms / 60_000L} 分钟"
    ms < 86_400_000L -> "${ms / 3_600_000L} 小时"
    else -> "${ms / 86_400_000L} 天"
}

private fun formatTime(epoch: Long): String = runCatching {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epoch))
}.getOrDefault("-")
