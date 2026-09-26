package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject

/** Configuration and device prerequisites shared by the screen and the actual executor. */
internal object ToolboxAvailability {
    fun check(id: String, config: JSONObject, environment: JSONObject): JSONObject {
        fun blocked(message: String, unsupported: Boolean = false) = JSONObject()
            .put("available", false).put("unsupported", unsupported).put("message", message)
        val installed = strings(environment.optJSONArray("installedPackages"))
        val eligible = strings(environment.optJSONArray("eligiblePackages"))
        val unprotected = installed - strings(environment.optJSONArray("protectedPackages"))
        if (id in ToolboxCatalog.existingIds) return blocked("请在原有扫描工作台或文件归类页面操作")
        ToolboxCatalog.appGroups[id]?.let { group ->
            if (group.none { it in installed }) return blocked("未安装对应应用")
            if (group.none { it in unprotected }) return blocked("对应应用已加入保护名单")
        }
        when (id) {
            "mounter" -> {
                if (config.optString("downloadRules").isBlank()) return blocked("先设置来源目录与目标目录")
                if (config.optBoolean("bindRedirect") && !environment.optBoolean("bindSupported"))
                    return blocked("当前系统不支持目录重定向，可关闭重定向后仅转移文件", true)
            }
            "logcat" -> if (!environment.optBoolean("logcatSupported")) return blocked("系统日志命令不可用", true)
            "memory" -> if (!environment.optBoolean("memorySupported")) return blocked("内核未开放页缓存回收接口", true)
            "dirty" -> if (!environment.optBoolean("gcSupported")) return blocked("当前文件系统或内核不支持 F2FS 维护", true)
            "process", "database", "dex2" -> {
                val key = when (id) { "process" -> "processPackages"; "database" -> "databasePackages"; else -> "compilePackages" }
                var selected = ToolboxConfig.packages(config.optString(key)).intersect(eligible)
                if (id == "process") selected = selected - ToolboxConfig.packages(config.optString("processWhitelist"))
                if (selected.isEmpty()) return blocked("先选择可处理的应用；保护名单中的应用会被跳过")
                if (id == "process" && !environment.optBoolean("processSupported")) return blocked("系统进程管理接口不可用", true)
                if (id == "process" && config.optString("processMode") == "freeze" && !environment.optBoolean("freezeSupported"))
                    return blocked("当前系统未提供进程冻结接口，请选择其他管理方式", true)
                if (id == "dex2" && !environment.optBoolean("compileSupported")) return blocked("系统应用编译接口不可用", true)
            }
        }
        return JSONObject().put("available", true).put("message", "可执行")
    }
    fun status(result: JSONObject): String = when {
        result.optBoolean("cancelled") -> "已停止"
        result.optBoolean("unsupported") -> "不支持"
        !result.optBoolean("success") -> "未完成"
        result.optBoolean("skipped") -> "已跳过"
        result.optBoolean("requestedOnly") -> "已提交请求"
        else -> "已完成"
    }
    private fun strings(array: JSONArray?): Set<String> = if (array == null) emptySet()
        else (0 until array.length()).map { array.getString(it) }.toSet()
}
