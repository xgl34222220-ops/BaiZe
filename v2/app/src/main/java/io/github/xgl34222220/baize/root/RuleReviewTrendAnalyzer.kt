package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Read-only review history and reopen trend analysis.
 *
 * Inputs are bounded, already-redacted audit events plus the current Root-owned rule-quality report.
 * The analyzer never edits review metadata, cleaner rules, files, policy, tasks, snapshots or scheduler
 * configuration, and never accepts or returns filesystem paths.
 */
internal class RuleReviewTrendAnalyzer {
    fun analyze(
        events: List<JSONObject>,
        ruleQuality: JSONObject,
        nowEpoch: Long = System.currentTimeMillis()
    ): JSONObject {
        val cutoff = nowEpoch - LOOKBACK_DAYS * DAY_MS
        val reviewEvents = events.asSequence()
            .filter { it.optString("kind") == "review" }
            .filter { it.optLong("timeEpoch", nowEpoch) >= cutoff }
            .take(MAX_INPUT_EVENTS)
            .sortedBy { it.optLong("timeEpoch") }
            .toList()

        val subjects = linkedMapOf<String, SubjectStat>()
        val pendingReopens = linkedMapOf<String, Long>()
        val reasonCounts = linkedMapOf<String, Int>()
        val resolutionDurations = mutableListOf<Long>()
        val weekly = weeklyBuckets(nowEpoch)
        var manualReviewCount = 0
        var reopenCount = 0
        var resolvedReopenCount = 0
        var recentReopens = 0
        var previousReopens = 0

        reviewEvents.forEach { event ->
            val operation = event.optString("operation").trim().lowercase(Locale.ROOT)
            val category = category(event)
            if (category.isBlank()) return@forEach
            val time = event.optLong("timeEpoch").coerceAtLeast(0L)
            val stat = subjects.getOrPut(category) { SubjectStat(category = category) }
            stat.lastActionAt = maxOf(stat.lastActionAt, time)
            weekly.firstOrNull { time in it.start until it.end }?.let { bucket ->
                if (operation == REOPEN_OPERATION) bucket.reopens += 1 else if (operation.startsWith(REVIEW_PREFIX)) bucket.reviews += 1
            }

            if (operation == REOPEN_OPERATION) {
                reopenCount += 1
                stat.reopenCount += 1
                stat.lastReopenAt = maxOf(stat.lastReopenAt, time)
                val reason = reopenReason(event)
                stat.lastReason = reason
                val reasonType = reasonType(reason)
                stat.reasonCounts[reasonType] = (stat.reasonCounts[reasonType] ?: 0) + 1
                reasonCounts[reasonType] = (reasonCounts[reasonType] ?: 0) + 1
                pendingReopens[category] = time
                when {
                    time >= nowEpoch - TREND_WINDOW_DAYS * DAY_MS -> recentReopens += 1
                    time >= nowEpoch - TREND_WINDOW_DAYS * 2L * DAY_MS -> previousReopens += 1
                }
            } else if (operation.startsWith(REVIEW_PREFIX)) {
                manualReviewCount += 1
                stat.manualReviewCount += 1
                stat.lastState = stateFromOperation(operation)
                val reopenedAt = pendingReopens.remove(category)
                if (reopenedAt != null && time >= reopenedAt) {
                    val duration = (time - reopenedAt).coerceAtMost(MAX_RESOLUTION_MS)
                    stat.resolvedCount += 1
                    stat.resolutionDurations += duration
                    resolutionDurations += duration
                    resolvedReopenCount += 1
                    weekly.firstOrNull { time in it.start until it.end }?.resolved =
                        (weekly.firstOrNull { time in it.start until it.end }?.resolved ?: 0) + 1
                }
            }
        }

        val activeReopened = currentActiveReopens(ruleQuality)
        activeReopened.forEach { current ->
            val stat = subjects.getOrPut(current.category) { SubjectStat(category = current.category) }
            stat.activeReopened = true
            stat.lastState = "pending"
            stat.lastReopenAt = maxOf(stat.lastReopenAt, current.reopenedAt)
            if (stat.lastReason.isBlank()) stat.lastReason = current.reason
        }

        val items = subjects.values
            .filter { it.reopenCount > 0 || it.manualReviewCount > 0 || it.activeReopened }
            .sortedWith(
                compareByDescending<SubjectStat> { it.activeReopened }
                    .thenByDescending { it.reopenCount }
                    .thenByDescending { it.lastReopenAt }
                    .thenByDescending { it.lastActionAt }
            )
            .take(MAX_ITEMS)

        val repeatedCount = subjects.values.count { it.reopenCount >= REPEATED_REOPEN_THRESHOLD }
        val averageResolutionMs = average(resolutionDurations)
        val medianResolutionMs = median(resolutionDurations)
        val resolutionRate = percent(resolvedReopenCount, reopenCount)
        val trend = trend(recentReopens, previousReopens)
        val available = reviewEvents.isNotEmpty() || activeReopened.isNotEmpty()

        return JSONObject()
            .put("available", available)
            .put("readOnly", true)
            .put("automaticActions", false)
            .put("reviewMetadataChanged", false)
            .put("rulesChanged", false)
            .put("policyUntouched", true)
            .put("scheduleUntouched", true)
            .put("pathsIncluded", false)
            .put("lookbackDays", LOOKBACK_DAYS)
            .put("eventSampleCount", reviewEvents.size)
            .put("reviewEventCount", reviewEvents.size)
            .put("manualReviewCount", manualReviewCount)
            .put("reopenCount", reopenCount)
            .put("resolvedReopenCount", resolvedReopenCount)
            .put("activeReopenCount", activeReopened.size)
            .put("repeatedlyReopenedCount", repeatedCount)
            .put("resolutionRate", resolutionRate)
            .put("averageResolutionMs", averageResolutionMs)
            .put("medianResolutionMs", medianResolutionMs)
            .put("trendWindowDays", TREND_WINDOW_DAYS)
            .put("recentReopenCount", recentReopens)
            .put("previousReopenCount", previousReopens)
            .put("trend", trend)
            .put("summary", summary(available, activeReopened.size, repeatedCount, reopenCount, resolutionRate))
            .put("reasonBreakdown", JSONArray(reasonBreakdown(reasonCounts, reopenCount)))
            .put("weekly", JSONArray(weekly.map(WeekBucket::toJson)))
            .put("items", JSONArray(items.map(SubjectStat::toJson)))
    }

    private fun category(event: JSONObject): String {
        val profile = sanitize(event.optString("profile"), MAX_CATEGORY_LENGTH)
        if (profile.isNotBlank()) return profile
        val details = event.optJSONArray("details") ?: return ""
        for (index in 0 until details.length().coerceAtMost(MAX_DETAILS)) {
            val value = sanitize(details.optJSONObject(index)?.optString("category").orEmpty(), MAX_CATEGORY_LENGTH)
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun reopenReason(event: JSONObject): String {
        val details = event.optJSONArray("details")
        if (details != null) {
            for (index in 0 until details.length().coerceAtMost(MAX_DETAILS)) {
                val reason = sanitize(details.optJSONObject(index)?.optString("reason").orEmpty(), MAX_REASON_LENGTH)
                if (reason.isNotBlank()) return reason
            }
        }
        val message = sanitize(event.optString("message"), MAX_REASON_LENGTH)
        return message.substringAfter(" · ", message).ifBlank { "未分类原因" }
    }

    private fun currentActiveReopens(ruleQuality: JSONObject): List<CurrentReopen> {
        val queue = ruleQuality.optJSONArray("reviewQueue") ?: JSONArray()
        return buildList {
            for (index in 0 until queue.length().coerceAtMost(MAX_QUEUE_ITEMS)) {
                val item = queue.optJSONObject(index) ?: continue
                if (!item.optBoolean("reviewReopened", false) || item.optString("reviewState") != "pending") continue
                val category = sanitize(item.optString("category"), MAX_CATEGORY_LENGTH)
                if (category.isBlank()) continue
                add(
                    CurrentReopen(
                        category = category,
                        reopenedAt = item.optLong("reviewReopenedAt").coerceAtLeast(0L),
                        reason = sanitize(item.optString("reviewReopenReason"), MAX_REASON_LENGTH)
                    )
                )
            }
        }.distinctBy { it.category }
    }

    private fun reasonBreakdown(counts: Map<String, Int>, total: Int): List<JSONObject> = counts.entries
        .sortedByDescending { it.value }
        .take(MAX_REASON_TYPES)
        .map { (type, count) ->
            JSONObject()
                .put("type", type)
                .put("label", reasonLabel(type))
                .put("count", count)
                .put("percent", percent(count, total))
        }

    private fun weeklyBuckets(nowEpoch: Long): List<WeekBucket> {
        val currentStart = weekStart(nowEpoch)
        return (WEEK_BUCKETS - 1 downTo 0).map { offset ->
            val start = currentStart - offset * WEEK_MS
            WeekBucket(
                start = start,
                end = start + WEEK_MS,
                label = WEEK_FORMAT.get().format(Date(start))
            )
        }
    }

    private fun weekStart(epoch: Long): Long {
        val day = epoch / DAY_MS
        return (day - ((day + 3L) % 7L)) * DAY_MS
    }

    private fun reasonType(reason: String): String = when {
        reason.contains("异常率") -> "failure_rate"
        reason.contains("保护率") -> "protection_rate"
        reason.contains("风险级别") -> "risk_raise"
        reason.contains("严重级别") -> "severity_raise"
        reason.contains("问题类型") || reason.contains("类型升级") -> "type_escalation"
        else -> "other"
    }

    private fun reasonLabel(type: String): String = when (type) {
        "failure_rate" -> "异常率上升"
        "protection_rate" -> "保护率上升"
        "risk_raise" -> "风险等级提高"
        "severity_raise" -> "严重级别提高"
        "type_escalation" -> "问题类型升级"
        else -> "其他原因"
    }

    private fun stateFromOperation(operation: String): String = when {
        operation.endsWith("kept") -> "kept"
        operation.endsWith("observing") -> "observing"
        operation.endsWith("ignored") -> "ignored"
        else -> "pending"
    }

    private fun trend(recent: Int, previous: Int): String = when {
        recent == 0 && previous == 0 -> "flat"
        recent > previous -> "up"
        recent < previous -> "down"
        else -> "flat"
    }

    private fun summary(available: Boolean, active: Int, repeated: Int, reopened: Int, resolutionRate: Int): String = when {
        !available -> "暂无审核历史，完成规则审核后会生成趋势"
        active > 0 -> "当前有 $active 项重新打开的规则等待处理"
        repeated > 0 -> "发现 $repeated 项规则曾反复重新打开"
        reopened > 0 -> "重新打开项处理完成率为 $resolutionRate%"
        else -> "已有人工审核记录，暂未发生自动重新打开"
    }

    private fun average(values: List<Long>): Long = if (values.isEmpty()) 0L else values.sum() / values.size

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2L else sorted[middle]
    }

    private fun percent(part: Int, total: Int): Int = if (total <= 0) 0 else {
        ((part.coerceAtMost(total) * 100.0) / total).roundToInt().coerceIn(0, 100)
    }

    private fun sanitize(raw: String, limit: Int): String = raw
        .replace(Regex("[\\p{Cntrl}]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(limit)

    private fun stableKey(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private inner class SubjectStat(val category: String) {
        var reopenCount: Int = 0
        var manualReviewCount: Int = 0
        var resolvedCount: Int = 0
        var activeReopened: Boolean = false
        var lastState: String = "pending"
        var lastActionAt: Long = 0L
        var lastReopenAt: Long = 0L
        var lastReason: String = ""
        val resolutionDurations = mutableListOf<Long>()
        val reasonCounts = linkedMapOf<String, Int>()

        fun toJson(): JSONObject = JSONObject()
            .put("key", stableKey(category))
            .put("category", category)
            .put("reopenCount", reopenCount)
            .put("manualReviewCount", manualReviewCount)
            .put("resolvedCount", resolvedCount)
            .put("activeReopened", activeReopened)
            .put("repeated", reopenCount >= REPEATED_REOPEN_THRESHOLD)
            .put("lastState", lastState)
            .put("lastActionAt", lastActionAt)
            .put("lastReopenAt", lastReopenAt)
            .put("lastReason", lastReason)
            .put("averageResolutionMs", average(resolutionDurations))
            .put("reasonBreakdown", JSONArray(reasonBreakdown(reasonCounts, reopenCount)))
    }

    private data class CurrentReopen(val category: String, val reopenedAt: Long, val reason: String)

    private data class WeekBucket(
        val start: Long,
        val end: Long,
        val label: String,
        var reopens: Int = 0,
        var reviews: Int = 0,
        var resolved: Int = 0
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("startEpoch", start)
            .put("label", label)
            .put("reopens", reopens)
            .put("reviews", reviews)
            .put("resolved", resolved)
    }

    companion object {
        private const val LOOKBACK_DAYS = 90
        private const val TREND_WINDOW_DAYS = 14
        private const val WEEK_BUCKETS = 8
        private const val MAX_INPUT_EVENTS = 240
        private const val MAX_QUEUE_ITEMS = 40
        private const val MAX_ITEMS = 20
        private const val MAX_DETAILS = 12
        private const val MAX_REASON_TYPES = 6
        private const val MAX_CATEGORY_LENGTH = 100
        private const val MAX_REASON_LENGTH = 240
        private const val REPEATED_REOPEN_THRESHOLD = 2
        private const val DAY_MS = 24L * 60L * 60L * 1_000L
        private const val WEEK_MS = 7L * DAY_MS
        private const val MAX_RESOLUTION_MS = LOOKBACK_DAYS * DAY_MS
        private const val REVIEW_PREFIX = "rule-review-"
        private const val REOPEN_OPERATION = "rule-review-reopened"
        private val WEEK_FORMAT = ThreadLocal.withInitial { SimpleDateFormat("MM/dd", Locale.getDefault()) }
    }
}
