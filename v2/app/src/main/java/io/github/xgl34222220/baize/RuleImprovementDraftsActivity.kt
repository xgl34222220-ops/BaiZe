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
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import org.json.JSONObject

class RuleImprovementDraftsActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private var service: IProfileRootService? = null
    private var bound = false
    private var state by mutableStateOf(RuleImprovementDraftsUiState())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IProfileRootService.Stub.asInterface(binder)
            bound = true
            state = state.copy(connected = true, message = "Root 规则草案服务已连接")
            load()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            state = state.copy(connected = false, loading = false, message = "Root 规则草案服务已断开")
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
                    RuleImprovementDraftsScreen(
                        state = state,
                        miuix = appearance.uiStyle == UiStyle.MIUIX,
                        onBack = ::finish,
                        onRefresh = ::load,
                        onOpenRuleQuality = { startActivity(Intent(this, RuleQualityActivity::class.java)) }
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
        state = state.copy(message = "正在连接 Root 规则草案服务…")
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
        state = state.copy(loading = true, message = "正在生成只读规则改进草案…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    JSONObject(root.getAuditTimelinePage(0, 100)).optJSONObject("ruleImprovementDrafts") ?: JSONObject()
                }
            }
            result.onSuccess { json ->
                val report = parseReport(json)
                state = state.copy(
                    connected = true,
                    loading = false,
                    report = report,
                    message = if (report.available) report.summary else "暂无足够证据生成草案"
                )
            }.onFailure {
                state = state.copy(loading = false, message = "读取规则草案失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun parseReport(json: JSONObject): RuleImprovementDraftReport {
        val drafts = buildList {
            val array = json.optJSONArray("drafts")
            if (array != null) for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val evidence = item.optJSONArray("evidence").toStringList()
                val checklist = item.optJSONArray("checklist").toStringList()
                val preview = buildList {
                    val previewArray = item.optJSONArray("preview")
                    if (previewArray != null) for (previewIndex in 0 until previewArray.length()) {
                        val line = previewArray.optJSONObject(previewIndex) ?: continue
                        add(
                            RuleDraftPreview(
                                dimension = line.optString("dimension"),
                                before = line.optString("before"),
                                after = line.optString("after")
                            )
                        )
                    }
                }
                add(
                    RuleImprovementDraft(
                        key = item.optString("key"),
                        category = item.optString("category", "未命名分类"),
                        action = item.optString("action", "observe"),
                        priority = item.optString("priority", "low"),
                        title = item.optString("title", "继续观察草案"),
                        rationale = item.optString("rationale"),
                        impact = item.optString("impact"),
                        caution = item.optString("caution"),
                        reviewState = item.optString("reviewState", "pending"),
                        type = item.optString("type"),
                        severity = item.optString("severity"),
                        risk = item.optString("risk"),
                        events = item.optInt("events").coerceAtLeast(0),
                        observations = item.optInt("observations").coerceAtLeast(0),
                        processed = item.optInt("processed").coerceAtLeast(0),
                        failureRate = item.optInt("failureRate").coerceIn(0, 100),
                        protectionRate = item.optInt("protectionRate").coerceIn(0, 100),
                        averageBytes = item.optLong("averageBytes").coerceAtLeast(0L),
                        reopenCount = item.optInt("reopenCount").coerceAtLeast(0),
                        repeated = item.optBoolean("repeated", false),
                        activeReopened = item.optBoolean("activeReopened", false),
                        lastReason = item.optString("lastReason"),
                        evidence = evidence,
                        preview = preview,
                        checklist = checklist
                    )
                )
            }
        }
        return RuleImprovementDraftReport(
            available = json.optBoolean("available", false),
            summary = json.optString("summary", "暂无足够证据生成规则改进草案"),
            lookbackDays = json.optInt("lookbackDays", 45).coerceAtLeast(1),
            draftCount = json.optInt("draftCount", drafts.size).coerceAtLeast(drafts.size),
            highPriorityCount = json.optInt("highPriorityCount").coerceAtLeast(0),
            pendingReviewCount = json.optInt("pendingReviewCount").coerceAtLeast(0),
            repeatedCount = json.optInt("repeatedCount").coerceAtLeast(0),
            considerDisableCount = json.optInt("considerDisableCount").coerceAtLeast(0),
            narrowScopeCount = json.optInt("narrowScopeCount").coerceAtLeast(0),
            strengthenProtectionCount = json.optInt("strengthenProtectionCount").coerceAtLeast(0),
            observeCount = json.optInt("observeCount").coerceAtLeast(0),
            conceptualPreview = json.optBoolean("conceptualPreview", true),
            exactPatchIncluded = json.optBoolean("exactPatchIncluded", false),
            readOnly = json.optBoolean("readOnly", true),
            drafts = drafts
        )
    }
}

private data class RuleImprovementDraftsUiState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val report: RuleImprovementDraftReport = RuleImprovementDraftReport(),
    val message: String = "等待连接 Root 规则草案服务"
)

private data class RuleImprovementDraftReport(
    val available: Boolean = false,
    val summary: String = "暂无足够证据生成规则改进草案",
    val lookbackDays: Int = 45,
    val draftCount: Int = 0,
    val highPriorityCount: Int = 0,
    val pendingReviewCount: Int = 0,
    val repeatedCount: Int = 0,
    val considerDisableCount: Int = 0,
    val narrowScopeCount: Int = 0,
    val strengthenProtectionCount: Int = 0,
    val observeCount: Int = 0,
    val conceptualPreview: Boolean = true,
    val exactPatchIncluded: Boolean = false,
    val readOnly: Boolean = true,
    val drafts: List<RuleImprovementDraft> = emptyList()
)

private data class RuleImprovementDraft(
    val key: String,
    val category: String,
    val action: String,
    val priority: String,
    val title: String,
    val rationale: String,
    val impact: String,
    val caution: String,
    val reviewState: String,
    val type: String,
    val severity: String,
    val risk: String,
    val events: Int,
    val observations: Int,
    val processed: Int,
    val failureRate: Int,
    val protectionRate: Int,
    val averageBytes: Long,
    val reopenCount: Int,
    val repeated: Boolean,
    val activeReopened: Boolean,
    val lastReason: String,
    val evidence: List<String>,
    val preview: List<RuleDraftPreview>,
    val checklist: List<String>
)

private data class RuleDraftPreview(val dimension: String, val before: String, val after: String)

@Composable
private fun RuleImprovementDraftsScreen(
    state: RuleImprovementDraftsUiState,
    miuix: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenRuleQuality: () -> Unit
) {
    var filter by rememberSaveable { mutableStateOf("all") }
    val horizontal = if (miuix) 18.dp else 20.dp
    val shape = if (miuix) RoundedCornerShape(26.dp) else MaterialTheme.shapes.extraLarge
    val filtered = remember(state.report.drafts, filter) {
        state.report.drafts.filter { draft ->
            when (filter) {
                "high" -> draft.priority == "high"
                "disable" -> draft.action == "consider_disable"
                "scope" -> draft.action == "narrow_scope"
                "protect" -> draft.action == "strengthen_protection"
                "observe" -> draft.action == "observe"
                "repeated" -> draft.repeated || draft.activeReopened
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
            item { DraftHeader(state.message, state.loading, onBack, onRefresh) }
            item { DraftSummary(state.report, horizontal, shape) }
            item { DraftSafetyCard(horizontal, shape, onOpenRuleQuality) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = horizontal),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "all" to "全部",
                        "high" to "高优先级",
                        "disable" to "考虑停用",
                        "scope" to "缩小范围",
                        "protect" to "增强保护",
                        "observe" to "继续观察",
                        "repeated" to "反复恶化"
                    ).forEach { (id, label) ->
                        FilterChip(selected = filter == id, onClick = { filter = id }, label = { Text(label) })
                    }
                }
            }
            if (filtered.isEmpty() && !state.loading) {
                item { DraftEmpty(horizontal, shape, state.report.available) }
            } else {
                items(filtered, key = { it.key }) { draft ->
                    DraftCard(draft, horizontal, shape)
                }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

@Composable
private fun DraftHeader(message: String, loading: Boolean, onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "返回") }
        Spacer(Modifier.width(5.dp))
        Column(Modifier.weight(1f)) {
            Text("规则改进草案", fontSize = 29.sp, fontWeight = FontWeight.Black)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, contentDescription = "刷新") }
        }
    }
}

@Composable
private fun DraftSummary(report: RuleImprovementDraftReport, horizontal: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(50.dp), RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .13f)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Rule, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text("${report.draftCount} 份人工草案", fontSize = 21.sp, fontWeight = FontWeight.Black)
                    Text(report.summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DraftMetric("高优先级", report.highPriorityCount.toString(), Modifier.weight(1f))
                DraftMetric("待审核", report.pendingReviewCount.toString(), Modifier.weight(1f))
                DraftMetric("反复恶化", report.repeatedCount.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(9.dp))
            Text(
                "停用评估 ${report.considerDisableCount} · 缩小范围 ${report.narrowScopeCount} · 增强保护 ${report.strengthenProtectionCount} · 观察 ${report.observeCount}",
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun DraftMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .55f)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
            Text(value, fontWeight = FontWeight.Black, fontSize = 17.sp)
        }
    }
}

@Composable
private fun DraftSafetyCard(horizontal: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape, onOpenRuleQuality: () -> Unit) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(44.dp), RoundedCornerShape(15.dp), color = BaiZeTokens.colors.success.copy(alpha = .13f)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Security, contentDescription = null, tint = BaiZeTokens.colors.success) }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("只读概念草案", fontWeight = FontWeight.Black, fontSize = 16.sp)
                    Text("不读取真实规则文本，不生成可执行补丁", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "所有内容仅供人工判断。系统不会停用规则、写入规则文件、删除文件、启动清理、修改策略、快照或任何定时周期。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(11.dp))
            OutlinedButton(onClick = onOpenRuleQuality, modifier = Modifier.fillMaxWidth()) { Text("返回规则质量中心人工审核") }
        }
    }
}

@Composable
private fun DraftCard(draft: RuleImprovementDraft, horizontal: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val visual = draftVisual(draft.action)
    var expanded by rememberSaveable(draft.key) { mutableStateOf(false) }
    val canExpand = draft.preview.isNotEmpty() || draft.checklist.isNotEmpty() || draft.evidence.isNotEmpty()

    Card(
        modifier = Modifier
            .padding(horizontal = horizontal)
            .fillMaxWidth()
            .animateContentSize()
            .clickable(enabled = canExpand) { expanded = !expanded },
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(44.dp), RoundedCornerShape(15.dp), color = visual.tint.copy(alpha = .13f)) {
                    Box(contentAlignment = Alignment.Center) { Icon(visual.icon, contentDescription = null, tint = visual.tint) }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(draft.category, fontWeight = FontWeight.Black, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(draft.title, color = visual.tint, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                }
                Surface(shape = CircleShape, color = priorityColor(draft.priority).copy(alpha = .13f)) {
                    Text(
                        priorityLabel(draft.priority),
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = priorityColor(draft.priority),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (canExpand) {
                    Spacer(Modifier.width(4.dp))
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(draft.rationale, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DraftMetric("异常率", "${draft.failureRate}%", Modifier.weight(1f))
                DraftMetric("保护率", "${draft.protectionRate}%", Modifier.weight(1f))
                DraftMetric("重开", draft.reopenCount.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "任务 ${draft.events} · 明细 ${draft.observations} · 处理 ${draft.processed} · 平均 ${Formatter.formatFileSize(context, draft.averageBytes)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
            if (draft.activeReopened || draft.repeated) {
                Spacer(Modifier.height(7.dp))
                Text(
                    when {
                        draft.activeReopened -> "当前审核已因新证据重新打开"
                        draft.repeated -> "该规则曾反复重新打开审核"
                        else -> ""
                    },
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                Spacer(Modifier.height(12.dp))
                if (draft.evidence.isNotEmpty()) {
                    Text("生成依据", fontWeight = FontWeight.Black, fontSize = 13.sp)
                    draft.evidence.forEach { Text("• $it", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 17.sp) }
                    Spacer(Modifier.height(12.dp))
                }
                Text("安全差异预览（概念）", fontWeight = FontWeight.Black, fontSize = 13.sp)
                Spacer(Modifier.height(7.dp))
                draft.preview.forEach { line -> DraftPreviewRow(line) }
                Spacer(Modifier.height(10.dp))
                Text("预期影响", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(draft.impact, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(7.dp))
                Text("注意事项", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(draft.caution, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, lineHeight = 17.sp)
                if (draft.checklist.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("人工应用检查清单", fontWeight = FontWeight.Black, fontSize = 13.sp)
                    draft.checklist.forEachIndexed { index, step ->
                        Text("${index + 1}. $step", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 17.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Surface(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(
                        "此页面没有应用、停用或写入按钮。请先回到规则质量中心核对证据，再人工修改并复测。",
                        Modifier.padding(11.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun DraftPreviewRow(line: RuleDraftPreview) {
    Surface(Modifier.fillMaxWidth().padding(bottom = 7.dp), RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(11.dp)) {
            Text(line.dimension, fontWeight = FontWeight.Black, fontSize = 11.sp)
            Text("当前：${line.before}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 16.sp)
            Text("草案：${line.after}", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DraftEmpty(horizontal: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape, available: Boolean) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(42.dp), tint = BaiZeTokens.colors.success)
            Spacer(Modifier.height(10.dp))
            Text(if (available) "当前筛选没有草案" else "暂无可生成的改进草案", fontWeight = FontWeight.Bold)
            Text(
                "继续正常扫描、清理和人工审核后，系统会基于脱敏统计生成概念草案。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 17.sp
            )
        }
    }
}

private data class DraftVisual(val icon: ImageVector, val tint: androidx.compose.ui.graphics.Color)

@Composable
private fun draftVisual(action: String): DraftVisual = when (action) {
    "consider_disable" -> DraftVisual(Icons.Rounded.Block, MaterialTheme.colorScheme.error)
    "strengthen_protection" -> DraftVisual(Icons.Rounded.Security, MaterialTheme.colorScheme.tertiary)
    "narrow_scope" -> DraftVisual(Icons.Rounded.Rule, MaterialTheme.colorScheme.primary)
    else -> DraftVisual(Icons.Rounded.Visibility, MaterialTheme.colorScheme.secondary)
}

@Composable
private fun priorityColor(priority: String): androidx.compose.ui.graphics.Color = when (priority) {
    "high" -> MaterialTheme.colorScheme.error
    "medium" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun priorityLabel(priority: String): String = when (priority) {
    "high" -> "高优先级"
    "medium" -> "中优先级"
    else -> "低优先级"
}

private fun org.json.JSONArray?.toStringList(): List<String> = buildList {
    val array = this@toStringList ?: return@buildList
    for (index in 0 until array.length()) {
        array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
    }
}
