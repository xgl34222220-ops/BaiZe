package io.github.xgl34222220.baize.root

import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent root service for the Alpha 6 automatic module path and advanced native audits.
 *
 * Ordinary users call [runModuleTask] with `clean` or `scan`. The complete v1 cleaner performs
 * discovery and cleaning in one task, so the UI never asks the user to open every category and
 * tick hundreds of cache entries. Native profile snapshots remain available only for advanced
 * audit/detail screens and higher-risk tools.
 */
class BaiZeProfileRootService : RootService() {
    private val cancelled = AtomicBoolean(false)
    private val taskRunning = AtomicBoolean(false)
    private val engine by lazy { NativeProfileEngine(this, cancelled) }

    @Volatile
    private var taskStateJson: String = idleState()

    private val binder = object : IProfileRootService.Stub() {
        override fun ping(): String = JSONObject()
            .put("uid", Process.myUid())
            .put("root", Process.myUid() == 0)
            .put("module", File(MODULE_DIR, "module.prop").isFile)
            .put("cleaner", File(MODULE_DIR, "cleaner.sh").isFile)
            .put("deepRules", File(MODULE_DIR, "config/deep.rules").isFile)
            .put("scheduler", File(MODULE_DIR, "service.sh").isFile)
            .put("engine", "module-auto-cleaner-v8+native-audit")
            .toString()

        override fun getProfileCatalog(): String = engine.catalog()

        override fun scanProfile(profile: String?, optionsJson: String?): String {
            if (!taskRunning.compareAndSet(false, true)) return busy("profile-scan")
            cancelled.set(false)
            val started = SystemClock.elapsedRealtime()
            return try {
                engine.scan(profile.orEmpty(), optionsJson.orEmpty()) { progress ->
                    updateState("profile-scan", progress, started)
                }
            } catch (error: Throwable) {
                failure("profile_scan_failed", error)
            } finally {
                taskRunning.set(false)
                taskStateJson = idleState()
            }
        }

        override fun getProfilePage(snapshotId: String?, offset: Int, limit: Int): String {
            if (taskRunning.get()) return busy("profile-page")
            cancelled.set(false)
            return engine.page(snapshotId.orEmpty(), offset, limit)
        }

        override fun cleanProfileSelected(snapshotId: String?, selectionJson: String?, optionsJson: String?): String {
            if (!taskRunning.compareAndSet(false, true)) return busy("profile-clean")
            cancelled.set(false)
            val started = SystemClock.elapsedRealtime()
            return try {
                engine.clean(snapshotId.orEmpty(), selectionJson.orEmpty(), optionsJson.orEmpty()) { progress ->
                    updateState("profile-clean", progress, started)
                }
            } catch (error: Throwable) {
                failure("profile_clean_failed", error)
            } finally {
                taskRunning.set(false)
                taskStateJson = idleState()
            }
        }

        override fun runModuleTask(mode: String?): String {
            val normalized = mode.orEmpty().trim().lowercase()
            if (normalized !in MODULE_TASKS) {
                return JSONObject().put("error", "unsupported_mode").put("mode", normalized).toString()
            }
            if (!taskRunning.compareAndSet(false, true)) return busy("module-$normalized")
            cancelled.set(false)
            val started = SystemClock.elapsedRealtime()
            taskStateJson = JSONObject()
                .put("running", true)
                .put("operation", "module-$normalized")
                .put("phase", if (normalized == "scan") "正在执行安全扫描" else "正在执行一键清理")
                .put("elapsedMs", 0)
                .toString()
            return try {
                executeModuleTask(normalized, started)
            } catch (error: Throwable) {
                failure("module_task_failed", error)
            } finally {
                taskRunning.set(false)
                taskStateJson = idleState()
            }
        }

        override fun getModuleState(): String = moduleState()

        override fun getTaskHistory(limit: Int): String = taskHistoryJson(limit)

        override fun clearTaskHistory(): String = clearTaskHistoryJson()

        override fun getRawLog(maxChars: Int): String = rawLogJson(maxChars)

        override fun clearRawLogs(): String = clearRawLogsJson()

        override fun recordNativeTask(taskJson: String?): String = recordNativeTaskJson(taskJson.orEmpty())

        override fun getSchedulerConfig(): String = configJson()

        override fun saveSchedulerConfig(configJson: String?): String = saveConfig(configJson.orEmpty())

        override fun resetScanWorkerProfile(): String = resetScanWorkerProfileJson()

        override fun getInstalledPackageCatalog(): String =
            this@BaiZeProfileRootService.installedPackageCatalogJson()

        override fun getWhitelistPackages(): String = this@BaiZeProfileRootService.whitelistPackagesJson()

        override fun saveWhitelistPackages(packagesJson: String?): String =
            this@BaiZeProfileRootService.persistWhitelistPackages(packagesJson.orEmpty())

        override fun getTaskState(): String {
            val running = readEnv(File(STATE_DIR, "running.env"))
            if (running.length() > 0) {
                running.put("running", true)
                running.put("operation", running.optString("mode", "module-task"))
                running.put("cancelRequested", cancelled.get())
                return running.toString()
            }
            return runCatching {
                JSONObject(taskStateJson).put("cancelRequested", cancelled.get()).toString()
            }.getOrDefault(taskStateJson)
        }

        override fun cancelCurrentTask() {
            cancelled.set(true)
            runCatching {
                File(STATE_DIR).mkdirs()
                File(STATE_DIR, "stop").writeText("1\n")
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun executeModuleTask(mode: String, started: Long): String {
        val cleaner = File(MODULE_DIR, "cleaner.sh")
        if (!cleaner.isFile) {
            return JSONObject()
                .put("error", "cleaner_missing")
                .put("message", "模块清理引擎缺失，请重新刷入完整模块")
                .toString()
        }

        val stateDir = File(STATE_DIR).apply { mkdirs() }
        File(stateDir, "stop").delete()
        val logDir = File(stateDir, "logs").apply { mkdirs() }
        val log = File(logDir, "app-${mode}-${System.currentTimeMillis()}.log")
        val process = ProcessBuilder("/system/bin/sh", cleaner.absolutePath, mode, "app")
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()

        while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
            if (cancelled.get()) {
                runCatching { File(stateDir, "stop").writeText("1\n") }
            }
            val running = readEnv(File(stateDir, "running.env"))
            val phase = running.optString("phase").ifBlank {
                if (mode == "scan") "正在执行安全扫描" else "正在自动扫描并清理"
            }
            taskStateJson = running
                .put("running", true)
                .put("operation", "module-$mode")
                .put("phase", phase)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .toString()
        }

        val code = process.exitValue()
        val elapsed = SystemClock.elapsedRealtime() - started
        val totals = readEnv(File(stateDir, "totals.env"))
        val latest = readEnv(File(stateDir, "latest.env"))
        val latestReport = File(stateDir, "reports/latest.tsv")
        val appDetails = appDetailsJson(
            File(stateDir, "reports/apps-latest.tsv"),
            File(stateDir, "reports/app-items-latest.tsv")
        )
        val output = tailText(log, 12_000)
        return JSONObject()
            .put("success", code == 0)
            .put("mode", mode)
            .put("exitCode", code)
            .put("cancelled", code == 9 || cancelled.get())
            .put("elapsedMs", elapsed)
            .put("output", output)
            .put("totals", totals)
            .put("latest", latest)
            .put("scanPerformance", scanPerformanceJson())
            .put("latestReport", if (latestReport.isFile) latestReport.absolutePath else "")
            .put("logName", log.name)
            .put("appDetails", appDetails)
            .put("otherDetails", otherDetailsJson(latestReport))
            .put("message", when (code) {
                0 -> if (mode == "scan") "扫描完成" else "自动清理完成"
                3 -> "已有其他任务正在运行"
                9 -> "任务已停止"
                else -> "任务失败（代码 $code）"
            })
            .toString()
    }

    private fun appDetailsJson(summaryFile: File, itemFile: File): JSONArray {
        val filesByPackage = linkedMapOf<String, Long>()
        val bytesByPackage = linkedMapOf<String, Long>()
        val errorsByPackage = linkedMapOf<String, Long>()
        val categoryFiles = linkedMapOf<String, LinkedHashMap<String, Long>>()
        val categoryBytes = linkedMapOf<String, LinkedHashMap<String, Long>>()
        val categoryErrors = linkedMapOf<String, LinkedHashMap<String, Long>>()
        val categorySamples = linkedMapOf<String, LinkedHashMap<String, String>>()
        var itemRows = 0

        fun add(packageName: String, category: String, files: Long, bytes: Long, errors: Long, samplePath: String) {
            if (!PACKAGE_NAME.matches(packageName)) return
            val safeCategory = category.trim().take(80).ifBlank { "应用缓存" }
            val safeFiles = files.coerceAtLeast(0L)
            val safeBytes = bytes.coerceAtLeast(0L)
            val safeErrors = errors.coerceAtLeast(0L)
            filesByPackage[packageName] = (filesByPackage[packageName] ?: 0L) + safeFiles
            bytesByPackage[packageName] = (bytesByPackage[packageName] ?: 0L) + safeBytes
            errorsByPackage[packageName] = (errorsByPackage[packageName] ?: 0L) + safeErrors
            val filesMap = categoryFiles.getOrPut(packageName) { linkedMapOf() }
            val bytesMap = categoryBytes.getOrPut(packageName) { linkedMapOf() }
            val errorsMap = categoryErrors.getOrPut(packageName) { linkedMapOf() }
            filesMap[safeCategory] = (filesMap[safeCategory] ?: 0L) + safeFiles
            bytesMap[safeCategory] = (bytesMap[safeCategory] ?: 0L) + safeBytes
            errorsMap[safeCategory] = (errorsMap[safeCategory] ?: 0L) + safeErrors
            val sample = samplePath.trim().take(240)
            if (sample.isNotBlank()) categorySamples.getOrPut(packageName) { linkedMapOf() }.putIfAbsent(safeCategory, sample)
        }

        runCatching {
            if (!itemFile.isFile) return@runCatching
            itemFile.forEachLine { raw ->
                val columns = raw.split('\t', limit = 6)
                if (columns.size < 6 || columns[0] == "package") return@forEachLine
                add(
                    packageName = columns[0].trim(),
                    category = columns[1],
                    files = columns[2].toLongOrNull() ?: 0L,
                    bytes = columns[3].toLongOrNull() ?: 0L,
                    errors = columns[4].toLongOrNull() ?: 0L,
                    samplePath = columns[5]
                )
                itemRows += 1
            }
        }

        if (itemRows == 0) {
            runCatching {
                if (!summaryFile.isFile) return@runCatching
                summaryFile.forEachLine { raw ->
                    val columns = raw.split('\t', limit = 4)
                    if (columns.size < 4 || columns[0] == "package") return@forEachLine
                    add(
                        packageName = columns[0].trim(),
                        category = columns[1],
                        files = columns[2].toLongOrNull() ?: 0L,
                        bytes = columns[3].toLongOrNull() ?: 0L,
                        errors = 0L,
                        samplePath = ""
                    )
                }
            }
        }

        val result = JSONArray()
        bytesByPackage.keys
            .sortedWith(
                compareByDescending<String> { bytesByPackage[it] ?: 0L }
                    .thenByDescending { filesByPackage[it] ?: 0L }
                    .thenBy { it }
            )
            .take(100)
            .forEach { packageName ->
                val categories = JSONArray()
                val names = categoryBytes[packageName].orEmpty().keys
                    .sortedWith(compareByDescending<String> { categoryBytes[packageName]?.get(it) ?: 0L }.thenBy { it })
                names.forEach { name ->
                    categories.put(
                        JSONObject()
                            .put("name", name)
                            .put("files", categoryFiles[packageName]?.get(name) ?: 0L)
                            .put("bytes", categoryBytes[packageName]?.get(name) ?: 0L)
                            .put("errors", categoryErrors[packageName]?.get(name) ?: 0L)
                            .put("samplePath", categorySamples[packageName]?.get(name).orEmpty())
                    )
                }
                result.put(
                    JSONObject()
                        .put("packageName", packageName)
                        .put("files", filesByPackage[packageName] ?: 0L)
                        .put("bytes", bytesByPackage[packageName] ?: 0L)
                        .put("errors", errorsByPackage[packageName] ?: 0L)
                        .put("category", names.joinToString("、"))
                        .put("categories", categories)
                )
            }
        return result
    }

    private fun otherDetailsJson(file: File): JSONArray {
        data class Aggregate(
            var files: Long = 0,
            var bytes: Long = 0,
            var errors: Long = 0,
            var samplePath: String = ""
        )

        val groups = linkedMapOf<String, Aggregate>()
        runCatching {
            if (!file.isFile) return@runCatching
            file.forEachLine { raw ->
                val columns = raw.split('\t', limit = 6)
                if (columns.size < 6 || columns[0] == "action") return@forEachLine
                val action = columns[0].trim()
                if (action !in setOf("candidate", "cleaned", "failed")) return@forEachLine
                val category = columns[2].trim().take(80)
                if (category.isBlank()) return@forEachLine
                val suffix = category.substringAfterLast(':', "")
                if (suffix.isNotBlank() && PACKAGE_NAME.matches(suffix)) return@forEachLine
                val items = columns[3].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                val bytes = columns[4].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                val path = columns[5].trim().take(240)
                val aggregate = groups.getOrPut(category) { Aggregate() }
                if (action == "failed") {
                    aggregate.errors += items
                } else {
                    aggregate.files += items
                    aggregate.bytes += bytes
                }
                if (aggregate.samplePath.isBlank() && path.isNotBlank()) aggregate.samplePath = path
            }
        }
        val result = JSONArray()
        groups.entries
            .filter { (_, value) -> value.files > 0 || value.bytes > 0 || value.errors > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Aggregate>> { it.value.bytes }.thenBy { it.key })
            .take(60)
            .forEach { (name, value) ->
                result.put(
                    JSONObject()
                        .put("name", name)
                        .put("files", value.files)
                        .put("bytes", value.bytes)
                        .put("errors", value.errors)
                        .put("samplePath", value.samplePath)
                )
            }
        return result
    }

    private fun moduleState(): String {
        val stateDir = File(STATE_DIR)
        val scheduler = readEnv(File(stateDir, "scheduler.env"))
        val module = readEnv(File(stateDir, "module.env"))
        val totals = readEnv(File(stateDir, "totals.env"))
        val latest = readEnv(File(stateDir, "latest.env"))
        val running = readEnv(File(stateDir, "running.env"))
        return JSONObject()
            .put("moduleInstalled", File(MODULE_DIR, "module.prop").isFile)
            .put("cleanerReady", File(MODULE_DIR, "cleaner.sh").isFile)
            .put("rulesReady", File(MODULE_DIR, "config/deep.rules").isFile)
            .put("scheduler", scheduler)
            .put("module", module)
            .put("totals", totals)
            .put("latest", latest)
            .put("scanPerformance", scanPerformanceJson())
            .put(
                "appDetails",
                appDetailsJson(
                    File(stateDir, "reports/apps-latest.tsv"),
                    File(stateDir, "reports/app-items-latest.tsv")
                )
            )
            .put("otherDetails", otherDetailsJson(File(stateDir, "reports/latest.tsv")))
            .put("running", running)
            .put("config", configJsonObject())
            .toString()
    }

    private fun scanPerformanceJson(): JSONObject {
        val stateDir = File(STATE_DIR)
        val cache = readEnv(File(stateDir, "cache_scan.env"))
        val profile = readEnv(File(stateDir, "root-worker-profile.env"))
        val config = configJsonObject()
        val requestedMode = config.optInt("scan_root_workers", 0).coerceIn(0, 2)
        val actualWorkers = cache.optInt("root_workers", profile.optInt("last_workers", 1)).coerceIn(1, 2)
        val recommendedWorkers = profile.optInt("recommended_workers", 1).coerceIn(1, 2)
        val hasProfile = profile.length() > 0
        val reason = if (hasProfile) {
            cache.optString("worker_reason").ifBlank {
                profile.optString("last_decision", "not_measured")
            }
        } else {
            "not_measured"
        }
        return JSONObject()
            .put("available", hasProfile)
            .put("requestedMode", requestedMode)
            .put("workerPolicy", if (requestedMode == 0) "auto" else "manual")
            .put("workerReason", reason)
            .put("actualWorkers", actualWorkers)
            .put("recommendedWorkers", recommendedWorkers)
            .put("parallelGainPercent", profile.optInt("parallel_gain_percent", 0))
            .put("serialRate", profile.optLong("serial_rate", 0L).coerceAtLeast(0L))
            .put("parallelRate", profile.optLong("parallel_rate", 0L).coerceAtLeast(0L))
            .put("successfulRuns", profile.optInt("successful_runs", 0).coerceAtLeast(0))
            .put("nextProbeRun", profile.optInt("next_probe_run", 0).coerceAtLeast(0))
            .put("parallelBlockedUntil", profile.optLong("parallel_blocked_until", 0L).coerceAtLeast(0L))
            .put("lastUpdatedEpoch", profile.optLong("updated_epoch", 0L).coerceAtLeast(0L))
    }

    private fun resetScanWorkerProfileJson(): String = runCatching {
        val profile = File(STATE_DIR, "root-worker-profile.env")
        val deleted = !profile.exists() || profile.delete()
        if (!deleted) error("无法删除本机性能基准")
        JSONObject()
            .put("success", true)
            .put("message", "性能基准已清除，下次自动扫描将从串行重新学习")
            .toString()
    }.getOrElse { error ->
        JSONObject()
            .put("success", false)
            .put("error", error.message ?: error.javaClass.simpleName)
            .toString()
    }

    private fun taskHistoryJson(requestedLimit: Int): String {
        val limit = requestedLimit.coerceIn(1, 100)
        val historyFile = File(STATE_DIR, "history.tsv")
        val entries = JSONArray()
        var totalReleased = 0L
        var cleanedRuns = 0
        val totals = readEnv(File(STATE_DIR, "totals.env"))

        val lines = runCatching {
            if (historyFile.isFile) historyFile.readLines().takeLast(limit).asReversed() else emptyList()
        }.getOrDefault(emptyList())

        lines.forEach { raw ->
            val columns = raw.split('\t', limit = 10)
            if (columns.size < 7) return@forEach
            val mode = columns[1].trim()
            val bytes = columns[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val cleaned = mode != "scan" && !mode.endsWith("-scan")
            if (cleaned) {
                totalReleased += bytes
                cleanedRuns += 1
            }
            entries.put(
                JSONObject()
                    .put("time", columns[0].trim())
                    .put("mode", mode)
                    .put("bytes", bytes)
                    .put("files", columns[3].toIntOrNull()?.coerceAtLeast(0) ?: 0)
                    .put("emptyDirs", columns[4].toIntOrNull()?.coerceAtLeast(0) ?: 0)
                    .put("errors", columns[5].toIntOrNull()?.coerceAtLeast(0) ?: 0)
                    .put("result", columns[6].trim())
                    .put("trigger", columns.getOrNull(7)?.trim().orEmpty())
                    .put("categoryDetails", parseHistoryCategoryDetails(columns.getOrNull(8).orEmpty()))
                    .put("appDetails", parseHistoryAppDetails(columns.getOrNull(9).orEmpty()))
                    .put("cleaned", cleaned)
            )
        }

        return JSONObject()
            .put("success", true)
            .put("count", entries.length())
            .put("cleanedRuns", cleanedRuns)
            .put("totalReleased", totalReleased)
            .put("lifetimeRuns", totals.optLong("runs", cleanedRuns.toLong()).coerceAtLeast(0L))
            .put("lifetimeReleased", totals.optLong("bytes", totalReleased).coerceAtLeast(0L))
            .put("lifetimeFiles", totals.optLong("regular_files", 0L).coerceAtLeast(0L))
            .put("lifetimeEmptyFiles", totals.optLong("empty_files", 0L).coerceAtLeast(0L))
            .put("lifetimeEmptyDirs", totals.optLong("empty_dirs", 0L).coerceAtLeast(0L))
            .put("lifetimeFragments", totals.optLong("fragment_files", 0L).coerceAtLeast(0L))
            .put("lifetimeElapsed", totals.optLong("elapsed", 0L).coerceAtLeast(0L))
            .put("entries", entries)
            .toString()
    }

    private fun parseHistoryCategoryDetails(raw: String): JSONArray {
        val result = JSONArray()
        raw.split(';').asSequence().map { it.trim() }.filter { it.isNotBlank() }.take(12).forEach { token ->
            val columns = token.split('|', limit = 3)
            if (columns.size < 3) return@forEach
            val name = columns[0].trim().take(80)
            if (name.isBlank()) return@forEach
            result.put(
                JSONObject()
                    .put("name", name)
                    .put("bytes", columns[1].toLongOrNull()?.coerceAtLeast(0L) ?: 0L)
                    .put("files", columns[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L)
            )
        }
        return result
    }

    private fun parseHistoryAppDetails(raw: String): JSONArray {
        val result = JSONArray()
        raw.split(';').asSequence().map { it.trim() }.filter { it.isNotBlank() }.take(12).forEach { token ->
            val columns = token.split('|', limit = 4)
            if (columns.size < 3) return@forEach
            val packageName = columns[0].trim()
            if (!PACKAGE_NAME.matches(packageName)) return@forEach
            result.put(
                JSONObject()
                    .put("packageName", packageName)
                    .put("bytes", columns[1].toLongOrNull()?.coerceAtLeast(0L) ?: 0L)
                    .put("files", columns[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L)
                    .put("category", columns.getOrNull(3)?.trim().orEmpty().take(80))
            )
        }
        return result
    }

    private fun clearTaskHistoryJson(): String = runCatching {
        File(STATE_DIR, "history.tsv").writeText("")
        File(STATE_DIR, "latest.env").delete()
        File(STATE_DIR, "reports/latest.tsv").delete()
        File(STATE_DIR, "reports/apps-latest.tsv").delete()
        File(STATE_DIR, "reports/app-items-latest.tsv").delete()
        JSONObject().put("success", true).toString()
    }.getOrElse { error ->
        JSONObject()
            .put("success", false)
            .put("error", error.message ?: error.javaClass.simpleName)
            .toString()
    }

    private fun recordNativeTaskJson(raw: String): String = runCatching {
        val input = JSONObject(raw)
        val mode = input.optString("mode").trim()
        require(mode in setOf("smart-clean", "snapshot-clean")) { "unsupported_native_mode" }
        val success = input.optBoolean("success", true)
        val cancelled = input.optBoolean("cancelled", false)
        val bytes = input.optLong("bytes", 0L).coerceIn(0L, Long.MAX_VALUE / 4)
        val files = input.optLong("files", 0L).coerceIn(0L, Int.MAX_VALUE.toLong())
        val emptyFiles = input.optLong("emptyFiles", 0L).coerceIn(0L, Int.MAX_VALUE.toLong())
        val emptyDirs = input.optLong("emptyDirs", 0L).coerceIn(0L, Int.MAX_VALUE.toLong())
        val fragments = input.optLong("fragments", 0L).coerceIn(0L, Int.MAX_VALUE.toLong())
        val errors = input.optLong("errors", 0L).coerceIn(0L, Int.MAX_VALUE.toLong())
        val elapsedSeconds = input.optLong("elapsedSeconds", 0L).coerceIn(0L, 24L * 60L * 60L)
        val result = input.optString("result", "原生智能清理完成")
            .replace('\t', ' ')
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(500)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val stateDir = File(STATE_DIR).apply { mkdirs() }
        val history = File(stateDir, "history.tsv")
        val categorySummary = input.optString("categorySummary")
            .replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').take(1000)
        val appSummary = input.optString("appSummary")
            .replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').take(1000)
        history.appendText("$timestamp\t$mode\t$bytes\t$files\t$emptyDirs\t$errors\t$result\tapp-native\t$categorySummary\t$appSummary\n")
        val retained = history.readLines().takeLast(100)
        val historyTemp = File(stateDir, "history.tsv.tmp.${Process.myPid()}")
        historyTemp.writeText(retained.joinToString("\n", postfix = if (retained.isEmpty()) "" else "\n"))
        replaceFile(historyTemp, history)

        if (success && !cancelled) {
            val totalsFile = File(stateDir, "totals.env")
            val totals = readEnv(totalsFile)
            val updated = linkedMapOf(
                "runs" to totals.optLong("runs", 0L) + 1L,
                "regular_files" to totals.optLong("regular_files", 0L) + files,
                "empty_files" to totals.optLong("empty_files", 0L) + emptyFiles,
                "empty_dirs" to totals.optLong("empty_dirs", 0L) + emptyDirs,
                "hidden_items" to totals.optLong("hidden_items", 0L),
                "fragment_files" to totals.optLong("fragment_files", 0L) + fragments,
                "bytes" to totals.optLong("bytes", 0L) + bytes,
                "elapsed" to totals.optLong("elapsed", 0L) + elapsedSeconds
            )
            val totalsTemp = File(stateDir, "totals.env.tmp.${Process.myPid()}")
            totalsTemp.writeText(buildString {
                updated.forEach { (key, value) -> append(key).append('=').append(value.coerceAtLeast(0L)).append('\n') }
                append("last_time=").append(SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())).append('\n')
            })
            replaceFile(totalsTemp, totalsFile)
            updateModuleDescription(updated)
        }

        File(stateDir, "latest.env").writeText(buildString {
            append("mode=").append(mode).append('\n')
            append("time=").append(timestamp).append('\n')
            append("files=").append(files).append('\n')
            append("regular_files=").append(files).append('\n')
            append("empty_files=").append(emptyFiles).append('\n')
            append("empty_dirs=").append(emptyDirs).append('\n')
            append("fragment_files=").append(fragments).append('\n')
            append("bytes=").append(bytes).append('\n')
            append("errors=").append(errors).append('\n')
            append("elapsed=").append(elapsedSeconds).append('\n')
            append("result=").append(result).append('\n')
        })
        JSONObject().put("success", true).toString()
    }.getOrElse { error ->
        JSONObject()
            .put("success", false)
            .put("error", error.message ?: error.javaClass.simpleName)
            .toString()
    }

    private fun replaceFile(temporary: File, target: File, worldReadable: Boolean = false) {
        temporary.setReadable(true, !worldReadable)
        temporary.setWritable(true, true)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        target.setReadable(true, !worldReadable)
        target.setWritable(true, true)
    }

    private fun updateModuleDescription(totals: Map<String, Long>) = runCatching {
        val moduleProp = File(MODULE_DIR, "module.prop")
        if (!moduleProp.isFile) return@runCatching
        val description = buildString {
            append("description=累计清理 ").append(humanBytes(totals["bytes"] ?: 0L))
            append(" · 文件 ").append(totals["regular_files"] ?: 0L)
            append(" · 空文件 ").append(totals["empty_files"] ?: 0L)
            append(" · 空目录 ").append(totals["empty_dirs"] ?: 0L)
            append(" · 碎片 ").append(totals["fragment_files"] ?: 0L)
        }
        val lines = moduleProp.readLines().toMutableList()
        val index = lines.indexOfFirst { it.startsWith("description=") }
        if (index >= 0) lines[index] = description else lines += description
        val temporary = File(moduleProp.parentFile, "module.prop.tmp.${Process.myPid()}")
        temporary.writeText(lines.joinToString("\n", postfix = "\n"))
        replaceFile(temporary, moduleProp, worldReadable = true)
    }

    private fun humanBytes(value: Long): String {
        val bytes = value.coerceAtLeast(0L).toDouble()
        return when {
            bytes >= 1_073_741_824.0 -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576.0 -> String.format(Locale.US, "%.2f MB", bytes / 1_048_576.0)
            bytes >= 1024.0 -> String.format(Locale.US, "%.2f KB", bytes / 1024.0)
            else -> "${bytes.toLong()} B"
        }
    }

    private fun configJson(): String = configJsonObject().toString()

    private fun configJsonObject(): JSONObject {
        ensureConfig()
        val result = JSONObject()
        File(CONFIG_FILE).takeIf { it.isFile }?.forEachLine { raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#") || !line.contains('=')) return@forEachLine
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim()
            if (key in ALLOWED_CONFIG) result.put(key, value.toIntOrNull() ?: value)
        }
        return result
    }

    private fun saveConfig(raw: String): String {
        val input = runCatching { JSONObject(raw) }.getOrElse {
            return JSONObject().put("error", "invalid_json").put("message", "计划配置格式无效").toString()
        }
        ensureConfig()
        val updates = LinkedHashMap<String, String>()
        val keys = input.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val range = ALLOWED_CONFIG[key] ?: continue
            val value = input.optInt(key, Int.MIN_VALUE)
            if (value == Int.MIN_VALUE || value !in range) {
                return JSONObject().put("error", "invalid_value").put("key", key).toString()
            }
            updates[key] = value.toString()
        }
        if (updates.isEmpty()) return JSONObject().put("error", "empty_config").toString()

        val file = File(CONFIG_FILE)
        val lines = if (file.isFile) file.readLines().toMutableList() else mutableListOf()
        val written = HashSet<String>()
        for (index in lines.indices) {
            val line = lines[index]
            val key = line.substringBefore('=', "").trim()
            val replacement = updates[key]
            if (replacement != null && !line.trimStart().startsWith("#")) {
                lines[index] = "$key=$replacement"
                written += key
            }
        }
        for ((key, value) in updates) if (key !in written) lines += "$key=$value"

        val temporary = File("$CONFIG_FILE.tmp.${Process.myPid()}")
        temporary.parentFile?.mkdirs()
        temporary.writeText(lines.joinToString("\n", postfix = "\n"))
        temporary.setReadable(true, true)
        temporary.setWritable(true, true)
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
        return JSONObject().put("success", true).put("config", configJsonObject()).toString()
    }

    private fun installedPackageCatalogJson(): String {
        val systemPackages = queryPackageNames("cmd package list packages -s").toSet()
        val thirdPartyPackages = queryPackageNames("cmd package list packages -3").toSet()
        val allPackages = linkedSetOf<String>()
        allPackages += queryPackageNames("cmd package list packages")
        allPackages += systemPackages
        allPackages += thirdPartyPackages

        if (allPackages.isEmpty()) {
            listOf("/data/user/0", "/data/user_de/0").forEach { rootPath ->
                File(rootPath).listFiles()
                    ?.asSequence()
                    ?.filter { it.isDirectory && PACKAGE_NAME.matches(it.name) }
                    ?.mapTo(allPackages) { it.name }
            }
        }

        val packages = JSONArray()
        allPackages.asSequence()
            .filter { PACKAGE_NAME.matches(it) }
            .sorted()
            .forEach { packageName ->
                packages.put(
                    JSONObject()
                        .put("packageName", packageName)
                        .put("system", packageName in systemPackages && packageName !in thirdPartyPackages)
                )
            }
        return JSONObject()
            .put("success", packages.length() > 0)
            .put("source", if (systemPackages.isNotEmpty() || thirdPartyPackages.isNotEmpty()) "root-cmd" else "data-fallback")
            .put("count", packages.length())
            .put("packages", packages)
            .toString()
    }

    private fun queryPackageNames(command: String): List<String> = runCatching {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        if (!process.waitFor(8, TimeUnit.SECONDS)) process.destroyForcibly()
        lines.asSequence()
            .map { it.trim().removePrefix("package:").substringBefore(' ') }
            .filter { PACKAGE_NAME.matches(it) }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())

    private fun whitelistPackagesJson(): String = JSONArray(readWhitelistPackages().sorted()).toString()

    private fun persistWhitelistPackages(raw: String): String {
        val array = runCatching { JSONArray(raw) }.getOrElse {
            return JSONObject().put("error", "invalid_json").put("message", "白名单格式无效").toString()
        }
        val packages = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val packageName = array.optString(index).trim()
            if (!PACKAGE_NAME.matches(packageName)) {
                return JSONObject().put("error", "invalid_package").put("package", packageName).toString()
            }
            packages += packageName
            if (packages.size > 500) {
                return JSONObject().put("error", "too_many_packages").put("limit", 500).toString()
            }
        }

        File(STATE_DIR).mkdirs()
        val sidecar = packages.sorted().joinToString("\n", postfix = if (packages.isEmpty()) "" else "\n")
        writeAtomic(File(WHITELIST_PACKAGES_FILE), sidecar)
        rebuildWhitelistFile(packages)
        return JSONObject()
            .put("success", true)
            .put("count", packages.size)
            .put("message", "应用白名单已写入清理引擎")
            .toString()
    }

    private fun readWhitelistPackages(): Set<String> {
        val sidecar = File(WHITELIST_PACKAGES_FILE)
        if (sidecar.isFile) {
            return sidecar.readLines()
                .asSequence()
                .map { it.trim() }
                .filter { PACKAGE_NAME.matches(it) }
                .toSet()
        }

        val inferred = linkedSetOf<String>()
        File(WHITELIST_FILE).takeIf { it.isFile }?.forEachLine { raw ->
            val line = raw.trim()
            for (pattern in GENERATED_PATH_PATTERNS) {
                val packageName = pattern.matchEntire(line)?.groupValues?.getOrNull(1)
                if (!packageName.isNullOrBlank()) inferred += packageName
            }
        }
        return inferred
    }

    private fun rebuildWhitelistFile(packages: Set<String>) {
        val file = File(WHITELIST_FILE)
        val manual = mutableListOf<String>()
        var generatedSection = false
        if (file.isFile) {
            file.forEachLine { raw ->
                val line = raw.trim()
                when (line) {
                    APP_WHITELIST_BEGIN -> generatedSection = true
                    APP_WHITELIST_END -> generatedSection = false
                    else -> if (!generatedSection && line.startsWith("/") && !isGeneratedAppPath(line)) {
                        manual += line
                    }
                }
            }
        }

        val users = linkedSetOf("0")
        listOf("/data/user", "/data/user_de", "/data/media").forEach { root ->
            File(root).listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory && it.name.all(Char::isDigit) }
                ?.mapTo(users) { it.name }
        }

        val output = buildString {
            append("# 白泽清理保护白名单。自定义绝对路径可继续逐行添加。\n")
            manual.distinct().sorted().forEach { append(it).append('\n') }
            if (manual.isNotEmpty()) append('\n')
            append(APP_WHITELIST_BEGIN).append('\n')
            for (packageName in packages.sorted()) {
                append("# app:").append(packageName).append('\n')
                for (user in users.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }) {
                    append("/data/user/").append(user).append('/').append(packageName).append('\n')
                    append("/data/user_de/").append(user).append('/').append(packageName).append('\n')
                    append("/data/media/").append(user).append("/Android/data/").append(packageName).append('\n')
                }
            }
            append(APP_WHITELIST_END).append('\n')
        }
        writeAtomic(file, output)
    }

    private fun isGeneratedAppPath(path: String): Boolean =
        GENERATED_PATH_PATTERNS.any { it.matches(path.trimEnd('/')) }

    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp.${Process.myPid()}")
        temporary.writeText(text)
        temporary.setReadable(true, true)
        temporary.setWritable(true, true)
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    private fun rawLogJson(maxChars: Int): String {
        val safeLimit = maxChars.coerceIn(2_000, 64_000)
        val logDir = File(STATE_DIR, "logs")
        val latest = logDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.equals("log", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
        if (latest == null) {
            return JSONObject()
                .put("success", true)
                .put("name", "")
                .put("text", "")
                .toString()
        }
        return JSONObject()
            .put("success", true)
            .put("name", latest.name)
            .put("modified", latest.lastModified())
            .put("text", tailText(latest, safeLimit))
            .toString()
    }

    private fun clearRawLogsJson(): String {
        val logDir = File(STATE_DIR, "logs")
        var removed = 0
        var failed = 0
        logDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("log", ignoreCase = true) }
            ?.forEach { file ->
                if (runCatching { file.delete() }.getOrDefault(false)) removed += 1 else failed += 1
            }
        return JSONObject()
            .put("success", failed == 0)
            .put("removed", removed)
            .put("failed", failed)
            .toString()
    }

    private fun ensureConfig() {
        val file = File(CONFIG_FILE)
        if (file.isFile) return
        file.parentFile?.mkdirs()
        val defaults = File(MODULE_DIR, "config/default.conf")
        if (defaults.isFile) defaults.copyTo(file, overwrite = false)
    }

    private fun readEnv(file: File): JSONObject {
        val result = JSONObject()
        if (!file.isFile) return result
        runCatching {
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isBlank() || line.startsWith("#") || !line.contains('=')) return@forEachLine
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=').trim()
                result.put(key, value.toLongOrNull() ?: value)
            }
        }
        return result
    }

    private fun tailText(file: File, maxChars: Int): String = runCatching {
        if (!file.isFile) return@runCatching ""
        val text = file.readText()
        if (text.length <= maxChars) text else text.takeLast(maxChars)
    }.getOrDefault("")

    private fun updateState(operation: String, progress: NativeProfileEngine.Progress, started: Long) {
        taskStateJson = JSONObject()
            .put("running", true)
            .put("operation", operation)
            .put("phase", progress.phase)
            .put("current", progress.current)
            .put("total", progress.total)
            .put("currentPath", progress.path)
            .put("deletedBytes", progress.bytes)
            .put("deletedFiles", progress.files)
            .put("failures", progress.failures)
            .put("elapsedMs", (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L))
            .toString()
    }

    private fun failure(code: String, error: Throwable): String = JSONObject()
        .put("error", code)
        .put("message", error.message ?: error.javaClass.simpleName)
        .toString()

    private fun busy(operation: String): String = JSONObject()
        .put("success", false)
        .put("error", "busy")
        .put("operation", operation)
        .put("message", "已有任务正在运行")
        .toString()

    private fun idleState(): String = JSONObject()
        .put("running", false)
        .put("operation", "idle")
        .put("phase", "等待任务")
        .toString()

    companion object {
        private const val MODULE_DIR = "/data/adb/modules/baize_v2"
        private const val STATE_DIR = "/data/adb/baize-v2"
        private const val CONFIG_FILE = "$STATE_DIR/config.conf"
        private const val WHITELIST_FILE = "$STATE_DIR/whitelist.conf"
        private const val WHITELIST_PACKAGES_FILE = "$STATE_DIR/whitelist.packages"
        private const val APP_WHITELIST_BEGIN = "# BEGIN BAIZE APP WHITELIST"
        private const val APP_WHITELIST_END = "# END BAIZE APP WHITELIST"

        private val PACKAGE_NAME = Regex("""^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$""")
        private val GENERATED_PATH_PATTERNS = listOf(
            Regex("""^/data/(?:user|user_de)/\d+/([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)$"""),
            Regex("""^/data/media/\d+/Android/data/([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)$""")
        )

        private val MODULE_TASKS = setOf(
            "scan", "clean", "cache-clean", "empty-clean", "rules-clean", "fragment-scan",
            "fragment-clean", "deep-scan", "deep-clean", "corpse-scan", "corpse-clean",
            "apk-scan", "apk-clean"
        )

        private val ALLOWED_CONFIG: Map<String, IntRange> = mapOf(
            "enabled" to 0..1,
            "screen_off_only" to 0..1,
            "charging_only" to 0..1,
            "device_idle_only" to 0..1,
            "min_battery" to 0..100,
            "max_battery_temp" to 30..60,
            "max_run_minutes" to 5..180,
            "scan_root_workers" to 0..2,
            "scan_parallel_min_items" to 100..10_000_000,
            "scan_parallel_min_gain_percent" to 5..50,
            "scan_parallel_reprobe_runs" to 2..50,
            "scan_parallel_failure_cooldown_hours" to 1..168,
            "schedule_cache_enabled" to 0..1,
            "schedule_cache_hours" to 1..720,
            "schedule_empty_enabled" to 0..1,
            "schedule_empty_hours" to 1..720,
            "schedule_rules_enabled" to 0..1,
            "schedule_rules_hours" to 1..720,
            "schedule_fragment_enabled" to 0..1,
            "schedule_fragment_hours" to 1..720,
            "schedule_deep_enabled" to 0..1,
            "schedule_deep_hours" to 1..720,
            "daily_schedule_enabled" to 0..1,
            "daily_schedule_hour" to 0..23,
            "daily_schedule_minute" to 0..59,
            "daily_grace_minutes" to 15..720,
            "clean_app_cache" to 0..1,
            "clean_external_cache" to 0..1,
            "clean_system_logs" to 0..1,
            "clean_oem_logs" to 0..1,
            "clean_empty_files" to 0..1,
            "clean_empty_dirs" to 0..1,
            "clean_root_shells" to 0..1,
            "clean_app_rules" to 0..1,
            "clean_hidden_junk" to 0..1,
            "clean_fragments" to 0..1,
            "clean_custom_rules" to 0..1,
            "clean_installer_temp" to 0..1,
            "clean_apk_packages" to 0..1,
            "notify_on_complete" to 0..1,
            "notify_zero_result" to 0..1,
            "deep_high_risk_enabled" to 0..1,
            "app_cache_days" to 0..365,
            "external_cache_days" to 0..365,
            "system_logs_days" to 0..365,
            "oem_logs_days" to 0..365,
            "empty_file_days" to 0..365,
            "hidden_junk_days" to 0..365,
            "fragment_days" to 0..365,
            "installer_temp_days" to 1..30,
            "apk_package_days" to 0..365,
            "apk_package_max_mb" to 16..16_384,
            "root_shell_days" to 1..90,
            "max_file_mb" to 16..16_384
        )
    }
}
