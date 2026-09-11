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
import androidx.compose.material.icons.rounded.Inventory2
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
import androidx.compose.runtime.produceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private var restoredReview = false
    private val scanProfile get() = if (intent.getStringExtra(EXTRA_PROFILE) == "deep") "deep" else "safe"
    private val labels = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var cacheSnapshotId = ""
    private var profileSnapshotId = ""
    private var snapshotExpiresAtRealtime = 0L
    private var cleanupPolicy = CleanupPolicy.BALANCED
    private var pollJob: Job? = null
    private var scanJob: Job? = null
    private var stopJob: Job? = null
    private val scanGeneration = ScanLoadGeneration()
    private var screenState by mutableStateOf(WorkbenchUiState())

    private val profileConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            profileService = IProfileRootService.Stub.asInterface(binder)
            profileBound = true
            screenState = screenState.copy(profileConnected = true)
            maybeStartScan()
        }

        override fun onNullBinding(name: ComponentName?) {
            RootService.unbind(this)
            onServiceDisconnected(name)
            screenState = screenState.copy(phase = "Root 启动失败，请检查授权后重试")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            invalidateScanLoad()
            scanJob?.cancel()
            saveReview()
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

        override fun onNullBinding(name: ComponentName?) {
            RootService.unbind(this)
            onServiceDisconnected(name)
            screenState = screenState.copy(phase = "Root 启动失败，请检查授权后重试")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            invalidateScanLoad()
            scanJob?.cancel()
            saveReview()
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
        screenState = screenState.copy(cacheRequired = scanProfile != "deep")
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
                            onProtect = ::protectItem,
                            onQuarantine = ::quarantineItem
                        )
                    )
                }
            }
        }
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { ScanReviewStore.read(this@ScanWorkbenchActivity, scanProfile) }
            if (saved != null) restoreReview(saved)
            connectServices()
        }
    }

    override fun onStop() {
        saveReview()
        super.onStop()
    }

    override fun onDestroy() {
        scanGeneration.invalidate()
        scanJob?.cancel()
        pollJob?.cancel()
        if (profileBound) runCatching { RootService.unbind(profileConnection) }
        if (cacheBound) runCatching { RootService.unbind(cacheConnection) }
        super.onDestroy()
    }

    private fun connectServices() {
        screenState = screenState.copy(phase = "正在连接双 Root 快照引擎…")
        if (!profileBound) {
            profileBound = true
            runCatching {
                RootService.bind(
                    Intent(this, BaiZeProfileRootService::class.java)
                        .addCategory(RootService.CATEGORY_DAEMON_MODE),
                    profileConnection
                )
            }.onFailure { profileBound = false; screenState = screenState.copy(phase = "详情引擎启动失败：${it.message.orEmpty()}") }
        }
        if (scanProfile != "deep" && !cacheBound) {
            cacheBound = true
            runCatching {
                RootService.bind(
                    Intent(this, BaiZeRootService::class.java)
                        .addCategory(RootService.CATEGORY_DAEMON_MODE),
                    cacheConnection
                )
            }.onFailure { cacheBound = false; screenState = screenState.copy(phase = "缓存引擎启动失败：${it.message.orEmpty()}") }
        }
    }

    private fun maybeStartScan() {
        if (profileService == null || (scanProfile != "deep" && cacheService == null)) return
        if (restoredReview) {
            screenState = screenState.copy(phase = if (screenState.scanReady) "已恢复上次扫描，可继续选择" else "已恢复上次结果，重新扫描后可清理")
            return
        }
        if (autoScanStarted) return
        autoScanStarted = true
        lifecycleScope.launch {
            delay(180L)
            runScan()
        }
    }

    private fun runScan() {
        if (screenState.running || stopJob?.isActive == true) return
        val profile = profileService
        val cache = cacheService
        if (profile == null || (scanProfile != "deep" && cache == null)) {
            connectServices()
            return
        }
        restoredReview = false
        val generation = scanGeneration.start()
        cacheSnapshotId = ""
        profileSnapshotId = ""
        snapshotExpiresAtRealtime = 0L
        screenState = screenState.copy(
            running = true, loadingResults = false, scanReady = false,
            phase = "正在并行扫描应用缓存与安全项目…",
            progressCurrent = 0L, progressTotal = 0L, currentPath = "",
            items = emptyList(), selectedIds = emptySet(), resultText = "",
            expiresAtRealtime = 0L
        )
        // Persist the invalidation even before the first page, including across rotation.
        saveReview()
        startPolling()
        scanJob = lifecycleScope.launch {
            try {
                val (config, packageWhitelist, options) = withContext(Dispatchers.IO) {
                    val config = JSONObject(profile.getSchedulerConfig())
                    Triple(config, profile.getWhitelistPackages(), optionsJson(profile, config))
                }
                if (!scanGeneration.accepts(generation)) return@launch
                cleanupPolicy = CleanupPolicy.fromId(config.optInt("cleanup_policy", CleanupPolicy.BALANCED.id))
                val policy = cleanupPolicy
                val (cacheResult, profileResult) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val cacheJob = async {
                            val json = if (scanProfile == "deep") JSONObject().put("snapshotId", "")
                                else JSONObject(requireNotNull(cache).scanCandidates(packageWhitelist))
                            json to SystemClock.elapsedRealtime()
                        }
                        val profileJob = async {
                            JSONObject(profile.scanProfile(scanProfile, options)) to SystemClock.elapsedRealtime()
                        }
                        cacheJob.await() to profileJob.await()
                    }
                }
                if (!scanGeneration.accepts(generation)) return@launch
                pollJob?.cancel()
                val (cacheJson, cacheCompletedAt) = cacheResult
                val (profileJson, profileCompletedAt) = profileResult
                val busy = listOf(cacheJson, profileJson).firstOrNull {
                    it.optString("error") == "busy" || it.optInt("exitCode") == 3
                }
                if (busy != null) error(busy.optString("message", "已有扫描或清理任务正在运行"))
                val cacheOk = scanProfile != "deep" && !cacheJson.has("error") && !cacheJson.optBoolean("cancelled")
                    && cacheJson.optString("snapshotId").isNotBlank()
                val profileOk = profileJson.optBoolean("success") && !profileJson.optBoolean("cancelled")
                    && profileJson.optString("snapshotId").isNotBlank()
                if (!cacheOk && !profileOk) error(profileJson.optString("message", cacheJson.optString("message", "未返回有效快照")))
                cacheSnapshotId = if (cacheOk) cacheJson.optString("snapshotId") else ""
                profileSnapshotId = if (profileOk) profileJson.optString("snapshotId") else ""
                val cacheId = cacheSnapshotId
                val profileId = profileSnapshotId
                snapshotExpiresAtRealtime = minOf(
                    // Cache epochs have second precision and may predate the scan response.
                    if (cacheOk) cacheCompletedAt - cacheJson.optLong("elapsedMs", 0L).coerceAtLeast(0L) - 1_000L +
                        cacheJson.optLong("snapshotExpiresInMs", SNAPSHOT_TTL_MS).coerceIn(0L, SNAPSHOT_TTL_MS)
                    else Long.MAX_VALUE,
                    if (profileOk) profileCompletedAt + profileJson.optLong("snapshotExpiresInMs", SNAPSHOT_TTL_MS)
                        .coerceIn(0L, SNAPSHOT_TTL_MS) else Long.MAX_VALUE
                )
                val partial = profileJson.optBoolean("partial") || !profileOk || (scanProfile != "deep" && !cacheOk)
                val warning = if (partial) "本轮扫描未覆盖全部范围；仅展示有效快照中的项目。" else ""
                screenState = screenState.copy(
                    loadingResults = true, phase = "扫描结束，正在读取结果…",
                    progressCurrent = 0L, progressTotal = 0L, currentPath = "",
                    policyTitle = policy.title, policyKey = policy.key, highRiskMode = policy.highRiskMode,
                    expiresAtRealtime = snapshotExpiresAtRealtime,
                    resultText = warning + "读取期间仅供预览，全部读取完成后才可选择和清理。"
                )
                val results = ProgressiveScanResults<WorkbenchItem>({ it.id }) {
                    it.selectable && ReviewRiskPolicy.defaultSelected(it.risk, "", policy.autoRisk == "medium")
                }
                val counts = mutableMapOf<String, Pair<Int, Int>>()
                suspend fun publish(source: String, batch: List<WorkbenchItem>, cursor: ScanPageCursor) {
                    currentCoroutineContext().ensureActive()
                    val review = results.append(batch)
                    withContext(Dispatchers.Main) {
                        if (!scanGeneration.accepts(generation)) return@withContext
                        counts[source] = cursor.offset to requireNotNull(cursor.total)
                        val loaded = counts.values.sumOf { it.first.toLong() }
                        val expectedSources = (if (cacheOk) 1 else 0) + (if (profileOk) 1 else 0)
                        val total = if (counts.size == expectedSources) counts.values.sumOf { it.second.toLong() } else 0L
                        val newer = review != null && review.items.size > screenState.items.size
                        screenState = screenState.copy(
                            phase = "正在读取结果 · 已读取 $loaded 项" + if (total > 0) " / $total" else "",
                            progressCurrent = loaded, progressTotal = total,
                            items = if (newer) requireNotNull(review).items else screenState.items,
                            selectedIds = if (newer) requireNotNull(review).selectedIds else screenState.selectedIds
                        )
                    }
                }
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val cachePages = async {
                            if (cacheOk) loadCacheItems(requireNotNull(cache), cacheId) { batch, cursor -> publish("cache", batch, cursor) }
                        }
                        val profilePages = async {
                            if (profileOk) loadProfileItems(profile, profileId) { batch, cursor -> publish("profile", batch, cursor) }
                        }
                        cachePages.await()
                        profilePages.await()
                    }
                }
                if (!scanGeneration.accepts(generation)) return@launch
                screenState = screenState.copy(phase = "结果读取完成，正在整理显示…")
                val review = withContext(Dispatchers.Default) {
                    results.finish(compareByDescending<WorkbenchItem> { it.bytes.coerceAtLeast(0L) }
                        .thenBy { it.groupTitle }.thenBy { it.title })
                }
                if (!scanGeneration.accepts(generation)) return@launch
                val expired = SystemClock.elapsedRealtime() >= snapshotExpiresAtRealtime
                screenState = screenState.copy(
                    running = false, loadingResults = false,
                    scanReady = review.items.isNotEmpty() && !expired,
                    phase = when {
                        expired -> "扫描快照已过期，结果仅供查看，请重新扫描"
                        partial -> "本轮扫描未覆盖全部范围，已读取 ${review.items.size} 项"
                        review.items.isEmpty() -> "扫描完成，没有发现垃圾项目"
                        else -> "扫描完成，展开应用或分类后选择要清理的项目"
                    },
                    items = review.items, selectedIds = review.selectedIds,
                    resultText = warning + if (review.items.isEmpty()) {
                        if (partial) "未发现可展示项目，请重新扫描。" else "当前设备很干净"
                    } else if (policy == CleanupPolicy.CONSERVATIVE) {
                        "保守档默认只勾选低风险项目；中风险仍可手动选择"
                    } else {
                        "默认勾选低、中风险项目；高风险默认不选，可逐项选择。大小未统计的项目也会列出。"
                    }
                )
                scanGeneration.invalidate()
                saveReview()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!scanGeneration.accepts(generation)) return@launch
                val wasLoading = screenState.loadingResults
                invalidateScanLoad()
                screenState = screenState.copy(running = false,
                    phase = if (wasLoading) "读取结果失败，已加载项目仅供查看：${error.message.orEmpty()}"
                        else "安全扫描失败：${error.message.orEmpty()}")
                saveReview()
            }
        }
    }

    private fun invalidateScanLoad() {
        scanGeneration.invalidate()
        pollJob?.cancel()
        cacheSnapshotId = ""
        profileSnapshotId = ""
        snapshotExpiresAtRealtime = 0L
        screenState = screenState.copy(scanReady = false, loadingResults = false, expiresAtRealtime = 0L)
    }

    private suspend fun loadCacheItems(
        service: IBaiZeRootService, snapshotId: String,
        onPage: suspend (List<WorkbenchItem>, ScanPageCursor) -> Unit
    ) {
        val cursor = ScanPageCursor(snapshotId)
        while (!cursor.complete) {
            currentCoroutineContext().ensureActive()
            val page = JSONObject(service.getResultPage(snapshotId, cursor.offset, CACHE_PAGE_SIZE))
            currentCoroutineContext().ensureActive()
            val result = ArrayList<WorkbenchItem>()
            if (page.has("error")) error(page.optString("message", page.optString("error")))
            val array = page.optJSONArray("items") ?: error("扫描结果缺少项目列表")
            cursor.accept(page.optString("snapshotId"),
                page.optInt("offset", if (array.length() == 0 && page.optInt("total", -1) == 0) 0 else -1),
                page.optInt("total", -1), array.length())
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
            onPage(result, cursor)
        }
    }

    private suspend fun loadProfileItems(
        service: IProfileRootService, snapshotId: String,
        onPage: suspend (List<WorkbenchItem>, ScanPageCursor) -> Unit
    ) {
        val cursor = ScanPageCursor(snapshotId)
        while (!cursor.complete) {
            currentCoroutineContext().ensureActive()
            val page = JSONObject(service.getProfilePage(snapshotId, cursor.offset, PROFILE_PAGE_SIZE))
            currentCoroutineContext().ensureActive()
            val result = ArrayList<WorkbenchItem>()
            if (page.has("error")) error(page.optString("message", page.optString("error")))
            val array = page.optJSONArray("items") ?: error("扫描结果缺少项目列表")
            cursor.accept(page.optString("snapshotId"),
                page.optInt("offset", if (array.length() == 0 && page.optInt("total", -1) == 0) 0 else -1),
                page.optInt("total", -1), array.length())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val path = item.optString("path").trim()
                val profile = item.optString("profile").ifBlank { "safe" }
                val category = item.optString("category").ifBlank { "safe_item" }
                val label = item.optString("categoryLabel").ifBlank { categoryLabel(category) }
                val risk = item.optString("risk", "medium").lowercase()
                val packageName = item.optString("packageName").trim()
                val appName = if (packageName.isNotBlank()) applicationLabel(packageName)
                    else item.optString("appName").trim().ifBlank { label }
                val candidateId = item.optString("id").ifBlank { stableId("$profile\u0000$category\u0000$path") }
                val note = item.optString("note").trim()
                result += WorkbenchItem(
                    id = "profile:$candidateId",
                    source = "profile",
                    profile = profile,
                    packageName = packageName,
                    appName = appName,
                    category = category,
                    groupKey = if (packageName.isNotBlank()) "app:$packageName" else "category:$profile:$label",
                    groupTitle = if (packageName.isNotBlank()) appName else label,
                    title = File(path).name.ifBlank { label },
                    risk = risk,
                    path = path,
                    bytes = item.optLong("bytes", -1L),
                    files = item.optLong("files", -1L),
                    directories = item.optLong("directories", -1L),
                    reason = item.optString("blockedReason").ifBlank {
                        if (risk == "high") "默认不清理；可能包含离线内容或应用数据，确认用途后勾选。$note"
                        else note.ifBlank { riskReason(risk, label, cleanupPolicy) }
                    },
                    selectable = ReviewRiskPolicy.selectable(risk, item.optString("blockedReason"))
                )
            }
            onPage(result, cursor)
        }
    }

    private fun cleanSelection() {
        if (screenState.running || screenState.loadingResults || stopJob?.isActive == true) return
        if (!screenState.scanReady || SystemClock.elapsedRealtime() >= snapshotExpiresAtRealtime) {
            screenState = screenState.copy(scanReady = false, phase = "扫描快照已过期，请重新扫描")
            return
        }
        val profile = profileService ?: return
        val cache = cacheService
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
                    val options = JSONObject(optionsJson(profile))
                        .put("allowHighRisk", profileItems.any { it.risk == "high" }).toString()
                    var bytes = 0L
                    var files = 0L
                    var failures = 0
                    var cleanedCandidates = 0
                    var cancelled = false
                    val messages = ArrayList<String>()
                    val outcomes = HashMap<String, String>()
                    val actualApps = ArrayList<AppJunkUiItem>()
                    val actualJunk = ArrayList<GeneralJunkUiItem>()

                    if (cacheItems.isNotEmpty()) {
                        val cache = requireNotNull(cache)
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
                        cancelled = cacheResult.optBoolean("cancelled")
                        messages += cacheResult.optString("message", "应用缓存处理完成")
                        val status = if (cacheResult.optBoolean("success") && !cacheResult.optBoolean("cancelled") && cacheResult.optInt("failures") == 0)
                            "已按所选缓存执行清理" else "未全部完成，请查看任务结果"
                        cacheItems.forEach { outcomes[it.id] = status }
                        val report = if (cacheResult.optBoolean("success"))
                            runCatching { JSONObject(profile.getModuleState()).optJSONArray("appDetails") }.getOrNull() ?: JSONArray()
                        else JSONArray()
                        for (index in 0 until report.length()) {
                            val app = report.optJSONObject(index) ?: continue
                            val pkg = app.optString("packageName")
                            if (cacheItems.none { it.packageName == pkg }) continue
                            actualApps += AppJunkUiItem(pkg, applicationLabel(pkg), "应用缓存",
                                app.optLong("files"), app.optLong("bytes"), app.optLong("errors"))
                        }
                    }

                    if (!cancelled && profileItems.isNotEmpty()) {
                        val selection = JSONObject()
                        profileItems.forEach { selection.put(it.id.removePrefix("profile:"), true) }
                        val profileResult = JSONObject(
                            profile.cleanProfileSelected(profileSnapshotId, selection.toString(), options)
                        )
                        bytes += profileResult.optLong("deletedBytes", 0L).coerceAtLeast(0L)
                        files += profileResult.optLong("deletedFiles", 0L).coerceAtLeast(0L)
                        failures += profileResult.optInt("failures", if (profileResult.optBoolean("success")) 0 else 1).coerceAtLeast(0)
                        cleanedCandidates += profileResult.optInt("cleanedCandidates", 0).coerceAtLeast(0)
                        cancelled = cancelled || profileResult.optBoolean("cancelled")
                        messages += profileResult.optString("message", "安全项目处理完成")
                        val details = profileResult.optJSONArray("details") ?: JSONArray()
                        for (index in 0 until details.length()) {
                            val detail = details.optJSONObject(index) ?: continue
                            val status = when (detail.optString("action")) {
                                "cleaned" -> "已清理"
                                "partial" -> "部分清理：${detail.optString("reason")}"
                                else -> "未清理：${detail.optString("reason")}"
                            }
                            val id = "profile:${detail.optString("id")}"
                            outcomes[id] = status
                            val original = profileItems.firstOrNull { it.id == id }
                            if (original != null) {
                                val actualBytes = detail.optLong("bytes")
                                val actualFiles = detail.optLong("files")
                                if (original.packageName.isNotBlank()) {
                                    actualApps += AppJunkUiItem(original.packageName, original.appName, original.title,
                                        actualFiles, actualBytes, if (detail.optString("action") == "cleaned") 0L else 1L)
                                } else {
                                    actualJunk += GeneralJunkUiItem(original.title, actualFiles, actualBytes,
                                        if (detail.optString("action") == "cleaned") 0L else 1L, original.path)
                                }
                            }
                        }
                    }

                    runCatching {
                        profile.recordNativeTask(
                            JSONObject()
                                .put("mode", "workbench-clean")
                                .put("success", failures == 0 && !cancelled)
                                .put("cancelled", cancelled)
                                .put("bytes", bytes)
                                .put("files", files)
                                .put("errors", failures)
                                .put("result", "工作台清理完成，处理 $cleanedCandidates 个候选")
                                .toString()
                        )
                    }
                    val groupedApps = actualApps.groupBy { it.packageName }.values.map { entries ->
                        entries.first().copy(files = entries.sumOf { it.files }, bytes = entries.sumOf { it.bytes },
                            errors = entries.sumOf { it.errors }, categories = entries.map {
                                AppJunkCategoryUiItem(it.category, it.files, it.bytes, it.errors, "")
                            })
                    }
                    LastCleanupStore.save(this@ScanWorkbenchActivity, groupedApps, actualJunk)
                    CleanAggregate(bytes, files, failures, cleanedCandidates, messages, outcomes, cancelled)
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
                    items = screenState.items.map { item -> item.copy(
                        outcome = if (item.id in screenState.selectedIds) result.outcomes[item.id] ?: "未完成或未返回结果" else "未勾选，保留",
                        selectable = false
                    ) },
                    selectedIds = emptySet(),
                    phase = if (result.cancelled) "清理已停止，已处理结果保留" else if (result.failures == 0) "已完成所选项目清理" else "清理完成，但有 ${result.failures} 个异常",
                    resultText = "释放 ${formatBytes(result.bytes)} · 文件 ${result.files} · 候选 ${result.candidates}\n${result.messages.filter { it.isNotBlank() }.joinToString("\n")}"
                )
            }.onFailure {
                screenState = screenState.copy(
                    running = false,
                    scanReady = false,
                    items = screenState.items.map { it.copy(selectable = false, outcome = "任务中断，处理状态未确认") },
                    selectedIds = emptySet(),
                    phase = "所选项目清理失败：${it.message ?: it.javaClass.simpleName}"
                )
            }
            saveReview()
        }
    }

    private fun quarantineItem(item: WorkbenchItem) {
    if (screenState.running || screenState.loadingResults || stopJob?.isActive == true || item !in screenState.items || item.source != "profile" || item.risk != "high" || !cleanupPolicy.canQuarantineHighRisk) return
    if (!screenState.scanReady || SystemClock.elapsedRealtime() >= snapshotExpiresAtRealtime) {
        screenState = screenState.copy(scanReady = false, phase = "扫描快照已过期，请重新扫描")
        return
    }
    val service = profileService ?: return
    screenState = screenState.copy(
        running = true,
        phase = "正在把 ${item.title} 移入隔离区…",
        currentPath = item.path
    )
    startPolling()
    lifecycleScope.launch {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val selection = JSONObject().put(item.id.removePrefix("profile:"), true)
                JSONObject(service.quarantineProfileSelected(profileSnapshotId, selection.toString(), optionsJson(service)))
            }
        }
        pollJob?.cancel()
        result.onSuccess { json ->
            if (json.optBoolean("success") && json.optInt("quarantinedCandidates") > 0) {
                cacheSnapshotId = ""
                profileSnapshotId = ""
                snapshotExpiresAtRealtime = 0L
                screenState = screenState.copy(
                    running = false,
                    scanReady = false,
                    items = emptyList(),
                    selectedIds = emptySet(),
                    phase = json.optString("message", "高风险项目已移入隔离区"),
                    resultText = "已隔离 ${json.optInt("quarantinedCandidates")} 项 · ${formatBytes(json.optLong("quarantinedBytes"))}"
                )
                Toast.makeText(this@ScanWorkbenchActivity, "已移入隔离区，可随时恢复", Toast.LENGTH_SHORT).show()
                delay(180L)
                runScan()
            } else {
                screenState = screenState.copy(
                    running = false,
                    phase = json.optString("message", json.optString("error", "隔离失败"))
                )
            }
        }.onFailure {
            screenState = screenState.copy(running = false, phase = "隔离失败：${it.message ?: it.javaClass.simpleName}")
        }
    }
}

    private fun protectItem(item: WorkbenchItem) {
        if (screenState.running || item !in screenState.items) return
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
        val group = screenState.items.filter { it.groupKey == groupKey && it.selectable && it.risk in setOf("low", "medium") }
        if (group.isEmpty()) return
        val selected = screenState.selectedIds.toMutableSet()
        val shouldSelect = group.any { it.id !in selected }
        group.forEach { if (shouldSelect) selected += it.id else selected -= it.id }
        screenState = screenState.copy(selectedIds = selected)
    }

    private fun selectAllSafe() {
        if (screenState.running) return
        screenState = screenState.copy(
            selectedIds = screenState.items.asSequence().filter { it.selectable && it.risk in setOf("low", "medium") }.mapTo(linkedSetOf()) { it.id }
        )
    }

    private fun clearSelection() {
        if (!screenState.running) screenState = screenState.copy(selectedIds = emptySet())
    }

    private fun stopTask() {
        if (stopJob?.isActive == true) return
        val profile = profileService
        val cache = cacheService
        val stoppingScan = scanJob?.isActive == true
        if (stoppingScan) {
            invalidateScanLoad()
            scanJob?.cancel()
        }
        val stoppingGeneration = if (stoppingScan) scanGeneration.start() else null
        screenState = screenState.copy(phase = "正在安全停止当前任务…")
        stopJob = lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { profile?.cancelCurrentTask() }
                runCatching { cache?.cancelCurrentTask() }
            }
            if (stoppingGeneration != null && scanGeneration.accepts(stoppingGeneration)) {
                scanGeneration.invalidate()
                screenState = screenState.copy(running = false, scanReady = false,
                    phase = "扫描 / 结果读取已停止；已加载项目仅供查看，请重新扫描")
                saveReview()
            }
        }
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

    private fun optionsJson(service: IProfileRootService, suppliedConfig: JSONObject? = null): String {
        val config = suppliedConfig ?: runCatching { JSONObject(service.getSchedulerConfig()) }.getOrDefault(JSONObject())
        val policy = CleanupPolicy.fromId(config.optInt("cleanup_policy", CleanupPolicy.BALANCED.id))
        val paths = runCatching { JSONArray(service.getWhitelistPaths()) }.getOrDefault(JSONArray())
        val packages = runCatching { JSONArray(service.getWhitelistPackages()) }.getOrDefault(JSONArray())
        val maxMb = config.optInt("max_file_mb", 256).coerceIn(16, 16_384)
        return JSONObject()
            .put("whitelistPackages", packages)
            .put("whitelistPaths", paths)
            .put("maxFileBytes", maxMb * 1_024L * 1_024L)
            .put("fragmentDays", config.optInt("fragment_days", 7).coerceIn(0, 365))
            .put("allowHighRisk", false)
            .put("maxAutoRisk", policy.autoRisk)
            .put("highRiskMode", policy.highRiskMode)
            .put("cleanupPolicy", policy.key)
            .put("includeReviewRules", true)
            .toString()
    }

    private fun applicationLabel(packageName: String): String = labels.getOrPut(packageName) { runCatching {
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
    }.getOrNull() ?: packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() } }

    private fun saveReview() {
        val state = screenState
        val cacheId = cacheSnapshotId
        val profileId = profileSnapshotId
        val policyId = cleanupPolicy.id
        val expires = System.currentTimeMillis() + (snapshotExpiresAtRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        ScanReviewStore.save(this, scanProfile) {
            JSONObject().put("cacheSnapshotId", cacheId).put("profileSnapshotId", profileId)
                .put("expiresAt", expires).put("scanReady", state.scanReady && !state.running && !state.loadingResults)
                .put("loadingResults", state.loadingResults)
                .put("phase", state.phase).put("resultText", state.resultText)
                .put("policyId", policyId).put("selected", JSONArray(state.selectedIds.toList()))
                .put("items", JSONArray().apply { state.items.forEach { item -> put(JSONObject()
                    .put("id", item.id).put("source", item.source).put("profile", item.profile)
                    .put("packageName", item.packageName).put("appName", item.appName)
                    .put("category", item.category).put("groupKey", item.groupKey).put("groupTitle", item.groupTitle)
                    .put("title", item.title).put("risk", item.risk).put("path", item.path)
                    .put("bytes", item.bytes).put("files", item.files).put("directories", item.directories)
                    .put("reason", item.reason).put("selectable", item.selectable).put("outcome", item.outcome)
                ) } })
        }
    }

    private suspend fun restoreReview(saved: JSONObject) {
        val array = saved.optJSONArray("items") ?: return
        val items = withContext(Dispatchers.Default) {
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { item ->
                WorkbenchItem(item.optString("id"), item.optString("source"), item.optString("profile"),
                    item.optString("packageName"), item.optString("appName"), item.optString("category"),
                    item.optString("groupKey"), item.optString("groupTitle"), item.optString("title"),
                    item.optString("risk"), item.optString("path"), item.optLong("bytes", -1),
                    item.optLong("files", -1), item.optLong("directories", -1), item.optString("reason"),
                    item.optBoolean("selectable"), item.optString("outcome"))
            } }
        }
        if (items.isEmpty()) return
        restoredReview = true
        cacheSnapshotId = saved.optString("cacheSnapshotId")
        profileSnapshotId = saved.optString("profileSnapshotId")
        val remaining = (saved.optLong("expiresAt") - System.currentTimeMillis()).coerceIn(0L, SNAPSHOT_TTL_MS)
        snapshotExpiresAtRealtime = SystemClock.elapsedRealtime() + remaining
        cleanupPolicy = CleanupPolicy.fromId(saved.optInt("policyId", CleanupPolicy.BALANCED.id))
        val selected = saved.optJSONArray("selected") ?: JSONArray()
        val selectedIds = withContext(Dispatchers.Default) {
            (0 until selected.length()).map { selected.optString(it) }.toSet()
        }
        val incomplete = saved.optBoolean("loadingResults")
        screenState = screenState.copy(items = items,
            selectedIds = selectedIds,
            scanReady = saved.optBoolean("scanReady") && !incomplete && remaining > 0L,
            expiresAtRealtime = snapshotExpiresAtRealtime,
            phase = if (incomplete) "上次结果读取未完成；已加载项目仅供查看，请重新扫描"
                else if (remaining > 0L) saved.optString("phase") else "上次结果已保留；重新扫描后可继续清理",
            resultText = saved.optString("resultText"), policyTitle = cleanupPolicy.title,
            policyKey = cleanupPolicy.key, highRiskMode = cleanupPolicy.highRiskMode)
    }

    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(24)

    private fun formatBytes(bytes: Long): String = Formatter.formatFileSize(this, bytes.coerceAtLeast(0L))

    companion object {
        private const val CACHE_PAGE_SIZE = 100
        private const val PROFILE_PAGE_SIZE = 60
        const val EXTRA_PROFILE = "review_profile"
        private const val SNAPSHOT_TTL_MS = 30L * 60L * 1_000L
    }
}

private data class CleanAggregate(
    val bytes: Long,
    val files: Long,
    val failures: Int,
    val candidates: Int,
    val messages: List<String>,
    val outcomes: Map<String, String>,
    val cancelled: Boolean
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
    val selectable: Boolean,
    val outcome: String = ""
)

private data class WorkbenchUiState(
    val profileConnected: Boolean = false,
    val cacheConnected: Boolean = false,
    val cacheRequired: Boolean = true,
    val running: Boolean = false,
    val loadingResults: Boolean = false,
    val scanReady: Boolean = false,
    val phase: String = "等待 Root 服务",
    val progressCurrent: Long = 0L,
    val progressTotal: Long = 0L,
    val currentPath: String = "",
    val items: List<WorkbenchItem> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val expiresAtRealtime: Long = 0L,
    val resultText: String = "",
    val policyTitle: String = CleanupPolicy.BALANCED.title,
    val policyKey: String = CleanupPolicy.BALANCED.key,
    val highRiskMode: String = CleanupPolicy.BALANCED.highRiskMode
) {
    val connected: Boolean get() = profileConnected && (!cacheRequired || cacheConnected)
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
    val onProtect: (WorkbenchItem) -> Unit,
    val onQuarantine: (WorkbenchItem) -> Unit
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

private data class WorkbenchPresentation(
    val groups: List<WorkbenchGroup> = emptyList(),
    val rows: List<WorkbenchRow> = emptyList(),
    val selectedBytes: Long = 0L,
    val selectableCount: Int = 0,
    val appCount: Int = 0,
    val profileCount: Int = 0,
    val protectedCount: Int = 0
)

private fun workbenchPresentation(
    items: List<WorkbenchItem>, selectedIds: Set<String>, filter: String,
    expandedGroups: Set<String>, loading: Boolean
): WorkbenchPresentation {
    val filtered = items.filter { item ->
        when (filter) {
            "unselected" -> item.id !in selectedIds && !item.outcome.startsWith("已清理") && !item.outcome.startsWith("已按所选")
            "blocked" -> !item.selectable
            "deep" -> item.profile == "deep"
            "cache" -> item.source == "cache"
            "empty" -> item.profile == "empty" || item.category.startsWith("empty")
            "rules" -> item.profile == "rules" || item.category.contains("rule") || item.category.contains("trash")
            "fragments" -> item.profile == "fragments" || item.category.contains("fragment")
            else -> true
        }
    }
    val groups = filtered.groupBy { it.groupKey }.map { (key, entries) ->
        WorkbenchGroup(key, entries.first().groupTitle, entries,
            entries.sumOf { it.bytes.coerceAtLeast(0L) },
            entries.count { it.id in selectedIds },
            entries.count { it.selectable && (it.risk == "low" || it.risk == "medium") })
    }.let { if (loading) it else it.sortedByDescending { group -> group.bytes } }
    val rows = buildList<WorkbenchRow> {
        groups.forEach { group ->
            add(WorkbenchRow.Group(group))
            if (group.key in expandedGroups) group.items.forEach { add(WorkbenchRow.Candidate(it)) }
        }
    }
    return WorkbenchPresentation(groups, rows,
        items.sumOf { if (it.id in selectedIds) it.bytes.coerceAtLeast(0L) else 0L },
        items.count { it.selectable },
        items.asSequence().map { it.packageName }.filter { it.isNotBlank() }.distinct().count(),
        items.count { it.source == "profile" }, items.count { !it.selectable })
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

    val presentation by produceState(
        initialValue = WorkbenchPresentation(),
        state.items, state.selectedIds, filter, expandedGroups, state.loadingResults
    ) {
        value = withContext(Dispatchers.Default) {
            workbenchPresentation(state.items, state.selectedIds, filter, expandedGroups, state.loadingResults)
        }
    }
    val groups = presentation.groups
    val rows = presentation.rows
    LaunchedEffect(groups) {
        if (expandedGroups.isEmpty() && groups.isNotEmpty()) {
            expandedGroups = groups.take(if (groups.size <= 4) groups.size else 2).mapTo(linkedSetOf()) { it.key }
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
            if (state.items.isNotEmpty()) {
                item {
                    WorkbenchSummaryCard(
                        state = state,
                        shape = shape,
                        horizontal = horizontal,
                        presentation = presentation,
                        selectedBytes = Formatter.formatFileSize(context, presentation.selectedBytes)
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
                            "unselected" to "未选择 / 未清理",
                            "blocked" to "不可清理",
                            "deep" to "深度规则",
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
                        OutlinedButton(onClick = actions.onSelectAll, enabled = !state.running, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.SelectAll, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("全选安全项")
                        }
                        OutlinedButton(onClick = actions.onClear, enabled = !state.running, modifier = Modifier.weight(1f)) {
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
                            enabled = !state.running,
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
                            highRiskMode = state.highRiskMode,
                            enabled = !state.running,
                            onToggle = { actions.onToggleItem(row.item.id) },
                            onProtect = { actions.onProtect(row.item) },
                            onQuarantine = { actions.onQuarantine(row.item) }
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
                            enabled = state.scanReady && state.selectedIds.isNotEmpty() && !state.running,
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
                if (state.loadingResults && state.progressTotal > 0L) {
                    LinearProgressIndicator(
                        progress = { (state.progressCurrent.toFloat() / state.progressTotal).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (state.loadingResults) {
                    Text("已展示 ${state.items.size} 项；分批更新预览，完成后统一排序", fontSize = 11.sp)
                }
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
                "扫描后可按应用和垃圾项目勾选。结果会保留；超过 30 分钟需重新扫描后再清理。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onScan, enabled = !state.running, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.connected) "开始扫描" else "重试 Root 连接")
            }
        }
    }
}

@Composable
private fun WorkbenchSummaryCard(
    state: WorkbenchUiState,
    shape: Shape,
    horizontal: androidx.compose.ui.unit.Dp,
    selectedBytes: String,
    presentation: WorkbenchPresentation
) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(19.dp)) {
            Text("已选 ${state.selectedIds.size} / ${presentation.selectableCount} 项", fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            Text("预计至少释放 $selectedBytes", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryPill("应用 ${presentation.appCount}")
                SummaryPill("其他 ${presentation.profileCount}")
                SummaryPill("受保护 ${presentation.protectedCount}")
                SummaryPill("${state.policyTitle}档")
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
    enabled: Boolean,
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
                enabled = enabled && group.selectableCount > 0
            )
            val owner = group.items.firstOrNull()?.packageName.orEmpty()
            if (owner.isNotBlank()) {
                ApplicationIcon(owner, group.title, Modifier.size(39.dp))
            } else {
                Icon(categoryIcon(group.items.firstOrNull()?.profile.orEmpty()), null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(39.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(group.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${group.items.size} 项 · 已选 ${group.selectedCount} · ${Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current, group.bytes)}" +
                        if (group.items.any { it.risk == "high" }) " · 含高风险，需逐项选择" else "",
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
    highRiskMode: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    onProtect: () -> Unit,
    onQuarantine: () -> Unit
) {
    Row(
        modifier = Modifier
            .padding(horizontal = horizontal + 9.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(enabled = enabled && item.selectable, onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = enabled && item.selectable)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                RiskBadge(item.risk)
            }
            Text(
                item.outcome.ifBlank { item.reason },
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
                    append(" · ").append(item.path)
                },
                color = MaterialTheme.colorScheme.outline,
                fontSize = 9.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (item.risk == "high" && highRiskMode != "audit") {
            IconButton(onClick = onQuarantine, enabled = enabled, modifier = Modifier.size(38.dp)) {
                Icon(
                    Icons.Rounded.Inventory2,
                    contentDescription = if (highRiskMode == "recommended_quarantine") "建议移入隔离区" else "移入隔离区",
                    modifier = Modifier.size(19.dp),
                    tint = if (highRiskMode == "recommended_quarantine") BaiZeTokens.colors.warning else MaterialTheme.colorScheme.error
                )
            }
        } else if (item.risk != "high" && item.risk != "critical") {
            IconButton(onClick = onProtect, enabled = enabled, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.Shield, contentDescription = "加入白名单", modifier = Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
            }
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

private fun riskReason(risk: String, label: String, policy: CleanupPolicy): String = when (risk) {
    "low" -> "$label · 可安全自动处理"
    "medium" -> "$label · 清理前再次校验白名单与大小限制"
    "high" -> when (policy.highRiskMode) {
        "audit" -> "$label · 保守档仅审计，切换策略并重新扫描后才可隔离"
        "recommended_quarantine" -> "$label · 积极档建议逐项移入隔离区，仍不会直接删除"
        else -> "$label · 不会直接删除，可单独移入隔离区"
    }
    else -> "$label · 关键风险项目，只展示不自动清理"
}
