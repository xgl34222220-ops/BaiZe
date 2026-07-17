package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
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
    private var currentPage = 0
    private var totalResults = 0
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
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rootService = null
            bindingRequested = false
            renderDisconnected("Root 服务已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = CandidateAdapter(
            onSelectionChanged = { item, checked ->
                selectionOverrides[item.path] = checked
                updateSelectionText()
            },
            onWhitelist = { item -> addToWhitelist(item.packageName, item.appName) }
        )
        binding.resultsList.layoutManager = LinearLayoutManager(this)
        binding.resultsList.adapter = adapter

        binding.versionText.text = "v${BuildConfig.VERSION_NAME} · 原生 App / Root Binder"
        binding.connectButton.setOnClickListener { connectRootService() }
        binding.scanButton.setOnClickListener { runScan() }
        binding.cancelButton.setOnClickListener {
            rootService?.cancelCurrentTask()
            binding.resultText.text = "正在请求停止…"
        }
        binding.previousPageButton.setOnClickListener { loadPage(currentPage - 1) }
        binding.nextPageButton.setOnClickListener { loadPage(currentPage + 1) }
        binding.clearWhitelistButton.setOnClickListener { confirmClearWhitelist() }
        renderDisconnected("尚未连接 Root 服务")
    }

    private fun rootIntent(): Intent = Intent(this, BaiZeRootService::class.java)
        .addCategory(RootService.CATEGORY_DAEMON_MODE)

    private fun connectRootService() {
        if (rootService != null) {
            renderConnected()
            return
        }
        binding.statusText.text = "正在请求 Root 权限并启动服务…"
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
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { rootService?.ping().orEmpty() } }
            val json = result.getOrNull()?.let(::JSONObject)
            val uid = json?.optInt("uid", -1) ?: -1
            val module = when {
                json?.optBoolean("moduleV2") == true -> "v2 模块已安装"
                json?.optBoolean("moduleV1") == true -> "检测到 v1 模块"
                else -> "模块桥接未安装"
            }
            binding.statusText.text = if (uid == 0) "Root 服务已连接 · $module" else "服务已连接，但 UID=$uid"
        }
    }

    private fun renderDisconnected(message: String) {
        binding.statusText.text = message
        binding.connectButton.isEnabled = true
        binding.scanButton.isEnabled = false
        binding.cancelButton.isEnabled = false
    }

    private fun runScan() {
        val service = rootService ?: return
        binding.scanButton.isEnabled = false
        binding.cancelButton.isEnabled = true
        binding.progressIndicator.visibility = View.VISIBLE
        binding.resultsSection.visibility = View.GONE
        binding.resultText.text = "正在验证真实且非空的缓存目录…"
        selectionOverrides.clear()

        val whitelistJson = JSONArray(whitelist.toList()).toString()
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { service.scanCandidates(whitelistJson) }
            }
            binding.progressIndicator.visibility = View.GONE
            binding.scanButton.isEnabled = rootService != null
            binding.cancelButton.isEnabled = false
            result.onSuccess { raw ->
                val json = JSONObject(raw)
                if (json.optBoolean("cancelled")) {
                    binding.resultText.text = "扫描已停止 · ${json.optLong("elapsedMs")}ms"
                    return@onSuccess
                }
                totalResults = json.optInt("totalCandidates")
                val elapsed = json.optLong("elapsedMs")
                val white = json.optInt("whitelisted")
                binding.resultText.text = buildString {
                    append("扫描完成 · ${elapsed}ms\n")
                    append("非空真实目录：$totalResults\n")
                    append("白名单：$white\n")
                    append("大小和文件数按页统计，单页最多等待 8 秒。")
                }
                binding.resultsSection.visibility = if (totalResults > 0) View.VISIBLE else View.GONE
                if (totalResults > 0) loadPage(0)
            }.onFailure { error ->
                binding.resultText.text = "扫描失败：${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    private fun loadPage(page: Int) {
        val service = rootService ?: return
        val pageCount = pageCount()
        if (page !in 0 until pageCount) return
        lifecycleScope.launch {
            binding.progressIndicator.visibility = View.VISIBLE
            binding.cancelButton.isEnabled = true
            binding.previousPageButton.isEnabled = false
            binding.nextPageButton.isEnabled = false
            binding.selectionText.text = "正在统计第 ${page + 1} 页，最长等待 8 秒…"
            val result = runCatching {
                withContext(Dispatchers.IO) { service.getResultPage(page * pageSize, pageSize) }
            }
            binding.progressIndicator.visibility = View.GONE
            binding.cancelButton.isEnabled = false
            result.onSuccess { raw ->
                val json = JSONObject(raw)
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
                        selected = selectionOverrides[path] ?: !whitelisted
                    )
                }
                currentPage = page
                adapter.submitPage(items)
                binding.pageText.text = "${page + 1} / $pageCount"
                binding.previousPageButton.isEnabled = page > 0
                binding.nextPageButton.isEnabled = page + 1 < pageCount
                updateSelectionText()
            }.onFailure {
                binding.selectionText.text = "结果分页读取失败：${it.message}"
            }
        }
    }

    private fun pageCount(): Int = ceil(totalResults / pageSize.toDouble()).toInt().coerceAtLeast(1)

    private fun updateSelectionText() {
        binding.selectionText.text = buildString {
            append("共 $totalResults 项 · 本页已选 ${adapter.currentSelectedCount()} 项")
            append(" · 白名单 ${whitelist.size} 个应用")
        }
    }

    private fun addToWhitelist(packageName: String, appName: String) {
        val updated = whitelist
        updated += packageName
        preferences.edit().putStringSet(KEY_WHITELIST, updated).apply()
        adapter.markPackageWhitelisted(packageName)
        selectionOverrides.entries.removeAll { it.key.contains("/$packageName/") }
        updateSelectionText()
        binding.resultText.text = "已将 $appName 加入白名单；下次扫描会自动取消勾选。"
    }

    private fun confirmClearWhitelist() {
        if (whitelist.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("清空白名单")
            .setMessage("将移除全部 ${whitelist.size} 个应用白名单，当前扫描结果不会自动重新统计。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                preferences.edit().remove(KEY_WHITELIST).apply()
                updateSelectionText()
                binding.resultText.text = "白名单已清空，请重新扫描刷新状态。"
            }
            .show()
    }

    override fun onDestroy() {
        if (bindingRequested) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }

    companion object {
        private const val KEY_WHITELIST = "package_whitelist"
    }
}
