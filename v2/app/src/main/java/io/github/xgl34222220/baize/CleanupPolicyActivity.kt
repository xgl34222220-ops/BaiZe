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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import io.github.xgl34222220.baize.root.RootServiceClients
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class CleanupPolicyActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private var service: IProfileRootService? = null
    private var bound = false
    private var state by mutableStateOf(CleanupPolicyUiState())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = RootServiceClients.profile(binder, applicationContext.cacheDir)
            bound = true
            state = state.copy(connected = true, message = "Root 策略服务已连接")
            loadPolicy()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            state = state.copy(connected = false, loading = false, message = "Root 策略服务已断开")
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
                    CleanupPolicyScreen(
                        state = state,
                        onBack = ::finish,
                        onRefresh = ::loadPolicy,
                        onSelect = ::applyPolicy
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
        state = state.copy(message = "正在连接 Root 策略服务…")
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
            bound = true
        }.onFailure {
            bound = false
            state = state.copy(message = "Root 策略服务启动失败：${it.message.orEmpty()}")
        }
    }

    private fun loadPolicy() {
        val root = service ?: return
        if (state.loading) return
        state = state.copy(loading = true, message = "正在分析当前策略与设备状态…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val config = JSONObject(root.getSchedulerConfig())
                    val audit = runCatching { JSONObject(root.getAuditTimelinePage(0, 100)) }.getOrDefault(JSONObject())
                    config to audit.optJSONObject("advisor")
                }
            }
            result.onSuccess { (json, advisorJson) ->
                val policy = CleanupPolicy.fromId(json.optInt("cleanup_policy", CleanupPolicy.BALANCED.id))
                state = state.copy(
                    connected = true,
                    loading = false,
                    activePolicy = policy,
                    customized = json.optBoolean("cleanup_policy_customized", false),
                    maxFileMb = json.optInt("max_file_mb", policy.values.getValue("max_file_mb")),
                    fragmentDays = json.optInt("fragment_days", policy.values.getValue("fragment_days")),
                    quarantineDays = json.optInt("quarantine_retention_days", policy.values.getValue("quarantine_retention_days")),
                    advice = parseAdvice(advisorJson),
                    message = if (json.optBoolean("cleanup_policy_customized", false)) {
                        "当前基于${policy.title}档，并包含手动调整"
                    } else {
                        "当前使用${policy.title}档"
                    }
                )
            }.onFailure {
                state = state.copy(loading = false, message = "读取策略失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun parseAdvice(raw: JSONObject?): PolicyAdvice? {
        if (raw == null || !raw.optBoolean("available", false)) return null
        val reasonsJson = raw.optJSONArray("reasons")
        val reasons = buildList {
            if (reasonsJson != null) for (index in 0 until reasonsJson.length()) {
                reasonsJson.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        return PolicyAdvice(
            recommendedPolicy = CleanupPolicy.fromId(raw.optInt("recommendedPolicyId", CleanupPolicy.BALANCED.id)),
            summary = raw.optString("summary", "暂时没有策略建议"),
            confidence = raw.optString("confidence", "low"),
            storageFreePercent = raw.optInt("storageFreePercent", -1),
            failureRate = raw.optInt("failureRate").coerceIn(0, 100),
            restoreRate = raw.optInt("restoreRate").coerceIn(0, 100),
            protectionRate = raw.optInt("protectionRate").coerceIn(0, 100),
            averageScanMs = raw.optLong("averageScanMs").coerceAtLeast(0L),
            sampleCount = raw.optInt("sampleCount").coerceAtLeast(0),
            reasons = reasons,
            automatic = raw.optBoolean("automatic", false),
            scheduleUntouched = raw.optBoolean("scheduleUntouched", true)
        )
    }

    private fun applyPolicy(policy: CleanupPolicy) {
        val root = service ?: return
        if (state.loading) return
        state = state.copy(loading = true, message = "正在应用${policy.title}档…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    JSONObject(root.saveSchedulerConfig(JSONObject().put("cleanup_policy", policy.id).toString()))
                }
            }
            result.onSuccess { json ->
                if (json.optBoolean("success")) {
                    state = state.copy(
                        loading = false,
                        activePolicy = policy,
                        customized = false,
                        message = "${policy.title}档已生效；定时任务周期保持不变"
                    )
                    loadPolicy()
                } else {
                    state = state.copy(loading = false, message = "应用失败：${json.optString("message", json.optString("error"))}")
                }
            }.onFailure {
                state = state.copy(loading = false, message = "应用失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }
}

internal data class CleanupPolicyUiState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val activePolicy: CleanupPolicy = CleanupPolicy.BALANCED,
    val customized: Boolean = false,
    val maxFileMb: Int = 256,
    val fragmentDays: Int = 7,
    val quarantineDays: Int = 7,
    val advice: PolicyAdvice? = null,
    val message: String = "等待连接 Root 策略服务"
)

internal data class PolicyAdvice(
    val recommendedPolicy: CleanupPolicy,
    val summary: String,
    val confidence: String,
    val storageFreePercent: Int,
    val failureRate: Int,
    val restoreRate: Int,
    val protectionRate: Int,
    val averageScanMs: Long,
    val sampleCount: Int,
    val reasons: List<String>,
    val automatic: Boolean,
    val scheduleUntouched: Boolean
)

@Composable
internal fun CleanupPolicyScreen(
    state: CleanupPolicyUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (CleanupPolicy) -> Unit
) {
    var selectedId by rememberSaveable(state.activePolicy.id, state.customized) {
        mutableIntStateOf(state.activePolicy.id)
    }
    val selected = CleanupPolicy.fromId(selectedId)
    val pending = selected != state.activePolicy || state.customized
    val enabled = state.connected && !state.loading

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BaiZeTokens.colors.surfaceBase),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            DetailPageHeader("清理策略", "", onBack) {
                IconButton(onClick = onRefresh, enabled = enabled, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新", modifier = Modifier.size(22.dp))
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Icon(Icons.Rounded.Tune, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(if (state.connected) "正在使用${state.activePolicy.title}档" else "尚未读取策略",
                        fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
                    if (state.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                if (state.customized) Text("已包含自定义调整", Modifier.padding(top = 5.dp),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.connected) {
                    Row(Modifier.fillMaxWidth().padding(top = 17.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PolicyMetric("单文件上限", "${state.maxFileMb} MB", Modifier.weight(1f))
                        PolicyMetric("碎片保留", "${state.fragmentDays} 天", Modifier.weight(1f))
                        PolicyMetric("隔离保留", "${state.quarantineDays} 天", Modifier.weight(1f))
                    }
                }
                DetailStatusText(state.message, Modifier.padding(top = 12.dp))
            }
        }
        item { DetailSectionHeader("选择清理强度", "选择后应用，自动清理时间保持不变") }
        item {
            DetailGlassPanel {
                CleanupPolicy.entries.forEachIndexed { index, policy ->
                    PolicyChoiceRow(policy, selected == policy, enabled) { selectedId = policy.id }
                    if (index != CleanupPolicy.entries.lastIndex) HorizontalDivider(
                        Modifier.padding(start = 4.dp, end = 4.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .055f)
                    )
                }
            }
        }
        item {
            GlassActionButton(
                label = when {
                    !state.connected -> "连接后可应用策略"
                    state.loading -> "正在读取或应用…"
                    pending -> "应用${selected.title}档"
                    else -> "${selected.title}档已生效"
                },
                onClick = { onSelect(selected) },
                enabled = enabled && pending,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp)
            )
        }
        item { DetailExpandableText("${selected.title}档的清理范围", selected.highlights.joinToString("\n\n")) }
        state.advice?.let { advice ->
            item { DetailSectionHeader("设备建议") }
            item {
                PolicyAdvicePanel(
                    advice = advice,
                    matches = advice.recommendedPolicy == state.activePolicy && !state.customized,
                    enabled = enabled,
                    onApply = { onSelect(advice.recommendedPolicy) }
                )
            }
        }
        item {
            DetailExpandableText("始终保留的保护",
                "白名单与关键路径始终受保护，清理时会重新核对文件、挂载点和软链接。\n\n高风险内容不会被普通清理直接删除；需要逐项确认后移入隔离区。关键风险仅供查看，不会处理。\n\n应用档位会替换清理范围、保留时间等自定义参数。自动清理的执行时间与周期由清理计划单独管理。")
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
private fun PolicyChoiceRow(
    policy: CleanupPolicy,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(policy.title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                if (policy == CleanupPolicy.BALANCED) Text("推荐", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text(policy.subtitle, fontSize = 12.sp, lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun PolicyAdvicePanel(
    advice: PolicyAdvice,
    matches: Boolean,
    enabled: Boolean,
    onApply: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val confidence = when (advice.confidence) {
        "high" -> "高可信"
        "medium" -> "中等可信"
        else -> "数据较少"
    }
    DetailGlassPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("建议使用${advice.recommendedPolicy.title}档", Modifier.weight(1f),
                fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
            Text(confidence, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DetailStatusText(advice.summary, Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { expanded = !expanded }
            .heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (expanded) "收起建议依据" else "查看建议依据", Modifier.weight(1f),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            Icon(if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("基于最近 30 天的 ${advice.sampleCount} 条有效记录；建议不会自动应用。",
                    fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PolicyMetric("可用空间", if (advice.storageFreePercent < 0) "未知" else "${advice.storageFreePercent}%", Modifier.weight(1f))
                    PolicyMetric("任务异常", "${advice.failureRate}%", Modifier.weight(1f))
                    PolicyMetric("隔离恢复", "${advice.restoreRate}%", Modifier.weight(1f))
                }
                advice.reasons.forEach { reason ->
                    Text(reason, fontSize = 12.sp, lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(2.dp))
            }
        }
        if (!matches) GlassActionButton("采用建议的${advice.recommendedPolicy.title}档", onApply,
            enabled = enabled, secondary = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        else Text("当前策略与建议一致", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PolicyMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 21.sp)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp)
    }
}
