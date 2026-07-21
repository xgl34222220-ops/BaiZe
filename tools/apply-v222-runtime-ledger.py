from pathlib import Path

root = Path(__file__).resolve().parents[1]


def rep(path: str, old: str, new: str, count: int = 1) -> None:
    target = root / path
    text = target.read_text()
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"missing anchor: {path}: {old[:120]!r}")
    target.write_text(text.replace(old, new, count))


ledger_path = root / "v2/app/src/main/java/io/github/xgl34222220/baize/RuntimeTaskLedger.kt"
ledger_path.parent.mkdir(parents=True, exist_ok=True)
ledger_path.write_text(r'''package io.github.xgl34222220.baize

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small app-side task ledger used as a durable fallback when a Root daemon is restarting,
 * still running an older protocol, or temporarily cannot update history.tsv.
 */
object RuntimeTaskLedger {
    private const val PREFS = "baize_runtime_task_ledger_v1"
    private const val KEY_ENTRIES = "entries"
    private const val LIMIT = 50

    fun record(
        context: Context,
        title: String,
        result: String,
        bytes: Long,
        files: Long,
        emptyDirs: Long,
        errors: Long,
        cleaned: Boolean,
        trigger: String = "App 本地账本"
    ): List<HistoryUiItem> {
        val item = HistoryUiItem(
            title = title.take(120),
            time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
            trigger = trigger.take(80),
            result = result.replace('\n', ' ').replace('\r', ' ').take(500),
            bytes = bytes.coerceAtLeast(0L),
            files = files.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            emptyDirs = emptyDirs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            errors = errors.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            cleaned = cleaned
        )
        val merged = mergeLists(listOf(item), load(context))
        save(context, merged)
        return merged
    }

    fun load(context: Context): List<HistoryUiItem> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, "[]")
            .orEmpty()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val title = item.optString("title").trim()
                val time = item.optString("time").trim()
                if (title.isBlank() || time.isBlank()) continue
                add(
                    HistoryUiItem(
                        title = title,
                        time = time,
                        trigger = item.optString("trigger", "App 本地账本"),
                        result = item.optString("result"),
                        bytes = item.optLong("bytes", 0L).coerceAtLeast(0L),
                        files = item.optInt("files", 0).coerceAtLeast(0),
                        emptyDirs = item.optInt("emptyDirs", 0).coerceAtLeast(0),
                        errors = item.optInt("errors", 0).coerceAtLeast(0),
                        cleaned = item.optBoolean("cleaned", false)
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    fun merge(context: Context, remote: List<HistoryUiItem>): List<HistoryUiItem> =
        mergeLists(remote, load(context))

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ENTRIES).apply()
    }

    private fun save(context: Context, entries: List<HistoryUiItem>) {
        val array = JSONArray()
        entries.take(LIMIT).forEach { item ->
            array.put(
                JSONObject()
                    .put("title", item.title)
                    .put("time", item.time)
                    .put("trigger", item.trigger)
                    .put("result", item.result)
                    .put("bytes", item.bytes)
                    .put("files", item.files)
                    .put("emptyDirs", item.emptyDirs)
                    .put("errors", item.errors)
                    .put("cleaned", item.cleaned)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, array.toString())
            .apply()
    }

    private fun mergeLists(primary: List<HistoryUiItem>, secondary: List<HistoryUiItem>): List<HistoryUiItem> =
        (primary + secondary)
            .filter { it.title.isNotBlank() && it.time.isNotBlank() }
            .distinctBy { item ->
                "${item.time.take(16)}|${item.title}|${item.result}|${item.bytes}|${item.files}|${item.errors}"
            }
            .sortedByDescending { it.time }
            .take(LIMIT)
}
''')

activity = "v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt"
rep(
    activity,
    "        updateStorage()\n\n        setContent {",
    "        updateStorage()\n        dashboardState.value = dashboardState.value.copy(history = RuntimeTaskLedger.load(this))\n\n        setContent {"
)

rep(
    activity,
    '''            dashboardState.value = dashboardState.value.copy(
                running = false,
                recentJunk = junk,
                taskPhase = result
            )
            refreshHistory()
''',
    '''            dashboardState.value = dashboardState.value.copy(
                running = false,
                recentJunk = junk,
                taskPhase = result
            )
            appendRuntimeLog(
                title = historyModeTitle(mode),
                result = result,
                bytes = latest.optLong("bytes", 0L),
                files = junk.sumOf { it.files },
                emptyDirs = latest.optLong("empty_dirs", 0L),
                errors = latest.optLong("errors", if (success) 0L else 1L),
                cleaned = !mode.endsWith("scan")
            )
            refreshHistory()
'''
)

rep(
    activity,
    '''            preferences.edit()
                .putLong("last_clean_bytes", bytes)
                .putString("last_report_text", "$resultLine\\n$detailLine")
                .apply()
            notifyCleanResult(title, resultLine, detailLine, bytes)
''',
    '''            preferences.edit()
                .putLong("last_clean_bytes", bytes)
                .putString("last_report_text", "$resultLine\\n$detailLine")
                .apply()
            appendRuntimeLog(
                title = historyModeTitle("clean"),
                result = resultLine,
                bytes = bytes,
                files = files,
                emptyDirs = emptyDirs,
                errors = errors,
                cleaned = true
            )
            notifyCleanResult(title, resultLine, detailLine, bytes)
'''
)

old_persist = '''            withContext(Dispatchers.IO) {
                runCatching {
                    profiles.recordNativeTask(JSONObject()
                        .put("mode", "native-scan").put("success", successfulScan)
                        .put("cancelled", cancelled).put("bytes", knownBytes).put("files", total)
                        .put("emptyFiles", emptyFiles).put("emptyDirs", emptyDirs)
                        .put("fragments", fragments).put("errors", failures)
                        .put("elapsedSeconds", elapsed / 1000L).put("result", scanResultLine)
                        .put("categorySummary", scanCategories).toString())
                }
            }
            refreshHistory()
'''
new_persist = '''            val nativeTaskPayload = JSONObject()
                .put("mode", "native-scan").put("success", successfulScan)
                .put("cancelled", cancelled).put("bytes", knownBytes).put("files", total)
                .put("emptyFiles", emptyFiles).put("emptyDirs", emptyDirs)
                .put("fragments", fragments).put("errors", failures)
                .put("elapsedSeconds", elapsed / 1000L).put("result", scanResultLine)
                .put("categorySummary", scanCategories).toString()
            val persistenceError = persistNativeTaskWithRetry(profiles, nativeTaskPayload)
            val ledgerResult = if (persistenceError == null) {
                scanResultLine
            } else {
                "$scanResultLine；Root 记录失败，已保存到 App 本地账本"
            }
            appendRuntimeLog(
                title = historyModeTitle("native-scan"),
                result = ledgerResult,
                bytes = knownBytes,
                files = total.toLong(),
                emptyDirs = emptyDirs,
                errors = failures,
                cleaned = false
            )
            if (persistenceError != null) {
                dashboardState.value = dashboardState.value.copy(
                    taskPhase = "$scanResultLine\\n日志已保存在 App 本地；Root 持久化失败：$persistenceError"
                )
            }
            refreshHistory()
'''
rep(activity, old_persist, new_persist)

rep(
    activity,
    '''            preferences.edit()
                .putLong("last_clean_bytes", deletedBytes)
                .putString("last_report_text", "$resultLine\\n$detailLine")
                .apply()
            val recorder = profileEngine ?: rootService
''',
    '''            preferences.edit()
                .putLong("last_clean_bytes", deletedBytes)
                .putString("last_report_text", "$resultLine\\n$detailLine")
                .apply()
            appendRuntimeLog(
                title = historyModeTitle("snapshot-clean"),
                result = resultLine,
                bytes = deletedBytes,
                files = deletedFiles,
                emptyDirs = emptyDirs,
                errors = failures.toLong(),
                cleaned = true
            )
            val recorder = profileEngine ?: rootService
'''
)

old_refresh = '''    private fun refreshHistory() {
        val service = rootService ?: return
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { JSONObject(service.getTaskHistoryPage(0, 30)) }.getOrNull()
            } ?: return@launch
            if (!json.optBoolean("success")) return@launch
            val array = json.optJSONArray("entries")
            val entries = buildList {
                if (array != null) for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        HistoryUiItem(
                            title = historyModeTitle(item.optString("mode")),
                            time = item.optString("time"),
                            trigger = historyTrigger(item.optString("trigger")),
                            result = item.optString("result", if (item.optBoolean("cleaned")) "清理完成" else "扫描完成"),
                            bytes = item.optLong("bytes", 0L).coerceAtLeast(0L),
                            files = item.optInt("files", 0).coerceAtLeast(0),
                            emptyDirs = item.optInt("emptyDirs", 0).coerceAtLeast(0),
                            errors = item.optInt("errors", 0).coerceAtLeast(0),
                            cleaned = item.optBoolean("cleaned"),
                            categories = parseHistoryCategories(item.optJSONArray("categoryDetails")),
                            apps = parseHistoryApps(item.optJSONArray("appDetails"))
                        )
                    )
                }
            }
            dashboardState.value = dashboardState.value.copy(
                history = entries,
                lifetimeRuns = json.optLong("lifetimeRuns", json.optLong("cleanedRuns", 0L)).coerceAtLeast(0L),
                lifetimeReleased = json.optLong("lifetimeReleased", json.optLong("totalReleased", 0L)).coerceAtLeast(0L),
                lifetimeFiles = json.optLong("lifetimeFiles", 0L).coerceAtLeast(0L),
                lifetimeEmptyFiles = json.optLong("lifetimeEmptyFiles", 0L).coerceAtLeast(0L),
                lifetimeEmptyDirs = json.optLong("lifetimeEmptyDirs", 0L).coerceAtLeast(0L),
                lifetimeFragments = json.optLong("lifetimeFragments", 0L).coerceAtLeast(0L),
                lifetimeElapsed = json.optLong("lifetimeElapsed", 0L).coerceAtLeast(0L)
            )
        }
    }
'''
new_refresh = '''    private fun refreshHistory() {
        val service = rootService
        if (service == null) {
            dashboardState.value = dashboardState.value.copy(
                history = RuntimeTaskLedger.merge(this, dashboardState.value.history)
            )
            return
        }
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { JSONObject(service.getTaskHistoryPage(0, 30)) }.getOrNull()
            }
            if (json == null || !json.optBoolean("success")) {
                dashboardState.value = dashboardState.value.copy(
                    history = RuntimeTaskLedger.merge(this@MiuixDashboardActivity, dashboardState.value.history)
                )
                return@launch
            }
            val array = json.optJSONArray("entries")
            val entries = buildList {
                if (array != null) for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        HistoryUiItem(
                            title = historyModeTitle(item.optString("mode")),
                            time = item.optString("time"),
                            trigger = historyTrigger(item.optString("trigger")),
                            result = item.optString("result", if (item.optBoolean("cleaned")) "清理完成" else "扫描完成"),
                            bytes = item.optLong("bytes", 0L).coerceAtLeast(0L),
                            files = item.optInt("files", 0).coerceAtLeast(0),
                            emptyDirs = item.optInt("emptyDirs", 0).coerceAtLeast(0),
                            errors = item.optInt("errors", 0).coerceAtLeast(0),
                            cleaned = item.optBoolean("cleaned"),
                            categories = parseHistoryCategories(item.optJSONArray("categoryDetails")),
                            apps = parseHistoryApps(item.optJSONArray("appDetails"))
                        )
                    )
                }
            }
            dashboardState.value = dashboardState.value.copy(
                history = RuntimeTaskLedger.merge(this@MiuixDashboardActivity, entries),
                lifetimeRuns = json.optLong("lifetimeRuns", json.optLong("cleanedRuns", 0L)).coerceAtLeast(0L),
                lifetimeReleased = json.optLong("lifetimeReleased", json.optLong("totalReleased", 0L)).coerceAtLeast(0L),
                lifetimeFiles = json.optLong("lifetimeFiles", 0L).coerceAtLeast(0L),
                lifetimeEmptyFiles = json.optLong("lifetimeEmptyFiles", 0L).coerceAtLeast(0L),
                lifetimeEmptyDirs = json.optLong("lifetimeEmptyDirs", 0L).coerceAtLeast(0L),
                lifetimeFragments = json.optLong("lifetimeFragments", 0L).coerceAtLeast(0L),
                lifetimeElapsed = json.optLong("lifetimeElapsed", 0L).coerceAtLeast(0L)
            )
        }
    }
'''
rep(activity, old_refresh, new_refresh)

helper_anchor = '''    private fun refreshHistory() {
'''
helpers = '''    private fun appendRuntimeLog(
        title: String,
        result: String,
        bytes: Long,
        files: Long,
        emptyDirs: Long,
        errors: Long,
        cleaned: Boolean
    ) {
        RuntimeTaskLedger.record(
            context = this,
            title = title,
            result = result,
            bytes = bytes,
            files = files,
            emptyDirs = emptyDirs,
            errors = errors,
            cleaned = cleaned
        )
        dashboardState.value = dashboardState.value.copy(
            history = RuntimeTaskLedger.merge(this, dashboardState.value.history)
        )
    }

    private suspend fun persistNativeTaskWithRetry(
        service: IProfileRootService,
        payload: String
    ): String? {
        var lastError = ""
        for (attempt in 0..1) {
            val response = withContext(Dispatchers.IO) {
                runCatching { JSONObject(service.recordNativeTask(payload)) }
            }
            val json = response.getOrNull()
            if (json?.optBoolean("success") == true) return null
            lastError = json?.optString("error").orEmpty().ifBlank {
                response.exceptionOrNull()?.message ?: "Root 服务未返回成功状态"
            }
            if (attempt == 0) delay(180)
        }
        return lastError.take(160)
    }

'''
rep(activity, helper_anchor, helpers + helper_anchor)

old_clear = '''    private fun confirmClearHistory() {
        val service = rootService ?: return
        AlertDialog.Builder(this)
            .setTitle("清空最近记录？")
            .setMessage("只删除最近任务摘要；累计清理次数与累计释放空间会继续保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    val success = withContext(Dispatchers.IO) {
                        runCatching { JSONObject(service.clearTaskHistory()).optBoolean("success") }.getOrDefault(false)
                    }
                    toast(if (success) "最近记录已清空" else "清空失败")
                    if (success) dashboardState.value = dashboardState.value.copy(history = emptyList(), recentApps = emptyList())
                    refreshHistory()
                }
            }.show()
    }
'''
new_clear = '''    private fun confirmClearHistory() {
        val service = rootService
        AlertDialog.Builder(this)
            .setTitle("清空最近记录？")
            .setMessage("同时删除 App 本地任务账本与 Root 最近摘要；累计清理统计会继续保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    val rootCleared = if (service == null) false else withContext(Dispatchers.IO) {
                        runCatching { JSONObject(service.clearTaskHistory()).optBoolean("success") }.getOrDefault(false)
                    }
                    RuntimeTaskLedger.clear(this@MiuixDashboardActivity)
                    dashboardState.value = dashboardState.value.copy(history = emptyList(), recentApps = emptyList())
                    toast(
                        when {
                            service == null -> "本地任务日志已清空；Root 服务未连接"
                            rootCleared -> "任务日志已全部清空"
                            else -> "本地日志已清空，但 Root 历史清理失败"
                        }
                    )
                    if (service != null && rootCleared) refreshHistory()
                }
            }.show()
    }
'''
rep(activity, old_clear, new_clear)

service = "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt"
rep(
    service,
    '''            return try {
                engine.scan(profile.orEmpty(), optionsJson.orEmpty()) { progress ->
                    updateState("profile-scan", progress, started)
                }
            } catch (error: Throwable) {
''',
    '''            return try {
                val result = engine.scan(profile.orEmpty(), optionsJson.orEmpty()) { progress ->
                    updateState("profile-scan", progress, started)
                }
                writeNativeScanTrace(profile.orEmpty(), result, started)
                result
            } catch (error: Throwable) {
'''
)

raw_anchor = '''    private fun rawLogJson(maxChars: Int): String {
'''
trace_helper = '''    private fun writeNativeScanTrace(profile: String, raw: String, started: Long) {
        runCatching {
            val result = JSONObject(raw)
            val logDir = File(STATE_DIR, "logs").apply { mkdirs() }
            val log = File(logDir, "app-native-scan-${System.currentTimeMillis()}.log")
            val total = result.optInt("low", 0) + result.optInt("medium", 0) +
                result.optInt("high", 0) + result.optInt("critical", 0)
            val elapsedSeconds = ((SystemClock.elapsedRealtime() - started).coerceAtLeast(0L) / 1000L)
            log.writeText(buildString {
                append("白泽 Root 原生扫描日志\\n")
                append("模式：").append(profile.ifBlank { "safe" }).append('\\n')
                append("状态：").append(if (result.optBoolean("success", true)) "完成" else "失败").append('\\n')
                append("候选：").append(total).append(" 项\\n")
                append("已知大小：").append(humanBytes(result.optLong("knownBytes", 0L))).append('\\n')
                append("空文件：").append(result.optLong("emptyFiles", 0L))
                    .append(" · 空目录：").append(result.optLong("emptyDirs", 0L)).append('\\n')
                append("碎片：").append(result.optLong("fragmentFiles", 0L))
                    .append(" · 耗时：").append(elapsedSeconds).append(" 秒\\n")
                result.optString("message").takeIf { it.isNotBlank() }?.let { append("消息：").append(it).append('\\n') }
            })
            log.setReadable(true, true)
            logDir.listFiles()?.filter { it.isFile && it.extension.equals("log", true) }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(12)
                ?.forEach { it.delete() }
        }
    }

'''
rep(service, raw_anchor, trace_helper + raw_anchor)

logs_contract = "v2/app/src/main/java/io/github/xgl34222220/baize/ui/logs/LogsContract.kt"
rep(
    logs_contract,
    '''            level = when {
                item.errors > 0 -> LogLevel.ERROR
                item.cleaned && item.bytes > 0L -> LogLevel.SUCCESS
                item.files > 0 -> LogLevel.WARNING
                else -> LogLevel.INFO
            }
''',
    '''            level = when {
                item.errors > 0 -> LogLevel.ERROR
                item.cleaned -> LogLevel.SUCCESS
                else -> LogLevel.INFO
            }
'''
)

logs_screen = "v2/app/src/main/java/io/github/xgl34222220/baize/ui/logs/miuix/LogsScreenMiuix.kt"
rep(
    logs_screen,
    '''        item { MiuixLogsHeader(state.logs.isNotEmpty(), actions) }
        item { MiuixRuntimeOverview(state) }
        item { MiuixSectionTitle("RAW OUTPUT", "模块原始输出", "直接读取 cleaner.sh 最近一次真实输出") }
        item { MiuixRawLogCard(state, actions) }
        item { MiuixSectionTitle("DIAGNOSTICS", "诊断工具", "服务恢复、清理明细与崩溃记录") }
        item { MiuixDiagnostics(actions) }
        item { MiuixSectionTitle("RUNTIME LOGS", "最近运行日志", "由真实任务记录和当前服务状态生成") }

        if (state.logs.isEmpty()) {
            item { MiuixEmptyLogs() }
        } else {
            items(state.logs, key = { it.key }) { item ->
                MiuixLogCard(item)
            }
        }
''',
    '''        item { MiuixLogsHeader(state.logs.isNotEmpty(), actions) }
        item { MiuixRuntimeOverview(state) }
        item {
            MiuixSectionTitle(
                "RUNTIME LOGS",
                "最近运行日志",
                "${state.logs.size} 条持久记录 · Root 历史与 App 本地账本自动合并"
            )
        }

        if (state.logs.isEmpty()) {
            item { MiuixEmptyLogs() }
        } else {
            items(state.logs, key = { it.key }, contentType = { "runtime-log" }) { item ->
                MiuixLogCard(item)
            }
        }

        item { MiuixSectionTitle("RAW OUTPUT", "模块原始输出", "直接读取 Root 服务或 cleaner.sh 最近一次真实输出") }
        item { MiuixRawLogCard(state, actions) }
        item { MiuixSectionTitle("DIAGNOSTICS", "诊断工具", "服务恢复、清理明细与崩溃记录") }
        item { MiuixDiagnostics(actions) }
'''
)
rep(logs_screen, ".shadow(6.dp, shape, clip = false)", ".shadow(2.dp, shape, clip = false)", count=2)
rep(logs_screen, ".shadow(12.dp, shape, clip = false)", ".shadow(6.dp, shape, clip = false)")
rep(
    logs_screen,
    'if (state.hasRawLog) "显示最后 36 行，不使用任务摘要代替" else "执行模块扫描或清理后自动读取"',
    'if (state.hasRawLog) "显示最后 36 行，保留 Root 原始内容" else "执行扫描或清理后自动读取 Root 原始输出"'
)
rep(
    logs_screen,
    '"完成一次扫描或清理后，这里会显示任务结果、大小和异常数量。"',
    '"扫描结束后会先写入 App 本地账本，并同步到 Root 历史；无需等待页面刷新。"'
)

build = root / "v2/app/build.gradle.kts"
build_text = build.read_text()
build_text = build_text.replace("import org.gradle.api.tasks.Exec\n\n", "")
build_text = build_text.replace('versionCode = 22601\n        versionName = "2.2.1"', 'versionCode = 22602\n        versionName = "2.2.2"')
start_marker = '\nval applyV221SourceHotfix = tasks.register<Exec>("applyV221SourceHotfix") {'
if start_marker in build_text:
    build_text = build_text[:build_text.index(start_marker)].rstrip() + "\n"
build.write_text(build_text)

(root / "v2/module/module.prop").write_text(
    "id=baize_v2\n"
    "name=白泽 v2\n"
    "version=v2.2.2-test\n"
    "versionCode=22602\n"
    "author=惜故里丶\n"
    "description=白泽 v2.2.2 测试版：可靠运行日志、本地任务账本、Root 原始扫描追踪与日志页性能优化。\n"
)

package_script = root / "v2/scripts/package-module.sh"
package_text = package_script.read_text().replace("v2.2.1", "v2.2.2").replace("22601", "22602")
package_text = package_text.replace("白泽 v2.2.1", "白泽 v2.2.2")
package_script.write_text(package_text)

release = root / "RELEASE_NOTES_V2.2.2.md"
release.write_text(
    "# 白泽 v2.2.2 测试版\n\n"
    "- 安全扫描在 Root 服务内直接生成原始扫描日志。\n"
    "- App 本地任务账本与 Root 历史自动合并，重启后仍保留。\n"
    "- Root 历史写入失败会自动重试并在界面明确提示。\n"
    "- 最近运行日志移到页面前部，减少逐卡片阴影并优化长列表复用。\n"
)

print("v2.2.2 runtime ledger patch applied")
