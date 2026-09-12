package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
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
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ceil

class ProfileActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private val profile by lazy { intent.getStringExtra(EXTRA_PROFILE).orEmpty() }
    private val preferences by lazy { getSharedPreferences("baize_v2", MODE_PRIVATE) }

    private var service: IProfileRootService? = null
    private var bindingRequested = false
    private var taskRunning = false
    private var snapshotId = ""
    private var total = 0
    private var page = 0
    private var quickCleanReady = false
    private var pollJob: Job? = null
    private var screenState by mutableStateOf(ProfileUiState())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IProfileRootService.Stub.asInterface(binder)
            bindingRequested = true
            screenState = screenState.copy(connected = true)
            renderConnected()
            recoverRemoteOrLatestState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bindingRequested = false
            taskRunning = false
            pollJob?.cancel()
            screenState = screenState.copy(
                connected = false,
                running = false,
                loadingPage = false,
                serviceText = "Root 服务已断开",
                summaryText = "服务连接已断开，请返回后重新进入"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (profile !in SUPPORTED_PROFILES) {
            finish()
            return
        }

        screenState = initialState(profile)
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
                    ProfileRoute(
                        appearance = appearance,
                        state = screenState,
                        actions = ProfileUiActions(
                            onBack = ::finish,
                            onScan = ::scan,
                            onStop = ::stopTask,
                            onClean = ::quickClean,
                            onPrevious = { loadPage(page - 1) },
                            onNext = { loadPage(page + 1) }
                        )
                    )
                }
            }
        }
        connect()
    }

    override fun onResume() {
        super.onResume()
        if (service != null && !taskRunning) recoverRemoteOrLatestState()
    }

    private fun rootIntent(): Intent = Intent(this, BaiZeProfileRootService::class.java)
        .addCategory(RootService.CATEGORY_DAEMON_MODE)

    private fun connect() {
        screenState = screenState.copy(serviceText = "正在连接 Root 原生清理引擎")
        runCatching {
            RootService.bind(rootIntent(), connection)
            bindingRequested = true
        }.onFailure {
            screenState = screenState.copy(
                connected = false,
                serviceText = it.message ?: "Root 服务启动失败",
                summaryText = "无法启动 Root 清理服务"
            )
        }
    }

    private fun renderConnected() {
        lifecycleScope.launch {
            val raw = runCatching { withContext(Dispatchers.IO) { service?.ping().orEmpty() } }.getOrNull()
            val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            val root = json?.optBoolean("root") == true
            val rules = json?.optBoolean("deepRules") == true
            screenState = screenState.copy(
                connected = root,
                serviceText = when {
                    !root -> "服务已连接，但未获得完整 Root"
                    profile == "deep" && !rules -> "Root 已连接，但完整规则库缺失"
                    else -> "Root 扫描与一键清理引擎已连接"
                }
            )
        }
    }

    private fun scan() {
        if (taskRunning) {
            screenState = screenState.copy(summaryText = "当前任务仍在执行，请先停止或等待完成")
            return
        }
        if (service == null) {
            screenState = screenState.copy(summaryText = "Root 服务尚未连接，正在重新连接…")
            connect()
            return
        }
        if (requiresModuleAuthorization()) runAuthorizedModuleScan() else runNativeDetailScan()
    }

    private fun runAuthorizedModuleScan() {
        val root = service ?: return
        taskRunning = true
        quickCleanReady = false
        snapshotId = ""
        total = 0
        page = 0
        screenState = screenState.copy(
            running = true,
            loadingPage = false,
            summaryText = "正在扫描${profileTitle(profile)}并生成安全授权…",
            quickCleanReady = false,
            cleanButtonText = "扫描后可一键清理",
            selectionText = "扫描完成后会自动恢复安全清理授权",
            items = emptyList(),
            total = 0,
            page = 0,
            pageCount = 1,
            showCandidates = false
        )
        startPolling()

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { root.runModuleTask(scanMode(profile)) }
            }
            taskRunning = false
            pollJob?.cancel()
            screenState = screenState.copy(running = false)
            result.onSuccess { raw ->
                val json = JSONObject(raw)
                if (json.optString("error") == "busy") {
                    screenState = screenState.copy(summaryText = "检测到后台任务，正在恢复执行状态…")
                    recoverRemoteOrLatestState()
                    return@onSuccess
                }
                val output = json.optString("output").lineSequence().filter { it.isNotBlank() }.takeLast(6).joinToString("\n")
                val success = json.optBoolean("success")
                val latest = json.optJSONObject("latest") ?: JSONObject()
                val discovered = latest.optLong("files", latest.optLong("regular_files", 0L)).coerceAtLeast(0L)
                quickCleanReady = success && !json.optBoolean("cancelled") && discovered > 0L
                total = discovered.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                screenState = screenState.copy(
                    summaryText = buildString {
                        append(
                            when {
                                json.optBoolean("cancelled") -> "扫描已停止"
                                success -> "${profileTitle(profile)}扫描完成"
                                else -> json.optString("message", "扫描失败")
                            }
                        )
                        append(" · ${json.optLong("elapsedMs")}ms")
                        if (output.isNotBlank()) append("\n").append(output)
                        if (quickCleanReady) append("\n发现 $discovered 项安全内容，可直接一键清理。")
                        else if (success && discovered == 0L) append("\n没有发现可清理的安全项目。")
                    },
                    quickCleanReady = quickCleanReady,
                    cleanButtonText = if (quickCleanReady) quickCleanLabel(profile, total) else "扫描后可一键清理",
                    selectionText = when (profile) {
                        "deep" -> "低风险与允许的中风险规则已准备；关键风险永远只审计。"
                        "corpses" -> "已核对当前安装包列表，确认的卸载残留可一键清理。"
                        else -> "安全扫描已完成。"
                    },
                    total = total,
                    showCandidates = false,
                    items = emptyList()
                )
            }.onFailure {
                screenState = screenState.copy(summaryText = "扫描失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun runNativeDetailScan() {
        val root = service ?: return
        taskRunning = true
        quickCleanReady = false
        snapshotId = ""
        total = 0
        page = 0
        screenState = screenState.copy(
            running = true,
            loadingPage = false,
            summaryText = "正在扫描${profileTitle(profile)}…",
            quickCleanReady = false,
            cleanButtonText = "扫描后可一键清理",
            selectionText = "扫描后自动选择全部安全项",
            items = emptyList(),
            total = 0,
            page = 0,
            pageCount = 1,
            showCandidates = false
        )
        startPolling()

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { root.scanProfile(profile, optionsJson(false)) }
            }
            taskRunning = false
            pollJob?.cancel()
            screenState = screenState.copy(running = false)
            result.onSuccess { raw ->
                val json = JSONObject(raw)
                if (json.has("error")) {
                    screenState = screenState.copy(summaryText = json.optString("message", "扫描失败"))
                    return@onSuccess
                }
                if (json.optBoolean("cancelled")) {
                    screenState = screenState.copy(summaryText = "扫描已停止 · ${json.optLong("elapsedMs")}ms")
                    return@onSuccess
                }
                snapshotId = json.optString("snapshotId")
                total = json.optInt("totalCandidates")
                quickCleanReady = total > 0
                val pages = pageCount()
                screenState = screenState.copy(
                    summaryText = buildString {
                        append("扫描完成 · ${json.optLong("elapsedMs")}ms\n")
                        append("发现 $total 项可处理内容")
                        val low = json.optInt("low")
                        val medium = json.optInt("medium")
                        if (low > 0) append(" · 低风险 $low")
                        if (medium > 0) append(" · 中风险 $medium")
                        append("\n已自动选择全部安全项，无需逐项勾选。")
                    },
                    quickCleanReady = quickCleanReady,
                    cleanButtonText = if (quickCleanReady) quickCleanLabel(profile, total) else "扫描后可一键清理",
                    selectionText = if (total > 0) "全部安全项已自动纳入本次清理；列表仅用于查看路径与大小。" else "没有发现可清理项目",
                    total = total,
                    pageCount = pages,
                    showCandidates = total > 0
                )
                if (total > 0) loadPage(0)
            }.onFailure {
                screenState = screenState.copy(summaryText = "扫描失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun loadPage(targetPage: Int) {
        val root = service ?: return
        if (snapshotId.isBlank() || taskRunning) return
        val pages = pageCount()
        if (targetPage !in 0 until pages) return
        screenState = screenState.copy(loadingPage = true)

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { root.getProfilePage(snapshotId, targetPage * PAGE_SIZE, PAGE_SIZE) }
            }
            result.onSuccess { raw ->
                val json = JSONObject(raw)
                if (json.has("error")) {
                    screenState = screenState.copy(
                        loadingPage = false,
                        selectionText = json.optString("message", "读取结果失败")
                    )
                    return@onSuccess
                }
                val array = json.optJSONArray("items") ?: JSONArray()
                val values = ArrayList<ProfileUiItem>(array.length())
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val risk = item.optString("risk", "low")
                    values += ProfileUiItem(
                        id = item.optString("id"),
                        appName = item.optString("appName", item.optString("categoryLabel")),
                        packageName = item.optString("packageName"),
                        categoryLabel = item.optString("categoryLabel", "清理项目"),
                        risk = risk,
                        path = item.optString("path"),
                        bytes = item.optLong("bytes", -1L),
                        files = item.optLong("files", -1L),
                        directories = item.optLong("directories", -1L),
                        measured = item.optBoolean("measured"),
                        complete = item.optBoolean("complete"),
                        note = if (risk == "critical") "仅审计" else "已自动选择"
                    )
                }
                page = targetPage
                screenState = screenState.copy(
                    loadingPage = false,
                    items = values,
                    page = page,
                    pageCount = pages,
                    selectionText = "共 $total 项 · 当前页 ${values.size} 项 · 安全项已自动选择"
                )
            }.onFailure {
                screenState = screenState.copy(
                    loadingPage = false,
                    selectionText = "读取结果失败：${it.message ?: it.javaClass.simpleName}"
                )
            }
        }
    }

    private fun stopTask() {
        if (!taskRunning) {
            screenState = screenState.copy(summaryText = "当前没有正在运行的任务")
            return
        }
        service?.cancelCurrentTask()
        screenState = screenState.copy(summaryText = "正在安全停止当前任务…")
    }

    private fun quickClean() {
        if (taskRunning) {
            screenState = screenState.copy(summaryText = "当前任务仍在执行，请先停止或等待完成")
            return
        }
        if (!quickCleanReady) {
            screenState = screenState.copy(summaryText = "没有可用的扫描快照，请先完成扫描")
            return
        }
        val root = service
        if (root == null) {
            screenState = screenState.copy(summaryText = "Root 服务尚未连接，正在重新连接…")
            connect()
            return
        }
        taskRunning = true
        screenState = screenState.copy(
            running = true,
            summaryText = "正在一键清理${profileTitle(profile)}…"
        )
        startPolling()

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { root.runModuleTask(cleanMode(profile)) }
            }
            taskRunning = false
            pollJob?.cancel()
            screenState = screenState.copy(running = false)
            result.onSuccess { raw ->
                val json = JSONObject(raw)
                if (json.optString("error") == "busy") {
                    screenState = screenState.copy(summaryText = "检测到后台任务，正在恢复执行状态…")
                    recoverRemoteOrLatestState()
                    return@onSuccess
                }
                val output = json.optString("output").lineSequence().filter { it.isNotBlank() }.takeLast(6).joinToString("\n")
                val report = buildString {
                    append(
                        when {
                            json.optBoolean("cancelled") -> "任务已停止"
                            json.optBoolean("success") -> "${profileTitle(profile)}一键清理完成"
                            else -> json.optString("message", "清理失败")
                        }
                    )
                    append(" · ${json.optLong("elapsedMs")}ms")
                    if (output.isNotBlank()) append("\n").append(output)
                }
                preferences.edit().putString("last_report_text", report).apply()
                NativeNotifier.showTaskResult(
                    this@ProfileActivity,
                    if (json.optBoolean("success")) "白泽${profileTitle(profile)}清理完成" else "白泽清理任务结束",
                    json.optString("message", "${profileTitle(profile)}任务已结束"),
                    report
                )
                quickCleanReady = false
                snapshotId = ""
                total = 0
                page = 0
                screenState = screenState.copy(
                    summaryText = report,
                    quickCleanReady = false,
                    cleanButtonText = "扫描后可一键清理",
                    selectionText = "清理完成，可重新扫描确认剩余项目",
                    items = emptyList(),
                    total = 0,
                    page = 0,
                    pageCount = 1,
                    showCandidates = false
                )
            }.onFailure {
                screenState = screenState.copy(summaryText = "清理失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun recoverRemoteOrLatestState() {
        val root = service ?: return
        if (!requiresModuleAuthorization() || taskRunning) return
        lifecycleScope.launch {
            val task = runCatching {
                withContext(Dispatchers.IO) { JSONObject(root.getTaskState()) }
            }.getOrNull()
            if (task?.optBoolean("running") == true) {
                taskRunning = true
                quickCleanReady = false
                screenState = screenState.copy(
                    running = true,
                    quickCleanReady = false,
                    items = emptyList(),
                    showCandidates = false
                )
                renderRemoteTaskState(task)
                startRecoveryPolling()
            } else {
                restoreAuthorizedScanResult()
            }
        }
    }

    private fun startRecoveryPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive && taskRunning) {
                val task = runCatching {
                    withContext(Dispatchers.IO) { service?.getTaskState()?.let(::JSONObject) }
                }.getOrNull()
                if (task?.optBoolean("running") == true) {
                    renderRemoteTaskState(task)
                    delay(500L)
                    continue
                }
                taskRunning = false
                screenState = screenState.copy(running = false)
                restoreAuthorizedScanResult()
                break
            }
        }
    }

    private fun renderRemoteTaskState(json: JSONObject) {
        screenState = screenState.copy(
            running = true,
            summaryText = buildString {
                append(json.optString("phase", "后台任务正在执行"))
                val current = json.optInt("progress_current", json.optInt("current"))
                val totalState = json.optInt("progress_total", json.optInt("total"))
                if (totalState > 0) append(" · $current/$totalState")
                val path = json.optString("current_path", json.optString("currentPath"))
                if (path.isNotBlank()) append("\n").append(path.takeLast(92))
                if (json.optBoolean("cancelRequested")) append("\n正在安全停止…")
            }
        )
    }

    private suspend fun restoreAuthorizedScanResult() {
        if (!requiresModuleAuthorization()) return
        val root = service ?: return
        val state = runCatching {
            withContext(Dispatchers.IO) { JSONObject(root.getModuleState()) }
        }.getOrNull() ?: return
        val latest = state.optJSONObject("latest") ?: return
        if (latest.optString("mode") != scanMode(profile)) return

        val files = latest.optLong("files", latest.optLong("regular_files", 0L)).coerceAtLeast(0L)
        val errors = latest.optLong("errors", 0L).coerceAtLeast(0L)
        val result = latest.optString("result").trim()
        quickCleanReady = files > 0L
        total = files.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        screenState = screenState.copy(
            running = false,
            loadingPage = false,
            quickCleanReady = quickCleanReady,
            cleanButtonText = if (quickCleanReady) quickCleanLabel(profile, total) else "扫描后可一键清理",
            selectionText = when (profile) {
                "deep" -> "已恢复最近一次深度扫描授权；只会清理低风险与允许的中风险项目。"
                "corpses" -> "已恢复最近一次卸载残留扫描授权；删除前会再次核对安装状态。"
                else -> "已恢复最近一次安全扫描结果。"
            },
            summaryText = buildString {
                append("已恢复最近一次${profileTitle(profile)}扫描结果")
                if (files > 0L) append("\n发现 $files 项，可直接一键清理") else append("\n没有发现可清理项目")
                if (errors > 0L) append(" · 异常 $errors")
                if (result.isNotBlank()) append("\n").append(result)
            },
            items = emptyList(),
            total = total,
            page = 0,
            pageCount = 1,
            showCandidates = false
        )
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive && taskRunning) {
                val raw = runCatching { withContext(Dispatchers.IO) { service?.getTaskState().orEmpty() } }.getOrNull()
                if (!raw.isNullOrBlank()) {
                    val json = runCatching { JSONObject(raw) }.getOrNull()
                    if (json != null && json.optBoolean("running")) renderRemoteTaskState(json)
                }
                delay(400L)
            }
        }
    }

    private fun optionsJson(allowHighRisk: Boolean): String {
        val whitelist = preferences.getStringSet("package_whitelist", emptySet()).orEmpty()
        val pathWhitelist = preferences.getStringSet("path_whitelist", emptySet()).orEmpty()
        val maxMb = preferences.getFloat("large_file_mb", 512f).toLong().coerceIn(64L, 16_384L)
        val fragmentDays = preferences.getInt("fragment_days", 7).coerceIn(1, 365)
        return JSONObject()
            .put("whitelistPackages", JSONArray(whitelist.toList()))
            .put("whitelistPaths", JSONArray(pathWhitelist.toList()))
            .put("maxFileBytes", maxMb * 1024L * 1024L)
            .put("fragmentDays", fragmentDays)
            .put("allowHighRisk", allowHighRisk)
            .toString()
    }

    private fun requiresModuleAuthorization(): Boolean = profile == "deep" || profile == "corpses"

    private fun scanMode(profile: String): String = when (profile) {
        "deep" -> "deep-scan"
        "corpses" -> "corpse-scan"
        else -> "scan"
    }

    private fun cleanMode(profile: String): String = when (profile) {
        "empty" -> "empty-clean"
        "rules" -> "rules-clean"
        "fragments" -> "fragment-clean"
        "deep" -> "deep-clean"
        "corpses" -> "corpse-clean"
        else -> "clean"
    }

    private fun pageCount(): Int = ceil(total / PAGE_SIZE.toDouble()).toInt().coerceAtLeast(1)

    override fun onDestroy() {
        pollJob?.cancel()
        if (bindingRequested) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
        private const val PAGE_SIZE = 30
        private val SUPPORTED_PROFILES = setOf("empty", "rules", "fragments", "deep", "corpses")

        fun profileTitle(profile: String): String = when (profile) {
            "empty" -> "空项目"
            "rules" -> "规则垃圾"
            "fragments" -> "残留碎片"
            "deep" -> "深度清理"
            "corpses" -> "卸载残留"
            else -> "清理项目"
        }

        fun profileSubtitle(profile: String): String = when (profile) {
            "empty" -> "扫描空文件与空目录，自动保护占位文件和常用媒体目录"
            "rules" -> "清理隐藏垃圾、系统/OEM 日志与扩展规则路径"
            "fragments" -> "清理超过保留期的临时文件、旋转日志和中断下载"
            "deep" -> "使用 4,714 条有效规则扫描，安全项目可一键清理"
            "corpses" -> "清理已卸载应用留在 Android/data 与 Android/obb 的目录"
            else -> ""
        }

        private fun safetyDescription(profile: String): String = when (profile) {
            "deep" -> "只需扫描一次，随后可一键清理安全规则；关键风险永远只审计，高风险不会混入普通自动清理。"
            "corpses" -> "只需扫描一次，随后可一键清理全部确认残留；应用重新安装后会在删除前自动跳过。"
            else -> "扫描后会自动选择全部安全项，无需逐项勾选；列表只用于查看明细，删除前仍会进行完整安全校验。"
        }

        private fun quickCleanLabel(profile: String, count: Int = 0): String {
            val suffix = if (count > 0) "（$count 项）" else ""
            return when (profile) {
                "empty" -> "一键清理全部空项目$suffix"
                "rules" -> "一键清理全部安全规则$suffix"
                "fragments" -> "一键清理全部过期碎片$suffix"
                "deep" -> "一键清理深度安全项"
                "corpses" -> "一键清理确认的卸载残留"
                else -> "一键清理全部安全项$suffix"
            }
        }

        private fun confirmMessage(profile: String): String = when (profile) {
            "deep" -> "将清理最近一次深度扫描中通过授权的低风险与允许的中风险项目。关键风险永远只审计，规则变化后授权会立即失效。"
            "corpses" -> "将清理最近一次扫描确认的 Android/data 与 Android/obb 卸载残留。删除前会再次查询安装包列表，已重新安装的应用会自动跳过。"
            else -> "将自动清理本分类中全部通过二次校验的安全项。白名单、软链接、挂载点、异常路径与大文件限制仍会自动保护。"
        }

        private fun initialState(profile: String) = ProfileUiState(
            profile = profile,
            title = profileTitle(profile),
            subtitle = profileSubtitle(profile),
            safetyText = safetyDescription(profile),
            scanButtonText = if (profile == "deep" || profile == "corpses") "开始安全扫描" else "扫描清理明细",
            confirmText = confirmMessage(profile)
        )
    }
}

internal data class ProfileUiItem(
    val id: String,
    val appName: String,
    val packageName: String,
    val categoryLabel: String,
    val risk: String,
    val path: String,
    val bytes: Long,
    val files: Long,
    val directories: Long,
    val measured: Boolean,
    val complete: Boolean,
    val note: String
)

internal data class ProfileUiState(
    val profile: String = "",
    val title: String = "清理项目",
    val subtitle: String = "",
    val safetyText: String = "",
    val scanButtonText: String = "开始扫描",
    val cleanButtonText: String = "扫描后可一键清理",
    val confirmText: String = "",
    val connected: Boolean = false,
    val serviceText: String = "正在连接 Root 原生清理引擎",
    val running: Boolean = false,
    val loadingPage: Boolean = false,
    val summaryText: String = "等待开始安全扫描",
    val selectionText: String = "扫描后自动选择全部安全项",
    val quickCleanReady: Boolean = false,
    val items: List<ProfileUiItem> = emptyList(),
    val total: Int = 0,
    val page: Int = 0,
    val pageCount: Int = 1,
    val showCandidates: Boolean = false
)

internal data class ProfileUiActions(
    val onBack: () -> Unit,
    val onScan: () -> Unit,
    val onStop: () -> Unit,
    val onClean: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit
)

@Composable
private fun ProfileRoute(
    appearance: AppearanceSettings,
    state: ProfileUiState,
    actions: ProfileUiActions
) {
    var confirmClean by remember(state.profile, state.quickCleanReady) { mutableStateOf(false) }
    when (appearance.uiStyle) {
        UiStyle.MATERIAL -> ProfileScreenMaterial(state, actions, onRequestClean = { confirmClean = true })
        UiStyle.MIUIX -> ProfileScreenMiuix(appearance, state, actions, onRequestClean = { confirmClean = true })
    }

    if (confirmClean && state.quickCleanReady) {
        AlertDialog(
            onDismissRequest = { confirmClean = false },
            title = { Text(state.cleanButtonText) },
            text = { Text(state.confirmText) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClean = false
                    actions.onClean()
                }) { Text("立即清理") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClean = false }) { Text("取消") }
            }
        )
    }
}

@Composable
internal fun ProfileScreenMaterial(
    state: ProfileUiState,
    actions: ProfileUiActions,
    onRequestClean: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(BaiZeTokens.colors.surfaceBase)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item { ProfileMaterialHeader(state, actions.onBack) }
            item { ProfileMaterialTaskCard(state, actions, onRequestClean) }
            if (state.showCandidates || state.items.isNotEmpty()) {
                item { ProfileMaterialSection(state) }
                if (state.items.isEmpty() && !state.running) item {
                    DetailEmptyState("没有可清理项目", "本次扫描范围内的文件已检查完毕。")
                }
                itemsIndexed(state.items, key = { _, item -> item.id.ifBlank { item.path } }) { index, item ->
                    ProfileMaterialCandidate(item, first = index == 0, last = index == state.items.lastIndex)
                }
                item { ProfilePagination(state, actions, miuix = false) }
            }
            item { ProfileMaterialSafetyCard(state) }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

@Composable
private fun ProfileMaterialHeader(state: ProfileUiState, onBack: () -> Unit) {
    DetailPageHeader(state.title, state.subtitle, onBack)
}

@Composable
private fun ProfileMaterialTaskCard(
    state: ProfileUiState,
    actions: ProfileUiActions,
    onRequestClean: () -> Unit
) {
    DetailTaskCard(
        metric = if (state.quickCleanReady) "${state.total} 项待清理" else if (state.running) "正在处理" else "准备扫描",
        metricLabel = state.title,
        phase = state.summaryText,
        running = state.running,
        ready = state.quickCleanReady,
        scanEnabled = state.connected,
        cleanEnabled = state.connected,
        onScan = actions.onScan, onClean = onRequestClean, onStop = actions.onStop,
        onReconnect = actions.onScan,
        scanLabel = state.scanButtonText, cleanLabel = state.cleanButtonText
    )
}

@Composable
private fun ProfileMaterialSafetyCard(state: ProfileUiState) {
    DetailExpandableText("清理范围与保护设置", state.safetyText + "\n\n" + state.serviceText)
}

@Composable
private fun ProfileMaterialSection(state: ProfileUiState) {
    DetailSectionHeader("扫描明细", state.selectionText)
}

@Composable
private fun ProfileMaterialCandidate(item: ProfileUiItem, first: Boolean, last: Boolean) {
    val riskColor = when (item.risk) {
        "critical", "high" -> MaterialTheme.colorScheme.error
        "medium" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val measurement = if (item.measured) {
        "${item.files.coerceAtLeast(0L)} 个文件 · ${item.directories.coerceAtLeast(0L)} 个目录" +
            if (!item.complete) " · 统计受限" else ""
    } else "当前页按需统计大小"
    val size = if (item.measured) formatBytes(item.bytes.coerceAtLeast(0L)) else "待统计"
    DetailResultRow(
        title = item.appName.ifBlank { item.categoryLabel }, value = size,
        summary = "${item.categoryLabel} · ${riskLabel(item.risk)}", path = item.path,
        details = listOf(item.packageName, item.categoryLabel, riskLabel(item.risk), "$size · $measurement", item.note, item.path)
            .filter { it.isNotBlank() }.joinToString("\n\n"),
        icon = if (item.risk == "critical" || item.risk == "high") Icons.Rounded.Warning else Icons.Rounded.Folder,
        first = first, last = last, accent = riskColor
    )
}

@Composable
private fun ProfileScreenMiuix(
    appearance: AppearanceSettings,
    state: ProfileUiState,
    actions: ProfileUiActions,
    onRequestClean: () -> Unit
) {
    ProfileScreenMaterial(state, actions, onRequestClean)
}

@Composable
private fun ProfilePagination(state: ProfileUiState, actions: ProfileUiActions, miuix: Boolean) {
    if (!state.showCandidates || state.pageCount <= 1) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassActionButton("上一页", actions.onPrevious,
            enabled = !state.running && !state.loadingPage && state.page > 0,
            modifier = Modifier.weight(1f), secondary = true)
        Text("${state.page + 1}/${state.pageCount}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        GlassActionButton("下一页", actions.onNext,
            enabled = !state.running && !state.loadingPage && state.page + 1 < state.pageCount,
            modifier = Modifier.weight(1f), secondary = true)
    }
}

private fun riskLabel(risk: String): String = when (risk) {
    "critical" -> "仅审计"
    "high" -> "高风险"
    "medium" -> "中风险"
    else -> "低风险"
}

private fun formatBytes(value: Long): String {
    val bytes = value.coerceAtLeast(0L).toDouble()
    return when {
        bytes >= 1_073_741_824.0 -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576.0 -> String.format(Locale.US, "%.2f MB", bytes / 1_048_576.0)
        bytes >= 1024.0 -> String.format(Locale.US, "%.2f KB", bytes / 1024.0)
        else -> "${bytes.toLong()} B"
    }
}
