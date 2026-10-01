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
    val moduleName: String? = null,
    val moduleInstalled: Boolean? = null
) {
    /** Successful compatibility is information, never a warning or a global banner. */
    fun warning(app: ComponentVersion): String = assessment(app).first

    fun compatibilityNote(app: ComponentVersion): String = assessment(app).second

    fun presentation(app: ComponentVersion, current: Boolean): RuntimeVersionPresentation {
        val (warning, compatible) = assessment(app)
        return RuntimeVersionPresentation(
            // A cached observation cannot describe the engine that is starting now.
            warning = if (current) warning else "",
            details = buildList {
                add("App ${app.label}")
                add(DiagnosticLabels.versionObservation(current))
                add("Root ${root.label}")
                add(if (moduleInstalled == false) "未安装自动清理模块" else "模块 ${module.label}")
                if (warning.isNotBlank()) add(warning)
                if (compatible.isNotBlank()) add(compatible)
            }.joinToString("\n")
        )
    }

    private fun assessment(app: ComponentVersion): Pair<String, String> {
        val mismatches = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        val compatible = mutableListOf<String>()
        val components = listOf("Root" to root) +
            if (moduleInstalled == false) emptyList() else listOf("模块" to module)
        components.forEach { (label, version) ->
            // 30011 adds an App-owned protection request. Its Root process must be current;
            // the scheduling module still uses the unchanged 30008 scripts and binaries.
            val compatibleCodes = when (app) {
                ComponentVersion("2.0.0", 30009L) -> setOf(30008L)
                ComponentVersion("2.0.0", 30010L) -> setOf(30008L, 30009L)
                ComponentVersion("2.0.0", 30011L) -> if (label == "模块") setOf(30008L, 30009L, 30010L) else emptySet()
                ComponentVersion("2.0.0", 30012L) -> if (label == "模块") setOf(30008L, 30009L, 30010L, 30011L) else emptySet()
                else -> emptySet()
            }
            if (version.name == "2.0.0" && version.code in compatibleCodes) {
                compatible += "$label ${version.label}"
                return@forEach
            }
            when (version.compareWith(app)) {
                VersionComparison.MISMATCH -> mismatches += "$label ${version.label}"
                VersionComparison.UNKNOWN -> unknown += label
                VersionComparison.MATCH -> Unit
            }
        }
        val warning = buildList {
            if (mismatches.isNotEmpty()) {
                add("版本不一致：App ${app.label}；${mismatches.joinToString("、")}。任务结束后更新配套 App/模块并重启设备。")
            }
            if (unknown.isNotEmpty()) {
                add("${unknown.joinToString("、")}版本未知，无法确认匹配（旧服务或版本字段缺失/无效）；请查看运行诊断，确认已安装配套版本。")
            }
        }.joinToString("\n")
        val note = if (compatible.isEmpty()) "" else "已验证兼容：${compatible.joinToString("、")}。" +
            if (warning.isEmpty()) {
                if (moduleInstalled == false) "当前 Root 组件可继续使用。" else "本次只需更新 App，当前模块可继续使用。"
            } else ""
        return warning to note
    }

    fun toJson(): JSONObject = JSONObject()
        .put("rootVersionName", root.name ?: JSONObject.NULL)
        .put("rootVersionCode", root.code ?: JSONObject.NULL)
        .put("moduleVersionName", module.name ?: JSONObject.NULL)
        .put("moduleVersionCode", module.code ?: JSONObject.NULL)
        .put("moduleName", moduleName ?: JSONObject.NULL)
        .put("module", moduleInstalled ?: JSONObject.NULL)

    companion object {
        fun fromPing(json: JSONObject): RuntimeVersions = RuntimeVersions(
            ComponentVersion.parse(json.opt("rootVersionName"), json.opt("rootVersionCode")),
            ComponentVersion.parse(json.opt("moduleVersionName"), json.opt("moduleVersionCode")),
            (json.opt("moduleName") as? String)?.trim()?.take(128)?.takeIf { it.isNotBlank() },
            json.opt("module") as? Boolean
        )
    }
}

internal data class RuntimeVersionPresentation(val warning: String, val details: String)

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
