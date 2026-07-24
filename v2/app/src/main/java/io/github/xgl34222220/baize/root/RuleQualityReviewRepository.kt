package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Root-owned manual review metadata. It never edits cleaner rules or scheduler configuration. */
internal class RuleQualityReviewRepository(
    private val stateDir: File = File(RootPaths.STATE_DIR)
) {
    private val reviewFile = File(stateDir, "rule-quality-reviews.json")

    @Synchronized
    fun read(): Map<String, RuleQualityReview> = runCatching {
        if (!reviewFile.isFile) return@runCatching emptyMap()
        val records = JSONObject(reviewFile.readText()).optJSONArray("records") ?: JSONArray()
        buildMap {
            for (index in 0 until records.length().coerceAtMost(MAX_RECORDS)) {
                val item = records.optJSONObject(index) ?: continue
                val key = item.optString("key")
                val state = item.optString("state")
                if (!KEY_PATTERN.matches(key) || state !in STORED_STATES) continue
                put(
                    key,
                    RuleQualityReview(
                        state = state,
                        note = sanitizeNote(item.optString("note")),
                        reviewedAt = item.optLong("reviewedAt").coerceAtLeast(0L)
                    )
                )
            }
        }
    }.getOrDefault(emptyMap())

    @Synchronized
    fun update(ruleKeyRaw: String?, actionRaw: String?, noteRaw: String?, validKeys: Set<String>): String = runCatching {
        val ruleKey = ruleKeyRaw.orEmpty().trim().lowercase()
        val action = actionRaw.orEmpty().trim().lowercase()
        if (!KEY_PATTERN.matches(ruleKey)) return failure("invalid_rule_key", "审核键格式无效")
        if (ruleKey !in validKeys) return failure("unknown_rule_key", "该规则已不在当前审核队列，请刷新后重试")
        if (action !in ALLOWED_ACTIONS) return failure("unsupported_action", "不支持的审核动作")

        val records = read().toMutableMap()
        if (action == "reset") {
            records.remove(ruleKey)
        } else {
            records[ruleKey] = RuleQualityReview(
                state = ACTION_TO_STATE.getValue(action),
                note = sanitizeNote(noteRaw.orEmpty()),
                reviewedAt = System.currentTimeMillis()
            )
        }
        write(records)
        val current = records[ruleKey]
        JSONObject()
            .put("success", true)
            .put("ruleKey", ruleKey)
            .put("action", action)
            .put("state", current?.state ?: "pending")
            .put("note", current?.note.orEmpty())
            .put("reviewedAt", current?.reviewedAt ?: 0L)
            .put("message", if (action == "reset") "已重置为待审核" else "审核记录已保存")
            .put("rulesChanged", false)
            .put("scheduleUntouched", true)
            .toString()
    }.getOrElse { failure("review_write_failed", it.message ?: it.javaClass.simpleName) }

    private fun write(records: Map<String, RuleQualityReview>) {
        stateDir.mkdirs()
        val bounded = records.entries
            .sortedByDescending { it.value.reviewedAt }
            .take(MAX_RECORDS)
        val array = JSONArray()
        bounded.forEach { (key, review) ->
            array.put(
                JSONObject()
                    .put("key", key)
                    .put("state", review.state)
                    .put("note", review.note)
                    .put("reviewedAt", review.reviewedAt)
            )
        }
        val payload = JSONObject()
            .put("schema", SCHEMA_VERSION)
            .put("updatedAt", System.currentTimeMillis())
            .put("records", array)
        RootFileStore.writeAtomic(reviewFile, payload.toString())
    }

    private fun sanitizeNote(raw: String): String = raw
        .replace(Regex("[\\p{Cntrl}]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_NOTE_LENGTH)

    private fun failure(code: String, message: String): String = JSONObject()
        .put("success", false)
        .put("error", code)
        .put("message", message)
        .put("rulesChanged", false)
        .put("scheduleUntouched", true)
        .toString()

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val MAX_RECORDS = 200
        private const val MAX_NOTE_LENGTH = 200
        private val KEY_PATTERN = Regex("^[0-9a-f]{16}$")
        private val ALLOWED_ACTIONS = setOf("keep", "observe", "ignore", "reset")
        private val STORED_STATES = setOf("kept", "observing", "ignored")
        private val ACTION_TO_STATE = mapOf(
            "keep" to "kept",
            "observe" to "observing",
            "ignore" to "ignored"
        )
    }
}

internal data class RuleQualityReview(
    val state: String,
    val note: String,
    val reviewedAt: Long
)
