package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.BaiZeRootService
import io.github.xgl34222220.baize.root.CandidatePlanRootService
import io.github.xgl34222220.baize.root.IBaiZeRootService
import io.github.xgl34222220.baize.root.ICandidatePlanService
import io.github.xgl34222220.baize.root.IPersistentCleanPlanService
import io.github.xgl34222220.baize.root.PersistentCleanPlanRootService
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
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
import java.security.MessageDigest
import java.util.UUID

/**
 * Stage four smart-clean entry: browse every scanned candidate, group it, exclude unwanted items,
 * then atomically reduce both Root snapshots to the exact final plan. Actual deletion and resume
 * remain owned by [ResumableSmartScanActivity].
 */
class CandidateSmartScanActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private val preferences by lazy { getSharedPreferences("baize_v2", MODE_PRIVATE) }

    private var cacheService: IBaiZeRootService? = null
    private var safeService: IPersistentCleanPlanService? = null
    private var candidateService: ICandidatePlanService? = null
    private var cacheBinding = false
    private var safeBinding = false
    private var candidateBinding = false
    private var pollJob: Job? = null
    private var resumedOnce = false

    private var cleanPlanId = ""
    private var cleanPlanCreatedAt = 0L
    private var cacheSnapshotId = ""
    private var safeSnapshotId = ""
    private var cacheCount = 0
    private var safeCount = 0
    private var selectionFinalized = false
    private var excludedIds = linkedSetOf<String>()

    private var screenState by mutableStateOf(CandidatePickerState())

    private val cacheConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            cacheService = IBaiZeRootService.Stub.asInterface(binder)
            cacheBinding = true
            updateConnections()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cacheService = null
            cacheBinding = false
            updateConnections()
        }
    }

    private val safeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            safeService = IPersistentCleanPlanService.Stub.asInterface(binder)
            safeBinding = true
            updateConnections()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            safeService = null
            safeBinding = false
            updateConnections()
        }
    }

    private val candidateConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            candidateService = ICandidatePlanService.Stub.asInterface(binder)
            candidateBinding = true
            updateConnections()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            candidateService = null
            candidateBinding = false
            updateConnections()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        restorePlan()
        setContent {
            val appearance by appearanceViewModel.settings.collectAsState()
            BaiZeTheme(appearance) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CandidatePickerScreen(
                        state = screenState,
                        onBack = ::finish,
                        onScan = ::startScan,
                        onStop = ::stopScan,
                        onReconnect = ::bindServices,
                        onReload = ::loadCandidates,
                        onGrouping = ::setGrouping,
                        onToggleExpanded = ::toggleExpanded,
                        onToggleCandidate = ::toggleCandidate,
                        onToggleGroup = ::toggleGroup,
                        onSelectAll = ::selectAll,
                        onSelectNone = ::selectNone,
                        onFinalize = { finalizeSelection(false) },
                        onFinalizeAndClean = { finalizeSelection(true) },
                        onOpenClean = ::openResumableClean
                    )
                }
            }
        }
        bindServices()
    }

    override fun onResume() {
        super.onResume()
        if (resumedOnce && !screenState.running) {
            val hadPlan = screenState.planReady
            restorePlan()
            if (hadPlan && !screenState.planReady) {
                screenState = CandidatePickerState(
                    connected = servicesReady(),
                    status = if (servicesReady()) "候选计划引擎已连接" else "正在连接 Root 引擎…",
                    phase = "上一份清理计划已完成，可开始新的智能扫描"
                )
            }
        }
        resumedOnce = true
    }

    private fun bindServices() {
        if (cacheService == null && !cacheBinding) {
            runCatching {
                RootService.bind(
                    Intent(this, BaiZeRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE),
                    cacheConnection
                )
                cacheBinding = true
            }.onFailure {
                cacheBinding = false
                screenState = screenState.copy(phase = "缓存 Root 服务启动失败：${it.message}")
            }
        }
        if (safeService == null && !safeBinding) {
            runCatching {
                RootService.bind(
                    Intent(this, PersistentCleanPlanRootService::class.java)
                        .addCategory(RootService.CATEGORY_DAEMON_MODE),
                    safeConnection
                )
                safeBinding = true
            }.onFailure {
                safeBinding = false
                screenState = screenState.copy(phase = "安全项目 Root 服务启动失败：${it.message}")
            }
        }
        if (candidateService == null && !candidateBinding) {
            runCatching {
                RootService.bind(
                    Intent(this, CandidatePlanRootService::class.java)
                        .addCategory(RootService.CATEGORY_DAEMON_MODE),
                    candidateConnection
                )
                candidateBinding = true
            }.onFailure {
                candidateBinding = false
                screenState = screenState.copy(phase = "候选计划 Root 服务启动失败：${it.message}")
            }
        }
        updateConnections()
    }

    private fun servicesReady(): Boolean =
        cacheService != null && safeService != null && candidateService != null

    private fun updateConnections() {
        val ready = listOf(cacheService, safeService, candidateService).count { it != null }
        screenState = screenState.copy(
            connected = ready == 3,
            status = if (ready == 3) "扫描、分页与候选计划引擎已连接" else "正在连接 Root 引擎 · $ready/3"
        )
        if (ready == 3 && screenState.planReady && !selectionFinalized &&
            screenState.candidates.isEmpty() && !screenState.loadingCandidates && !screenState.running
        ) {
            loadCandidates()
        }
    }

    private fun startScan() {
        if (screenState.running) return
        val cache = cacheService
        val safe = safeService
        if (cache == null || safe == null || candidateService == null) {
            screenState = screenState.copy(phase = "Root 引擎尚未全部连接")
            bindServices()
            return
        }

        clearPlan()
        screenState = CandidatePickerState(
            connected = true,
            running = true,
            operation = "scan",
            status = "扫描、分页与候选计划引擎已连接",
            phase = "正在并行扫描应用缓存与安全项目…",
            progressCurrent = 0,
            progressTotal = 2
        )
        startPolling()

        lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            try {
                val packageWhitelist = preferences.getStringSet("package_whitelist", emptySet()).orEmpty()
                val options = optionsJson()
                val (cacheJson, safeJson) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val cacheJob = async {
                            JSONObject(cache.scanCandidates(JSONArray(packageWhitelist.toList().sorted()).toString()))
                        }
                        val safeJob = async { JSONObject(safe.scanSafe(options)) }
                        cacheJob.await() to safeJob.await()
                    }
                }
                if (cacheJson.optString("error") == "busy" || safeJson.optString("error") == "busy") {
                    screenState = screenState.copy(running = false, operation = "", phase = "当前已有扫描或清理任务正在运行")
                    return@launch
                }

                cacheSnapshotId = cacheJson.takeUnless { it.has("error") }?.optString("snapshotId").orEmpty()
                safeSnapshotId = safeJson.takeUnless { it.has("error") }?.optString("snapshotId").orEmpty()
                cacheCount = if (cacheSnapshotId.isBlank()) 0 else cacheJson.optInt("totalCandidates").coerceAtLeast(0)
                safeCount = if (safeSnapshotId.isBlank()) 0 else (
                    safeJson.optInt("low") + safeJson.optInt("medium")
                ).coerceAtLeast(0)
                cleanPlanId = UUID.randomUUID().toString()
                cleanPlanCreatedAt = System.currentTimeMillis()
                selectionFinalized = false
                excludedIds.clear()
                val total = cacheCount + safeCount
                val cancelled = cacheJson.optBoolean("cancelled") || safeJson.optBoolean("cancelled")
                if (cancelled || total <= 0) {
                    clearPlan()
                    screenState = screenState.copy(
                        running = false,
                        operation = "",
                        phase = if (cancelled) "智能扫描已停止" else "扫描完成，没有发现可安全清理的项目",
                        progressCurrent = 2,
                        progressTotal = 2
                    )
                    return@launch
                }

                persistPlan()
                screenState = screenState.copy(
                    running = false,
                    operation = "",
                    planReady = true,
                    finalized = false,
                    phase = "扫描完成 · $total 项\n正在读取候选明细，可按应用或类别自由勾选",
                    cacheCount = cacheCount,
                    safeCount = safeCount,
                    selectedCount = total,
                    progressCurrent = 2,
                    progressTotal = 2
                )
                loadCandidates()
                val elapsed = SystemClock.elapsedRealtime() - started
                screenState = screenState.copy(phase = "扫描完成 · $total 项 · ${elapsed}ms\n请选择要进入最终清理计划的项目")
            } catch (throwable: Throwable) {
                screenState = screenState.copy(
                    running = false,
                    operation = "",
                    phase = "智能扫描失败：${throwable.message ?: throwable.javaClass.simpleName}"
                )
            } finally {
                pollJob?.cancel()
            }
        }
    }

    private fun loadCandidates() {
        if (screenState.running || screenState.loadingCandidates || selectionFinalized) return
        val cache = cacheService ?: return
        val safe = safeService ?: return
        if (cacheSnapshotId.isBlank() && safeSnapshotId.isBlank()) return
        screenState = screenState.copy(
            loadingCandidates = true,
            phase = "正在分页读取候选明细…"
        )

        lifecycleScope.launch {
            try {
                val candidates = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val cacheJob = async {
                            loadAllPages(cacheSnapshotId) { offset, limit ->
                                cache.getResultPage(cacheSnapshotId, offset, limit)
                            }.map { json -> cacheCandidate(json) }
                        }
                        val safeJob = async {
                            loadAllPages(safeSnapshotId) { offset, limit ->
                                safe.getPage(safeSnapshotId, offset, limit)
                            }.mapNotNull { json -> safeCandidate(json) }
                        }
                        (cacheJob.await() + safeJob.await())
                            .distinctBy { it.id }
                            .sortedWith(
                                compareByDescending<PickerCandidate> { it.bytes.coerceAtLeast(0L) }
                                    .thenBy { it.appName }
                                    .thenBy { it.path }
                            )
                    }
                }
                val validIds = candidates.mapTo(HashSet()) { it.id }
                excludedIds.retainAll(validIds)
                val selected = candidates.filterNot { it.id in excludedIds }
                persistSelection()
                screenState = screenState.copy(
                    loadingCandidates = false,
                    candidates = candidates,
                    selectedCount = selected.size,
                    selectedBytes = selected.sumOf { it.bytes.coerceAtLeast(0L) },
                    phase = "候选明细已加载 · 共 ${candidates.size} 项，已选 ${selected.size} 项"
                )
            } catch (throwable: Throwable) {
                screenState = screenState.copy(
                    loadingCandidates = false,
                    phase = "候选明细读取失败：${throwable.message ?: throwable.javaClass.simpleName}"
                )
            }
        }
    }

    private fun loadAllPages(
        snapshotId: String,
        fetch: (Int, Int) -> String
    ): List<JSONObject> {
        if (snapshotId.isBlank()) return emptyList()
        val result = ArrayList<JSONObject>()
        var offset = 0
        var total = Int.MAX_VALUE
        while (offset < total && result.size < MAX_UI_CANDIDATES) {
            val page = JSONObject(fetch(offset, PAGE_SIZE))
            if (page.has("error")) throw IllegalStateException(page.optString("message", "候选快照已失效"))
            total = page.optInt("total", 0).coerceAtMost(MAX_UI_CANDIDATES)
            val items = page.optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) break
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let(result::add)
            }
            offset += items.length()
        }
        if (total > MAX_UI_CANDIDATES) throw IllegalStateException("候选数量超过界面安全上限，请缩小扫描范围")
        return result
    }

    private fun cacheCandidate(json: JSONObject): PickerCandidate {
        val packageName = json.optString("packageName")
        val path = json.optString("path")
        val category = json.optString("categoryLabel", "应用缓存")
        return PickerCandidate(
            id = "cache:$path",
            source = "cache",
            appName = resolveAppName(packageName),
            packageName = packageName,
            category = category,
            risk = "low",
            path = path,
            bytes = json.optLong("bytes", 0L),
            files = json.optLong("files", 0L),
            directories = json.optLong("directories", 0L),
            measured = true,
            note = "应用缓存快照"
        )
    }

    private fun safeCandidate(json: JSONObject): PickerCandidate? {
        val path = json.optString("path")
        val id = json.optString("id")
        if (path.isBlank() || id.isBlank()) return null
        val packageName = json.optString("packageName")
        return PickerCandidate(
            id = id,
            source = "safe",
            appName = if (packageName.isBlank()) "系统与共享存储" else resolveAppName(packageName),
            packageName = packageName,
            category = json.optString("categoryLabel", "安全项目"),
            risk = json.optString("risk", "low"),
            path = path,
            bytes = json.optLong("bytes", -1L),
            files = json.optLong("files", -1L),
            directories = json.optLong("directories", -1L),
            measured = json.optBoolean("measured", false),
            note = json.optString("note")
        )
    }

    private fun resolveAppName(packageName: String): String {
        if (packageName.isBlank()) return "系统与共享存储"
        return runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString().ifBlank { packageName }
        }.getOrDefault(packageName)
    }

    private fun toggleCandidate(id: String) {
        if (selectionFinalized || screenState.running) return
        if (!excludedIds.add(id)) excludedIds.remove(id)
        updateSelectionState()
    }

    private fun toggleGroup(candidates: List<PickerCandidate>) {
        if (selectionFinalized || screenState.running) return
        val allSelected = candidates.all { it.id !in excludedIds }
        if (allSelected) candidates.forEach { excludedIds += it.id }
        else candidates.forEach { excludedIds -= it.id }
        updateSelectionState()
    }

    private fun selectAll() {
        if (selectionFinalized || screenState.running) return
        excludedIds.clear()
        updateSelectionState()
    }

    private fun selectNone() {
        if (selectionFinalized || screenState.running) return
        excludedIds = screenState.candidates.mapTo(linkedSetOf()) { it.id }
        updateSelectionState()
    }

    private fun updateSelectionState() {
        val selected = screenState.candidates.filterNot { it.id in excludedIds }
        screenState = screenState.copy(
            selectedCount = selected.size,
            selectedBytes = selected.sumOf { it.bytes.coerceAtLeast(0L) },
            excludedIds = excludedIds.toSet(),
            phase = "已选择 ${selected.size}/${screenState.candidates.size} 项"
        )
        persistSelection()
    }

    private fun setGrouping(grouping: String) {
        screenState = screenState.copy(grouping = grouping, expandedGroups = emptySet())
    }

    private fun toggleExpanded(key: String) {
        val expanded = screenState.expandedGroups.toMutableSet()
        if (!expanded.add(key)) expanded.remove(key)
        screenState = screenState.copy(expandedGroups = expanded)
    }

    private fun finalizeSelection(openAfter: Boolean) {
        if (screenState.running || selectionFinalized) {
            if (selectionFinalized && openAfter) openResumableClean()
            return
        }
        val service = candidateService
        if (service == null) {
            screenState = screenState.copy(phase = "候选计划 Root 服务尚未连接")
            bindServices()
            return
        }
        if (screenState.selectedCount <= 0) {
            screenState = screenState.copy(phase = "请至少选择一个候选项目")
            return
        }

        val selectedBefore = screenState.candidates.filterNot { it.id in excludedIds }
        screenState = screenState.copy(
            running = true,
            operation = "finalize",
            phase = "正在固化最终清理计划；不会重新扫描…"
        )
        lifecycleScope.launch {
            try {
                val selection = JSONObject()
                    .put("__all_safe__", true)
                    .put("__exclude__", JSONArray(excludedIds.toList().sorted()))
                val result = withContext(Dispatchers.IO) {
                    JSONObject(service.finalizePlan(cacheSnapshotId, safeSnapshotId, selection.toString()))
                }
                if (result.has("error")) throw IllegalStateException(result.optString("message", "最终计划生成失败"))

                cacheSnapshotId = result.optString("cacheSnapshotId")
                safeSnapshotId = result.optString("safeSnapshotId")
                cacheCount = result.optInt("cacheCount", 0).coerceAtLeast(0)
                safeCount = result.optInt("safeCount", 0).coerceAtLeast(0)
                selectionFinalized = true
                excludedIds.clear()
                persistPlan()
                val selectedCount = cacheCount + safeCount
                val selectedBytes = result.optLong("selectedBytes", selectedBefore.sumOf { it.bytes.coerceAtLeast(0L) })
                screenState = screenState.copy(
                    running = false,
                    operation = "",
                    finalized = true,
                    candidates = selectedBefore,
                    excludedIds = emptySet(),
                    selectedCount = selectedCount,
                    selectedBytes = selectedBytes,
                    cacheCount = cacheCount,
                    safeCount = safeCount,
                    phase = "最终清理计划已锁定 · $selectedCount 项\n未选项目已从 Root 快照移除，后续只会处理当前计划"
                )
                if (openAfter) openResumableClean()
            } catch (throwable: Throwable) {
                screenState = screenState.copy(
                    running = false,
                    operation = "",
                    phase = "最终计划生成失败：${throwable.message ?: throwable.javaClass.simpleName}"
                )
            }
        }
    }

    private fun openResumableClean() {
        if (!selectionFinalized && screenState.selectedCount > 0) {
            finalizeSelection(true)
            return
        }
        if (!screenState.planReady) return
        startActivity(Intent(this, ResumableSmartScanActivity::class.java))
    }

    private fun stopScan() {
        cacheService?.cancelCurrentTask()
        safeService?.cancelCurrentTask()
        screenState = screenState.copy(phase = "已发送停止请求…")
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive && screenState.running && screenState.operation == "scan") {
                val states = withContext(Dispatchers.IO) {
                    listOfNotNull(
                        cacheService?.getTaskState()?.let { runCatching { JSONObject(it) }.getOrNull() },
                        safeService?.getTaskState()?.let { runCatching { JSONObject(it) }.getOrNull() }
                    )
                }
                val active = states.filter { it.optBoolean("running") }
                if (active.isNotEmpty()) {
                    val current = active.sumOf { it.optInt("current", it.optInt("progress_current", 0)).coerceAtLeast(0) }
                    val total = active.sumOf { it.optInt("total", it.optInt("progress_total", 0)).coerceAtLeast(0) }
                    val phase = active.joinToString(" · ") { it.optString("phase", "扫描中") }
                    screenState = screenState.copy(
                        phase = phase,
                        progressCurrent = current,
                        progressTotal = total
                    )
                }
                delay(300)
            }
        }
    }

    private fun restorePlan() {
        val raw = preferences.getString(CLEAN_PLAN_KEY, null).orEmpty()
        val plan = runCatching { JSONObject(raw) }.getOrNull()
        if (plan == null) {
            clearPlanMemory()
            return
        }
        val createdAt = plan.optLong("createdAt", 0L)
        if (createdAt <= 0L || System.currentTimeMillis() - createdAt !in 0L..CLEAN_PLAN_TTL_MS) {
            clearPlan()
            return
        }
        cleanPlanId = plan.optString("planId")
        cleanPlanCreatedAt = createdAt
        cacheSnapshotId = plan.optString("cacheSnapshotId")
        safeSnapshotId = plan.optString("safeSnapshotId")
        cacheCount = plan.optInt("cacheCount", 0).coerceAtLeast(0)
        safeCount = plan.optInt("safeCount", 0).coerceAtLeast(0)
        excludedIds = jsonStrings(plan.optJSONArray("excludedCandidateIds")).toCollection(linkedSetOf())
        selectionFinalized = plan.optBoolean("selectionFinalized", false) || plan.optInt("runCount", 0) > 0
        val total = cacheCount + safeCount
        if (cleanPlanId.isBlank() || total <= 0 || (cacheSnapshotId.isBlank() && safeSnapshotId.isBlank())) {
            clearPlan()
            return
        }
        screenState = screenState.copy(
            planReady = true,
            finalized = selectionFinalized,
            phase = if (selectionFinalized) {
                "已恢复最终清理计划 ${cleanPlanId.take(8)} · 剩余 $total 项"
            } else {
                "已恢复待选择计划 ${cleanPlanId.take(8)} · $total 项"
            },
            cacheCount = cacheCount,
            safeCount = safeCount,
            selectedCount = plan.optInt("selectedCandidateCount", total).coerceIn(0, total),
            selectedBytes = plan.optLong("selectedCandidateBytes", 0L).coerceAtLeast(0L),
            excludedIds = excludedIds.toSet()
        )
    }

    private fun persistSelection() {
        if (cleanPlanId.isBlank()) return
        val raw = preferences.getString(CLEAN_PLAN_KEY, null).orEmpty()
        val plan = runCatching { JSONObject(raw) }.getOrElse { return }
        plan.put("excludedCandidateIds", JSONArray(excludedIds.toList().sorted()))
            .put("selectedCandidateCount", screenState.selectedCount)
            .put("selectedCandidateBytes", screenState.selectedBytes)
            .put("selectionFinalized", selectionFinalized)
        preferences.edit().putString(CLEAN_PLAN_KEY, plan.toString()).apply()
    }

    private fun persistPlan() {
        val total = cacheCount + safeCount
        if (cleanPlanId.isBlank() || total <= 0) return
        val plan = JSONObject()
            .put("version", 2)
            .put("planId", cleanPlanId)
            .put("createdAt", cleanPlanCreatedAt)
            .put("optionsSha", sha256(optionsJson()))
            .put("cacheSnapshotId", cacheSnapshotId)
            .put("safeSnapshotId", safeSnapshotId)
            .put("cacheCount", cacheCount)
            .put("safeCount", safeCount)
            .put("originalCacheCount", cacheCount)
            .put("originalSafeCount", safeCount)
            .put("runCount", 0)
            .put("deletedBytes", 0L)
            .put("deletedFiles", 0L)
            .put("deletedDirectories", 0L)
            .put("processedCandidates", 0)
            .put("cleanedCandidates", 0)
            .put("changedCandidates", 0)
            .put("protectedCandidates", 0)
            .put("partialCandidates", 0)
            .put("failedCandidates", 0)
            .put("classifiedDeletedBytes", 0L)
            .put("unattributedDeletedBytes", 0L)
            .put("categoryStats", JSONObject())
            .put("riskStats", JSONObject())
            .put("deleteErrors", 0)
            .put("failures", 0)
            .put("resumable", false)
            .put("cacheSummary", "应用缓存 $cacheCount 项")
            .put("safeSummary", "安全项目 $safeCount 项")
            .put("selectionFinalized", selectionFinalized)
            .put("excludedCandidateIds", JSONArray(excludedIds.toList().sorted()))
            .put("selectedCandidateCount", cacheCount + safeCount)
            .put("selectedCandidateBytes", screenState.selectedBytes)
        preferences.edit()
            .putString(CLEAN_PLAN_KEY, plan.toString())
            .remove(LEGACY_PLAN_KEY)
            .apply()
    }

    private fun clearPlan() {
        preferences.edit().remove(CLEAN_PLAN_KEY).remove(LEGACY_PLAN_KEY).apply()
        clearPlanMemory()
    }

    private fun clearPlanMemory() {
        cleanPlanId = ""
        cleanPlanCreatedAt = 0L
        cacheSnapshotId = ""
        safeSnapshotId = ""
        cacheCount = 0
        safeCount = 0
        selectionFinalized = false
        excludedIds.clear()
    }

    private fun optionsJson(): String {
        val packages = preferences.getStringSet("package_whitelist", emptySet()).orEmpty().toList().sorted()
        val paths = preferences.getStringSet("path_whitelist", emptySet()).orEmpty().toList().sorted()
        val maxMb = preferences.getFloat("large_file_mb", 512f).toLong().coerceIn(64L, 16_384L)
        return JSONObject()
            .put("whitelistPackages", JSONArray(packages))
            .put("whitelistPaths", JSONArray(paths))
            .put("maxFileBytes", maxMb * 1024L * 1024L)
            .put("fragmentDays", preferences.getInt("fragment_days", 7).coerceIn(0, 365))
            .put("allowHighRisk", false)
            .toString()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun jsonStrings(array: JSONArray?): Set<String> {
        val result = LinkedHashSet<String>()
        if (array == null) return result
        for (index in 0 until array.length()) {
            array.optString(index).trim().takeIf { it.isNotBlank() }?.let(result::add)
        }
        return result
    }

    override fun onDestroy() {
        pollJob?.cancel()
        if (cacheBinding) runCatching { RootService.unbind(cacheConnection) }
        if (safeBinding) runCatching { RootService.unbind(safeConnection) }
        if (candidateBinding) runCatching { RootService.unbind(candidateConnection) }
        super.onDestroy()
    }

    companion object {
        private const val CLEAN_PLAN_KEY = "smart_clean_plan_v2"
        private const val LEGACY_PLAN_KEY = "smart_clean_plan_v1"
        private const val CLEAN_PLAN_TTL_MS = 30L * 60_000L
        private const val PAGE_SIZE = 100
        private const val MAX_UI_CANDIDATES = 20_000
    }
}

private data class PickerCandidate(
    val id: String,
    val source: String,
    val appName: String,
    val packageName: String,
    val category: String,
    val risk: String,
    val path: String,
    val bytes: Long,
    val files: Long,
    val directories: Long,
    val measured: Boolean,
    val note: String
)

private data class CandidatePickerState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val operation: String = "",
    val status: String = "正在连接 Root 引擎…",
    val phase: String = "连接完成后可开始智能扫描",
    val planReady: Boolean = false,
    val finalized: Boolean = false,
    val loadingCandidates: Boolean = false,
    val cacheCount: Int = 0,
    val safeCount: Int = 0,
    val selectedCount: Int = 0,
    val selectedBytes: Long = 0L,
    val candidates: List<PickerCandidate> = emptyList(),
    val excludedIds: Set<String> = emptySet(),
    val grouping: String = "app",
    val expandedGroups: Set<String> = emptySet(),
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0
)

private data class PickerGroup(
    val key: String,
    val title: String,
    val subtitle: String,
    val candidates: List<PickerCandidate>,
    val bytes: Long
)

@Composable
private fun CandidatePickerScreen(
    state: CandidatePickerState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onStop: () -> Unit,
    onReconnect: () -> Unit,
    onReload: () -> Unit,
    onGrouping: (String) -> Unit,
    onToggleExpanded: (String) -> Unit,
    onToggleCandidate: (String) -> Unit,
    onToggleGroup: (List<PickerCandidate>) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onFinalize: () -> Unit,
    onFinalizeAndClean: () -> Unit,
    onOpenClean: () -> Unit
) {
    val context = LocalContext.current
    val progress = if (state.progressTotal > 0) {
        (state.progressCurrent.toFloat() / state.progressTotal.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val groups = candidateGroups(state)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 34.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "CANDIDATE PLAN",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Text("智能清理", fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text("逐项查看 · 自由勾选 · 固化最终计划", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(30.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(58.dp).background(
                                if (state.finalized) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.secondaryContainer,
                                RoundedCornerShape(20.dp)
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (state.finalized) Icons.Rounded.CheckCircle else Icons.Rounded.CleaningServices,
                                contentDescription = null,
                                tint = if (state.finalized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Spacer(Modifier.size(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(state.status, fontWeight = FontWeight.Bold)
                            Text(
                                state.phase,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (state.running) {
                        if (state.progressTotal > 0) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        if (state.operation == "scan") {
                            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.Stop, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text("停止扫描")
                            }
                        }
                    } else if (!state.connected) {
                        OutlinedButton(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("重新连接 Root 引擎")
                        }
                    } else if (!state.planReady) {
                        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("开始智能扫描")
                        }
                    } else if (state.finalized) {
                        Button(onClick = onOpenClean, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("进入清理计划 · ${state.selectedCount} 项")
                        }
                        OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("放弃当前计划并重新扫描")
                        }
                    }
                }
            }
        }

        if (state.planReady) {
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        "SELECTION",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Text(
                        if (state.finalized) "最终清理计划" else "候选选择",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        "应用缓存 ${state.cacheCount} 项 · 安全项目 ${state.safeCount} 项",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "已选 ${state.selectedCount} 项 · ${Formatter.formatFileSize(context, state.selectedBytes)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (state.loadingCandidates) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("正在分页读取候选，目录大小会在 Root 侧按需计算")
                    }
                }
            }
        } else if (state.planReady && !state.finalized && state.candidates.isEmpty()) {
            item {
                OutlinedButton(
                    onClick = onReload,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("重新读取候选明细")
                }
            }
        }

        if (state.candidates.isNotEmpty() && !state.finalized) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilterChip(
                                selected = state.grouping == "app",
                                onClick = { onGrouping("app") },
                                label = { Text("按应用") }
                            )
                            FilterChip(
                                selected = state.grouping == "category",
                                onClick = { onGrouping("category") },
                                label = { Text("按类别") }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = onSelectAll, modifier = Modifier.weight(1f)) {
                                Text("全选")
                            }
                            OutlinedButton(onClick = onSelectNone, modifier = Modifier.weight(1f)) {
                                Text("全不选")
                            }
                        }
                    }
                }
            }

            groups.forEach { group ->
                item(key = "group:${group.key}") {
                    CandidateGroupHeader(
                        group = group,
                        selectedCount = group.candidates.count { it.id !in state.excludedIds },
                        expanded = group.key in state.expandedGroups,
                        onToggleSelection = { onToggleGroup(group.candidates) },
                        onToggleExpanded = { onToggleExpanded(group.key) }
                    )
                }
                if (group.key in state.expandedGroups) {
                    items(group.candidates, key = { "candidate:${it.id}" }) { candidate ->
                        CandidateRow(
                            candidate = candidate,
                            selected = candidate.id !in state.excludedIds,
                            onToggle = { onToggleCandidate(candidate.id) }
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "已选 ${state.selectedCount} 项 · ${Formatter.formatFileSize(context, state.selectedBytes)}",
                            fontWeight = FontWeight.Bold
                        )
                        Button(
                            onClick = onFinalizeAndClean,
                            enabled = state.selectedCount > 0,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("生成计划并进入清理")
                        }
                        OutlinedButton(
                            onClick = onFinalize,
                            enabled = state.selectedCount > 0,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("仅锁定最终计划")
                        }
                        Text(
                            "锁定后未选项目会从 Root 快照中移除。后续停止、异常退出和继续清理都只处理已选项目，且不会重新扫描。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

private fun candidateGroups(state: CandidatePickerState): List<PickerGroup> {
    val grouped = if (state.grouping == "category") {
        state.candidates.groupBy { it.category.ifBlank { "其他" } }
    } else {
        state.candidates.groupBy { it.appName.ifBlank { it.packageName.ifBlank { "系统与共享存储" } } }
    }
    return grouped.map { (title, candidates) ->
        val cache = candidates.count { it.source == "cache" }
        val safe = candidates.size - cache
        PickerGroup(
            key = "${state.grouping}:$title",
            title = title,
            subtitle = buildString {
                append(candidates.size).append(" 项")
                if (cache > 0) append(" · 缓存 ").append(cache)
                if (safe > 0) append(" · 安全 ").append(safe)
            },
            candidates = candidates.sortedByDescending { it.bytes.coerceAtLeast(0L) },
            bytes = candidates.sumOf { it.bytes.coerceAtLeast(0L) }
        )
    }.sortedWith(compareByDescending<PickerGroup> { it.bytes }.thenBy { it.title })
}

@Composable
private fun CandidateGroupHeader(
    group: PickerGroup,
    selectedCount: Int,
    expanded: Boolean,
    onToggleSelection: () -> Unit,
    onToggleExpanded: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = selectedCount == group.candidates.size,
                onCheckedChange = { onToggleSelection() }
            )
            Box(
                modifier = Modifier.size(42.dp).background(
                    MaterialTheme.colorScheme.secondaryContainer,
                    RoundedCornerShape(15.dp)
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(group.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${group.subtitle} · 已选 $selectedCount · ${Formatter.formatFileSize(context, group.bytes)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开"
            )
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: PickerCandidate,
    selected: Boolean,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() })
                Column(modifier = Modifier.weight(1f)) {
                    Text(candidate.category, fontWeight = FontWeight.Bold)
                    Text(
                        buildString {
                            append(riskLabel(candidate.risk))
                            append(" · ")
                            append(if (candidate.source == "cache") "应用缓存" else "安全项目")
                            if (candidate.measured && candidate.bytes >= 0L) {
                                append(" · ").append(Formatter.formatFileSize(context, candidate.bytes))
                            } else {
                                append(" · 大小待计算")
                            }
                            if (candidate.files >= 0L) append(" · ").append(candidate.files).append(" 文件")
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
            HorizontalDivider()
            Text(
                candidate.path,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (candidate.note.isNotBlank()) {
                Text(candidate.note, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
            }
        }
    }
}

private fun riskLabel(risk: String): String = when (risk) {
    "low" -> "低风险"
    "medium" -> "中风险"
    "high" -> "高风险"
    "critical" -> "关键风险"
    else -> risk.ifBlank { "未知风险" }
}
