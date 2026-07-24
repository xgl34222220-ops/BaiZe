package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Root-owned structured audit timeline.
 *
 * New engine results are persisted as bounded JSONL events. Existing history.tsv rows are merged at
 * read time so upgrades keep their old task records without a destructive migration. Paths are
 * reduced to a tail before persistence; the audit UI never receives arbitrary full filesystem paths.
 */
internal class AuditRepository(
    private val stateDir: File = File(RootPaths.STATE_DIR)
) {
    private val auditFile = File(stateDir, "audit.jsonl")
    private val metaFile = File(stateDir, "audit.meta")
    private val historyFile = File(stateDir, "history.tsv")
    private val policyAdvisor = PolicyAdvisor()
    private val effectivenessAnalyzer = CleanupEffectivenessAnalyzer()

    @Synchronized
    fun recordResult(
        operation: String,
        source: String,
        rawResult: String,
        startedEpoch: Long = System.currentTimeMillis()
    ) {
        val result = runCatching { JSONObject(rawResult) }.getOrElse {
            JSONObject()
                .put("success", false)
                .put("error", "invalid_result")
                .put("message", it.message ?: "无法解析任务结果")
        }
        appendEvent(buildEvent(operation, source, result, startedEpoch))
    }

    @Synchronized
    fun recordNativeTask(taskJson: String, historyResult: String) {
        val task = runCatching { JSONObject(taskJson) }.getOrNull() ?: return
        val history = runCatching { JSONObject(historyResult) }.getOrNull()
        val result = JSONObject(task.toString())
        if (history?.optBoolean("success") != true) {
            result.put("success", false)
            result.put("error", history?.optString("error").orEmpty().ifBlank { "history_record_failed" })
        }
        appendEvent(buildEvent(task.optString("mode", "native-task"), "app-native", result, System.currentTimeMillis()))
    }

    @Synchronized
    fun timelinePageJson(offset: Int, requestedLimit: Int): String {
        val clearEpoch = readClearEpoch()
        val combined = (readAuditEvents(clearEpoch) + readLegacyEvents(clearEpoch))
            .distinctBy { it.optString("id") }
            .sortedByDescending { it.optLong("timeEpoch") }
            .take(MAX_EVENTS)

        val safeOffset = offset.coerceIn(0, combined.size)
        val safeLimit = requestedLimit.coerceIn(1, MAX_PAGE_SIZE)
        val end = (safeOffset + safeLimit).coerceAtMost(combined.size)
        val page = JSONArray()
        for (index in safeOffset until end) page.put(combined[index])

        var successCount = 0
        var failedCount = 0
        var cancelledCount = 0
        var releasedBytes = 0L
        var quarantinedBytes = 0L
        var protectedCount = 0L
        combined.forEach { event ->
            when (event.optString("status")) {
                "success", "partial", "scanned", "accepted" -> successCount += 1
                "cancelled" -> cancelledCount += 1
                else -> failedCount += 1
            }
            val kind = event.optString("kind")
            val bytes = event.optLong("bytes").coerceAtLeast(0L)
            if (kind == "clean" && event.optString("status") in setOf("success", "partial")) releasedBytes += bytes
            if (kind == "safety" && event.optString("operation").contains("quarantine") && event.optString("status") in setOf("success", "partial")) {
                quarantinedBytes += bytes
            }
            protectedCount += event.optLong("protected").coerceAtLeast(0L)
        }

        val advisor = policyAdvisor.evaluate(combined)
        val effectiveness = effectivenessAnalyzer.analyze(combined)

        return JSONObject()
            .put("success", true)
            .put("schema", SCHEMA_VERSION)
            .put("total", combined.size)
            .put("offset", safeOffset)
            .put("nextOffset", end)
            .put("hasMore", end < combined.size)
            .put("successCount", successCount)
            .put("failedCount", failedCount)
            .put("cancelledCount", cancelledCount)
            .put("releasedBytes", releasedBytes)
            .put("quarantinedBytes", quarantinedBytes)
            .put("protectedCount", protectedCount)
            .put("advisor", advisor)
            .put("effectiveness", effectiveness)
            .put("events", page)
            .toString()
    }

    @Synchronized
    fun clearTimelineJson(): String = runCatching {
        stateDir.mkdirs()
        RootFileStore.writeAtomic(auditFile, "")
        RootFileStore.writeAtomic(metaFile, "cleared_at=${System.currentTimeMillis()}\n")
        JSONObject().put("success", true).put("message", "审计时间线已清空，累计统计和清理历史未修改").toString()
    }.getOrElse {
        JSONObject().put("success", false).put("error", it.message ?: it.javaClass.simpleName).toString()
    }

    private fun buildEvent(operationRaw: String, sourceRaw: String, result: JSONObject, startedEpoch: Long): JSONObject {
        val operation = sanitize(operationRaw, 80).ifBlank { "unknown" }
        val latest = result.optJSONObject("latest") ?: JSONObject()
        val now = System.currentTimeMillis()
        val time = TIME_FORMAT.get().format(Date(now))
        val errorCode = sanitize(result.optString("error"), 120)
        val cancelled = result.optBoolean("cancelled") || latest.optBoolean("cancelled")
        val accepted = result.optBoolean("accepted")
        val failures = number(result, latest, "failures", "errors").coerceAtLeast(0L)
        val success = result.optBoolean("success", errorCode.isBlank())
        val status = when {
            cancelled -> "cancelled"
            accepted -> "accepted"
            errorCode.isNotBlank() || !success -> "failed"
            failures > 0L -> "partial"
            operation.contains("scan") -> "scanned"
            else -> "success"
        }
        val source = sanitize(
            result.optString("trigger").ifBlank { latest.optString("trigger") }.ifBlank { sourceRaw },
            100
        )
        val message = sanitize(
            result.optString("message").ifBlank { latest.optString("result") }
                .ifBlank { result.optString("result") }
                .ifBlank { errorCode.ifBlank { defaultMessage(operation, status) } },
            600
        )
        val bytes = number(result, latest, "deletedBytes", "quarantinedBytes", "bytes").coerceAtLeast(0L)
        val files = number(result, latest, "deletedFiles", "quarantinedFiles", "files", "regular_files").coerceAtLeast(0L)
        val directories = number(result, latest, "deletedDirectories", "quarantinedDirectories", "directories", "empty_dirs").coerceAtLeast(0L)
        val selected = number(result, latest, "selected", "selectedCandidates", "totalCandidates").coerceAtLeast(0L)
        val processed = number(result, latest, "cleanedCandidates", "quarantinedCandidates", "purged", "processed").coerceAtLeast(0L)
        val skipped = number(result, latest, "skippedCandidates", "skipped").coerceAtLeast(0L)
        val elapsedMs = when {
            result.has("elapsedMs") -> result.optLong("elapsedMs")
            latest.has("elapsedMs") -> latest.optLong("elapsedMs")
            result.has("elapsedSeconds") -> result.optLong("elapsedSeconds") * 1_000L
            latest.has("elapsed") -> latest.optLong("elapsed") * 1_000L
            else -> (now - startedEpoch).coerceAtLeast(0L)
        }.coerceAtLeast(0L)
        val details = sanitizeDetails(result.optJSONArray("details"))
        val protected = (0 until details.length()).count {
            val action = details.optJSONObject(it)?.optString("action").orEmpty()
            action == "protected" || action == "partial" || action == "skipped"
        }.toLong()
        val reasons = collectReasons(errorCode, message, details)
        val snapshot = sanitize(
            result.optString("snapshotId").ifBlank { latest.optString("snapshotId") },
            100
        )
        val profile = sanitize(result.optString("profile").ifBlank { latest.optString("profile") }, 80)
        val id = stableId("$time\u0000$operation\u0000$bytes\u0000$files\u0000$message")

        return JSONObject()
            .put("schema", SCHEMA_VERSION)
            .put("id", id)
            .put("timeEpoch", now)
            .put("time", time)
            .put("operation", operation)
            .put("kind", kindFor(operation))
            .put("source", source.ifBlank { "app" })
            .put("status", status)
            .put("message", message)
            .put("errorCode", errorCode)
            .put("profile", profile)
            .put("snapshotId", snapshot)
            .put("selected", selected)
            .put("processed", processed)
            .put("skipped", skipped)
            .put("protected", protected)
            .put("bytes", bytes)
            .put("files", files)
            .put("directories", directories)
            .put("errors", failures)
            .put("elapsedMs", elapsedMs)
            .put("reasonCodes", reasons)
            .put("details", details)
            .put("legacy", false)
    }

    private fun sanitizeDetails(raw: JSONArray?): JSONArray {
        val output = JSONArray()
        if (raw == null) return output
        for (index in 0 until raw.length().coerceAtMost(MAX_DETAILS)) {
            val item = raw.optJSONObject(index) ?: continue
            val path = item.optString("path").ifBlank { item.optString("restoredPath") }
            output.put(
                JSONObject()
                    .put("action", sanitize(item.optString("action"), 60))
                    .put("category", sanitize(item.optString("category").ifBlank { item.optString("label") }, 100))
                    .put("risk", sanitize(item.optString("risk"), 40))
                    .put("reason", sanitize(item.optString("reason").ifBlank { item.optString("message") }, 240))
                    .put("pathTail", sanitize(pathTail(path), 160))
                    .put("bytes", item.optLong("bytes").coerceAtLeast(0L))
                    .put("files", item.optLong("files").coerceAtLeast(0L))
                    .put("directories", item.optLong("directories").coerceAtLeast(0L))
            )
        }
        return output
    }

    private fun collectReasons(errorCode: String, message: String, details: JSONArray): JSONArray {
        val values = linkedSetOf<String>()
        errorCode.takeIf { it.isNotBlank() }?.let(values::add)
        if (errorCode.isNotBlank()) message.takeIf { it.isNotBlank() }?.let(values::add)
        for (index in 0 until details.length()) {
            val reason = details.optJSONObject(index)?.optString("reason").orEmpty()
            if (reason.isNotBlank()) values += reason
            if (values.size >= MAX_REASONS) break
        }
        return JSONArray(values.take(MAX_REASONS))
    }

    private fun appendEvent(event: JSONObject) {
        stateDir.mkdirs()
        val lines = runCatching { if (auditFile.isFile) auditFile.readLines() else emptyList() }
            .getOrDefault(emptyList())
            .filter { it.isNotBlank() }
            .takeLast(MAX_EVENTS - 1)
            .toMutableList()
        lines += event.toString()
        RootFileStore.writeAtomic(auditFile, lines.joinToString("\n", postfix = "\n"))
    }

    private fun readAuditEvents(clearEpoch: Long): List<JSONObject> = runCatching {
        if (!auditFile.isFile) return@runCatching emptyList()
        auditFile.readLines()
            .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
            .filter { it.optLong("timeEpoch") > clearEpoch }
            .takeLast(MAX_EVENTS)
    }.getOrDefault(emptyList())

    private fun readLegacyEvents(clearEpoch: Long): List<JSONObject> = runCatching {
        if (!historyFile.isFile) return@runCatching emptyList()
        historyFile.readLines().takeLast(MAX_LEGACY).mapNotNull { raw ->
            val columns = raw.split('\t', limit = 10)
            if (columns.size < 7) return@mapNotNull null
            val time = sanitize(columns[0], 40)
            val epoch = runCatching { TIME_FORMAT.get().parse(time)?.time ?: 0L }.getOrDefault(0L)
            if (epoch <= clearEpoch) return@mapNotNull null
            val operation = sanitize(columns[1], 80)
            val bytes = columns[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val files = columns[3].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val directories = columns[4].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val errors = columns[5].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val message = sanitize(columns[6], 600)
            val source = sanitize(columns.getOrNull(7).orEmpty(), 100).ifBlank { "history" }
            val scan = operation == "scan" || operation.endsWith("-scan")
            val status = when {
                errors > 0L -> "partial"
                scan -> "scanned"
                else -> "success"
            }
            JSONObject()
                .put("schema", SCHEMA_VERSION)
                .put("id", stableId("$time\u0000$operation\u0000$bytes\u0000$files\u0000$message"))
                .put("timeEpoch", epoch)
                .put("time", time)
                .put("operation", operation)
                .put("kind", kindFor(operation))
                .put("source", source)
                .put("status", status)
                .put("message", message)
                .put("errorCode", "")
                .put("profile", "")
                .put("snapshotId", "")
                .put("selected", files)
                .put("processed", files)
                .put("skipped", 0)
                .put("protected", 0)
                .put("bytes", bytes)
                .put("files", files)
                .put("directories", directories)
                .put("errors", errors)
                .put("elapsedMs", 0)
                .put("reasonCodes", JSONArray())
                .put("details", JSONArray())
                .put("legacy", true)
        }
    }.getOrDefault(emptyList())

    private fun readClearEpoch(): Long = RootFileStore.readEnv(metaFile).optLong("cleared_at", 0L).coerceAtLeast(0L)

    private fun number(primary: JSONObject, fallback: JSONObject, vararg keys: String): Long {
        keys.forEach { key -> if (primary.has(key)) return primary.optLong(key) }
        keys.forEach { key -> if (fallback.has(key)) return fallback.optLong(key) }
        return 0L
    }

    private fun kindFor(operation: String): String = when {
        operation.contains("quarantine") || operation.contains("restore") || operation.contains("purge") || operation.contains("expire") -> "safety"
        operation.contains("scan") -> "scan"
        operation.contains("organize") -> "organize"
        else -> "clean"
    }

    private fun defaultMessage(operation: String, status: String): String = when (status) {
        "accepted" -> "$operation 已交给后台执行"
        "scanned" -> "$operation 扫描完成"
        "cancelled" -> "$operation 已停止"
        "failed" -> "$operation 执行失败"
        else -> "$operation 执行完成"
    }

    private fun pathTail(path: String): String {
        val normalized = path.trim().replace('\\', '/')
        if (normalized.isBlank()) return ""
        val segments = normalized.split('/').filter { it.isNotBlank() }
        return if (segments.isEmpty()) "" else "…/" + segments.takeLast(4).joinToString("/")
    }

    private fun sanitize(value: String, limit: Int): String = value
        .replace('\u0000', ' ')
        .replace('\t', ' ')
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
        .take(limit)

    private fun stableId(value: String): String = "audit-" + MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(24)

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val MAX_EVENTS = 500
        private const val MAX_LEGACY = 100
        private const val MAX_PAGE_SIZE = 100
        private const val MAX_DETAILS = 24
        private const val MAX_REASONS = 10
        private val TIME_FORMAT = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    }
}
