package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.root.RootServiceClients
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.BaiZeRootService
import io.github.xgl34222220.baize.root.IBaiZeRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    private val scanProfile get() = intent.getStringExtra(EXTRA_PROFILE)
        ?.takeIf { it in setOf("safe", "deep", "rules", "empty", "fragments", "corpses") } ?: "safe"
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
            profileService = RootServiceClients.profile(binder, applicationContext.cacheDir)
            profileBound = true
            screenState = screenState.copy(profileConnected = true)
            maybeStartScan()
        }
        override fun onNullBinding(name: ComponentName?) {
            RootService.unbind(this)
            onServiceDisconnected(name)
            screenState = screenState.copy(notice = WorkbenchNotice.ERROR, phase = "Root 启动失败，请检查授权后重试")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            invalidateScanLoad()
            scanJob?.cancel()
            saveReview()
            profileService = null
            profileBound = false
            screenState = screenState.copy(profileConnected = false, running = false,
                notice = WorkbenchNotice.ERROR, phase = "Root 详情引擎连接已断开")
        }
    }
    private val cacheConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            cacheService = RootServiceClients.cache(binder, applicationContext.cacheDir)
            cacheBound = true
            screenState = screenState.copy(cacheConnected = true)
            maybeStartScan()
        }
        override fun onNullBinding(name: ComponentName?) {
            RootService.unbind(this)
            onServiceDisconnected(name)
            screenState = screenState.copy(notice = WorkbenchNotice.ERROR, phase = "Root 启动失败，请检查授权后重试")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            invalidateScanLoad()
            scanJob?.cancel()
            saveReview()
            cacheService = null
            cacheBound = false
            screenState = screenState.copy(cacheConnected = false, running = false,
                notice = WorkbenchNotice.ERROR, phase = "Root 缓存引擎连接已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screenState = screenState.copy(cacheRequired = scanProfile == "safe")
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
                    ScanWorkbenchScreen(appearance, screenState, WorkbenchActions(
                        onBack = ::finish, onScan = ::runScan, onStop = ::stopTask,
                        onClean = ::cleanSelection, onToggleItem = ::toggleItem,
                        onToggleGroup = ::toggleGroup, onSelectAll = ::selectAllSafe,
                        onClear = ::clearSelection, onProtect = ::protectItem,
                        onQuarantine = ::quarantineItem, onSelectMedium = ::selectAllMedium,
                        onManageWhitelist = ::openWhitelist
                    ))
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
        if (!restoredReview || screenState.notice != WorkbenchNotice.ERROR) {
            screenState = screenState.copy(notice = WorkbenchNotice.INFO, phase = "正在连接双 Root 快照引擎…")
        }
        if (!profileBound) {
            profileBound = true
            runCatching {
                RootService.bind(Intent(this, BaiZeProfileRootService::class.java)
                    .addCategory(RootService.CATEGORY_DAEMON_MODE), profileConnection)
            }.onFailure { profileBound = false; screenState = screenState.copy(notice = WorkbenchNotice.ERROR, phase = "详情引擎启动失败：${it.message.orEmpty()}") }
        }
        if (scanProfile == "safe" && !cacheBound) {
            cacheBound = true
            runCatching {
                RootService.bind(Intent(this, BaiZeRootService::class.java)
                    .addCategory(RootService.CATEGORY_DAEMON_MODE), cacheConnection)
            }.onFailure { cacheBound = false; screenState = screenState.copy(notice = WorkbenchNotice.ERROR, phase = "缓存引擎启动失败：${it.message.orEmpty()}") }
        }
    }

    private fun maybeStartScan() {
        if (profileService == null || (scanProfile == "safe" && cacheService == null)) return
        if (restoredReview) {
            if (screenState.notice != WorkbenchNotice.ERROR) {
                screenState = screenState.copy(
                    notice = if (screenState.scanReady) WorkbenchNotice.INFO else WorkbenchNotice.WARNING,
                    phase = if (screenState.scanReady) "已恢复上次扫描，可继续选择" else "已恢复上次结果，重新扫描后可清理")
            }
            return
        }
        if (autoScanStarted) return
        autoScanStarted = true
        lifecycleScope.launch { delay(180L); runScan() }
    }

    private fun runScan() {
        if (screenState.running || stopJob?.isActive == true) return
        val profile = profileService
        val cache = cacheService
        if (profile == null || (scanProfile == "safe" && cache == null)) {
            connectServices()
            return
        }
        restoredReview = false
        val generation = scanGeneration.start()
        cacheSnapshotId = ""
        profileSnapshotId = ""
        snapshotExpiresAtRealtime = 0L
        screenState = screenState.copy(running = true, loadingResults = false, scanReady = false,
            notice = WorkbenchNotice.INFO, phase = "正在并行扫描应用缓存与安全项目…",
            progressCurrent = 0L, progressTotal = 0L, currentPath = "",
            items = emptyList(), selectedIds = emptySet(), resultText = "", expiresAtRealtime = 0L)
        // Persist invalidation before any result page, including across rotation.
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
                            val json = if (scanProfile != "safe") JSONObject().put("snapshotId", "")
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
                val cacheOk = scanProfile == "safe" && !cacheJson.has("error") && !cacheJson.optBoolean("cancelled")
                    && cacheJson.optString("snapshotId").isNotBlank()
                val profileOk = profileJson.optBoolean("success") && !profileJson.optBoolean("cancelled")
                    && profileJson.optString("snapshotId").isNotBlank()
                if (!cacheOk && !profileOk) error(profileJson.optString("message", cacheJson.optString("message", "未返回有效快照")))
                cacheSnapshotId = if (cacheOk) cacheJson.optString("snapshotId") else ""
                profileSnapshotId = if (profileOk) profileJson.optString("snapshotId") else ""
                val cacheId = cacheSnapshotId
                val profileId = profileSnapshotId
                snapshotExpiresAtRealtime = minOf(
                    if (cacheOk) cacheCompletedAt - cacheJson.optLong("elapsedMs", 0L).coerceAtLeast(0L) - 1_000L +
                        cacheJson.optLong("snapshotExpiresInMs", SNAPSHOT_TTL_MS).coerceIn(0L, SNAPSHOT_TTL_MS)
                    else Long.MAX_VALUE,
                    if (profileOk) profileCompletedAt + profileJson.optLong("snapshotExpiresInMs", SNAPSHOT_TTL_MS)
                        .coerceIn(0L, SNAPSHOT_TTL_MS) else Long.MAX_VALUE)
                val partial = profileJson.optBoolean("partial") || !profileOk || (scanProfile == "safe" && !cacheOk)
                val warning = if (partial) "本轮扫描未覆盖全部范围；仅展示有效快照中的项目。" else ""
                screenState = screenState.copy(loadingResults = true, notice = WorkbenchNotice.INFO, phase = "扫描结束，正在读取结果…",
                    progressCurrent = 0L, progressTotal = 0L, currentPath = "",
                    policyTitle = policy.title, policyKey = policy.key, highRiskMode = policy.highRiskMode,
                    expiresAtRealtime = snapshotExpiresAtRealtime,
                    resultText = warning + "读取期间仅供预览，全部读取完成后才可选择和清理。")
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
                        screenState = screenState.copy(notice = WorkbenchNotice.INFO,
                            phase = "正在读取结果 · 已读取 $loaded 项" + if (total > 0) " / $total" else "",
                            progressCurrent = loaded, progressTotal = total,
                            items = if (newer) requireNotNull(review).items else screenState.items,
                            selectedIds = if (newer) requireNotNull(review).selectedIds else screenState.selectedIds)
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
                screenState = screenState.copy(running = false, loadingResults = false,
                    scanReady = review.items.isNotEmpty() && !expired,
                    notice = if (expired || partial) WorkbenchNotice.WARNING else WorkbenchNotice.SUCCESS,
                    phase = when {
                        expired -> "扫描快照已过期，结果仅供查看，请重新扫描"
                        partial -> "本轮扫描未覆盖全部范围，已读取 ${review.items.size} 项"
                        review.items.isEmpty() -> "扫描完成，没有发现垃圾项目"
                        else -> "扫描完成，展开应用或分类后选择要清理的项目"
                    }, items = review.items, selectedIds = review.selectedIds,
                    resultText = warning + if (review.items.isEmpty()) {
                        if (partial) "未发现可展示项目，请重新扫描。" else "当前设备很干净"
                    } else if (policy == CleanupPolicy.CONSERVATIVE) {
                        "保守档默认只勾选低风险项目；中风险仍可手动选择"
                    } else {
                        "默认勾选低、中风险项目；高风险默认不选，可逐项选择。大小未统计的项目也会列出。"
                    })
                scanGeneration.invalidate()
                saveReview()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!scanGeneration.accepts(generation)) return@launch
                val wasLoading = screenState.loadingResults
                invalidateScanLoad()
                screenState = screenState.copy(running = false, notice = WorkbenchNotice.ERROR,
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

    private suspend fun loadCacheItems(service: IBaiZeRootService, snapshotId: String,
        onPage: suspend (List<WorkbenchItem>, ScanPageCursor) -> Unit) {
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
                result += WorkbenchItem(id = "cache:${stableId("$packageName\u0000$category\u0000$path")}",
                    source = "cache", profile = "cache", packageName = packageName, appName = appName,
                    category = category, groupKey = "app:$packageName", groupTitle = appName,
                    title = category, risk = "low", path = path,
                    bytes = item.optLong("bytes", 0L).coerceAtLeast(0L),
                    files = item.optLong("files", 0L).coerceAtLeast(0L),
                    directories = item.optLong("directories", 0L).coerceAtLeast(0L),
                    reason = "应用缓存快照命中 · 只删除扫描时记录的文件", selectable = true)
            }
            onPage(result, cursor)
        }
    }

    private suspend fun loadProfileItems(service: IProfileRootService, snapshotId: String,
        onPage: suspend (List<WorkbenchItem>, ScanPageCursor) -> Unit) {
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
                result += WorkbenchItem(id = "profile:$candidateId", source = "profile", profile = profile,
                    packageName = packageName, appName = appName, category = category,
                    groupKey = if (packageName.isNotBlank()) "app:$packageName" else "category:$profile:$label",
                    groupTitle = if (packageName.isNotBlank()) appName else label,
                    title = File(path).name.ifBlank { label }, risk = risk, path = path,
                    bytes = item.optLong("bytes", -1L), files = item.optLong("files", -1L),
                    directories = item.optLong("directories", -1L),
                    reason = item.optString("blockedReason").ifBlank {
                        if (risk == "high") "默认不清理；可能包含离线内容或应用数据，确认用途后勾选。$note"
                        else note.ifBlank { riskReason(risk, label, cleanupPolicy) }
                    }, selectable = ReviewRiskPolicy.selectable(risk, item.optString("blockedReason")))
            }
            onPage(result, cursor)
        }
    }

    private fun cleanSelection() {
        if (screenState.running || screenState.loadingResults || stopJob?.isActive == true) return
        if (!screenState.scanReady || SystemClock.elapsedRealtime() >= snapshotExpiresAtRealtime) {
            screenState = screenState.copy(scanReady = false, notice = WorkbenchNotice.WARNING, phase = "扫描快照已过期，请重新扫描")
            return
        }
        val profile = profileService ?: return
        val cache = cacheService
        val selected = screenState.items.filter { it.selectable && it.id in screenState.selectedIds }
        if (selected.isEmpty()) {
            screenState = screenState.copy(notice = WorkbenchNotice.WARNING, phase = "请至少勾选一个安全项目")
            return
        }
        val cacheItems = selected.filter { it.source == "cache" }
        val profileItems = selected.filter { it.source == "profile" }
        screenState = screenState.copy(running = true, notice = WorkbenchNotice.INFO,
            phase = "正在校验并清理 ${selected.size} 个已勾选项目…", progressCurrent = 0L,
            progressTotal = selected.size.toLong(), currentPath = "")
        startPolling()
        saveReview()
        val attempt = CleanupAttempt()
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
                    var incomplete = false
                    val messages = ArrayList<String>()
                    val outcomes = HashMap<String, String>()
                    val actualApps = ArrayList<AppJunkUiItem>()
                    val actualJunk = ArrayList<GeneralJunkUiItem>()
                    if (cacheItems.isNotEmpty()) {
                        val cache = requireNotNull(cache)
                        val selection = JSONObject()
                        cacheItems.forEach { selection.put(it.path, true) }
                        val prepared = JSONObject(attempt.authorize {
                            profile.prepareCacheSelection(cacheSnapshotId, selection.toString())
                        })
                        if (!prepared.optBoolean("success")) error(prepared.optString("message", prepared.optString("error", "无法裁剪缓存快照")))
                        val selectedSnapshotId = prepared.optString("snapshotId")
                        val cacheResult = JSONObject(attempt.mutate {
                            cache.cleanSelected(selectedSnapshotId, JSONObject().put("__all_safe__", true).toString(), packageWhitelist)
                        })
                        bytes += cacheResult.optLong("deletedBytes", 0L).coerceAtLeast(0L)
                        files += cacheResult.optLong("deletedFiles", 0L).coerceAtLeast(0L)
                        failures += cacheResult.optInt("failures", if (cacheResult.optBoolean("success")) 0 else 1).coerceAtLeast(0)
                        cleanedCandidates += cacheResult.optInt("cleanedCandidates", 0).coerceAtLeast(0)
                        cancelled = cacheResult.optBoolean("cancelled")
                        incomplete = incomplete || !cacheResult.optBoolean("success") || cancelled || cacheResult.optInt("failures") > 0
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
                            actualApps += AppJunkUiItem(pkg, applicationLabel(pkg), "应用缓存", app.optLong("files"), app.optLong("bytes"), app.optLong("errors"))
                        }
                    }
                    if (!cancelled && profileItems.isNotEmpty()) {
                        val selection = JSONObject()
                        profileItems.forEach { selection.put(it.id.removePrefix("profile:"), true) }
                        val profileResult = JSONObject(attempt.mutate {
                            profile.cleanProfileSelected(profileSnapshotId, selection.toString(), options)
                        })
                        bytes += profileResult.optLong("deletedBytes", 0L).coerceAtLeast(0L)
                        files += profileResult.optLong("deletedFiles", 0L).coerceAtLeast(0L)
                        failures += profileResult.optInt("failures", if (profileResult.optBoolean("success")) 0 else 1).coerceAtLeast(0)
                        cleanedCandidates += profileResult.optInt("cleanedCandidates", 0).coerceAtLeast(0)
                        cancelled = cancelled || profileResult.optBoolean("cancelled")
                        incomplete = incomplete || !profileResult.optBoolean("success") || cancelled || profileResult.optBoolean("timedOut") ||
                            profileResult.optInt("skippedCandidates") > 0 || profileResult.optInt("failures") > 0
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
                            if (detail.optString("action") != "cleaned") incomplete = true
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
                        profile.recordNativeTask(JSONObject().put("mode", "workbench-clean")
                            .put("success", !incomplete && failures == 0 && !cancelled)
                            .put("cancelled", cancelled).put("bytes", bytes).put("files", files).put("errors", failures)
                            .put("result", "工作台清理完成，处理 $cleanedCandidates 个候选").toString())
                    }
                    val groupedApps = actualApps.groupBy { it.packageName }.values.map { entries ->
                        entries.first().copy(files = entries.sumOf { it.files }, bytes = entries.sumOf { it.bytes },
                            errors = entries.sumOf { it.errors }, categories = entries.map {
                                AppJunkCategoryUiItem(it.category, it.files, it.bytes, it.errors, "")
                            })
                    }
                    LastCleanupStore.save(this@ScanWorkbenchActivity, groupedApps, actualJunk)
                    CleanAggregate(bytes, files, failures, cleanedCandidates, messages, outcomes, cancelled, incomplete)
                }
            }
            pollJob?.cancel()
            response.onSuccess { result ->
                cacheSnapshotId = ""
                profileSnapshotId = ""
                snapshotExpiresAtRealtime = 0L
                screenState = screenState.copy(running = false, scanReady = false,
                    items = screenState.items.map { item -> item.copy(outcome =
                        if (item.id in screenState.selectedIds) result.outcomes[item.id] ?: "未完成或未返回结果" else "未勾选，保留") },
                    selectedIds = if (result.incomplete) screenState.selectedIds.filterTo(linkedSetOf()) { itWasNotCleaned(it, result.outcomes) } else emptySet(),
                    notice = if (result.incomplete) WorkbenchNotice.WARNING else WorkbenchNotice.SUCCESS, expiresAtRealtime = 0L,
                    phase = if (result.cancelled) "清理已停止，结果与未完成勾选已保留"
                        else if (result.incomplete) "部分项目未完成，重新扫描后可继续清理" else "已完成所选项目清理",
                    resultText = "释放 ${formatBytes(result.bytes)} · 文件 ${result.files} · 候选 ${result.candidates}\n${result.messages.filter { it.isNotBlank() }.joinToString("\n")}")
            }.onFailure { error ->
                val canRetry = !attempt.snapshotTouched && !attempt.cleanupSubmitted && SystemClock.elapsedRealtime() < snapshotExpiresAtRealtime &&
                    profileService != null && (cacheItems.isEmpty() || cacheService != null)
                if (!canRetry) {
                    cacheSnapshotId = ""
                    profileSnapshotId = ""
                    snapshotExpiresAtRealtime = 0L
                }
                screenState = screenState.copy(running = false, scanReady = canRetry, notice = WorkbenchNotice.ERROR,
                    expiresAtRealtime = snapshotExpiresAtRealtime,
                    // Never replay deletion after a lost response. Keep the exact user review.
                    phase = if (attempt.cleanupSubmitted) "清理结果未确认，列表与勾选已保留" else "清理未完成，列表与勾选已保留",
                    resultText = (if (canRetry) "请求尚未执行，可重试。" else "请重新扫描后再清理，避免重复执行。") +
                        "\n" + (error.message ?: error.javaClass.simpleName))
            }
            saveReview()
        }
    }

    private fun quarantineItem(item: WorkbenchItem) {
        if (screenState.running || screenState.loadingResults || stopJob?.isActive == true || item !in screenState.items || item.source != "profile" || item.risk != "high" || !cleanupPolicy.canQuarantineHighRisk) return
        if (!screenState.scanReady || SystemClock.elapsedRealtime() >= snapshotExpiresAtRealtime) {
            screenState = screenState.copy(scanReady = false, notice = WorkbenchNotice.WARNING, phase = "扫描快照已过期，请重新扫描")
            return
        }
        val service = profileService ?: return
        screenState = screenState.copy(running = true, notice = WorkbenchNotice.INFO,
            phase = "正在把 ${item.title} 移入隔离区…", currentPath = item.path)
        startPolling()
        val attempt = CleanupAttempt()
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val selection = JSONObject().put(item.id.removePrefix("profile:"), true)
                    val options = optionsJson(service)
                    JSONObject(attempt.mutate { service.quarantineProfileSelected(profileSnapshotId, selection.toString(), options) })
                }
            }
            pollJob?.cancel()
            result.onSuccess { json ->
                if (json.optBoolean("success") && json.optInt("quarantinedCandidates") > 0) {
                    cacheSnapshotId = ""
                    profileSnapshotId = ""
                    snapshotExpiresAtRealtime = 0L
                    screenState = screenState.copy(running = false, scanReady = false, items = emptyList(), selectedIds = emptySet(),
                        notice = WorkbenchNotice.SUCCESS, phase = json.optString("message", "高风险项目已移入隔离区"),
                        resultText = "已隔离 ${json.optInt("quarantinedCandidates")} 项 · ${formatBytes(json.optLong("quarantinedBytes"))}")
                    Toast.makeText(this@ScanWorkbenchActivity, "已移入隔离区，可随时恢复", Toast.LENGTH_SHORT).show()
                    delay(180L)
                    runScan()
                } else {
                    if (attempt.cleanupSubmitted) {
                        cacheSnapshotId = ""
                        profileSnapshotId = ""
                        snapshotExpiresAtRealtime = 0L
                    }
                    screenState = screenState.copy(running = false,
                        notice = if (json.optBoolean("cancelled") || json.optBoolean("success")) WorkbenchNotice.WARNING else WorkbenchNotice.ERROR,
                        scanReady = !attempt.cleanupSubmitted && SystemClock.elapsedRealtime() < snapshotExpiresAtRealtime,
                        expiresAtRealtime = snapshotExpiresAtRealtime,
                        phase = json.optString("message", json.optString("error", "隔离未完成")),
                        resultText = if (attempt.cleanupSubmitted) "列表与勾选已保留，请重新扫描后继续。" else "请求尚未执行。")
                    saveReview()
                }
            }.onFailure {
                if (attempt.cleanupSubmitted) {
                    cacheSnapshotId = ""
                    profileSnapshotId = ""
                    snapshotExpiresAtRealtime = 0L
                }
                screenState = screenState.copy(running = false, notice = WorkbenchNotice.ERROR,
                    scanReady = !attempt.cleanupSubmitted && SystemClock.elapsedRealtime() < snapshotExpiresAtRealtime,
                    expiresAtRealtime = snapshotExpiresAtRealtime, phase = "隔离结果未确认，列表与勾选已保留",
                    resultText = "${it.message ?: it.javaClass.simpleName}" +
                        if (attempt.cleanupSubmitted) "\n请重新扫描后继续，避免重复执行。" else "\n请求尚未执行，可重试。")
                saveReview()
            }
        }
    }

    private fun protectItem(item: WorkbenchItem) {
        if (screenState.running || item !in screenState.items) return
        val service = profileService ?: return
        screenState = screenState.copy(notice = WorkbenchNotice.INFO, phase = "正在把 ${item.groupTitle} 加入保护白名单…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (item.packageName.isNotBlank()) {
                        val current = JSONArray(service.getWhitelistPackages())
                        val packages = linkedSetOf<String>()
                        for (index in 0 until current.length()) current.optString(index).trim().takeIf { it.isNotBlank() }?.let(packages::add)
                        packages += item.packageName
                        JSONObject(service.saveWhitelistPackages(JSONArray(packages.sorted()).toString()))
                    } else JSONObject(service.addWhitelistPath(item.path))
                }
            }
            result.onSuccess { json ->
                if (json.optBoolean("success")) {
                    cacheSnapshotId = ""
                    profileSnapshotId = ""
                    snapshotExpiresAtRealtime = 0L
                    screenState = screenState.copy(scanReady = false, items = emptyList(), selectedIds = emptySet(),
                        notice = WorkbenchNotice.SUCCESS, phase = "已加入白名单，正在重新生成安全快照…")
                    Toast.makeText(this@ScanWorkbenchActivity, json.optString("message", "已加入白名单"), Toast.LENGTH_SHORT).show()
                    delay(180L)
                    runScan()
                } else screenState = screenState.copy(notice = WorkbenchNotice.ERROR,
                    phase = "白名单保存失败：${json.optString("message", json.optString("error"))}")
            }.onFailure { screenState = screenState.copy(notice = WorkbenchNotice.ERROR,
                phase = "白名单保存失败：${it.message ?: it.javaClass.simpleName}") }
        }
    }

    private fun openWhitelist() {
        if (screenState.running || screenState.loadingResults) return
        invalidateScanLoad()
        screenState = screenState.copy(notice = WorkbenchNotice.INFO,
            phase = "白名单管理后请重新扫描，旧记录不会直接用于删除")
        saveReview()
        startActivity(Intent(this, WhitelistActivity::class.java))
    }

    private fun selectionIsEditable(): Boolean {
        val reason = reviewSelectionBlockReason(screenState, SystemClock.elapsedRealtime())
        if (reason != null && !screenState.running) screenState = screenState.copy(phase = reason)
        return reason == null
    }
    private fun toggleItem(id: String) {
        val item = screenState.items.firstOrNull { it.id == id } ?: return
        if (!item.selectable || item.risk == "critical" || !selectionIsEditable()) return
        val selected = screenState.selectedIds.toMutableSet()
        if (!selected.add(id)) selected.remove(id)
        screenState = screenState.copy(selectedIds = selected)
    }
    private fun toggleGroup(groupKey: String) {
        if (!selectionIsEditable()) return
        val group = screenState.items.filter { it.groupKey == groupKey && it.selectable && it.risk in setOf("low", "medium") }
        if (group.isEmpty()) return
        val selected = screenState.selectedIds.toMutableSet()
        val shouldSelect = group.any { it.id !in selected }
        group.forEach { if (shouldSelect) selected += it.id else selected -= it.id }
        screenState = screenState.copy(selectedIds = selected)
    }
    private fun selectAllSafe() = selectRisks(setOf("low", "medium"))
    private fun selectAllMedium() = selectRisks(setOf("medium"))
    private fun selectRisks(risks: Set<String>) {
        if (!selectionIsEditable()) return
        screenState = screenState.copy(selectedIds = reviewRiskSelection(screenState.items, risks))
    }
    private fun clearSelection() {
        if (selectionIsEditable()) screenState = screenState.copy(selectedIds = emptySet())
    }

    private fun stopTask() {
        if (stopJob?.isActive == true) return
        val profile = profileService
        val cache = cacheService
        val stoppingScan = scanJob?.isActive == true
        if (stoppingScan) { invalidateScanLoad(); scanJob?.cancel() }
        val stoppingGeneration = if (stoppingScan) scanGeneration.start() else null
        screenState = screenState.copy(notice = WorkbenchNotice.INFO, phase = "正在安全停止当前任务…")
        stopJob = lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { profile?.cancelCurrentTask() }
                runCatching { cache?.cancelCurrentTask() }
            }
            if (stoppingGeneration != null && scanGeneration.accepts(stoppingGeneration)) {
                scanGeneration.invalidate()
                screenState = screenState.copy(running = false, scanReady = false, notice = WorkbenchNotice.WARNING,
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
                if (state != null) screenState = screenState.copy(phase = state.optString("phase", screenState.phase),
                    progressCurrent = state.optLong("progress_current", state.optLong("current", 0L)).coerceAtLeast(0L),
                    progressTotal = state.optLong("progress_total", state.optLong("total", 0L)).coerceAtLeast(0L),
                    currentPath = state.optString("current_path", state.optString("path")).takeLast(120))
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
        return JSONObject().put("whitelistPackages", packages).put("whitelistPaths", paths)
            .put("maxFileBytes", maxMb * 1_024L * 1_024L)
            .put("fragmentDays", config.optInt("fragment_days", 7).coerceIn(0, 365))
            .put("allowHighRisk", false).put("maxAutoRisk", policy.autoRisk)
            .put("highRiskMode", policy.highRiskMode).put("cleanupPolicy", policy.key)
            .put("includeReviewRules", true).toString()
    }
    private fun applicationLabel(packageName: String): String = labels.getOrPut(packageName) { runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
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
                .put("loadingResults", state.loadingResults).put("phase", state.phase)
                .put("resultText", state.resultText).put("notice", state.notice.name)
                .put("policyId", policyId).put("selected", JSONArray(state.selectedIds.toList()))
                .put("items", JSONArray().apply { state.items.forEach { item -> put(JSONObject()
                    .put("id", item.id).put("source", item.source).put("profile", item.profile)
                    .put("packageName", item.packageName).put("appName", item.appName)
                    .put("category", item.category).put("groupKey", item.groupKey).put("groupTitle", item.groupTitle)
                    .put("title", item.title).put("risk", item.risk).put("path", item.path)
                    .put("bytes", item.bytes).put("files", item.files).put("directories", item.directories)
                    .put("reason", item.reason).put("selectable", item.selectable).put("outcome", item.outcome)) } })
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
        val selectedIds = withContext(Dispatchers.Default) { (0 until selected.length()).map { selected.optString(it) }.toSet() }
        val incomplete = saved.optBoolean("loadingResults")
        screenState = screenState.copy(items = items, selectedIds = selectedIds,
            scanReady = saved.optBoolean("scanReady") && !incomplete && remaining > 0L,
            expiresAtRealtime = snapshotExpiresAtRealtime,
            phase = if (incomplete) "上次结果读取未完成；已加载项目仅供查看，请重新扫描"
                else if (remaining > 0L) saved.optString("phase") else "上次结果已保留；重新扫描后可继续清理",
            notice = if (incomplete || remaining <= 0L) WorkbenchNotice.WARNING else
                runCatching { WorkbenchNotice.valueOf(saved.optString("notice", "INFO")) }.getOrDefault(WorkbenchNotice.INFO),
            resultText = saved.optString("resultText"), policyTitle = cleanupPolicy.title,
            policyKey = cleanupPolicy.key, highRiskMode = cleanupPolicy.highRiskMode)
    }
    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(24)
    private fun formatBytes(bytes: Long): String = Formatter.formatFileSize(this, bytes.coerceAtLeast(0L))
    companion object {
        private const val CACHE_PAGE_SIZE = 100
        private const val PROFILE_PAGE_SIZE = 60
        const val EXTRA_PROFILE = "review_profile"
        private const val SNAPSHOT_TTL_MS = 30L * 60L * 1_000L
    }
}

private data class CleanAggregate(val bytes: Long, val files: Long, val failures: Int, val candidates: Int,
    val messages: List<String>, val outcomes: Map<String, String>, val cancelled: Boolean, val incomplete: Boolean)
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
private fun itWasNotCleaned(id: String, outcomes: Map<String, String>): Boolean =
    outcomes[id] !in setOf("已清理", "已按所选缓存执行清理")
