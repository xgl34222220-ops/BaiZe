package io.github.xgl34222220.baize.root

import io.github.xgl34222220.baize.CleanupPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Read-only cleanup policy advisor.
 *
 * The advisor never writes configuration, never changes scheduler cadence and never starts a cleanup
 * task. It only summarizes bounded audit events and coarse storage pressure into a recommendation
 * that the user may explicitly accept from the policy screen.
 */
internal class PolicyAdvisor(
    private val configFile: File = File(RootPaths.CONFIG_FILE),
    private val dataRoot: File = File("/data"),
    private val sharedRoot: File = File("/storage/emulated/0")
) {
    fun evaluate(events: List<JSONObject>, nowEpoch: Long = System.currentTimeMillis()): JSONObject {
        val config = RootFileStore.readEnv(configFile)
        val current = CleanupPolicy.fromId(config.optInt("cleanup_policy", CleanupPolicy.BALANCED.id))
        val cutoff = nowEpoch - LOOKBACK_DAYS * DAY_MS
        val recent = events.asSequence()
            .filter { it.optLong("timeEpoch", nowEpoch) >= cutoff }
            .take(MAX_INPUT_EVENTS)
            .toList()

        val scans = recent.filter { it.optString("kind") == "scan" && it.optString("status") !in setOf("failed", "cancelled") }
        val operations = recent.filter {
            it.optString("kind") in setOf("clean", "safety", "organize") &&
                it.optString("status") !in setOf("accepted", "scanned")
        }
        val failed = operations.count { it.optString("status") == "failed" || it.optLong("errors") > 0L }
        val partial = operations.count { it.optString("status") == "partial" }
        val failureRate = percent(failed + partial, operations.size)

        val quarantines = operations.count {
            it.optString("operation").contains("quarantine", ignoreCase = true) &&
                it.optString("status") in setOf("success", "partial")
        }
        val restores = operations.count {
            it.optString("operation").contains("restore", ignoreCase = true) &&
                it.optString("status") in setOf("success", "partial")
        }
        val restoreRate = if (quarantines <= 0) 0 else ((restores.coerceAtMost(quarantines) * 100.0) / quarantines).roundToInt()

        val selected = operations.sumOf { it.optLong("selected").coerceAtLeast(0L) }
        val protected = operations.sumOf { it.optLong("protected").coerceAtLeast(0L) }
        val protectionRate = if (selected <= 0L) 0 else ((protected.coerceAtMost(selected) * 100.0) / selected).roundToInt()
        val releasedBytes = operations.asSequence()
            .filter { it.optString("kind") == "clean" && it.optString("status") in setOf("success", "partial") }
            .sumOf { it.optLong("bytes").coerceAtLeast(0L) }
        val successfulCleans = operations.count {
            it.optString("kind") == "clean" && it.optString("status") in setOf("success", "partial")
        }
        val averageReleaseBytes = if (successfulCleans == 0) 0L else releasedBytes / successfulCleans
        val averageScanMs = scans.map { it.optLong("elapsedMs").coerceAtLeast(0L) }
            .filter { it > 0L }
            .averageOrZero()
        val storage = storagePressure()
        val evidenceCount = operations.size + scans.size

        val reasons = mutableListOf<String>()
        var recommended = current
        var decision = "keep"

        val safetyConcern = (operations.size >= 4 && failureRate >= 25) ||
            (quarantines >= 2 && restoreRate >= 25) ||
            (selected >= 10L && protectionRate >= 40)

        when {
            safetyConcern -> {
                recommended = CleanupPolicy.CONSERVATIVE
                decision = "safety"
                if (failureRate >= 25) reasons += "最近任务失败或部分失败率为 $failureRate%"
                if (quarantines >= 2 && restoreRate >= 25) reasons += "隔离内容恢复率为 $restoreRate%，建议降低清理干预"
                if (selected >= 10L && protectionRate >= 40) reasons += "受保护项目占比为 $protectionRate%"
            }
            storage.freePercent in 0..8 && evidenceCount >= MIN_EVIDENCE && failureRate <= 15 && restoreRate <= 10 -> {
                recommended = CleanupPolicy.AGGRESSIVE
                decision = "critical_storage"
                reasons += "当前最紧张存储区域仅剩 ${storage.freePercent}% 可用空间"
                reasons += "近期任务稳定，未发现明显误清理或恢复信号"
            }
            storage.freePercent in 9..15 && evidenceCount >= MIN_EVIDENCE && failureRate < 20 && restoreRate < 15 -> {
                recommended = if (current == CleanupPolicy.CONSERVATIVE) CleanupPolicy.BALANCED else CleanupPolicy.AGGRESSIVE
                decision = "low_storage"
                reasons += "当前最紧张存储区域剩余 ${storage.freePercent}%"
                reasons += "近期清理失败率较低，可适度扩大普通垃圾覆盖"
            }
            current == CleanupPolicy.AGGRESSIVE && storage.freePercent >= 20 && evidenceCount >= MIN_EVIDENCE -> {
                recommended = CleanupPolicy.BALANCED
                decision = "pressure_relieved"
                reasons += "可用空间已恢复到 ${storage.freePercent}%"
                reasons += "无需长期保持积极档的较短保留期"
            }
            evidenceCount < MIN_EVIDENCE -> {
                decision = "insufficient_data"
                reasons += "近期有效记录不足，暂时保持当前档位"
            }
            else -> {
                reasons += "当前空间压力和历史稳定性与${current.title}档匹配"
            }
        }

        if (averageScanMs >= SLOW_SCAN_MS) {
            reasons += "平均扫描耗时约 ${formatSeconds(averageScanMs)}，建议避免频繁手动重复扫描"
        }
        if (averageReleaseBytes > 0L) {
            reasons += "最近每次成功清理平均释放 ${humanBytes(averageReleaseBytes)}"
        }

        val confidence = when {
            evidenceCount >= 10 && storage.freePercent >= 0 -> "high"
            evidenceCount >= MIN_EVIDENCE -> "medium"
            else -> "low"
        }
        val summary = when {
            recommended == current && decision == "insufficient_data" -> "数据不足，建议暂时保持${current.title}档"
            recommended == current -> "当前${current.title}档与设备状态匹配"
            else -> "建议从${current.title}档切换到${recommended.title}档"
        }

        return JSONObject()
            .put("available", true)
            .put("automatic", false)
            .put("scheduleUntouched", true)
            .put("lookbackDays", LOOKBACK_DAYS)
            .put("currentPolicy", current.key)
            .put("currentPolicyId", current.id)
            .put("recommendedPolicy", recommended.key)
            .put("recommendedPolicyId", recommended.id)
            .put("changed", recommended != current)
            .put("decision", decision)
            .put("confidence", confidence)
            .put("summary", summary)
            .put("sampleCount", evidenceCount)
            .put("operationCount", operations.size)
            .put("scanCount", scans.size)
            .put("storageFreePercent", storage.freePercent)
            .put("storageFreeBytes", storage.freeBytes)
            .put("failureRate", failureRate)
            .put("restoreRate", restoreRate)
            .put("protectionRate", protectionRate)
            .put("averageScanMs", averageScanMs)
            .put("releasedBytes", releasedBytes)
            .put("averageReleaseBytes", averageReleaseBytes)
            .put("reasons", JSONArray(reasons.distinct().take(MAX_REASONS)))
            .put("signals", JSONArray().apply {
                put(signal("storage", "可用空间", if (storage.freePercent < 0) "未知" else "${storage.freePercent}%"))
                put(signal("failure", "失败率", "$failureRate%"))
                put(signal("restore", "恢复率", "$restoreRate%"))
                put(signal("scan", "平均扫描", if (averageScanMs <= 0L) "暂无" else formatSeconds(averageScanMs)))
            })
    }

    private fun storagePressure(): StorageStat {
        val roots = listOf(dataRoot, sharedRoot)
            .mapNotNull { root ->
                val total = runCatching { root.totalSpace }.getOrDefault(0L)
                val free = runCatching { root.usableSpace }.getOrDefault(0L)
                if (total <= 0L) null else StorageStat(
                    freePercent = ((free.coerceAtLeast(0L) * 100.0) / total).roundToInt().coerceIn(0, 100),
                    freeBytes = free.coerceAtLeast(0L)
                )
            }
        return roots.minByOrNull { it.freePercent } ?: StorageStat(-1, 0L)
    }

    private fun signal(id: String, label: String, value: String): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("value", value)

    private fun percent(part: Int, total: Int): Int = if (total <= 0) 0 else ((part.coerceAtMost(total) * 100.0) / total).roundToInt()

    private fun List<Long>.averageOrZero(): Long = if (isEmpty()) 0L else sum() / size

    private fun formatSeconds(milliseconds: Long): String = if (milliseconds < 1_000L) {
        "${milliseconds}ms"
    } else {
        String.format(Locale.US, "%.1fs", milliseconds / 1_000.0)
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private data class StorageStat(val freePercent: Int, val freeBytes: Long)

    companion object {
        private const val LOOKBACK_DAYS = 30
        private const val DAY_MS = 24L * 60L * 60L * 1_000L
        private const val MAX_INPUT_EVENTS = 120
        private const val MIN_EVIDENCE = 3
        private const val MAX_REASONS = 6
        private const val SLOW_SCAN_MS = 45_000L
    }
}
