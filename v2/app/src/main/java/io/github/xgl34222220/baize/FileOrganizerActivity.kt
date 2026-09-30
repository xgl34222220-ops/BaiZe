package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import io.github.xgl34222220.baize.root.RootServiceClients
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class FileOrganizerActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private var service: IProfileRootService? = null
    private var bound = false
    private var state by mutableStateOf(FileOrganizerUiState())
    private var schedule by mutableStateOf(FileOrganizerScheduleSettings())
    private var scheduleSavedText by mutableStateOf("")
    private var scanJob: Job? = null
    private val scanGeneration = ScanLoadGeneration()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = RootServiceClients.profile(binder, applicationContext.cacheDir)
            bound = true
            state = state.copy(connected = true, status = if (state.items.isEmpty()) "文件归类服务已就绪" else state.status)
            loadRootSchedule()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            scanGeneration.invalidate()
            scanJob?.cancel()
            service = null
            bound = false
            state = state.copy(
                connected = false,
                running = false,
                previewReady = false,
                status = "Root 服务已断开，预览已保留；重新扫描后可归类"
            )
            saveReview()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        schedule = FileOrganizerWorker.loadSettings(this)
        setContent {
            val appearance = appearanceViewModel.settings.collectAsStateWithLifecycle().value
            BaiZeTheme(appearance) {
                FileOrganizerScreen(
                    state = state,
                    schedule = schedule,
                    scheduleSavedText = scheduleSavedText,
                    onBack = ::finish,
                    onOneTap = ::oneTapOrganize,
                    onUndo = ::undoLast,
                    onStop = ::stopTask,
                    onScheduleChange = {
                        schedule = it
                        scheduleSavedText = ""
                    },
                    onSaveSchedule = ::saveSchedule,
                    onToggleItem = ::toggleItem,
                    onToggleCategory = ::toggleCategory,
                    onToggleAll = ::toggleAll,
                    onApply = ::applySelection
                )
            }
        }
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { ScanReviewStore.read(this@FileOrganizerActivity, "organizer") }
            if (saved != null) state = restoreOrganizerReview(saved, System.currentTimeMillis(), SystemClock.elapsedRealtime())
            bindService()
        }
    }

    override fun onResume() {
        super.onResume()
        if (service == null) schedule = FileOrganizerWorker.loadSettings(this)
        else loadRootSchedule()
    }

    private fun loadRootSchedule() {
        val root = service ?: return
        lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) {
                runCatching { JSONObject(root.getSchedulerConfig()) }.getOrNull()
            } ?: return@launch
            schedule = FileOrganizerWorker.fromRootConfig(this@FileOrganizerActivity, config)
            FileOrganizerWorker.cacheUiSettings(this@FileOrganizerActivity, schedule)
            FileOrganizerWorker.ensureWatchdog(this@FileOrganizerActivity)
        }
    }

    private fun bindService() {
        if (state.items.isEmpty()) state = state.copy(status = "正在连接 Root 文件归类服务…")
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java)
                    .addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
            bound = true
        }.onFailure {
            bound = false
            state = state.copy(
                status = "Root 服务启动失败：${it.message ?: it.javaClass.simpleName}"
            )
        }
    }

    private fun saveSchedule() {
        val root = service ?: run {
            scheduleSavedText = "Root 服务尚未连接，计划没有保存"
            return
        }
        scheduleSavedText = "正在保存到 Root Supervisor…"
        lifecycleScope.launch {
            val payload = JSONObject()
                .put("schedule_organize_enabled", if (schedule.enabled) 1 else 0)
                .put("schedule_organize_minutes", schedule.intervalMinutes)
                .put("schedule_organize_hours", ((schedule.intervalMinutes + 59) / 60).coerceAtLeast(1))
                .put("organize_charging_only", if (schedule.chargingOnly) 1 else 0)
                .put("organize_screen_off_only", if (schedule.screenOffOnly) 1 else 0)
                .put("organize_device_idle_only", if (schedule.idleOnly) 1 else 0)
                .put("organize_run_immediately", if (schedule.runImmediatelyOnEnable) 1 else 0)
                .put("organizer_conflict_policy", schedule.conflictPolicy.coerceIn(0, 2))
            if (schedule.enabled) payload.put("enabled", 1)
            val saved = withContext(Dispatchers.IO) {
                runCatching { JSONObject(root.saveSchedulerConfig(payload.toString())) }.getOrElse {
                    JSONObject().put("error", "save_failed").put("message", it.message ?: it.javaClass.simpleName)
                }
            }
            if (!saved.optBoolean("success", false)) {
                scheduleSavedText = saved.optString("message", "Root 计划保存失败")
                return@launch
            }
            FileOrganizerWorker.cacheUiSettings(this@FileOrganizerActivity, schedule)
            FileOrganizerWorker.ensureWatchdog(this@FileOrganizerActivity)
            val immediateText = if (schedule.enabled && schedule.runImmediatelyOnEnable) "，开启时会加入立即执行队列" else ""
            scheduleSavedText = if (schedule.enabled) {
                "Root 计划已保存：每 ${FileOrganizerWorker.intervalLabel(schedule.intervalMinutes)}检查一次$immediateText"
            } else "定时归类已关闭"
            FileOrganizerWorker.recordResult(this@FileOrganizerActivity, scheduleSavedText)
            loadRootSchedule()
        }
    }

    private fun oneTapOrganize() {
        val root = service ?: run { bindService(); return }
        if (state.running) return
        val generation = scanGeneration.start()
        state = state.copy(running = true, previewReady = false, snapshotId = "", items = emptyList(),
            selectedIds = emptySet(), totalFound = 0, truncated = false,
            status = "正在查找可归类文件；扫描不会移动文件")
        saveReview()
        scanJob = lifecycleScope.launch {
            try {
                val scan = withContext(Dispatchers.IO) { JSONObject(root.scanFileOrganizer()) }
                if (!scanGeneration.accepts(generation)) return@launch
                check(!scan.has("error") && scan.optBoolean("success")) { scan.optString("message", "文件归类扫描失败") }
                check(!scan.optBoolean("cancelled")) { "归类扫描已停止，请重新扫描" }
                val id = scan.optString("snapshotId")
                val total = scan.optInt("total", -1)
                check(id.isNotBlank() && total >= 0) { "归类扫描没有返回有效计划" }
                val expires = SystemClock.elapsedRealtime() + scan.optLong("expiresInMs", 0L).coerceAtLeast(0L)
                state = state.copy(snapshotId = id, expiresAtRealtime = expires, totalFound = total,
                    truncated = scan.optBoolean("truncated"), status = "正在读取归类预览 · 0 / $total")
                val cursor = ScanPageCursor(id)
                val loaded = ArrayList<OrganizerPreviewItem>()
                val loadedIds = HashSet<String>()
                // Read every page before enabling any move. A bounded preview must never
                // silently stand in for the whole plan or authorize unseen files.
                while (!cursor.complete) {
                    currentCoroutineContext().ensureActive()
                    val page = withContext(Dispatchers.IO) {
                        JSONObject(root.runMaintenanceTool("organizer_page", JSONObject().put("snapshotId", id)
                            .put("offset", cursor.offset).put("limit", 100).toString()))
                    }
                    if (!scanGeneration.accepts(generation)) return@launch
                    check(!page.has("error") && page.optBoolean("success")) { page.optString("message", "预览读取失败") }
                    val array = page.optJSONArray("items") ?: error("预览缺少文件列表")
                    cursor.accept(page.optString("snapshotId"), page.optInt("offset", -1), page.optInt("total", -1), array.length())
                    val batch = parseOrganizerPreview(array)
                    check(batch.size == array.length()) { "部分文件缺少标识，预览未完整读取" }
                    check(batch.all { loadedIds.add(it.id) }) { "归类预览存在重复标识，请重新扫描" }
                    loaded.addAll(batch)
                    state = state.copy(items = loaded.toList(), status = "正在读取归类预览 · ${loaded.size} / $total")
                }
                check(cursor.total == total) { "归类计划已变化，请重新扫描" }
                val valid = SystemClock.elapsedRealtime() < expires
                state = state.copy(running = false, previewReady = valid && loaded.isNotEmpty(), selectedIds = emptySet(),
                    status = when {
                        !valid -> "归类计划已过期，预览已保留，请重新扫描"
                        state.truncated -> "扫描范围未完整覆盖，已找到 $total 个文件；请核对后选择"
                        total == 0 -> "已检查范围内没有需要归类的新文件"
                        else -> "找到 $total 个文件。先核对来源和去向，再选择需要移动的内容"
                    })
                scanGeneration.invalidate()
                saveReview()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!scanGeneration.accepts(generation)) return@launch
                scanGeneration.invalidate()
                state = state.copy(running = false, previewReady = false,
                    status = "归类预览未完成：${error.message.orEmpty()}。已读取项目仅供查看")
                saveReview()
            }
        }
    }

    private fun applySelection() {
        val root = service ?: return
        if (!state.canEdit(SystemClock.elapsedRealtime())) return
        val ids = state.items.filter { it.id in state.selectedIds }.map { it.id }
        if (ids.isEmpty()) return
        val snapshotId = state.snapshotId
        val conflictPolicy = schedule.conflictPolicy
        // Once submitted, a lost reply must not make the same plan executable again.
        state = state.copy(running = true, previewReady = false, snapshotId = "", status = "正在归类已选 ${ids.size} 个文件…")
        saveReview()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { JSONObject(root.applyFileOrganizer(snapshotId,
                    JSONObject().put("all", false).put("ids", JSONArray(ids)).put("conflictPolicy", conflictPolicy).toString())) }
                    .getOrElse { JSONObject().put("error", "apply_failed").put("message", it.message ?: it.javaClass.simpleName) }
            }
            if (result.has("error")) {
                state = state.copy(running = false, status = "归类结果未确认：${result.optString("message", "请检查记录")}。请重新扫描后继续")
            } else {
                val moved = result.optInt("moved")
                val failed = result.optInt("failed")
                state = state.copy(running = false, lastTotal = moved, lastBytes = result.optLong("bytes"),
                    undoAvailable = result.optBoolean("undoAvailable"),
                    status = "${if (result.optBoolean("cancelled")) "归类已停止" else if (failed > 0) "部分文件未完成" else "归类完成"}：移动 $moved/${ids.size} 个 · " +
                        "重命名 ${result.optInt("renamed")} 个 · 重复 ${result.optInt("deduplicated")} 个 · 跳过 ${result.optInt("skipped")} 个 · 失败 $failed 个")
            }
            saveReview()
        }
    }

    private fun toggleItem(id: String) {
        if (!state.canEdit(SystemClock.elapsedRealtime()) || state.items.none { it.id == id }) return
        state = state.copy(selectedIds = state.selectedIds.toMutableSet().apply { if (!add(id)) remove(id) })
    }

    private fun toggleCategory(category: String) = toggleIds(state.items.filter { it.category == category }.mapTo(linkedSetOf()) { it.id })
    private fun toggleAll() = toggleIds(state.items.mapTo(linkedSetOf()) { it.id })
    private fun toggleIds(ids: Set<String>) {
        if (!state.canEdit(SystemClock.elapsedRealtime())) return
        val selected = state.selectedIds.toMutableSet()
        if (selected.containsAll(ids)) selected.removeAll(ids) else selected.addAll(ids)
        state = state.copy(selectedIds = selected)
    }

    private fun stopTask() {
        val root = service ?: return
        val scanning = scanJob?.isActive == true
        if (scanning) {
            scanGeneration.invalidate()
            scanJob?.cancel()
        }
        state = state.copy(status = "正在安全停止…", previewReady = false)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { root.cancelCurrentTask() } }
            if (scanning) {
                state = state.copy(running = false, status = "归类扫描已停止，已读取项目仅供查看")
                saveReview()
            }
        }
    }

    private fun saveReview() {
        val snapshot = state
        val wallTime = System.currentTimeMillis()
        val realtime = SystemClock.elapsedRealtime()
        ScanReviewStore.save(this, "organizer") { organizerReviewJson(snapshot, wallTime, realtime) }
    }

    override fun onStop() {
        saveReview()
        super.onStop()
    }

    private fun undoLast() {
        val root = service ?: return
        if (state.running) return
        state = state.copy(running = true, previewReady = false, snapshotId = "", status = "正在撤销上一次文件归类…")
        saveReview()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { JSONObject(root.undoFileOrganizer()) }.getOrElse {
                    JSONObject()
                        .put("error", "undo_failed")
                        .put("message", it.message ?: it.javaClass.simpleName)
                }
            }
            if (result.has("error")) {
                state = state.copy(
                    running = false,
                    status = result.optString("message", "撤销失败")
                )
                return@launch
            }
            state = state.copy(
                running = false,
                undoAvailable = result.optBoolean("undoAvailable"),
                status = "撤销完成：恢复 ${result.optInt("restored")} 个 · 跳过 ${
                    result.optInt("skipped")
                } 个 · 失败 ${result.optInt("failed")} 个"
            )
        }
    }

    override fun onDestroy() {
        scanGeneration.invalidate()
        scanJob?.cancel()
        if (bound) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }
}
