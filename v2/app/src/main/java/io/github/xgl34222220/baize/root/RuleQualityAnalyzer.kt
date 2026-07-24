package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Read-only rule quality analysis built from bounded, already-redacted audit summaries.
 *
 * Manual review metadata may be merged into the response, but the analyzer never edits rule files,
 * changes policy or scheduler configuration, starts a task, or receives full filesystem paths.
 */
internal class RuleQualityAnalyzer {
    fun analyze(
        events: List<JSONObject>,
        reviews: Map<String, RuleQualityReview> = emptyMap(),
        nowEpoch: Long = System.currentTimeMillis()
    ): JSONObject {
        val cutoff = nowEpoch - LOOKBACK_DAYS * DAY_MS
        val recent = events.asSequence()
            .filter { it.optLong("timeEpoch", nowEpoch) >= cutoff }
            .filter { it.optString("kind") in TASK_KINDS }
            .take(MAX_INPUT_EVENTS)
            .toList()
        val stats = linkedMapOf<String, RuleStat>()

        recent.forEach { event ->
            val details = event.optJSONArray("details") ?: return@forEach
            val failedEvent = event.optString("status") in setOf("failed", "partial") || event.optLong("errors") > 0L
            val categoriesSeen = linkedSetOf<String>()
            for (index in 0 until details.length().coerceAtMost(MAX_DETAILS_PER_EVENT)) {
                val detail = details.optJSONObject(index) ?: continue
                val category = detail.optString("category").trim().take(MAX_CATEGORY_LENGTH)
                if (category.isBlank()) continue
                val risk = detail.optString("risk").trim().lowercase(Locale.ROOT).take(MAX_RISK_LENGTH)
                val key = stableKey(category, risk)
                val stat = stats.getOrPut(key) { RuleStat(key = key, category = category, risk = risk) }
                val action = detail.optString("action").trim().lowercase(Locale.ROOT)
                stat.observations += 1
                stat.bytes += detail.optLong("bytes").coerceAtLeast(0L)
                stat.files += detail.optLong("files").coerceAtLeast(0L)
                if (action in PROCESSED_ACTIONS) stat.processed += 1
                if (action in PROTECTED_ACTIONS) stat.protected += 1
                if (action in FAILED_ACTIONS) stat.actionFailures += 1
                categoriesSeen += key
            }
            categoriesSeen.forEach { key ->
                stats[key]?.let { stat ->
                    stat.events += 1
                    if (failedEvent) stat.failedEvents += 1
                }
            }
        }

        val classified = stats.values.map(::classify).map { assessment ->
            val review = reviews[assessment.key]
            assessment.copy(
                reviewState = review?.state ?: "pending",
                reviewNote = review?.note.orEmpty(),
                reviewedAt = review?.reviewedAt ?: 0L
            )
        }
        val reviewQueue = classified
            .filter { it.type != "healthy" && it.type != "insufficient" }
            .sortedWith(
                compareBy<RuleAssessment> { reviewStateRank(it.reviewState) }
                    .thenByDescending { severityRank(it.severity) }
                    .thenByDescending { it.failureRate }
                    .thenByDescending { it.protectionRate }
                    .thenByDescending { it.events }
            )
            .take(MAX_REVIEW_ITEMS)
        val pendingCount = reviewQueue.count { it.reviewState == "pending" }
        val observingCount = reviewQueue.count { it.reviewState == "observing" }
        val keptCount = reviewQueue.count { it.reviewState == "kept" }
        val ignoredCount = reviewQueue.count { it.reviewState == "ignored" }
        val reviewedCount = observingCount + keptCount + ignoredCount
        val healthyCount = classified.count { it.type == "healthy" }
        val insufficientCount = classified.count { it.type == "insufficient" }
        val highPriorityCount = reviewQueue.count { it.reviewState == "pending" && it.severity == "high" }

        return JSONObject()
            .put("available", classified.isNotEmpty())
            .put("readOnly", true)
            .put("reviewOnly", true)
            .put("reviewStateWritable", true)
            .put("automaticActions", false)
            .put("rulesChanged", false)
            .put("policyUntouched", true)
            .put("scheduleUntouched", true)
            .put("pathsIncluded", false)
            .put("lookbackDays", LOOKBACK_DAYS)
            .put("eventSampleCount", recent.size)
            .put("ruleCount", classified.size)
            .put("needsReview", pendingCount)
            .put("pendingCount", pendingCount)
            .put("observingCount", observingCount)
            .put("keptCount", keptCount)
            .put("ignoredCount", ignoredCount)
            .put("reviewedCount", reviewedCount)
            .put("highPriorityCount", highPriorityCount)
            .put("healthyCount", healthyCount)
            .put("insufficientCount", insufficientCount)
            .put("summary", summary(classified.size, pendingCount, highPriorityCount, observingCount, reviewedCount))
            .put("reviewQueue", JSONArray(reviewQueue.map(RuleAssessment::toJson)))
    }

    private fun classify(stat: RuleStat): RuleAssessment {
        val protectionRate = percent(stat.protected, stat.observations)
        val failureRate = percent(
            stat.failedEvents + stat.actionFailures.coerceAtMost(stat.events),
            stat.events.coerceAtLeast(1)
        )
        val averageBytes = if (stat.processed <= 0) 0L else stat.bytes / stat.processed
        val type: String
        val severity: String
        val recommendation: String
        val message: String

        when {
            stat.events < MIN_RULE_EVENTS || stat.observations < MIN_RULE_OBSERVATIONS -> {
                type = "insufficient"
                severity = "info"
                recommendation = "keep"
                message = "样本不足，暂不评价该规则"
            }
            failureRate >= HIGH_FAILURE_RATE -> {
                type = "high_failure"
                severity = "high"
                recommendation = "consider_disable"
                message = "近期开启该规则的任务异常率为 $failureRate%，建议人工核对后考虑停用"
            }
            protectionRate >= FREQUENT_PROTECTION_RATE -> {
                type = "frequently_protected"
                severity = "high"
                recommendation = "narrow_scope"
                message = "有 $protectionRate% 的记录被安全机制拦截，建议缩小规则范围"
            }
            stat.events >= MIN_ZERO_HIT_EVENTS && stat.processed == 0 && stat.bytes == 0L -> {
                type = "zero_hit"
                severity = "medium"
                recommendation = "consider_disable"
                message = "连续 ${stat.events} 次记录均未产生有效清理，可人工评估是否继续保留"
            }
            stat.processed >= MIN_LOW_VALUE_PROCESSED && averageBytes < LOW_VALUE_AVERAGE_BYTES -> {
                type = "low_value"
                severity = "medium"
                recommendation = "observe"
                message = "多次执行但平均收益较低，建议继续观察或合并规则"
            }
            else -> {
                type = "healthy"
                severity = "info"
                recommendation = "keep"
                message = "当前命中、收益与安全表现正常"
            }
        }

        return RuleAssessment(
            key = stat.key,
            category = stat.category,
            risk = stat.risk,
            type = type,
            severity = severity,
            recommendation = recommendation,
            message = message,
            events = stat.events,
            observations = stat.observations,
            processed = stat.processed,
            protected = stat.protected,
            failures = stat.failedEvents + stat.actionFailures,
            protectionRate = protectionRate,
            failureRate = failureRate,
            bytes = stat.bytes,
            files = stat.files,
            averageBytes = averageBytes
        )
    }

    private fun summary(ruleCount: Int, pending: Int, highPriority: Int, observing: Int, reviewed: Int): String = when {
        ruleCount == 0 -> "暂无足够的规则审计数据"
        highPriority > 0 -> "发现 $highPriority 项高优先级规则需要人工审核"
        pending > 0 -> "还有 $pending 项规则等待人工审核"
        observing > 0 -> "$observing 项规则正在持续观察"
        reviewed > 0 -> "当前审核队列已处理完成"
        else -> "当前规则质量表现正常"
    }

    private fun stableKey(category: String, risk: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$category\u0000$risk".toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private fun percent(part: Int, total: Int): Int = if (total <= 0) 0 else {
        ((part.coerceAtMost(total) * 100.0) / total).roundToInt().coerceIn(0, 100)
    }

    private fun severityRank(value: String): Int = when (value) {
        "high" -> 3
        "medium" -> 2
        else -> 1
    }

    private fun reviewStateRank(value: String): Int = when (value) {
        "pending" -> 0
        "observing" -> 1
        "kept" -> 2
        "ignored" -> 3
        else -> 4
    }

    private data class RuleStat(
        val key: String,
        val category: String,
        val risk: String,
        var events: Int = 0,
        var observations: Int = 0,
        var processed: Int = 0,
        var protected: Int = 0,
        var failedEvents: Int = 0,
        var actionFailures: Int = 0,
        var bytes: Long = 0L,
        var files: Long = 0L
    )

    private data class RuleAssessment(
        val key: String,
        val category: String,
        val risk: String,
        val type: String,
        val severity: String,
        val recommendation: String,
        val message: String,
        val events: Int,
        val observations: Int,
        val processed: Int,
        val protected: Int,
        val failures: Int,
        val protectionRate: Int,
        val failureRate: Int,
        val bytes: Long,
        val files: Long,
        val averageBytes: Long,
        val reviewState: String = "pending",
        val reviewNote: String = "",
        val reviewedAt: Long = 0L
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("key", key)
            .put("category", category)
            .put("risk", risk)
            .put("type", type)
            .put("severity", severity)
            .put("recommendation", recommendation)
            .put("message", message)
            .put("events", events)
            .put("observations", observations)
            .put("processed", processed)
            .put("protected", protected)
            .put("failures", failures)
            .put("protectionRate", protectionRate)
            .put("failureRate", failureRate)
            .put("bytes", bytes)
            .put("files", files)
            .put("averageBytes", averageBytes)
            .put("reviewState", reviewState)
            .put("reviewNote", reviewNote)
            .put("reviewedAt", reviewedAt)
    }

    companion object {
        private const val LOOKBACK_DAYS = 45
        private const val DAY_MS = 24L * 60L * 60L * 1_000L
        private const val MAX_INPUT_EVENTS = 180
        private const val MAX_DETAILS_PER_EVENT = 120
        private const val MAX_REVIEW_ITEMS = 40
        private const val MAX_CATEGORY_LENGTH = 100
        private const val MAX_RISK_LENGTH = 40
        private const val MIN_RULE_EVENTS = 3
        private const val MIN_RULE_OBSERVATIONS = 3
        private const val MIN_ZERO_HIT_EVENTS = 4
        private const val MIN_LOW_VALUE_PROCESSED = 3
        private const val HIGH_FAILURE_RATE = 40
        private const val FREQUENT_PROTECTION_RATE = 50
        private const val LOW_VALUE_AVERAGE_BYTES = 1024L * 1024L
        private val TASK_KINDS = setOf("scan", "clean", "organize")
        private val PROCESSED_ACTIONS = setOf("deleted", "cleaned", "quarantined", "restored", "purged", "processed")
        private val PROTECTED_ACTIONS = setOf("protected", "skipped", "partial")
        private val FAILED_ACTIONS = setOf("failed", "error")
    }
}
