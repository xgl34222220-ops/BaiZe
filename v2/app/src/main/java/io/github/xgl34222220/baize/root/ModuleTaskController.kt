package io.github.xgl34222220.baize.root

import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

internal class ModuleTaskController(
    private val coordinator: TaskCoordinator,
    private val schedulerRepository: SchedulerRepository,
    private val diagnostics: DiagnosticRepository
) {
    fun startDetachedModuleTask(mode: String, started: Long): String {
        val worker = File(RootPaths.MODULE_DIR, "task-worker.sh")
        if (!worker.isFile) {
            return JSONObject().put("error", "worker_missing")
                .put("message", "独立 Root Worker 缺失，请重新刷入完整模块").toString()
        }
        val existing = RootFileStore.readEnv(File(RootPaths.STATE_DIR, "running.env"))
        if (existing.length() > 0 && existing.optString("mode").isNotBlank()) return coordinator.busy("module-$mode")
        val stateDir = File(RootPaths.STATE_DIR).apply { mkdirs() }
        val taskId = "${System.currentTimeMillis()}-${Process.myPid()}"
        val launcherLog = File(stateDir, "logs/launcher-$taskId.log").apply { parentFile?.mkdirs() }
        val process = ProcessBuilder("/system/bin/sh", worker.absolutePath, mode, "app", taskId)
            .redirectErrorStream(true).redirectOutput(launcherLog).start()
        val exited = process.waitFor(8, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            return JSONObject().put("error", "worker_launch_timeout")
                .put("message", "独立 Root Worker 启动超时").toString()
        }
        val code = process.exitValue()
        if (code != 0) {
            val output = RootFileStore.tailText(launcherLog, 2_000).trim()
            return JSONObject().put("error", if (code == 3) "busy" else "worker_launch_failed")
                .put("exitCode", code)
                .put("message", output.ifBlank { "独立 Root Worker 启动失败（代码 $code）" }).toString()
        }
        coordinator.setModuleState(
            operation = "module-$mode",
            phase = "独立 Root Worker 已启动",
            startedRealtime = started,
            extras = JSONObject().put("taskId", taskId)
        )
        return JSONObject().put("success", true).put("accepted", true).put("running", true)
            .put("mode", mode).put("taskId", taskId)
            .put("message", "清理任务已交给独立 Root Worker，关闭 App 仍会继续").toString()
    }

    fun executeModuleTask(mode: String, started: Long): String {
        val cleaner = File(RootPaths.MODULE_DIR, "cleaner.sh")
        if (!cleaner.isFile) {
            return JSONObject()
                .put("error", "cleaner_missing")
                .put("message", "模块清理引擎缺失，请重新刷入完整模块")
                .toString()
        }

        val stateDir = File(RootPaths.STATE_DIR).apply { mkdirs() }
        File(stateDir, "stop").delete()
        val logDir = File(stateDir, "logs").apply { mkdirs() }
        val log = File(logDir, "app-${mode}-${System.currentTimeMillis()}.log")
        val process = ProcessBuilder("/system/bin/sh", cleaner.absolutePath, mode, "app")
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()

        while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
            if (coordinator.cancelled.get()) {
                runCatching { File(stateDir, "stop").writeText("1\n") }
            }
            val running = RootFileStore.readEnv(File(stateDir, "running.env"))
            val phase = running.optString("phase").ifBlank {
                if (mode == "scan") "正在执行安全扫描" else "正在自动扫描并清理"
            }
            coordinator.setModuleState(
                operation = "module-$mode",
                phase = phase,
                startedRealtime = started,
                extras = running
            )
        }

        val code = process.exitValue()
        val elapsed = SystemClock.elapsedRealtime() - started
        val totals = RootFileStore.readEnv(File(stateDir, "totals.env"))
        val latest = RootFileStore.readEnv(File(stateDir, "latest.env"))
        val latestReport = File(stateDir, "reports/latest.tsv")
        val appDetails = appDetailsJson(
            File(stateDir, "reports/apps-latest.tsv"),
            File(stateDir, "reports/app-items-latest.tsv")
        )
        val output = RootFileStore.tailText(log, 12_000)
        return JSONObject()
            .put("success", code == 0)
            .put("mode", mode)
            .put("exitCode", code)
            .put("cancelled", code == 9 || coordinator.cancelled.get())
            .put("elapsedMs", elapsed)
            .put("output", output)
            .put("totals", totals)
            .put("latest", latest)
            .put("scanPerformance", scanPerformanceJson())
            .put("latestReport", if (latestReport.isFile) latestReport.absolutePath else "")
            .put("logName", log.name)
            .put("appDetails", appDetails)
            .put("otherDetails", otherDetailsJson(latestReport))
            .put("coverage", diagnostics.scanCoverage())
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
            if (!RootValidation.packageName.matches(packageName)) return
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
                if (suffix.isNotBlank() && RootValidation.packageName.matches(suffix)) return@forEachLine
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

    fun moduleState(): String {
        val stateDir = File(RootPaths.STATE_DIR)
        val scheduler = RootFileStore.readEnv(File(stateDir, "scheduler.env"))
        val module = RootFileStore.readEnv(File(stateDir, "module.env"))
        val totals = RootFileStore.readEnv(File(stateDir, "totals.env"))
        val latest = RootFileStore.readEnv(File(stateDir, "latest.env"))
        val running = RootFileStore.readEnv(File(stateDir, "running.env"))
        return JSONObject()
            .put("moduleInstalled", File(RootPaths.MODULE_DIR, "module.prop").isFile)
            .put("cleanerReady", File(RootPaths.MODULE_DIR, "cleaner.sh").isFile)
            .put("rulesReady", File(RootPaths.MODULE_DIR, "config/deep.rules").isFile)
            .put("scheduler", scheduler)
            .put("supervisor", RootFileStore.readEnv(File(stateDir, "supervisor.env")))
            .put("appInstall", RootFileStore.readEnv(File(stateDir, "app-install.env")))
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
            .put("coverage", diagnostics.scanCoverage())
            .put("running", running)
            .put("config", JSONObject(schedulerRepository.configJson()))
            .toString()
    }

    private fun scanPerformanceJson(): JSONObject {
        val stateDir = File(RootPaths.STATE_DIR)
        val cache = RootFileStore.readEnv(File(stateDir, "cache_scan.env"))
        val profile = RootFileStore.readEnv(File(stateDir, "root-worker-profile.env"))
        val config = JSONObject(schedulerRepository.configJson())
        val requestedMode = config.optInt("scan_root_workers", 0).coerceIn(0, 4)
        val actualWorkers = cache.optInt("root_workers", profile.optInt("last_workers", 1)).coerceIn(1, 4)
        val recommendedWorkers = profile.optInt("recommended_workers", 1).coerceIn(1, 4)
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
}
