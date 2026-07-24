package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * Builds conceptual, human-review-only rule improvement drafts from bounded path-free reports.
 *
 * The analyzer never reads rule text or rule files, never creates an executable patch, and never
 * changes review state, cleaner rules, files, policy, tasks, snapshots, or scheduler configuration.
 */
internal class RuleImprovementDraftAnalyzer {
    fun analyze(ruleQuality: JSONObject, trends: JSONObject): JSONObject {
        val trendItems = parseTrendItems(trends)
        val queue = ruleQuality.optJSONArray("reviewQueue") ?: JSONArray()
        val drafts = mutableListOf<RuleImprovementDraft>()

        for (index in 0 until queue.length().coerceAtMost(MAX_QUEUE_ITEMS)) {
            val item = queue.optJSONObject(index) ?: continue
            val category = sanitize(item.optString("category"), MAX_CATEGORY_LENGTH)
            if (category.isBlank()) continue
            propose(item, trendItems[category])?.let(drafts::add)
        }

        val sorted = drafts
            .sortedWith(
                compareByDescending<RuleImprovementDraft> { priorityRank(it.priority) }
                    .thenByDescending { it.activeReopened }
                    .thenByDescending { it.repeated }
                    .thenByDescending { it.failureRate }
                    .thenByDescending { it.protectionRate }
                    .thenByDescending { it.events }
            )
            .take(MAX_DRAFTS)

        val highPriorityCount = sorted.count { it.priority == "high" }
        val pendingReviewCount = sorted.count { it.reviewState == "pending" }
        val repeatedCount = sorted.count { it.repeated }
        val disableCount = sorted.count { it.action == "consider_disable" }
        val narrowCount = sorted.count { it.action == "narrow_scope" }
        val protectionCount = sorted.count { it.action == "strengthen_protection" }
        val observeCount = sorted.count { it.action == "observe" }

        return JSONObject()
            .put("available", sorted.isNotEmpty())
            .put("readOnly", true)
            .put("manualOnly", true)
            .put("conceptualPreview", true)
            .put("exactPatchIncluded", false)
            .put("ruleTextRead", false)
            .put("ruleFilesRead", false)
            .put("automaticActions", false)
            .put("reviewMetadataChanged", false)
            .put("rulesChanged", false)
            .put("policyUntouched", true)
            .put("tasksUntouched", true)
            .put("snapshotsUntouched", true)
            .put("scheduleUntouched", true)
            .put("pathsIncluded", false)
            .put("sourceRuleQueueLimit", MAX_QUEUE_ITEMS)
            .put("sourceTrendItemLimit", MAX_TREND_ITEMS)
            .put("draftLimit", MAX_DRAFTS)
            .put("lookbackDays", ruleQuality.optInt("lookbackDays", 45).coerceIn(1, 365))
            .put("draftCount", sorted.size)
            .put("highPriorityCount", highPriorityCount)
            .put("pendingReviewCount", pendingReviewCount)
            .put("repeatedCount", repeatedCount)
            .put("considerDisableCount", disableCount)
            .put("narrowScopeCount", narrowCount)
            .put("strengthenProtectionCount", protectionCount)
            .put("observeCount", observeCount)
            .put("summary", summary(sorted.size, highPriorityCount, pendingReviewCount, repeatedCount))
            .put("drafts", JSONArray(sorted.map(RuleImprovementDraft::toJson)))
    }

    private fun propose(item: JSONObject, trend: TrendItem?): RuleImprovementDraft? {
        val category = sanitize(item.optString("category"), MAX_CATEGORY_LENGTH)
        val subjectKey = item.optString("subjectKey").takeIf(KEY_PATTERN::matches)
            ?: stableKey(category)
        val type = token(item.optString("type"), 40)
        val severity = token(item.optString("severity"), 20)
        val risk = token(item.optString("risk"), 40)
        val recommendation = token(item.optString("recommendation"), 40)
        val reviewState = token(item.optString("reviewState", "pending"), 20).ifBlank { "pending" }
        val events = item.optInt("events").coerceAtLeast(0)
        val observations = item.optInt("observations").coerceAtLeast(0)
        val processed = item.optInt("processed").coerceAtLeast(0)
        val failureRate = item.optInt("failureRate").coerceIn(0, 100)
        val protectionRate = item.optInt("protectionRate").coerceIn(0, 100)
        val averageBytes = item.optLong("averageBytes").coerceAtLeast(0L)
        val activeReopened = item.optBoolean("reopened", false) || trend?.activeReopened == true
        val reopenCount = maxOf(if (item.optBoolean("reopened", false)) 1 else 0, trend?.reopenCount ?: 0)
        val repeated = trend?.repeated == true || reopenCount >= REPEATED_REOPEN_THRESHOLD
        val reason = sanitize(
            trend?.lastReason.orEmpty().ifBlank { item.optString("reopenReason") },
            MAX_REASON_LENGTH
        )

        val action = when {
            type == "high_failure" && (failureRate >= SEVERE_FAILURE_RATE || repeated || activeReopened) -> "consider_disable"
            type == "zero_hit" && (events >= LONG_ZERO_HIT_EVENTS || repeated) -> "consider_disable"
            type == "frequently_protected" && (
                risk in HIGH_RISKS ||
                    reason.contains("风险") ||
                    reason.contains("严重")
                ) -> "strengthen_protection"
            type == "frequently_protected" || protectionRate >= NARROW_SCOPE_PROTECTION_RATE -> "narrow_scope"
            type == "high_failure" -> "narrow_scope"
            type == "low_value" || type == "zero_hit" -> "observe"
            recommendation == "narrow_scope" -> "narrow_scope"
            recommendation == "consider_disable" && repeated -> "consider_disable"
            else -> null
        } ?: return null

        val priority = when {
            action == "consider_disable" && (repeated || activeReopened || failureRate >= SEVERE_FAILURE_RATE) -> "high"
            action == "strengthen_protection" -> "high"
            action == "narrow_scope" && (severity == "high" || repeated || activeReopened) -> "high"
            action == "observe" -> "low"
            else -> "medium"
        }

        val evidence = buildEvidence(
            events = events,
            observations = observations,
            processed = processed,
            failureRate = failureRate,
            protectionRate = protectionRate,
            averageBytes = averageBytes,
            reopenCount = reopenCount,
            reason = reason
        )
        val preview = preview(action, risk)
        val checklist = checklist(action)

        return RuleImprovementDraft(
            key = stableKey("$subjectKey\u0000$action"),
            subjectKey = subjectKey,
            category = category,
            action = action,
            priority = priority,
            title = title(action),
            rationale = rationale(action, type, repeated, activeReopened),
            impact = impact(action),
            caution = caution(action),
            reviewState = reviewState,
            type = type,
            severity = severity,
            risk = risk,
            events = events,
            observations = observations,
            processed = processed,
            failureRate = failureRate,
            protectionRate = protectionRate,
            averageBytes = averageBytes,
            reopenCount = reopenCount,
            repeated = repeated,
            activeReopened = activeReopened,
            lastReason = reason,
            evidence = evidence,
            preview = preview,
            checklist = checklist
        )
    }

    private fun buildEvidence(
        events: Int,
        observations: Int,
        processed: Int,
        failureRate: Int,
        protectionRate: Int,
        averageBytes: Long,
        reopenCount: Int,
        reason: String
    ): List<String> = buildList {
        add("近期开启规则的任务 $events 次，累计明细 $observations 条")
        if (failureRate > 0) add("任务异常率为 $failureRate%")
        if (protectionRate > 0) add("安全机制保护率为 $protectionRate%")
        if (processed > 0) add("已处理 $processed 条，平均单条收益 ${formatBytes(averageBytes)}")
        if (reopenCount > 0) add("审核自动重新打开 $reopenCount 次")
        if (reason.isNotBlank()) add("最近恶化原因：$reason")
    }.take(MAX_EVIDENCE_ITEMS)

    private fun preview(action: String, risk: String): List<DraftPreviewLine> = when (action) {
        "consider_disable" -> listOf(
            DraftPreviewLine("启用状态", "继续参与规则评估", "人工核对后再决定是否停用"),
            DraftPreviewLine("安全边界", "沿用当前保护机制", "停用前保留原规则备份与恢复入口"),
            DraftPreviewLine("验证方式", "依赖现有历史表现", "停用前后各执行一次只读扫描对比")
        )
        "strengthen_protection" -> listOf(
            DraftPreviewLine("风险门槛", riskLabel(risk), "提高到更严格的人工确认或隔离门槛"),
            DraftPreviewLine("高风险动作", "沿用当前保护", "优先隔离或审计，禁止直接删除"),
            DraftPreviewLine("匹配例外", "使用现有保护项", "补充易误判分类的排除与白名单条件")
        )
        "narrow_scope" -> listOf(
            DraftPreviewLine("匹配范围", "保持当前分类范围", "限定到重复命中且收益明确的子范围"),
            DraftPreviewLine("排除条件", "沿用现有排除项", "增加受保护、系统关键和低置信度排除条件"),
            DraftPreviewLine("清理动作", "保持当前动作", "范围外项目只审计，不进入清理候选")
        )
        else -> listOf(
            DraftPreviewLine("规则状态", "保持当前配置", "继续观察，不立即修改"),
            DraftPreviewLine("观察指标", "依赖单次结果", "累计更多任务、异常率、保护率与收益样本"),
            DraftPreviewLine("复核条件", "人工不定期检查", "达到重复异常或重开阈值后再次审核")
        )
    }

    private fun checklist(action: String): List<String> = when (action) {
        "consider_disable" -> listOf("核对最近异常或零命中的来源", "确认停用不会覆盖唯一清理能力", "人工备份规则后再修改", "修改后执行只读扫描验证")
        "strengthen_protection" -> listOf("确认高风险分类与关键目录保护", "优先采用隔离而非直接删除", "补充排除条件或白名单", "用同一场景复测误判率")
        "narrow_scope" -> listOf("定位重复误判或高保护分类", "缩小匹配范围并保留安全排除", "不要扩大目录或通配符范围", "修改后比较命中与收益变化")
        else -> listOf("继续收集至少三次任务样本", "记录异常率、保护率和平均收益", "样本明显恶化时重新审核")
    }

    private fun parseTrendItems(trends: JSONObject): Map<String, TrendItem> {
        val items = trends.optJSONArray("items") ?: JSONArray()
        return buildMap {
            for (index in 0 until items.length().coerceAtMost(MAX_TREND_ITEMS)) {
                val item = items.optJSONObject(index) ?: continue
                val category = sanitize(item.optString("category"), MAX_CATEGORY_LENGTH)
                if (category.isBlank()) continue
                put(
                    category,
                    TrendItem(
                        reopenCount = item.optInt("reopenCount").coerceAtLeast(0),
                        activeReopened = item.optBoolean("activeReopened", false),
                        repeated = item.optBoolean("repeated", false),
                        lastReason = sanitize(item.optString("lastReason"), MAX_REASON_LENGTH)
                    )
                )
            }
        }
    }

    private fun title(action: String): String = when (action) {
        "consider_disable" -> "考虑停用草案"
        "strengthen_protection" -> "增强保护草案"
        "narrow_scope" -> "缩小范围草案"
        else -> "继续观察草案"
    }

    private fun rationale(action: String, type: String, repeated: Boolean, activeReopened: Boolean): String = when (action) {
        "consider_disable" -> if (repeated || activeReopened) {
            "该规则在人工处理后仍再次恶化，建议先核对覆盖能力，再人工评估是否停用。"
        } else if (type == "zero_hit") {
            "该规则长期没有产生有效命中，建议人工评估保留价值。"
        } else {
            "异常率较高，建议人工确认故障来源和替代能力后再决定是否停用。"
        }
        "strengthen_protection" -> "该规则涉及较高风险或保护机制频繁介入，建议提高确认、隔离和排除条件。"
        "narrow_scope" -> "当前匹配范围可能过宽，建议只保留收益明确且误判较低的子范围。"
        else -> "当前证据不足以支持修改，建议继续收集稳定样本后再决定。"
    }

    private fun impact(action: String): String = when (action) {
        "consider_disable" -> "可能减少异常或无效扫描，但需确认不会失去唯一清理覆盖。"
        "strengthen_protection" -> "可能降低误删风险，但高风险项目会更多进入隔离或人工确认。"
        "narrow_scope" -> "可能减少误判与保护拦截，同时可能降低可清理数量。"
        else -> "不会立即改变清理结果，只增加观察时间。"
    }

    private fun caution(action: String): String = when (action) {
        "consider_disable" -> "这里只建议人工评估，不能直接停用规则。"
        "strengthen_protection" -> "不要通过扩大白名单或降低风险等级来绕过保护。"
        "narrow_scope" -> "预览是概念差异，不包含真实规则文本或可执行补丁。"
        else -> "观察期间保持现有规则与安全策略不变。"
    }

    private fun summary(total: Int, high: Int, pending: Int, repeated: Int): String = when {
        total == 0 -> "暂无足够证据生成规则改进草案"
        high > 0 -> "生成 $total 份人工草案，其中 $high 份需要优先核对"
        repeated > 0 -> "生成 $total 份草案，包含 $repeated 项反复恶化规则"
        pending > 0 -> "生成 $total 份草案，$pending 项仍处于待审核状态"
        else -> "已生成 $total 份人工规则改进草案"
    }

    private fun priorityRank(value: String): Int = when (value) {
        "high" -> 3
        "medium" -> 2
        else -> 1
    }

    private fun riskLabel(value: String): String = when (value) {
        "critical" -> "关键风险"
        "high" -> "高风险"
        "medium" -> "中风险"
        "low" -> "低风险"
        else -> "未标注风险"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L * 1024L)} GiB"
        bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MiB"
        bytes >= 1024L -> "${bytes / 1024L} KiB"
        else -> "$bytes B"
    }

    private fun sanitize(raw: String, limit: Int): String = raw
        .replace(Regex("[\\p{Cntrl}]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(limit)

    private fun token(raw: String, limit: Int): String = raw
        .replace(Regex("[^a-zA-Z0-9_-]"), "")
        .lowercase(Locale.ROOT)
        .take(limit)

    private fun stableKey(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private data class TrendItem(
        val reopenCount: Int,
        val activeReopened: Boolean,
        val repeated: Boolean,
        val lastReason: String
    )

    private data class DraftPreviewLine(val dimension: String, val before: String, val after: String) {
        fun toJson(): JSONObject = JSONObject()
            .put("dimension", dimension)
            .put("before", before)
            .put("after", after)
    }

    private data class RuleImprovementDraft(
        val key: String,
        val subjectKey: String,
        val category: String,
        val action: String,
        val priority: String,
        val title: String,
        val rationale: String,
        val impact: String,
        val caution: String,
        val reviewState: String,
        val type: String,
        val severity: String,
        val risk: String,
        val events: Int,
        val observations: Int,
        val processed: Int,
        val failureRate: Int,
        val protectionRate: Int,
        val averageBytes: Long,
        val reopenCount: Int,
        val repeated: Boolean,
        val activeReopened: Boolean,
        val lastReason: String,
        val evidence: List<String>,
        val preview: List<DraftPreviewLine>,
        val checklist: List<String>
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("key", key)
            .put("subjectKey", subjectKey)
            .put("category", category)
            .put("action", action)
            .put("priority", priority)
            .put("title", title)
            .put("rationale", rationale)
            .put("impact", impact)
            .put("caution", caution)
            .put("reviewState", reviewState)
            .put("type", type)
            .put("severity", severity)
            .put("risk", risk)
            .put("events", events)
            .put("observations", observations)
            .put("processed", processed)
            .put("failureRate", failureRate)
            .put("protectionRate", protectionRate)
            .put("averageBytes", averageBytes)
            .put("reopenCount", reopenCount)
            .put("repeated", repeated)
            .put("activeReopened", activeReopened)
            .put("lastReason", lastReason)
            .put("evidence", JSONArray(evidence))
            .put("preview", JSONArray(preview.map(DraftPreviewLine::toJson)))
            .put("checklist", JSONArray(checklist))
    }

    companion object {
        private const val MAX_QUEUE_ITEMS = 40
        private const val MAX_TREND_ITEMS = 20
        private const val MAX_DRAFTS = 20
        private const val MAX_CATEGORY_LENGTH = 100
        private const val MAX_REASON_LENGTH = 240
        private const val MAX_EVIDENCE_ITEMS = 6
        private const val REPEATED_REOPEN_THRESHOLD = 2
        private const val SEVERE_FAILURE_RATE = 60
        private const val NARROW_SCOPE_PROTECTION_RATE = 50
        private const val LONG_ZERO_HIT_EVENTS = 6
        private val KEY_PATTERN = Regex("^[0-9a-f]{16}$")
        private val HIGH_RISKS = setOf("critical", "high")
    }
}
