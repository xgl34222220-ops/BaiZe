package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.StatFs
import android.text.format.Formatter
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.databinding.ActivityDashboardBinding
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DashboardActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDashboardBinding
    private val preferences by lazy { getSharedPreferences("baize_v2", MODE_PRIVATE) }

    private var profileService: IProfileRootService? = null
    private var serviceBound = false
    private var taskRunning = false
    private var taskPollJob: Job? = null
    private var loadingConfig = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            profileService = IProfileRootService.Stub.asInterface(binder)
            serviceBound = true
            readServiceStatus()
            loadSchedulerConfig()
            refreshModuleState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            profileService = null
            serviceBound = false
            taskRunning = false
            taskPollJob?.cancel()
            renderServiceState("Root 服务已断开", false)
            renderTaskButtons(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.versionText.text = "Alpha 9"
        setupNavigation()
        setupActions()
        setupSettings()
        updateStorage()
        refreshSavedReport()
        connectService()
    }

    override fun onResume() {
        super.onResume()
        updateStorage()
        refreshSavedReport()
        refreshWhitelist()
        if (profileService != null) {
            readServiceStatus()
            refreshModuleState()
        }
    }

    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> show(binding.homePage)
                R.id.nav_plan -> show(binding.planPage)
                R.id.nav_records -> show(binding.recordsPage)
                R.id.nav_settings -> show(binding.settingsPage)
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        binding.bottomNavigation.selectedItemId = R.id.nav_home
    }

    private fun show(page: View) {
        val pages = listOf(binding.homePage, binding.planPage, binding.recordsPage, binding.settingsPage)
        pages.forEach { it.visibility = if (it === page) View.VISIBLE else View.GONE }
        page.scrollTo(0, 0)
    }

    private fun setupActions() {
        binding.cleanNowButton.setOnClickListener { runModuleTask("clean") }
        binding.scanOnlyButton.setOnClickListener { startActivity(Intent(this, SmartScanActivity::class.java)) }
        binding.stopTaskButton.setOnClickListener {
            profileService?.cancelCurrentTask()
            binding.taskStatusText.text = "正在安全停止当前任务…"
        }
        binding.deepToolButton.setOnClickListener { openProfile("deep") }
        binding.corpsesToolButton.setOnClickListener { openProfile("corpses") }
        binding.advancedAuditButton.setOnClickListener {
            startActivity(Intent(this, CleanCenterActivity::class.java))
        }
        binding.reconnectButton.setOnClickListener {
            if (serviceBound) runCatching { RootService.unbind(connection) }
            profileService = null
            serviceBound = false
            connectService()
        }
        binding.savePlanButton.setOnClickListener { saveSchedulerConfig() }
        binding.refreshRecordsButton.setOnClickListener { refreshModuleState() }
    }

    private fun openProfile(profile: String) {
        startActivity(Intent(this, ProfileActivity::class.java).putExtra(ProfileActivity.EXTRA_PROFILE, profile))
    }

    private fun setupSettings() {
        binding.intervalSlider.addOnChangeListener { _, value, _ ->
            binding.intervalText.text = "每 ${value.toInt()} 小时"
            updatePlanPreview()
        }
        binding.dailyHourSlider.addOnChangeListener { _, value, _ ->
            binding.dailyHourText.text = String.format("每日 %02d:00", value.toInt())
            updatePlanPreview()
        }
        binding.minBatterySlider.addOnChangeListener { _, value, _ ->
            binding.minBatteryText.text = "最低电量 ${value.toInt()}%"
        }
        binding.largeFileSlider.addOnChangeListener { _, value, _ ->
            binding.largeFileText.text = "单文件上限 ${value.toInt()} MB"
        }
        binding.fragmentDaysSlider.addOnChangeListener { _, value, _ ->
            binding.fragmentDaysText.text = fragmentRetentionLabel(value.toInt())
        }

        val previewSwitches = listOf(
            binding.scheduleSwitch,
            binding.dailySwitch,
            binding.cacheScheduleSwitch,
            binding.emptyScheduleSwitch,
            binding.rulesScheduleSwitch,
            binding.fragmentScheduleSwitch,
            binding.deepScheduleSwitch,
            binding.screenOffSwitch,
            binding.chargingSwitch,
            binding.deviceIdleSwitch
        )
        previewSwitches.forEach { toggle ->
            toggle.setOnCheckedChangeListener { _, _ -> if (!loadingConfig) updatePlanPreview() }
        }
    }

    private fun runModuleTask(mode: String) {
        val service = profileService ?: run {
            binding.taskStatusText.text = "正在连接 Root 服务…"
            connectService()
            return
        }
        if (taskRunning) return

        taskRunning = true
        renderTaskButtons(true)
        binding.taskStatusText.text = if (mode == "scan") {
            "正在执行安全扫描，不会删除任何内容…"
        } else {
            "正在自动扫描并清理已启用的安全项目…"
        }
        binding.recentTaskText.text = binding.taskStatusText.text

        taskPollJob?.cancel()
        taskPollJob = lifecycleScope.launch {
            while (isActive && taskRunning) {
                val raw = runCatching { withContext(Dispatchers.IO) { service.getTaskState() } }.getOrNull()
                if (!raw.isNullOrBlank()) renderTaskState(JSONObject(raw))
                delay(450)
            }
        }

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { service.runModuleTask(mode) }
            }
            taskRunning = false
            taskPollJob?.cancel()
            renderTaskButtons(false)
            result.onSuccess { renderModuleTaskResult(JSONObject(it)) }
                .onFailure { error ->
                    val message = "任务失败：${error.message ?: error.javaClass.simpleName}"
                    binding.taskStatusText.text = message
                    binding.recentTaskText.text = message
                }
            refreshModuleState()
        }
    }

    private fun renderTaskState(json: JSONObject) {
        val phase = json.optString("phase", "任务执行中")
        val current = json.optInt("progress_current", json.optInt("current", 0))
        val total = json.optInt("progress_total", json.optInt("total", 0))
        val path = json.optString("current_path", json.optString("currentPath"))
        binding.taskStatusText.text = buildString {
            append(phase)
            if (total > 0) append(" · $current/$total")
            if (path.isNotBlank()) append("\n").append(path.takeLast(88))
            if (json.optBoolean("cancelRequested")) append("\n正在安全停止…")
        }
        binding.recentTaskText.text = binding.taskStatusText.text
    }

    private fun renderModuleTaskResult(json: JSONObject) {
        val success = json.optBoolean("success")
        val cancelled = json.optBoolean("cancelled")
        val elapsed = json.optLong("elapsedMs")
        val output = json.optString("output").trim()
        val lastLines = output.lineSequence().filter { it.isNotBlank() }.takeLast(4).joinToString("\n")
        val summary = buildString {
            append(
                when {
                    cancelled -> "任务已停止"
                    success -> json.optString("message", "任务完成")
                    else -> json.optString("message", "任务失败")
                }
            )
            append(" · ${elapsed}ms")
            if (lastLines.isNotBlank()) append("\n").append(lastLines)
        }
        binding.taskStatusText.text = summary
        binding.recentTaskText.text = summary
        binding.recordSummaryText.text = summary
        val latestBytes = json.optJSONObject("latest")?.optLong("bytes", 0L) ?: 0L
        preferences.edit().apply {
            putString("last_report_text", summary)
            if (latestBytes > 0L) putLong("last_clean_bytes", latestBytes)
        }.apply()
        if (latestBytes > 0L) binding.lastFreedText.text = Formatter.formatFileSize(this, latestBytes)
    }

    private fun renderTaskButtons(running: Boolean) {
        binding.cleanNowButton.isEnabled = !running && profileService != null
        binding.scanOnlyButton.isEnabled = !running && profileService != null
        binding.stopTaskButton.visibility = if (running) View.VISIBLE else View.GONE
    }

    private fun loadSchedulerConfig() {
        val service = profileService ?: return
        lifecycleScope.launch {
            val raw = runCatching { withContext(Dispatchers.IO) { service.getSchedulerConfig() } }.getOrNull() ?: return@launch
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@launch
            loadingConfig = true
            binding.scheduleSwitch.isChecked = json.optInt("enabled", 1) == 1
            binding.intervalSlider.value = json.optInt("schedule_cache_hours", 24).coerceIn(1, 720).toFloat()
            binding.dailySwitch.isChecked = json.optInt("daily_schedule_enabled", 0) == 1
            binding.dailyHourSlider.value = json.optInt("daily_schedule_hour", 3).coerceIn(0, 23).toFloat()
            binding.cacheScheduleSwitch.isChecked = json.optInt("schedule_cache_enabled", 1) == 1
            binding.emptyScheduleSwitch.isChecked = json.optInt("schedule_empty_enabled", 1) == 1
            binding.rulesScheduleSwitch.isChecked = json.optInt("schedule_rules_enabled", 1) == 1
            binding.fragmentScheduleSwitch.isChecked = json.optInt("schedule_fragment_enabled", 1) == 1
            binding.deepScheduleSwitch.isChecked = json.optInt("schedule_deep_enabled", 0) == 1
            binding.screenOffSwitch.isChecked = json.optInt("screen_off_only", 1) == 1
            binding.chargingSwitch.isChecked = json.optInt("charging_only", 0) == 1
            binding.deviceIdleSwitch.isChecked = json.optInt("device_idle_only", 0) == 1
            binding.minBatterySlider.value = json.optInt("min_battery", 25).coerceIn(0, 100).toFloat()
            binding.notificationSwitch.isChecked = json.optInt("notify_on_complete", 1) == 1
            binding.largeFileSlider.value = json.optInt("max_file_mb", 256).coerceIn(16, 2048).toFloat()
            binding.fragmentDaysSlider.value = json.optInt("fragment_days", 7).coerceIn(0, 30).toFloat()
            loadingConfig = false
            binding.intervalText.text = "每 ${binding.intervalSlider.value.toInt()} 小时"
            binding.dailyHourText.text = String.format("每日 %02d:00", binding.dailyHourSlider.value.toInt())
            binding.minBatteryText.text = "最低电量 ${binding.minBatterySlider.value.toInt()}%"
            binding.largeFileText.text = "单文件上限 ${binding.largeFileSlider.value.toInt()} MB"
            binding.fragmentDaysText.text = fragmentRetentionLabel(binding.fragmentDaysSlider.value.toInt())
            updatePlanPreview()
        }
    }

    private fun saveSchedulerConfig() {
        val service = profileService ?: return
        val interval = binding.intervalSlider.value.toInt()
        val json = JSONObject()
            .put("enabled", flag(binding.scheduleSwitch.isChecked))
            .put("schedule_cache_enabled", flag(binding.cacheScheduleSwitch.isChecked))
            .put("schedule_cache_hours", interval)
            .put("schedule_empty_enabled", flag(binding.emptyScheduleSwitch.isChecked))
            .put("schedule_empty_hours", interval)
            .put("schedule_rules_enabled", flag(binding.rulesScheduleSwitch.isChecked))
            .put("schedule_rules_hours", interval)
            .put("schedule_fragment_enabled", flag(binding.fragmentScheduleSwitch.isChecked))
            .put("schedule_fragment_hours", interval)
            .put("schedule_deep_enabled", flag(binding.deepScheduleSwitch.isChecked))
            .put("schedule_deep_hours", 168)
            .put("daily_schedule_enabled", flag(binding.dailySwitch.isChecked))
            .put("daily_schedule_hour", binding.dailyHourSlider.value.toInt())
            .put("daily_schedule_minute", 0)
            .put("screen_off_only", flag(binding.screenOffSwitch.isChecked))
            .put("charging_only", flag(binding.chargingSwitch.isChecked))
            .put("device_idle_only", flag(binding.deviceIdleSwitch.isChecked))
            .put("min_battery", binding.minBatterySlider.value.toInt())
            .put("notify_on_complete", flag(binding.notificationSwitch.isChecked))
            .put("max_file_mb", binding.largeFileSlider.value.toInt())
            .put("fragment_days", binding.fragmentDaysSlider.value.toInt())

        binding.planStateText.text = "正在写入模块调度配置…"
        lifecycleScope.launch {
            val raw = runCatching { withContext(Dispatchers.IO) { service.saveSchedulerConfig(json.toString()) } }
            raw.onSuccess {
                val result = JSONObject(it)
                binding.planStateText.text = if (result.optBoolean("success")) {
                    "已写入模块配置，后台调度器将在下一次轮询时直接使用。"
                } else {
                    "保存失败：${result.optString("error", "未知错误")}"
                }
            }.onFailure { binding.planStateText.text = "保存失败：${it.message}" }
            refreshModuleState()
        }
    }

    private fun updatePlanPreview() {
        if (loadingConfig) return
        binding.planStateText.text = if (!binding.scheduleSwitch.isChecked) {
            "自动清理总开关已关闭。"
        } else if (binding.dailySwitch.isChecked) {
            buildString {
                append(String.format("每日 %02d:00", binding.dailyHourSlider.value.toInt()))
                if (binding.screenOffSwitch.isChecked) append(" · 等待息屏")
                if (binding.chargingSwitch.isChecked) append(" · 仅充电")
                if (binding.deviceIdleSwitch.isChecked) append(" · 仅空闲")
            }
        } else {
            buildString {
                append("每 ${binding.intervalSlider.value.toInt()} 小时")
                if (binding.screenOffSwitch.isChecked) append(" · 等待息屏")
                if (binding.chargingSwitch.isChecked) append(" · 仅充电")
                if (binding.deviceIdleSwitch.isChecked) append(" · 仅空闲")
            }
        }
    }

    private fun fragmentRetentionLabel(days: Int): String =
        if (days <= 0) "碎片立即清理" else "碎片保留 $days 天"

    private fun flag(value: Boolean): Int = if (value) 1 else 0

    private fun updateStorage() {
        runCatching {
            val stat = StatFs(dataDir.absolutePath)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            val used = (total - free).coerceAtLeast(0L)
            val percent = if (total > 0L) ((used * 100L) / total).toInt().coerceIn(0, 100) else 0
            binding.storageRing.setProgressCompat(percent, true)
            binding.freeSpaceText.text = Formatter.formatFileSize(this, free)
            binding.storageDetailText.text = "已用 ${Formatter.formatFileSize(this, used)} · 共 ${Formatter.formatFileSize(this, total)}"
        }.onFailure {
            binding.freeSpaceText.text = "--"
            binding.storageDetailText.text = "存储状态读取失败"
        }
    }

    private fun refreshSavedReport() {
        val report = preferences.getString("last_report_text", null) ?: "暂无清理记录"
        binding.recentTaskText.text = report
        binding.recordSummaryText.text = report
        val bytes = preferences.getLong("last_clean_bytes", 0L)
        binding.lastFreedText.text = if (bytes > 0L) Formatter.formatFileSize(this, bytes) else "--"
    }

    private fun refreshWhitelist() {
        val count = preferences.getStringSet("package_whitelist", emptySet()).orEmpty().size
        binding.whitelistText.text = "白名单：$count 个应用"
    }

    private fun connectService() {
        renderServiceState("正在连接 Root 服务", false)
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
            serviceBound = true
        }.onFailure {
            renderServiceState(it.message ?: "Root 服务启动失败", false)
        }
    }

    private fun readServiceStatus() {
        val service = profileService ?: return
        lifecycleScope.launch {
            val raw = runCatching { withContext(Dispatchers.IO) { service.ping() } }.getOrNull()
            val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            val root = json?.optBoolean("root") == true
            val module = json?.optBoolean("module") == true
            val cleaner = json?.optBoolean("cleaner") == true
            val scheduler = json?.optBoolean("scheduler") == true
            val rules = json?.optBoolean("deepRules") == true
            val text = when {
                !root -> "服务已连接，但未获得完整 Root"
                !module -> "Root 已连接 · 未检测到白泽模块"
                !cleaner -> "模块已连接 · 一键清理引擎缺失"
                !scheduler -> "清理引擎已连接 · 调度器缺失"
                !rules -> "自动清理已就绪 · 深度规则库缺失"
                else -> "Root、自动清理、定时任务与规则库均已就绪"
            }
            val ready = root && module && cleaner && scheduler && rules
            renderServiceState(text, ready)
            renderTaskButtons(taskRunning)
        }
    }

    private fun refreshModuleState() {
        val service = profileService ?: return
        lifecycleScope.launch {
            val raw = runCatching { withContext(Dispatchers.IO) { service.getModuleState() } }.getOrNull() ?: return@launch
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@launch
            val latestBytes = json.optJSONObject("latest")?.optLong("bytes", 0L) ?: 0L
            if (latestBytes > 0L) {
                preferences.edit().putLong("last_clean_bytes", latestBytes).apply()
                binding.lastFreedText.text = Formatter.formatFileSize(this@DashboardActivity, latestBytes)
            }
            val scheduler = json.optJSONObject("scheduler") ?: JSONObject()
            val state = scheduler.optString("state", "waiting")
            val reason = scheduler.optString("reason", "等待调度器首次轮询")
            binding.schedulerStatusText.text = when (state) {
                "running" -> "定时任务正在执行 · $reason"
                "completed" -> "最近定时任务已完成 · $reason"
                "failed" -> "定时任务失败 · $reason"
                "disabled" -> "自动清理已关闭"
                "missed" -> "今日任务已错过 · $reason"
                else -> reason
            }
            binding.recordSchedulerText.text = binding.schedulerStatusText.text
        }
    }

    private fun renderServiceState(text: String, ready: Boolean) {
        binding.serviceStatusText.text = text
        binding.settingsStatusText.text = text
        binding.serviceDot.alpha = if (ready) 1f else 0.35f
    }

    override fun onDestroy() {
        taskPollJob?.cancel()
        if (serviceBound) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }
}
