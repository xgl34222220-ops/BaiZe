package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Read-only effectiveness scoring for recent scan and cleanup activity.
 *
 * The analyzer consumes only bounded, already-redacted audit events. It never writes configuration,
 * never disables rules, never starts a cleanup and never changes scheduler cadence.
 */
internal class CleanupEffectivenessAnalyzer {
    fun analyze(events: List<JSONObject>, nowEpoch: Long = System.currentTimeMillis()): JSONObject {
        val cutoff = nowEpoch - LOOKBACK_DAYS * DAY_MS
        val recent = events.asSequence()
            .filter { it.optLong("timeEpoch", nowEpoch) >= cutoff }
            .take(MAX_INPUT_EVENTS)
            .toList()
        val scoreable = recent.filter { event ->
            event.optString("kind") in setOf("scan", "clean", "organize") &&
                event.optString("status") !in setOf("accepted")
        }
        val taskScores = scoreable.take(MAX_RECENT_TASKS).map(::scoreTask)
        val dimensions = aggregateDimensions(taskScores)
        val overall = weightedOverall(dimensions)
        val trend = buildTrend(scoreable, nowEpoch)
        val observations = buildRuleObservations(recent)
        val available = taskScores.isNotEmpty()

        return JSONObject()
            .put("available", available)
            .put("readOnly", true)
            .put("automaticActions", false)
            .put("rulesChanged", false)
            .put("scheduleUntouched", true)
            .put("lookbackDays", LOOKBACK_DAYS)
            .put("sampleCount", scoreable.size)
            .put("overall", if (available) overall else 0)
            .put("grade", if (available) grade(overall) else "N/A")
            .put("summary", summary(available, overall, trend.optString("direction"), observations.length()))
            .put("dimensions", JSONObject()
                .put("safety", dimensions.safety)
                .put("benefit", dimensions.benefit)
                .put("speed", dimensions.speed)
                .put("stability", dimensions.stability))
            .put("trend", trend)
            .put("recentTasks", JSONArray(taskScores.map(TaskScore::toJson)))
            .put("ruleObservations", observations)
    }

    private fun scoreTask(event: JSONObject): TaskScore {
        val kind = event.optString("kind")
        val status = event.optString("status")
        val selected = event.optLong("selected").coerceAtLeast(0L)
        val protected = event.optLong("protected").coerceAtLeast(0L)
        val skipped = event.optLong("skipped").coerceAtLeast(0L)
        val errors = event.optLong("errors").coerceAtLeast(0L)
        val bytes = event.optLong("bytes").coerceAtLeast(0L)
        val elapsedMs = event.optLong("elapsedMs").coerceAtLeast(0L)
        val blocked = (protected + skipped).coerceAtMost(selected.coerceAtLeast(protected + skipped))
        val protectionRate = if (selected <= 0L) 0 else ((blocked * 100.0) / selected).roundToInt()

        val safety = (100 - protectionRate.coerceAtMost(55) - (errors * 18L).coerceAtMost(36L).toInt())
            .coerceIn(0, 100)
        val benefit = when (kind) {
            "scan" -> when {
                selected >= 20L || bytes >= 256L * MIB -> 90
                selected > 0L || bytes > 0L -> 75
                else -> 65
            }
            else -> when {
                bytes >= 1024L * MIB -> 100
                bytes >= 512L * MIB -> 92
                bytes >= 256L * MIB -> 84
                bytes >= 128L * MIB -> 74
                bytes >= 32L * MIB -> 62
                bytes > 0L -> 48
                status == "success" -> 35
                else -> 20
            }
        }
        val speed = speedScore(elapsedMs)
        val stability = when (status) {
            "success", "scanned" -> if (errors == 0L) 100 else 75
            "partial" -> 62
            "cancelled" -> 42
            "failed" -> 15
            else -> 55
        }
        val overall = ((safety * 35) + (benefit * 30) + (speed * 15) + (stability * 20)) / 100
        return TaskScore(
            id = event.optString("id").take(80),
            time = event.optString("time").take(40),
            operation = event.optString("operation").take(80),
            kind = kind.take(20),
            status = status.take(20),
            bytes = bytes,
            elapsedMs = elapsedMs,
            safety = safety,
            benefit = benefit,
            speed = speed,
            stability = stability,
            overall = overall.coerceIn(0, 100)
        )
    }

    private fun aggregateDimensions(tasks: List<TaskScore>): Dimensions {
        if (tasks.isEmpty()) return Dimensions(0, 0, 0, 0)
        return Dimensions(
            safety = tasks.map(TaskScore::safety).averageScore(),
            benefit = tasks.map(TaskScore::benefit).averageScore(),
            speed = tasks.map(TaskScore::speed).averageScore(),
            stability = tasks.map(TaskScore::stability).averageScore()
        )
    }

    private fun weightedOverall(dimensions: Dimensions): Int = (
        dimensions.safety * 35 + dimensions.benefit * 30 + dimensions.speed * 15 + dimensions.stability * 20
    ) / 100

    private fun buildTrend(events: List<JSONObject>, nowEpoch: Long): JSONObject {
        val recentCutoff = nowEpoch - TREND_DAYS * DAY_MS
        val current = events.filter { it.optLong("timeEpoch") >= recentCutoff }.map(::scoreTask)
        val previous = events.filter { it.optLong("timeEpoch") < recentCutoff }.map(::scoreTask)
        val currentOverall = if (current.isEmpty()) 0 else current.map(TaskScore::overall).averageScore()
        val previousOverall = if (previous.isEmpty()) 0 else previous.map(TaskScore::overall).averageScore()
        val comparable = current.size >= MIN_TREND_SAMPLES && previous.size >= MIN_TREND_SAMPLES
        val delta = if (comparable) currentOverall - previousOverall else 0
        val direction = when {
            !comparable -> "insufficient"
            delta >= 5 -> "improving"
            delta <= -5 -> "declining"
            else -> "stable"
        }
        return JSONObject()
            .put("direction", direction)
            .put("delta", delta)
            .put("recentScore", currentOverall)
            .put("previousScore", previousOverall)
            .put("recentSamples", current.size)
            .put("previousSamples", previous.size)
            .put("message", when (direction) {
                "improving" -> "最近 7 天清理效果较此前提升 $delta 分"
                "declining" -> "最近 7 天清理效果较此前下降 ${-delta} 分"
                "stable" -> "最近清理效果保持稳定"
                else -> "历史样本不足，暂不判断趋势"
            })
    }

    private fun buildRuleObservations(events: List<JSONObject>): JSONArray {
        val stats = linkedMapOf<String, RuleStat>()
        events.forEach { event ->
            val details = event.optJSONArray("details") ?: return@forEach
            for (index in 0 until details.length()) {
                val detail = details.optJSONObject(index) ?: continue
                val category = detail.optString("category").trim().take(100)
                if (category.isBlank()) continue
                val action = detail.optString("action").lowercase(Locale.ROOT)
                val stat = stats.getOrPut(category) { RuleStat(category) }
                stat.observations += 1
                stat.bytes += detail.optLong("bytes").coerceAtLeast(0L)
                if (action in PROCESSED_ACTIONS) stat.processed += 1
                if (action in PROTECTED_ACTIONS) stat.protected += 1
            }
        }
        val candidates = stats.values.mapNotNull { stat ->
            if (stat.observations < MIN_RULE_OBSERVATIONS) return@mapNotNull null
            val protectionRate = percent(stat.protected, stat.observations)
            val averageBytes = if (stat.processed <= 0) 0L else stat.bytes / stat.processed
            when {
                protectionRate >= 50 -> JSONObject()
                    .put("category", stat.category)
                    .put("type", "frequently_protected")
                    .put("severity", "review")
                    .put("observations", stat.observations)
                    .put("processed", stat.processed)
                    .put("protected", stat.protected)
                    .put("protectionRate", protectionRate)
                    .put("bytes", stat.bytes)
                    .put("message", "该分类有 $protectionRate% 的记录被安全机制拦截，建议检查规则范围")
                stat.processed >= MIN_PROCESSED_FOR_LOW_VALUE && averageBytes < LOW_VALUE_AVERAGE_BYTES -> JSONObject()
                    .put("category", stat.category)
                    .put("type", "low_value")
                    .put("severity", "info")
                    .put("observations", stat.observations)
                    .put("processed", stat.processed)
                    .put("protected", stat.protected)
                    .put("protectionRate", protectionRate)
                    .put("bytes", stat.bytes)
                    .put("averageBytes", averageBytes)
                    .put("message", "该分类多次执行但平均收益较低，可人工评估是否继续保留")
                else -> null
            }
        }.sortedWith(compareByDescending<JSONObject> { it.optString("type") == "frequently_protected" }
            .thenByDescending { it.optInt("observations") })
        return JSONArray(candidates.take(MAX_RULE_OBSERVATIONS))
    }

    private fun speedScore(elapsedMs: Long): Int = when {
        elapsedMs <= 0L -> 60
        elapsedMs <= 10_000L -> 100
        elapsedMs <= 30_000L -> 88
        elapsedMs <= 60_000L -> 74
        elapsedMs <= 120_000L -> 58
        elapsedMs <= 300_000L -> 38
        else -> 20
    }

    private fun grade(score: Int): String = when {
        score >= 90 -> "S"
        score >= 80 -> "A"
        score >= 70 -> "B"
        score >= 60 -> "C"
        else -> "D"
    }

    private fun summary(available: Boolean, overall: Int, direction: String, observations: Int): String = when {
        !available -> "暂无足够的扫描或清理记录"
        direction == "declining" -> "总体 $overall 分，近期效果有所下降"
        observations > 0 -> "总体 $overall 分，发现 $observations 项规则观察"
        overall >= 80 -> "总体 $overall 分，清理效果良好"
        overall >= 60 -> "总体 $overall 分，仍有优化空间"
        else -> "总体 $overall 分，建议查看异常与保护原因"
    }

    private fun percent(part: Int, total: Int): Int = if (total <= 0) 0 else ((part.coerceAtMost(total) * 100.0) / total).roundToInt()
    private fun List<Int>.averageScore(): Int = if (isEmpty()) 0 else average().roundToInt().coerceIn(0, 100)

    private data class Dimensions(val safety: Int, val benefit: Int, val speed: Int, val stability: Int)
    private data class RuleStat(val category: String, var observations: Int = 0, var processed: Int = 0, var protected: Int = 0, var bytes: Long = 0L)
    private data class TaskScore(
        val id: String,
        val time: String,
        val operation: String,
        val kind: String,
        val status: String,
        val bytes: Long,
        val elapsedMs: Long,
        val safety: Int,
        val benefit: Int,
        val speed: Int,
        val stability: Int,
        val overall: Int
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("time", time)
            .put("operation", operation)
            .put("kind", kind)
            .put("status", status)
            .put("bytes", bytes)
            .put("elapsedMs", elapsedMs)
            .put("safety", safety)
            .put("benefit", benefit)
            .put("speed", speed)
            .put("stability", stability)
            .put("overall", overall)
            .put("grade", gradeFor(overall))

        private fun gradeFor(score: Int): String = when {
            score >= 90 -> "S"
            score >= 80 -> "A"
            score >= 70 -> "B"
            score >= 60 -> "C"
            else -> "D"
        }
    }

    companion object {
        private const val LOOKBACK_DAYS = 30
        private const val TREND_DAYS = 7
        private const val DAY_MS = 24L * 60L * 60L * 1_000L
        private const val MAX_INPUT_EVENTS = 160
        private const val MAX_RECENT_TASKS = 20
        private const val MIN_TREND_SAMPLES = 2
        private const val MIN_RULE_OBSERVATIONS = 3
        private const val MIN_PROCESSED_FOR_LOW_VALUE = 2
        private const val MAX_RULE_OBSERVATIONS = 12
        private const val MIB = 1024L * 1024L
        private const val LOW_VALUE_AVERAGE_BYTES = MIB
        private val PROCESSED_ACTIONS = setOf("cleaned", "deleted", "quarantined", "restored", "purged", "processed")
        private val PROTECTED_ACTIONS = setOf("protected", "skipped", "partial")
    }
}
