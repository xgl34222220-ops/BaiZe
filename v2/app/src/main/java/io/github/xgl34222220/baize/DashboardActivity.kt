package io.github.xgl34222220.baize

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.StatFs
import android.text.InputType
import android.text.format.Formatter
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge()
        pendingSmartClean = intent.getBooleanExtra(EXTRA_RUN_SMART_CLEAN, false)

        binding.versionText.text = "v${BuildConfig.VERSION_NAME}"
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
            if (binding.notificationSwitch.isChecked) requestNotificationPermissionIfNeeded()
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
        profileService ?: run {
            binding.taskStatusText.text = "正在连接 Root 服务…"
            connectService()
            return
        }
        if (taskRunning) return
        if (binding.notificationSwitch.isChecked) requestNotificationPermissionIfNeeded()
        // Manual cleaning must respect the saved user configuration. Older builds rewrote the
        // whole scheduler file here, silently resetting independent intervals and advanced rules.
        runModuleTask("clean")
    }

    private fun smartSchedulerPayload(): JSONObject {
        return JSONObject()
            .put("enabled", flag(binding.scheduleSwitch.isChecked))
            .put("schedule_cache_enabled", flag(binding.cacheScheduleControl.enabledForSchedule))
            .put("schedule_cache_hours", binding.cacheScheduleControl.hours)
            .put("schedule_empty_enabled", flag(binding.emptyScheduleControl.enabledForSchedule))
            .put("schedule_empty_hours", binding.emptyScheduleControl.hours)
            .put("schedule_rules_enabled", flag(binding.rulesScheduleControl.enabledForSchedule))
            .put("schedule_rules_hours", binding.rulesScheduleControl.hours)
            .put("schedule_fragment_enabled", flag(binding.fragmentScheduleControl.enabledForSchedule))
            .put("schedule_fragment_hours", binding.fragmentScheduleControl.hours)
            .put("schedule_deep_enabled", flag(binding.deepScheduleControl.enabledForSchedule))
            .put("schedule_deep_hours", binding.deepScheduleControl.hours)
            .put("daily_schedule_enabled", flag(binding.dailySwitch.isChecked))
            .put("daily_schedule_hour", binding.dailyHourSlider.value.toInt())
            .put("daily_schedule_minute", binding.dailyMinuteSlider.value.toInt())
            .put("daily_grace_minutes", binding.dailyGraceSlider.value.toInt())
            .put("screen_off_only", flag(binding.screenOffSwitch.isChecked))
            .put("charging_only", flag(binding.chargingSwitch.isChecked))
            .put("device_idle_only", flag(binding.deviceIdleSwitch.isChecked))
            .put("min_battery", binding.minBatterySlider.value.toInt())
            .put("notify_on_complete", flag(binding.notificationSwitch.isChecked))
            .put("notify_zero_result", flag(binding.notifyZeroSwitch.isChecked))
            .put("max_file_mb", binding.largeFileSlider.value.toInt())
            .put("fragment_days", binding.fragmentDaysSlider.value.toInt())
            .put("app_cache_days", binding.cacheDaysSlider.value.toInt())
            .put("external_cache_days", binding.cacheDaysSlider.value.toInt())
            .put("system_logs_days", binding.logDaysSlider.value.toInt())
            .put("oem_logs_days", binding.logDaysSlider.value.toInt())
            .put("installer_temp_days", binding.installerDaysSlider.value.toInt())
            .put("root_shell_days", binding.rootShellDaysSlider.value.toInt())
            .put("clean_app_cache", flag(binding.cleanInternalCacheSwitch.isChecked))
            .put("clean_external_cache", flag(binding.cleanExternalCacheSwitch.isChecked))
            .put("clean_app_rules", flag(binding.cleanAppRulesSwitch.isChecked))
            .put("clean_system_logs", flag(binding.cleanSystemLogsSwitch.isChecked))
            .put("clean_oem_logs", flag(binding.cleanOemLogsSwitch.isChecked))
            .put("clean_hidden_junk", flag(binding.cleanHiddenJunkSwitch.isChecked))
            .put("clean_empty_files", flag(binding.cleanEmptyFilesSwitch.isChecked))
            .put("clean_empty_dirs", flag(binding.cleanEmptyDirsSwitch.isChecked))
            .put("clean_root_shells", flag(binding.cleanRootShellsSwitch.isChecked))
            .put("clean_fragments", flag(binding.cleanFragmentsSwitch.isChecked))
            .put("clean_installer_temp", flag(binding.cleanInstallerTempSwitch.isChecked))
            .put("clean_custom_rules", flag(binding.cleanCustomRulesSwitch.isChecked))
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
        binding.cacheScheduleControl.configure("应用缓存", 1)
        binding.emptyScheduleControl.configure("空文件与空目录", 1)
        binding.rulesScheduleControl.configure("规则垃圾与日志", 6)
        binding.fragmentScheduleControl.configure("残留碎片", 12)
        binding.deepScheduleControl.configure("深度安全项", 168)
        val scheduleControls = listOf(
            binding.cacheScheduleControl,
            binding.emptyScheduleControl,
            binding.rulesScheduleControl,
            binding.fragmentScheduleControl,
            binding.deepScheduleControl
        )
        scheduleControls.forEach { control -> control.setOnScheduleChangedListener { updatePlanPreview() } }

        binding.intervalSlider.addOnChangeListener { _, value, fromUser ->
            val hours = value.toInt()
            binding.intervalText.text = "同步全部周期 · ${formatHours(hours)}（点此精确输入）"
            if (fromUser && !loadingConfig) {
                scheduleControls.forEach { it.hours = hours }
                updatePlanPreview()
            }
        }
        binding.intervalText.setOnClickListener {
            showHoursInputDialog(binding.intervalSlider.value.toInt()) { hours ->
                loadingConfig = true
                binding.intervalSlider.value = hours.toFloat()
                scheduleControls.forEach { it.hours = hours }
                loadingConfig = false
                binding.intervalText.text = "同步全部周期 · ${formatHours(hours)}（点此精确输入）"
                updatePlanPreview()
            }
        }
        val updateDailyLabels = {
            binding.dailyHourText.text = String.format(
                "每天 %02d:%02d 执行",
                binding.dailyHourSlider.value.toInt(),
                binding.dailyMinuteSlider.value.toInt()
            )
            binding.dailyMinuteText.text = "分钟 · ${binding.dailyMinuteSlider.value.toInt()}"
            binding.dailyGraceText.text = "条件补做窗口 · ${binding.dailyGraceSlider.value.toInt()} 分钟"
        }
        binding.dailyHourSlider.addOnChangeListener { _, _, _ ->
            updateDailyLabels()
            updatePlanPreview()
        }
        binding.dailyMinuteSlider.addOnChangeListener { _, _, _ ->
            updateDailyLabels()
            updatePlanPreview()
        }
        binding.dailyGraceSlider.addOnChangeListener { _, _, _ ->
            updateDailyLabels()
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
        binding.notificationSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && !loadingConfig) requestNotificationPermissionIfNeeded()
        }

        binding.scheduleSwitch.setOnCheckedChangeListener { _, _ ->
            if (!loadingConfig) updatePlanPreview()
        }
        val previewSwitches = listOf(
            binding.dailySwitch,
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
        if (binding.notificationSwitch.isChecked &&
            (!success || cancelled || latestBytes > 0L || binding.notifyZeroSwitch.isChecked)
        ) {
            val latest = json.optJSONObject("latest") ?: JSONObject()
            val regularFiles = latest.optLong("regular_files", latest.optLong("files", 0L)).coerceAtLeast(0L)
            val emptyFiles = latest.optLong("empty_files", 0L).coerceAtLeast(0L)
            val emptyDirs = latest.optLong("empty_dirs", 0L).coerceAtLeast(0L)
            val fragments = latest.optLong("fragment_files", 0L).coerceAtLeast(0L)
            val title = when {
                cancelled -> "白泽清理已停止"
                success -> "白泽清理完成"
                else -> "白泽任务失败"
            }
            NativeNotifier.showTaskResult(
                this,
                title,
                "释放 ${Formatter.formatFileSize(this, latestBytes)} · 文件 $regularFiles 个",
                "空文件 $emptyFiles 个 · 空目录 $emptyDirs 个 · 碎片 $fragments 个 · ${formatElapsed(elapsed / 1000L)}"
            )
        }
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
            binding.dailyMinuteSlider.value = json.optInt("daily_schedule_minute", 30).coerceIn(0, 59).toFloat()
            binding.dailyGraceSlider.value = json.optInt("daily_grace_minutes", 240).coerceIn(15, 720).toFloat()
            binding.cacheScheduleControl.enabledForSchedule = json.optInt("schedule_cache_enabled", 1) == 1
            binding.cacheScheduleControl.hours = json.optInt("schedule_cache_hours", 1)
            binding.emptyScheduleControl.enabledForSchedule = json.optInt("schedule_empty_enabled", 1) == 1
            binding.emptyScheduleControl.hours = json.optInt("schedule_empty_hours", 1)
            binding.rulesScheduleControl.enabledForSchedule = json.optInt("schedule_rules_enabled", 1) == 1
            binding.rulesScheduleControl.hours = json.optInt("schedule_rules_hours", 6)
            binding.fragmentScheduleControl.enabledForSchedule = json.optInt("schedule_fragment_enabled", 1) == 1
            binding.fragmentScheduleControl.hours = json.optInt("schedule_fragment_hours", 12)
            binding.deepScheduleControl.enabledForSchedule = json.optInt("schedule_deep_enabled", 0) == 1
            binding.deepScheduleControl.hours = json.optInt("schedule_deep_hours", 168)
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
            binding.intervalText.text = "同步全部周期 · ${formatHours(binding.intervalSlider.value.toInt())}（点此精确输入）"
            binding.dailyHourText.text = String.format(
                "每天 %02d:%02d 执行",
                binding.dailyHourSlider.value.toInt(),
                binding.dailyMinuteSlider.value.toInt()
            )
            binding.dailyMinuteText.text = "分钟 · ${binding.dailyMinuteSlider.value.toInt()}"
            binding.dailyGraceText.text = "条件补做窗口 · ${binding.dailyGraceSlider.value.toInt()} 分钟"
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
                append(
                    String.format(
                        "每天 %02d:%02d",
                        binding.dailyHourSlider.value.toInt(),
                        binding.dailyMinuteSlider.value.toInt()
                    )
                )
                if (binding.screenOffSwitch.isChecked) append(" · 等待息屏")
                if (binding.chargingSwitch.isChecked) append(" · 仅充电")
                if (binding.deviceIdleSwitch.isChecked) append(" · 仅空闲")
            }
        } else {
            buildString {
                val enabled = listOf(
                    binding.cacheScheduleControl,
                    binding.emptyScheduleControl,
                    binding.rulesScheduleControl,
                    binding.fragmentScheduleControl,
                    binding.deepScheduleControl
                ).filter { it.enabledForSchedule }
                append("已启用 ${enabled.size} 类独立任务")
                if (enabled.isNotEmpty()) append(" · 最短 ${enabled.minOf { it.hours }} 小时")
                if (binding.screenOffSwitch.isChecked) append(" · 等待息屏")
                if (binding.chargingSwitch.isChecked) append(" · 仅充电")
                if (binding.deviceIdleSwitch.isChecked) append(" · 仅空闲")
            }
        }
    }

    private fun showHoursInputDialog(current: Int, onSelected: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.coerceIn(1, 720).toString())
            hint = "1 - 720"
            setSelectAllOnFocus(true)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("精确设置执行周期")
            .setMessage("可输入 1 到 720 小时。例如 168 小时为 7 天，720 小时为 30 天。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val hours = input.text?.toString()?.trim()?.toIntOrNull()
                if (hours == null || hours !in 1..720) {
                    input.error = "请输入 1 到 720 之间的整数"
                    return@setOnClickListener
                }
                onSelected(hours)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun formatHours(hours: Int): String {
        val safe = hours.coerceIn(1, 720)
        return if (safe >= 24 && safe % 24 == 0) {
            "$safe 小时 / ${safe / 24} 天"
        } else {
            "$safe 小时"
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

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.pageHost.setPadding(0, bars.top, 0, 0)
            val params = binding.bottomNavigation.layoutParams as android.widget.FrameLayout.LayoutParams
            params.bottomMargin = bars.bottom + dp(12)
            binding.bottomNavigation.layoutParams = params
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun flag(value: Boolean): Int = if (value) 1 else 0

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2101)
        }
    }

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
                withContext(Dispatchers.IO) { service.getTaskHistory(30) }
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
        val lifetimeRuns = json.optLong("lifetimeRuns", json.optLong("cleanedRuns", 0L)).coerceAtLeast(0L)
        val lifetimeReleased = json.optLong("lifetimeReleased", json.optLong("totalReleased", 0L)).coerceAtLeast(0L)
        binding.historyList.removeAllViews()
        binding.historyCountText.text = "$lifetimeRuns 次"
        binding.historyReleasedText.text = Formatter.formatFileSize(this, lifetimeReleased)
        val regularFiles = json.optLong("lifetimeFiles", 0L).coerceAtLeast(0L)
        val emptyFiles = json.optLong("lifetimeEmptyFiles", 0L).coerceAtLeast(0L)
        val emptyDirs = json.optLong("lifetimeEmptyDirs", 0L).coerceAtLeast(0L)
        val fragments = json.optLong("lifetimeFragments", 0L).coerceAtLeast(0L)
        val elapsed = json.optLong("lifetimeElapsed", 0L).coerceAtLeast(0L)
        binding.historyLifetimeDetailText.text =
            "文件 $regularFiles · 空文件 $emptyFiles · 空目录 $emptyDirs · 碎片 $fragments · ${formatElapsed(elapsed)}"

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
        "smart-clean" -> "原生智能清理"
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

    private fun formatElapsed(seconds: Long): String = when {
        seconds >= 3600L -> "耗时 ${seconds / 3600}小时${(seconds % 3600) / 60}分"
        seconds >= 60L -> "耗时 ${seconds / 60}分${seconds % 60}秒"
        else -> "耗时 ${seconds}秒"
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
