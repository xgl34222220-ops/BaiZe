package io.github.xgl34222220.baize.root

import io.github.xgl34222220.baize.PerfToolsConfig
import io.github.xgl34222220.baize.PerfToolsPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 性能工具（实验）的 Root 侧读写：只读写固定文件名，执行交给模块 perf-tools.sh。
 * 配置变更写入 history.tsv（记录 / 审计时间线读取它），与模块的执行记录在同一处。
 */
internal object PerfToolsStore {
    const val CONFIG = "perf-tools.conf"
    const val WHITELIST = "perf-whitelist.conf"
    const val DB_BLACKLIST = "perf-db-blacklist.conf"
    private const val STATUS = "perf-tools.env"
    private const val DEX = "perf-dex2oat.env"
    private const val DB = "perf-dbopt.env"
    private const val LOG = "perf-tools.log"
    private const val HISTORY = "history.tsv"
    private val SQLITE_PATHS = listOf("/system/bin/sqlite3", "/system/xbin/sqlite3", "/vendor/bin/sqlite3",
        "/product/bin/sqlite3", "/system_ext/bin/sqlite3")

    fun read(stateDir: File): String {
        val script = RootPaths.script("perf-tools.sh")
        val log = File(stateDir, LOG).takeIf { it.isFile && it.length() < 256 * 1024 }?.readLines().orEmpty().takeLast(40)
        return JSONObject().put("success", true)
            .put("config", small(File(stateDir, CONFIG)))
            .put("whitelist", small(File(stateDir, WHITELIST)))
            .put("dbBlacklist", small(File(stateDir, DB_BLACKLIST)))
            .put("status", RootFileStore.readEnv(File(stateDir, STATUS)))
            .put("dex2oat", RootFileStore.readEnv(File(stateDir, DEX)))
            .put("dbopt", RootFileStore.readEnv(File(stateDir, DB)))
            .put("log", JSONArray(log))
            .put("sqlite3", SQLITE_PATHS.firstOrNull { File(it).let { f -> f.isFile && f.canExecute() } }.orEmpty())
            .put("freezer", freezerSupport())
            .put("moduleReady", script.isFile)
            .toString()
    }

    /** 三个文本都重新解析再写入；不合法的行直接丢弃。 */
    fun write(stateDir: File, configRaw: String, whitelistRaw: String, blacklistRaw: String): String {
        require(configRaw.length <= 16 * 1024 && whitelistRaw.length <= 64 * 1024 && blacklistRaw.length <= 64 * 1024) { "too_large" }
        val before = PerfToolsConfig.parse(small(File(stateDir, CONFIG)))
        val config = PerfToolsConfig.parse(configRaw)
        val whitelist = PerfToolsPolicy.normalizeList(whitelistRaw)
        val blacklist = PerfToolsPolicy.normalizeList(blacklistRaw).filterNot { ':' in it }
        RootFileStore.writeAtomic(File(stateDir, CONFIG), config.encode())
        RootFileStore.writeAtomic(File(stateDir, WHITELIST), whitelist.joinToString("\n", postfix = if (whitelist.isEmpty()) "" else "\n"))
        RootFileStore.writeAtomic(File(stateDir, DB_BLACKLIST), blacklist.joinToString("\n", postfix = if (blacklist.isEmpty()) "" else "\n"))
        val changes = describeChanges(before, config)
        if (changes.isNotEmpty()) appendHistory(stateDir, "性能工具·设置", changes.joinToString("；"), "app")
        return JSONObject().put("success", true).put("whitelist", whitelist.size).put("dbBlacklist", blacklist.size)
            .put("config", config.encode()).toString()
    }

    fun command(name: String): String {
        val script = RootPaths.script("perf-tools.sh")
        if (!script.isFile) return JSONObject().put("success", false).put("error", "module_missing")
            .put("message", "模块版本过旧，缺少性能工具脚本，请更新模块").toString()
        val action = when (name) {
            "dex2oat-start" -> "start-dex2oat"
            "dex2oat-stop" -> "stop-dex2oat"
            "thaw-all" -> "thaw-all"
            else -> throw IllegalArgumentException("unsupported_command")
        }
        val process = ProcessBuilder("/system/bin/sh", script.absolutePath, action)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText().take(2_000) }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return JSONObject().put("success", false).put("error", "timeout").put("message", "性能工具响应超时").toString()
        }
        val code = process.exitValue()
        val message = when {
            action == "start-dex2oat" && code == 3 -> "编译已在进行"
            action == "start-dex2oat" && code == 0 -> "已开始编译，关闭 App 也会继续"
            action == "stop-dex2oat" -> "已请求停止，当前应用编译完成后结束"
            action == "thaw-all" -> "已解冻 ${output.substringAfter("thawed=", "0").trim().toIntOrNull() ?: 0} 个进程"
            else -> output.trim()
        }
        if (action == "thaw-all") appendHistory(File(RootPaths.STATE_DIR), "性能工具·进程压制", "手动解冻全部：$message", "app")
        return JSONObject().put("success", code == 0 || code == 3).put("exitCode", code).put("message", message).toString()
    }

    /** cgroup v2 进程冻结是否可用：存在 /sys/fs/cgroup/uid_x/pid_y/cgroup.freeze（只看前几个目录）。 */
    private fun freezerSupport(): JSONObject {
        val root = File("/sys/fs/cgroup")
        val v2 = File(root, "cgroup.controllers").isFile
        val sample = if (!v2) null else root.listFiles { f -> f.isDirectory && f.name.startsWith("uid_") }
            ?.asSequence()?.take(8)
            ?.flatMap { uid -> uid.listFiles { f -> f.isDirectory && f.name.startsWith("pid_") }?.asSequence()?.take(2).orEmpty() }
            ?.firstOrNull { File(it, "cgroup.freeze").isFile }
        return JSONObject().put("cgroupV2", v2).put("supported", sample != null)
            .put("reason", when {
                !v2 -> "内核未挂载 cgroup v2"
                sample == null -> "未找到应用级 cgroup.freeze 节点"
                else -> ""
            })
    }

    private fun describeChanges(old: PerfToolsConfig, new: PerfToolsConfig): List<String> = buildList {
        fun onOff(v: Boolean) = if (v) "开启" else "关闭"
        if (old.dex2oatAuto != new.dex2oatAuto) add("自动编译${onOff(new.dex2oatAuto)}")
        if (old.dex2oatMode != new.dex2oatMode) add("编译模式 ${new.dex2oatMode.value}")
        if (old.dex2oatAllApps != new.dex2oatAllApps) add("编译范围 ${if (new.dex2oatAllApps) "全部" else "用户应用"}")
        if (old.dex2oatForce != new.dex2oatForce) add("强制重新编译${onOff(new.dex2oatForce)}")
        if (old.dbOptimizeAuto != new.dbOptimizeAuto) add("数据库优化${onOff(new.dbOptimizeAuto)}")
        if (old.dbForceStop != new.dbForceStop) add("优化前强制停止${onOff(new.dbForceStop)}")
        if (old.freezeEnabled != new.freezeEnabled) add("进程压制${onOff(new.freezeEnabled)}")
        if (old.freezeAfterMinutes != new.freezeAfterMinutes) add("后台时长 ${new.freezeAfterMinutes} 分钟")
        if (old.memEnabled != new.memEnabled) add("内存压制${onOff(new.memEnabled)}")
        if (old.memThresholdMb != new.memThresholdMb) add("内存阈值 ${new.memThresholdMb} MB")
        if (old.memCooldownSeconds != new.memCooldownSeconds) add("回收冷却 ${new.memCooldownSeconds} 秒")
        if (old.memKill != new.memKill) add("结束后台缓存进程${onOff(new.memKill)}")
    }

    private fun appendHistory(stateDir: File, operation: String, message: String, source: String) = runCatching {
        val file = File(stateDir, HISTORY)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val clean = message.replace('\t', ' ').replace('\n', ' ').take(500)
        val lines = (if (file.isFile) file.readLines().takeLast(99) else emptyList()) + "$time\t$operation\t0\t0\t0\t0\t$clean\t$source\t\t\tnot_applicable"
        RootFileStore.writeAtomic(file, lines.joinToString("\n", postfix = "\n"))
    }

    private fun small(file: File): String = file.takeIf { it.isFile && it.length() <= 64 * 1024 }?.readText().orEmpty()
}
