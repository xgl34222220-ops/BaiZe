package io.github.xgl34222220.baize

import androidx.compose.runtime.Immutable
import io.github.xgl34222220.baize.root.RunDetailReasons
import org.json.JSONArray
import org.json.JSONObject

/** 记录详情页的一行：一个未被删除的项目（失败或跳过），字段来自 Root 端的逐项明细。 */
@Immutable
internal data class RunDetailItem(
    val index: Int,
    val failed: Boolean,
    val reasonCode: String,
    val reasonText: String,
    val category: String,
    val count: Long,
    val bytes: Long,
    val path: String,
    val note: String,
    val state: String,
    val exists: Boolean,
    val directory: Boolean,
    val actionable: Boolean,
    val blockedReason: String,
    val protectionKind: String,
    val protectionRule: String
) {
    val hasPath: Boolean get() = path.startsWith('/')
    val protectedByUser: Boolean get() = protectionRule.isNotBlank()
    val done: Boolean get() = state.isNotBlank()

    /** 行内说明：分类 · 原因（中文）；已处理或已不存在时直接说明结果。 */
    val statusLine: String get() = when {
        state == "quarantined" -> "已移入回收站，可在回收站恢复"
        state == "deleted" -> "已永久删除"
        state == "gone" -> "已不存在"
        hasPath && !exists -> "已不存在（可能已被应用自己删除）"
        else -> listOf(category.ifBlank { if (failed) "删除失败" else "已跳过" }, reasonText)
            .filter(String::isNotBlank).distinct().joinToString(" · ")
    }

    val protectionText: String get() = if (protectedByUser) RunDetailReasons.protectionText(protectionKind, protectionRule) else ""

    companion object {
        fun parse(json: JSONObject): RunDetailItem = RunDetailItem(
            index = json.optInt("index", -1),
            failed = json.optString("kind") == "failed",
            reasonCode = json.optString("reason"),
            reasonText = json.optString("reasonText").ifBlank { RunDetailReasons.text(json.optString("reason"), json.optString("note")) },
            category = json.optString("category"),
            count = json.optLong("count", 1L).coerceAtLeast(1L),
            bytes = json.optLong("bytes", 0L).coerceAtLeast(0L),
            path = json.optString("path"),
            note = json.optString("note"),
            state = json.optString("state"),
            exists = json.optBoolean("exists", false),
            directory = json.optBoolean("directory", false),
            actionable = json.optBoolean("actionable", false),
            blockedReason = json.optString("blockedReason"),
            protectionKind = json.optString("protectionKind"),
            protectionRule = json.optString("protectionRule")
        )

        fun parseAll(json: JSONObject): List<RunDetailItem> {
            val array = json.optJSONArray("items") ?: return emptyList()
            return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::parse) }.filter { it.index >= 0 }
        }
    }
}

/** 删除前确认框的文案：说明数量、回收站去向，以及哪些项目受你的保护规则保护。 */
internal object RunDetailConfirm {
    fun title(items: List<RunDetailItem>): String = if (items.size == 1) "删除这个项目？" else "删除所选 ${items.size} 项？"

    fun body(items: List<RunDetailItem>): String = buildString {
        append("将移入白泽回收站（隔离区），保留期内可恢复。无法移入回收站的项目会再询问你是否永久删除。")
        val protectedItems = items.filter { it.protectedByUser }
        if (protectedItems.isNotEmpty()) {
            append("\n\n其中 ").append(protectedItems.size).append(" 项受你的保护规则保护：")
            protectedItems.map { it.protectionText }.distinct().take(3).forEach { append("\n· ").append(it) }
            append("\n继续即「临时允许本次」，保护名单本身不变。")
        }
    }

    fun permanentBody(count: Int): String = "有 $count 项无法移入回收站（例如不在同一存储分区或回收站已满）。永久删除后无法恢复，确定继续吗？"
}

/** App 端快照清理的受保护项目 → 记录详情行（与模块 run-details 同一原因码）。 */
internal object RunDetailItems {
    fun fromProtected(items: List<ProtectedUiItem>): JSONArray = JSONArray().also { array ->
        items.take(200).forEach { item ->
            array.put(JSONObject()
                .put("kind", "skipped")
                .put("reason", reasonCode(item.reason, item.risk))
                .put("category", item.category)
                .put("count", 1)
                .put("bytes", 0)
                .put("path", item.path)
                .put("note", item.reason))
        }
    }

    fun reasonCode(reason: String, risk: String = ""): String = when {
        reason.contains("白名单") || reason.contains("保护名单") || reason.contains("路径保护") -> "protected"
        reason.contains("变化") || reason.contains("已修改") -> "changed"
        reason.contains("不存在") -> "missing"
        reason.contains("大小") -> "size"
        reason.contains("高风险") || risk == "high" || risk == "critical" -> "review"
        else -> "safety"
    }
}
