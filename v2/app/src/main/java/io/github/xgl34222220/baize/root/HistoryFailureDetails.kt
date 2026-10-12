package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject

/**
 * history.tsv column 13 (index 12): what a task left in place.
 *
 * 3.3.4 rows start with the `~v2` token; their errors column (6) counts only real failures
 * (BaiZe tried to delete and the kernel refused). Tokens, `;` separated:
 *  - `category|count|reason|samplePath`  failed group
 *  - `~skip|code|count|samplePath`       skipped on purpose (protection list, changed, slow…)
 *  - `~detail|<run id>`                  per-item detail file (state/run-details/<id>.tsv)
 *
 * Older rows are re-classified on read so they never all show 有异常:
 *  - scans never delete, so their errors are unreadable directories (skipped);
 *  - shell cleanups before 3.3.4 counted protected / unreadable items as errors, so without
 *    evidence of a real failure the count is shown neutral ("旧版本记录，未区分原因");
 *  - the #238 test build wrote failed groups whose reason is a read/scan failure; those are skips.
 */
internal object HistoryFailureDetails {
    const val COLUMN = 12
    const val SPLIT_LIMIT = COLUMN + 1

    data class Outcome(
        val failed: Long,
        val skipped: Long,
        val failureGroups: JSONArray,
        val skipGroups: JSONArray,
        val detailId: String,
        val classified: Boolean
    )

    /** Failed groups only (kept for callers that read the old `failureDetails` field). */
    fun toJson(raw: String): JSONArray = parse(raw).failed

    fun outcome(mode: String, errorsColumn: Long, raw: String, trigger: String = ""): Outcome {
        val errors = errorsColumn.coerceAtLeast(0L)
        val parsed = parse(raw)
        val realGroups = JSONArray()
        val skipGroups = JSONArray()
        for (index in 0 until parsed.skipped.length()) skipGroups.put(parsed.skipped.getJSONObject(index))
        var realCount = 0L
        var benignCount = 0L
        for (index in 0 until parsed.failed.length()) {
            val group = parsed.failed.getJSONObject(index)
            if (isBenignReason(group.optString("reason"))) {
                benignCount += group.optLong("count")
                skipGroups.put(skip("unreadable", group.optLong("count"), group.optString("samplePath")))
            } else {
                realCount += group.optLong("count")
                realGroups.put(group)
            }
        }
        val skippedFromTokens = sum(parsed.skipped)
        if (parsed.v2) {
            return Outcome(errors, skippedFromTokens + benignCount, realGroups, skipGroups, parsed.detailId, true)
        }
        val scan = mode == "scan" || mode.endsWith("-scan")
        return when {
            errors == 0L -> Outcome(0L, skippedFromTokens, realGroups, skipGroups, parsed.detailId, false)
            scan -> Outcome(0L, errors, JSONArray(), skipGroups.apply {
                if (length() == 0) put(skip("scan_unreadable", errors, ""))
            }, parsed.detailId, false)
            mode in REAL_ERROR_MODES || trigger == "app-native" || mode.startsWith("性能工具") -> Outcome(errors, skippedFromTokens, realGroups, skipGroups, parsed.detailId, false)
            parsed.failed.length() > 0 -> {
                val failed = realCount.coerceAtMost(errors)
                val rest = errors - failed
                if (rest > benignCount) skipGroups.put(skip("legacy", rest - benignCount, ""))
                Outcome(failed, rest, realGroups, skipGroups, parsed.detailId, false)
            }
            else -> Outcome(0L, errors, JSONArray(), skipGroups.apply { put(skip("legacy", errors, "")) }, parsed.detailId, false)
        }
    }

    /** Read / traversal problems: nothing was deleted, so never a failure. */
    fun isBenignReason(reason: String): Boolean = BENIGN.any { reason.contains(it) }

    private class Parsed(val failed: JSONArray, val skipped: JSONArray, val detailId: String, val v2: Boolean)

    private fun parse(raw: String): Parsed {
        val failed = JSONArray()
        val skipped = JSONArray()
        var detailId = ""
        var v2 = false
        raw.split(';').asSequence().map { it.trim() }.filter { it.isNotBlank() }.take(40).forEach { token ->
            when {
                token == "~v2" -> v2 = true
                token.startsWith("~detail|") -> token.substringAfter('|').trim()
                    .takeIf { RunDetailRepository.ID_PATTERN.matches(it) }?.let { detailId = it }
                token.startsWith("~skip|") -> {
                    val columns = token.split('|', limit = 4)
                    val count = columns.getOrNull(2)?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: return@forEach
                    if (skipped.length() < 16) skipped.put(skip(columns[1].trim().take(40), count, columns.getOrNull(3).orEmpty()))
                }
                token.startsWith("~") -> Unit
                else -> {
                    val columns = token.split('|', limit = 4)
                    if (columns.size < 3 || failed.length() >= 8) return@forEach
                    val category = columns[0].trim().take(80)
                    val count = columns[1].trim().toLongOrNull()?.coerceAtLeast(0L) ?: return@forEach
                    val reason = columns[2].trim().take(160)
                    if (category.isBlank() && reason.isBlank()) return@forEach
                    failed.put(JSONObject()
                        .put("category", category.ifBlank { "未分类" })
                        .put("count", count)
                        .put("reason", reason.ifBlank { "未记录原因" })
                        .put("reasonText", RunDetailReasons.errnoText(reason.ifBlank { "未记录原因" }))
                        .put("samplePath", columns.getOrNull(3).orEmpty().trim().take(300)))
                }
            }
        }
        return Parsed(failed, skipped, detailId, v2)
    }

    private fun skip(code: String, count: Long, sample: String): JSONObject = JSONObject()
        .put("code", code).put("count", count).put("label", RunDetailReasons.skipText(code))
        .put("samplePath", sample.trim().take(300))

    private fun sum(array: JSONArray): Long = (0 until array.length()).sumOf { array.getJSONObject(it).optLong("count") }

    /** Modes whose errors column always meant real failures (App-native engines, organizer, tools). */
    private val REAL_ERROR_MODES = setOf(
        "smart-clean", "snapshot-clean", "workbench-clean", "profile-clean", "instant-cache",
        "file-organizer-apply", "file-organizer-undo", "organize"
    )

    private val BENIGN = listOf(
        "扫描未完成", "无法完整读取", "无法读取", "目录未完成", "读取不完整", "慢目录", "超过单目录时限"
    )
}
