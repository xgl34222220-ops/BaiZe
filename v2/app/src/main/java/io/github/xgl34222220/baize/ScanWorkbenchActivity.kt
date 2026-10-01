package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.root.RootServiceClients
import android.app.Application
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.xgl34222220.baize.ui.components.BaiZeDialog
import io.github.xgl34222220.baize.ui.components.BaiZeDialogButton
import androidx.compose.material3.Text
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
import kotlinx.coroutines.CoroutineScope
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
import java.util.concurrent.atomic.AtomicBoolean

class ScanWorkbenchActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private val scanViewModel: ScanWorkbenchViewModel by viewModels()
    internal val session get() = scanViewModel.session
    private var confirmStop by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session.initialize(intent.getStringExtra(EXTRA_PROFILE).orEmpty())
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
                    BackHandler { requestBack() }
                    ScanWorkbenchScreen(appearance, session.screenState, WorkbenchActions(
                        onBack = ::requestBack, onScan = session::runScan, onStop = session::stopTask,
                        onClean = session::cleanSelection, onToggleItem = session::toggleItem,
                        onToggleGroup = session::toggleGroup, onSelectAll = session::selectAllSafe,
                        onClear = session::clearSelection, onProtect = session::protectItem,
                        onQuarantine = session::quarantineItem, onSelectMedium = session::selectAllMedium,
                        onManageWhitelist = { if (session.prepareWhitelist()) CleanerNavigation.open(this, Intent(this, WhitelistActivity::class.java)) },
                        onToggleVisibleItems = session::toggleItems
                    ))
                    if (shouldConfirmStop()) BaiZeDialog(
                        onDismissRequest = { confirmStop = null }, title = { Text("任务仍在进行") },
                        text = { Text("留在这里查看进度，或先停止任务。已扫描和已处理的记录会保留。") },
                        confirmButton = { BaiZeDialogButton({ confirmStop = null; session.stopTask() }) { Text("停止任务") } },
                        dismissButton = { BaiZeDialogButton({ confirmStop = null }) { Text("继续等待") } })
                }
            }
        }
    }

    internal fun shouldConfirmStop(): Boolean = confirmStop == session.operationToken && session.screenState.running

    private fun requestBack() {
        if (session.screenState.running) confirmStop = session.operationToken else finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // SINGLE_TOP intents are navigation, never a command to restart discovery.
    }

    override fun onStop() {
        session.saveReview()
        super.onStop()
    }

    companion object { const val EXTRA_PROFILE = "review_profile" }
}

internal class ScanWorkbenchViewModel(application: Application) : AndroidViewModel(application) {
    val session = ScanWorkbenchSession(application, viewModelScope)
    override fun onCleared() { session.close() }
}

internal class ScanWorkbenchSession(application: Application, private val lifecycleScope: CoroutineScope) : ContextWrapper(application) {
    private var initialized = false
    private var closed = false
    private var operationEpoch = 0L
    val operationToken: Long get() = operationEpoch
    private val needsCache get() = scanProfile in setOf("safe", "cache")
    private var profileService: IProfileRootService? = null
    private var cacheService: IBaiZeRootService? = null
    private var profileBound = false
    private var cacheBound = false
    private var autoScanStarted = false
    private var restoredReview = false
    private var scanRequested = false
    private var scanProfile = "safe"
    private val labels = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var cacheSnapshotId = ""
    private var profileSnapshotId = ""
    private var snapshotExpiresAtRealtime = 0L
    private var cleanupPolicy = CleanupPolicy.BALANCED
    private var pollJob: Job? = null
    private var scanJob: Job? = null
    private var mutationJob: Job? = null
    private var mutationStopRequest: AtomicBoolean? = null
    private var stopJob: Job? = null
    private val scanGeneration = ScanLoadGeneration()
    private val reviewHydration = ReviewHydrationGate()
    var screenState by mutableStateOf(WorkbenchUiState(restoringReview = true, phase = "正在恢复扫描记录…"))
        private set

    private val profileConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (closed) return
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
            if (closed) return
            operationEpoch++
            invalidateScanLoad()
            scanJob?.cancel()
            mutationJob?.cancel()
            profileService = null
            profileBound = false
            screenState = screenState.copy(profileConnected = false, running = false,
                notice = WorkbenchNotice.ERROR, phase = "Root 详情引擎连接已断开，任务结果未确认")
            saveReview()
        }
    }
    private val cacheConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (closed) return
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
            if (closed) return
            operationEpoch++
            invalidateScanLoad()
            scanJob?.cancel()
            mutationJob?.cancel()
            cacheService = null
            cacheBound = false
            screenState = screenState.copy(cacheConnected = false, running = false,
                notice = WorkbenchNotice.ERROR, phase = "Root 缓存引擎连接已断开，任务结果未确认")
            saveReview()
        }
    }

    fun initialize(profile: String) {
        if (initialized) return
        initialized = true
        scanProfile = CleanerNavigation.normalizedProfile(profile)
        screenState = screenState.copy(cacheRequired = needsCache, scanProfile = scanProfile)
        lifecycleScope.launch {
            var loaded = false
            try {
                val saved = withContext(Dispatchers.IO) { ScanReviewStore.read(this@ScanWorkbenchSession, scanProfile, strict = true) }
                if (saved != null) restoreReview(saved)
                loaded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Avoid an automatic replacement after an unreadable record.
                restoredReview = true
                screenState = screenState.copy(notice = WorkbenchNotice.ERROR,
                    phase = "扫描记录读取失败，请重新扫描", resultText = error.message.orEmpty())
            } finally {
                reviewHydration.finish(loaded)
                screenState = screenState.copy(restoringReview = false)
            }
            // Historical/completed reviews are readable without starting Root or prompting again.
            // Only a live snapshot or an explicit rescan needs a service connection.
            if (!restoredReview || screenState.scanReady) connectServices()
        }
    }

    fun close() {
        closed = true
        operationEpoch++
        scanGeneration.invalidate()
        scanJob?.cancel()
        mutationJob?.cancel()
        pollJob?.cancel()
        // Activity recreation retains this session. Only a finished ViewModel releases bindings.
        if (screenState.running) invalidateScanLoad()
        saveReview()
        if (profileBound) runCatching { RootService.unbind(profileConnection) }
        if (cacheBound) runCatching { RootService.unbind(cacheConnection) }
    }

    private fun connectServices() {
        if (closed || reviewHydration.loading) return
        if (!restoredReview || scanRequested) {
            screenState = screenState.copy(notice = WorkbenchNotice.INFO, phase = "正在连接双 Root 快照引擎…")
        }
        if (!profileBound) {
            profileBound = true
            runCatching {
                RootService.bind(Intent(this, BaiZeProfileRootService::class.java)
                    .addCategory(RootService.CATEGORY_DAEMON_MODE), profileConnection)
            }.onFailure { profileBound = false; screenState = screenState.copy(notice = WorkbenchNotice.ERROR, phase = "详情引擎启动失败：${it.message.orEmpty()}") }
        }
        if (needsCache && !cacheBound) {
            cacheBound = true
            runCatching {
                RootService.bind(Intent(this, BaiZeRootService::class.java)
                    .addCategory(RootService.CATEGORY_DAEMON_MODE), cacheConnection)
            }.onFailure { cacheBound = false; screenState = screenState.copy(notice = WorkbenchNotice.ERROR, phase = "缓存引擎启动失败：${it.message.orEmpty()}") }
        }
    }

    private fun maybeStartScan() {
        if (reviewHydration.loading) return
        if (profileService == null || (needsCache && cacheService == null)) return
        if (scanRequested) {
            scanRequested = false
            autoScanStarted = true
            runScan()
            return
        }
        if (restoredReview) {
            if (screenState.cleanupCompleted) return
            if (screenState.notice != WorkbenchNotice.ERROR) {
                screenState = screenState.copy(
                    notice = if (screenState.scanReady && !screenState.coverageIncomplete) WorkbenchNotice.INFO else WorkbenchNotice.WARNING,
                    phase = if (screenState.scanReady) "已恢复上次扫描，可继续选择" else "已恢复上次结果，重新扫描后可清理")
            }
            return
        }
        if (autoScanStarted) return
        autoScanStarted = true
        runScan()
    }

    fun runScan() {
        if (closed || reviewHydration.loading) return
        if (screenState.running || stopJob?.isActive == true) return
        val profile = profileService
        val cache = cacheService
        if (profile == null || (needsCache && cache == null)) {
            scanRequested = true
            connectServices()
            return
        }
        if (!reviewHydration.beginReplacement()) return
        autoScanStarted = true
        scanRequested = false
        operationEpoch++
        restoredReview = false
        val generation = scanGeneration.start()
        cacheSnapshotId = ""
        profileSnapshotId = ""
        snapshotExpiresAtRealtime = 0L
        screenState = screenState.copy(running = true, loadingResults = false, scanReady = false, operation = "scan",
            cleanupCompleted = false, cleanedBytes = 0L, cleanedFiles = 0L, cleanedDirectories = 0L,
            notice = WorkbenchNotice.INFO, phase = when (scanProfile) {
                "safe" -> "正在扫描应用缓存与安全项目…"
                "cache" -> "正在扫描应用缓存…"
                else -> "正在扫描所选规则范围…"
            },
            progressCurrent = 0L, progressTotal = 0L, currentPath = "",
            items = emptyList(), selectedIds = emptySet(), resultText = "", expiresAtRealtime = 0L,
            coverageSummary = "", coverageIncomplete = false)
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
                            val json = if (!needsCache) JSONObject().put("snapshotId", "")
                                else JSONObject(requireNotNull(cache).scanCandidates(packageWhitelist))
                            json to SystemClock.elapsedRealtime()
                        }
                        val profileJob = async {
                            (if (scanProfile == "cache") JSONObject() else JSONObject(profile.scanProfile(scanProfile, options))) to SystemClock.elapsedRealtime()
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
                val cacheOk = needsCache && !cacheJson.has("error") && !cacheJson.optBoolean("cancelled")
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
                val coverage = workbenchScanCoverage(cacheJson.takeIf { needsCache }, profileJson, cacheOk, profileOk, profileRequired = scanProfile != "cache")
                val partial = coverage.incomplete
                val warning = if (partial) "本轮扫描未覆盖全部范围；仅展示有效快照中的项目。" else ""
                screenState = screenState.copy(loadingResults = true, notice = WorkbenchNotice.INFO, phase = "扫描结束，正在读取结果…",
                    progressCurrent = 0L, progressTotal = 0L, currentPath = "",
                    policyTitle = policy.title, policyKey = policy.key, highRiskMode = policy.highRiskMode,
                    expiresAtRealtime = snapshotExpiresAtRealtime, coverageSummary = coverage.summary, coverageIncomplete = partial,
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
                screenState = screenState.copy(running = false, operation = "idle", loadingResults = false,
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
                val appName = item.optString("appName").trim().ifBlank { applicationLabel(packageName) }
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

    fun cleanSelection() {
        if (closed || screenState.running || screenState.loadingResults || stopJob?.isActive == true) return
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
        val epoch = ++operationEpoch
        val stopRequested = AtomicBoolean().also { mutationStopRequest = it }
        val reviewedCacheSnapshot = cacheSnapshotId
        val reviewedProfileSnapshot = profileSnapshotId
        val reviewedExpiresAt = snapshotExpiresAtRealtime
        val reviewedItems = screenState.items
        val reviewedSelection = selected.mapTo(linkedSetOf()) { it.id }
        val cacheItems = selected.filter { it.source == "cache" }
        val profileItems = selected.filter { it.source == "profile" }
        screenState = screenState.copy(running = true, operation = "clean", notice = WorkbenchNotice.INFO,
            cleanupCompleted = false, cleanedBytes = 0L, cleanedFiles = 0L, cleanedDirectories = 0L,
            phase = "正在校验并清理 ${selected.size} 个已勾选项目…", progressCurrent = 0L,
            progressTotal = selected.size.toLong(), currentPath = "")
        startPolling()
        val pendingReview = saveReview()
        val attempt = CleanupAttempt()
        mutationJob = lifecycleScope.launch {
            val response = runCatching {
                withContext(Dispatchers.IO) {
                    check(pendingReview?.get() == true) { "无法保存清理前记录，请重试" }
                    currentCoroutineContext().ensureActive()
                    val packageWhitelist = profile.getWhitelistPackages()
                    val options = JSONObject(optionsJson(profile))
                        .put("allowHighRisk", profileItems.any { it.risk == "high" }).toString()
                    ensureMutationCanStart(stopRequested)
                    var bytes = 0L
                    var files = 0L
                    var directories = 0L
                    var failures = 0
                    var cleanedCandidates = 0
                    var cancelled = false
                    var incomplete = false
                    val messages = ArrayList<String>()
                    val outcomes = HashMap<String, String>()
                    val actualApps = ArrayList<AppJunkUiItem>()
                    val actualJunk = ArrayList<GeneralJunkUiItem>()
                    var remainingCache = RemainingReview(if (cacheItems.isEmpty()) reviewedCacheSnapshot else "", reviewedExpiresAt)
                    var remainingProfile = RemainingReview(if (profileItems.isEmpty()) reviewedProfileSnapshot else "", reviewedExpiresAt)
                    if (cacheItems.isNotEmpty()) {
                        val cache = requireNotNull(cache)
                        val selection = JSONObject()
                        cacheItems.forEach { selection.put(it.path, true) }
                        ensureMutationCanStart(stopRequested)
                        val cacheResult = JSONObject(attempt.mutate {
                            cache.cleanSelected(reviewedCacheSnapshot, selection.toString(), packageWhitelist)
                        })
                        remainingCache = remainingReview(cacheResult, reviewedExpiresAt)
                        val cacheDeletedBytes = cacheResult.optLong("deletedBytes", 0L).coerceAtLeast(0L)
                        val cacheDeletedFiles = cacheResult.optLong("deletedFiles", 0L).coerceAtLeast(0L)
                        val cacheCleanedCandidates = cacheResult.optInt("cleanedCandidates", 0).coerceAtLeast(0)
                        val cacheChangedCandidates = cacheResult.optInt("changedCandidates", 0).coerceAtLeast(0)
                        val cacheProtectedCandidates = cacheResult.optInt("protectedCandidates", 0).coerceAtLeast(0)
                        val cachePartialCandidates = cacheResult.optInt("partialCandidates", 0).coerceAtLeast(0)
                        val cacheFailedCandidates = cacheResult.optInt("failedCandidates", 0).coerceAtLeast(0)
                        val cacheSkippedCandidates = cacheResult.optInt(
                            "skippedCandidates",
                            cacheChangedCandidates + cacheProtectedCandidates
                        ).coerceAtLeast(0)
                        val cacheMutated = cacheResult.optBoolean(
                            "mutated",
                            cacheDeletedFiles > 0L || cacheCleanedCandidates > 0
                        )
                        val cacheFailures = cacheResult.optInt(
                            "failures",
                            if (cacheResult.optBoolean("success")) cacheFailedCandidates else 1
                        ).coerceAtLeast(0)

                        bytes += cacheDeletedBytes
                        files += cacheDeletedFiles
                        directories += cacheResult.optLong("deletedDirectories", 0L).coerceAtLeast(0L)
                        failures += cacheFailures
                        cleanedCandidates += cacheCleanedCandidates
                        cancelled = cacheResult.optBoolean("cancelled")
                        incomplete = incomplete || !cacheResult.optBoolean("success") || cancelled ||
                            cacheFailures > 0 || !cacheMutated || cacheSkippedCandidates > 0 ||
                            cachePartialCandidates > 0 || cacheFailedCandidates > 0
                        messages += cacheResult.optString("message", "应用缓存处理完成")

                        val status = when {
                            !cacheResult.optBoolean("success") || cancelled ->
                                "缓存清理未完成"
                            !cacheMutated ->
                                "未删除文件 · 跳过 $cacheSkippedCandidates 项"
                            cacheSkippedCandidates > 0 || cachePartialCandidates > 0 || cacheFailedCandidates > 0 ->
                                "实际删除 $cacheDeletedFiles 个文件 · 跳过 $cacheSkippedCandidates 项"
                            else ->
                                "实际删除 $cacheDeletedFiles 个文件 · 释放 ${formatBytes(cacheDeletedBytes)}"
                        }
                        cacheItems.forEach { outcomes[it.id] = status }

                        if (cacheMutated) {
                            val packages = cacheItems.map { it.packageName }.filter { it.isNotBlank() }.distinct()
                            if (packages.size == 1) {
                                val pkg = packages.single()
                                actualApps += AppJunkUiItem(
                                    pkg,
                                    applicationLabel(pkg),
                                    "应用缓存",
                                    cacheDeletedFiles,
                                    cacheDeletedBytes,
                                    cacheFailures.toLong()
                                )
                            } else {
                                actualJunk += GeneralJunkUiItem(
                                    "应用缓存（实际清理）",
                                    cacheDeletedFiles,
                                    cacheDeletedBytes,
                                    cacheFailures.toLong(),
                                    ""
                                )
                            }
                        }
                    }
                    if (stopRequested.get()) { cancelled = true; incomplete = true }
                    if (!cancelled && profileItems.isNotEmpty()) {
                        val selection = JSONObject()
                        profileItems.forEach { selection.put(it.id.removePrefix("profile:"), true) }
                        ensureMutationCanStart(stopRequested)
                        val profileResult = JSONObject(attempt.mutate {
                            profile.cleanProfileSelected(reviewedProfileSnapshot, selection.toString(), options)
                        })
                        remainingProfile = remainingReview(profileResult, reviewedExpiresAt)
                        bytes += profileResult.optLong("deletedBytes", 0L).coerceAtLeast(0L)
                        files += profileResult.optLong("deletedFiles", 0L).coerceAtLeast(0L)
                        directories += profileResult.optLong("deletedDirectories", 0L).coerceAtLeast(0L)
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
                    currentCoroutineContext().ensureActive()
                    runCatching {
                        profile.recordNativeTask(JSONObject().put("mode", "workbench-clean")
                            .put("success", !incomplete && failures == 0 && !cancelled)
                            .put("cancelled", cancelled).put("bytes", bytes).put("files", files).put("errors", failures)
                            .put("emptyDirs", directories)
                            .put("result", "工作台清理完成，处理 $cleanedCandidates 个候选").toString())
                    }
                    val groupedApps = actualApps.groupBy { it.packageName }.values.map { entries ->
                        entries.first().copy(files = entries.sumOf { it.files }, bytes = entries.sumOf { it.bytes },
                            errors = entries.sumOf { it.errors }, categories = entries.map {
                                AppJunkCategoryUiItem(it.category, it.files, it.bytes, it.errors, "")
                            })
                    }
                    CleanAggregate(bytes, files, directories, failures, cleanedCandidates, messages, outcomes, cancelled, incomplete,
                        groupedApps, actualJunk, remainingCache, remainingProfile)
                }
            }
            if (closed || epoch != operationEpoch) return@launch
            pollJob?.cancel()
            response.onSuccess { result ->
                // Publish history with the accepted review, never from an obsolete IO reply.
                LastCleanupStore.save(this@ScanWorkbenchSession, result.apps, result.junk)
                val now = SystemClock.elapsedRealtime()
                val cacheRemaining = result.cacheRemaining.takeIf { it.id.isNotBlank() && it.expiresAt > now }
                val profileRemaining = result.profileRemaining.takeIf { it.id.isNotBlank() && it.expiresAt > now }
                cacheSnapshotId = cacheRemaining?.id.orEmpty()
                profileSnapshotId = profileRemaining?.id.orEmpty()
                val updatedItems = reviewedItems.map { item ->
                    if (item.id in reviewedSelection) item.copy(selectable = false,
                        outcome = result.outcomes[item.id] ?: "未完成或未返回结果；重新扫描后可重试")
                    else item.copy(selectable = item.selectable && when (item.source) {
                        "cache" -> cacheRemaining != null
                        "profile" -> profileRemaining != null
                        else -> false
                    }, outcome = item.outcome.ifBlank { "未勾选，保留" })
                }
                val canContinue = updatedItems.any { it.selectable }
                snapshotExpiresAtRealtime = if (canContinue) listOfNotNull(cacheRemaining?.expiresAt, profileRemaining?.expiresAt).minOrNull() ?: 0L else 0L
                screenState = screenState.copy(running = false, scanReady = canContinue, operation = "idle",
                    cleanupCompleted = true, cleanedBytes = result.bytes, cleanedFiles = result.files, cleanedDirectories = result.directories,
                    items = updatedItems, selectedIds = emptySet(),
                    notice = if (result.incomplete) WorkbenchNotice.WARNING else WorkbenchNotice.SUCCESS, expiresAtRealtime = snapshotExpiresAtRealtime,
                    phase = when {
                        canContinue && result.incomplete -> "本批处理已结束；未选项目可继续选择，未完成项需重新扫描"
                        canContinue -> "本批已清理；剩余项目可继续勾选，高风险需再次确认"
                        result.cancelled -> "清理已停止，结果已保留，请重新扫描后继续"
                        result.incomplete -> "部分项目未完成，重新扫描后可继续清理"
                        else -> "已完成所选项目清理"
                    },
                    resultText = "释放 ${formatBytes(result.bytes)} · 文件 ${result.files} · 目录 ${result.directories} · 候选 ${result.candidates}\n${result.messages.filter { it.isNotBlank() }.joinToString("\n")}")
            }.onFailure { error ->
                val canRetry = !stopRequested.get() && !attempt.snapshotTouched && !attempt.cleanupSubmitted && SystemClock.elapsedRealtime() < snapshotExpiresAtRealtime &&
                    profileService != null && (cacheItems.isEmpty() || cacheService != null)
                if (!canRetry) {
                    cacheSnapshotId = ""
                    profileSnapshotId = ""
                    snapshotExpiresAtRealtime = 0L
                }
                screenState = screenState.copy(running = false, operation = "idle", scanReady = canRetry, notice = WorkbenchNotice.ERROR,
                    expiresAtRealtime = snapshotExpiresAtRealtime,
                    // Never replay deletion after a lost response. Keep the exact user review.
                    phase = if (stopRequested.get()) "清理已停止，列表与勾选已保留"
                        else if (attempt.cleanupSubmitted) "清理结果未确认，列表与勾选已保留" else "清理未完成，列表与勾选已保留",
                    resultText = (if (canRetry) "请求尚未执行，可重试。" else "请重新扫描后再清理，避免重复执行。") +
                        "\n" + (error.message ?: error.javaClass.simpleName))
            }
            saveReview()
        }
    }

    fun quarantineItem(item: WorkbenchItem) {
        if (closed || screenState.running || screenState.loadingResults || stopJob?.isActive == true || item !in screenState.items || item.source != "profile" || item.risk != "high" || !cleanupPolicy.canQuarantineHighRisk) return
        if (!screenState.scanReady || SystemClock.elapsedRealtime() >= snapshotExpiresAtRealtime) {
            screenState = screenState.copy(scanReady = false, notice = WorkbenchNotice.WARNING, phase = "扫描快照已过期，请重新扫描")
            return
        }
        val service = profileService ?: return
        val epoch = ++operationEpoch
        val stopRequested = AtomicBoolean().also { mutationStopRequest = it }
        val reviewedSnapshot = profileSnapshotId
        screenState = screenState.copy(running = true, operation = "clean", notice = WorkbenchNotice.INFO,
            phase = "正在把 ${item.title} 移入隔离区…", currentPath = item.path)
        startPolling()
        val pendingReview = saveReview()
        val attempt = CleanupAttempt()
        mutationJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    check(pendingReview?.get() == true) { "无法保存隔离前记录，请重试" }
                    currentCoroutineContext().ensureActive()
                    val selection = JSONObject().put(item.id.removePrefix("profile:"), true)
                    val options = optionsJson(service)
                    ensureMutationCanStart(stopRequested)
                    JSONObject(attempt.mutate { service.quarantineProfileSelected(reviewedSnapshot, selection.toString(), options) })
                }
            }
            if (closed || epoch != operationEpoch) return@launch
            pollJob?.cancel()
            result.onSuccess { json ->
                if (json.optBoolean("success") && json.optInt("quarantinedCandidates") > 0) {
                    cacheSnapshotId = ""
                    profileSnapshotId = ""
                    snapshotExpiresAtRealtime = 0L
                    screenState = screenState.copy(running = false, operation = "idle", scanReady = false, expiresAtRealtime = 0L,
                        items = screenState.items.map { if (it.id == item.id) it.copy(outcome = "已移入隔离区，可恢复", selectable = false) else it },
                        selectedIds = screenState.selectedIds - item.id,
                        notice = WorkbenchNotice.SUCCESS, phase = json.optString("message", "高风险项目已移入隔离区"),
                        resultText = "已隔离 ${json.optInt("quarantinedCandidates")} 项 · ${formatBytes(json.optLong("quarantinedBytes"))}")
                    Toast.makeText(this@ScanWorkbenchSession, "已移入隔离区，可随时恢复", Toast.LENGTH_SHORT).show()
                    saveReview()
                } else {
                    if (attempt.cleanupSubmitted || stopRequested.get()) {
                        cacheSnapshotId = ""
                        profileSnapshotId = ""
                        snapshotExpiresAtRealtime = 0L
                    }
                    screenState = screenState.copy(running = false, operation = "idle",
                        notice = if (json.optBoolean("cancelled") || json.optBoolean("success")) WorkbenchNotice.WARNING else WorkbenchNotice.ERROR,
                        scanReady = !stopRequested.get() && !attempt.cleanupSubmitted && SystemClock.elapsedRealtime() < snapshotExpiresAtRealtime,
                        expiresAtRealtime = snapshotExpiresAtRealtime,
                        phase = json.optString("message", json.optString("error", "隔离未完成")),
                        resultText = if (attempt.cleanupSubmitted) "列表与勾选已保留，请重新扫描后继续。" else "请求尚未执行。")
                    saveReview()
                }
            }.onFailure {
                if (attempt.cleanupSubmitted || stopRequested.get()) {
                    cacheSnapshotId = ""
                    profileSnapshotId = ""
                    snapshotExpiresAtRealtime = 0L
                }
                screenState = screenState.copy(running = false, operation = "idle", notice = WorkbenchNotice.ERROR,
                    scanReady = !stopRequested.get() && !attempt.cleanupSubmitted && SystemClock.elapsedRealtime() < snapshotExpiresAtRealtime,
                    expiresAtRealtime = snapshotExpiresAtRealtime,
                    phase = if (stopRequested.get()) "隔离已停止，列表与勾选已保留" else "隔离结果未确认，列表与勾选已保留",
                    resultText = "${it.message ?: it.javaClass.simpleName}" +
                        if (attempt.cleanupSubmitted || stopRequested.get()) "\n请重新扫描后继续，避免重复执行。" else "\n请求尚未执行，可重试。")
                saveReview()
            }
        }
    }

    fun protectItem(item: WorkbenchItem) {
        if (closed || screenState.running || item !in screenState.items) return
        val service = profileService ?: return
        val epoch = ++operationEpoch
        screenState = screenState.copy(running = true, operation = "protect", notice = WorkbenchNotice.INFO,
            phase = "正在把 ${item.groupTitle} 加入保护白名单…")
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
            if (closed || epoch != operationEpoch) return@launch
            result.onSuccess { json ->
                if (json.optBoolean("success")) {
                    cacheSnapshotId = ""
                    profileSnapshotId = ""
                    snapshotExpiresAtRealtime = 0L
                    screenState = screenState.copy(running = false, operation = "idle", scanReady = false, expiresAtRealtime = 0L,
                        items = screenState.items.map { existing ->
                            if ((item.packageName.isNotBlank() && existing.packageName == item.packageName) || existing.path == item.path)
                                existing.copy(selectable = false, outcome = "已加入白名单，保留") else existing
                        },
                        notice = WorkbenchNotice.SUCCESS, phase = "已加入白名单，列表已保留；重新扫描后可继续清理")
                    Toast.makeText(this@ScanWorkbenchSession, json.optString("message", "已加入白名单"), Toast.LENGTH_SHORT).show()
                    saveReview()
                } else screenState = screenState.copy(running = false, operation = "idle", notice = WorkbenchNotice.ERROR,
                    phase = "白名单保存失败：${json.optString("message", json.optString("error"))}")
            }.onFailure { screenState = screenState.copy(running = false, operation = "idle", notice = WorkbenchNotice.ERROR,
                phase = "白名单保存失败：${it.message ?: it.javaClass.simpleName}") }
        }
    }

    fun prepareWhitelist(): Boolean {
        if (screenState.running || screenState.loadingResults) return false
        invalidateScanLoad()
        screenState = screenState.copy(notice = WorkbenchNotice.INFO,
            phase = "白名单管理后请重新扫描，旧记录不会直接用于删除")
        saveReview()
        return true
    }

    private fun selectionIsEditable(): Boolean {
        val reason = reviewSelectionBlockReason(screenState, SystemClock.elapsedRealtime())
        if (reason != null && !screenState.running) screenState = screenState.copy(phase = reason)
        return reason == null
    }
    fun toggleItem(id: String) {
        val item = screenState.items.firstOrNull { it.id == id } ?: return
        if (!item.selectable || item.risk == "critical" || !selectionIsEditable()) return
        val selected = screenState.selectedIds.toMutableSet()
        if (!selected.add(id)) selected.remove(id)
        screenState = screenState.copy(selectedIds = selected)
    }
    fun toggleGroup(groupKey: String) {
        if (!selectionIsEditable()) return
        val group = screenState.items.filter { it.groupKey == groupKey && it.selectable && it.risk in setOf("low", "medium") }
        if (group.isEmpty()) return
        val selected = screenState.selectedIds.toMutableSet()
        val shouldSelect = group.any { it.id !in selected }
        group.forEach { if (shouldSelect) selected += it.id else selected -= it.id }
        screenState = screenState.copy(selectedIds = selected)
    }
    fun toggleItems(ids: Set<String>) {
        if (!selectionIsEditable()) return
        val eligible = screenState.items.filter { it.id in ids && it.selectable && it.risk in setOf("low", "medium") }
            .mapTo(linkedSetOf()) { it.id }
        if (eligible.isEmpty()) return
        val selected = screenState.selectedIds.toMutableSet()
        if (selected.containsAll(eligible)) selected.removeAll(eligible) else selected.addAll(eligible)
        screenState = screenState.copy(selectedIds = selected)
    }
    fun selectAllSafe() = selectRisks(setOf("low", "medium"))
    fun selectAllMedium() = selectRisks(setOf("medium"))
    private fun selectRisks(risks: Set<String>) {
        if (!selectionIsEditable()) return
        screenState = screenState.copy(selectedIds = reviewRiskSelection(screenState.items, risks))
    }
    fun clearSelection() {
        if (selectionIsEditable()) screenState = screenState.copy(selectedIds = emptySet())
    }

    fun stopTask() {
        if (!screenState.running || stopJob?.isActive == true) return
        val profile = profileService
        val cache = cacheService
        val stoppingScan = scanJob?.isActive == true
        if (!stoppingScan) mutationStopRequest?.set(true)
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
    private suspend fun ensureMutationCanStart(stopRequested: AtomicBoolean) {
        currentCoroutineContext().ensureActive()
        check(!stopRequested.get()) { "当前任务已停止，未继续执行" }
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
        val config = suppliedConfig ?: JSONObject(service.getSchedulerConfig())
        val policy = CleanupPolicy.fromId(config.optInt("cleanup_policy", CleanupPolicy.BALANCED.id))
        val paths = JSONArray(service.getWhitelistPaths())
        val packages = JSONArray(service.getWhitelistPackages())
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

    fun saveReview(): java.util.concurrent.Future<Boolean>? {
        if (!reviewHydration.canPersist) return null
        val state = screenState
        val cacheId = cacheSnapshotId
        val profileId = profileSnapshotId
        val policyId = cleanupPolicy.id
        val expires = System.currentTimeMillis() + (snapshotExpiresAtRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        return ScanReviewStore.save(this, scanProfile) {
            JSONObject().put("cacheSnapshotId", cacheId).put("profileSnapshotId", profileId)
                .put("expiresAt", expires).put("scanReady", state.scanReady && !state.running && !state.loadingResults)
                .put("loadingResults", state.loadingResults).put("phase", state.phase)
                .put("operation", state.operation).put("running", state.running)
                .put("cleanupCompleted", state.cleanupCompleted).put("cleanedBytes", state.cleanedBytes).put("cleanedFiles", state.cleanedFiles)
                .put("cleanedDirectories", state.cleanedDirectories)
                .put("resultText", state.resultText).put("notice", state.notice.name)
                .put("coverageSummary", state.coverageSummary).put("coverageIncomplete", state.coverageIncomplete)
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
        restoredReview = true
        cacheSnapshotId = saved.optString("cacheSnapshotId")
        profileSnapshotId = saved.optString("profileSnapshotId")
        val remaining = (saved.optLong("expiresAt") - System.currentTimeMillis()).coerceIn(0L, SNAPSHOT_TTL_MS)
        snapshotExpiresAtRealtime = SystemClock.elapsedRealtime() + remaining
        cleanupPolicy = CleanupPolicy.fromId(saved.optInt("policyId", CleanupPolicy.BALANCED.id))
        val selected = saved.optJSONArray("selected") ?: JSONArray()
        val selectedIds = withContext(Dispatchers.Default) { (0 until selected.length()).map { selected.optString(it) }.toSet() }
        val incomplete = saved.optBoolean("loadingResults") || saved.optBoolean("running")
        screenState = screenState.copy(items = items, selectedIds = selectedIds,
            cleanupCompleted = saved.optBoolean("cleanupCompleted") && !incomplete,
            cleanedBytes = saved.optLong("cleanedBytes", 0L).coerceAtLeast(0L),
            cleanedFiles = saved.optLong("cleanedFiles", 0L).coerceAtLeast(0L), operation = "idle",
            cleanedDirectories = saved.optLong("cleanedDirectories", 0L).coerceAtLeast(0L),
            scanReady = saved.optBoolean("scanReady") && !incomplete && remaining > 0L,
            expiresAtRealtime = snapshotExpiresAtRealtime,
            phase = if (incomplete) "上次任务已中断或结果未确认；保留记录，请重新扫描"
                else if (saved.optBoolean("cleanupCompleted")) saved.optString("phase")
                else if (remaining > 0L) saved.optString("phase") else "上次结果已保留；重新扫描后可继续清理",
            notice = if (incomplete || (remaining <= 0L && !saved.optBoolean("cleanupCompleted"))) WorkbenchNotice.WARNING else
                runCatching { WorkbenchNotice.valueOf(saved.optString("notice", "INFO")) }.getOrDefault(WorkbenchNotice.INFO),
            coverageSummary = saved.optString("coverageSummary"), coverageIncomplete = saved.optBoolean("coverageIncomplete"),
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

private data class CleanAggregate(val bytes: Long, val files: Long, val directories: Long, val failures: Int, val candidates: Int,
    val messages: List<String>, val outcomes: Map<String, String>, val cancelled: Boolean, val incomplete: Boolean,
    val apps: List<AppJunkUiItem>, val junk: List<GeneralJunkUiItem>,
    val cacheRemaining: RemainingReview, val profileRemaining: RemainingReview)
private data class RemainingReview(val id: String, val expiresAt: Long)
private fun remainingReview(response: JSONObject, previousExpiry: Long): RemainingReview {
    val remainingId = response.optString("remainingSnapshotId")
    val remainingMs = response.optLong("snapshotExpiresInMs", 0L).coerceIn(0L, 30L * 60L * 1_000L)
    if (remainingId.isBlank() || response.optInt("remainingCandidates") <= 0 || remainingMs <= 0L) return RemainingReview("", 0L)
    return RemainingReview(remainingId, minOf(previousExpiry, SystemClock.elapsedRealtime() + remainingMs))
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
