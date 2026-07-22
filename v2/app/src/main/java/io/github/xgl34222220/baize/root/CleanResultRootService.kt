package io.github.xgl34222220.baize.root

import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Read-only clean result archive and paging service.
 *
 * The service never discovers or accepts deletion targets. It reads the transaction journal written
 * by [CleanPlanResumeRootService], archives terminal reports, and enriches every result with a stable
 * rule explanation and inferred application ownership for the report UI.
 */
class CleanResultRootService : RootService() {
    private val binder = object : ICleanResultService.Stub() {
        override fun ping(): String {
            pruneReports()
            return JSONObject()
                .put("uid", Process.myUid())
                .put("root", Process.myUid() == 0)
                .put("engine", "clean-result-explain-v2")
                .put("reports", reportRoot().listFiles()?.count { it.isDirectory && summaryFile(it).isFile } ?: 0)
                .put("latest", latestReportId())
                .toString()
        }

        override fun registerPlan(
            planId: String?,
            authorizedCandidates: Int,
            estimatedBytes: Long
        ): String = guarded(planId) { id ->
            pruneReports()
            val directory = reportDirectory(id).apply { mkdirs() }
            val previous = readJson(metaFile(directory))
            val meta = JSONObject()
                .put("version", REPORT_VERSION)
                .put("reportId", id)
                .put("createdAt", previous.optLong("createdAt", System.currentTimeMillis()))
                .put("updatedAt", System.currentTimeMillis())
                .put("authorizedCandidates", authorizedCandidates.coerceAtLeast(0))
                .put("estimatedBytes", estimatedBytes.coerceAtLeast(0L))
            if (!atomicWrite(metaFile(directory), meta.toString())) {
                return@guarded error("result_meta_write_failed", "无法保存清理前统计")
            }
            JSONObject(meta.toString()).put("success", true).toString()
        }

        override fun archive(planId: String?): String = guarded(planId) { id ->
            pruneReports()
            val directory = reportDirectory(id).apply { mkdirs() }
            val transaction = transactionState(id)
            if (transaction == null) {
                val existing = readJson(summaryFile(directory))
                return@guarded if (existing.length() > 0) {
                    JSONObject(existing.toString()).put("success", true).toString()
                } else {
                    error("transaction_missing", "清理事务不存在，无法生成报告")
                }
            }

            val itemStates = transaction.optJSONObject("itemStates") ?: JSONObject()
            val items = resultItems(itemStates)
            val summary = compactSummary(id, transaction, readJson(metaFile(directory)))
                .put("archived", true)
                .put("completedAt", System.currentTimeMillis())
                .put("itemResultCount", items.size)
                .put("explanationSchema", EXPLANATION_SCHEMA)
            val rows = items.joinToString(
                separator = "\n",
                postfix = if (items.isEmpty()) "" else "\n"
            ) { it.toString() }

            if (!atomicWrite(itemsFile(directory), rows)) {
                return@guarded error("result_items_write_failed", "无法保存逐项清理结果")
            }
            if (!atomicWrite(summaryFile(directory), summary.toString())) {
                return@guarded error("result_summary_write_failed", "无法保存清理报告摘要")
            }
            if (!atomicWrite(latestFile(), "$id\n")) {
                return@guarded error("result_latest_write_failed", "清理报告已保存，但最近报告索引写入失败")
            }
            JSONObject(summary.toString()).put("success", true).toString()
        }

        override fun getSummary(reportId: String?): String {
            pruneReports()
            val id = resolveReportId(reportId.orEmpty())
                ?: return error("report_missing", "没有可查看的清理报告")
            val live = transactionState(id)
            val archived = readJson(summaryFile(reportDirectory(id)))
            if (live == null && archived.length() == 0) {
                return error("report_missing", "清理报告不存在或已过期")
            }
            val summary = if (live != null) {
                compactSummary(id, live, readJson(metaFile(reportDirectory(id))))
            } else {
                JSONObject(archived.toString())
            }
            return summary
                .put("success", true)
                .put("live", live != null)
                .put("archived", live == null)
                .put("explanationSchema", EXPLANATION_SCHEMA)
                .toString()
        }

        override fun getPage(
            reportId: String?,
            offset: Int,
            limit: Int,
            filterJson: String?
        ): String {
            pruneReports()
            val id = resolveReportId(reportId.orEmpty())
                ?: return error("report_missing", "没有可查看的清理报告")
            if (transactionState(id) == null && !summaryFile(reportDirectory(id)).isFile) {
                return error("report_missing", "清理报告不存在或已过期")
            }

            val filters = parseJson(filterJson)
            val action = normalizeFilter(filters.optString("action", "all"))
            val category = normalizeFilter(filters.optString("category", "all"))
            val risk = normalizeFilter(filters.optString("risk", "all"))
            val query = filters.optString("query").trim().lowercase().take(160)
            val filtered = loadItems(id)
                .asSequence()
                .filter { action == "all" || normalizeAction(it.optString("action")) == action }
                .filter { category == "all" || normalizeCategory(it.optString("category")) == category }
                .filter { risk == "all" || normalizeRisk(it.optString("risk")) == risk }
                .filter {
                    query.isBlank() ||
                        it.optString("path").lowercase().contains(query) ||
                        it.optString("reason").lowercase().contains(query) ||
                        it.optString("packageName").lowercase().contains(query) ||
                        it.optString("ruleLabel").lowercase().contains(query) ||
                        it.optString("ruleSource").lowercase().contains(query) ||
                        it.optString("matchReason").lowercase().contains(query)
                }
                .sortedWith(
                    compareBy<JSONObject> { actionPriority(normalizeAction(it.optString("action"))) }
                        .thenByDescending { it.optLong("deletedBytes") }
                        .thenBy { it.optString("packageName") }
                        .thenBy { it.optString("path") }
                )
                .toList()

            val start = offset.coerceAtLeast(0).coerceAtMost(filtered.size)
            val pageSize = limit.coerceIn(1, MAX_PAGE_SIZE)
            val end = (start + pageSize).coerceAtMost(filtered.size)
            val page = JSONArray()
            for (index in start until end) page.put(filtered[index])
            return JSONObject()
                .put("success", true)
                .put("reportId", id)
                .put("offset", start)
                .put("limit", pageSize)
                .put("total", filtered.size)
                .put("hasMore", end < filtered.size)
                .put("explanationSchema", EXPLANATION_SCHEMA)
                .put("items", page)
                .toString()
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private inline fun guarded(rawPlanId: String?, block: (String) -> String): String {
        val id = validId(rawPlanId.orEmpty()) ?: return error("invalid_report", "清理报告编号无效")
        return runCatching { block(id) }
            .getOrElse { error("clean_result_failed", it.message ?: it.javaClass.simpleName) }
    }

    private fun compactSummary(id: String, state: JSONObject, meta: JSONObject): JSONObject {
        val summary = JSONObject(state.toString()).apply { remove("itemStates") }
        val authorized = meta.optInt(
            "authorizedCandidates",
            summary.optInt("authorizedCandidates", summary.optInt("scannedCandidates", 0))
        ).coerceAtLeast(0)
        val processed = summary.optInt("processedCandidates", 0).coerceAtLeast(0)
        val remaining = summary.optInt("cacheRemaining", 0).coerceAtLeast(0) +
            summary.optInt("safeRemaining", 0).coerceAtLeast(0)
        val estimated = meta.optLong(
            "estimatedBytes",
            summary.optLong("estimatedBytes", 0L)
        ).coerceAtLeast(0L)
        val deleted = summary.optLong("deletedBytes", 0L).coerceAtLeast(0L)
        return summary
            .put("resultSchema", "clean-report-v1")
            .put("reportId", id)
            .put("authorizedCandidates", authorized)
            .put("processedCandidates", processed)
            .put("remainingCandidates", remaining)
            .put("estimatedBytes", estimated)
            .put("actualDeletedBytes", deleted)
            .put(
                "completionPercent",
                if (authorized > 0) (processed * 100 / authorized).coerceIn(0, 100) else 0
            )
            .put(
                "spaceRecoveryPercent",
                if (estimated > 0L) ((deleted * 100L / estimated).coerceIn(0L, 999L)).toInt() else 0
            )
            .put("updatedAt", summary.optLong("updatedAt", System.currentTimeMillis()))
    }

    private fun loadItems(id: String): List<JSONObject> {
        val live = transactionState(id)?.optJSONObject("itemStates")
        if (live != null) return resultItems(live)
        val file = itemsFile(reportDirectory(id))
        if (!file.isFile) return emptyList()
        return buildList {
            file.useLines { lines ->
                lines.take(MAX_RESULT_ITEMS).forEach { line ->
                    val source = parseJson(line)
                    if (source.length() > 0) add(enrichItem(source))
                }
            }
        }
    }

    private fun resultItems(states: JSONObject): List<JSONObject> {
        val values = ArrayList<JSONObject>()
        val keys = states.keys()
        while (keys.hasNext() && values.size < MAX_RESULT_ITEMS) {
            val source = states.optJSONObject(keys.next()) ?: continue
            val path = source.optString("path")
            if (!path.startsWith("/")) continue
            values += enrichItem(source)
        }
        return values
    }

    private fun enrichItem(source: JSONObject): JSONObject {
        val path = source.optString("path")
        val action = normalizeAction(source.optString("action"))
        val reason = source.optString("reason").ifBlank { defaultReason(action) }
        val category = normalizeCategory(source.optString("category"))
        val risk = normalizeRisk(source.optString("risk"))
        val deletedBytes = source.optLong("deletedBytes", 0L).coerceAtLeast(0L)
        val estimatedBytes = source.optLong(
            "estimatedBytes",
            if (action == "cleaned") deletedBytes else 0L
        ).coerceAtLeast(0L)
        val packageName = source.optString("packageName").trim()
            .takeIf(::validPackageName)
            ?: inferPackageName(path)
        val explanation = explainRule(source, category, path)
        val canProtectPath = protectablePath(path)
        return JSONObject(source.toString())
            .put("action", action)
            .put("reason", reason)
            .put("reasonCode", reasonCode(action, reason))
            .put("category", category)
            .put("risk", risk)
            .put("packageName", packageName)
            .put("ruleId", explanation.id)
            .put("ruleLabel", explanation.label)
            .put("ruleSource", explanation.source)
            .put("matchReason", explanation.matchReason)
            .put("estimatedBytes", estimatedBytes)
            .put("deletedBytes", deletedBytes)
            .put("remainingEstimatedBytes", (estimatedBytes - deletedBytes).coerceAtLeast(0L))
            .put("canProtectPackage", packageName.isNotBlank())
            .put("canProtectPath", canProtectPath)
            .put("protectionTarget", if (packageName.isNotBlank()) "package" else if (canProtectPath) "path" else "none")
            .put(
                "protectionHint",
                when {
                    packageName.isNotBlank() -> "可保护整个应用，下一次扫描会跳过该应用相关候选"
                    canProtectPath -> "可保护此路径及其子项，下一次扫描会跳过"
                    else -> "此系统路径已经处于固定安全策略内"
                }
            )
    }

    private fun explainRule(source: JSONObject, category: String, path: String): RuleExplanation {
        val lower = path.lowercase()
        return when (category) {
            "cache" -> {
                val kind = when {
                    lower.contains("/code_cache") -> "代码缓存"
                    lower.contains("/external_cache") -> "外部缓存"
                    else -> "应用缓存"
                }
                RuleExplanation(
                    id = "cache-directory",
                    label = kind,
                    source = "内置缓存规则",
                    matchReason = "目标位于应用缓存目录，扫描快照记录了其中可安全删除的缓存文件。"
                )
            }
            "empty" -> RuleExplanation(
                id = "empty-item",
                label = "空文件或空目录",
                source = "空项目规则",
                matchReason = "扫描时文件大小为 0，或目录没有任何子项；清理前会再次确认仍为空。"
            )
            "fragment" -> fragmentExplanation(lower)
            "rules" -> rulePathExplanation(lower, source.optString("ruleLabel"))
            else -> when {
                lower.contains("/android/data/") || lower.contains("/android/obb/") -> RuleExplanation(
                    id = "android-app-storage",
                    label = "Android 应用存储",
                    source = "应用路径识别",
                    matchReason = "路径属于 Android 应用专属目录，应用包名由标准目录结构解析。"
                )
                else -> RuleExplanation(
                    id = "generic-clean-candidate",
                    label = "清理候选",
                    source = "扫描快照",
                    matchReason = "该路径在扫描时满足对应清理条件，并被固化进不可变清理计划。"
                )
            }
        }
    }

    private fun fragmentExplanation(lowerPath: String): RuleExplanation {
        val name = lowerPath.substringAfterLast('/')
        return when {
            name.endsWith(".tmp") || name.endsWith(".temp") -> RuleExplanation(
                "fragment-temp", "过期临时文件", "碎片文件规则", "文件名使用临时扩展名，并且修改时间超过设置的保留天数。"
            )
            name.endsWith(".part") || name.endsWith(".partial") || name.endsWith(".download") || name.endsWith(".crdownload") -> RuleExplanation(
                "fragment-download", "中断下载碎片", "碎片文件规则", "文件名符合未完成下载格式，并且已经超过保留期限。"
            )
            name.contains("tombstone") || name.contains("minidump") || name.contains("heapdump") ||
                name.contains("crash") || name.contains("trace") || name.contains("dump") -> RuleExplanation(
                "fragment-crash", "崩溃与转储碎片", "崩溃文件规则", "文件名表明它是崩溃、跟踪或内存转储产物，并且已经过期。"
            )
            else -> RuleExplanation(
                "fragment-aged", "过期残留碎片", "碎片文件规则", "文件名符合碎片模式，且最后修改时间早于当前保留期限。"
            )
        }
    }

    private fun rulePathExplanation(lowerPath: String, existingLabel: String): RuleExplanation {
        val known = when {
            lowerPath == "/data/anr" || lowerPath.startsWith("/data/anr/") -> Triple("system-anr", "系统 ANR", "系统诊断规则")
            lowerPath == "/data/tombstones" || lowerPath.startsWith("/data/tombstones/") -> Triple("system-tombstone", "Tombstone", "系统诊断规则")
            lowerPath == "/data/system/dropbox" || lowerPath.startsWith("/data/system/dropbox/") -> Triple("system-dropbox", "系统 Dropbox", "系统诊断规则")
            lowerPath == "/data/system/heapdump" || lowerPath.startsWith("/data/system/heapdump/") -> Triple("system-heapdump", "系统 Heapdump", "系统诊断规则")
            lowerPath == "/data/misc/logd" || lowerPath.startsWith("/data/misc/logd/") -> Triple("system-logd", "系统日志", "系统日志规则")
            lowerPath == "/data/vendor/log" || lowerPath.startsWith("/data/vendor/log/") -> Triple("vendor-log", "厂商日志", "厂商日志规则")
            lowerPath == "/data/log" || lowerPath.startsWith("/data/log/") -> Triple("system-log", "系统日志", "系统日志规则")
            lowerPath.substringAfterLast('/') in HIDDEN_TRASH_NAMES -> Triple("hidden-trash", "隐藏垃圾目录", "共享存储启发式规则")
            else -> null
        }
        if (known != null) {
            return RuleExplanation(
                known.first,
                known.second,
                known.third,
                "目标路径命中 ${known.second} 的受控清理范围；删除前仍会检查白名单、挂载点和符号链接。"
            )
        }
        val label = existingLabel.takeIf { it.isNotBlank() } ?: "扩展路径规则"
        return RuleExplanation(
            "extended-path-rule",
            label,
            "模块扩展规则",
            "目标路径命中模块内置或用户扩展的绝对路径规则；报告只解释命中来源，不会重新执行规则发现。"
        )
    }

    private fun reasonCode(action: String, reason: String): String {
        val value = reason.lowercase()
        return when {
            value.contains("白名单") -> "whitelist"
            value.contains("安全边界") || value.contains("路径格式") -> "safety_boundary"
            value.contains("不存在") -> "missing"
            value.contains("符号链接") -> "symlink"
            value.contains("挂载点") -> "mount_point"
            value.contains("风险") -> "risk_policy"
            value.contains("发生变化") || value.contains("不再") -> "changed_after_scan"
            action == "cleaned" -> "deleted"
            action == "partial" -> "partial_delete"
            action == "failed" -> "delete_failed"
            action == "protected" -> "protected"
            else -> "state_changed"
        }
    }

    private fun inferPackageName(path: String): String {
        for (pattern in PACKAGE_PATH_PATTERNS) {
            val value = pattern.find(path)?.groupValues?.getOrNull(1).orEmpty()
            if (validPackageName(value)) return value
        }
        return ""
    }

    private fun validPackageName(value: String): Boolean = PACKAGE_NAME.matches(value)

    private fun protectablePath(path: String): Boolean {
        val normalized = path.trimEnd('/').ifBlank { "/" }
        if (normalized.length !in 2..4096 || normalized.contains('\u0000')) return false
        if (HARD_EXACT.contains(normalized)) return false
        if (READ_ONLY.any { normalized == it || normalized.startsWith("$it/") }) return false
        if (normalized == "/data/adb" || normalized.startsWith("/data/adb/")) return false
        return MUTABLE_ROOTS.any { normalized == it || normalized.startsWith("$it/") }
    }

    private fun transactionState(id: String): JSONObject? {
        val file = File(transactionRoot(), "$id/state.json")
        if (!file.isFile) return null
        return readJson(file).takeIf { it.optString("planId") == id }
    }

    private fun resolveReportId(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank() || value == "latest") return latestReportId().takeIf { it.isNotBlank() }
        return validId(value)?.takeIf { reportExists(it) }
    }

    private fun latestReportId(): String {
        val indexed = latestFile().takeIf { it.isFile }?.readText()?.trim().orEmpty()
        if (validId(indexed) != null && reportExists(indexed)) return indexed
        return reportRoot().listFiles()
            ?.filter { it.isDirectory && summaryFile(it).isFile }
            ?.maxByOrNull { summaryFile(it).lastModified() }
            ?.name
            .orEmpty()
    }

    private fun reportExists(id: String): Boolean =
        transactionState(id) != null || summaryFile(reportDirectory(id)).isFile

    private fun reportRoot(): File = File(RootPaths.STATE_DIR, "clean-result-reports").apply { mkdirs() }
    private fun transactionRoot(): File = File(RootPaths.STATE_DIR, "clean-plan-transactions")
    private fun reportDirectory(id: String): File = File(reportRoot(), id)
    private fun metaFile(directory: File): File = File(directory, "meta.json")
    private fun summaryFile(directory: File): File = File(directory, "summary.json")
    private fun itemsFile(directory: File): File = File(directory, "items.ndjson")
    private fun latestFile(): File = File(reportRoot(), "latest.txt")

    private fun pruneReports() {
        val now = System.currentTimeMillis()
        val completed = reportRoot().listFiles()
            ?.filter { it.isDirectory && summaryFile(it).isFile }
            ?.sortedByDescending { summaryFile(it).lastModified() }
            .orEmpty()
        completed.forEachIndexed { index, directory ->
            val updated = summaryFile(directory).lastModified().takeIf { it > 0L } ?: directory.lastModified()
            if (index >= MAX_REPORTS || updated <= 0L || now - updated > REPORT_TTL_MS) {
                deleteTree(directory)
            }
        }
        val latest = latestFile().takeIf { it.isFile }?.readText()?.trim().orEmpty()
        if (latest.isNotBlank() && !reportExists(latest)) latestFile().delete()
    }

    private fun readJson(file: File): JSONObject {
        if (!file.isFile) return JSONObject()
        return runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    }

    private fun parseJson(raw: String?): JSONObject =
        runCatching { JSONObject(raw.orEmpty()) }.getOrDefault(JSONObject())

    private fun atomicWrite(target: File, content: String): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        restrict(target)
        true
    }.getOrDefault(false)

    private fun restrict(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    private fun deleteTree(file: File): Boolean = runCatching {
        if (!file.exists()) return@runCatching true
        file.walkBottomUp().forEach { it.delete() }
        !file.exists()
    }.getOrDefault(false)

    private fun normalizeAction(raw: String): String = when (raw.trim().lowercase()) {
        "cleaned", "deleted", "success" -> "cleaned"
        "protected" -> "protected"
        "partial" -> "partial"
        "failed", "error" -> "failed"
        else -> "changed"
    }

    private fun normalizeCategory(raw: String): String = when (raw.trim().lowercase()) {
        "cache", "empty", "rules", "fragment", "other" -> raw.trim().lowercase()
        else -> "other"
    }

    private fun normalizeRisk(raw: String): String = when (raw.trim().lowercase()) {
        "medium", "high", "critical" -> raw.trim().lowercase()
        else -> "low"
    }

    private fun normalizeFilter(raw: String): String = raw.trim().lowercase().ifBlank { "all" }

    private fun defaultReason(action: String): String = when (action) {
        "cleaned" -> "已通过快照校验并完成删除"
        "protected" -> "白名单、安全边界或系统保护阻止了删除"
        "partial" -> "部分内容已删除，剩余内容保留在断点计划中"
        "failed" -> "删除失败，项目保留以便继续处理"
        else -> "扫描后目标已不存在或文件状态发生变化"
    }

    private fun actionPriority(action: String): Int = when (action) {
        "failed" -> 0
        "partial" -> 1
        "protected" -> 2
        "changed" -> 3
        else -> 4
    }

    private fun validId(raw: String): String? =
        runCatching { UUID.fromString(raw).toString() }.getOrNull()

    private fun error(code: String, message: String): String = JSONObject()
        .put("error", code)
        .put("message", message)
        .toString()

    private data class RuleExplanation(
        val id: String,
        val label: String,
        val source: String,
        val matchReason: String
    )

    companion object {
        private const val REPORT_VERSION = 2
        private const val EXPLANATION_SCHEMA = "rule-explanation-v1"
        private const val REPORT_TTL_MS = 30L * 24L * 60L * 60_000L
        private const val MAX_REPORTS = 50
        private const val MAX_RESULT_ITEMS = 20_000
        private const val MAX_PAGE_SIZE = 100

        private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
        private val PACKAGE_PATH_PATTERNS = listOf(
            Regex("^/data/user/[0-9]+/([A-Za-z0-9_.]+)(?:/|$)"),
            Regex("^/data/data/([A-Za-z0-9_.]+)(?:/|$)"),
            Regex("^/storage/emulated/[0-9]+/Android/(?:data|obb|media)/([A-Za-z0-9_.]+)(?:/|$)"),
            Regex("^/sdcard/Android/(?:data|obb|media)/([A-Za-z0-9_.]+)(?:/|$)")
        )
        private val HIDDEN_TRASH_NAMES = setOf(".cache", ".thumbnails", ".tmp", ".temp", ".logs", ".debug")
        private val HARD_EXACT = setOf(
            "/", "/data", "/data/adb", "/data/system", "/data/misc", "/storage", "/storage/emulated", "/sdcard"
        )
        private val READ_ONLY = setOf(
            "/system", "/vendor", "/product", "/odm", "/apex", "/proc", "/sys", "/dev", "/metadata"
        )
        private val MUTABLE_ROOTS = setOf(
            "/data/user", "/data/data", "/data/anr", "/data/tombstones", "/data/system/dropbox",
            "/data/system/heapdump", "/data/misc/logd", "/data/vendor/log", "/data/log",
            "/storage/emulated", "/sdcard"
        )
    }
}
