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

    private var service: IProfileRootService? = null
    private var bindingRequested = false
    private var taskRunning = false
    private var snapshotId = ""
    private var total = 0
    private var page = 0
    private var quickCleanReady = false
    private var pollJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IProfileRootService.Stub.asInterface(binder)
            bindingRequested = true
            renderConnected()
            recoverRemoteOrLatestState()
            renderActionState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bindingRequested = false
            taskRunning = false
            pollJob?.cancel()
            binding.statusText.text = "Root 服务已断开"
            renderActionState()
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

        adapter = ProfileCandidateAdapter { _, _ -> }
        adapter.setInteractionEnabled(false)
        binding.resultsList.layoutManager = LinearLayoutManager(this)
        binding.resultsList.adapter = adapter

        binding.titleText.text = profileTitle(profile)
        binding.subtitleText.text = profileSubtitle(profile)
        binding.resultTitleText.text = "${profileTitle(profile)}明细"
        binding.safetyText.text = safetyDescription(profile)
        binding.scanButton.text = if (requiresModuleAuthorization()) "开始安全扫描" else "扫描清理明细"
        binding.cleanButton.text = "扫描后可一键清理"

        binding.backButton.setOnClickListener { finish() }
        binding.scanButton.setOnClickListener { scan() }
        binding.cancelButton.setOnClickListener {
            service?.cancelCurrentTask()
            binding.summaryText.text = "正在安全停止当前任务…"
        }
        binding.cleanButton.setOnClickListener { confirmQuickClean() }
        binding.previousButton.setOnClickListener { loadPage(page - 1) }
        binding.nextButton.setOnClickListener { loadPage(page + 1) }

        binding.statusText.text = "正在连接 Root 原生清理引擎"
        renderActionState()
        connect()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && service != null && !taskRunning) {
            recoverRemoteOrLatestState()
        }
    }

    private fun rootIntent(): Intent = Intent(this, BaiZeProfileRootService::class.java)
        .addCategory(RootService.CATEGORY_DAEMON_MODE)

    private fun connect() {
        runCatching {
            RootService.bind(rootIntent(), connection)
            bindingRequested = true
        }.onFailure {
            binding.statusText.text = it.message ?: "Root 服务启动失败"
        }
    }

    private fun renderConnected() {
        lifecycleScope.launch {
            val raw = runCatching { withContext(Dispatchers.IO) { service?.ping().orEmpty() } }.getOrNull()
            val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            val root = json?.optBoolean("root") == true
            val rules = json?.optBoolean("deepRules") == true
            binding.statusText.text = when {
                !root -> "服务已连接，但未获得完整 Root"
                profile == "deep" && !rules -> "Root 已连接，但完整规则库缺失"
                else -> "Root 扫描与一键清理引擎已连接"
            }
            renderActionState()
        }
    }

    private fun scan() {
        if (taskRunning || service == null) return
        if (requiresModuleAuthorization()) runAuthorizedModuleScan() else runNativeDetailScan()
    }

    private fun runAuthorizedModuleScan() {
        val root = service ?: return
        taskRunning = true
        quickCleanReady = false
        snapshotId = ""
        total = 0
        adapter.submitPage(emptyList())
        binding.resultSection.visibility = View.GONE
        setTaskUi(true)
        binding.summaryText.text = "正在扫描${profileTitle(profile)}并生成安全授权…"
        startPolling()

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { root.runModuleTask(scanMode(profile)) }
            }
            taskRunning = false
            pollJob?.cancel()
            setTaskUi(false)
            result.onSuccess { raw ->
                val json = JSONObject(raw)
                if (json.optString("error") == "busy") {
                    binding.summaryText.text = "检测到后台任务，正在恢复执行状态…"
                    recoverRemoteOrLatestState()
                    return@onSuccess
                }
                val output = json.optString("output").lineSequence().filter { it.isNotBlank() }.takeLast(6).joinToString("\n")
                val success = json.optBoolean("success")
                val latest = json.optJSONObject("latest") ?: JSONObject()
                val discovered = latest.optLong("files", latest.optLong("regular_files", 0L)).coerceAtLeast(0L)
                quickCleanReady = success && !json.optBoolean("cancelled") && discovered > 0L
                binding.summaryText.text = buildString {
                    append(
                        when {
                            json.optBoolean("cancelled") -> "扫描已停止"
                            success -> "${profileTitle(profile)}扫描完成"
                            else -> json.optString("message", "扫描失败")
                        }
                    )
                    append(" · ${json.optLong("elapsedMs")}ms")
                    if (output.isNotBlank()) append("\n").append(output)
                    if (quickCleanReady) append("\n发现 $discovered 项安全内容，可直接一键清理。")
                    else if (success && discovered == 0L) append("\n没有发现可清理的安全项目。")
                }
                binding.resultSection.visibility = if (quickCleanReady) View.VISIBLE else View.GONE
                binding.resultsList.visibility = View.GONE
                binding.pageText.visibility = View.GONE
                binding.previousButton.visibility = View.GONE
                binding.nextButton.visibility = View.GONE
                binding.selectionText.text = when (profile) {
                    "deep" -> "低风险与允许的中风险规则已准备；关键风险永远只审计。"
                    "corpses" -> "已核对当前安装包列表，确认的卸载残留可一键清理。"
                    else -> "安全扫描已完成。"
                }
                binding.cleanButton.text = quickCleanLabel(profile)
                renderActionState()
            }.onFailure {
                binding.summaryText.text = "扫描失败：${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    private fun runNativeDetailScan() {
        val root = service ?: return
        taskRunning = true
        quickCleanReady = false
        snapshotId = ""
        total = 0
        page = 0
        adapter.submitPage(emptyList())
        adapter.setInteractionEnabled(false)
        binding.resultsList.visibility = View.VISIBLE
        binding.pageText.visibility = View.VISIBLE
        binding.previousButton.visibility = View.VISIBLE
        binding.nextButton.visibility = View.VISIBLE
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
                quickCleanReady = total > 0
                binding.summaryText.text = buildString {
                    append("扫描完成 · ${json.optLong("elapsedMs")}ms\n")
                    append("发现 $total 项可处理内容")
                    val low = json.optInt("low")
                    val medium = json.optInt("medium")
                    if (low > 0) append(" · 低风险 $low")
                    if (medium > 0) append(" · 中风险 $medium")
                    append("\n已自动选择全部安全项，无需逐项勾选。")
                }
                binding.cleanButton.text = quickCleanLabel(profile, total)
                binding.selectionText.text = "全部安全项已自动纳入本次清理；列表仅用于查看路径与大小。"
                binding.resultSection.visibility = if (total > 0) View.VISIBLE else View.GONE
                if (total > 0) loadPage(0)
                renderActionState()
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
                    val risk = item.optString("risk", "low")
                    values.add(
                        ProfileCandidate(
                            id = item.optString("id"),
                            appName = item.optString("appName", item.optString("categoryLabel")),
                            packageName = item.optString("packageName"),
                            categoryLabel = item.optString("categoryLabel", "清理项目"),
                            risk = risk,
                            path = item.optString("path"),
                            bytes = item.optLong("bytes", -1L),
                            files = item.optLong("files", -1L),
                            directories = item.optLong("directories", -1L),
                            measured = item.optBoolean("measured"),
                            complete = item.optBoolean("complete"),
                            note = if (risk == "critical") "仅审计" else "已自动选择",
                            selected = risk != "critical"
                        )
                    )
                }
                page = targetPage
                adapter.submitPage(values)
                adapter.setInteractionEnabled(false)
                binding.pageText.text = "${page + 1} / $pages"
                binding.previousButton.isEnabled = page > 0
                binding.nextButton.isEnabled = page + 1 < pages
                binding.selectionText.text = "共 $total 项 · 当前页 ${values.size} 项 · 安全项已自动选择"
                renderActionState()
            }.onFailure {
                binding.selectionText.text = "读取结果失败：${it.message}"
            }
        }
    }

    private fun confirmQuickClean() {
        if (!quickCleanReady || service == null || taskRunning) return
        AlertDialog.Builder(this)
            .setTitle(quickCleanLabel(profile))
            .setMessage(confirmMessage(profile))
            .setNegativeButton("取消", null)
            .setPositiveButton("立即清理") { _, _ -> quickClean() }
            .show()
    }

    private fun quickClean() {
        val root = service ?: return
        if (taskRunning) return
        taskRunning = true
        setTaskUi(true)
        binding.summaryText.text = "正在一键清理${profileTitle(profile)}…"
        startPolling()

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { root.runModuleTask(cleanMode(profile)) }
            }
            taskRunning = false
            pollJob?.cancel()
            setTaskUi(false)
            result.onSuccess { raw ->
                val json = JSONObject(raw)
                val output = json.optString("output").lineSequence().filter { it.isNotBlank() }.takeLast(6).joinToString("\n")
                val report = buildString {
                    append(
                        when {
                            json.optBoolean("cancelled") -> "任务已停止"
                            json.optBoolean("success") -> "${profileTitle(profile)}一键清理完成"
                            else -> json.optString("message", "清理失败")
                        }
                    )
                    append(" · ${json.optLong("elapsedMs")}ms")
                    if (output.isNotBlank()) append("\n").append(output)
                }
                binding.summaryText.text = report
                preferences.edit().putString("last_report_text", report).apply()
                NativeNotifier.showTaskResult(
                    this@ProfileActivity,
                    if (json.optBoolean("success")) "白泽${profileTitle(profile)}清理完成" else "白泽清理任务结束",
                    json.optString("message", "${profileTitle(profile)}任务已结束"),
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

    private fun recoverRemoteOrLatestState() {
        val root = service ?: return
        if (!requiresModuleAuthorization() || taskRunning) return
        lifecycleScope.launch {
            val task = runCatching {
                withContext(Dispatchers.IO) { JSONObject(root.getTaskState()) }
            }.getOrNull()
            if (task?.optBoolean("running") == true) {
                taskRunning = true
                quickCleanReady = false
                binding.resultSection.visibility = View.GONE
                setTaskUi(true)
                renderRemoteTaskState(task)
                startRecoveryPolling()
            } else {
                restoreAuthorizedScanResult()
            }
        }
    }

    private fun startRecoveryPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive && taskRunning) {
                val task = runCatching {
                    withContext(Dispatchers.IO) { service?.getTaskState()?.let(::JSONObject) }
                }.getOrNull()
                if (task?.optBoolean("running") == true) {
                    renderRemoteTaskState(task)
                    delay(500L)
                    continue
                }

                taskRunning = false
                setTaskUi(false)
                restoreAuthorizedScanResult()
                break
            }
        }
    }

    private fun renderRemoteTaskState(json: JSONObject) {
        binding.summaryText.text = buildString {
            append(json.optString("phase", "后台任务正在执行"))
            val current = json.optInt("progress_current", json.optInt("current"))
            val totalState = json.optInt("progress_total", json.optInt("total"))
            if (totalState > 0) append(" · $current/$totalState")
            val path = json.optString("current_path", json.optString("currentPath"))
            if (path.isNotBlank()) append("\n").append(path.takeLast(92))
            if (json.optBoolean("cancelRequested")) append("\n正在安全停止…")
        }
    }

    private suspend fun restoreAuthorizedScanResult() {
        if (!requiresModuleAuthorization()) return
        val root = service ?: return
        val state = runCatching {
            withContext(Dispatchers.IO) { JSONObject(root.getModuleState()) }
        }.getOrNull() ?: return
        val latest = state.optJSONObject("latest") ?: return
        if (latest.optString("mode") != scanMode(profile)) {
            renderActionState()
            return
        }

        val files = latest.optLong("files", latest.optLong("regular_files", 0L)).coerceAtLeast(0L)
        val errors = latest.optLong("errors", 0L).coerceAtLeast(0L)
        val result = latest.optString("result").trim()
        quickCleanReady = files > 0L
        binding.resultsList.visibility = View.GONE
        binding.pageText.visibility = View.GONE
        binding.previousButton.visibility = View.GONE
        binding.nextButton.visibility = View.GONE
        binding.resultSection.visibility = if (quickCleanReady) View.VISIBLE else View.GONE
        binding.cleanButton.text = if (quickCleanReady) quickCleanLabel(profile, files.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        else "扫描后可一键清理"
        binding.selectionText.text = when (profile) {
            "deep" -> "已恢复最近一次深度扫描授权；只会清理低风险与允许的中风险项目。"
            "corpses" -> "已恢复最近一次卸载残留扫描授权；删除前会再次核对安装状态。"
            else -> "已恢复最近一次安全扫描结果。"
        }
        binding.summaryText.text = buildString {
            append("已恢复最近一次${profileTitle(profile)}扫描结果")
            if (files > 0L) append("\n发现 $files 项，可直接一键清理")
            else append("\n没有发现可清理项目")
            if (errors > 0L) append(" · 异常 $errors")
            if (result.isNotBlank()) append("\n").append(result)
        }
        renderActionState()
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
                            val current = json.optInt("progress_current", json.optInt("current"))
                            val totalState = json.optInt("progress_total", json.optInt("total"))
                            if (totalState > 0) append(" · $current/$totalState")
                            val path = json.optString("current_path", json.optString("currentPath"))
                            if (path.isNotBlank()) append("\n").append(path.takeLast(92))
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
        binding.cleanButton.isEnabled = !running && quickCleanReady && service != null
        binding.previousButton.isEnabled = !running && page > 0
        binding.nextButton.isEnabled = !running && page + 1 < pageCount()
        adapter.setInteractionEnabled(false)
    }

    private fun renderActionState() {
        binding.scanButton.isEnabled = !taskRunning && service != null
        binding.cleanButton.isEnabled = !taskRunning && quickCleanReady && service != null
    }

    private fun requiresModuleAuthorization(): Boolean = profile == "deep" || profile == "corpses"

    private fun scanMode(profile: String): String = when (profile) {
        "deep" -> "deep-scan"
        "corpses" -> "corpse-scan"
        else -> "scan"
    }

    private fun cleanMode(profile: String): String = when (profile) {
        "empty" -> "empty-clean"
        "rules" -> "rules-clean"
        "fragments" -> "fragment-clean"
        "deep" -> "deep-clean"
        "corpses" -> "corpse-clean"
        else -> "clean"
    }

    private fun quickCleanLabel(profile: String, count: Int = 0): String {
        val suffix = if (count > 0) "（$count 项）" else ""
        return when (profile) {
            "empty" -> "一键清理全部空项目$suffix"
            "rules" -> "一键清理全部安全规则$suffix"
            "fragments" -> "一键清理全部过期碎片$suffix"
            "deep" -> "一键清理深度安全项"
            "corpses" -> "一键清理确认的卸载残留"
            else -> "一键清理全部安全项$suffix"
        }
    }

    private fun confirmMessage(profile: String): String = when (profile) {
        "deep" -> "将清理最近一次深度扫描中通过授权的低风险与允许的中风险项目。关键风险永远只审计，规则变化后授权会立即失效。"
        "corpses" -> "将清理最近一次扫描确认的 Android/data 与 Android/obb 卸载残留。删除前会再次查询安装包列表，已重新安装的应用会自动跳过。"
        else -> "将自动清理本分类中全部通过二次校验的安全项，不需要逐项勾选。白名单、软链接、挂载点、异常路径与大文件限制仍会自动保护。"
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
            "deep" -> "深度清理"
            "corpses" -> "卸载残留"
            else -> "清理项目"
        }

        fun profileSubtitle(profile: String): String = when (profile) {
            "empty" -> "扫描空文件与空目录，自动保护占位文件和常用媒体目录"
            "rules" -> "清理隐藏垃圾、系统/OEM 日志与扩展规则路径"
            "fragments" -> "清理超过保留期的临时文件、旋转日志和中断下载"
            "deep" -> "使用 4,714 条有效规则扫描，安全项目可一键清理"
            "corpses" -> "清理已卸载应用留在 Android/data 与 Android/obb 的目录"
            else -> ""
        }

        private fun safetyDescription(profile: String): String = when (profile) {
            "deep" -> "只需扫描一次，随后可一键清理安全规则；关键风险永远只审计，高风险不会混入普通自动清理。"
            "corpses" -> "只需扫描一次，随后可一键清理全部确认残留；应用重新安装后会在删除前自动跳过。"
            else -> "扫描后会自动选择全部安全项，无需逐项勾选；列表只用于查看明细，删除前仍会进行完整安全校验。"
        }
    }
}
