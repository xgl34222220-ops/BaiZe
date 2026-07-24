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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingFlat
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class CleanupEffectivenessActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private var service: IProfileRootService? = null
    private var bound = false
    private var state by mutableStateOf(EffectivenessUiState())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IProfileRootService.Stub.asInterface(binder)
            bound = true
            state = state.copy(connected = true, message = "Root 效果分析服务已连接")
            load()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            state = state.copy(connected = false, loading = false, message = "Root 效果分析服务已断开")
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
                    CleanupEffectivenessScreen(
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
        state = state.copy(message = "正在连接 Root 效果分析服务…")
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
        state = state.copy(loading = true, message = "正在分析最近 30 天清理效果…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    JSONObject(root.getAuditTimelinePage(0, 100)).optJSONObject("effectiveness")
                        ?: JSONObject()
                }
            }
            result.onSuccess { json ->
                val report = parseReport(json)
                state = state.copy(
                    connected = true,
                    loading = false,
                    report = report,
                    message = if (report.available) "已分析 ${report.sampleCount} 次扫描与清理" else "暂无足够的扫描或清理记录"
                )
            }.onFailure {
                state = state.copy(loading = false, message = "读取效果评分失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun parseReport(json: JSONObject): EffectivenessReport {
        val dimensions = json.optJSONObject("dimensions") ?: JSONObject()
        val trend = json.optJSONObject("trend") ?: JSONObject()
        return EffectivenessReport(
            available = json.optBoolean("available"),
            overall = json.optInt("overall").coerceIn(0, 100),
            grade = json.optString("grade", "N/A"),
            summary = json.optString("summary", "暂无效果评分"),
            sampleCount = json.optInt("sampleCount").coerceAtLeast(0),
            safety = dimensions.optInt("safety").coerceIn(0, 100),
            benefit = dimensions.optInt("benefit").coerceIn(0, 100),
            speed = dimensions.optInt("speed").coerceIn(0, 100),
            stability = dimensions.optInt("stability").coerceIn(0, 100),
            trendDirection = trend.optString("direction", "insufficient"),
            trendDelta = trend.optInt("delta"),
            trendMessage = trend.optString("message", "历史样本不足，暂不判断趋势"),
            tasks = json.optJSONArray("recentTasks").toTasks(),
            observations = json.optJSONArray("ruleObservations").toObservations(),
            readOnly = json.optBoolean("readOnly", true),
            scheduleUntouched = json.optBoolean("scheduleUntouched", true)
        )
    }
}

private data class EffectivenessUiState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val report: EffectivenessReport = EffectivenessReport(),
    val message: String = "等待连接 Root 效果分析服务"
)

private data class EffectivenessReport(
    val available: Boolean = false,
    val overall: Int = 0,
    val grade: String = "N/A",
    val summary: String = "暂无效果评分",
    val sampleCount: Int = 0,
    val safety: Int = 0,
    val benefit: Int = 0,
    val speed: Int = 0,
    val stability: Int = 0,
    val trendDirection: String = "insufficient",
    val trendDelta: Int = 0,
    val trendMessage: String = "历史样本不足，暂不判断趋势",
    val tasks: List<EffectivenessTask> = emptyList(),
    val observations: List<RuleObservation> = emptyList(),
    val readOnly: Boolean = true,
    val scheduleUntouched: Boolean = true
)

private data class EffectivenessTask(
    val id: String,
    val time: String,
    val operation: String,
    val status: String,
    val bytes: Long,
    val elapsedMs: Long,
    val overall: Int,
    val grade: String,
    val safety: Int,
    val benefit: Int,
    val speed: Int,
    val stability: Int
)

private data class RuleObservation(
    val category: String,
    val type: String,
    val observations: Int,
    val protected: Int,
    val protectionRate: Int,
    val bytes: Long,
    val message: String
)

@Composable
private fun CleanupEffectivenessScreen(
    state: EffectivenessUiState,
    miuix: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    val report = state.report
    val horizontal = if (miuix) 18.dp else 20.dp
    val shape = if (miuix) RoundedCornerShape(28.dp) else MaterialTheme.shapes.extraLarge
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item { EffectivenessHeader(state.message, state.loading, onBack, onRefresh) }
        if (!report.available) {
            item { EmptyEffectivenessCard(horizontal, shape, state.loading) }
        } else {
            item { EffectivenessHero(report, horizontal, shape) }
            item {
                Column(Modifier.padding(horizontal = horizontal)) {
                    Text("四维评分", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text("安全性优先，其次评估收益、耗时和运行稳定性", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            item {
                Column(Modifier.padding(horizontal = horizontal), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        ScoreCard("安全性", report.safety, Icons.Rounded.Security, Modifier.weight(1f))
                        ScoreCard("收益", report.benefit, Icons.Rounded.Savings, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        ScoreCard("耗时", report.speed, Icons.Rounded.Speed, Modifier.weight(1f))
                        ScoreCard("稳定性", report.stability, Icons.Rounded.Verified, Modifier.weight(1f))
                    }
                }
            }
            item { TrendCard(report, horizontal, shape) }
            if (report.observations.isNotEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = horizontal)) {
                        Text("规则观察", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                        Text("仅提示人工检查，不会自动关闭或修改规则", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
                items(report.observations, key = { "${it.type}:${it.category}" }) { observation ->
                    RuleObservationCard(observation, horizontal, shape)
                }
            }
            if (report.tasks.isNotEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = horizontal)) {
                        Text("最近任务", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                        Text("每次扫描和清理都保留独立四维评分", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
                items(report.tasks, key = { it.id.ifBlank { "${it.time}:${it.operation}" } }) { task ->
                    TaskScoreCard(task, horizontal, shape)
                }
            }
            item { ReadOnlyNotice(report, horizontal, shape) }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
private fun EffectivenessHeader(message: String, loading: Boolean, onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "返回") }
        Spacer(Modifier.width(5.dp))
        Column(Modifier.weight(1f)) {
            Text("清理效果", fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Rounded.Refresh, contentDescription = "刷新") }
    }
}

@Composable
private fun EffectivenessHero(report: EffectivenessReport, horizontal: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.size(76.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = .14f)) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(report.overall.toString(), fontSize = 25.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                        Text(report.grade, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("30 天综合评分", fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text(report.summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(7.dp))
                Text("基于 ${report.sampleCount} 次有效任务", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ScoreCard(label: String, score: Int, icon: ImageVector, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(9.dp))
            Text("$score 分", fontSize = 22.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun TrendCard(report: EffectivenessReport, horizontal: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape) {
    val icon = when (report.trendDirection) {
        "improving" -> Icons.Rounded.TrendingUp
        "declining" -> Icons.Rounded.TrendingDown
        else -> Icons.Rounded.TrendingFlat
    }
    Card(modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(), shape = shape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.size(45.dp), shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.secondary.copy(alpha = .14f)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("历史趋势", fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text(report.trendMessage, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            if (report.trendDirection !in setOf("insufficient", "stable")) {
                Text(if (report.trendDelta > 0) "+${report.trendDelta}" else report.trendDelta.toString(), fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun RuleObservationCard(observation: RuleObservation, horizontal: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape) {
    val protected = observation.type == "frequently_protected"
    Card(modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(), shape = shape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (protected) Icons.Rounded.Security else Icons.Rounded.Rule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(observation.category, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (protected) "频繁受保护" else "长期低收益", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text("${observation.observations} 次", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
            Spacer(Modifier.height(9.dp))
            Text(observation.message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "累计 ${Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current, observation.bytes)} · 保护率 ${observation.protectionRate}%",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun TaskScoreCard(task: EffectivenessTask, horizontal: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(), shape = shape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(modifier = Modifier.size(45.dp), shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .11f)) {
                    Box(contentAlignment = Alignment.Center) { Text(task.grade, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary) }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(task.operation.ifBlank { "清理任务" }, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${task.time} · ${task.status}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                }
                Text("${task.overall} 分", fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TinyMetric("安全", task.safety, Modifier.weight(1f))
                TinyMetric("收益", task.benefit, Modifier.weight(1f))
                TinyMetric("耗时", task.speed, Modifier.weight(1f))
                TinyMetric("稳定", task.stability, Modifier.weight(1f))
            }
            if (task.bytes > 0L || task.elapsedMs > 0L) {
                Spacer(Modifier.height(9.dp))
                Text(
                    "释放 ${Formatter.formatFileSize(context, task.bytes)} · 用时 ${formatDuration(task.elapsedMs)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun TinyMetric(label: String, score: Int, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 8.sp)
            Text(score.toString(), fontWeight = FontWeight.Black, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ReadOnlyNotice(report: EffectivenessReport, horizontal: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape) {
    Card(modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(), shape = shape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("只读分析", fontWeight = FontWeight.Black)
                Text(
                    "评分不会自动关闭规则、删除文件、切换策略或修改定时周期。${if (report.readOnly && report.scheduleUntouched) "当前安全约束已确认。" else ""}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

@Composable
private fun EmptyEffectivenessCard(horizontal: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape, loading: Boolean) {
    Card(modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(), shape = shape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (loading) CircularProgressIndicator(Modifier.size(34.dp)) else Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))
            Text(if (loading) "正在生成评分" else "暂无足够记录", fontWeight = FontWeight.Black)
            Text("完成几次扫描和清理后，这里会显示四维评分与规则趋势。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

private fun JSONArray?.toTasks(): List<EffectivenessTask> = buildList {
    val array = this@toTasks ?: return@buildList
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        add(EffectivenessTask(
            id = item.optString("id"),
            time = item.optString("time"),
            operation = item.optString("operation"),
            status = item.optString("status"),
            bytes = item.optLong("bytes").coerceAtLeast(0L),
            elapsedMs = item.optLong("elapsedMs").coerceAtLeast(0L),
            overall = item.optInt("overall").coerceIn(0, 100),
            grade = item.optString("grade", "D"),
            safety = item.optInt("safety").coerceIn(0, 100),
            benefit = item.optInt("benefit").coerceIn(0, 100),
            speed = item.optInt("speed").coerceIn(0, 100),
            stability = item.optInt("stability").coerceIn(0, 100)
        ))
    }
}

private fun JSONArray?.toObservations(): List<RuleObservation> = buildList {
    val array = this@toObservations ?: return@buildList
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        add(RuleObservation(
            category = item.optString("category"),
            type = item.optString("type"),
            observations = item.optInt("observations").coerceAtLeast(0),
            protected = item.optInt("protected").coerceAtLeast(0),
            protectionRate = item.optInt("protectionRate").coerceIn(0, 100),
            bytes = item.optLong("bytes").coerceAtLeast(0L),
            message = item.optString("message")
        ))
    }
}

private fun formatDuration(milliseconds: Long): String = when {
    milliseconds <= 0L -> "未知"
    milliseconds < 1_000L -> "${milliseconds}ms"
    milliseconds < 60_000L -> String.format(java.util.Locale.US, "%.1fs", milliseconds / 1_000.0)
    else -> String.format(java.util.Locale.US, "%.1fmin", milliseconds / 60_000.0)
}
