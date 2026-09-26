package io.github.xgl34222220.baize

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.*
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

class ToolboxActivity : ComponentActivity() {
    private val appearance: AppearanceViewModel by viewModels()
    private val model: ToolboxViewModel by viewModels()
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.export { value -> contentResolver.openOutputStream(uri)?.use { it.write(value.toByteArray()) } }
    }
    private val importConfig = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.import { contentResolver.openInputStream(uri)?.use { input ->
            val bytes = ByteArray(128001); val output = java.io.ByteArrayOutputStream()
            while (true) { val n = input.read(bytes); if (n < 0) break; require(output.size() + n <= 128000) { "配置文件过大" }; output.write(bytes, 0, n) }
            output.toString("UTF-8")
        }.orEmpty() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        model.bind()
        setContent {
            val settings by appearance.settings.collectAsState()
            BaiZeTheme(settings) {
                CompositionLocalProvider(LocalAppearanceSettings provides settings) {
                    ToolboxScreen(model, ::finish,
                        onWhitelist = { startActivity(Intent(this, WhitelistActivity::class.java)) },
                        onAppearance = { startActivity(Intent(this, ThemeSettingsActivity::class.java)) },
                        onExport = { export.launch("BaiZe-2.0.0-功能配置.json") },
                        onImport = { importConfig.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        onNotification = { if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) })
                }
            }
        }
    }
}

class ToolboxViewModel(application: Application) : AndroidViewModel(application) {
    var snapshot by mutableStateOf(JSONObject().put("config", ToolboxConfig.normalize(JSONObject()))); private set
    var connected by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf("正在连接 Root 服务…"); private set
    var packages by mutableStateOf<List<Pair<String, String>>>(emptyList()); private set
    private var remote: IProfileRootService? = null
    private var polling: Job? = null
    private var binding = false
    private var lastResultId = ""
    private val context get() = getApplication<Application>()
    val running get() = snapshot.optJSONObject("state")?.optBoolean("running") == true
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = RootServiceClients.profile(binder, context.cacheDir)
            connected = true; message = "已连接"; binding = false
            polling?.cancel()
            polling = viewModelScope.launch {
                while (connected) { refresh(); delay(if (running) 1000 else 5000) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { connected = false; remote = null; message = "Root 连接已断开，点击重连" }
        override fun onBindingDied(name: ComponentName?) { onServiceDisconnected(name); binding = false }
        override fun onNullBinding(name: ComponentName?) { onServiceDisconnected(name); binding = false }
    }
    fun bind() {
        if (connected || binding) return
        binding = true
        runCatching { RootService.bind(Intent(context, BaiZeProfileRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE), connection) }
            .onFailure { binding = false; message = it.message.orEmpty() }
        viewModelScope.launch { delay(20000); if (!connected) { binding = false; message = "Root 连接超时，请检查授权并重连" } }
        FileOrganizerWorker.ensureWatchdog(context)
    }
    private suspend fun call(operation: String, args: JSONArray = JSONArray()): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(RootServiceClients.profileExchange(requireNotNull(remote) { "Root 尚未连接" }, context.cacheDir, operation, args))
    }
    private suspend fun refresh() {
        runCatching { call("toolboxSnapshot") }.onSuccess {
            if (it.optBoolean("success")) {
                snapshot = it
                val result = it.optJSONObject("state")?.optJSONObject("result")
                if (result != null && result.optString("taskId") != lastResultId) {
                    lastResultId = result.optString("taskId")
                    message = result.optString("message", "任务已结束")
                }
                ToolboxNotifications.show(context, it)
            }
            else message = it.optString("message", "读取失败")
        }.onFailure { message = it.message.orEmpty() }
    }
    fun action(operation: String, argument: String? = null) {
        if (busy || !connected) return
        busy = true
        viewModelScope.launch {
            try {
                val result = call(operation, JSONArray().apply { if (argument != null) put(argument) })
                message = result.optString("message", if (result.optBoolean("success")) "已完成" else "操作失败")
                refresh()
            } catch (e: Exception) { message = e.message.orEmpty() }
            finally { busy = false }
        }
    }
    fun save(change: (JSONObject) -> Unit) {
        val config = JSONObject((snapshot.optJSONObject("config") ?: return).toString())
        change(config); action("toolboxSave", config.toString())
    }
    fun loadPackages() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                context.packageManager.getInstalledApplications(0).filter { it.packageName != context.packageName &&
                    it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 && it.uid % 100000 >= 10000 }
                    .map { it.packageName to context.packageManager.getApplicationLabel(it).toString() }.sortedBy { it.second }
            } }.onSuccess { packages = it }
        }
    }
    fun export(write: (String) -> Unit) {
        val config = snapshot.optJSONObject("config")?.toString(2) ?: return
        viewModelScope.launch { message = withContext(Dispatchers.IO) { runCatching { write(config); "配置已导出" }.getOrElse { it.message.orEmpty() } } }
    }
    fun import(read: () -> String) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { read() } }.onSuccess { action("toolboxSave", it) }.onFailure { message = it.message.orEmpty() }
        }
    }
    override fun onCleared() { polling?.cancel(); runCatching { RootService.unbind(connection) }; super.onCleared() }
}

internal object ToolboxNotifications {
    fun show(context: Context, snapshot: JSONObject) {
        if (snapshot.optJSONObject("config")?.optBoolean("notifications", true) == false) return
        val result = snapshot.optJSONObject("state")?.optJSONObject("result") ?: return
        val id = result.optString("taskId"); if (id.isBlank()) return
        val prefs = context.getSharedPreferences("toolbox-notifications", Context.MODE_PRIVATE)
        if (prefs.getString("last", "") == id) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("toolbox-results", "功能任务结果", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(context, 302, Intent(context, ToolboxActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching { manager.notify(302, NotificationCompat.Builder(context, "toolbox-results").setSmallIcon(R.mipmap.ic_baize)
            .setContentTitle("白泽功能任务结果").setContentText(result.optString("message")).setContentIntent(pending).setAutoCancel(true).build()) }
            .onSuccess { prefs.edit().putString("last", id).apply() }
    }
}
