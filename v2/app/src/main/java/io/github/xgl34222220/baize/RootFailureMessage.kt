package io.github.xgl34222220.baize

import org.json.JSONObject

/**
 * Human-readable reason for a failed Root call. The Root side reports failures as
 * {"success":false,"error":code,"message":text}; a toast must surface that text
 * instead of collapsing every rejection into "未知错误".
 */
internal object RootFailureMessage {
    fun of(json: JSONObject?, throwable: Throwable?, fallback: String = "Root 服务没有返回原因"): String {
        val message = json?.optString("message").orEmpty().trim()
        val error = json?.optString("error").orEmpty().trim()
        val key = json?.optString("key").orEmpty().trim()
        val fromJson = when {
            message.isNotEmpty() && key.isNotEmpty() -> "$message（$key）"
            message.isNotEmpty() -> message
            error.isNotEmpty() -> describeCode(error) + if (key.isNotEmpty()) "（$key）" else ""
            else -> ""
        }
        if (fromJson.isNotEmpty()) return fromJson
        throwable?.let { failure ->
            val text = failure.message?.trim().orEmpty()
            return if (text.isNotEmpty()) text else failure.javaClass.simpleName.ifBlank { fallback }
        }
        return if (json != null && !json.has("success")) "Root 服务返回了无法识别的结果" else fallback
    }

    private fun describeCode(code: String): String = when (code) {
        "module_safety_update_required" -> "需要更新配套模块"
        "invalid_value" -> "配置值超出范围"
        "invalid_json" -> "计划配置格式无效"
        "empty_config" -> "没有可保存的计划配置"
        else -> code
    }
}
