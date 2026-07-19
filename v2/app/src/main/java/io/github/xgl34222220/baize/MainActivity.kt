package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.StatFs
import android.text.format.Formatter
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.databinding.ActivityMainBinding
import io.github.xgl34222220.baize.root.BaiZeRootService
import io.github.xgl34222220.baize.root.IBaiZeRootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: CandidateAdapter

    private var rootService: IBaiZeRootService? = null
    private var bindingRequested = false
    private var pendingScanAfterConnect = false
    private var currentPage = 0
    private var totalResults = 0
    private var scanWhitelisted = 0
    private var currentSnapshotId = ""
    private var cleanupRunning = false
    private var taskPollJob: Job? = null

    private val pageSize = 30
    private val selectionOverrides = mutableMapOf<String, Boolean>()
    private val preferences by lazy { getSharedPreferences("baize_v2", MODE_PRIVATE) }
    private val whitelist: MutableSet<String>
        get() = preferences.getStringSet(KEY_WHITELIST, emptySet()).orEmpty().toMutableSet()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            rootService = IBaiZeRootService.Stub.asInterface(service)
            bindingRequested = true
            renderConnected()
            if (pendingScanAfterConnect) {
                pendingScanAfterConnect = false
                runScan()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rootService = null
            bindingRequested = false
            cleanupRunning = false
            taskPollJob?.cancel()
            renderDisconnected("Root 服务已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        restoreSelectionOverrides()
        setupResultList()
        setupNavigation()
        setupActions()
        setupProductSettings()
        renderSavedReport()
        updateStorageOverview()
        renderDisconnected("正在连接 Root 服务…")
        connectRootService()
    }

    private fun setupResultList() {
        adapter = CandidateAdapter(
            onSelectionChanged = { item, checked ->
                selectionOverrides[item.path] = checked
                persistSelectionOverrides()
                updateSelectionText()
            },
            onWhitelist = { item -> addToWhitelist(item.packageName, item.appName) }
        )
        binding.resultsList.layoutManager = LinearLayoutManager(this)
        binding.resultsList.adapter = adapter
    }

    private fun setupNavigation() {
        binding.versionText.text = "Alpha 4"
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showPage(binding.homePage)
                R.id.nav_scan -> showPage(binding.scanPage)
                R.id.nav_plan -> showPage(binding.planPage)
                R.id.nav_records -> showPage(binding.recordsPage)
                R.id.nav_settings -> showPage(binding.settingsPage)
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        binding.bottomNavigation.selectedItemId = R.id.nav_home
    }

    private fun showPage(page: View) {
        val pages = listOf(
            binding.homePage,
            binding.scanPage,
            binding.planPage,
            binding.recordsPage,
            binding.settingsPage
        )
        pages.forEach { it.visibility = if (it === page) View.VISIBLE else View.GONE }
        page.scrollTo(0, 0)
    }

    private fun setupActions() {
        binding.homeScanButton.setOnClickListener {
            binding.bottomNavigation.selectedItemId = R.id.nav_scan
            if (rootService == null) {
                pendingScanAfterConnect = true
                connectRootService()
            } else {
                runScan()
            }
        }
        binding.connectButton.setOnClickListener { connectRootService() }
        binding.scanButton.setOnClickListener { runScan() }
        binding.cleanSelectedButton.setOnClickListener { confirmCleanup() }
        binding.cancelButton.setOnClickListener {
            rootService?.cancelCurrentTask()
            val message = if (cleanupRunning) "正在请求停止清理…" else "正在请求停止任务…"
            binding.resultText.text = message
            binding.homeTaskText.text = message
        }
        binding.previousPageButton.setOnClickListener { loadPage(currentPage - 1) }
        binding.nextPageButton.setOnClickListener { loadPage(currentPage + 1) }
        binding.clearWhitelistButton.setOnClickListener { confirmClearWhitelist() }
    }

    private fun setupProductSettings() {
        val scheduleEnabled = preferences.getBoolean(KEY_SCHEDULE_ENABLED, false)
        val interval = preferences.getFloat(KEY_INTERVAL_HOURS, 24f).coerceIn(1f, 72f)
        val screenOff = preferences.getBoolean(KEY_SCREEN_OFF, true)
        val chargingOnly = preferences.getBoolean(KEY_CHARGING_ONLY, false)
        val notifications = preferences.getBoolean(KEY_NOTIFICATIONS, true)
        val riskProtection = preferences.getBoolean(KEY_RISK_PROTECTION, true)
        val largeFileMb = preferences.getFloat(KEY_LARGE_FILE_MB, 512f).coerceIn(64f, 2048f)

        binding.scheduleEnabledSwitch.isChecked = scheduleEnabled
        binding.intervalSlider.value = interval
        binding.screenOffSwitch.isChecked = screenOff
        binding.chargingOnlySwitch.isChecked = chargingOnly
        binding.notificationSwitch.isChecked = notifications
        binding.riskProtectionSwitch.isChecked = riskProtection
        binding.largeFileSlider.value = largeFileMb

        updateIntervalText(interval)
        updateLargeFileText(largeFileMb)
        updatePlanState()
        refreshWhitelistIndicators()

        binding.scheduleEnabledSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(KEY_SCHEDULE_ENABLED, checked).apply()
            updatePlanState()
        }
        binding.intervalSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) preferences.edit().putFloat(KEY_INTERVAL_HOURS, value).apply()
            updateIntervalText(value)
            updatePlanState()
        }
        binding.screenOffSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(KEY_SCREEN_OFF, checked).apply()
            updatePlanState()
        }
        binding.chargingOnlySwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(KEY_CHARGING_ONLY, checked).apply()
            updatePlanState()
        }
        binding.notificationSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(KEY_NOTIFICATIONS, checked).apply()
        }
        binding.riskProtectionSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(KEY_RISK_PROTECTION, checked).apply()
        }
        binding.largeFileSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) preferences.edit().putFloat(KEY_LARGE_FILE_MB, value).apply()
            updateLargeFileText(value)
        }
    }

    private fun updateIntervalText(value: Float) {
        binding.intervalValueText.text = "每 ${value.toInt()} 小时"
    }

    private fun updateLargeFileText(value: Float) {
        binding.largeFileValueText.text = "${value.toInt()} MB"
    }

    private fun updatePlanState() {
        val enabled = binding.scheduleEnabledSwitch.isChecked
        val interval = binding.intervalSlider.value.toInt()
        binding.planStateText.text = if (enabled) {
            buildString {
                append("计划参数已保存 · 每 $interval 小时")
                if (binding.screenOffSwitch.isChecked) append(" · 息屏后")
                if (binding.chargingOnlySwitch.isChecked) append(" · 仅充电")
                append("。模块后台执行器将在下一阶段接入。")
            }
        } else {
            "自动清理未启用。计划参数会保留在本机。"
        }
    }

    private fun updateStorageOverview() {
        runCatching {
            val stat = StatFs(dataDir.absolutePath)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            val used = (total - free).coerceAtLeast(0)
            val percent = if (total > 0) ((used * 100L) / total).toInt().coerceIn(0, 100) else 0
            binding.storageRing.setProgressCompat(percent, true)
            binding.storageText.text = "$percent%"
            binding.storageSubText.text = "${Formatter.formatFileSize(this, free)} 可用"
        }.onFailure {
            binding.storageText.text = "--"
            binding.storageSubText.text = "存储读取失败"
        }
    }

    private fun renderSavedReport() {
        val summary = preferences.getString(KEY_LAST_REPORT_TEXT, null)
        val freed = preferences.getLong(KEY_LAST_CLEAN_BYTES, 0L)
        binding.recordSummaryText.text = summary ?: "还没有清理记录"
        binding.homeLastCleanText.text = if (freed > 0L) Formatter.formatFileSize(this, freed) else "暂无记录"
        refreshWhitelistIndicators()
    }

    private fun refreshWhitelistIndicators() {
        val count = whitelist.size
        binding.whitelistCountText.text = "$count 个应用"
        binding.clearWhitelistButton.text = if (count > 0) "管理并清空白名单（$count）" else "白名单为空"
        binding.clearWhitelistButton.isEnabled = count > 0 && !cleanupRunning
    }

    private fun rootIntent(): Intent = Intent(this, BaiZeRootService::class.java)
        .addCategory(RootService.CATEGORY_DAEMON_MODE)

    private fun connectRootService() {
        if (rootService != null) {
            renderConnected()
            return
        }
        binding.statusText.text = "正在请求 Root 权限并启动服务…"
        binding.homeStatusText.text = "正在连接 Root 服务"
        binding.connectButton.isEnabled = false
        try {
            RootService.bind(rootIntent(), connection)
            bindingRequested = true
        } catch (error: Throwable) {
            bindingRequested = false
            renderDisconnected(error.message ?: "Root 服务启动失败")
        }
    }

    private fun renderConnected() {
        binding.connectButton.isEnabled = true
        binding.scanButton.isEnabled = true
        binding.homeScanButton.isEnabled = true
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { rootService?.ping().orEmpty() } }
            val json = result.getOrNull()?.let(::JSONObject)
            val uid = json?.optInt("uid", -1) ?: -1
            val module = when {
                json?.optBoolean("moduleV2") == true -> "一体化模块已安装"
                json?.optBoolean("moduleV1") == true -> "检测到旧版 v1 模块"
                else -> "未检测到白泽模块"
            }
            val status = if (uid == 0) "Root 服务已连接" else "服务 UID=$uid，未获得完整 Root"
            binding.statusText.text = "$status · $module"
            binding.homeStatusText.text = status
            binding.moduleStateText.text = module
            binding.connectButton.text = "重新连接服务"
        }
    }

    private fun renderDisconnected(message: String) {
        binding.statusText.text = message
        binding.homeStatusText.text = message
        binding.moduleStateText.text = "等待 Root 服务"
        binding.connectButton.isEnabled = true
        binding.connectButton.text = "连接 Root 服务"
        binding.scanButton.isEnabled = false
        binding.homeScanButton.isEnabled = true
        binding.cleanSelectedButton.isEnabled = false
        binding.cancelButton.isEnabled = false
    }

    private fun runScan() {
        val service = rootService ?: run {
            pendingScanAfterConnect = true
            connectRootService()
            return
        }
        if (cleanupRunning) return

        binding.bottomNavigation.selectedItemId = R.id.nav_scan
        setTaskUi(true, allowCancel = true)
        binding.resultsSection.visibility = View.GONE
        binding.resultText.text = "正在验证真实且非空的缓存目录…"
        binding.homeTaskText.text = "正在快速扫描应用缓存…"
        currentSnapshotId = ""
        totalResults = 0
        scanWhitelisted = 0
        clearSelectionOverrides()

        val whitelistJson = JSONArray(whitelist.toList()).toString()
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { service.scanCandidates(whitelistJson) }
            }
            setTaskUi(false, allowCancel = false)
            result.onSuccess { raw ->
                val json = JSONObject(raw)
                if (json.optString("error") == "busy") {
                    val message = json.optString("message", "已有任务正在运行")
                    binding.resultText.text = message
                    binding.homeTaskText.text = message
                    return@onSuccess
                }
                if (json.optBoolean("cancelled")) {
                    val message = "扫描已停止 · ${json.optLong("elapsedMs")}ms"
                    binding.resultText.text = message
                    binding.homeTaskText.text = message
                    return@onSuccess
                }
                currentSnapshotId = json.optString("snapshotId")
                totalResults = json.optInt("totalCandidates")
                scanWhitelisted = json.optInt("whitelisted")
                val elapsed = json.optLong("elapsedMs")
                binding.resultText.text = buildString {
                    append("扫描完成 · ${elapsed}ms\n")
                    append("真实非空目录：$totalResults\n")
                    append("白名单保护：$scanWhitelisted\n")
                    append("快照有效期 30 分钟；清理前会再次验证路径。")
                }
                binding.homeTaskText.text = "扫描完成 · $totalResults 个真实缓存目录 · ${elapsed}ms"
                binding.resultsSection.visibility = if (totalResults > 0) View.VISIBLE else View.GONE
                binding.cleanSelectedButton.isEnabled = totalResults > 0 && currentSnapshotId.isNotBlank()
                if (totalResults > 0) loadPage(0)
            }.onFailure { error ->
                val message = "扫描失败：${error.message ?: error.javaClass.simpleName}"
                binding.resultText.text = message
                binding.homeTaskText.text = message
            }
        }
    }

    private fun loadPage(page: Int) {
        val service = rootService ?: return
        val snapshotId = currentSnapshotId
        if (snapshotId.isBlank() || cleanupRunning) return
        val pageCount = pageCount()
        if (page !in 0 until pageCount) return

        lifecycleScope.launch {
            binding.progressIndicator.visibility = View.VISIBLE
            binding.cancelButton.isEnabled = true
            binding.previousPageButton.isEnabled = false
            binding.nextPageButton.isEnabled = false
            binding.cleanSelectedButton.isEnabled = false
            binding.selectionText.text = "正在统计第 ${page + 1} 页，最长等待 8 秒…"
            val result = runCatching {
                withContext(Dispatchers.IO) { service.getResultPage(snapshotId, page * pageSize, pageSize) }
            }
            binding.progressIndicator.visibility = View.GONE
            binding.cancelButton.isEnabled = false
            result.onSuccess { raw ->
                val json = JSONObject(raw)
                if (json.has("error")) {
                    binding.selectionText.text = json.optString("message", "结果分页读取失败")
                    binding.cleanSelectedButton.isEnabled = false
                    return@onSuccess
                }
                val array = json.optJSONArray("items") ?: JSONArray()
                val items = ArrayList<ScanCandidate>(array.length())
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val path = item.getString("path")
                    val whitelisted = item.optBoolean("whitelisted") || item.getString("packageName") in whitelist
                    items += ScanCandidate(
                        appName = item.optString("appName", item.getString("packageName")),
                        packageName = item.getString("packageName"),
                        categoryLabel = item.optString("categoryLabel", "缓存"),
                        path = path,
                        userId = item.optInt("userId"),
                        bytes = item.optLong("bytes"),
                        files = item.optLong("files"),
                        directories = item.optLong("directories"),
                        whitelisted = whitelisted,
                        readable = item.optBoolean("readable", true),
                        measured = item.optBoolean("measured", false),
                        complete = item.optBoolean("complete", false),
                        selected = selectionOverrides[path] == true && !whitelisted
                    )
                }
                currentPage = page
                adapter.submitPage(items)
                binding.pageText.text = "${page + 1} / $pageCount"
                binding.previousPageButton.isEnabled = page > 0
                binding.nextPageButton.isEnabled = page + 1 < pageCount
                binding.cleanSelectedButton.isEnabled = currentSnapshotId.isNotBlank()
                updateSelectionText()
            }.onFailure {
                binding.selectionText.text = "结果分页读取失败：${it.message}"
                binding.cleanSelectedButton.isEnabled = false
            }
        }
    }

    private fun pageCount(): Int = ceil(totalResults / pageSize.toDouble()).toInt().coerceAtLeast(1)

    private fun updateSelectionText() {
        val selectedTotal = selectionOverrides.values.count { it }
        binding.selectionText.text = buildString {
            append("共 $totalResults 项 · 本页已选 ${adapter.currentSelectedCount()} 项")
            append(" · 全部分页已选 $selectedTotal 项")
            append(" · 白名单 ${whitelist.size} 个应用")
        }
    }

    private fun addToWhitelist(packageName: String, appName: String) {
        if (cleanupRunning) return
        val updated = whitelist
        updated += packageName
        preferences.edit().putStringSet(KEY_WHITELIST, updated).apply()
        adapter.markPackageWhitelisted(packageName)
        selectionOverrides.entries.removeAll { it.key.contains("/$packageName/") }
        persistSelectionOverrides()
        updateSelectionText()
        refreshWhitelistIndicators()
        binding.resultText.text = "已将 $appName 加入白名单；清理服务会再次读取白名单并强制保护。"
    }

    private fun confirmCleanup() {
        if (currentSnapshotId.isBlank() || totalResults <= 0 || cleanupRunning) return
        val selectedTotal = selectionOverrides.values.count { it }
        if (selectedTotal <= 0) {
            binding.resultText.text = "请先勾选至少一个缓存项目。"
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清理已选缓存")
            .setMessage(
                "本次只清理你明确勾选的 $selectedTotal 个缓存项目。\n\n" +
                    "清理前会重新校验包名、路径、目录类型和挂载点；只删除 cache/code_cache 内部内容，保留缓存根目录。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("开始清理") { _, _ -> startCleanup() }
            .show()
    }

    private fun startCleanup() {
        val service = rootService ?: return
        val snapshotId = currentSnapshotId
        if (snapshotId.isBlank()) return

        cleanupRunning = true
        adapter.setInteractionEnabled(false)
        setTaskUi(true, allowCancel = true)
        binding.resultsList.isEnabled = false
        binding.resultText.text = "正在提交清理任务…"
        binding.homeTaskText.text = "正在准备安全清理…"

        val selectionJson = JSONObject().apply {
            selectionOverrides.forEach { (path, selected) -> put(path, selected) }
        }.toString()
        val whitelistJson = JSONArray(whitelist.toList()).toString()

        taskPollJob?.cancel()
        taskPollJob = lifecycleScope.launch {
            while (isActive && cleanupRunning) {
                val state = runCatching {
                    withContext(Dispatchers.IO) { service.getTaskState() }
                }.getOrNull()
                if (!state.isNullOrBlank()) renderTaskState(JSONObject(state))
                delay(400)
            }
        }

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    service.cleanSelected(snapshotId, selectionJson, whitelistJson)
                }
            }
            cleanupRunning = false
            taskPollJob?.cancel()
            adapter.setInteractionEnabled(true)
            binding.resultsList.isEnabled = true
            setTaskUi(false, allowCancel = false)
            result.onSuccess { raw -> renderCleanupReport(JSONObject(raw)) }
                .onFailure { error ->
                    val message = "清理失败：${error.message ?: error.javaClass.simpleName}"
                    binding.resultText.text = message
                    binding.homeTaskText.text = message
                    binding.cleanSelectedButton.isEnabled = currentSnapshotId.isNotBlank()
                }
        }
    }

    private fun renderTaskState(json: JSONObject) {
        if (json.optString("operation") != "clean") return
        val current = json.optInt("current")
        val total = json.optInt("total")
        val appName = json.optString("currentApp")
        val bytes = json.optLong("deletedBytes")
        val files = json.optLong("deletedFiles")
        val failures = json.optInt("failures")
        val summary = buildString {
            append(json.optString("phase", "正在清理"))
            if (total > 0) append(" · $current/$total")
            append("\n已释放 ${Formatter.formatFileSize(this@MainActivity, bytes)}")
            append(" · 已删除 $files 个文件")
            if (failures > 0) append(" · 失败 $failures")
            if (appName.isNotBlank()) append("\n当前：$appName")
            if (json.optBoolean("cancelRequested")) append("\n正在安全停止…")
        }
        binding.resultText.text = summary
        binding.homeTaskText.text = summary.replace('\n', ' ')
    }

    private fun renderCleanupReport(json: JSONObject) {
        if (!json.optBoolean("success")) {
            val message = json.optString("message", "清理任务未执行")
            binding.resultText.text = message
            binding.homeTaskText.text = message
            binding.cleanSelectedButton.isEnabled = false
            return
        }

        val cancelled = json.optBoolean("cancelled")
        val timedOut = json.optBoolean("totalTimedOut")
        val deletedBytes = json.optLong("deletedBytes")
        val title = when {
            cancelled -> "清理已停止"
            timedOut -> "清理达到总时间预算"
            else -> "清理完成"
        }
        val summary = buildString {
            append("$title · ${json.optLong("elapsedMs")}ms\n")
            append("已处理 ${json.optInt("processed")}/${json.optInt("selected")} 项\n")
            append("已释放 ${Formatter.formatFileSize(this@MainActivity, deletedBytes)}\n")
            append("删除文件：${json.optLong("deletedFiles")} · 目录：${json.optLong("deletedDirectories")}\n")
            append("完成：${json.optInt("cleanedCandidates")} · 跳过：${json.optInt("skippedCandidates")}")
            append(" · 异常：${json.optInt("failedCandidates")}")
            if (json.optInt("protectedMounts") > 0) append("\n挂载点保护：${json.optInt("protectedMounts")}")
            if (json.optInt("failures") > 0) append(" · 删除失败：${json.optInt("failures")}")
            append("\n详细报告：/data/adb/baize-v2/last-clean-report.json")
        }

        binding.resultText.text = summary
        binding.recordSummaryText.text = summary
        binding.homeTaskText.text = "$title · 已释放 ${Formatter.formatFileSize(this, deletedBytes)}"
        binding.homeLastCleanText.text = Formatter.formatFileSize(this, deletedBytes)
        preferences.edit()
            .putString(KEY_LAST_REPORT_TEXT, summary)
            .putLong(KEY_LAST_CLEAN_BYTES, deletedBytes)
            .apply()

        currentSnapshotId = ""
        totalResults = 0
        scanWhitelisted = 0
        adapter.submitPage(emptyList())
        binding.resultsSection.visibility = View.GONE
        binding.cleanSelectedButton.isEnabled = false
        clearSelectionOverrides()
        updateStorageOverview()
    }

    private fun setTaskUi(running: Boolean, allowCancel: Boolean) {
        binding.progressIndicator.visibility = if (running) View.VISIBLE else View.GONE
        binding.connectButton.isEnabled = !running
        binding.homeScanButton.isEnabled = !running
        binding.scanButton.isEnabled = !running && rootService != null
        binding.cancelButton.isEnabled = running && allowCancel
        binding.cleanSelectedButton.isEnabled = !running && currentSnapshotId.isNotBlank() && totalResults > 0
        binding.previousPageButton.isEnabled = !running && currentPage > 0
        binding.nextPageButton.isEnabled = !running && currentPage + 1 < pageCount()
        binding.clearWhitelistButton.isEnabled = !running && whitelist.isNotEmpty()
    }

    private fun restoreSelectionOverrides() {
        val raw = preferences.getString(KEY_SELECTION_OVERRIDES, null).orEmpty()
        runCatching {
            val json = JSONObject(raw)
            val keys = json.keys()
            while (keys.hasNext()) {
                val path = keys.next()
                if (path.startsWith("/")) selectionOverrides[path] = json.optBoolean(path, false)
            }
        }
    }

    private fun persistSelectionOverrides() {
        val json = JSONObject()
        selectionOverrides.forEach { (path, selected) -> json.put(path, selected) }
        preferences.edit().putString(KEY_SELECTION_OVERRIDES, json.toString()).apply()
    }

    private fun clearSelectionOverrides() {
        selectionOverrides.clear()
        preferences.edit().remove(KEY_SELECTION_OVERRIDES).apply()
    }

    private fun confirmClearWhitelist() {
        if (whitelist.isEmpty() || cleanupRunning) return
        AlertDialog.Builder(this)
            .setTitle("清空白名单")
            .setMessage("将移除全部 ${whitelist.size} 个应用白名单。为避免使用旧快照清理，清空后需要重新扫描。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                preferences.edit().remove(KEY_WHITELIST).apply()
                currentSnapshotId = ""
                totalResults = 0
                adapter.submitPage(emptyList())
                binding.resultsSection.visibility = View.GONE
                binding.cleanSelectedButton.isEnabled = false
                binding.resultText.text = "白名单已清空，请重新扫描。"
                refreshWhitelistIndicators()
            }
            .show()
    }

    override fun onDestroy() {
        taskPollJob?.cancel()
        if (bindingRequested) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }

    companion object {
        private const val KEY_WHITELIST = "package_whitelist"
        private const val KEY_SELECTION_OVERRIDES = "selection_overrides"
        private const val KEY_LAST_REPORT_TEXT = "last_report_text"
        private const val KEY_LAST_CLEAN_BYTES = "last_clean_bytes"
        private const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
        private const val KEY_INTERVAL_HOURS = "interval_hours"
        private const val KEY_SCREEN_OFF = "screen_off_only"
        private const val KEY_CHARGING_ONLY = "charging_only"
        private const val KEY_NOTIFICATIONS = "cleanup_notifications"
        private const val KEY_RISK_PROTECTION = "risk_protection"
        private const val KEY_LARGE_FILE_MB = "large_file_mb"
    }
}
