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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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

class CleanupPolicyActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private var service: IProfileRootService? = null
    private var bound = false
    private var state by mutableStateOf(CleanupPolicyUiState())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IProfileRootService.Stub.asInterface(binder)
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
                        miuix = appearance.uiStyle == UiStyle.MIUIX,
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
        state = state.copy(loading = true, message = "正在读取当前清理策略…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { JSONObject(root.getSchedulerConfig()) }
            }
            result.onSuccess { json ->
                val policy = CleanupPolicy.fromId(json.optInt("cleanup_policy", CleanupPolicy.BALANCED.id))
                state = state.copy(
                    connected = true,
                    loading = false,
                    activePolicy = policy,
                    customized = json.optBoolean("cleanup_policy_customized", false),
                    maxFileMb = json.optInt("max_file_mb", policy.values.getValue("max_file_mb")),
                    fragmentDays = json.optInt("fragment_days", policy.values.getValue("fragment_days")),
                    quarantineDays = json.optInt("quarantine_retention_days", policy.values.getValue("quarantine_retention_days")),
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

private data class CleanupPolicyUiState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val activePolicy: CleanupPolicy = CleanupPolicy.BALANCED,
    val customized: Boolean = false,
    val maxFileMb: Int = 256,
    val fragmentDays: Int = 7,
    val quarantineDays: Int = 7,
    val message: String = "等待连接 Root 策略服务"
)

@Composable
private fun CleanupPolicyScreen(
    miuix: Boolean,
    state: CleanupPolicyUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (CleanupPolicy) -> Unit
) {
    val horizontal = if (miuix) 18.dp else 20.dp
    val cardShape = if (miuix) RoundedCornerShape(28.dp) else MaterialTheme.shapes.extraLarge
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "返回") }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text("清理策略", fontSize = if (miuix) 30.sp else 27.sp, fontWeight = FontWeight.Black)
                    Text("三档预设只改变清理范围，不改变定时周期", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                IconButton(onClick = onRefresh, enabled = state.connected && !state.loading) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                }
            }
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
                shape = cardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(50.dp),
                            shape = RoundedCornerShape(17.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text("当前：${state.activePolicy.title}档", fontSize = 21.sp, fontWeight = FontWeight.Black)
                            Text(
                                if (state.customized) "包含手动参数调整" else state.activePolicy.subtitle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        if (!state.customized) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = BaiZeTokens.colors.success)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PolicyMetric("单文件", "${state.maxFileMb} MB", Modifier.weight(1f))
                        PolicyMetric("碎片保留", "${state.fragmentDays} 天", Modifier.weight(1f))
                        PolicyMetric("隔离保留", "${state.quarantineDays} 天", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    if (state.loading) {
                        Spacer(Modifier.height(10.dp))
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = horizontal, vertical = 2.dp)) {
                Text("选择档位", style = MaterialTheme.typography.titleLarge)
                Text("应用档位会覆盖清理相关参数，但不会修改任何 schedule_* 周期字段", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        CleanupPolicy.entries.forEach { policy ->
            item(key = policy.key) {
                PolicyCard(
                    policy = policy,
                    active = state.activePolicy == policy && !state.customized,
                    enabled = state.connected && !state.loading,
                    shape = cardShape,
                    horizontal = horizontal,
                    onSelect = { onSelect(policy) }
                )
            }
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth().navigationBarsPadding(),
                shape = cardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(9.dp))
                        Text("所有档位都无法关闭的保护", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "白名单、服务器端快照、挂载点检查、软链接防护、关键路径保护始终生效。高风险不会被普通清理直接删除，关键风险始终只审计。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PolicyCard(
    policy: CleanupPolicy,
    active: Boolean,
    enabled: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
    horizontal: androidx.compose.ui.unit.Dp,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .padding(horizontal = horizontal)
            .fillMaxWidth()
            .clip(shape)
            .clickable(enabled = enabled && !active, onClick = onSelect),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(43.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = if (active) MaterialTheme.colorScheme.secondary.copy(alpha = .16f) else MaterialTheme.colorScheme.primary.copy(alpha = .10f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (policy == CleanupPolicy.CONSERVATIVE) Icons.Rounded.Security else Icons.Rounded.CleaningServices,
                            contentDescription = null,
                            tint = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(policy.title, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = .11f)) {
                            Text(policy.badge, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = MaterialTheme.colorScheme.primary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(policy.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                if (active) Icon(Icons.Rounded.CheckCircle, contentDescription = "当前档位", tint = BaiZeTokens.colors.success)
            }
            Spacer(Modifier.height(13.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
            Spacer(Modifier.height(9.dp))
            policy.highlights.forEach { line ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp).size(5.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Spacer(Modifier.width(9.dp))
                    Text(line, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
            if (!active) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onSelect, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp)) {
                    Text("应用${policy.title}档", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PolicyMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .55f)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
            Text(value, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}
