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

class ProfileActivity : AppCompatActivity() {
    private lateinit var binding: ActivityProfileBinding
    private lateinit var adapter: ProfileCandidateAdapter

    private val profile by lazy { intent.getStringExtra(EXTRA_PROFILE).orEmpty() }
    private val preferences by lazy { getSharedPreferences("baize_v2", MODE_PRIVATE) }
    private val selection = LinkedHashMap<String, Boolean>()

    private var service: IProfileRootService? = null
    private var bindingRequested = false
    private var taskRunning = false
    private var snapshotId = ""
    private var total = 0
    private var page = 0
    private var pollJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IProfileRootService.Stub.asInterface(binder)
            bindingRequested = true
            renderConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bindingRequested = false
            taskRunning = false
            pollJob?.cancel()
            renderDisconnected("Root 服务已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (profile !in SUPPORTED_PROFILES) {
            finish()
            return
        }
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ProfileCandidateAdapter { item, checked ->
            selection[item.id] = checked
            renderSelection()
        }
        binding.resultsList.layoutManager = LinearLayoutManager(this)
        binding.resultsList.adapter = adapter

        binding.titleText.text = profileTitle(profile)
        binding.subtitleText.text = profileSubtitle(profile)
        binding.safetyText.text = safetyDescription(profile)
        binding.backButton.setOnClickListener { finish() }
        binding.scanButton.setOnClickListener { scan() }
        binding.cancelButton.setOnClickListener {
            service?.cancelCurrentTask()
            binding.summaryText.text = "正在请求安全停止…"
        }
        binding.cleanButton.setOnClickListener { confirmClean() }
        binding.previousButton.setOnClickListener { loadPage(page - 1) }
        binding.nextButton.setOnClickListener { loadPage(page + 1) }

        renderDisconnected("正在连接 Root 服务…")
        connect()
    }

    private fun rootIntent(): Intent = Intent(this, BaiZeProfileRootService::class.java)
        .addCategory(RootService.CATEGORY_DAEMON_MODE)

    private fun connect() {
        if (service != null) {
            renderConnected()
            return
        }
        binding.statusText.text = "正在请求 Root 权限并启动原生引擎"
        binding.scanButton.isEnabled = false
        runCatching {
            RootService.bind(rootIntent(), connection)
            bindingRequested = true
        }.onFailure { renderDisconnected(it.message ?: "Root 服务启动失败") }
    }

    private fun renderConnected() {
        binding.scanButton.isEnabled = true
        lifecycleScope.launch {
            val raw = runCatching { withContext(Dispatchers.IO) { service?.ping().orEmpty() } }.getOrNull()
            val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            val root = json?.optBoolean("root") == true
            val rules = json?.optBoolean("deepRules") == true
            binding.statusText.text = when {
                !root -> "服务已连接，但未获得完整 Root"
                profile == "deep" && !rules -> "Root 已连接，但模块规则库缺失"
                else -> "Root 原生引擎已连接"
            }
        }
    }

    private fun renderDisconnected(message: String) {
        binding.statusText.text = message
        binding.scanButton.isEnabled = false
        binding.cleanButton.isEnabled = false
        binding.cancelButton.isEnabled = false
    }

    private fun scan() {
        val root = service ?: return
        if (taskRunning) return
        taskRunning = true
        snapshotId = ""
        total = 0
        page = 0
        selection.clear()
        adapter.submitPage(emptyList())
        binding.resultSection.visibility = View.GONE
        setTaskUi(true)
        binding.summaryText.text = "正在扫描${profileTitle(profile)}…"
        startPolling()

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { root.scanProfile(profile, optionsJson(false)) }
            }
            taskRunning = false
            pollJob?.cancel()
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
                val summary = buildString {
                    append("扫描完成 · ${json.optLong("elapsedMs")}ms\n")
                    append("共 $total 项")
                    val low = json.optInt("low")
                    val medium = json.optInt("medium")
                    val high = json.optInt("high")
                    val critical = json.optInt("critical")
                    if (low > 0) append(" · 低风险 $low")
                    if (medium > 0) append(" · 中风险 $medium")
                    if (high > 0) append(" · 高风险 $high")
                    if (critical > 0) append(" · 关键审计 $critical")
                    append("\n快照有效期 30 分钟，默认不勾选任何项目。")
                }
                binding.summaryText.text = summary
                binding.resultSection.visibility = if (total > 0) View.VISIBLE else View.GONE
                if (total > 0) loadPage(0)
            }.onFailure {
                binding.summaryText.text = "扫描失败：${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    private fun loadPage(targetPage: Int) {
        val root = service ?: return
        if (snapshotId.isBlank() || taskRunning) return
        val pages = pageCount()
        if (targetPage !in 0 until pages) return
        binding.progressIndicator.visibility = View.VISIBLE
        binding.previousButton.isEnabled = false
        binding.nextButton.isEnabled = false
        binding.cleanButton.isEnabled = false
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { root.getProfilePage(snapshotId, targetPage * PAGE_SIZE, PAGE_SIZE) }
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
                    val id = item.optString("id")
                    values.add(
                        ProfileCandidate(
                            id = id,
                            appName = item.optString("appName", item.optString("categoryLabel")),
                            packageName = item.optString("packageName"),
                            categoryLabel = item.optString("categoryLabel", "清理项目"),
                            risk = item.optString("risk", "low"),
                            path = item.optString("path"),
                            bytes = item.optLong("bytes", -1L),
                            files = item.optLong("files", -1L),
                            directories = item.optLong("directories", -1L),
                            measured = item.optBoolean("measured"),
                            complete = item.optBoolean("complete"),
                            note = item.optString("note"),
                            selected = selection[id] == true
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
        binding.cleanButton.isEnabled = selected > 0 && snapshotId.isNotBlank() && !taskRunning
    }

    private fun confirmClean() {
        val selected = selection.values.count { it }
        if (selected <= 0 || snapshotId.isBlank()) return
        val highRisk = profile == "deep" || profile == "corpses"
        AlertDialog.Builder(this)
            .setTitle("清理已选项目")
            .setMessage(buildString {
                append("本次只处理你明确勾选的 $selected 项。\n\n")
                append("服务端会重新验证扫描快照、路径、风险、白名单、软链接、挂载点和大文件限制，并按实际删除前后差值计算释放空间。")
                if (highRisk) append("\n\n此分类含高风险项目，确认后仅对已勾选且通过二次校验的项目临时授权。​")
            })
            .setNegativeButton("取消", null)
            .setPositiveButton("确认清理") { _, _ -> clean(highRisk) }
            .show()
    }

    private fun clean(allowHighRisk: Boolean) {
        val root = service ?: return
        val currentSnapshot = snapshotId
        if (currentSnapshot.isBlank() || taskRunning) return
        taskRunning = true
        adapter.setInteractionEnabled(false)
        setTaskUi(true)
        binding.summaryText.text = "正在提交安全清理任务…"
        startPolling()
        val selectedJson = JSONObject().apply {
            selection.forEach { (id, checked) -> put(id, checked) }
        }.toString()

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    root.cleanProfileSelected(currentSnapshot, selectedJson, optionsJson(allowHighRisk))
                }
            }
            taskRunning = false
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
                    append(if (json.optBoolean("cancelled")) "清理已停止" else if (json.optBoolean("timedOut")) "清理达到时间预算" else "清理完成")
                    append(" · ${json.optLong("elapsedMs")}ms\n")
                    append("实际释放 ${Formatter.formatFileSize(this@ProfileActivity, bytes)}")
                    append(" · 文件 ${json.optLong("deletedFiles")}")
                    append(" · 目录 ${json.optLong("deletedDirectories")}\n")
                    append("完成 ${json.optInt("cleanedCandidates")} · 跳过 ${json.optInt("skippedCandidates")} · 失败 ${json.optInt("failures")}")
                }
                binding.summaryText.text = report
                preferences.edit()
                    .putString("last_report_text", report)
                    .putLong("last_clean_bytes", bytes)
                    .apply()
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
            while (isActive && taskRunning) {
                val raw = runCatching { withContext(Dispatchers.IO) { service?.getTaskState().orEmpty() } }.getOrNull()
                if (!raw.isNullOrBlank()) {
                    val json = runCatching { JSONObject(raw) }.getOrNull()
                    if (json != null && json.optBoolean("running")) {
                        binding.summaryText.text = buildString {
                            append(json.optString("phase", "正在执行"))
                            val current = json.optInt("current")
                            val totalState = json.optInt("total")
                            if (totalState > 0) append(" · $current/$totalState")
                            if (json.optLong("deletedBytes") > 0) append("\n已释放 ${Formatter.formatFileSize(this@ProfileActivity, json.optLong("deletedBytes"))}")
                            val path = json.optString("currentPath")
                            if (path.isNotBlank()) append("\n$path")
                            if (json.optBoolean("cancelRequested")) append("\n正在安全停止…")
                        }
                    }
                }
                delay(400L)
            }
        }
    }

    private fun optionsJson(allowHighRisk: Boolean): String {
        val whitelist = preferences.getStringSet("package_whitelist", emptySet()).orEmpty()
        val pathWhitelist = preferences.getStringSet("path_whitelist", emptySet()).orEmpty()
        val maxMb = preferences.getFloat("large_file_mb", 512f).toLong().coerceIn(64L, 16_384L)
        val fragmentDays = preferences.getInt("fragment_days", 7).coerceIn(1, 365)
        return JSONObject()
            .put("whitelistPackages", JSONArray(whitelist.toList()))
            .put("whitelistPaths", JSONArray(pathWhitelist.toList()))
            .put("maxFileBytes", maxMb * 1024L * 1024L)
            .put("fragmentDays", fragmentDays)
            .put("allowHighRisk", allowHighRisk)
            .toString()
    }

    private fun setTaskUi(running: Boolean) {
        binding.progressIndicator.visibility = if (running) View.VISIBLE else View.GONE
        binding.scanButton.isEnabled = !running && service != null
        binding.cancelButton.isEnabled = running
        binding.cleanButton.isEnabled = !running && selection.values.any { it } && snapshotId.isNotBlank()
        binding.previousButton.isEnabled = !running && page > 0
        binding.nextButton.isEnabled = !running && page + 1 < pageCount()
    }

    private fun pageCount(): Int = ceil(total / PAGE_SIZE.toDouble()).toInt().coerceAtLeast(1)

    override fun onDestroy() {
        pollJob?.cancel()
        if (bindingRequested) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
        private const val PAGE_SIZE = 30
        private val SUPPORTED_PROFILES = setOf("empty", "rules", "fragments", "deep", "corpses")

        fun profileTitle(profile: String): String = when (profile) {
            "empty" -> "空项目"
            "rules" -> "规则垃圾"
            "fragments" -> "残留碎片"
            "deep" -> "深度规则"
            "corpses" -> "卸载残留"
            else -> "清理项目"
        }

        fun profileSubtitle(profile: String): String = when (profile) {
            "empty" -> "空文件与空目录，自动保护占位文件和常用媒体目录"
            "rules" -> "隐藏垃圾、系统/OEM 日志和扩展规则路径"
            "fragments" -> "默认只处理至少保留 7 天的临时文件、旋转日志和崩溃转储"
            "deep" -> "完整规则库分级扫描；关键风险只审计，不执行删除"
            "corpses" -> "识别已卸载应用在 Android/data 与 Android/obb 中的残留"
            else -> ""
        }

        private fun safetyDescription(profile: String): String = when (profile) {
            "deep" -> "默认不选择；关键风险只允许审计，高风险必须在清理确认中单次授权。规则 SHA 变化后快照立即失效。"
            "corpses" -> "默认不选择；删除前再次查询安装包列表，应用重新安装后会自动跳过。"
            else -> "默认不选择；清理前会重新验证路径、风险、白名单、软链接、挂载点和大文件限制。"
        }
    }
}
