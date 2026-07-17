package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
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

        adapter = CandidateAdapter(
            onSelectionChanged = { item, checked ->
                if (checked) selectionOverrides.remove(item.path) else selectionOverrides[item.path] = false
                persistSelectionOverrides()
                updateSelectionText()
            },
            onWhitelist = { item -> addToWhitelist(item.packageName, item.appName) }
        )
        binding.resultsList.layoutManager = LinearLayoutManager(this)
        binding.resultsList.adapter = adapter

        binding.versionText.text = "v${BuildConfig.VERSION_NAME} · 原生 App / Root Binder"
        binding.connectButton.setOnClickListener { connectRootService() }
        binding.scanButton.setOnClickListener { runScan() }
        binding.cleanSelectedButton.setOnClickListener { confirmCleanup() }
        binding.cancelButton.setOnClickListener {
            rootService?.cancelCurrentTask()
            binding.resultText.text = if (cleanupRunning) "正在请求停止清理…" else "正在请求停止任务…"
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
        binding.cleanSelectedButton.isEnabled = false
        binding.cancelButton.isEnabled = false
    }

    private fun runScan() {
        val service = rootService ?: return
        if (cleanupRunning) return
        setTaskUi(true, allowCancel = true)
        binding.resultsSection.visibility = View.GONE
        binding.resultText.text = "正在验证真实且非空的缓存目录…"
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
                    binding.resultText.text = json.optString("message", "已有任务正在运行")
                    return@onSuccess
                }
                if (json.optBoolean("cancelled")) {
                    binding.resultText.text = "扫描已停止 · ${json.optLong("elapsedMs")}ms"
                    return@onSuccess
                }
                currentSnapshotId = json.optString("snapshotId")
                totalResults = json.optInt("totalCandidates")
                scanWhitelisted = json.optInt("whitelisted")
                val elapsed = json.optLong("elapsedMs")
                binding.resultText.text = buildString {
                    append("扫描完成 · ${elapsed}ms\n")
                    append("非空真实目录：$totalResults\n")
                    append("白名单保护：$scanWhitelisted\n")
                    append("快照有效期 30 分钟；清理前会再次验证路径。")
                }
                binding.resultsSection.visibility = if (totalResults > 0) View.VISIBLE else View.GONE
                binding.cleanSelectedButton.isEnabled = totalResults > 0 && currentSnapshotId.isNotBlank()
                if (totalResults > 0) loadPage(0)
            }.onFailure { error ->
                binding.resultText.text = "扫描失败：${error.message ?: error.javaClass.simpleName}"
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
                        selected = selectionOverrides[path] ?: !whitelisted
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
        val manuallyCancelled = selectionOverrides.values.count { !it }
        binding.selectionText.text = buildString {
            append("共 $totalResults 项 · 本页已选 ${adapter.currentSelectedCount()} 项")
            append(" · 手动取消 $manuallyCancelled 项")
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
        binding.resultText.text = "已将 $appName 加入白名单；清理服务会再次读取白名单并强制保护。"
    }

    private fun confirmCleanup() {
        if (currentSnapshotId.isBlank() || totalResults <= 0 || cleanupRunning) return
        val manuallyCancelled = selectionOverrides.values.count { !it }
        AlertDialog.Builder(this)
            .setTitle("清理已选缓存")
            .setMessage(
                "默认清理当前快照内全部非白名单缓存目录，已手动取消 $manuallyCancelled 项。\n\n" +
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
                    binding.resultText.text = "清理失败：${error.message ?: error.javaClass.simpleName}"
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
        binding.resultText.text = buildString {
            append(json.optString("phase", "正在清理"))
            if (total > 0) append(" · $current/$total")
            append("\n已释放 ${Formatter.formatFileSize(this@MainActivity, bytes)}")
            append(" · 已删除 $files 个文件")
            if (failures > 0) append(" · 失败 $failures")
            if (appName.isNotBlank()) append("\n当前：$appName")
            if (json.optBoolean("cancelRequested")) append("\n正在安全停止…")
        }
    }

    private fun renderCleanupReport(json: JSONObject) {
        if (!json.optBoolean("success")) {
            binding.resultText.text = json.optString("message", "清理任务未执行")
            binding.cleanSelectedButton.isEnabled = false
            return
        }
        val cancelled = json.optBoolean("cancelled")
        val timedOut = json.optBoolean("totalTimedOut")
        binding.resultText.text = buildString {
            append(
                when {
                    cancelled -> "清理已停止"
                    timedOut -> "清理达到总时间预算"
                    else -> "清理完成"
                }
            )
            append(" · ${json.optLong("elapsedMs")}ms\n")
            append("已处理 ${json.optInt("processed")}/${json.optInt("selected")} 项\n")
            append("已释放 ${Formatter.formatFileSize(this@MainActivity, json.optLong("deletedBytes"))}\n")
            append("删除文件：${json.optLong("deletedFiles")} · 目录：${json.optLong("deletedDirectories")}\n")
            append("完成：${json.optInt("cleanedCandidates")} · 跳过：${json.optInt("skippedCandidates")}")
            append(" · 异常：${json.optInt("failedCandidates")}")
            if (json.optInt("protectedMounts") > 0) append("\n挂载点保护：${json.optInt("protectedMounts")}")
            if (json.optInt("failures") > 0) append(" · 删除失败：${json.optInt("failures")}")
            append("\n详细报告已保存到 /data/adb/baize-v2/last-clean-report.json")
        }
        currentSnapshotId = ""
        totalResults = 0
        scanWhitelisted = 0
        adapter.submitPage(emptyList())
        binding.resultsSection.visibility = View.GONE
        binding.cleanSelectedButton.isEnabled = false
        clearSelectionOverrides()
    }

    private fun setTaskUi(running: Boolean, allowCancel: Boolean) {
        binding.progressIndicator.visibility = if (running) View.VISIBLE else View.GONE
        binding.connectButton.isEnabled = !running
        binding.scanButton.isEnabled = !running && rootService != null
        binding.cancelButton.isEnabled = running && allowCancel
        binding.cleanSelectedButton.isEnabled = !running && currentSnapshotId.isNotBlank() && totalResults > 0
        binding.previousPageButton.isEnabled = !running && currentPage > 0
        binding.nextPageButton.isEnabled = !running && currentPage + 1 < pageCount()
        binding.clearWhitelistButton.isEnabled = !running
    }

    private fun restoreSelectionOverrides() {
        val raw = preferences.getString(KEY_SELECTION_OVERRIDES, null).orEmpty()
        runCatching {
            val json = JSONObject(raw)
            val keys = json.keys()
            while (keys.hasNext()) {
                val path = keys.next()
                if (path.startsWith("/") && !json.optBoolean(path, true)) selectionOverrides[path] = false
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
    }
}
