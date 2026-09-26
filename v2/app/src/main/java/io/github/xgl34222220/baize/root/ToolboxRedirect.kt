package io.github.xgl34222220.baize.root

import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit directory bind rules, applied and verified in init's mount namespace. */
internal class ToolboxRedirect(private val cancelled: AtomicBoolean) {
    private val records = File(RootPaths.STATE_DIR, "toolbox/mounts.json")
    private val command = ToolboxCommand(cancelled)
    private fun nsenter(): File? = listOf("/system/bin/nsenter", "/system/xbin/nsenter").map(::File).firstOrNull { it.canExecute() }
    private fun global(path: String) = File("/proc/1/root$path")
    private fun matches(source: String, target: String): Boolean = runCatching {
        val a = Os.stat(global(source).path); val b = Os.stat(global(target).path)
        a.st_dev == b.st_dev && a.st_ino == b.st_ino
    }.getOrDefault(false)
    private fun mounted(path: String): Boolean = runCatching {
        File("/proc/1/mountinfo").useLines { lines -> lines.any { it.split(' ').getOrNull(4)?.replace("\\040", " ") == path } }
    }.getOrDefault(false)
    fun unmountedRules(raw: String): String {
        val stored = ToolboxConfig.read(records)
        return directoryRules(raw).filter { rule ->
            if (!mounted(rule.source)) true else {
                require(stored.optString(rule.source) == rule.destination && matches(rule.source, rule.destination)) { "来源已有其他挂载，未执行转移" }
                false
            }
        }.joinToString("\n") { "${it.source}+${it.destination}" }
    }
    fun apply(raw: String): JSONObject {
        val executable = nsenter() ?: return error("系统未提供 nsenter，文件转移已完成，未启用挂载重定向")
        val rules = directoryRules(raw)
        val stored = ToolboxConfig.read(records)
        val details = JSONArray()
        var failed = 0
        for (rule in rules) {
            if (cancelled.get()) break
            val result = runCatching {
                val source = File(rule.source); val target = File(rule.destination)
                val protected = JSONArray(WhitelistRepository().pathsJson())
                for (i in 0 until protected.length()) {
                    val path = ToolboxFileRules.normalizePath(protected.getString(i))
                    require(listOf(rule.source, rule.destination).none { it == path || it.startsWith("$path/") || path.startsWith("$it/") }) { "目录命中保护白名单" }
                }
                require(source.isDirectory && target.isDirectory && source.canonicalPath == source.absolutePath && target.canonicalPath == target.absolutePath) { "来源/目标目录不存在或包含链接" }
                if (mounted(rule.source)) {
                    require(matches(rule.source, rule.destination) && stored.optString(rule.source) == rule.destination) { "来源已有其他挂载，未覆盖" }
                    JSONObject().put("success", true).put("message", "重定向已存在")
                } else {
                    var visited = 0
                    val stack = java.util.ArrayDeque<File>(); stack.add(source)
                    while (!stack.isEmpty()) {
                        val file = stack.removeLast(); visited++
                        require(visited <= 10000 && file.isDirectory && file.canonicalPath == file.absolutePath) { "来源还有未转移文件或链接，未覆盖挂载" }
                        val children = file.listFiles() ?: throw IllegalStateException("无法核对来源目录")
                        children.forEach { stack.add(it) }
                    }
                    // Record before mutation so an interrupted request remains removable from the UI.
                    stored.put(rule.source, rule.destination); RootFileStore.writeAtomic(records, stored.toString())
                    val run = command.run(listOf(executable.path, "-t", "1", "-m", "--", "/system/bin/mount", "--bind", rule.destination, rule.source))
                    val verified = run.success && mounted(rule.source) && matches(rule.source, rule.destination)
                    if (!verified && !mounted(rule.source)) { stored.remove(rule.source); RootFileStore.writeAtomic(records, stored.toString()) }
                    JSONObject().put("success", verified).put("message", if (verified)
                        "已验证系统挂载；已打开的下载应用可能需要重启" else run.output.ifBlank { "未验证到目录重定向" })
                }
            }.getOrElse { error(it.message.orEmpty()) }
            if (!result.optBoolean("success")) failed++
            details.put(result.put("source", rule.source).put("target", rule.destination))
        }
        return JSONObject().put("success", failed == 0 && !cancelled.get()).put("details", details)
            .put("message", "目录重定向：${details.length()} 条，失败 $failed 条；重启后需再次执行或由计划恢复")
    }
    fun remove(): JSONObject {
        val executable = nsenter() ?: return error("系统未提供 nsenter，不能解除挂载")
        val stored = ToolboxConfig.read(records)
        val details = JSONArray()
        for (source in stored.keys().asSequence().toList().sortedByDescending(String::length)) {
            val target = stored.getString(source)
            val success = if (!mounted(source)) true else if (!matches(source, target)) false else
                command.run(listOf(executable.path, "-t", "1", "-m", "--", "/system/bin/umount", source), honourCancel = false).success && !mounted(source)
            if (success) stored.remove(source)
            details.put(JSONObject().put("source", source).put("success", success))
            RootFileStore.writeAtomic(records, stored.toString())
        }
        return JSONObject().put("success", stored.length() == 0).put("details", details)
            .put("message", "重定向已解除 ${details.length() - stored.length()} 条；目标文件保留，可再撤销转移")
    }
    private fun error(message: String) = JSONObject().put("success", false).put("message", message)
    companion object {
        fun directoryRules(raw: String): List<ToolboxFileRules.Rule> = ToolboxFileRules.parse(raw).also { rules ->
            require(rules.isNotEmpty()) { "请先设置目录规则" }
            require(rules.all { it.patterns == listOf("*") && it.source.none { c -> c in "*?" } }) { "绑定重定向仅支持完整目录：来源+目标，不能按文件后缀过滤" }
            require(rules.all { rule -> rules.none { other -> rule !== other &&
                (other.source == rule.source || other.destination == rule.source || other.source.startsWith("${rule.source}/") || other.destination.startsWith("${rule.source}/")) } }) { "重定向规则不能嵌套或循环" }
        }
    }
}
