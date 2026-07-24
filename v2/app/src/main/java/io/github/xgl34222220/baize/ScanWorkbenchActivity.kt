package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.BaiZeRootService
import io.github.xgl34222220.baize.root.IBaiZeRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class ScanWorkbenchActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private var profileService: IProfileRootService? = null
    private var cacheService: IBaiZeRootService? = null
    private var profileBound = false
    private var cacheBound = false
    private var autoScanStarted = false
    private var cacheSnapshotId = ""
    private var profileSnapshotId = ""
    private var snapshotExpiresAtRealtime = 0L
    private var pollJob: Job? = null
    private var screenState by mutableStateOf(WorkbenchUiState())

    private val profileConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            profileService = IProfileRootService.Stub.asInterface(binder)
            profileBound = true
            screenState = screenState.copy(profileConnected = true)
            maybeStartScan()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            profileService = null
            profileBound = false
            screenState = screenState.copy(
                profileConnected = false,
                running = false,
                phase = "Root 详情引擎连接已断开"
            )
        }
    }

    private val cacheConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            cacheService = IBaiZeRootService.Stub.asInterface(binder)
            cacheBound = true
            screenState = screenState.copy(cacheConnected = true)
            maybeStartScan()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cacheService = null
            cacheBound = false
            screenState = screenState.copy(
                cacheConnected = false,
                running = false,
                phase = "Root 缓存引擎连接已断开"
            )
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
                    ScanWorkbenchScreen(
                        appearance = appearance,
                        state = screenState,
                        actions = WorkbenchActions(
                            onBack = ::finish,
                            onScan = ::runScan,
                            onStop = ::stopTask,
                            onClean = ::cleanSelection,
                            onToggleItem = ::toggleItem,
                            onToggleGroup = ::toggleGroup,
                            onSelectAll = ::selectAllSafe,
                            onClear = ::clearSelection,
                            onProtect = ::protectItem
                        )
                    )
                }
            }
        }
        connectServices()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        if (profileBound) runCatching { RootService.unbind(profileConnection) }
        if (cacheBound) runCatching { RootService.unbind(cacheConnection) }
        super.onDestroy()
    }

    private fun connectServices() {
        screenState = screenState.copy(phase = "正在连接双 Root 快照引擎…")
        if (!profileBound) {
            runCatching {
                RootService.bind(
                    Intent(this, BaiZeProfileRootService::class.java)
                        .addCategory(RootService.CATEGORY_DAEMON_MODE),
                    profileConnection
                )
                profileBound = true
            }.onFailure { screenState = screenState.copy(phase = "详情引擎启动失败：${it.message.orEmpty()}") }
        }
        if (!cacheBound) {
            runCatching {
                RootService.bind(
                    Intent(this, BaiZeRootService::class.java)
                        .addCategory(RootService.CATEGORY_DAEMON_MODE),
                    cacheConnection
                )
                cacheBound = true
            }.onFailure { screenState = screenState.copy(phase = "缓存引擎启动失败：${it.message.orEmpty()}") }
        }
    }

    private fun maybeStartScan() {
        if (profileService == null || cacheService == null || autoScanStarted) return
        autoScanStarted = true
        lifecycleScope.launch {
            delay(180L)
            runScan()
        }
    }

    private fun runScan() {
        if (screenState.running) return
        val profile = profileService
        val cache = cacheService
        if (profile == null || cache == null) {
            connectServices()
            return
        }
        cacheSnapshotId = ""
        profileSnapshotId = ""
        snapshotExpiresAtRealtime = 0L
        screenState = screenState.copy(
            running = true,
            scanReady = false,
            phase = "正在并行扫描应用缓存与安全项目…",
            progressCurrent = 0L,
            progressTotal = 0L,
            currentPath = "",
            items = emptyList(),
            selectedIds = emptySet(),
            resultText = ""
        )
        startPolling()

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val packageWhitelist = profile.getWhitelistPackages()
                    val options = optionsJson(profile)
                    coroutineScope {
                        val cacheJob = async { JSONObject(cache.scanCandidates(packageWhitelist)) }
                        val profileJob = async { JSONObject(profile.scanProfile("safe", options)) }
                        cacheJob.await() to profileJob.await()
                    }
                }
            }
            pollJob?.cancel()
            if (result.isFailure) {
                screenState = screenState.copy(
                    running = false,
                    phase = "安全扫描失败：${result.exceptionOrNull()?.message ?: "Root 服务异常"}"
                )
                return@launch
            }

            val (cacheJson, profileJson) = result.getOrThrow()
            val busy = listOf(cacheJson, profileJson).firstOrNull {
                it.optString("error") == "busy" || it.optInt("exitCode") == 3
            }
            if (busy != null) {
                screenState = screenState.copy(
                    running = false,
                    phase = busy.optString("message", "已有扫描或清理任务正在运行")
                )
                return@launch
            }

            val cacheOk = !cacheJson.has("error") && !cacheJson.optBoolean("cancelled")
            val profileOk = profileJson.optBoolean("success") && !profileJson.optBoolean("cancelled")
            if (!cacheOk && !profileOk) {
                screenState = screenState.copy(
                    running = false,
                    phase = profileJson.optString("message", cacheJson.optString("message", "两个扫描引擎均未返回有效快照"))
                )
                return@launch
            }

            cacheSnapshotId = if (cacheOk) cacheJson.optString("snapshotId") else ""
            profileSnapshotId = if (profileOk) profileJson.optString("snapshotId") else ""
            val pages = runCatching {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val cacheItems = async { if (cacheSnapshotId.isBlank()) emptyList() else loadCacheItems(cache, cacheSnapshotId) }
                        val profileItems = async { if (profileSnapshotId.isBlank()) emptyList() else loadProfileItems(profile, profileSnapshotId) }
                        cacheItems.await() + profileItems.await()
                    }
                }
            }
            if (pages.isFailure) {
                screenState = screenState.copy(
                    running = false,
                    phase = "扫描完成，但读取结果失败：${pages.exceptionOrNull()?.message.orEmpty()}"
                )
                return@launch
            }

            val items = pages.getOrThrow().sortedWith(
                compareByDescending<WorkbenchItem> { it.bytes.coerceAtLeast(0L) }
                    .thenBy { it.groupTitle }
                    .thenBy { it.title }
            )
            val selected = items.asSequence().filter { it.selectable }.mapTo(linkedSetOf()) { it.id }
            snapshotExpiresAtRealtime = SystemClock.elapsedRealtime() + SNAPSHOT_TTL_MS
            screenState = screenState.copy(
                running = false,
                scanReady = items.isNotEmpty(),
                phase = if (items.isEmpty()) "扫描完成，没有发现可安全清理的项目" else "扫描完成，已生成不可变清理快照",
                items = items,
                selectedIds = selected,
                expiresAtRealtime = snapshotExpiresAtRealtime,
                resultText = if (items.isEmpty()) "当前设备很干净" else "默认勾选全部低风险与中风险项目"
            )
        }
    }

    private suspend fun loadCacheItems(service: IBaiZeRootService, snapshotId: String): List<WorkbenchItem> {
        val result = ArrayList<WorkbenchItem>()
        var offset = 0
        var total = Int.MAX_VALUE
        while (offset < total && result.size < MAX_ITEMS) {
            val page = JSONObject(service.getResultPage(snapshotId, offset, PAGE_SIZE))
            if (page.has("error")) error(page.optString("message", page.optString("error")))
            total = page.optInt("total", 0).coerceAtLeast(0)
            val array = page.optJSONArray("items") ?: JSONArray()
            if (array.length() == 0) break
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val packageName = item.optString("packageName").trim()
                val category = item.optString("categoryLabel").ifBlank { "应用缓存" }
                val path = item.optString("path").trim()
                if (packageName.isBlank() || path.isBlank()) continue
                val appName = applicationLabel(packageName)
                result += WorkbenchItem(
                    id = "cache:${stableId("$packageName\u0000$category\u0000$path")}",
                    source = "cache",
                    profile = "cache",
                    packageName = packageName,
                    appName = appName,
                    category = category,
                    groupKey = "app:$packageName",
                    groupTitle = appName,
                    title = category,
                    risk = "low",
                    path = path,
                    bytes = item.optLong("bytes", 0L).coerceAtLeast(0L),
                    files = item.optLong("files", 0L).coerceAtLeast(0L),
                    directories = item.optLong("directories", 0L).coerceAtLeast(0L),
                    reason = "应用缓存快照命中 · 只删除扫描时记录的文件",
                    selectable = true
                )
            }
            offset += array.length()
        }
        return result
    }

    private suspend fun loadProfileItems(service: IProfileRootService, snapshotId: String): List<WorkbenchItem> {
        val result = ArrayList<WorkbenchItem>()
        var offset = 0
        var total = Int.MAX_VALUE
        while (offset < total && result.size < MAX_ITEMS) {
            val page = JSONObject(service.getProfilePage(snapshotId, offset, PAGE_SIZE))
            if (page.has("error")) error(page.optString("message", page.optString("error")))
            total = page.optInt("total", 0).coerceAtLeast(0)
            val array = page.optJSONArray("items") ?: JSONArray()
            if (array.length() == 0) break
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val path = item.optString("path").trim()
                val profile = item.optString("profile").ifBlank { "safe" }
                val category = item.optString("category").ifBlank { "safe_item" }
                val label = item.optString("categoryLabel").ifBlank { categoryLabel(category) }
                val risk = item.optString("risk", "medium").lowercase()
                val packageName = item.optString("packageName").trim()
                val appName = item.optString("appName").trim().ifBlank {
                    if (packageName.isNotBlank()) applicationLabel(packageName) else label
                }
                val candidateId = item.optString("id").ifBlank { stableId("$profile\u0000$category\u0000$path") }
                val note = item.optString("note").trim()
                result += WorkbenchItem(
                    id = "profile:$candidateId",
                    source = "profile",
                    profile = profile,
                    packageName = packageName,
                    appName = appName,
                    category = category,
                    groupKey = "category:$profile:$label",
                    groupTitle = label,
                    title = File(path).name.ifBlank { label },
                    risk = risk,
                    path = path,
                    bytes = item.optLong("bytes", -1L),
                    files = item.optLong("files", -1L),
                    directories = item.optLong("directories", -1L),
                    reason = note.ifBlank { riskReason(risk, label) },
                    selectable = risk == "low" || risk == "medium"
                )
            }
            offset += array.length()
        }
        return result
    }

    private fun cleanSelection() {
        if (screenState.running) return
        if (!screenState.scanReady || SystemClock.elapsedRealtime() >= snapshotExpiresAtRealtime) {
            screenState = screenState.copy(scanReady = false, phase = "扫描快照已过期，请重新扫描")
            return
        }
        val profile = profileService ?: return
        val cache = cacheService ?: return
        val selected = screenState.items.filter { it.selectable && it.id in screenState.selectedIds }
        if (selected.isEmpty()) {
            screenState = screenState.copy(phase = "请至少勾选一个安全项目")
            return
        }
        val cacheItems = selected.filter { it.source == "cache" }
        val profileItems = selected.filter { it.source == "profile" }
        screenState = screenState.copy(
            running = true,
            phase = "正在校验并清理 ${selected.size} 个已勾选项目…",
            progressCurrent = 0L,
            progressTotal = selected.size.toLong(),
            currentPath = ""
        )
        startPolling()

        lifecycleScope.launch {
            val response = runCatching {
                withContext(Dispatchers.IO) {
                    val packageWhitelist = profile.getWhitelistPackages()
                    val options = optionsJson(profile)
                    var bytes = 0L
                    var files = 0L
                    var failures = 0
                    var cleanedCandidates = 0
                    val messages = ArrayList<String>()

                    if (cacheItems.isNotEmpty()) {
                        val selection = JSONObject()
                        cacheItems.forEach { selection.put(it.path, true) }
                        val prepared = JSONObject(profile.prepareCacheSelection(cacheSnapshotId, selection.toString()))
                        if (!prepared.optBoolean("success")) {
                            error(prepared.optString("message", prepared.optString("error", "无法裁剪缓存快照")))
                        }
                        val selectedSnapshotId = prepared.optString("snapshotId")
                        val cacheResult = JSONObject(
                            cache.cleanSelected(
                                selectedSnapshotId,
                                JSONObject().put("__all_safe__", true).toString(),
                                packageWhitelist
                            )
                        )
                        bytes += cacheResult.optLong("deletedBytes", 0L).coerceAtLeast(0L)
                        files += cacheResult.optLong("deletedFiles", 0L).coerceAtLeast(0L)
                        failures += cacheResult.optInt("failures", if (cacheResult.optBoolean("success")) 0 else 1).coerceAtLeast(0)
                        cleanedCandidates += cacheResult.optInt("cleanedCandidates", 0).coerceAtLeast(0)
                        messages += cacheResult.optString("message", "应用缓存处理完成")
                    }

                    if (profileItems.isNotEmpty()) {
                        val selection = JSONObject()
                        profileItems.forEach { selection.put(it.id.removePrefix("profile:"), true) }
                        val profileResult = JSONObject(
                            profile.cleanProfileSelected(profileSnapshotId, selection.toString(), options)
                        )
                        bytes += profileResult.optLong("deletedBytes", 0L).coerceAtLeast(0L)
                        files += profileResult.optLong("deletedFiles", 0L).coerceAtLeast(0L)
                        failures += profileResult.optInt("failures", if (profileResult.optBoolean("success")) 0 else 1).coerceAtLeast(0)
                        cleanedCandidates += profileResult.optInt("cleanedCandidates", 0).coerceAtLeast(0)
                        messages += profileResult.optString("message", "安全项目处理完成")
                    }

                    runCatching {
                        profile.recordNativeTask(
                            JSONObject()
                                .put("mode", "workbench-clean")
                                .put("success", failures == 0)
                                .put("bytes", bytes)
                                .put("files", files)
                                .put("errors", failures)
                                .put("result", "工作台清理完成，处理 $cleanedCandidates 个候选")
                                .toString()
                        )
                    }
                    CleanAggregate(bytes, files, failures, cleanedCandidates, messages)
                }
            }
            pollJob?.cancel()
            cacheSnapshotId = ""
            profileSnapshotId = ""
            snapshotExpiresAtRealtime = 0L
            response.onSuccess { result ->
                screenState = screenState.copy(
                    running = false,
                    scanReady = false,
                    items = emptyList(),
                    selectedIds = emptySet(),
                    phase = if (result.failures == 0) "已完成所选项目清理" else "清理完成，但有 ${result.failures} 个异常",
                    resultText = "释放 ${formatBytes(result.bytes)} · 文件 ${result.files} · 候选 ${result.candidates}\n${result.messages.filter { it.isNotBlank() }.joinToString("\n")}"
                )
            }.onFailure {
                screenState = screenState.copy(
                    running = false,
                    scanReady = false,
                    items = emptyList(),
                    selectedIds = emptySet(),
                    phase = "所选项目清理失败：${it.message ?: it.javaClass.simpleName}"
                )
            }
        }
    }

    private fun protectItem(item: WorkbenchItem) {
        if (screenState.running) return
        val service = profileService ?: return
        screenState = screenState.copy(phase = "正在把 ${item.groupTitle} 加入保护白名单…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (item.packageName.isNotBlank()) {
                        val current = JSONArray(service.getWhitelistPackages())
                        val packages = linkedSetOf<String>()
                        for (index in 0 until current.length()) current.optString(index).trim().takeIf { it.isNotBlank() }?.let(packages::add)
                        packages += item.packageName
                        JSONObject(service.saveWhitelistPackages(JSONArray(packages.sorted()).toString()))
                    } else {
                        JSONObject(service.addWhitelistPath(item.path))
                    }
                }
            }
            result.onSuccess { json ->
                if (json.optBoolean("success")) {
                    cacheSnapshotId = ""
                    profileSnapshotId = ""
                    snapshotExpiresAtRealtime = 0L
                    screenState = screenState.copy(
                        scanReady = false,
                        items = emptyList(),
                        selectedIds = emptySet(),
                        phase = "已加入白名单，正在重新生成安全快照…"
                    )
                    Toast.makeText(this@ScanWorkbenchActivity, json.optString("message", "已加入白名单"), Toast.LENGTH_SHORT).show()
                    delay(180L)
                    runScan()
                } else {
                    screenState = screenState.copy(phase = "白名单保存失败：${json.optString("message", json.optString("error"))}")
                }
            }.onFailure {
                screenState = screenState.copy(phase = "白名单保存失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun toggleItem(id: String) {
        val item = screenState.items.firstOrNull { it.id == id } ?: return
        if (!item.selectable || screenState.running) return
        val selected = screenState.selectedIds.toMutableSet()
        if (!selected.add(id)) selected.remove(id)
        screenState = screenState.copy(selectedIds = selected)
    }

    private fun toggleGroup(groupKey: String) {
        if (screenState.running) return
        val group = screenState.items.filter { it.groupKey == groupKey && it.selectable }
        if (group.isEmpty()) return
        val selected = screenState.selectedIds.toMutableSet()
        val shouldSelect = group.any { it.id !in selected }
        group.forEach { if (shouldSelect) selected += it.id else selected -= it.id }
        screenState = screenState.copy(selectedIds = selected)
    }

    private fun selectAllSafe() {
        if (screenState.running) return
        screenState = screenState.copy(
            selectedIds = screenState.items.asSequence().filter { it.selectable }.mapTo(linkedSetOf()) { it.id }
        )
    }

    private fun clearSelection() {
        if (!screenState.running) screenState = screenState.copy(selectedIds = emptySet())
    }

    private fun stopTask() {
        profileService?.cancelCurrentTask()
        cacheService?.cancelCurrentTask()
        screenState = screenState.copy(phase = "正在安全停止当前任务…")
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive && screenState.running) {
                val state = withContext(Dispatchers.IO) {
                    val profile = runCatching { profileService?.getTaskState()?.let(::JSONObject) }.getOrNull()
                    val cache = runCatching { cacheService?.getTaskState()?.let(::JSONObject) }.getOrNull()
                    listOfNotNull(profile, cache).firstOrNull { it.optBoolean("running") }
                }
                if (state != null) {
                    screenState = screenState.copy(
                        phase = state.optString("phase", screenState.phase),
                        progressCurrent = state.optLong("progress_current", state.optLong("current", 0L)).coerceAtLeast(0L),
                        progressTotal = state.optLong("progress_total", state.optLong("total", 0L)).coerceAtLeast(0L),
                        currentPath = state.optString("current_path", state.optString("path")).takeLast(120)
                    )
                }
                delay(350L)
            }
        }
    }

    private fun optionsJson(service: IProfileRootService): String {
        val config = runCatching { JSONObject(service.getSchedulerConfig()) }.getOrDefault(JSONObject())
        val paths = runCatching { JSONArray(service.getWhitelistPaths()) }.getOrDefault(JSONArray())
        val packages = runCatching { JSONArray(service.getWhitelistPackages()) }.getOrDefault(JSONArray())
        val maxMb = config.optInt("max_file_mb", 256).coerceIn(16, 16_384)
        return JSONObject()
            .put("whitelistPackages", packages)
            .put("whitelistPaths", paths)
            .put("maxFileBytes", maxMb * 1_024L * 1_024L)
            .put("fragmentDays", config.optInt("fragment_days", 7).coerceIn(0, 365))
            .put("allowHighRisk", false)
            .toString()
    }

    private fun applicationLabel(packageName: String): String = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                packageName,
                android.content.pm.PackageManager.ApplicationInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
        packageManager.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
    }.getOrNull() ?: packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(24)

    private fun formatBytes(bytes: Long): String = Formatter.formatFileSize(this, bytes.coerceAtLeast(0L))

    companion object {
        private const val PAGE_SIZE = 100
        private const val MAX_ITEMS = 2_000
        private const val SNAPSHOT_TTL_MS = 30L * 60L * 1_000L
    }
}

private data class CleanAggregate(
    val bytes: Long,
    val files: Long,
    val failures: Int,
    val candidates: Int,
    val messages: List<String>
)

private data class WorkbenchItem(
    val id: String,
    val source: String,
    val profile: String,
    val packageName: String,
    val appName: String,
    val category: String,
    val groupKey: String,
    val groupTitle: String,
    val title: String,
    val risk: String,
    val path: String,
    val bytes: Long,
    val files: Long,
    val directories: Long,
    val reason: String,
    val selectable: Boolean
)

private data class WorkbenchUiState(
    val profileConnected: Boolean = false,
    val cacheConnected: Boolean = false,
    val running: Boolean = false,
    val scanReady: Boolean = false,
    val phase: String = "等待 Root 服务",
    val progressCurrent: Long = 0L,
    val progressTotal: Long = 0L,
    val currentPath: String = "",
    val items: List<WorkbenchItem> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val expiresAtRealtime: Long = 0L,
    val resultText: String = ""
) {
    val connected: Boolean get() = profileConnected && cacheConnected
    val selectedItems: List<WorkbenchItem> get() = items.filter { it.id in selectedIds }
    val selectedBytes: Long get() = selectedItems.sumOf { it.bytes.coerceAtLeast(0L) }
}

private data class WorkbenchActions(
    val onBack: () -> Unit,
    val onScan: () -> Unit,
    val onStop: () -> Unit,
    val onClean: () -> Unit,
    val onToggleItem: (String) -> Unit,
    val onToggleGroup: (String) -> Unit,
    val onSelectAll: () -> Unit,
    val onClear: () -> Unit,
    val onProtect: (WorkbenchItem) -> Unit
)

private data class WorkbenchGroup(
    val key: String,
    val title: String,
    val items: List<WorkbenchItem>,
    val bytes: Long,
    val selectedCount: Int,
    val selectableCount: Int
)

private sealed interface WorkbenchRow {
    val key: String

    data class Group(val group: WorkbenchGroup) : WorkbenchRow {
        override val key: String = "group:${group.key}"
    }

    data class Candidate(val item: WorkbenchItem) : WorkbenchRow {
        override val key: String = "item:${item.id}"
    }
}

@Composable
private fun ScanWorkbenchScreen(
    appearance: AppearanceSettings,
    state: WorkbenchUiState,
    actions: WorkbenchActions
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val miuix = appearance.uiStyle == UiStyle.MIUIX
    val horizontal = if (miuix) 18.dp else 20.dp
    val shape: Shape = if (miuix) RoundedCornerShape(24.dp) else MaterialTheme.shapes.large
    var filter by remember { mutableStateOf("all") }
    var expandedGroups by remember { mutableStateOf(emptySet<String>()) }

    val filteredItems = remember(state.items, filter) {
        state.items.filter { item ->
            when (filter) {
                "cache" -> item.source == "cache"
                "empty" -> item.profile == "empty" || item.category.startsWith("empty")
                "rules" -> item.profile == "rules" || item.category.contains("rule") || item.category.contains("trash")
                "fragments" -> item.profile == "fragments" || item.category.contains("fragment")
                else -> true
            }
        }
    }
    val groups = remember(filteredItems, state.selectedIds) {
        filteredItems.groupBy { it.groupKey }
            .map { (key, items) ->
                WorkbenchGroup(
                    key = key,
                    title = items.first().groupTitle,
                    items = items,
                    bytes = items.sumOf { it.bytes.coerceAtLeast(0L) },
                    selectedCount = items.count { it.id in state.selectedIds },
                    selectableCount = items.count { it.selectable }
                )
            }
            .sortedByDescending { it.bytes }
    }
    LaunchedEffect(groups.map { it.key }) {
        if (expandedGroups.isEmpty() && groups.isNotEmpty()) {
            expandedGroups = groups.take(if (groups.size <= 4) groups.size else 2).mapTo(linkedSetOf()) { it.key }
        }
    }
    val rows = remember(groups, expandedGroups) {
        buildList<WorkbenchRow> {
            groups.forEach { group ->
                add(WorkbenchRow.Group(group))
                if (group.key in expandedGroups) group.items.forEach { add(WorkbenchRow.Candidate(it)) }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { WorkbenchHeader(miuix, actions.onBack) }
            item {
                WorkbenchStatusCard(
                    state = state,
                    shape = shape,
                    horizontal = horizontal,
                    onStop = actions.onStop
                )
            }
            if (!state.scanReady && !state.running) {
                item {
                    WorkbenchEmptyCard(
                        state = state,
                        shape = shape,
                        horizontal = horizontal,
                        onScan = actions.onScan
                    )
                }
            }
            if (state.scanReady) {
                item {
                    WorkbenchSummaryCard(
                        state = state,
                        shape = shape,
                        horizontal = horizontal,
                        selectedBytes = Formatter.formatFileSize(context, state.selectedBytes)
                    )
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
                            "cache" to "应用缓存",
                            "empty" to "空项目",
                            "rules" to "规则垃圾",
                            "fragments" to "残留碎片"
                        ).forEach { (id, label) ->
                            FilterChip(
                                selected = filter == id,
                                onClick = { filter = id },
                                label = { Text(label) }
                            )
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(onClick = actions.onSelectAll, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.SelectAll, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("全选安全项")
                        }
                        OutlinedButton(onClick = actions.onClear, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Deselect, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("取消全选")
                        }
                    }
                }
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is WorkbenchRow.Group -> WorkbenchGroupRow(
                            group = row.group,
                            expanded = row.group.key in expandedGroups,
                            shape = shape,
                            horizontal = horizontal,
                            onToggleExpanded = {
                                expandedGroups = expandedGroups.toMutableSet().apply {
                                    if (!add(row.group.key)) remove(row.group.key)
                                }
                            },
                            onToggleSelection = { actions.onToggleGroup(row.group.key) }
                        )
                        is WorkbenchRow.Candidate -> WorkbenchCandidateRow(
                            item = row.item,
                            selected = row.item.id in state.selectedIds,
                            horizontal = horizontal,
                            onToggle = { actions.onToggleItem(row.item.id) },
                            onProtect = { actions.onProtect(row.item) }
                        )
                    }
                }
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = horizontal).navigationBarsPadding(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = actions.onClean,
                            enabled = state.selectedIds.isNotEmpty() && !state.running,
                            modifier = Modifier.fillMaxWidth().height(58.dp),
                            shape = if (miuix) RoundedCornerShape(19.dp) else MaterialTheme.shapes.large
                        ) {
                            Icon(Icons.Rounded.CleaningServices, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("清理已选 ${state.selectedIds.size} 项", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = actions.onScan,
                            enabled = !state.running,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text("重新扫描")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkbenchHeader(miuix: Boolean, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "返回") }
        Spacer(Modifier.width(5.dp))
        Column {
            Text(
                "扫描结果工作台",
                fontSize = if (miuix) 28.sp else 25.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                "按应用和垃圾类别选择，不重新扫描直接清理",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun WorkbenchStatusCard(
    state: WorkbenchUiState,
    shape: Shape,
    horizontal: androidx.compose.ui.unit.Dp,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (state.running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = if (state.connected) BaiZeTokens.colors.success.copy(alpha = .14f) else BaiZeTokens.colors.warning.copy(alpha = .16f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (state.connected) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = if (state.connected) BaiZeTokens.colors.success else BaiZeTokens.colors.warning
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.phase, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    if (state.currentPath.isNotBlank()) {
                        Text(
                            state.currentPath,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (state.running) {
                    IconButton(onClick = onStop) { Icon(Icons.Rounded.Stop, contentDescription = "停止") }
                }
            }
            if (state.running) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (state.progressTotal > 0L) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "${state.progressCurrent.coerceAtMost(state.progressTotal)} / ${state.progressTotal}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkbenchEmptyCard(
    state: WorkbenchUiState,
    shape: Shape,
    horizontal: androidx.compose.ui.unit.Dp,
    onScan: () -> Unit
) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text(if (state.resultText.isBlank()) "准备安全扫描" else state.resultText, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(
                "扫描只生成 30 分钟有效的服务器端快照，不会删除文件。清理时只消费勾选的快照项目。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onScan, enabled = state.connected, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.connected) "开始安全扫描" else "正在连接 Root 服务")
            }
        }
    }
}

@Composable
private fun WorkbenchSummaryCard(
    state: WorkbenchUiState,
    shape: Shape,
    horizontal: androidx.compose.ui.unit.Dp,
    selectedBytes: String
) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(19.dp)) {
            Text("已选 ${state.selectedIds.size} / ${state.items.count { it.selectable }} 项", fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            Text("预计至少释放 $selectedBytes", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryPill("应用 ${state.items.count { it.source == "cache" }}")
                SummaryPill("其他 ${state.items.count { it.source == "profile" }}")
                SummaryPill("受保护 ${state.items.count { !it.selectable }}")
            }
            if (state.resultText.isNotBlank()) {
                Spacer(Modifier.height(9.dp))
                Text(state.resultText, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SummaryPill(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = .58f)) {
        Text(text, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WorkbenchGroupRow(
    group: WorkbenchGroup,
    expanded: Boolean,
    shape: Shape,
    horizontal: androidx.compose.ui.unit.Dp,
    onToggleExpanded: () -> Unit,
    onToggleSelection: () -> Unit
) {
    Card(
        modifier = Modifier
            .padding(horizontal = horizontal)
            .fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = group.selectableCount > 0 && group.selectedCount == group.selectableCount,
                onCheckedChange = { onToggleSelection() },
                enabled = group.selectableCount > 0
            )
            Surface(
                modifier = Modifier.size(39.dp),
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (group.key.startsWith("app:")) Icons.Rounded.Apps else categoryIcon(group.items.firstOrNull()?.profile.orEmpty()),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(group.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${group.items.size} 项 · 已选 ${group.selectedCount} · ${Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current, group.bytes)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
        }
    }
}

@Composable
private fun WorkbenchCandidateRow(
    item: WorkbenchItem,
    selected: Boolean,
    horizontal: androidx.compose.ui.unit.Dp,
    onToggle: () -> Unit,
    onProtect: () -> Unit
) {
    Row(
        modifier = Modifier
            .padding(horizontal = horizontal + 9.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(enabled = item.selectable, onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = item.selectable)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                RiskBadge(item.risk)
            }
            Text(
                item.reason,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(if (item.bytes >= 0L) Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current, item.bytes) else "大小待测")
                    if (item.files >= 0L) append(" · ").append(item.files).append(" 文件")
                    append(" · ").append(item.path.takeLast(64))
                },
                color = MaterialTheme.colorScheme.outline,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onProtect, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Rounded.Shield, contentDescription = "加入白名单", modifier = Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun RiskBadge(risk: String) {
    val (label, color) = when (risk) {
        "low" -> "低风险" to BaiZeTokens.colors.success
        "medium" -> "中风险" to MaterialTheme.colorScheme.primary
        "high" -> "高风险" to BaiZeTokens.colors.warning
        else -> "关键" to MaterialTheme.colorScheme.error
    }
    Surface(shape = CircleShape, color = color.copy(alpha = .13f)) {
        Text(label, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

private fun categoryIcon(profile: String) = when (profile) {
    "empty" -> Icons.Rounded.Folder
    "rules" -> Icons.Rounded.Rule
    "fragments" -> Icons.Rounded.CleaningServices
    else -> Icons.Rounded.CleaningServices
}

private fun categoryLabel(category: String): String = when (category) {
    "empty_file" -> "空文件"
    "empty_dir" -> "空目录"
    "fragment" -> "残留碎片"
    "hidden_trash" -> "隐藏垃圾"
    "rule_trash" -> "规则垃圾"
    else -> "安全项目"
}

private fun riskReason(risk: String, label: String): String = when (risk) {
    "low" -> "$label · 可安全自动处理"
    "medium" -> "$label · 清理前再次校验白名单与大小限制"
    "high" -> "$label · 默认只审计，不自动勾选"
    else -> "$label · 关键风险项目，只展示不自动清理"
}
