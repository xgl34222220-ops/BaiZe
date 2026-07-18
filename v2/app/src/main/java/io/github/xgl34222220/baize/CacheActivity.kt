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
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cacheService = null
            cacheBindingRequested = false
            running = false
            pollJob?.cancel()
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
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            moduleService = null
            moduleBindingRequested = false
            running = false
            pollJob?.cancel()
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
        binding.safetyText.text = "扫描后会自动纳入全部安全缓存，无需逐项勾选；白名单、标准路径、软链接、挂载点和大文件限制仍会在删除前再次校验。"

        adapter = ProfileCandidateAdapter { _, _ -> }
        adapter.setInteractionEnabled(false)
        binding.resultsList.layoutManager = LinearLayoutManager(this)
        binding.resultsList.adapter = adapter

        binding.backButton.setOnClickListener { finish() }
        binding.scanButton.setOnClickListener { scan() }
        binding.cancelButton.setOnClickListener {
            cacheService?.cancelCurrentTask()
            moduleService?.cancelCurrentTask()
            binding.summaryText.text = "正在安全停止当前任务…"
        }
        binding.cleanButton.setOnClickListener { confirmQuickClean() }
        binding.previousButton.setOnClickListener { loadPage(page - 1) }
        binding.nextButton.setOnClickListener { loadPage(page + 1) }

        binding.scanButton.text = "扫描缓存明细"
        binding.cleanButton.text = "扫描后可一键清理"
        binding.statusText.text = "正在连接 Root 扫描与自动清理引擎"
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
            binding.statusText.text = it.message ?: "缓存扫描服务启动失败"
        }
        runCatching {
            RootService.bind(moduleIntent(), moduleConnection)
            moduleBindingRequested = true
        }.onFailure {
            binding.statusText.text = it.message ?: "自动清理服务启动失败"
        }
    }

    private fun renderConnectionState() {
        binding.statusText.text = when {
            cacheService != null && moduleService != null -> "Root 扫描与一键清理引擎已连接"
            cacheService != null -> "缓存扫描已连接 · 正在连接一键清理"
            moduleService != null -> "一键清理已连接 · 正在连接缓存扫描"
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
        binding.summaryText.text = "正在发现真实且非空的应用缓存目录…"

        lifecycleScope.launch {
            val whitelist = preferences.getStringSet("package_whitelist", emptySet()).orEmpty()
            val result = runCatching {
                withContext(Dispatchers.IO) { root.scanCandidates(JSONArray(whitelist.toList()).toString()) }
            }
            running = false
            setTaskUi(false)
            result.onSuccess { raw ->
                val json = JSONObject(raw)
                if (json.has("error")) {
                    binding.summaryText.text = json.optString("message", "扫描失败")
                    return@onSuccess
                }
                if (json.optBoolean("cancelled")) {
                    binding.summaryText.text = "扫描已停止 · ${json.optLong("elapsedMs")}ms"
                    return@onSuccess
                }
                snapshotId = json.optString("snapshotId")
                total = json.optInt("totalCandidates")
                quickCleanReady = total > 0
                binding.summaryText.text = buildString {
                    append("扫描完成 · ${json.optLong("elapsedMs")}ms\n")
                    append("发现 $total 项真实非空缓存 · 白名单保护 ${json.optInt("whitelisted")} 项\n")
                    append("已自动选择全部安全缓存，直接点击一键清理即可。")
                }
                binding.cleanButton.text = if (total > 0) "一键清理全部缓存（$total 项）" else "没有可清理缓存"
                binding.selectionText.text = "全部安全缓存已自动纳入本次清理；列表仅用于查看明细。"
                binding.resultSection.visibility = if (total > 0) View.VISIBLE else View.GONE
                if (total > 0) loadPage(0)
                renderActionState()
            }.onFailure {
                binding.summaryText.text = "扫描失败：${it.message ?: it.javaClass.simpleName}"
            }
        }
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
                            note = "已自动选择",
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
                binding.selectionText.text = "共 $total 项 · 当前页 ${values.size} 项 · 已自动选择全部安全缓存"
                renderActionState()
            }.onFailure {
                binding.selectionText.text = "读取结果失败：${it.message}"
            }
        }
    }

    private fun confirmQuickClean() {
        if (!quickCleanReady || moduleService == null || running) return
        AlertDialog.Builder(this)
            .setTitle("一键清理全部安全缓存")
            .setMessage("将自动清理本次分类下所有通过二次校验的应用缓存，不需要逐项勾选。缓存根目录会保留，白名单、软链接、挂载点和异常路径会自动跳过。")
            .setNegativeButton("取消", null)
            .setPositiveButton("立即清理") { _, _ -> quickClean() }
            .show()
    }

    private fun quickClean() {
        val root = moduleService ?: return
        if (running) return
        running = true
        setTaskUi(true)
        binding.summaryText.text = "正在自动扫描并清理全部安全缓存…"
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
                val output = json.optString("output").lineSequence().filter { it.isNotBlank() }.takeLast(5).joinToString("\n")
                val report = buildString {
                    append(
                        when {
                            json.optBoolean("cancelled") -> "缓存清理已停止"
                            json.optBoolean("success") -> "全部安全缓存清理完成"
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
                quickCleanReady = false
                snapshotId = ""
                total = 0
                adapter.submitPage(emptyList())
                binding.resultSection.visibility = View.GONE
                binding.cleanButton.text = "扫描后可一键清理"
            }.onFailure {
                binding.summaryText.text = "清理失败：${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive && running) {
                val raw = runCatching { withContext(Dispatchers.IO) { moduleService?.getTaskState().orEmpty() } }.getOrNull()
                if (!raw.isNullOrBlank()) {
                    val json = runCatching { JSONObject(raw) }.getOrNull()
                    if (json != null && json.optBoolean("running")) {
                        binding.summaryText.text = buildString {
                            append(json.optString("phase", "正在自动清理缓存"))
                            val current = json.optInt("progress_current", json.optInt("current"))
                            val totalState = json.optInt("progress_total", json.optInt("total"))
                            if (totalState > 0) append(" · $current/$totalState")
                            val path = json.optString("current_path", json.optString("currentPath"))
                            if (path.isNotBlank()) append("\n").append(path.takeLast(92))
                        }
                    }
                }
                delay(400L)
            }
        }
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
