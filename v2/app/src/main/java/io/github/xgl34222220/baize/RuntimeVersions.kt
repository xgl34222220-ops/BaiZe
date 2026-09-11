package io.github.xgl34222220.baize

import org.json.JSONObject

internal data class ComponentVersion(val name: String?, val code: Long?) {
    val label: String get() = "${name ?: "未知"} (${code ?: "未知"})"

    fun compareWith(app: ComponentVersion): VersionComparison = when {
        name != null && app.name != null && name != app.name -> VersionComparison.MISMATCH
        code != null && app.code != null && code != app.code -> VersionComparison.MISMATCH
        name == null || code == null || app.name == null || app.code == null -> VersionComparison.UNKNOWN
        else -> VersionComparison.MATCH
    }

    companion object {
        fun parse(name: Any?, code: Any?): ComponentVersion {
            val normalized = (name as? String)?.trim()?.replaceFirst(Regex("^[vV]"), "")
                ?.takeIf { it.matches(Regex("[0-9]+(?:\\.[0-9]+)+(?:[-+][0-9A-Za-z.-]+)?")) }
            val number = when (code) {
                is Int -> code.toLong()
                is Long -> code
                is String -> code.trim().takeIf { it.matches(Regex("[0-9]+")) }?.toLongOrNull()
                else -> null
            }?.takeIf { it > 0 }
            return ComponentVersion(normalized, number)
        }
    }
}

internal enum class VersionComparison { MATCH, MISMATCH, UNKNOWN }

internal data class RuntimeVersions(
    val root: ComponentVersion,
    val module: ComponentVersion,
    val moduleName: String? = null
) {
    fun warning(app: ComponentVersion): String {
        val mismatches = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        listOf("Root" to root, "模块" to module).forEach { (label, version) ->
            when (version.compareWith(app)) {
                VersionComparison.MISMATCH -> mismatches += "$label ${version.label}"
                VersionComparison.UNKNOWN -> unknown += label
                VersionComparison.MATCH -> Unit
            }
        }
        return buildList {
            if (mismatches.isNotEmpty()) {
                add("版本不一致：App ${app.label}；${mismatches.joinToString("、")}。任务结束后更新配套 App/模块并重启设备。")
            }
            if (unknown.isNotEmpty()) {
                add("${unknown.joinToString("、")}版本未知，无法确认匹配（旧服务或版本字段缺失/无效）；请查看运行诊断，确认已安装配套版本。")
            }
        }.joinToString("\n")
    }

    fun toJson(): JSONObject = JSONObject()
        .put("rootVersionName", root.name ?: JSONObject.NULL)
        .put("rootVersionCode", root.code ?: JSONObject.NULL)
        .put("moduleVersionName", module.name ?: JSONObject.NULL)
        .put("moduleVersionCode", module.code ?: JSONObject.NULL)
        .put("moduleName", moduleName ?: JSONObject.NULL)

    companion object {
        fun fromPing(json: JSONObject): RuntimeVersions = RuntimeVersions(
            ComponentVersion.parse(json.opt("rootVersionName"), json.opt("rootVersionCode")),
            ComponentVersion.parse(json.opt("moduleVersionName"), json.opt("moduleVersionCode")),
            (json.opt("moduleName") as? String)?.trim()?.take(128)?.takeIf { it.isNotBlank() }
        )
    }
}

internal object DiagnosticLabels {
    fun versionObservation(current: Boolean): String = if (current) {
        "本次连接最近观测（非实时保证）"
    } else {
        "历史版本缓存（已断开或尚未在本次连接验证，不代表当前运行版本）"
    }

    fun crashRecord(kind: String, record: String?): String =
        "$kind 历史崩溃记录（未与本次连接关联；时间接近也不能证明断连原因）\n" +
            (record ?: "暂无 $kind 崩溃记录")
}
