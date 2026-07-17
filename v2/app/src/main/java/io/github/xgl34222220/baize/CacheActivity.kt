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

class CacheActivity : AppCompatActivity() {
    private lateinit var binding: ActivityProfileBinding
    private lateinit var adapter: ProfileCandidateAdapter
    private val preferences by lazy { getSharedPreferences("baize_v2", MODE_PRIVATE) }
    private val selection = LinkedHashMap<String, Boolean>()

    private var service: IBaiZeRootService? = null
    private var bindingRequested = false
    private var running = false
    private var snapshotId = ""
    private var total = 0
    private var page = 0
    private var pollJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IBaiZeRootService.Stub.asInterface(binder)
            bindingRequested = true
            binding.statusText.text = "Root 缓存引擎已连接"
            binding.scanButton.isEnabled = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bindingRequested = false
            running = false
            pollJob?.cancel()
            binding.statusText.text = "Root 服务已断开"
            binding.scanButton.isEnabled = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.titleText.text = "应用缓存"
        binding.subtitleText.text = "内部 cache、code_cache 与 Android/data 外部缓存"
        binding.safetyText.text = "默认不选择；只清空缓存根目录内部内容，删除前再次验证包名、标准路径、软链接、挂载点和白名单。"

        adapter = ProfileCandidateAdapter { item, checked ->
            selection[item.id] = checked
            renderSelection()
        }
        binding.resultsList.layoutManager = LinearLayoutManager(this)
        binding.resultsList.adapter = adapter

        binding.backButton.setOnClickListener { finish() }
        binding.scanButton.setOnClickListener { scan() }
        binding.cancelButton.setOnClickListener {
            service?.cancelCurrentTask()
            binding.summaryText.text = "正在请求安全停止…"
        }
        binding.cleanButton.setOnClickListener { confirmClean() }
        binding.previousButton.setOnClickListener { loadPage(page - 1) }
        binding.nextButton.setOnClickListener { loadPage(page + 1) }

        binding.statusText.text = "正在连接 Root 缓存引擎"
        binding.scanButton.isEnabled = false
        connect()
    }

    private fun rootIntent(): Intent = Intent(this, BaiZeRootService::class.java)
        .addCategory(RootService.CATEGORY_DAEMON_MODE)

    private fun connect() {
        runCatching {
            RootService.bind(rootIntent(), connection)
            bindingRequested = true
        }.onFailure {
            binding.statusText.text = it.message ?: "Root 服务启动失败"
        }
    }

    private fun scan() {
        val root = service ?: return
        if (running) return
        running = true
        snapshotId = ""
        total = 0
        page = 0
        selection.clear()
        adapter.submitPage(emptyList())
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
                binding.summaryText.text = buildString {
                    append("扫描完成 · ${json.optLong("elapsedMs")}ms\n")
                    append("真实非空缓存 $total 项 · 白名单保护 ${json.optInt("whitelisted")} 项\n")
                    append("快照有效期 30 分钟，默认不选择。")
                }
                binding.resultSection.visibility = if (total > 0) View.VISIBLE else View.GONE
                if (total > 0) loadPage(0)
            }.onFailure {
                binding.summaryText.text = "扫描失败：${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    private fun loadPage(targetPage: Int) {
        val root = service ?: return
        if (snapshotId.isBlank() || running) return
        val pages = pageCount()
        if (targetPage !in 0 until pages) return
        binding.progressIndicator.visibility = View.VISIBLE
        binding.cleanButton.isEnabled = false
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
                            note = "",
                            selected = selection[path] == true
                        )
                    )
                }
                page = targetPage
                adapter.submitPage(values)
                binding.pageText.text = "${page + 1} / $pages"
                binding.previousButton.isEnabled = page > 0
                binding.nextButton.isEnabled = page + 1 < pages
                renderSelection()
            }.onFailure {
                binding.selectionText.text = "读取结果失败：${it.message}"
            }
        }
    }

    private fun renderSelection() {
        val selected = selection.values.count { it }
        binding.selectionText.text = "共 $total 项 · 当前页已选 ${adapter.selectedOnPage()} 项 · 全部分页已选 $selected 项"
        binding.cleanButton.isEnabled = selected > 0 && snapshotId.isNotBlank() && !running
    }

    private fun confirmClean() {
        val selected = selection.values.count { it }
        if (selected <= 0) return
        AlertDialog.Builder(this)
            .setTitle("清理已选缓存")
            .setMessage("本次只清理你明确勾选的 $selected 项。缓存根目录会保留，清理前由 Root 服务重新验证全部安全边界。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认清理") { _, _ -> clean() }
            .show()
    }

    private fun clean() {
        val root = service ?: return
        if (snapshotId.isBlank() || running) return
        running = true
        adapter.setInteractionEnabled(false)
        setTaskUi(true)
        binding.summaryText.text = "正在提交安全清理任务…"
        startPolling()
        val selected = JSONObject().apply { selection.forEach { (path, checked) -> put(path, checked) } }.toString()
        val whitelist = preferences.getStringSet("package_whitelist", emptySet()).orEmpty()

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { root.cleanSelected(snapshotId, selected, JSONArray(whitelist.toList()).toString()) }
            }
            running = false
            pollJob?.cancel()
            adapter.setInteractionEnabled(true)
            setTaskUi(false)
            result.onSuccess { raw ->
                val json = JSONObject(raw)
                if (!json.optBoolean("success")) {
                    binding.summaryText.text = json.optString("message", "清理任务未执行")
                    return@onSuccess
                }
                val bytes = json.optLong("deletedBytes")
                val report = buildString {
                    append(if (json.optBoolean("cancelled")) "清理已停止" else "清理完成")
                    append(" · ${json.optLong("elapsedMs")}ms\n")
                    append("实际释放 ${Formatter.formatFileSize(this@CacheActivity, bytes)}")
                    append(" · 文件 ${json.optLong("deletedFiles")}")
                    append(" · 目录 ${json.optLong("deletedDirectories")}\n")
                    append("完成 ${json.optInt("cleanedCandidates")} · 跳过 ${json.optInt("skippedCandidates")} · 异常 ${json.optInt("failedCandidates")}")
                }
                binding.summaryText.text = report
                preferences.edit().putString("last_report_text", report).putLong("last_clean_bytes", bytes).apply()
                snapshotId = ""
                total = 0
                selection.clear()
                adapter.submitPage(emptyList())
                binding.resultSection.visibility = View.GONE
            }.onFailure {
                binding.summaryText.text = "清理失败：${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive && running) {
                val raw = runCatching { withContext(Dispatchers.IO) { service?.getTaskState().orEmpty() } }.getOrNull()
                if (!raw.isNullOrBlank()) {
                    val json = runCatching { JSONObject(raw) }.getOrNull()
                    if (json != null && json.optBoolean("running")) {
                        binding.summaryText.text = buildString {
                            append(json.optString("phase", "正在清理"))
                            if (json.optInt("total") > 0) append(" · ${json.optInt("current")}/${json.optInt("total")}")
                            append("\n已释放 ${Formatter.formatFileSize(this@CacheActivity, json.optLong("deletedBytes"))}")
                            val app = json.optString("currentApp")
                            if (app.isNotBlank()) append("\n$app")
                        }
                    }
                }
                delay(400L)
            }
        }
    }

    private fun setTaskUi(active: Boolean) {
        binding.progressIndicator.visibility = if (active) View.VISIBLE else View.GONE
        binding.scanButton.isEnabled = !active && service != null
        binding.cancelButton.isEnabled = active
        binding.cleanButton.isEnabled = !active && selection.values.any { it } && snapshotId.isNotBlank()
        binding.previousButton.isEnabled = !active && page > 0
        binding.nextButton.isEnabled = !active && page + 1 < pageCount()
    }

    private fun pageCount(): Int = ceil(total / PAGE_SIZE.toDouble()).toInt().coerceAtLeast(1)

    override fun onDestroy() {
        pollJob?.cancel()
        if (bindingRequested) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }

    companion object {
        private const val PAGE_SIZE = 30
    }
}
