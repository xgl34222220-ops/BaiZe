package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.text.format.Formatter
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.databinding.ActivitySmartScanBinding
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.BaiZeRootService
import io.github.xgl34222220.baize.root.IBaiZeRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    private var cacheSnapshotId = ""
    private var cacheCount = 0
    private var safeSnapshotId = ""
    private var safeCount = 0
    private var totalSafe = 0

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
        binding.cleanAllButton.setOnClickListener { confirmCleanSnapshots() }
        binding.cancelButton.setOnClickListener {
            cacheService?.cancelCurrentTask()
            profileService?.cancelCurrentTask()
            binding.summaryText.text = "正在请求安全停止…"
        }
        binding.detailsButton.setOnClickListener {
            val show = binding.overviewSection.visibility != View.VISIBLE
            binding.overviewSection.visibility = if (show) View.VISIBLE else View.GONE
            binding.detailsButton.text = if (show) "收起详情" else "查看详情"
        }
        bindServices()
    }

    private fun bindServices() {
        runCatching {
            RootService.bind(Intent(this, BaiZeRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE), cacheConnection)
            cacheBound = true
        }.onFailure { binding.statusText.text = it.message ?: "缓存引擎连接失败" }
        runCatching {
            RootService.bind(Intent(this, BaiZeProfileRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE), profileConnection)
            profileBound = true
        }.onFailure { binding.statusText.text = it.message ?: "分类引擎连接失败" }
    }

    private fun updateConnectionState() {
        val ready = cacheService != null && profileService != null
        binding.startButton.isEnabled = ready && !running
        binding.cleanAllButton.isEnabled = ready && !running && totalSafe > 0
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
        resetSnapshots()
        running = true
        renderRunning(true)
        binding.overviewSection.visibility = View.VISIBLE
        binding.detailsButton.visibility = View.GONE
        binding.cacheResultText.text = "应用缓存 · 正在扫描"
        binding.emptyResultText.text = "空项目 · 等待扫描"
        binding.rulesResultText.text = "规则垃圾 · 等待扫描"
        binding.fragmentsResultText.text = "残留碎片 · 等待扫描"
        binding.summaryText.text = "正在扫描应用缓存…"
        val started = SystemClock.elapsedRealtime()

        lifecycleScope.launch {
            var failed = 0
            try {
                val whitelist = preferences.getStringSet("package_whitelist", emptySet()).orEmpty()
                val (cacheJson, safeJson) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val cacheJob = async { JSONObject(cache.scanCandidates(JSONArray(whitelist.toList()).toString())) }
                        val safeJob = async { JSONObject(profiles.scanProfile("safe", optionsJson())) }
                        cacheJob.await() to safeJob.await()
                    }
                }
                if (cacheJson.has("error")) failed++ else {
                    cacheSnapshotId = cacheJson.optString("snapshotId")
                    cacheCount = cacheJson.optInt("totalCandidates") - cacheJson.optInt("whitelisted")
                    cacheCount = cacheCount.coerceAtLeast(0)
                    totalSafe += cacheCount
                }
                binding.cacheResultText.text = categorySummary("应用缓存", cacheCount, cacheJson.optLong("elapsedMs"))
                if (cacheJson.optBoolean("cancelled")) return@launch

                if (safeJson.has("error")) {
                    failed++
                    val reason = safeJson.optString("message", "扫描失败")
                    binding.emptyResultText.text = "空项目 · $reason"
                    binding.rulesResultText.text = "规则垃圾 · $reason"
                    binding.fragmentsResultText.text = "残留碎片 · $reason"
                } else {
                    safeSnapshotId = safeJson.optString("snapshotId")
                    safeCount = (safeJson.optInt("low") + safeJson.optInt("medium")).coerceAtLeast(0)
                    totalSafe += safeCount
                    val elapsedSafe = safeJson.optLong("elapsedMs")
                    binding.emptyResultText.text = categorySummary("空项目", safeJson.optInt("emptyFiles") + safeJson.optInt("emptyDirs"), elapsedSafe)
                    binding.rulesResultText.text = categorySummary("规则垃圾", safeJson.optInt("ruleTargets"), elapsedSafe)
                    binding.fragmentsResultText.text = categorySummary("残留碎片", safeJson.optInt("fragmentFiles"), elapsedSafe)
                }

                val cancelled = cacheJson.optBoolean("cancelled") || safeJson.optBoolean("cancelled")
                binding.summaryText.text = buildString {
                    append(if (cancelled) "智能扫描已停止" else "智能扫描完成")
                    append(" · ${SystemClock.elapsedRealtime() - started}ms\n")
                    append("四类共发现 $totalSafe 项可安全清理内容")
                    if (failed > 0) append(" · $failed 类扫描异常")
                    if (!cancelled) append("\n共享存储只遍历一次；一键清理直接消费本次快照。")
                }
                binding.cleanAllButton.text = if (totalSafe > 0) "一键清理 $totalSafe 项" else "没有可清理项目"
                binding.cleanAllButton.visibility = if (totalSafe > 0) View.VISIBLE else View.GONE
                binding.detailsButton.visibility = if (totalSafe > 0) View.VISIBLE else View.GONE
                binding.startButton.text = "重新扫描"
            } catch (error: Throwable) {
                binding.summaryText.text = "智能扫描失败：${error.message ?: error.javaClass.simpleName}"
            } finally {
                running = false
                renderRunning(false)
                updateConnectionState()
            }
        }
    }

    private fun confirmCleanSnapshots() {
        if (running || totalSafe <= 0) return
        AlertDialog.Builder(this)
            .setTitle("一键清理 $totalSafe 项")
            .setMessage("直接清理刚才生成的应用缓存与安全项目快照，不进入二级页面，也不会重新扫描。清理前仍会逐项复核白名单、路径、软链接、挂载点与大文件限制。")
            .setNegativeButton("取消", null)
            .setPositiveButton("立即清理") { _, _ -> cleanSnapshots() }
            .show()
    }

    private fun cleanSnapshots() {
        val cache = cacheService ?: return
        val profiles = profileService ?: return
        if (running) return
        running = true
        renderRunning(true)
        binding.summaryText.text = "正在使用智能扫描快照一键清理…"
        val started = SystemClock.elapsedRealtime()

        lifecycleScope.launch {
            var deletedBytes = 0L
            var deletedFiles = 0L
            var deletedDirectories = 0L
            var deletedEmptyFiles = 0L
            var deletedEmptyDirectories = 0L
            var deletedFragments = 0L
            var cleaned = 0
            var failures = 0
            var cancelled = false
            try {
                val selectAll = JSONObject().put("__all_safe__", true).toString()
                val whitelist = preferences.getStringSet("package_whitelist", emptySet()).orEmpty()
                if (cacheSnapshotId.isNotBlank() && cacheCount > 0) {
                    binding.summaryText.text = "正在清理应用缓存快照…"
                    val json = JSONObject(withContext(Dispatchers.IO) {
                        cache.cleanSelected(cacheSnapshotId, selectAll, JSONArray(whitelist.toList()).toString())
                    })
                    deletedBytes += json.optLong("deletedBytes")
                    deletedFiles += json.optLong("deletedFiles")
                    deletedDirectories += json.optLong("deletedDirectories")
                    cleaned += json.optInt("cleanedCandidates")
                    failures += json.optInt("failures")
                    cancelled = json.optBoolean("cancelled")
                }
                if (!cancelled) {
                    if (safeSnapshotId.isNotBlank() && safeCount > 0) {
                        binding.summaryText.text = "正在清理安全项目快照…"
                        val json = JSONObject(withContext(Dispatchers.IO) {
                            profiles.cleanProfileSelected(safeSnapshotId, selectAll, optionsJson())
                        })
                        deletedBytes += json.optLong("deletedBytes")
                        deletedFiles += json.optLong("deletedFiles")
                        deletedDirectories += json.optLong("deletedDirectories")
                        val details = json.optJSONArray("details") ?: JSONArray()
                        for (index in 0 until details.length()) {
                            val item = details.optJSONObject(index) ?: continue
                            when (item.optString("profile")) {
                                "empty" -> {
                                    deletedEmptyFiles += item.optLong("files")
                                    deletedEmptyDirectories += item.optLong("directories")
                                }
                                "fragments" -> deletedFragments += item.optLong("files")
                            }
                        }
                        cleaned += json.optInt("cleanedCandidates")
                        failures += json.optInt("failures")
                        cancelled = json.optBoolean("cancelled")
                    }
                }
                val report = buildString {
                    append(if (cancelled) "一键清理已停止" else "一键清理完成")
                    append(" · ${SystemClock.elapsedRealtime() - started}ms\n")
                    append("释放 ${Formatter.formatFileSize(this@SmartScanActivity, deletedBytes)}")
                    append(" · 已处理 $cleaned 项 · 文件 $deletedFiles · 目录 $deletedDirectories")
                    if (failures > 0) append(" · 未清理 $failures")
                }
                binding.summaryText.text = report
                preferences.edit()
                    .putString("last_report_text", report)
                    .putLong("last_clean_bytes", deletedBytes)
                    .apply()
                runCatching {
                    withContext(Dispatchers.IO) {
                        profiles.recordNativeTask(
                            JSONObject()
                                .put("mode", "smart-clean")
                                .put("success", !cancelled)
                                .put("cancelled", cancelled)
                                .put("bytes", deletedBytes)
                                .put("files", deletedFiles)
                                .put("emptyFiles", deletedEmptyFiles)
                                .put("emptyDirs", deletedEmptyDirectories)
                                .put("fragments", deletedFragments)
                                .put("errors", failures)
                                .put("elapsedSeconds", (SystemClock.elapsedRealtime() - started) / 1000L)
                                .put("result", report.substringBefore('\n'))
                                .toString()
                        )
                    }
                }
                NativeNotifier.showTaskResult(
                    this@SmartScanActivity,
                    if (cancelled) "白泽一键清理已停止" else "白泽一键清理完成",
                    "释放 ${Formatter.formatFileSize(this@SmartScanActivity, deletedBytes)}",
                    report
                )
                resetSnapshots()
                binding.cleanAllButton.visibility = View.GONE
                binding.detailsButton.visibility = View.GONE
                binding.startButton.text = "再次扫描"
            } catch (error: Throwable) {
                binding.summaryText.text = "一键清理失败：${error.message ?: error.javaClass.simpleName}"
            } finally {
                running = false
                renderRunning(false)
                updateConnectionState()
            }
        }
    }

    private fun renderRunning(value: Boolean) {
        binding.progressIndicator.visibility = if (value) View.VISIBLE else View.GONE
        binding.startButton.isEnabled = !value && cacheService != null && profileService != null
        binding.cleanAllButton.isEnabled = !value && totalSafe > 0
        binding.cancelButton.visibility = if (value) View.VISIBLE else View.GONE
        binding.cancelButton.isEnabled = value
    }

    private fun resetSnapshots() {
        cacheSnapshotId = ""
        cacheCount = 0
        safeSnapshotId = ""
        safeCount = 0
        totalSafe = 0
    }

    private fun categorySummary(title: String, count: Int, elapsed: Long): String = "$title · $count 项 · ${elapsed}ms"

    private fun optionsJson(): String {
        val whitelist = preferences.getStringSet("package_whitelist", emptySet()).orEmpty()
        val pathWhitelist = preferences.getStringSet("path_whitelist", emptySet()).orEmpty()
        val maxMb = preferences.getFloat("large_file_mb", 512f).toLong().coerceIn(64L, 16_384L)
        return JSONObject()
            .put("whitelistPackages", JSONArray(whitelist.toList()))
            .put("whitelistPaths", JSONArray(pathWhitelist.toList()))
            .put("maxFileBytes", maxMb * 1024L * 1024L)
            .put("fragmentDays", preferences.getInt("fragment_days", 7).coerceIn(0, 365))
            .put("allowHighRisk", false)
            .toString()
    }

    override fun onDestroy() {
        if (cacheBound) runCatching { RootService.unbind(cacheConnection) }
        if (profileBound) runCatching { RootService.unbind(profileConnection) }
        super.onDestroy()
    }
}
