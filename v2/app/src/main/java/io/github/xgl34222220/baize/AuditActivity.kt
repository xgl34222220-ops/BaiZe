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
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import org.json.JSONArray
import org.json.JSONObject

class AuditActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private var service: IProfileRootService? = null
    private var bound = false
    private var state by mutableStateOf(AuditUiState())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IProfileRootService.Stub.asInterface(binder)
            bound = true
            state = state.copy(connected = true, message = "Root 审计服务已连接")
            loadTimeline()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            state = state.copy(connected = false, loading = false, message = "Root 审计服务已断开")
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
                    AuditScreen(
                        state = state,
                        style = appearance.uiStyle,
                        onBack = ::finish,
                        onRefresh = ::loadTimeline,
                        onClear = ::clearTimeline,
                        onOpenPolicy = { startActivity(Intent(this, CleanupPolicyActivity::class.java)) },
                        onOpenEffectiveness = { startActivity(Intent(this, CleanupEffectivenessActivity::class.java)) },
                        onOpenRuleQuality = { startActivity(Intent(this, RuleQualityActivity::class.java)) },
                        onOpenReviewTrends = { startActivity(Intent(this, RuleReviewTrendsActivity::class.java)) }
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
        state = state.copy(message = "正在连接 Root 审计服务…")
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
            bound = true
        }.onFailure {
            bound = false
            state = state.copy(message = "Root 审计服务启动失败：${it.message.orEmpty()}")
        }
    }

    private fun loadTimeline() {
        val root = service ?: return
        if (state.loading) return
        state = state.copy(loading = true, message = "正在读取结构化审计时间线…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { JSONObject(root.getAuditTimelinePage(0, 100)) }
            }
            result.onSuccess { json ->
                if (!json.optBoolean("success")) {
                    state = state.copy(loading = false, message = json.optString("message", "读取审计记录失败"))
                    return@onSuccess
                }
                val array = json.optJSONArray("events") ?: JSONArray()
                val events = buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        add(parseEvent(item))
                    }
                }
                state = state.copy(
                    loading = false,
                    events = events,
                    total = json.optInt("total", events.size).coerceAtLeast(events.size),
                    successCount = json.optInt("successCount").coerceAtLeast(0),
                    failedCount = json.optInt("failedCount").coerceAtLeast(0),
                    cancelledCount = json.optInt("cancelledCount").coerceAtLeast(0),
                    releasedBytes = json.optLong("releasedBytes").coerceAtLeast(0L),
                    quarantinedBytes = json.optLong("quarantinedBytes").coerceAtLeast(0L),
                    protectedCount = json.optLong("protectedCount").coerceAtLeast(0L),
                    advice = parseAdvice(json.optJSONObject("advisor")),
                    message = if (events.isEmpty()) "暂无审计事件" else "已载入 ${events.size} / ${json.optInt("total", events.size)} 条事件"
                )
            }.onFailure {
                state = state.copy(loading = false, message = "读取审计记录失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun clearTimeline() {
        val root = service ?: return
        if (state.loading) return
        state = state.copy(loading = true, message = "正在清空审计时间线…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { JSONObject(root.clearAuditTimeline()) }
            }
            result.onSuccess { json ->
                state = state.copy(loading = false, message = json.optString("message", "审计时间线已清空"))
                if (json.optBoolean("success")) loadTimeline()
            }.onFailure {
                state = state.copy(loading = false, message = "清空失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun parseEvent(json: JSONObject): AuditEvent = AuditEvent(
        id = json.optString("id"),
        time = json.optString("time"),
        operation = json.optString("operation"),
        kind = json.optString("kind", "clean"),
        source = json.optString("source", "app"),
        status = json.optString("status", "success"),
        message = json.optString("message"),
        errorCode = json.optString("errorCode"),
        profile = json.optString("profile"),
        snapshotId = json.optString("snapshotId"),
        selected = json.optLong("selected").coerceAtLeast(0L),
        processed = json.optLong("processed").coerceAtLeast(0L),
        skipped = json.optLong("skipped").coerceAtLeast(0L),
        protected = json.optLong("protected").coerceAtLeast(0L),
        bytes = json.optLong("bytes").coerceAtLeast(0L),
        files = json.optLong("files").coerceAtLeast(0L),
        directories = json.optLong("directories").coerceAtLeast(0L),
        errors = json.optLong("errors").coerceAtLeast(0L),
        elapsedMs = json.optLong("elapsedMs").coerceAtLeast(0L),
        reasons = json.optJSONArray("reasonCodes").toStringList(),
        details = json.optJSONArray("details").toDetailList(),
        legacy = json.optBoolean("legacy")
    )

    private fun parseAdvice(raw: JSONObject?): AuditPolicyAdvice? {
        if (raw == null || !raw.optBoolean("available", false)) return null
        val reasonsJson = raw.optJSONArray("reasons")
        val reasons = buildList {
            if (reasonsJson != null) for (index in 0 until reasonsJson.length()) {
                reasonsJson.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        return AuditPolicyAdvice(
            recommendedPolicy = CleanupPolicy.fromId(raw.optInt("recommendedPolicyId", CleanupPolicy.BALANCED.id)),
            summary = raw.optString("summary", "暂时没有策略建议"),
            confidence = raw.optString("confidence", "low"),
            storageFreePercent = raw.optInt("storageFreePercent", -1),
            failureRate = raw.optInt("failureRate").coerceIn(0, 100),
            restoreRate = raw.optInt("restoreRate").coerceIn(0, 100),
            sampleCount = raw.optInt("sampleCount").coerceAtLeast(0),
            reasons = reasons
        )
    }
}

private data class AuditUiState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val events: List<AuditEvent> = emptyList(),
    val total: Int = 0,
    val successCount: Int = 0,
    val failedCount: Int = 0,
    val cancelledCount: Int = 0,
    val releasedBytes: Long = 0L,
    val quarantinedBytes: Long = 0L,
    val protectedCount: Long = 0L,
    val advice: AuditPolicyAdvice? = null,
    val message: String = "等待连接 Root 审计服务"
)

private data class AuditPolicyAdvice(
    val recommendedPolicy: CleanupPolicy,
    val summary: String,
    val confidence: String,
    val storageFreePercent: Int,
    val failureRate: Int,
    val restoreRate: Int,
    val sampleCount: Int,
    val reasons: List<String>
)

private data class AuditEvent(
    val id: String,
    val time: String,
    val operation: String,
    val kind: String,
    val source: String,
    val status: String,
    val message: String,
    val errorCode: String,
    val profile: String,
    val snapshotId: String,
    val selected: Long,
    val processed: Long,
    val skipped: Long,
    val protected: Long,
    val bytes: Long,
    val files: Long,
    val directories: Long,
    val errors: Long,
    val elapsedMs: Long,
    val reasons: List<String>,
    val details: List<AuditDetail>,
    val legacy: Boolean
)

private data class AuditDetail(
    val action: String,
    val category: String,
    val risk: String,
    val reason: String,
    val pathTail: String,
    val bytes: Long,
    val files: Long,
    val directories: Long
)

@Composable
private fun AuditScreen(
    state: AuditUiState,
    style: UiStyle,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onOpenPolicy: () -> Unit,
    onOpenEffectiveness: () -> Unit,
    onOpenRuleQuality: () -> Unit,
    onOpenReviewTrends: () -> Unit
) {
    var filter by rememberSaveable { mutableStateOf("all") }
    var confirmClear by remember { mutableStateOf(false) }
    val miuix = style == UiStyle.MIUIX
    val horizontal = if (miuix) 18.dp else 20.dp
    val cardShape = if (miuix) RoundedCornerShape(26.dp) else MaterialTheme.shapes.extraLarge
    val filtered = remember(state.events, filter) {
        state.events.filter { event ->
            when (filter) {
                "scan" -> event.kind == "scan"
                "clean" -> event.kind == "clean" || event.kind == "organize"
                "safety" -> event.kind == "safety"
                "failed" -> event.status in setOf("failed", "partial", "cancelled")
                else -> true
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            item {
                AuditHeader(
                    message = state.message,
                    loading = state.loading,
                    onBack = onBack,
                    onRefresh = onRefresh,
                    onClear = { confirmClear = true }
                )
            }
            item { AuditSummary(state, horizontal, cardShape) }
            item { EffectivenessEntryCard(horizontal, cardShape, onOpenEffectiveness) }
            item { RuleQualityEntryCard(horizontal, cardShape, onOpenRuleQuality) }
            item { RuleReviewTrendsEntryCard(horizontal, cardShape, onOpenReviewTrends) }
            state.advice?.let { advice ->
                item { AuditPolicyAdviceCard(advice, horizontal, cardShape, onOpenPolicy) }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = horizontal),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "all" to "全部",
                        "scan" to "扫描",
                        "clean" to "清理",
                        "safety" to "隔离与恢复",
                        "failed" to "异常"
                    ).forEach { (id, label) ->
                        FilterChip(selected = filter == id, onClick = { filter = id }, label = { Text(label) })
                    }
                }
            }
            if (filtered.isEmpty() && !state.loading) {
                item { AuditEmptyCard(horizontal, cardShape, filter) }
            } else {
                items(filtered, key = { it.id }) { event ->
                    AuditEventCard(event, horizontal, cardShape)
                }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空审计时间线？") },
            text = { Text("只隐藏当前时间点之前的审计事件，不会删除累计统计、白名单、隔离内容或原始清理历史。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun AuditHeader(
    message: String,
    loading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "返回") }
        Spacer(Modifier.width(5.dp))
        Column(Modifier.weight(1f)) {
            Text("清理审计", fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Rounded.Refresh, contentDescription = "刷新") }
        IconButton(onClick = onClear, enabled = !loading) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "清空审计") }
    }
}

@Composable
private fun AuditSummary(state: AuditUiState, horizontal: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
                        Icon(Icons.Rounded.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text("${state.total} 条可追溯事件", fontSize = 21.sp, fontWeight = FontWeight.Black)
                    Text(
                        "实际释放 ${Formatter.formatFileSize(context, state.releasedBytes)}",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuditMetric("成功", state.successCount.toString(), Modifier.weight(1f))
                AuditMetric("异常", state.failedCount.toString(), Modifier.weight(1f))
                AuditMetric("受保护", state.protectedCount.toString(), Modifier.weight(1f))
            }
            if (state.quarantinedBytes > 0L || state.cancelledCount > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "隔离 ${Formatter.formatFileSize(context, state.quarantinedBytes)} · 已停止 ${state.cancelledCount} 次",
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun AuditMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .55f)) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
            Text(value, fontWeight = FontWeight.Black, fontSize = 17.sp)
        }
    }
}

@Composable
private fun EffectivenessEntryCard(
    horizontal: androidx.compose.ui.unit.Dp,
    shape: androidx.compose.ui.graphics.Shape,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(45.dp),
                shape = RoundedCornerShape(15.dp),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = .14f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("清理效果评分", fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text("查看安全性、收益、耗时、稳定性和规则趋势", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            OutlinedButton(onClick = onOpen) { Text("查看") }
        }
    }
}

@Composable
private fun RuleQualityEntryCard(
    horizontal: androidx.compose.ui.unit.Dp,
    shape: androidx.compose.ui.graphics.Shape,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(45.dp),
                shape = RoundedCornerShape(15.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Rule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("规则质量中心", fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text("集中审核高失败、频繁保护、零命中与低收益规则", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            OutlinedButton(onClick = onOpen) { Text("审核") }
        }
    }
}

@Composable
private fun RuleReviewTrendsEntryCard(
    horizontal: androidx.compose.ui.unit.Dp,
    shape: androidx.compose.ui.graphics.Shape,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(45.dp),
                shape = RoundedCornerShape(15.dp),
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = .13f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Timeline, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("审核历史与趋势", fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text("查看反复重开、恶化原因与人工处理周期", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            OutlinedButton(onClick = onOpen) { Text("趋势") }
        }
    }
}

@Composable
private fun AuditPolicyAdviceCard(
    advice: AuditPolicyAdvice,
    horizontal: androidx.compose.ui.unit.Dp,
    shape: androidx.compose.ui.graphics.Shape,
    onOpenPolicy: () -> Unit
) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(43.dp),
                    shape = RoundedCornerShape(15.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("设备建议：${advice.recommendedPolicy.title}档", fontWeight = FontWeight.Black, fontSize = 16.sp)
                    Text(advice.summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "可用空间 ${if (advice.storageFreePercent < 0) "未知" else "${advice.storageFreePercent}%"} · 异常率 ${advice.failureRate}% · 恢复率 ${advice.restoreRate}% · 样本 ${advice.sampleCount}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
            advice.reasons.firstOrNull()?.let { reason ->
                Spacer(Modifier.height(7.dp))
                Text(reason, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Spacer(Modifier.height(11.dp))
            OutlinedButton(onClick = onOpenPolicy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Tune, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text("查看并手动采用建议")
            }
            Text(
                "建议不会自动生效，也不会修改定时任务周期。",
                modifier = Modifier.padding(top = 7.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp
            )
        }
    }
}

@Composable
private fun AuditEmptyCard(
    horizontal: androidx.compose.ui.unit.Dp,
    shape: androidx.compose.ui.graphics.Shape,
    filter: String
) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(10.dp))
            Text(if (filter == "all") "暂无审计事件" else "当前筛选没有事件", fontWeight = FontWeight.Bold)
            Text(
                "后续扫描、清理、隔离和恢复会自动写入 Root 审计时间线。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun AuditEventCard(
    event: AuditEvent,
    horizontal: androidx.compose.ui.unit.Dp,
    shape: androidx.compose.ui.graphics.Shape
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var expanded by rememberSaveable(event.id) { mutableStateOf(false) }
    val visual = auditVisual(event)
    val hasMore = event.reasons.isNotEmpty() || event.details.isNotEmpty() || event.snapshotId.isNotBlank() || event.errorCode.isNotBlank()
    Card(
        modifier = Modifier
            .padding(horizontal = horizontal)
            .fillMaxWidth()
            .animateContentSize()
            .clickable(enabled = hasMore) { expanded = !expanded },
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = visual.tint.copy(alpha = .12f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(visual.icon, contentDescription = null, tint = visual.tint, modifier = Modifier.size(21.dp))
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(operationTitle(event.operation), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "${event.time} · ${sourceLabel(event.source)}${if (event.legacy) " · 旧记录" else ""}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusBadge(event.status)
                if (hasMore) {
                    Spacer(Modifier.width(5.dp))
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(event.message.ifBlank { "任务未提供结果说明" }, fontSize = 12.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                if (event.bytes > 0L) AuditPill(Formatter.formatFileSize(context, event.bytes))
                if (event.selected > 0L) AuditPill("选择 ${event.selected}")
                if (event.processed > 0L) AuditPill("处理 ${event.processed}")
                if (event.skipped > 0L || event.protected > 0L) AuditPill("保护 ${event.skipped + event.protected}")
                if (event.errors > 0L) AuditPill("异常 ${event.errors}")
            }
            if (event.elapsedMs > 0L) {
                Spacer(Modifier.height(7.dp))
                Text("耗时 ${formatAuditElapsed(event.elapsedMs)}", color = MaterialTheme.colorScheme.outline, fontSize = 9.sp)
            }
            if (expanded) {
                Spacer(Modifier.height(13.dp))
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (event.profile.isNotBlank()) AuditKeyValue("配置", event.profile)
                        if (event.snapshotId.isNotBlank()) AuditKeyValue("快照", event.snapshotId.take(12))
                        if (event.errorCode.isNotBlank()) AuditKeyValue("错误码", event.errorCode)
                        event.reasons.forEach { reason -> AuditKeyValue("原因", reason) }
                        event.details.forEach { detail ->
                            Column {
                                Text(
                                    detail.category.ifBlank { detail.action.ifBlank { "候选明细" } },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                                val line = buildString {
                                    if (detail.reason.isNotBlank()) append(detail.reason)
                                    if (detail.pathTail.isNotBlank()) {
                                        if (isNotEmpty()) append("\n")
                                        append(detail.pathTail)
                                    }
                                }
                                if (line.isNotBlank()) Text(line, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, lineHeight = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val (label, color) = when (status) {
        "success" -> "成功" to BaiZeTokens.colors.success
        "scanned" -> "已扫描" to MaterialTheme.colorScheme.primary
        "accepted" -> "后台执行" to MaterialTheme.colorScheme.primary
        "partial" -> "部分异常" to BaiZeTokens.colors.warning
        "cancelled" -> "已停止" to BaiZeTokens.colors.warning
        else -> "失败" to MaterialTheme.colorScheme.error
    }
    Surface(shape = CircleShape, color = color.copy(alpha = .13f)) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AuditPill(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = .09f)) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.primary, fontSize = 9.sp)
    }
}

@Composable
private fun AuditKeyValue(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, color = MaterialTheme.colorScheme.primary, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, lineHeight = 13.sp, modifier = Modifier.weight(1f))
    }
}

private data class AuditVisual(val icon: ImageVector, val tint: androidx.compose.ui.graphics.Color)

@Composable
private fun auditVisual(event: AuditEvent): AuditVisual = when {
    event.status == "failed" -> AuditVisual(Icons.Rounded.ErrorOutline, MaterialTheme.colorScheme.error)
    event.status == "partial" || event.status == "cancelled" -> AuditVisual(Icons.Rounded.ErrorOutline, BaiZeTokens.colors.warning)
    event.kind == "scan" -> AuditVisual(Icons.Rounded.Search, MaterialTheme.colorScheme.primary)
    event.kind == "safety" -> AuditVisual(Icons.Rounded.Security, MaterialTheme.colorScheme.primary)
    event.kind == "clean" || event.kind == "organize" -> AuditVisual(Icons.Rounded.CleaningServices, BaiZeTokens.colors.success)
    else -> AuditVisual(Icons.Rounded.CheckCircle, BaiZeTokens.colors.success)
}

private fun operationTitle(operation: String): String = when (operation) {
    "scan", "profile-scan" -> "安全扫描"
    "clean", "smart-clean" -> "智能自动清理"
    "snapshot-clean" -> "扫描快照清理"
    "workbench-clean" -> "工作台所选清理"
    "profile-clean" -> "分类候选清理"
    "profile-quarantine" -> "高风险隔离"
    "quarantine-restore" -> "恢复隔离内容"
    "quarantine-purge" -> "永久删除隔离内容"
    "quarantine-expire" -> "清理过期隔离项"
    "instant-cache" -> "应用缓存清理"
    "file-organizer-scan" -> "文件归类扫描"
    "file-organizer-apply", "organize", "organizer" -> "文件自动归类"
    "file-organizer-undo" -> "撤销文件归类"
    "deep-scan" -> "完整深度扫描"
    "deep-clean" -> "完整深度清理"
    "corpse-scan" -> "卸载残留扫描"
    "corpse-clean" -> "卸载残留清理"
    else -> operation.replace('-', ' ').replaceFirstChar { it.uppercase() }
}

private fun sourceLabel(source: String): String = when {
    source.startsWith("scheduled:") -> "自动定时"
    source.startsWith("daily:") -> "每日定时"
    source == "app-native" -> "App 快照"
    source == "app" -> "App 手动"
    source == "manual" -> "手动执行"
    source == "history" -> "历史记录"
    else -> source.ifBlank { "Root 服务" }
}

private fun formatAuditElapsed(ms: Long): String = when {
    ms >= 3_600_000L -> "${ms / 3_600_000L}小时${ms % 3_600_000L / 60_000L}分"
    ms >= 60_000L -> "${ms / 60_000L}分${ms % 60_000L / 1_000L}秒"
    else -> "${ms / 1_000L}.${(ms % 1_000L) / 100L}秒"
}

private fun JSONArray?.toStringList(): List<String> = buildList {
    val array = this@toStringList ?: return@buildList
    for (index in 0 until array.length()) {
        val value = array.optString(index).trim()
        if (value.isNotBlank()) add(value)
    }
}

private fun JSONArray?.toDetailList(): List<AuditDetail> = buildList {
    val array = this@toDetailList ?: return@buildList
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        add(
            AuditDetail(
                action = item.optString("action"),
                category = item.optString("category"),
                risk = item.optString("risk"),
                reason = item.optString("reason"),
                pathTail = item.optString("pathTail"),
                bytes = item.optLong("bytes").coerceAtLeast(0L),
                files = item.optLong("files").coerceAtLeast(0L),
                directories = item.optLong("directories").coerceAtLeast(0L)
            )
        )
    }
}
