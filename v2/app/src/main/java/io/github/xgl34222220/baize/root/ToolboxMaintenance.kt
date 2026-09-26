package io.github.xgl34222220.baize.root

import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.CancellationSignal
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class ToolboxMaintenance(
    private val context: Context,
    private val cancelled: AtomicBoolean,
    private val progress: (String) -> Unit,
    private val proc: File = File("/proc"),
    private val f2fs: File = File("/sys/fs/f2fs"),
    private val command: ToolboxCommand = ToolboxCommand(cancelled),
    private val data: File = File("/data"),
    private val state: File = File(RootPaths.STATE_DIR),
    private val cgroup: File = File("/sys/fs/cgroup"),
    private val restoreShell: String = "/system/bin/sh"
) {
    fun environment(config: JSONObject): JSONObject {
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getInstalledApplications(0)
        val protected = JSONArray(WhitelistRepository().packagesJson()).let { list ->
            (0 until list.length()).map { list.getString(it) }.toSet()
        }
        val eligible = installed.filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 &&
            it.uid % 100000 >= 10000 && it.packageName != context.packageName && it.packageName !in protected }
        val am = File("/system/bin/am").canExecute()
        val freeze = am && config.optString("processMode") == "freeze" &&
            command.run(listOf("/system/bin/am", "help"), 5, honourCancel = false, outputLimit = 64000).output.contains("freeze [")
        return JSONObject().put("installedPackages", JSONArray(installed.map { it.packageName }))
            .put("protectedPackages", JSONArray(protected.toList()))
            .put("eligiblePackages", JSONArray(eligible.map { it.packageName }))
            .put("processSupported", am).put("freezeSupported", freeze)
            .put("compileSupported", File("/system/bin/cmd").canExecute())
            .put("logcatSupported", File("/system/bin/logcat").canExecute())
            .put("memorySupported", File(proc, "sys/vm/drop_caches").canWrite() && File("/system/bin/sync").canExecute())
            .put("gcSupported", f2fs.listFiles().orEmpty().any { File(it, "gc_urgent").canWrite() && File(it, "dirty_segments").canRead() })
            .put("bindSupported", listOf("/system/bin/nsenter", "/system/xbin/nsenter").any { File(it).canExecute() } &&
                File("/system/bin/mount").canExecute() && File("/proc/1/mountinfo").canRead())
    }
    fun run(id: String, config: JSONObject): JSONObject = when (id) {
        "logcat" -> commandResult(command.run(listOf("/system/bin/logcat", "-b", "all", "-c")), "系统日志缓冲已清空")
        "memory" -> memory()
        "process" -> processes(config)
        "thaw" -> thaw()
        "dex2" -> compile(config)
        "database" -> databases(config)
        "dirty" -> gc(config)
        else -> error("不支持此维护任务")
    }
    fun device(): JSONObject {
        val memory = memoryInfo()
        val volumes = JSONArray()
        listOf("/data", "/system", "/vendor", "/product", "/metadata", "/cache").forEach { path ->
            val file = File(path)
            if (file.isDirectory && file.totalSpace > 0) volumes.put(JSONObject().put("path", path)
                .put("totalBytes", file.totalSpace).put("freeBytes", file.usableSpace))
        }
        val nodes = JSONArray()
        f2fs.listFiles()?.filter { it.isDirectory && File(it, "dirty_segments").isFile }?.forEach {
            nodes.put(JSONObject().put("device", it.name).put("dirtySegments", number(File(it, "dirty_segments")))
                .put("freeSegments", number(File(it, "free_segments"))).put("gcSupported", File(it, "gc_urgent").canWrite()))
        }
        return JSONObject().put("memory", memory).put("partitions", volumes).put("f2fs", nodes)
    }
    private fun memoryInfo(): JSONObject {
        val values = runCatching { File(proc, "meminfo").readLines().associate {
            it.substringBefore(':') to (it.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L)
        } }.getOrDefault(emptyMap())
        return JSONObject().put("totalKb", values["MemTotal"] ?: 0).put("availableKb", values["MemAvailable"] ?: 0)
    }
    private fun memory(): JSONObject {
        val node = File(proc, "sys/vm/drop_caches")
        if (!node.canWrite()) return unsupported("当前内核未开放缓存内存回收接口")
        val before = memoryInfo().optLong("availableKb")
        val sync = command.run(listOf("/system/bin/sync"))
        if (!sync.success) return commandResult(sync, "")
        if (cancelled.get()) return error("已停止").put("cancelled", true)
        node.writeText("1\n")
        val after = memoryInfo().optLong("availableKb")
        return ok("已请求回收页缓存；内存变化为观测值，会随系统负载波动")
            .put("requestedOnly", true)
            .put("memoryBeforeKb", before).put("memoryAfterKb", after).put("memoryDeltaKb", after - before)
    }
    private fun installedThirdParty(): Map<String, ApplicationInfo> {
        @Suppress("DEPRECATION")
        return context.packageManager.getInstalledApplications(0).filter {
            it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.uid % 100000 >= 10000 && it.packageName != context.packageName
        }.associateBy { it.packageName }
    }
    private fun selected(config: JSONObject, key: String): List<String> {
        val requested = ToolboxConfig.packages(config.optString(key))
        val allowed = installedThirdParty()
        val whitelist = JSONArray(WhitelistRepository().packagesJson()).let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
        return requested.filter { it in allowed && it !in whitelist }.sorted()
    }
    private data class AppProcess(val name: String, val score: Int, val pid: Int, val ticks: String, val uid: Int) {
        val first get() = name
        val second get() = score
    }
    private fun runningProcesses(): List<AppProcess> = proc.listFiles()?.asSequence()
        ?.filter { it.name.toIntOrNull() != null }?.mapNotNull { dir -> runCatching {
            val name = File(dir, "cmdline").readText().substringBefore('\u0000')
            AppProcess(name, File(dir, "oom_score_adj").readText().trim().toInt(), dir.name.toInt(), processTicks(dir), File(dir, "status").readLines().first { it.startsWith("Uid:") }.substringAfter(':').trim().split(Regex("\\s+"))[0].toInt())
        }.getOrNull() }?.toList().orEmpty()
    private fun processes(config: JSONObject): JSONObject {
        val targets = selected(config, "processPackages") - ToolboxConfig.packages(config.optString("processWhitelist"))
        if (targets.isEmpty()) return error("请先选择需要管理的第三方应用；白名单应用不会被结束")
        val mem = memoryInfo()
        val total = mem.optLong("totalKb")
        if (total <= 0) return unsupported("无法读取内存使用率")
        val used = (100L - mem.optLong("availableKb") * 100L / total).toInt()
        if (used < config.optInt("memoryThreshold", 80)) return ok("内存占用 $used%，未达到设置的阈值").put("skipped", true)
        val mode = config.optString("processMode", "kill")
        if (mode == "freeze") {
            val help = command.run(listOf("/system/bin/am", "help"), outputLimit = 64000)
            if (!help.output.contains("freeze [")) return unsupported("当前系统未提供进程冻结命令")
        }
        val details = JSONArray()
        var failed = 0
        for (pkg in targets) {
            if (cancelled.get()) break
            val active = runningProcesses().filter { it.first.substringBefore(':') == pkg && it.uid == installedThirdParty()[pkg]?.uid }
            if (active.isEmpty() || active.any { it.second < 400 }) {
                details.put(JSONObject().put("package", pkg).put("status", "skipped").put("message", "未运行或包含前台/可感知进程"))
                continue
            }
            progress(pkg)
            // ActivityManager applies its current importance policy again, closing the foreground race.
            if (mode == "kill") {
                if (config.optBoolean("skipFrozen", true) && active.any { frozen(it) }) {
                    details.put(JSONObject().put("package", pkg).put("status", "skipped").put("message", "保留已冻结应用")); continue
                }
                val result = command.run(listOf("/system/bin/am", "kill", "--user", "0", pkg))
                if (!result.success) failed++
                details.put(commandResult(result, "已向系统提交后台终止请求").put("requestedOnly", true).put("package", pkg))
            } else for (app in active) {
                if (cancelled.get()) break
                if (app.score < 900 || processTicks(File(proc, app.pid.toString())) != app.ticks) continue
                if (config.optBoolean("skipFrozen", true) && frozen(app)) continue
                if (mode == "freeze") {
                    val result = command.run(listOf("/system/bin/am", "freeze", app.pid.toString()))
                    // System-owned, non-sticky freezing preserves ActivityManager's foreground unfreeze policy.
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (result.success && !cancelled.get() && !frozen(app) && System.nanoTime() < deadline) Thread.sleep(100)
                    val observed = result.success && frozen(app)
                    if (!observed) failed++
                    if (observed) rememberProcess(app, "freeze", "0")
                    details.put(commandResult(result, "已请求系统冻结").put("success", observed)
                        .put("message", if (observed) "已观测到进程冻结" else result.output.ifBlank { "未观测到冻结完成" }).put("process", app.name))
                } else {
                    val node = File(proc, "${app.pid}/oom_score_adj")
                    val original = node.readText().trim()
                    if ((original.toIntOrNull() ?: -1) < 900) continue
                    rememberProcess(app, "oom", original)
                    node.writeText("1000\n")
                    val observed = node.readText().trim() == "1000"
                    if (!observed) failed++
                    details.put(JSONObject().put("success", observed).put("process", app.name)
                        .put("message", if (observed) "已调整缓存进程回收优先级" else "未观测到回收优先级生效"))
                }
            }
        }
        val applied = (0 until details.length()).count { details.getJSONObject(it).optBoolean("success") }
        return ok(if (applied == 0 && failed == 0) "没有符合条件的后台进程" else if (mode == "kill")
            "已向系统提交 $applied 项终止请求，失败 $failed 项" else "已观测到 $applied 项生效，失败 $failed 项")
            .put("success", failed == 0).put("failures", failed).put("details", details)
            .put("applied", applied).put("skipped", applied == 0 && failed == 0).put("requestedOnly", mode == "kill" && applied > 0)
    }
    private val processRecords get() = File(state, "toolbox/process-state.json")
    private fun processTicks(dir: File): String = runCatching {
        File(dir, "stat").readText().substringAfterLast(')').trim().split(Regex("\\s+"))[19]
    }.getOrDefault("")
    private fun frozen(app: AppProcess): Boolean = runCatching {
        val group = File(proc, "${app.pid}/cgroup").readLines().firstOrNull { it.startsWith("0::") }?.substringAfter("0::") ?: return@runCatching false
        if (!group.endsWith("/pid_${app.pid}")) return@runCatching false
        File(cgroup, group.trimStart('/') + "/cgroup.freeze").readText().trim() == "1"
    }.getOrDefault(false)
    private fun rememberProcess(app: AppProcess, mode: String, original: String) {
        val records = ToolboxConfig.read(processRecords)
        val key = "${app.pid}:${app.ticks}:$mode"
        if (!records.has(key)) records.put(key, JSONObject().put("pid", app.pid).put("ticks", app.ticks).put("name", app.name).put("mode", mode).put("original", original))
        RootFileStore.writeAtomic(processRecords, records.toString())
    }
    private fun thaw(): JSONObject {
        val records = ToolboxConfig.read(processRecords)
        val next = JSONObject()
        val details = JSONArray()
        val keys = records.keys().asSequence().toList()
        for (key in keys) {
            val row = records.getJSONObject(key)
            val pid = row.optInt("pid")
            val dir = File(proc, pid.toString())
            if (pid <= 1 || processTicks(dir) != row.optString("ticks")) continue
            val success = runCatching {
                if (row.optString("mode") == "freeze") command.run(listOf("/system/bin/am", "unfreeze", pid.toString()), honourCancel = false).success
                else {
                    val node = File(dir, "oom_score_adj")
                    if (node.readText().trim() == "1000") node.writeText(row.getString("original") + "\n")
                    true
                }
            }.getOrDefault(false)
            if (!success) next.put(key, row)
            details.put(JSONObject().put("process", row.optString("name")).put("success", success))
        }
        RootFileStore.writeAtomic(processRecords, next.toString())
        return ok("已恢复白泽管理的进程，剩余 ${next.length()} 项").put("success", next.length() == 0).put("details", details)
    }
    private fun compile(config: JSONObject): JSONObject {
        val packages = selected(config, "compilePackages")
        if (packages.isEmpty()) return error("请先选择需要编译的第三方应用")
        val details = JSONArray()
        var failures = 0
        for (pkg in packages) {
            if (cancelled.get()) break
            progress(pkg)
            val args = mutableListOf("/system/bin/cmd", "package", "compile", "-m", config.getString("compilerFilter"))
            if (config.optBoolean("forceCompile")) args += "-f"
            args += pkg
            val result = command.run(args, 180)
            val success = result.success && !result.output.contains("Failure", true) && !result.output.contains("Error:", true)
            if (!success) failures++
            details.put(commandResult(result, "编译完成").put("success", success).put("package", pkg)
                .put("message", if (success) "编译完成" else result.output.ifBlank { "系统未完成编译" }))
            if (!success && result.output.contains("Unknown", true)) break
        }
        return ok("已处理 ${details.length()} 个应用，编译失败 $failures 个").put("success", failures == 0)
            .put("failures", failures).put("details", details)
    }
    private fun databases(config: JSONObject): JSONObject {
        val packages = selected(config, "databasePackages")
        if (packages.isEmpty()) return error("请先选择需要优化数据库的第三方应用")
        val details = JSONArray()
        var failures = 0
        var optimized = 0
        var saved = 0L
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        try {
            for (pkg in packages) {
                if (cancelled.get()) break
                for (parent in listOf(File(data, "user/0/$pkg/databases"), File(data, "user_de/0/$pkg/databases"))) {
                    if (parent.canonicalPath != parent.absolutePath) continue
                    for (file in parent.listFiles().orEmpty().filter { it.isFile }.take(100)) {
                        if (cancelled.get()) break
                        if (file.length() < 100 || file.length() > 256L * 1024 * 1024 || file.canonicalPath != file.absolutePath) continue
                        val header = ByteArray(16)
                        val sqlite = runCatching { file.inputStream().use { it.read(header) }; header.toString(Charsets.UTF_8) == "SQLite format 3\u0000" }.getOrDefault(false)
                        if (!sqlite) continue
                        if (runningProcesses().any { it.first.substringBefore(':') == pkg } ||
                            listOf("-wal", "-journal").any { File(file.path + it).length() > 0 }) {
                            details.put(JSONObject().put("path", file.path).put("status", "skipped").put("message", "应用正在运行或有待提交日志"))
                            continue
                        }
                        progress(file.path)
                        val signal = CancellationSignal()
                        val timeout = watchdog.schedule({ signal.cancel() }, 30, TimeUnit.SECONDS)
                        val cancel = watchdog.scheduleWithFixedDelay({ if (cancelled.get()) signal.cancel() }, 100, 100, TimeUnit.MILLISECONDS)
                        val stat = Os.stat(file.path)
                        val before = file.length()
                        try {
                            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                                DatabaseErrorHandler { throw SQLiteException("数据库校验失败，已停止优化") }).use { db ->
                                db.rawQuery("PRAGMA busy_timeout=1000", null, signal).use { it.moveToFirst() }
                                db.rawQuery("PRAGMA optimize", null, signal).use { it.moveToFirst() }
                                if (config.optBoolean("vacuum")) db.rawQuery("VACUUM", null, signal).use { it.moveToFirst() }
                            }
                            optimized++
                            saved += (before - file.length()).coerceAtLeast(0)
                            details.put(JSONObject().put("path", file.path).put("success", true))
                        } catch (e: Exception) {
                            failures++
                            details.put(JSONObject().put("path", file.path).put("success", false).put("message", e.message.orEmpty()))
                        } finally {
                            timeout.cancel(false); cancel.cancel(false)
                            for (suffix in listOf("", "-journal", "-wal", "-shm")) {
                                val existing = File(file.path + suffix)
                                if (existing.isFile && existing.canonicalPath == existing.absolutePath) runCatching {
                                    Os.chown(existing.path, stat.st_uid, stat.st_gid); Os.chmod(existing.path, stat.st_mode and 511)
                                }
                            }
                            command.run(listOf("/system/bin/restorecon", file.path), honourCancel = false)
                        }
                    }
                }
            }
        } finally { watchdog.shutdownNow() }
        return ok(if (optimized == 0 && failures == 0) "没有可优化的空闲标准数据库" else "优化 $optimized 个数据库，失败 $failures 个").put("success", failures == 0)
            .put("skipped", optimized == 0 && failures == 0)
            .put("optimized", optimized).put("compactedBytes", saved).put("failures", failures).put("details", details)
    }
    private fun gc(config: JSONObject): JSONObject {
        val nodes = f2fs.listFiles()?.filter { File(it, "gc_urgent").canWrite() && File(it, "dirty_segments").isFile }.orEmpty()
        if (nodes.isEmpty()) return unsupported("当前文件系统不是 F2FS，或内核未开放 GC 接口")
        val details = JSONArray()
        for (dir in nodes) {
            if (cancelled.get()) break
            val before = number(File(dir, "dirty_segments"))
            if (before < 0) { details.put(JSONObject().put("device", dir.name).put("success", false).put("message", "无法读取脏段数量")); continue }
            val threshold = config.optInt("dirtyThreshold", 1000)
            if (before <= threshold) { details.put(JSONObject().put("device", dir.name).put("skipped", true).put("before", before)); continue }
            val node = File(dir, "gc_urgent")
            val original = node.readText().trim()
            require(original.toIntOrNull() in 0..2) { "无法识别内核 GC 状态" }
            // A separate root shell restores the value even if the Binder process is killed.
            val backup = File(state, "toolbox/gc-${dir.name}.restore")
            RootFileStore.writeAtomic(backup, original)
            val guard = ProcessBuilder(restoreShell, "-c",
                "sleep \"\$1\"; if [ -f \"\$3\" ]; then cat \"\$3\" > \"\$2\" && rm -f \"\$3\"; fi",
                "baize-gc-restore", (config.optInt("gcSeconds", 15) + 5).toString(), node.path, backup.path)
                .redirectErrorStream(true).redirectOutput(File("/dev/null")).start()
            var restored = false
            try {
                node.writeText("1\n")
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.optInt("gcSeconds", 15).toLong())
                while (!cancelled.get() && System.nanoTime() < deadline && number(File(dir, "dirty_segments")) > threshold) {
                    progress("${dir.name} · 脏段 ${number(File(dir, "dirty_segments"))}")
                    Thread.sleep(250)
                }
            } finally {
                restored = runCatching { node.writeText("$original\n"); node.readText().trim() == original }.getOrDefault(false)
                if (restored) { backup.delete(); guard.destroy() }
            }
            details.put(JSONObject().put("device", dir.name).put("before", before)
                .put("after", number(File(dir, "dirty_segments"))).put("restored", restored))
        }
        val success = (0 until details.length()).all { details.getJSONObject(it).let { it.optBoolean("restored", true) && it.optBoolean("success", true) } }
        val performed = (0 until details.length()).count { details.getJSONObject(it).has("restored") }
        return ok(if (success && performed == 0) "脏段未达到阈值，无需维护" else "F2FS 维护已结束，脏段变化单独记录")
            .put("success", success).put("skipped", success && performed == 0).put("details", details)
    }
    private fun number(file: File) = runCatching { file.readText().trim().toLong() }.getOrDefault(-1)
    private fun commandResult(result: ToolboxCommand.Result, message: String) = JSONObject()
        .put("success", result.success).put("exitCode", result.exit).put("cancelled", result.cancelled).put("timedOut", result.timedOut)
        .put("message", if (result.success) message else if (result.timedOut) "执行超时" else result.output.ifBlank { "系统命令执行失败" })
        .put("output", result.output)
    private fun ok(message: String) = JSONObject().put("success", true).put("message", message)
    private fun error(message: String) = JSONObject().put("success", false).put("message", message)
    private fun unsupported(message: String) = error(message).put("unsupported", true)
}
