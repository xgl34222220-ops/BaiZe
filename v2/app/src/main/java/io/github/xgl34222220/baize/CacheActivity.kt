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
import io.github.xgl34222220.baize.databinding.ActivityProfileBinding
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.BaiZeRootService
import io.github.xgl34222220.baize.root.IBaiZeRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

class CacheActivity : AppCompatActivity() {
    private lateinit var binding: ActivityProfileBinding
    private lateinit var adapter: ProfileCandidateAdapter
    private val preferences by lazy { getSharedPreferences("baize_v2", MODE_PRIVATE) }

    private var cacheService: IBaiZeRootService? = null
    private var moduleService: IProfileRootService? = null
    private var cacheBindingRequested = false
    private var moduleBindingRequested = false
    private var running = false
    private var recovering = false
    private var snapshotId = ""
    private var total = 0
    private var page = 0
    private var quickCleanReady = false
    private var pollJob: Job? = null

    private val cacheConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            cacheService = IBaiZeRootService.Stub.asInterface(binder)
            cacheBindingRequested = true
            renderConnectionState()
            renderActionState()
            recoverRemoteOrSnapshot()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cacheService = null
            cacheBindingRequested = false
            renderConnectionState()
            renderActionState()
        }
    }

    private val moduleConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            moduleService = IProfileRootService.Stub.asInterface(binder)
            moduleBindingRequested = true
            renderConnectionState()
            renderActionState()
            recoverRemoteOrSnapshot()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            moduleService = null
            moduleBindingRequested = false
            renderConnectionState()
            renderActionState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.titleText.text = "应用缓存"
        binding.subtitleText.text = "内部 cache、code_cache 与 Android/data 外部缓存"
        binding.resultTitleText.text = "缓存明细"
        binding.safetyText.text = "扫描只执行一次并保存 30 分钟快照；一键清理只处理刚才扫描到的缓存，不会再次扫描。退出页面后任务进度与结果仍可恢复。"

        adapter = ProfileCandidateAdapter { _, _ -> }
        adapter.setInteractionEnabled(false)
        binding.resultsList.layoutManager = LinearLayoutManager(this)
        binding.resultsList.adapter = adapter

        binding.backButton.setOnClickListener { finish() }
        binding.scanButton.setOnClickListener { scan() }
        binding.cancelButton.setOnClickListener { stopTask() }
        binding.cleanButton.setOnClickListener { confirmQuickClean() }
        binding.previousButton.setOnClickListener { loadPage(page - 1) }
        binding.nextButton.setOnClickListener { loadPage(page + 1) }

        binding.scanButton.text = "扫描缓存明细"
        binding.cleanButton.text = "扫描后可一键清理"
        binding.statusText.text = "正在连接 Root 持久任务引擎"
        binding.resultSection.visibility = View.GONE
        renderActionState()
        connectServices()
    }

    private fun cacheIntent(): Intent = Intent(this, BaiZeRootService::class.java)
        .addCategory(RootService.CATEGORY_DAEMON_MODE)

    private fun moduleIntent(): Intent = Intent(this, BaiZeProfileRootService::class.java)
        .addCategory(RootService.CATEGORY_DAEMON_MODE)

    private fun connectServices() {
        runCatching {
            RootService.bind(cacheIntent(), cacheConnection)
            cacheBindingRequested = true
        }.onFailure {
            binding.statusText.text = it.message ?: "缓存任务服务启动失败"
        }
        runCatching {
            RootService.bind(moduleIntent(), moduleConnection)
            moduleBindingRequested = true
        }.onFailure {
            binding.statusText.text = it.message ?: "模块任务服务启动失败"
        }
    }

    private fun renderConnectionState() {
        binding.statusText.text = when {
            cacheService != null && moduleService != null -> "Root 持久扫描与快照清理引擎已连接"
            cacheService != null -> "缓存扫描已连接 · 正在连接快照清理"
            moduleService != null -> "快照清理已连接 · 正在连接缓存扫描"
            else -> "Root 服务已断开"
        }
    }

    private fun scan() {
        val root = cacheService ?: return
        if (running) return
        running = true
        snapshotId = ""
        total = 0
        page = 0
        quickCleanReady = false
        adapter.submitPage(emptyList())
        adapter.setInteractionEnabled(false)
        binding.resultSection.visibility = View.GONE
        setTaskUi(true)
        binding.summaryText.text = "正在扫描应用缓存…"
        startPolling()

        lifecycleScope.launch {
            val whitelist = preferences.getStringSet("package_whitelist", emptySet()).orEmpty()
            val result = runCatching {
                withContext(Dispatchers.IO) { root.scanCandidates(JSONArray(whitelist.toList()).toString()) }
            }
            running = false
            pollJob?.cancel()
            setTaskUi(false)
            result.onSuccess { raw ->
                val json = JSONObject(raw)
                if (json.optString("error") == "busy") {
                    binding.summaryText.text = "检测到后台任务，正在恢复真实进度…"
                    recoverRemoteOrSnapshot()
                    return@onSuccess
                }
                if (json.has("error")) {
                    binding.summaryText.text = json.optString("message", "扫描失败")
                    return@onSuccess
                }
                if (json.optBoolean("cancelled")) {
                    binding.summaryText.text = "扫描已停止 · ${json.optLong("elapsedMs")}ms"
                    restoreSnapshot(showEmptyMessage = false)
                    return@onSuccess
                }
                applyScanResult(json)
            }.onFailure {
                binding.summaryText.text = "扫描失败：${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    private fun applyScanResult(json: JSONObject) {
        snapshotId = json.optString("snapshotId")
        total = json.optInt("totalCandidates").coerceAtLeast(0)
        page = 0
        quickCleanReady = snapshotId.isNotBlank() && total > 0
        binding.summaryText.text = buildString {
            append("扫描完成 · ${json.optLong("elapsedMs")}ms\n")
            append("发现 $total 项真实非空缓存")
            val files = json.optLong("totalFiles", 0L)
            val bytes = json.optLong("totalBytes", 0L)
            if (files > 0L) append(" · $files 个文件")
            if (bytes > 0L) append(" · ${Formatter.formatFileSize(this@CacheActivity, bytes)}")
            val protected = json.optInt("whitelisted", 0)
            if (protected > 0) append(" · 白名单保护 $protected 项")
            append("\n已保存扫描快照，清理时不会再次扫描。")
        }
        binding.cleanButton.text = if (quickCleanReady) "一键清理刚才扫描的缓存（$total 项）" else "没有可清理缓存"
        binding.selectionText.text = if (quickCleanReady) "当前快照 30 分钟内有效；列表只用于查看明细。" else "没有发现可清理缓存"
        binding.resultSection.visibility = if (quickCleanReady) View.VISIBLE else View.GONE
        if (quickCleanReady) loadPage(0)
        renderActionState()
    }

    private fun loadPage(targetPage: Int) {
        val root = cacheService ?: return
        if (snapshotId.isBlank() || running) return
        val pages = pageCount()
        if (targetPage !in 0 until pages) return
        binding.progressIndicator.visibility = View.VISIBLE
        binding.previousButton.isEnabled = false
        binding.nextButton.isEnabled = false
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { root.getResultPage(snapshotId, targetPage * PAGE_SIZE, PAGE_SIZE) }
            }
            binding.progressIndicator.visibility = View.GONE
            result.onSuccess { raw ->
                val json = JSONObject(raw)
                if (json.has("error")) {
                    binding.selectionText.text = json.optString("message", "读取结果失败")
                    quickCleanReady = false
                    renderActionState()
                    return@onSuccess
                }
                val array = json.optJSONArray("items") ?: JSONArray()
                val values = ArrayList<ProfileCandidate>(array.length())
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val path = item.optString("path")
                    values.add(
                        ProfileCandidate(
                            id = path,
                            appName = item.optString("appName", item.optString("packageName")),
                            packageName = item.optString("packageName"),
                            categoryLabel = item.optString("categoryLabel", "缓存"),
                            risk = "low",
                            path = path,
                            bytes = item.optLong("bytes", -1L),
                            files = item.optLong("files", -1L),
                            directories = item.optLong("directories", -1L),
                            measured = item.optBoolean("measured"),
                            complete = item.optBoolean("complete"),
                            note = "已纳入扫描快照",
                            selected = true
                        )
                    )
                }
                page = targetPage
                adapter.submitPage(values)
                adapter.setInteractionEnabled(false)
                binding.pageText.text = "${page + 1} / $pages"
                binding.previousButton.isEnabled = page > 0
                binding.nextButton.isEnabled = page + 1 < pages
                binding.selectionText.text = "共 $total 项 · 当前页 ${values.size} 项 · 清理只消费本次快照"
                renderActionState()
            }.onFailure {
                binding.selectionText.text = "读取结果失败：${it.message}"
            }
        }
    }

    private fun confirmQuickClean() {
        if (!quickCleanReady || moduleService == null || running) return
        AlertDialog.Builder(this)
            .setTitle("清理刚才扫描到的缓存")
            .setMessage("不会再次扫描。只处理当前 30 分钟快照中的安全缓存；扫描后新建或修改的文件、白名单路径、软链接、挂载点和大文件会自动跳过。")
            .setNegativeButton("取消", null)
            .setPositiveButton("立即清理") { _, _ -> quickClean() }
            .show()
    }

    private fun quickClean() {
        val root = moduleService ?: return
        if (running || snapshotId.isBlank()) return
        running = true
        setTaskUi(true)
        binding.summaryText.text = "正在清理刚才扫描到的缓存，不会重新扫描…"
        startPolling()

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { root.runModuleTask("cache-clean") }
            }
            running = false
            pollJob?.cancel()
            setTaskUi(false)
            result.onSuccess { raw ->
                val json = JSONObject(raw)
                if (json.optString("error") == "busy" || json.optInt("exitCode") == 3) {
                    binding.summaryText.text = "检测到后台任务，正在恢复真实进度…"
                    recoverRemoteOrSnapshot()
                    return@onSuccess
                }
                val output = json.optString("output").lineSequence().filter { it.isNotBlank() }.takeLast(6).joinToString("\n")
                val report = buildString {
                    append(
                        when {
                            json.optBoolean("cancelled") -> "缓存清理已停止"
                            json.optBoolean("success") -> "扫描快照清理完成"
                            else -> json.optString("message", "缓存清理失败")
                        }
                    )
                    append(" · ${json.optLong("elapsedMs")}ms")
                    if (output.isNotBlank()) append("\n").append(output)
                }
                binding.summaryText.text = report
                preferences.edit().putString("last_report_text", report).apply()
                NativeNotifier.showTaskResult(
                    this@CacheActivity,
                    if (json.optBoolean("success")) "白泽缓存清理完成" else "白泽缓存任务结束",
                    json.optString("message", "缓存任务已结束"),
                    report
                )
                if (json.optBoolean("success") && !json.optBoolean("cancelled")) clearSnapshotUi()
                else restoreSnapshot(showEmptyMessage = false)
            }.onFailure {
                binding.summaryText.text = "清理失败：${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    private fun stopTask() {
        if (!running) return
        cacheService?.cancelCurrentTask()
        moduleService?.cancelCurrentTask()
        binding.summaryText.text = "已发送停止请求，正在结束当前目录…"
        startPolling()
    }

    private fun recoverRemoteOrSnapshot() {
        if (recovering || running || (cacheService == null && moduleService == null)) return
        recovering = true
        lifecycleScope.launch {
            val task = runCatching {
                withContext(Dispatchers.IO) {
                    val raw = cacheService?.getTaskState().orEmpty().ifBlank { moduleService?.getTaskState().orEmpty() }
                    if (raw.isBlank()) null else JSONObject(raw)
                }
            }.getOrNull()
            recovering = false
            if (task?.optBoolean("running") == true) {
                val mode = task.optString("mode", task.optString("operation"))
                if (mode.contains("cache", ignoreCase = true)) {
                    running = true
                    setTaskUi(true)
                    renderRemoteTaskState(task)
                    startRecoveryPolling()
                } else {
                    binding.summaryText.text = "其他清理任务正在运行：${task.optString("phase", mode)}"
                    renderActionState()
                }
            } else {
                restoreSnapshot(showEmptyMessage = true)
            }
        }
    }

    private fun startRecoveryPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive && running) {
                val task = readRemoteTaskState()
                if (task?.optBoolean("running") == true) {
                    renderRemoteTaskState(task)
                    delay(350L)
                    continue
                }
                running = false
                setTaskUi(false)
                restoreSnapshot(showEmptyMessage = false)
                break
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive && running) {
                val task = readRemoteTaskState()
                if (task?.optBoolean("running") == true) renderRemoteTaskState(task)
                delay(300L)
            }
        }
    }

    private suspend fun readRemoteTaskState(): JSONObject? = runCatching {
        withContext(Dispatchers.IO) {
            val raw = cacheService?.getTaskState().orEmpty().ifBlank { moduleService?.getTaskState().orEmpty() }
            if (raw.isBlank()) null else JSONObject(raw)
        }
    }.getOrNull()

    private fun renderRemoteTaskState(json: JSONObject) {
        binding.summaryText.text = buildString {
            append(json.optString("phase", "后台缓存任务正在执行"))
            val current = json.optInt("progress_current", json.optInt("current"))
            val totalState = json.optInt("progress_total", json.optInt("total"))
            if (totalState > 0) append(" · $current/$totalState")
            val path = json.optString("current_path", json.optString("currentPath"))
            if (path.isNotBlank()) append("\n").append(path.takeLast(92))
            if (json.optBoolean("cancelRequested")) append("\n正在安全停止…")
        }
    }

    private fun restoreSnapshot(showEmptyMessage: Boolean) {
        val root = cacheService ?: return
        lifecycleScope.launch {
            val info = runCatching {
                withContext(Dispatchers.IO) { JSONObject(root.ping()) }
            }.getOrNull() ?: return@launch
            if (!info.optBoolean("snapshotReady")) {
                clearSnapshotUi()
                if (showEmptyMessage) binding.summaryText.text = "等待开始缓存扫描"
                return@launch
            }
            snapshotId = info.optString("snapshotId")
            total = info.optInt("snapshotItems").coerceAtLeast(0)
            page = 0
            quickCleanReady = snapshotId.isNotBlank() && total > 0
            val files = info.optLong("snapshotFiles", 0L)
            val bytes = info.optLong("snapshotBytes", 0L)
            binding.summaryText.text = buildString {
                append("已恢复最近一次缓存扫描快照")
                if (total > 0) append("\n$total 项 · $files 个文件 · ${Formatter.formatFileSize(this@CacheActivity, bytes)}")
                else append("\n没有可清理缓存")
                append("\n可直接一键清理，不会重新扫描。")
            }
            binding.cleanButton.text = if (quickCleanReady) "一键清理刚才扫描的缓存（$total 项）" else "没有可清理缓存"
            binding.selectionText.text = "已从模块状态恢复；快照 30 分钟内有效。"
            binding.resultSection.visibility = if (quickCleanReady) View.VISIBLE else View.GONE
            if (quickCleanReady) loadPage(0)
            renderActionState()
        }
    }

    private fun clearSnapshotUi() {
        quickCleanReady = false
        snapshotId = ""
        total = 0
        page = 0
        adapter.submitPage(emptyList())
        binding.resultSection.visibility = View.GONE
        binding.cleanButton.text = "扫描后可一键清理"
        binding.selectionText.text = "扫描后保存快照，再一键清理"
        renderActionState()
    }

    private fun setTaskUi(active: Boolean) {
        binding.progressIndicator.visibility = if (active) View.VISIBLE else View.GONE
        binding.scanButton.isEnabled = !active && cacheService != null
        binding.cancelButton.isEnabled = active
        binding.cleanButton.isEnabled = !active && quickCleanReady && moduleService != null
        binding.previousButton.isEnabled = !active && page > 0
        binding.nextButton.isEnabled = !active && page + 1 < pageCount()
        adapter.setInteractionEnabled(false)
    }

    private fun renderActionState() {
        binding.scanButton.isEnabled = !running && cacheService != null
        binding.cancelButton.isEnabled = running
        binding.cleanButton.isEnabled = !running && quickCleanReady && moduleService != null
    }

    private fun pageCount(): Int = ceil(total / PAGE_SIZE.toDouble()).toInt().coerceAtLeast(1)

    override fun onDestroy() {
        pollJob?.cancel()
        if (cacheBindingRequested) runCatching { RootService.unbind(cacheConnection) }
        if (moduleBindingRequested) runCatching { RootService.unbind(moduleConnection) }
        super.onDestroy()
    }

    companion object {
        private const val PAGE_SIZE = 30
    }
}
