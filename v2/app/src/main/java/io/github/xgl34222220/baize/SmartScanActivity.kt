package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.databinding.ActivitySmartScanBinding
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.BaiZeRootService
import io.github.xgl34222220.baize.root.IBaiZeRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SmartScanActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySmartScanBinding
    private val preferences by lazy { getSharedPreferences("baize_v2", MODE_PRIVATE) }

    private var cacheService: IBaiZeRootService? = null
    private var profileService: IProfileRootService? = null
    private var cacheBound = false
    private var profileBound = false
    private var running = false

    private val cacheConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            cacheService = IBaiZeRootService.Stub.asInterface(binder)
            cacheBound = true
            updateConnectionState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cacheService = null
            cacheBound = false
            updateConnectionState()
        }
    }

    private val profileConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            profileService = IProfileRootService.Stub.asInterface(binder)
            profileBound = true
            updateConnectionState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            profileService = null
            profileBound = false
            updateConnectionState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmartScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.startButton.setOnClickListener { startSmartScan() }
        binding.cancelButton.setOnClickListener {
            cacheService?.cancelCurrentTask()
            profileService?.cancelCurrentTask()
            binding.summaryText.text = "正在请求安全停止…"
        }
        binding.cacheResultCard.setOnClickListener { startActivity(Intent(this, CacheActivity::class.java)) }
        binding.emptyResultCard.setOnClickListener { openProfile("empty") }
        binding.rulesResultCard.setOnClickListener { openProfile("rules") }
        binding.fragmentsResultCard.setOnClickListener { openProfile("fragments") }

        bindServices()
    }

    private fun bindServices() {
        runCatching {
            RootService.bind(
                Intent(this, BaiZeRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE),
                cacheConnection
            )
            cacheBound = true
        }.onFailure { binding.statusText.text = it.message ?: "缓存引擎连接失败" }
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE),
                profileConnection
            )
            profileBound = true
        }.onFailure { binding.statusText.text = it.message ?: "分类引擎连接失败" }
    }

    private fun updateConnectionState() {
        val ready = cacheService != null && profileService != null
        binding.startButton.isEnabled = ready && !running
        binding.statusText.text = when {
            ready -> "两套 Root 原生引擎已连接"
            cacheService != null -> "应用缓存引擎已连接，等待分类引擎"
            profileService != null -> "分类引擎已连接，等待应用缓存引擎"
            else -> "正在连接两套 Root 引擎"
        }
    }

    private fun startSmartScan() {
        val cache = cacheService ?: return
        val profiles = profileService ?: return
        if (running) return
        running = true
        binding.startButton.isEnabled = false
        binding.cancelButton.isEnabled = true
        binding.progressIndicator.visibility = View.VISIBLE
        binding.cacheResultText.text = "应用缓存 · 正在扫描"
        binding.emptyResultText.text = "空项目 · 等待扫描"
        binding.rulesResultText.text = "规则垃圾 · 等待扫描"
        binding.fragmentsResultText.text = "残留碎片 · 等待扫描"
        binding.summaryText.text = "正在扫描应用缓存…"
        val started = SystemClock.elapsedRealtime()

        lifecycleScope.launch {
            val results = LinkedHashMap<String, JSONObject>()
            try {
                val whitelist = preferences.getStringSet("package_whitelist", emptySet()).orEmpty()
                val cacheRaw = withContext(Dispatchers.IO) {
                    cache.scanCandidates(JSONArray(whitelist.toList()).toString())
                }
                val cacheJson = JSONObject(cacheRaw)
                results["cache"] = cacheJson
                binding.cacheResultText.text = summary("应用缓存", cacheJson)
                if (cacheJson.optBoolean("cancelled")) return@launch

                for (profile in listOf("empty", "rules", "fragments")) {
                    binding.summaryText.text = "正在扫描${ProfileActivity.profileTitle(profile)}…"
                    when (profile) {
                        "empty" -> binding.emptyResultText.text = "空项目 · 正在扫描"
                        "rules" -> binding.rulesResultText.text = "规则垃圾 · 正在扫描"
                        "fragments" -> binding.fragmentsResultText.text = "残留碎片 · 正在扫描"
                    }
                    val raw = withContext(Dispatchers.IO) {
                        profiles.scanProfile(profile, optionsJson())
                    }
                    val json = JSONObject(raw)
                    results[profile] = json
                    when (profile) {
                        "empty" -> binding.emptyResultText.text = summary("空项目", json)
                        "rules" -> binding.rulesResultText.text = summary("规则垃圾", json)
                        "fragments" -> binding.fragmentsResultText.text = summary("残留碎片", json)
                    }
                    if (json.optBoolean("cancelled")) break
                }

                val total = results.values.sumOf { it.optInt("totalCandidates") }
                val failed = results.values.count { it.has("error") }
                binding.summaryText.text = buildString {
                    append(if (results.values.any { it.optBoolean("cancelled") }) "智能扫描已停止" else "智能扫描完成")
                    append(" · ${SystemClock.elapsedRealtime() - started}ms\n")
                    append("四类共发现 $total 项")
                    if (failed > 0) append(" · $failed 类扫描异常")
                    append("\n点击分类进入详情并生成新的可清理快照。")
                }
            } catch (error: Throwable) {
                binding.summaryText.text = "智能扫描失败：${error.message ?: error.javaClass.simpleName}"
            } finally {
                running = false
                binding.progressIndicator.visibility = View.GONE
                binding.cancelButton.isEnabled = false
                updateConnectionState()
            }
        }
    }

    private fun summary(title: String, json: JSONObject): String {
        if (json.has("error")) return "$title · ${json.optString("message", "扫描失败")}"
        if (json.optBoolean("cancelled")) return "$title · 已停止 · ${json.optInt("totalCandidates")} 项"
        return "$title · ${json.optInt("totalCandidates")} 项 · ${json.optLong("elapsedMs")}ms"
    }

    private fun optionsJson(): String {
        val whitelist = preferences.getStringSet("package_whitelist", emptySet()).orEmpty()
        val pathWhitelist = preferences.getStringSet("path_whitelist", emptySet()).orEmpty()
        val maxMb = preferences.getFloat("large_file_mb", 512f).toLong().coerceIn(64L, 16_384L)
        return JSONObject()
            .put("whitelistPackages", JSONArray(whitelist.toList()))
            .put("whitelistPaths", JSONArray(pathWhitelist.toList()))
            .put("maxFileBytes", maxMb * 1024L * 1024L)
            .put("fragmentDays", preferences.getInt("fragment_days", 7).coerceIn(1, 365))
            .put("allowHighRisk", false)
            .toString()
    }

    private fun openProfile(profile: String) {
        startActivity(Intent(this, ProfileActivity::class.java).putExtra(ProfileActivity.EXTRA_PROFILE, profile))
    }

    override fun onDestroy() {
        if (cacheBound) runCatching { RootService.unbind(cacheConnection) }
        if (profileBound) runCatching { RootService.unbind(profileConnection) }
        super.onDestroy()
    }
}
