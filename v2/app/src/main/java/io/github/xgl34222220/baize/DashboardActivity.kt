package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.StatFs
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.databinding.ActivityDashboardBinding
import io.github.xgl34222220.baize.databinding.ItemTaskHistoryBinding
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
    private var pendingSmartClean = false
    private var historyLoading = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            profileService = IProfileRootService.Stub.asInterface(binder)
            serviceBound = true
            readServiceStatus()
            loadSchedulerConfig()
            refreshModuleState()
            refreshWhitelist()
            refreshTaskHistory()
            consumePendingSmartClean()
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
        pendingSmartClean = intent.getBooleanExtra(EXTRA_RUN_SMART_CLEAN, false)

        binding.versionText.text = "Alpha 20"
        setupNavigation()
        setupActions()
        setupSettings()
        setupThemePicker()
        updateStorage()
        refreshSavedReport()
        connectService()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_RUN_SMART_CLEAN, false)) {
            pendingSmartClean = true
            consumePendingSmartClean()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStorage()
        refreshSavedReport()
        refreshWhitelist()
        refreshTaskHistory()
        renderThemeSummary()
        if (profileService != null) {
            readServiceStatus()
            refreshModuleState()
        }
    }

    private fun consumePendingSmartClean() {
        if (!pendingSmartClean || profileService == null || taskRunning) return
        pendingSmartClean = false
        binding.bottomNavigation.selectedItemId = R.id.nav_home
        runSmartClean()
    }

    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    show(binding.homePage)
                    true
                }
                R.id.nav_plan -> {
                    show(binding.planPage)
                    true
                }
                R.id.nav_records -> {
                    show(binding.recordsPage)
                    refreshTaskHistory()
                    true
                }
                R.id.nav_settings -> {
                    // Keep settings in the existing DashboardActivity: no second Activity,
                    // no duplicate RootService binding and no extra glass lifecycle.
                    showSettingsMenu()
                    true
                }
                else -> false
            }
        }
        binding.bottomNavigation.selectedItemId = R.id.nav_home
    }

    private fun show(page: View) {
        val pages = listOf(binding.homePage, binding.planPage, binding.recordsPage, binding.settingsPage)
        pages.forEach { candidate ->
            candidate.animate().cancel()
            candidate.visibility = if (candidate === page) View.VISIBLE else View.GONE
        }
        page.alpha = 0f
        page.translationY = dp(6).toFloat()
        page.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(190L)
            .start()
        page.post { page.scrollTo(0, 0) }
    }

    private fun setupActions() {
        binding.cleanNowButton.setOnClickListener { runSmartClean() }
        binding.stopTaskButton.setOnClickListener {
            profileService?.cancelCurrentTask()
            binding.taskStatusText.text = "正在安全停止当前任务…"
        }
        binding.deepToolButton.setOnClickListener {
            confirmRiskAction(
                title = "开始深度清理？",
                message = "深度清理会扫描 OEM 调试日志、自定义规则和较高风险候选项。白名单、挂载点、软链接和单文件保护仍然生效。",
                confirmText = "继续扫描"
            ) { openProfile("deep") }
        }
        binding.corpsesToolButton.setOnClickListener {
            confirmRiskAction(
                title = "扫描卸载残留？",
                message = "将检查 Android/data、obb、media 和应用私有目录中的无主数据。进入后仍会先展示候选项，不会直接删除。",
                confirmText = "继续扫描"
            ) { openProfile("corpses") }
        }
        binding.advancedAuditButton.setOnClickListener {
            startActivity(Intent(this, CleanCenterActivity::class.java))
        }
        binding.reconnectButton.setOnClickListener {
            if (serviceBound) runCatching { RootService.unbind(connection) }
            profileService = null
            serviceBound = false
            connectService()
        }
        binding.manageWhitelistButton.setOnClickListener {
            startActivity(Intent(this, WhitelistActivity::class.java))
        }
        binding.saveProtectionButton.setOnClickListener {
            saveSettingsPatch(
                notification = binding.notificationSwitch.isChecked,
                notifyZero = binding.notifyZeroSwitch.isChecked,
                maxFileMb = binding.largeFileSlider.value.toInt()
            )
        }
        binding.crashReportButton.setOnClickListener { showCrashDialog() }
        binding.savePlanButton.setOnClickListener { saveSchedulerConfig() }
        binding.refreshRecordsButton.setOnClickListener {
            refreshModuleState()
            refreshTaskHistory()
        }
        binding.clearHistoryButton.setOnClickListener { confirmClearHistory() }
    }

    private fun openProfile(profile: String) {
        startActivity(Intent(this, ProfileActivity::class.java).putExtra(ProfileActivity.EXTRA_PROFILE, profile))
    }

    private fun confirmRiskAction(
        title: String,
        message: String,
        confirmText: String,
        action: () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton(confirmText) { _, _ -> action() }
            .show()
    }

    private fun runSmartClean() {
        val service = profileService ?: run {
            binding.taskStatusText.text = "正在连接 Root 服务…"
            connectService()
            return
        }
        if (taskRunning) return
        binding.cleanNowButton.isEnabled = false
        binding.taskStatusText.text = "正在启用智能安全清理范围…"
        lifecycleScope.launch {
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    service.saveSchedulerConfig(smartSchedulerPayload().toString())
                }
            }
            if (saved.isFailure || !runCatching { JSONObject(saved.getOrThrow()).optBoolean("success") }.getOrDefault(false)) {
                binding.cleanNowButton.isEnabled = true
                binding.taskStatusText.text = "智能清理配置写入失败，请重新连接 Root 服务"
                return@launch
            }
            runModuleTask("clean")
        }
    }

    private fun smartSchedulerPayload(): JSONObject {
        val interval = binding.intervalSlider.value.toInt().coerceIn(1, 720)
        return JSONObject()
            .put("enabled", flag(binding.scheduleSwitch.isChecked))
            .put("schedule_cache_enabled", 1)
            .put("schedule_cache_hours", interval)
            .put("schedule_empty_enabled", 1)
            .put("schedule_empty_hours", interval)
            .put("schedule_rules_enabled", 1)
            .put("schedule_rules_hours", interval)
            .put("schedule_fragment_enabled", 1)
            .put("schedule_fragment_hours", interval)
            .put("schedule_deep_enabled", 0)
            .put("schedule_deep_hours", 168)
            .put("daily_schedule_enabled", 0)
            .put("daily_schedule_hour", 3)
            .put("daily_schedule_minute", 0)
            .put("screen_off_only", 1)
            .put("charging_only", 0)
            .put("device_idle_only", 0)
            .put("min_battery", 20)
            .put("notify_on_complete", flag(binding.notificationSwitch.isChecked))
            .put("notify_zero_result", flag(binding.notifyZeroSwitch.isChecked))
            .put("max_file_mb", binding.largeFileSlider.value.toInt())
            .put("clean_app_cache", 1)
            .put("clean_external_cache", 1)
            .put("clean_app_rules", 1)
            .put("clean_system_logs", 1)
            .put("clean_oem_logs", 0)
            .put("clean_hidden_junk", 1)
            .put("clean_empty_files", 1)
            .put("clean_empty_dirs", 1)
            .put("clean_root_shells", 1)
            .put("clean_fragments", 1)
            .put("clean_installer_temp", 1)
            .put("clean_custom_rules", 0)
            .put("deep_high_risk_enabled", 0)
            .put("app_cache_days", 0)
            .put("external_cache_days", 0)
            .put("system_logs_days", 7)
            .put("oem_logs_days", 14)
            .put("hidden_junk_days", 0)
            .put("empty_file_days", 0)
            .put("fragment_days", 7)
            .put("installer_temp_days", 7)
            .put("root_shell_days", 14)
    }

    private fun showSettingsMenu() {
        // Do not animate or construct a dialog here. Some OEM ROMs validate hidden Material sliders
        // only when the page becomes visible; normalize every discrete value first, then switch pages
        // synchronously so a bad legacy config cannot crash the render pass.
        runCatching { normalizeDiscreteSliders() }
        runCatching { refreshWhitelist() }
        runCatching { renderThemeSummary() }

        val pages = listOf(binding.homePage, binding.planPage, binding.recordsPage, binding.settingsPage)
        pages.forEach { candidate ->
            candidate.animate().cancel()
            candidate.alpha = 1f
            candidate.translationY = 0f
            candidate.visibility = if (candidate === binding.settingsPage) View.VISIBLE else View.GONE
        }
        binding.settingsPage.scrollTo(0, 0)
    }

    private fun normalizeDiscreteSliders() {
        binding.minBatterySlider.value = snapToStep(binding.minBatterySlider.value.toInt(), 0, 100, 5)
        binding.largeFileSlider.value = snapToStep(binding.largeFileSlider.value.toInt(), 16, 2048, 16)
    }

    private fun setupThemePicker() {
        renderThemeSummary()
        binding.themeButton.setOnClickListener {
            startActivity(Intent(this, ThemeSettingsActivity::class.java))
        }
    }

    private fun renderThemeSummary() {
        binding.themeSummaryText.text = ThemeManager.themeSummary(this)
    }

    private fun showLargeFileDialog(current: Int) {
        val values = intArrayOf(64, 128, 256, 512, 1024, 2048)
        val labels = values.map { "$it MB" }.toTypedArray()
        val checked = values.indices.minByOrNull { kotlin.math.abs(values[it] - current) } ?: 2
        AlertDialog.Builder(this)
            .setTitle("单文件保护上限")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val value = values[which]
                binding.largeFileSlider.value = value.toFloat()
                binding.largeFileText.text = "单文件上限 $value MB"
                saveSettingsPatch(maxFileMb = value)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showWhitelistDialog(packageCount: Int, pathCount: Int) {
        AlertDialog.Builder(this)
            .setTitle("白名单保护")
            .setMessage("当前保护 $packageCount 个应用、$pathCount 条路径。清空后只会重新参与后续扫描，不会立即执行清理。")
            .setNegativeButton("保留", null)
            .setPositiveButton("清空白名单") { _, _ ->
                preferences.edit()
                    .remove("package_whitelist")
                    .remove("path_whitelist")
                    .apply()
                Toast.makeText(this, "白名单已清空", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showCrashDialog() {
        val report = CrashRecorder.read(this)
        AlertDialog.Builder(this)
            .setTitle("崩溃诊断")
            .setMessage(report ?: "暂无 App 崩溃记录")
            .setNegativeButton("关闭", null)
            .setPositiveButton("清除记录") { _, _ -> CrashRecorder.clear(this) }
            .show()
    }

    private fun reconnectProfileService() {
        if (serviceBound) runCatching { RootService.unbind(connection) }
        profileService = null
        serviceBound = false
        connectService()
        Toast.makeText(this, "正在重新连接 Root 服务", Toast.LENGTH_SHORT).show()
    }

    private fun saveSettingsPatch(notification: Boolean? = null, notifyZero: Boolean? = null, maxFileMb: Int? = null) {
        val rootService = profileService ?: run {
            Toast.makeText(this, "Root 服务尚未连接", Toast.LENGTH_SHORT).show()
            return
        }
        val payload = JSONObject()
        notification?.let { payload.put("notify_on_complete", if (it) 1 else 0) }
        notifyZero?.let { payload.put("notify_zero_result", if (it) 1 else 0) }
        maxFileMb?.let { payload.put("max_file_mb", it.coerceIn(16, 2048)) }
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { rootService.saveSchedulerConfig(payload.toString()) }
            }
            val message = result.fold(
                onSuccess = {
                    val json = runCatching { JSONObject(it) }.getOrDefault(JSONObject())
                    if (json.optBoolean("success")) "设置已保存" else "保存失败：${json.optString("error", "未知错误")}"
                },
                onFailure = { "保存失败：${it.message ?: it.javaClass.simpleName}" }
            )
            Toast.makeText(this@DashboardActivity, message, Toast.LENGTH_SHORT).show()
        }
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
        binding.cacheDaysSlider.addOnChangeListener { _, value, _ ->
            binding.cacheDaysText.text = retentionLabel("缓存", value.toInt())
        }
        binding.logDaysSlider.addOnChangeListener { _, value, _ ->
            binding.logDaysText.text = retentionLabel("日志", value.toInt())
        }
        binding.installerDaysSlider.addOnChangeListener { _, value, _ ->
            binding.installerDaysText.text = "安装临时文件保留 ${value.toInt()} 天"
        }
        binding.rootShellDaysSlider.addOnChangeListener { _, value, _ ->
            binding.rootShellDaysText.text = "根目录空壳保留 ${value.toInt()} 天"
        }

        binding.scheduleSwitch.setOnCheckedChangeListener { _, _ ->
            if (!loadingConfig) updatePlanPreview()
        }
        val previewSwitches = listOf(
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
        preferences.edit()
            .putString("last_report_text", summary)
            .putLong("last_clean_bytes", latestBytes.coerceAtLeast(0L))
            .apply()
        binding.lastFreedText.text = Formatter.formatFileSize(this, latestBytes.coerceAtLeast(0L))
        refreshTaskHistory()
    }

    private fun renderTaskButtons(running: Boolean) {
        binding.cleanNowButton.isEnabled = !running && profileService != null
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
            binding.minBatterySlider.value = snapToStep(json.optInt("min_battery", 25), 0, 100, 5)
            binding.notificationSwitch.isChecked = json.optInt("notify_on_complete", 1) == 1
            binding.notifyZeroSwitch.isChecked = json.optInt("notify_zero_result", 0) == 1
            binding.largeFileSlider.value = snapToStep(json.optInt("max_file_mb", 256), 16, 2048, 16)
            binding.fragmentDaysSlider.value = json.optInt("fragment_days", 7).coerceIn(0, 30).toFloat()
            binding.cacheDaysSlider.value = json.optInt("app_cache_days", 0).coerceIn(0, 30).toFloat()
            binding.logDaysSlider.value = json.optInt("system_logs_days", 7).coerceIn(0, 30).toFloat()
            binding.installerDaysSlider.value = json.optInt("installer_temp_days", 7).coerceIn(1, 30).toFloat()
            binding.rootShellDaysSlider.value = json.optInt("root_shell_days", 14).coerceIn(1, 90).toFloat()
            binding.cleanInternalCacheSwitch.isChecked = json.optInt("clean_app_cache", 1) == 1
            binding.cleanExternalCacheSwitch.isChecked = json.optInt("clean_external_cache", 1) == 1
            binding.cleanAppRulesSwitch.isChecked = json.optInt("clean_app_rules", 1) == 1
            binding.cleanSystemLogsSwitch.isChecked = json.optInt("clean_system_logs", 1) == 1
            binding.cleanOemLogsSwitch.isChecked = json.optInt("clean_oem_logs", 0) == 1
            binding.cleanHiddenJunkSwitch.isChecked = json.optInt("clean_hidden_junk", 1) == 1
            binding.cleanEmptyFilesSwitch.isChecked = json.optInt("clean_empty_files", 1) == 1
            binding.cleanEmptyDirsSwitch.isChecked = json.optInt("clean_empty_dirs", 1) == 1
            binding.cleanRootShellsSwitch.isChecked = json.optInt("clean_root_shells", 1) == 1
            binding.cleanFragmentsSwitch.isChecked = json.optInt("clean_fragments", 1) == 1
            binding.cleanInstallerTempSwitch.isChecked = json.optInt("clean_installer_temp", 0) == 1
            binding.cleanCustomRulesSwitch.isChecked = json.optInt("clean_custom_rules", 0) == 1
            loadingConfig = false
            binding.intervalText.text = "每 ${binding.intervalSlider.value.toInt()} 小时"
            binding.dailyHourText.text = String.format("每日 %02d:00", binding.dailyHourSlider.value.toInt())
            binding.minBatteryText.text = "最低电量 ${binding.minBatterySlider.value.toInt()}%"
            binding.largeFileText.text = "单文件上限 ${binding.largeFileSlider.value.toInt()} MB"
            binding.fragmentDaysText.text = fragmentRetentionLabel(binding.fragmentDaysSlider.value.toInt())
            binding.cacheDaysText.text = retentionLabel("缓存", binding.cacheDaysSlider.value.toInt())
            binding.logDaysText.text = retentionLabel("日志", binding.logDaysSlider.value.toInt())
            binding.installerDaysText.text = "安装临时文件保留 ${binding.installerDaysSlider.value.toInt()} 天"
            binding.rootShellDaysText.text = "根目录空壳保留 ${binding.rootShellDaysSlider.value.toInt()} 天"
            updatePlanPreview()
        }
    }

    private fun saveSchedulerConfig() {
        val service = profileService ?: return
        val json = smartSchedulerPayload()

        binding.planStateText.text = "正在写入模块调度配置…"
        lifecycleScope.launch {
            val raw = runCatching { withContext(Dispatchers.IO) { service.saveSchedulerConfig(json.toString()) } }
            raw.onSuccess {
                val result = JSONObject(it)
                binding.planStateText.text = if (result.optBoolean("success")) {
                    "智能自动清理已保存：安全项目全自动，危险项目只允许手动确认后执行。"
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

    private fun retentionLabel(name: String, days: Int): String =
        if (days <= 0) "$name 立即清理" else "$name 保留 $days 天"

    private fun snapToStep(value: Int, minimum: Int, maximum: Int, step: Int): Float {
        val clamped = value.coerceIn(minimum, maximum)
        val offset = clamped - minimum
        val snapped = minimum + ((offset + step / 2) / step) * step
        return snapped.coerceIn(minimum, maximum).toFloat()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

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

    private fun refreshTaskHistory() {
        val service = profileService ?: run {
            binding.historyEmptyText.visibility = View.VISIBLE
            binding.historyEmptyText.text = "等待 Root 服务连接后读取记录"
            return
        }
        if (historyLoading) return
        historyLoading = true
        binding.historyEmptyText.visibility = View.VISIBLE
        binding.historyEmptyText.text = "正在读取模块清理记录…"

        lifecycleScope.launch {
            val raw = runCatching {
                withContext(Dispatchers.IO) { service.getTaskHistory(100) }
            }.getOrNull()
            historyLoading = false
            val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (json == null || !json.optBoolean("success", false)) {
                binding.historyList.removeAllViews()
                binding.historyEmptyText.visibility = View.VISIBLE
                binding.historyEmptyText.text = "记录读取失败，请重新连接 Root 服务"
                return@launch
            }
            renderTaskHistory(json)
        }
    }

    private fun renderTaskHistory(json: JSONObject) {
        val entries = json.optJSONArray("entries")
        val count = entries?.length() ?: 0
        binding.historyList.removeAllViews()
        binding.historyCountText.text = "$count 次"
        binding.historyReleasedText.text = Formatter.formatFileSize(this, json.optLong("totalReleased", 0L))

        if (count <= 0) {
            binding.historyLastText.text = "暂无"
            binding.recordSummaryText.text = "还没有清理记录"
            binding.historyEmptyText.visibility = View.VISIBLE
            binding.historyEmptyText.text = "完成一次手动或自动清理后，记录会永久保存在模块目录中。"
            binding.taskStatusText.text = "还没有清理记录"
            binding.recentTaskText.text = "记录由模块保存，重启 App 或手机后仍会保留"
            return
        }

        binding.historyEmptyText.visibility = View.GONE
        for (index in 0 until count) {
            val item = entries?.optJSONObject(index) ?: continue
            val row = ItemTaskHistoryBinding.inflate(layoutInflater, binding.historyList, false)
            val mode = item.optString("mode")
            val cleaned = item.optBoolean("cleaned")
            val bytes = item.optLong("bytes", 0L).coerceAtLeast(0L)
            val files = item.optInt("files", 0).coerceAtLeast(0)
            val emptyDirs = item.optInt("emptyDirs", 0).coerceAtLeast(0)
            val errors = item.optInt("errors", 0).coerceAtLeast(0)
            val result = item.optString("result", if (cleaned) "清理完成" else "扫描完成")
            val trigger = historyTriggerLabel(item.optString("trigger"))

            row.historyTitle.text = historyModeTitle(mode)
            row.historySubtitle.text = "${item.optString("time")} · $trigger"
            row.historyDetail.text = "$result\n$files 个项目 · 空目录 $emptyDirs · 异常 $errors"
            row.historyBytes.text = if (cleaned) {
                Formatter.formatFileSize(this, bytes)
            } else {
                "可清理 ${Formatter.formatFileSize(this, bytes)}"
            }
            row.historyStatus.text = when {
                errors > 0 -> "有异常"
                cleaned -> "已清理"
                else -> "仅扫描"
            }
            binding.historyList.addView(row.root)
        }

        val latest = entries?.optJSONObject(0) ?: return
        val latestTime = latest.optString("time")
        val latestResult = latest.optString("result", "任务完成")
        val latestCleaned = latest.optBoolean("cleaned")
        val latestBytes = latest.optLong("bytes", 0L).coerceAtLeast(0L)
        val latestFiles = latest.optInt("files", 0).coerceAtLeast(0)
        binding.historyLastText.text = latestTime.substringAfter(' ', latestTime)
        binding.recordSummaryText.text = latestResult
        binding.taskStatusText.text = latestResult
        binding.recentTaskText.visibility = View.VISIBLE
        binding.recentTaskText.text = "$latestTime · $latestFiles 个项目"
        if (latestCleaned) binding.lastFreedText.text = Formatter.formatFileSize(this, latestBytes)
    }

    private fun confirmClearHistory() {
        val service = profileService ?: return
        AlertDialog.Builder(this)
            .setTitle("清空清理记录？")
            .setMessage("只会删除历史任务摘要，不会修改累计统计、白名单、清理规则或用户文件。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    val raw = runCatching { withContext(Dispatchers.IO) { service.clearTaskHistory() } }.getOrNull()
                    val success = raw?.let { runCatching { JSONObject(it).optBoolean("success") }.getOrDefault(false) } == true
                    if (success) {
                        preferences.edit().remove("last_report_text").remove("last_clean_bytes").apply()
                        refreshTaskHistory()
                        Toast.makeText(this@DashboardActivity, "清理记录已清空", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@DashboardActivity, "清空失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun historyModeTitle(mode: String): String = when (mode) {
        "scan" -> "智能安全扫描"
        "clean" -> "智能自动清理"
        "cache-clean" -> "应用缓存清理"
        "empty-clean" -> "空文件与空目录清理"
        "rules-clean" -> "规则垃圾清理"
        "fragment-scan" -> "残留碎片扫描"
        "fragment-clean" -> "残留碎片清理"
        "deep-scan" -> "完整深度扫描"
        "deep-clean" -> "完整深度清理"
        "corpse-scan" -> "卸载残留扫描"
        "corpse-clean" -> "卸载残留清理"
        else -> "清理任务"
    }

    private fun historyTriggerLabel(trigger: String): String = when {
        trigger.startsWith("scheduled:") -> "自动定时"
        trigger.startsWith("daily:") -> "每日定时"
        trigger == "app" -> "App 手动"
        trigger == "manual" -> "手动执行"
        trigger.isBlank() -> "历史任务"
        else -> trigger
    }

    private fun refreshSavedReport() {
        val report = preferences.getString("last_report_text", null) ?: "暂无清理记录"
        binding.recentTaskText.text = report
        binding.recordSummaryText.text = report
        val bytes = preferences.getLong("last_clean_bytes", -1L)
        binding.lastFreedText.text = if (bytes >= 0L) Formatter.formatFileSize(this, bytes) else "暂无"
    }

    private fun refreshWhitelist() {
        val rootService = profileService
        if (rootService == null) {
            binding.whitelistText.text = "白名单：等待 Root 服务连接"
            binding.manageWhitelistButton.isEnabled = false
            return
        }
        binding.manageWhitelistButton.isEnabled = true
        lifecycleScope.launch {
            val raw = runCatching {
                withContext(Dispatchers.IO) { rootService.getWhitelistPackages() }
            }.getOrNull()
            val count = raw?.let {
                runCatching { org.json.JSONArray(it).length() }.getOrDefault(0)
            } ?: 0
            binding.whitelistText.text = if (count > 0) {
                "已保护 $count 个应用 · 内部数据与外部目录均跳过"
            } else {
                "尚未添加应用白名单"
            }
        }
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
            val latest = json.optJSONObject("latest")
            if (latest != null && latest.length() > 0) {
                val latestBytes = latest.optLong("bytes", 0L).coerceAtLeast(0L)
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

    companion object {
        const val EXTRA_RUN_SMART_CLEAN = "io.github.xgl34222220.baize.RUN_SMART_CLEAN"
    }
}
