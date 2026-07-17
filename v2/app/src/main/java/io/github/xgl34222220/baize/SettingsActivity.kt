package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.databinding.ActivitySettingsBinding
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Isolated settings screen for Alpha 11.
 *
 * Settings no longer share the dashboard's hidden page host or floating dock state. This keeps theme
 * recreation, Root-service binding and preference writes away from running cleaning tasks.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val preferences by lazy { getSharedPreferences(ThemeManager.PREFS, MODE_PRIVATE) }

    private var service: IProfileRootService? = null
    private var serviceBound = false
    private var loading = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IProfileRootService.Stub.asInterface(binder)
            serviceBound = true
            readServiceState()
            loadEngineSettings()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            serviceBound = false
            binding.engineStatusText.text = "Root 服务已断开"
            binding.saveSettingsButton.isEnabled = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.themeButton.setOnClickListener { showThemeDialog() }
        binding.reconnectButton.setOnClickListener { reconnect() }
        binding.saveSettingsButton.setOnClickListener { saveEngineSettings() }
        binding.clearWhitelistButton.setOnClickListener { confirmClearWhitelist() }
        binding.largeFileSlider.addOnChangeListener { _, value, _ ->
            binding.largeFileText.text = "单文件保护上限 ${value.toInt()} MB"
        }

        renderThemeSummary()
        refreshWhitelist()
        connectService()
    }

    override fun onResume() {
        super.onResume()
        renderThemeSummary()
        refreshWhitelist()
    }

    private fun connectService() {
        binding.engineStatusText.text = "正在连接 Root 清理服务…"
        binding.saveSettingsButton.isEnabled = false
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java)
                    .addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
            serviceBound = true
        }.onFailure {
            serviceBound = false
            binding.engineStatusText.text = "Root 服务启动失败：${it.message ?: "未知错误"}"
        }
    }

    private fun reconnect() {
        if (serviceBound) runCatching { RootService.unbind(connection) }
        service = null
        serviceBound = false
        connectService()
    }

    private fun readServiceState() {
        val rootService = service ?: return
        lifecycleScope.launch {
            val raw = runCatching { withContext(Dispatchers.IO) { rootService.ping() } }.getOrNull()
            val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            val root = json?.optBoolean("root") == true
            val module = json?.optBoolean("module") == true
            val cleaner = json?.optBoolean("cleaner") == true
            binding.engineStatusText.text = when {
                !root -> "服务已连接，但未获得完整 Root"
                !module -> "Root 已连接 · 未检测到白泽模块"
                !cleaner -> "模块已连接 · 清理引擎缺失"
                else -> "Root、自动清理与规则保护均已就绪"
            }
            binding.saveSettingsButton.isEnabled = root && module && cleaner
        }
    }

    private fun loadEngineSettings() {
        val rootService = service ?: return
        lifecycleScope.launch {
            val raw = runCatching {
                withContext(Dispatchers.IO) { rootService.getSchedulerConfig() }
            }.getOrNull() ?: return@launch
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@launch
            loading = true
            binding.notificationSwitch.isChecked = json.optInt("notify_on_complete", 1) == 1
            binding.largeFileSlider.value = json.optInt("max_file_mb", 256)
                .coerceIn(16, 2048)
                .toFloat()
            binding.largeFileText.text = "单文件保护上限 ${binding.largeFileSlider.value.toInt()} MB"
            loading = false
        }
    }

    private fun saveEngineSettings() {
        if (loading) return
        val rootService = service ?: run {
            binding.saveStateText.text = "Root 服务尚未连接"
            return
        }
        val payload = JSONObject()
            .put("notify_on_complete", if (binding.notificationSwitch.isChecked) 1 else 0)
            .put("max_file_mb", binding.largeFileSlider.value.toInt())
        binding.saveStateText.text = "正在写入模块配置…"
        binding.saveSettingsButton.isEnabled = false
        lifecycleScope.launch {
            val raw = runCatching {
                withContext(Dispatchers.IO) { rootService.saveSchedulerConfig(payload.toString()) }
            }
            raw.onSuccess {
                val result = runCatching { JSONObject(it) }.getOrDefault(JSONObject())
                binding.saveStateText.text = if (result.optBoolean("success")) {
                    "设置已保存，手动与定时清理都会使用新保护策略。"
                } else {
                    "保存失败：${result.optString("error", "未知错误")}"
                }
            }.onFailure {
                binding.saveStateText.text = "保存失败：${it.message ?: it.javaClass.simpleName}"
            }
            binding.saveSettingsButton.isEnabled = service != null
        }
    }

    private fun renderThemeSummary() {
        val palette = ThemeManager.currentPalette(this)
        binding.themeSummaryText.text = buildString {
            append(palette.label).append(" · ").append(palette.description)
            if (palette.monet && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                append("（当前系统回退为白泽蓝）")
            }
        }
    }

    private fun showThemeDialog() {
        val current = ThemeManager.currentId(this)
        val labels = ThemeManager.palettes.map { palette ->
            if (palette.monet) {
                "${palette.label}\n${palette.description}（Android 12+）"
            } else {
                "${palette.label}\n${palette.description}"
            }
        }.toTypedArray()
        val checked = ThemeManager.palettes.indexOfFirst { it.id == current }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("主题与取色")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                ThemeManager.setPalette(this, ThemeManager.palettes[which].id)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshWhitelist() {
        val packages = preferences.getStringSet("package_whitelist", emptySet()).orEmpty().size
        val paths = preferences.getStringSet("path_whitelist", emptySet()).orEmpty().size
        binding.whitelistText.text = "已保护 $packages 个应用 · $paths 条路径"
        binding.clearWhitelistButton.isEnabled = packages + paths > 0
    }

    private fun confirmClearWhitelist() {
        AlertDialog.Builder(this)
            .setTitle("清空白名单")
            .setMessage("清空后，这些应用和路径会重新参与扫描。不会立即执行清理。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认清空") { _, _ ->
                preferences.edit()
                    .remove("package_whitelist")
                    .remove("path_whitelist")
                    .apply()
                refreshWhitelist()
            }
            .show()
    }

    override fun onDestroy() {
        if (serviceBound) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }
}
