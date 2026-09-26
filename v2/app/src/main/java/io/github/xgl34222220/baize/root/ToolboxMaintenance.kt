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
    private val f2fs: File = File("/sys/fs/f2fs")
) {
    private val command = ToolboxCommand(cancelled)
    fun run(id: String, config: JSONObject): JSONObject = when (id) {
        "logcat" -> commandResult(command.run(listOf("/system/bin/logcat", "-b", "all", "-c")), "系统日志缓冲已清空")
        "memory" -> memory()
        "process" -> processes(config)
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
    private fun runningProcesses(): List<Pair<String, Int>> = proc.listFiles()?.asSequence()
        ?.filter { it.name.toIntOrNull() != null }?.mapNotNull { dir -> runCatching {
            val name = File(dir, "cmdline").readText().substringBefore('\u0000')
            name to File(dir, "oom_score_adj").readText().trim().toInt()
        }.getOrNull() }?.toList().orEmpty()
    private fun processes(config: JSONObject): JSONObject {
        val targets = selected(config, "processPackages") - ToolboxConfig.packages(config.optString("processWhitelist"))
        if (targets.isEmpty()) return error("请先选择需要管理的第三方应用；白名单应用不会被结束")
        val mem = memoryInfo()
        val total = mem.optLong("totalKb")
        if (total <= 0) return unsupported("无法读取内存使用率")
        val used = (100L - mem.optLong("availableKb") * 100L / total).toInt()
        if (used < config.optInt("memoryThreshold", 80)) return ok("内存占用 $used%，未达到设置的阈值").put("skipped", true)
        val details = JSONArray()
        var failed = 0
        for (pkg in targets) {
            if (cancelled.get()) break
            val active = runningProcesses().filter { it.first.substringBefore(':') == pkg }
            if (active.isEmpty() || active.any { it.second < 400 }) {
                details.put(JSONObject().put("package", pkg).put("status", "skipped").put("message", "未运行或包含前台/可感知进程"))
                continue
            }
            progress(pkg)
            // ActivityManager applies its current importance policy again, closing the foreground race.
            val result = command.run(listOf("/system/bin/am", "kill", "--user", "0", pkg))
            if (!result.success) failed++
            details.put(commandResult(result, "已向系统提交后台终止请求").put("package", pkg))
        }
        return ok("后台管理完成，失败 $failed 项").put("success", failed == 0).put("failures", failed).put("details", details)
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
            details.put(commandResult(result, "编译完成").put("success", success).put("package", pkg))
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
                for (parent in listOf(File("/data/user/0/$pkg/databases"), File("/data/user_de/0/$pkg/databases"))) {
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
        return ok("优化 $optimized 个数据库，失败 $failures 个").put("success", failures == 0)
            .put("optimized", optimized).put("compactedBytes", saved).put("failures", failures).put("details", details)
    }
    private fun gc(config: JSONObject): JSONObject {
        val nodes = f2fs.listFiles()?.filter { File(it, "gc_urgent").canWrite() && File(it, "dirty_segments").isFile }.orEmpty()
        if (nodes.isEmpty()) return unsupported("当前文件系统不是 F2FS，或内核未开放 GC 接口")
        val details = JSONArray()
        for (dir in nodes) {
            if (cancelled.get()) break
            val before = number(File(dir, "dirty_segments"))
            if (before < 0) continue
            val threshold = config.optInt("dirtyThreshold", 1000)
            if (before <= threshold) { details.put(JSONObject().put("device", dir.name).put("skipped", true).put("before", before)); continue }
            val node = File(dir, "gc_urgent")
            val original = node.readText().trim()
            require(original.toIntOrNull() in 0..2) { "无法识别内核 GC 状态" }
            // A separate root shell restores the value even if the Binder process is killed.
            val backup = File(RootPaths.STATE_DIR, "toolbox/gc-${dir.name}.restore")
            RootFileStore.writeAtomic(backup, original)
            val guard = ProcessBuilder("/system/bin/sh", "-c",
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
        val success = (0 until details.length()).all { details.getJSONObject(it).optBoolean("restored", true) }
        return ok("F2FS 维护已结束，脏段变化单独记录").put("success", success).put("details", details)
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
