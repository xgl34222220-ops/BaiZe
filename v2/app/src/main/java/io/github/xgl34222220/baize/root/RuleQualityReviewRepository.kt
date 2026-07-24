package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Root-owned review metadata. It never edits cleaner rules, files, policy, or scheduler configuration. */
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
                val subjectKey = item.optString("subjectKey").takeIf(KEY_PATTERN::matches).orEmpty()
                put(
                    key,
                    RuleQualityReview(
                        state = state,
                        note = sanitizeNote(item.optString("note")),
                        reviewedAt = item.optLong("reviewedAt").coerceAtLeast(0L),
                        subjectKey = subjectKey,
                        baselineInitialized = item.optBoolean("baselineInitialized", false),
                        baselineRisk = sanitizeToken(item.optString("baselineRisk"), 40),
                        baselineType = sanitizeToken(item.optString("baselineType"), 40),
                        baselineSeverity = sanitizeToken(item.optString("baselineSeverity"), 20),
                        baselineEvents = item.optInt("baselineEvents").coerceAtLeast(0),
                        baselineObservations = item.optInt("baselineObservations").coerceAtLeast(0),
                        baselineFailureRate = item.optInt("baselineFailureRate").coerceIn(0, 100),
                        baselineProtectionRate = item.optInt("baselineProtectionRate").coerceIn(0, 100),
                        reopened = item.optBoolean("reopened", false) && state == "pending",
                        reopenedAt = item.optLong("reopenedAt").coerceAtLeast(0L),
                        reopenReason = sanitizeNote(item.optString("reopenReason")),
                        previousState = item.optString("previousState").takeIf { it in REVIEWED_STATES }.orEmpty()
                    )
                )
            }
        }
    }.getOrDefault(emptyMap())

    @Synchronized
    fun update(ruleKeyRaw: String?, actionRaw: String?, noteRaw: String?, report: JSONObject): String = runCatching {
        val ruleKey = ruleKeyRaw.orEmpty().trim().lowercase()
        val action = actionRaw.orEmpty().trim().lowercase()
        val evidence = parseEvidence(report)
        if (!KEY_PATTERN.matches(ruleKey)) return failure("invalid_rule_key", "审核键格式无效")
        val currentEvidence = evidence[ruleKey]
            ?: return failure("unknown_rule_key", "该规则已不在当前审核队列，请刷新后重试")
        if (action !in ALLOWED_ACTIONS) return failure("unsupported_action", "不支持的审核动作")

        val records = read().toMutableMap()
        records.entries.removeAll { (key, review) ->
            key == ruleKey || (review.subjectKey.isNotBlank() && review.subjectKey == currentEvidence.subjectKey)
        }
        if (action != "reset") {
            records[ruleKey] = RuleQualityReview(
                state = ACTION_TO_STATE.getValue(action),
                note = sanitizeNote(noteRaw.orEmpty()),
                reviewedAt = System.currentTimeMillis(),
                subjectKey = currentEvidence.subjectKey,
                baselineInitialized = true,
                baselineRisk = currentEvidence.risk,
                baselineType = currentEvidence.type,
                baselineSeverity = currentEvidence.severity,
                baselineEvents = currentEvidence.events,
                baselineObservations = currentEvidence.observations,
                baselineFailureRate = currentEvidence.failureRate,
                baselineProtectionRate = currentEvidence.protectionRate
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
            .put("reopened", false)
            .put("message", if (action == "reset") "已重置为待审核" else "审核记录与当前证据基线已保存")
            .put("reviewMetadataChanged", true)
            .put("rulesChanged", false)
            .put("policyUntouched", true)
            .put("scheduleUntouched", true)
            .toString()
    }.getOrElse { failure("review_write_failed", it.message ?: it.javaClass.simpleName) }

    @Synchronized
    fun reconcile(report: JSONObject, nowEpoch: Long = System.currentTimeMillis()): RuleQualityReconcileResult {
        val evidence = parseEvidence(report)
        val records = read().toMutableMap()
        val reopenedItems = mutableListOf<RuleQualityReopen>()
        var changed = false

        records.toMap().forEach { (storedKey, review) ->
            val current = evidence[storedKey] ?: evidence.values.firstOrNull {
                review.subjectKey.isNotBlank() && it.subjectKey == review.subjectKey
            } ?: return@forEach

            if (!review.baselineInitialized) {
                if (storedKey != current.key) records.remove(storedKey)
                records[current.key] = review.withBaseline(current)
                changed = true
                return@forEach
            }
            if (review.state !in AUTO_REOPEN_STATES || review.reopened) return@forEach

            val reason = reopenReason(review, current) ?: return@forEach
            if (storedKey != current.key) records.remove(storedKey)
            records[current.key] = review.copy(
                state = "pending",
                subjectKey = current.subjectKey,
                reopened = true,
                reopenedAt = nowEpoch,
                reopenReason = reason,
                previousState = review.state
            )
            reopenedItems += RuleQualityReopen(
                key = current.key,
                category = current.category,
                previousState = review.state,
                reason = reason,
                reopenedAt = nowEpoch
            )
            changed = true
        }

        if (changed) write(records)
        return RuleQualityReconcileResult(records.toMap(), reopenedItems, changed)
    }

    private fun reopenReason(review: RuleQualityReview, current: RuleQualityEvidence): String? {
        val riskRaised = riskRank(current.risk) > riskRank(review.baselineRisk)
        if (riskRaised && current.newEventsSinceReview >= MIN_RISK_NEW_EVENTS) {
            return "风险级别由 ${riskLabel(review.baselineRisk)} 提升为 ${riskLabel(current.risk)}"
        }

        val enoughNewEvidence = current.newEventsSinceReview >= MIN_NEW_EVENTS &&
            current.newObservationsSinceReview >= MIN_NEW_OBSERVATIONS
        if (!enoughNewEvidence) return null

        val failureDelta = current.failureRate - review.baselineFailureRate
        if (current.failureRate >= FAILURE_TRIGGER_RATE && failureDelta >= FAILURE_RATE_DELTA) {
            return "异常率较审核时上升 $failureDelta 个百分点，当前为 ${current.failureRate}%"
        }
        val protectionDelta = current.protectionRate - review.baselineProtectionRate
        if (current.protectionRate >= PROTECTION_TRIGGER_RATE && protectionDelta >= PROTECTION_RATE_DELTA) {
            return "保护率较审核时上升 $protectionDelta 个百分点，当前为 ${current.protectionRate}%"
        }
        if (severityRank(current.severity) > severityRank(review.baselineSeverity)) {
            return "规则严重级别由 ${severityLabel(review.baselineSeverity)} 提升为 ${severityLabel(current.severity)}"
        }
        if (current.type in ESCALATED_TYPES && current.type != review.baselineType) {
            return "规则问题类型升级为 ${typeLabel(current.type)}"
        }
        return null
    }

    private fun parseEvidence(report: JSONObject): Map<String, RuleQualityEvidence> {
        val queue = report.optJSONArray("reviewQueue") ?: JSONArray()
        return buildMap {
            for (index in 0 until queue.length().coerceAtMost(MAX_QUEUE_ITEMS)) {
                val item = queue.optJSONObject(index) ?: continue
                val key = item.optString("key")
                val subjectKey = item.optString("subjectKey")
                if (!KEY_PATTERN.matches(key) || !KEY_PATTERN.matches(subjectKey)) continue
                put(
                    key,
                    RuleQualityEvidence(
                        key = key,
                        subjectKey = subjectKey,
                        category = sanitizeNote(item.optString("category")).take(MAX_CATEGORY_LENGTH),
                        risk = sanitizeToken(item.optString("risk"), 40),
                        type = sanitizeToken(item.optString("type"), 40),
                        severity = sanitizeToken(item.optString("severity"), 20),
                        events = item.optInt("events").coerceAtLeast(0),
                        observations = item.optInt("observations").coerceAtLeast(0),
                        failureRate = item.optInt("failureRate").coerceIn(0, 100),
                        protectionRate = item.optInt("protectionRate").coerceIn(0, 100),
                        newEventsSinceReview = item.optInt("newEventsSinceReview").coerceAtLeast(0),
                        newObservationsSinceReview = item.optInt("newObservationsSinceReview").coerceAtLeast(0)
                    )
                )
            }
        }
    }

    private fun write(records: Map<String, RuleQualityReview>) {
        stateDir.mkdirs()
        val bounded = records.entries
            .sortedByDescending { maxOf(it.value.reopenedAt, it.value.reviewedAt) }
            .take(MAX_RECORDS)
        val array = JSONArray()
        bounded.forEach { (key, review) ->
            array.put(
                JSONObject()
                    .put("key", key)
                    .put("state", review.state)
                    .put("note", review.note)
                    .put("reviewedAt", review.reviewedAt)
                    .put("subjectKey", review.subjectKey)
                    .put("baselineInitialized", review.baselineInitialized)
                    .put("baselineRisk", review.baselineRisk)
                    .put("baselineType", review.baselineType)
                    .put("baselineSeverity", review.baselineSeverity)
                    .put("baselineEvents", review.baselineEvents)
                    .put("baselineObservations", review.baselineObservations)
                    .put("baselineFailureRate", review.baselineFailureRate)
                    .put("baselineProtectionRate", review.baselineProtectionRate)
                    .put("reopened", review.reopened)
                    .put("reopenedAt", review.reopenedAt)
                    .put("reopenReason", review.reopenReason)
                    .put("previousState", review.previousState)
            )
        }
        val payload = JSONObject()
            .put("schema", SCHEMA_VERSION)
            .put("updatedAt", System.currentTimeMillis())
            .put("records", array)
        RootFileStore.writeAtomic(reviewFile, payload.toString())
    }

    private fun RuleQualityReview.withBaseline(current: RuleQualityEvidence): RuleQualityReview = copy(
        subjectKey = current.subjectKey,
        baselineInitialized = true,
        baselineRisk = current.risk,
        baselineType = current.type,
        baselineSeverity = current.severity,
        baselineEvents = current.events,
        baselineObservations = current.observations,
        baselineFailureRate = current.failureRate,
        baselineProtectionRate = current.protectionRate
    )

    private fun sanitizeNote(raw: String): String = raw
        .replace(Regex("[\\p{Cntrl}]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_NOTE_LENGTH)

    private fun sanitizeToken(raw: String, limit: Int): String = raw
        .replace(Regex("[^a-zA-Z0-9_-]"), "")
        .lowercase()
        .take(limit)

    private fun failure(code: String, message: String): String = JSONObject()
        .put("success", false)
        .put("error", code)
        .put("message", message)
        .put("reviewMetadataChanged", false)
        .put("rulesChanged", false)
        .put("policyUntouched", true)
        .put("scheduleUntouched", true)
        .toString()

    private fun riskRank(value: String): Int = when (value) {
        "critical" -> 4
        "high" -> 3
        "medium" -> 2
        "low" -> 1
        else -> 0
    }

    private fun severityRank(value: String): Int = when (value) {
        "high" -> 3
        "medium" -> 2
        "info" -> 1
        else -> 0
    }

    private fun riskLabel(value: String): String = when (value) {
        "critical" -> "关键"
        "high" -> "高"
        "medium" -> "中"
        "low" -> "低"
        else -> "未知"
    }

    private fun severityLabel(value: String): String = when (value) {
        "high" -> "高"
        "medium" -> "中"
        "info" -> "提示"
        else -> "未知"
    }

    private fun typeLabel(value: String): String = when (value) {
        "high_failure" -> "高失败"
        "frequently_protected" -> "频繁保护"
        "zero_hit" -> "长期零命中"
        "low_value" -> "长期低收益"
        else -> "规则观察"
    }

    companion object {
        private const val SCHEMA_VERSION = 2
        private const val MAX_RECORDS = 200
        private const val MAX_QUEUE_ITEMS = 40
        private const val MAX_NOTE_LENGTH = 200
        private const val MAX_CATEGORY_LENGTH = 100
        private const val MIN_NEW_EVENTS = 2
        private const val MIN_NEW_OBSERVATIONS = 3
        private const val MIN_RISK_NEW_EVENTS = 1
        private const val FAILURE_TRIGGER_RATE = 40
        private const val FAILURE_RATE_DELTA = 20
        private const val PROTECTION_TRIGGER_RATE = 50
        private const val PROTECTION_RATE_DELTA = 25
        private val KEY_PATTERN = Regex("^[0-9a-f]{16}$")
        private val ALLOWED_ACTIONS = setOf("keep", "observe", "ignore", "reset")
        private val REVIEWED_STATES = setOf("kept", "observing", "ignored")
        private val AUTO_REOPEN_STATES = setOf("kept", "ignored")
        private val STORED_STATES = REVIEWED_STATES + "pending"
        private val ESCALATED_TYPES = setOf("high_failure", "frequently_protected")
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
    val reviewedAt: Long,
    val subjectKey: String = "",
    val baselineInitialized: Boolean = false,
    val baselineRisk: String = "",
    val baselineType: String = "",
    val baselineSeverity: String = "",
    val baselineEvents: Int = 0,
    val baselineObservations: Int = 0,
    val baselineFailureRate: Int = 0,
    val baselineProtectionRate: Int = 0,
    val reopened: Boolean = false,
    val reopenedAt: Long = 0L,
    val reopenReason: String = "",
    val previousState: String = ""
)

internal data class RuleQualityEvidence(
    val key: String,
    val subjectKey: String,
    val category: String,
    val risk: String,
    val type: String,
    val severity: String,
    val events: Int,
    val observations: Int,
    val failureRate: Int,
    val protectionRate: Int,
    val newEventsSinceReview: Int,
    val newObservationsSinceReview: Int
)

internal data class RuleQualityReopen(
    val key: String,
    val category: String,
    val previousState: String,
    val reason: String,
    val reopenedAt: Long
)

internal data class RuleQualityReconcileResult(
    val reviews: Map<String, RuleQualityReview>,
    val reopened: List<RuleQualityReopen>,
    val changed: Boolean
)
