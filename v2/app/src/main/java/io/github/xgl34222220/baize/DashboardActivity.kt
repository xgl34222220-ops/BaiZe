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
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.databinding.ActivityDashboardBinding
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DashboardActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDashboardBinding
    private val preferences by lazy { getSharedPreferences("baize_v2", MODE_PRIVATE) }

    private var profileService: IProfileRootService? = null
    private var serviceBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            profileService = IProfileRootService.Stub.asInterface(binder)
            serviceBound = true
            readServiceStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            profileService = null
            serviceBound = false
            renderServiceState("Root 原生引擎已断开", false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.versionText.text = "Alpha 5"
        setupNavigation()
        setupActions()
        setupSettings()
        updateStorage()
        refreshReport()
        connectService()
    }

    override fun onResume() {
        super.onResume()
        updateStorage()
        refreshReport()
        refreshWhitelist()
        if (profileService != null) readServiceStatus()
    }

    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> show(binding.homePage)
                R.id.nav_scan -> {
                    startActivity(Intent(this, CleanCenterActivity::class.java))
                    return@setOnItemSelectedListener false
                }
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
        binding.smartCleanButton.setOnClickListener { startActivity(Intent(this, SmartScanActivity::class.java)) }
        binding.cacheQuickButton.setOnClickListener { startActivity(Intent(this, CacheActivity::class.java)) }
        binding.emptyQuickButton.setOnClickListener { openProfile("empty") }
        binding.rulesQuickButton.setOnClickListener { openProfile("rules") }
        binding.fragmentsQuickButton.setOnClickListener { openProfile("fragments") }
        binding.deepQuickButton.setOnClickListener { openProfile("deep") }
        binding.corpsesQuickButton.setOnClickListener { openProfile("corpses") }
        binding.reconnectButton.setOnClickListener {
            if (serviceBound) runCatching { RootService.unbind(connection) }
            profileService = null
            serviceBound = false
            connectService()
        }
    }

    private fun openProfile(profile: String) {
        startActivity(Intent(this, ProfileActivity::class.java).putExtra(ProfileActivity.EXTRA_PROFILE, profile))
    }

    private fun setupSettings() {
        val scheduleEnabled = preferences.getBoolean("schedule_enabled", false)
        val interval = preferences.getFloat("interval_hours", 24f).coerceIn(1f, 72f)
        val screenOff = preferences.getBoolean("screen_off_only", true)
        val charging = preferences.getBoolean("charging_only", false)
        val notification = preferences.getBoolean("notifications", true)
        val largeFile = preferences.getFloat("large_file_mb", 512f).coerceIn(64f, 2048f)
        val fragmentDays = preferences.getInt("fragment_days", 7).coerceIn(1, 30).toFloat()

        binding.scheduleSwitch.isChecked = scheduleEnabled
        binding.intervalSlider.value = interval
        binding.screenOffSwitch.isChecked = screenOff
        binding.chargingSwitch.isChecked = charging
        binding.notificationSwitch.isChecked = notification
        binding.largeFileSlider.value = largeFile
        binding.fragmentDaysSlider.value = fragmentDays
        updatePlanText()
        binding.largeFileText.text = "${largeFile.toInt()} MB"
        binding.fragmentDaysText.text = "至少保留 ${fragmentDays.toInt()} 天"

        binding.scheduleSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean("schedule_enabled", checked).apply()
            updatePlanText()
        }
        binding.intervalSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) preferences.edit().putFloat("interval_hours", value).apply()
            binding.intervalText.text = "每 ${value.toInt()} 小时"
            updatePlanText()
        }
        binding.screenOffSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean("screen_off_only", checked).apply()
            updatePlanText()
        }
        binding.chargingSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean("charging_only", checked).apply()
            updatePlanText()
        }
        binding.notificationSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean("notifications", checked).apply()
        }
        binding.largeFileSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) preferences.edit().putFloat("large_file_mb", value).apply()
            binding.largeFileText.text = "${value.toInt()} MB"
        }
        binding.fragmentDaysSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) preferences.edit().putInt("fragment_days", value.toInt()).apply()
            binding.fragmentDaysText.text = "至少保留 ${value.toInt()} 天"
        }
    }

    private fun updatePlanText() {
        binding.intervalText.text = "每 ${binding.intervalSlider.value.toInt()} 小时"
        binding.planStateText.text = if (binding.scheduleSwitch.isChecked) {
            buildString {
                append("计划参数已保存 · 每 ${binding.intervalSlider.value.toInt()} 小时")
                if (binding.screenOffSwitch.isChecked) append(" · 息屏后")
                if (binding.chargingSwitch.isChecked) append(" · 仅充电")
                append("。后台调度器接入完成前不会假装自动执行。")
            }
        } else {
            "后台自动清理未启用。"
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

    private fun refreshReport() {
        val report = preferences.getString("last_report_text", null) ?: "暂无清理记录"
        val bytes = preferences.getLong("last_clean_bytes", 0L)
        binding.recentTaskText.text = report
        binding.recordSummaryText.text = report
        binding.lastFreedText.text = if (bytes > 0L) "最近释放 ${Formatter.formatFileSize(this, bytes)}" else "最近释放 --"
    }

    private fun refreshWhitelist() {
        val count = preferences.getStringSet("package_whitelist", emptySet()).orEmpty().size
        binding.whitelistText.text = "白名单：$count 个应用"
    }

    private fun connectService() {
        renderServiceState("正在连接 Root 原生引擎", false)
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
            serviceBound = true
        }.onFailure {
            renderServiceState(it.message ?: "Root 原生引擎启动失败", false)
        }
    }

    private fun readServiceStatus() {
        lifecycleScope.launch {
            val raw = runCatching { withContext(Dispatchers.IO) { profileService?.ping().orEmpty() } }.getOrNull()
            val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            val root = json?.optBoolean("root") == true
            val module = json?.optBoolean("module") == true
            val rules = json?.optBoolean("deepRules") == true
            val text = when {
                !root -> "服务已连接，但未获得完整 Root"
                !module -> "Root 已连接 · 未检测到一体化模块"
                !rules -> "Root 与模块已连接 · 深度规则库缺失"
                else -> "Root、模块与完整规则库均已就绪"
            }
            renderServiceState(text, root && module && rules)
        }
    }

    private fun renderServiceState(text: String, ready: Boolean) {
        binding.serviceStatusText.text = text
        binding.settingsStatusText.text = text
        binding.serviceDot.alpha = if (ready) 1f else 0.35f
    }

    override fun onDestroy() {
        if (serviceBound) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }
}
