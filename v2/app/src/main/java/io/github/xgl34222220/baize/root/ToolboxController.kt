package io.github.xgl34222220.baize.root

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Persistent Binder daemon: one queue, per-task schedules, completion records, and shared module lock. */
internal class ToolboxController(private val context: Context, private val coordinator: TaskCoordinator) {
    private val config = ToolboxConfig()
    private val pending = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var state = JSONObject().put("running", false)
    private val whitelist = WhitelistRepository()
    private val updates = ToolboxRuleUpdates(context)
    private val ruleDirectory by lazy { AppRuleStore.ensure(context) }
    private var lastEnvironment = JSONObject()
    private var environmentAt = 0L
    private var environmentMode = ""

    init { timer.scheduleWithFixedDelay({ runCatching { tick() } }, 30, 30, TimeUnit.SECONDS) }

    fun exchange(operation: String, args: JSONArray): String = runCatching {
        when (operation) {
            "toolboxSnapshot" -> snapshot()
            "toolboxSave" -> {
                check(!pending.get()) { "请等待当前任务结束后再修改设置" }
                val value = config.save(args.getString(0))
                JSONObject().put("success", true).put("config", value).put("message", "已保存，即刻生效")
            }
            "toolboxRun" -> start(args.getString(0), false)
            "toolboxTick" -> { tick(); JSONObject().put("success", true) }
            "toolboxCancel" -> { coordinator.cancelCurrentTask(); JSONObject().put("success", true).put("message", "正在停止，等待当前操作退出") }
            "toolboxResetStats" -> { config.resetStats(); JSONObject().put("success", true) }
            "toolboxClearHistory" -> { check(!pending.get()); config.clearHistory(); JSONObject().put("success", true) }
            "toolboxRulesUpdate", "toolboxRulesRollback", "toolboxRulesSave" -> {
                check(!pending.get() && !coordinator.isBusy()) { "清理任务正在运行，请稍后更新规则" }
                JSONObject(coordinator.runExclusive("toolbox-rules", "正在更新规则", "rules_update_failed") {
                    when (operation) {
                        "toolboxRulesUpdate" -> updates.update()
                        "toolboxRulesRollback" -> updates.rollback()
                        else -> saveCustomRules(args.getString(0))
                    }.toString()
                })
            }
            else -> throw IllegalArgumentException("未知控制台操作")
        }.toString()
    }.getOrElse { JSONObject().put("success", false).put("message", it.message ?: it.javaClass.simpleName).toString() }

    private fun snapshot(): JSONObject {
        val maintenance = ToolboxMaintenance(context, coordinator.cancelled, {})
        val settings = config.load()
        if (SystemClock.elapsedRealtime() - environmentAt > 30_000 || lastEnvironment.length() == 0 ||
            environmentMode != settings.optString("processMode")) {
            lastEnvironment = maintenance.environment(settings)
            environmentAt = SystemClock.elapsedRealtime()
            environmentMode = settings.optString("processMode")
        }
        val availability = JSONObject()
        ToolboxCatalog.extensions.forEach { availability.put(it.id, ToolboxAvailability.check(it.id, settings, lastEnvironment)) }
        val history = config.history()
        val recent = JSONArray()
        for (i in 0 until minOf(history.length(), 20)) recent.put(history.getJSONObject(i))
        val ruleDir = ruleDirectory
        return JSONObject().put("success", true).put("config", settings).put("availability", availability)
            .put("state", JSONObject(state.toString())).put("progress", JSONObject(coordinator.currentState()))
            .put("statistics", config.stats()).put("history", recent).put("device", maintenance.device())
            .put("rules", updates.state()).put("customRules", File(ruleDir, "custom.rules").readText().take(32000))
    }

    private fun start(request: String, scheduled: Boolean): JSONObject {
        val settings = config.load()
        val ids = if (request == "all") ToolboxCatalog.extensions.map { it.id }.filter {
            settings.getJSONObject("tasks").getJSONObject(it).optBoolean("enabled")
        } else request.split(',').distinct()
        require(ids.all { it in ToolboxCatalog.extensionIds || it in setOf("undo", "thaw", "unmount") }) { "此功能已合并回原有清理页面，请在原页面操作" }
        require(ids.isNotEmpty()) { "请先勾选要加入一键清理的功能" }
        if (coordinator.isBusy() || !pending.compareAndSet(false, true)) return JSONObject().put("success", false).put("message", "已有任务正在运行")
        val id = UUID.randomUUID().toString()
        state = JSONObject().put("running", true).put("phase", "已提交，等待执行").put("taskId", id)
        executor.execute {
            try {
                val result = JSONObject(coordinator.runExclusive("toolbox", "正在执行功能队列", "toolbox_failed") { started ->
                    withLease { execute(ids, settings, scheduled, id, started) }.toString()
                })
                if (!result.has("finishedAt")) {
                    result.put("finishedAt", System.currentTimeMillis()).put("taskId", id)
                    config.record(result, false)
                }
                state = JSONObject().put("running", false).put("taskId", id).put("result", result)
            } catch (error: Throwable) {
                val result = JSONObject().put("success", false).put("message", error.message.orEmpty())
                    .put("taskId", id).put("finishedAt", System.currentTimeMillis())
                runCatching { config.record(result, false) }
                state = JSONObject().put("running", false).put("taskId", id).put("result", result)
            } finally { pending.set(false) }
        }
        return JSONObject().put("success", true).put("accepted", true).put("taskId", id).put("message", "已提交 ${ids.size} 项任务")
    }

    private fun execute(ids: List<String>, settings: JSONObject, scheduled: Boolean, id: String, started: Long): JSONObject {
        val results = JSONArray()
        var bytes = 0L
        var files = 0L
        var failures = 0
        val environment = ToolboxMaintenance(context, coordinator.cancelled, {}).environment(settings)
        File(RootPaths.STATE_DIR, "stop").delete()
        for ((index, task) in ids.withIndex()) {
            if (coordinator.cancelled.get()) break
            val title = if (task == "undo") "撤销上次归类 / 转移" else if (task == "thaw") "恢复进程状态" else if (task == "unmount") "解除目录重定向" else ToolboxCatalog.task(task).title
            fun progress(path: String) {
                coordinator.update("toolbox", "$title · ${index + 1}/${ids.size}", index, ids.size, path, started, bytes, files, failures)
            }
            progress("")
            if (scheduled) config.markRun(task, System.currentTimeMillis())
            val result = runCatching {
                val available = ToolboxAvailability.check(task, settings, environment)
                if (!available.optBoolean("available")) return@runCatching available.put("success", false)
                when (task) {
                    "unmount" -> ToolboxRedirect(coordinator.cancelled).remove()
                    "wechat", "qq", "dy", "wyy" -> cacheTask(task, settings, ::progress)
                    "mounter", "undo" -> {
                        var rules = settings.optString(if (task == "mounter") "downloadRules" else "organizerRules")
                        if (task == "mounter") require(rules.isNotBlank()) { "请先填写下载转移规则" }
                        if (task == "mounter" && settings.optBoolean("bindRedirect")) {
                            rules = ToolboxRedirect(coordinator.cancelled).unmountedRules(rules)
                            if (rules.isBlank()) return@runCatching JSONObject().put("success", true).put("skipped", true).put("message", "目录重定向已生效")
                        }
                        val engine = FileOrganizerEngine(coordinator.cancelled, rulesText = rules, customOnly = task == "mounter")
                        if (task == "undo") JSONObject(engine.undo { progress(it.path) }) else {
                            val scan = JSONObject(engine.scan { progress(it.path) })
                            if (!scan.optBoolean("success") || coordinator.cancelled.get()) scan
                            else if (scan.optInt("total") == 0) JSONObject().put("success", true).put("skipped", true).put("message", "没有符合规则的文件")
                            else JSONObject(engine.apply(scan.getString("snapshotId"), "{\"all\":true,\"conflictPolicy\":\"rename\"}") { progress(it.path) })
                        }
                    }
                    else -> ToolboxMaintenance(context, coordinator.cancelled, ::progress).run(task, settings)
                }
            }.getOrElse { JSONObject().put("success", false).put("message", it.message ?: it.javaClass.simpleName) }
            if (task == "mounter" && settings.optBoolean("bindRedirect") && result.optBoolean("success") && !coordinator.cancelled.get()) {
                val mount = ToolboxRedirect(coordinator.cancelled).apply(settings.optString("downloadRules"))
                result.put("redirect", mount).put("success", mount.optBoolean("success")).put("message", mount.optString("message"))
            }
            if (coordinator.cancelled.get()) result.put("cancelled", true)
            if (result.optBoolean("cancelled") || result.optBoolean("timedOut") || result.optInt("failures") > 0) result.put("success", false)
            result.put("task", task).put("title", title).put("statusLabel", ToolboxAvailability.status(result))
            if (!result.optBoolean("success")) failures++
            bytes += result.optLong("deletedBytes").coerceAtLeast(0)
            files += result.optLong("deletedFiles").coerceAtLeast(0)
            // Retain concise detail samples. Full normal-cleaner logs stay in the existing audit flow.
            val details = result.optJSONArray("details")
            if (details != null && details.length() > 30) {
                result.put("detailCount", details.length()).put("details", JSONArray().apply { for (i in 0 until 30) put(details.get(i)) })
            }
            results.put(result)
        }
        val cancelled = coordinator.cancelled.get()
        val skipped = (0 until results.length()).count { results.getJSONObject(it).optBoolean("skipped") }
        val result = JSONObject().put("taskId", id).put("success", !cancelled && failures == 0)
            .put("cancelled", cancelled).put("completed", results.length()).put("requested", ids.size)
            .put("failures", failures).put("skipped", skipped).put("deletedBytes", bytes).put("deletedFiles", files).put("results", results)
            .put("scheduled", scheduled).put("elapsedMs", SystemClock.elapsedRealtime() - started).put("finishedAt", System.currentTimeMillis())
            .put("message", if (cancelled) "已停止，已处理 ${results.length()}/${ids.size} 项" else "处理 ${results.length()} 项，跳过 $skipped 项，未完成 $failures 项")
        config.record(result, settings.optBoolean("statistics", true))
        return result
    }

    private fun profileTask(profile: String, settings: JSONObject, packages: Set<String>, progress: (String) -> Unit): JSONObject {
        val options = JSONObject().put("whitelistPackages", JSONArray(whitelist.packagesJson()))
            .put("whitelistPaths", JSONArray(whitelist.pathsJson())).put("fragmentDays", settings.optInt("fragmentDays", 7))
            .put("maxAutoRisk", if (profile == "deep") "low" else "medium").put("targetPackages", JSONArray(packages.toList()))
        val engine = NativeProfileEngine(context, coordinator.cancelled)
        val scan = JSONObject(engine.scan(profile, options.toString()) { progress(it.path) })
        if (!scan.optBoolean("success") || coordinator.cancelled.get()) return scan
        if (scan.optInt("low") + (if (profile == "deep") 0 else scan.optInt("medium")) == 0)
            return JSONObject().put("success", true).put("skipped", true).put("message", "没有可自动清理的项目")
        return JSONObject(engine.clean(scan.getString("snapshotId"), "{\"__all_safe__\":true}", options.toString()) { progress(it.path) })
    }
    private fun cacheTask(task: String, settings: JSONObject, progress: (String) -> Unit): JSONObject {
        val engine = ForegroundCacheEngine(context, coordinator.cancelled)
        val white = whitelist.packagesJson()
        val group = ToolboxCatalog.appGroups.getValue(task)
        val snapshot = engine.scan(white, targetPackages = group) { _, _, _, path -> progress(path) }
        val paths = JSONArray(whitelist.pathsJson()).let { array -> (0 until array.length()).map { ToolboxFileRules.normalizePath(array.getString(it)) } }
        val selected = snapshot.items.filter { item ->
            item.packageName in group &&
                paths.none { val path = ToolboxFileRules.normalizePath(item.path); path == it || path.startsWith("$it/") || it.startsWith("$path/") }
        }
        if (coordinator.cancelled.get()) return JSONObject().put("success", false).put("cancelled", true)
        val result = engine.clean(snapshot.copy(items = selected), white) { _, _, _, path -> progress(path) }.json()
        if (!coordinator.cancelled.get()) {
            val deep = profileTask("deep", settings, group, progress)
            result.put("deep", deep).put("success", result.optBoolean("success") && deep.optBoolean("success") && deep.optInt("failures") == 0 && !deep.optBoolean("timedOut"))
                .put("deletedBytes", result.optLong("deletedBytes") + deep.optLong("deletedBytes"))
                .put("deletedFiles", result.optLong("deletedFiles") + deep.optLong("deletedFiles"))
        }
        if (result.optBoolean("success") && result.optLong("deletedBytes") == 0L && result.optLong("deletedFiles") == 0L)
            result.put("skipped", true).put("message", "没有符合条件的缓存文件")
        return result
    }
    private fun tick() {
        if (pending.get() || coordinator.isBusy()) return
        val settings = config.load()
        if (settings.optBoolean("screenOffOnly", true) && (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) return
        if (settings.optBoolean("chargingOnly")) {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if ((battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) == 0) return
        }
        val now = System.currentTimeMillis()
        val last = config.lastRuns()
        val due = ToolboxCatalog.extensions.filter {
            ToolboxConfig.due(settings.getJSONObject("tasks").getJSONObject(it.id), now, last.optLong(it.id)) ||
                (it.id == "process" && settings.optBoolean("processContinuous") && now >= last.optLong(it.id) &&
                    now - last.optLong(it.id) >= settings.optLong("pressureIntervalSeconds", 60) * 1000)
        }
        if (due.isNotEmpty()) start(due.joinToString(",") { it.id }, true)
    }
    private fun withLease(block: () -> JSONObject): JSONObject {
        val root = File(RootPaths.STATE_DIR).apply { mkdirs() }
        if (RuntimeTaskOwnership.isRunning(root)) return JSONObject().put("success", false).put("message", "后台模块任务正在运行")
        val lock = File(root, "run.lock")
        // Never delete someone else's stale-looking lock here: let the normal scheduler recover it.
        if (!lock.mkdir()) return JSONObject().put("success", false).put("message", "任务锁被占用，请稍后重试")
        val token = UUID.randomUUID().toString()
        try {
            File(lock, "pid").writeText("${Process.myPid()}\n")
            val stat = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(Regex("\\s+"))
            File(lock, "start_ticks").writeText(stat[19] + "\n")
            File(lock, "toolbox_token").writeText(token)
            File(root, "running.env").delete()
            return block()
        } finally {
            if (runCatching { File(lock, "toolbox_token").readText() == token }.getOrDefault(false)) lock.deleteRecursively()
        }
    }
    private fun saveCustomRules(raw: String): JSONObject {
        require(raw.length <= 32000) { "自定义规则最多 32 KB" }
        val lines = raw.lineSequence().map(String::trim).filter { it.isNotBlank() && !it.startsWith('#') }.toList()
        for (line in lines) {
            val columns = line.split('|')
            require(columns.size == 2 && columns[1].toIntOrNull() in 0..365 &&
                columns[0].startsWith('/') && columns[0].split('/').none { it in setOf(".", "..") } &&
                columns[0].none { it.isISOControl() || it == '\\' }) { "规则应为 绝对路径|保留天数（0–365）" }
        }
        val dir = AppRuleStore.ensure(context)
        RootFileStore.writeAtomic(File(dir, "custom.rules"), raw)
        RootFileStore.writeAtomic(File(dir, "custom.user"), "1")
        return JSONObject().put("success", true).put("message", "额外清理规则已保存，下一次规则扫描生效")
    }
}
