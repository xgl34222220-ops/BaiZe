from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: {label}: expected one match, found {count}")
    file.write_text(text.replace(old, new, 1))
    print(f"patched {label}")


AUDIT = 'v2/app/src/main/java/io/github/xgl34222220/baize/root/AuditRepository.kt'
ACTIVITY = 'v2/app/src/main/java/io/github/xgl34222220/baize/RuleQualityActivity.kt'
CLOSED_LOOP = 'v2/tests/test-rule-review-closed-loop-contract.sh'
WORKFLOW = '.github/workflows/v2.5-concurrent-scheduler-ci.yml'

replace_once(
    AUDIT,
    '''        val advisor = policyAdvisor.evaluate(combined)
        val effectiveness = effectivenessAnalyzer.analyze(combined)
        val ruleQuality = ruleQualityAnalyzer.analyze(combined, ruleQualityReviewRepository.read())
''',
    '''        val advisor = policyAdvisor.evaluate(combined)
        val effectiveness = effectivenessAnalyzer.analyze(combined)
        val preliminaryRuleQuality = ruleQualityAnalyzer.analyze(combined, ruleQualityReviewRepository.read())
        val reconciliation = ruleQualityReviewRepository.reconcile(preliminaryRuleQuality)
        recordAutomaticReopens(reconciliation.reopened)
        val ruleQuality = if (reconciliation.changed) {
            ruleQualityAnalyzer.analyze(combined, reconciliation.reviews)
        } else {
            preliminaryRuleQuality
        }
''',
    'timeline review reconciliation',
)

old_update = '''    @Synchronized
    fun updateRuleQualityReviewJson(ruleKey: String?, action: String?, note: String?): String {
        val clearEpoch = readClearEpoch()
        val combined = (readAuditEvents(clearEpoch) + readLegacyEvents(clearEpoch))
            .distinctBy { it.optString("id") }
            .sortedByDescending { it.optLong("timeEpoch") }
            .take(MAX_EVENTS)
        val report = ruleQualityAnalyzer.analyze(combined, ruleQualityReviewRepository.read())
        val queue = report.optJSONArray("reviewQueue") ?: JSONArray()
        val validKeys = linkedSetOf<String>()
        val categories = linkedMapOf<String, String>()
        for (index in 0 until queue.length()) {
            val item = queue.optJSONObject(index) ?: continue
            val key = item.optString("key")
            if (key.isBlank()) continue
            validKeys += key
            categories[key] = sanitize(item.optString("category"), 100)
        }
        val raw = ruleQualityReviewRepository.update(ruleKey, action, note, validKeys)
        val result = runCatching { JSONObject(raw) }.getOrElse { return raw }
        if (result.optBoolean("success", false)) {
            val key = result.optString("ruleKey")
            val category = categories[key].orEmpty().ifBlank { "未命名分类" }
            val state = result.optString("state", "pending")
            val stateLabel = when (state) {
                "kept" -> "已保留"
                "observing" -> "观察中"
                "ignored" -> "已忽略"
                else -> "待审核"
            }
            val auditResult = JSONObject(raw)
                .put("message", "规则审核已更新：$category · $stateLabel")
                .put("profile", category)
            recordResult("rule-review-$state", "app-rule-review", auditResult.toString())
        }
        return raw
    }

'''
new_update = '''    @Synchronized
    fun updateRuleQualityReviewJson(ruleKey: String?, action: String?, note: String?): String {
        val clearEpoch = readClearEpoch()
        val combined = (readAuditEvents(clearEpoch) + readLegacyEvents(clearEpoch))
            .distinctBy { it.optString("id") }
            .sortedByDescending { it.optLong("timeEpoch") }
            .take(MAX_EVENTS)
        val preliminary = ruleQualityAnalyzer.analyze(combined, ruleQualityReviewRepository.read())
        val reconciliation = ruleQualityReviewRepository.reconcile(preliminary)
        recordAutomaticReopens(reconciliation.reopened)
        val report = if (reconciliation.changed) {
            ruleQualityAnalyzer.analyze(combined, reconciliation.reviews)
        } else {
            preliminary
        }
        val queue = report.optJSONArray("reviewQueue") ?: JSONArray()
        val categories = linkedMapOf<String, String>()
        for (index in 0 until queue.length()) {
            val item = queue.optJSONObject(index) ?: continue
            val key = item.optString("key")
            if (key.isBlank()) continue
            categories[key] = sanitize(item.optString("category"), 100)
        }
        val raw = ruleQualityReviewRepository.update(ruleKey, action, note, report)
        val result = runCatching { JSONObject(raw) }.getOrElse { return raw }
        if (result.optBoolean("success", false)) {
            val key = result.optString("ruleKey")
            val category = categories[key].orEmpty().ifBlank { "未命名分类" }
            val state = result.optString("state", "pending")
            val stateLabel = when (state) {
                "kept" -> "已保留"
                "observing" -> "观察中"
                "ignored" -> "已忽略"
                else -> "待审核"
            }
            val auditResult = JSONObject(raw)
                .put("message", "规则审核已更新：$category · $stateLabel")
                .put("profile", category)
            recordResult("rule-review-$state", "app-rule-review", auditResult.toString())
        }
        return raw
    }

    private fun recordAutomaticReopens(items: List<RuleQualityReopen>) {
        items.forEach { item ->
            val details = JSONArray().put(
                JSONObject()
                    .put("action", "reopened")
                    .put("category", item.category)
                    .put("reason", item.reason)
            )
            val result = JSONObject()
                .put("success", true)
                .put("message", "规则审核已自动重新打开：${item.category} · ${item.reason}")
                .put("profile", item.category)
                .put("processed", 1)
                .put("details", details)
                .put("rulesChanged", false)
                .put("policyUntouched", true)
                .put("scheduleUntouched", true)
            recordResult("rule-review-reopened", "system-rule-review", result.toString(), item.reopenedAt)
        }
    }

'''
replace_once(AUDIT, old_update, new_update, 'audit review update and reopen events')

replace_once(
    ACTIVITY,
    '''                        reviewState = item.optString("reviewState", "pending"),
                        reviewNote = item.optString("reviewNote"),
                        reviewedAt = item.optLong("reviewedAt").coerceAtLeast(0L)
''',
    '''                        reviewState = item.optString("reviewState", "pending"),
                        reviewNote = item.optString("reviewNote"),
                        reviewedAt = item.optLong("reviewedAt").coerceAtLeast(0L),
                        newEventsSinceReview = item.optInt("newEventsSinceReview").coerceAtLeast(0),
                        newObservationsSinceReview = item.optInt("newObservationsSinceReview").coerceAtLeast(0),
                        reopened = item.optBoolean("reopened", false),
                        reopenedAt = item.optLong("reopenedAt").coerceAtLeast(0L),
                        reopenReason = item.optString("reopenReason"),
                        previousReviewState = item.optString("previousReviewState")
''',
    'activity item parsing',
)

replace_once(
    ACTIVITY,
    '''            pendingCount = json.optInt("pendingCount").coerceAtLeast(0),
            observingCount = json.optInt("observingCount").coerceAtLeast(0),
''',
    '''            pendingCount = json.optInt("pendingCount").coerceAtLeast(0),
            reopenedCount = json.optInt("reopenedCount").coerceAtLeast(0),
            observingCount = json.optInt("observingCount").coerceAtLeast(0),
''',
    'activity report parsing',
)

replace_once(
    ACTIVITY,
    '''    val pendingCount: Int = 0,
    val observingCount: Int = 0,
''',
    '''    val pendingCount: Int = 0,
    val reopenedCount: Int = 0,
    val observingCount: Int = 0,
''',
    'activity report model',
)

replace_once(
    ACTIVITY,
    '''    val reviewState: String,
    val reviewNote: String,
    val reviewedAt: Long
''',
    '''    val reviewState: String,
    val reviewNote: String,
    val reviewedAt: Long,
    val newEventsSinceReview: Int,
    val newObservationsSinceReview: Int,
    val reopened: Boolean,
    val reopenedAt: Long,
    val reopenReason: String,
    val previousReviewState: String
''',
    'activity item model',
)

replace_once(
    ACTIVITY,
    '''        val stateMatches = stateFilter == "all" || item.reviewState == stateFilter
''',
    '''        val stateMatches = when (stateFilter) {
            "all" -> true
            "reopened" -> item.reopened
            else -> item.reviewState == stateFilter
        }
''',
    'activity reopen filter predicate',
)

replace_once(
    ACTIVITY,
    '''                values = listOf(
                    "pending" to "待审核 ${state.report.pendingCount}",
''',
    '''                values = listOf(
                    "reopened" to "重新打开 ${state.report.reopenedCount}",
                    "pending" to "待审核 ${state.report.pendingCount}",
''',
    'activity reopen filter chip',
)

replace_once(
    ACTIVITY,
    '''            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityMetric("高优先级", report.highPriorityCount.toString(), Modifier.weight(1f))
                QualityMetric("观察中", report.observingCount.toString(), Modifier.weight(1f))
                QualityMetric("已处理", report.reviewedCount.toString(), Modifier.weight(1f))
            }
''',
    '''            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityMetric("重新打开", report.reopenedCount.toString(), Modifier.weight(1f))
                QualityMetric("高优先级", report.highPriorityCount.toString(), Modifier.weight(1f))
                QualityMetric("观察中", report.observingCount.toString(), Modifier.weight(1f))
            }
''',
    'activity reopen summary metric',
)

replace_once(
    ACTIVITY,
    '''                    "仅保存审核状态和备注。这里只汇总建议，不会自动停用规则、删除文件、修改清理策略或改变任何定时周期。",
''',
    '''                    "仅保存审核状态和备注；证据明显恶化时只自动重新打开审核状态，不会停用规则、删除文件、修改清理策略或改变任何定时周期。",
''',
    'activity safety copy',
)

replace_once(
    ACTIVITY,
    '''    val visual = qualityVisual(item)
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingAction by remember(item.key, item.reviewState) { mutableStateOf<String?>(null) }
''',
    '''    val visual = qualityVisual(item)
    val context = androidx.compose.ui.platform.LocalContext.current
    val stateTint = if (item.reopened) MaterialTheme.colorScheme.error else reviewStateColor(item.reviewState)
    var pendingAction by remember(item.key, item.reviewState) { mutableStateOf<String?>(null) }
''',
    'activity reopen state tint',
)

replace_once(
    ACTIVITY,
    '''                Surface(shape = CircleShape, color = reviewStateColor(item.reviewState).copy(alpha = .13f)) {
                    Text(
                        reviewStateLabel(item.reviewState),
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = reviewStateColor(item.reviewState),
''',
    '''                Surface(shape = CircleShape, color = stateTint.copy(alpha = .13f)) {
                    Text(
                        if (item.reopened) "重新审核" else reviewStateLabel(item.reviewState),
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = stateTint,
''',
    'activity reopen badge',
)

replace_once(
    ACTIVITY,
    '''            if (item.reviewNote.isNotBlank() || item.reviewedAt > 0L) {
''',
    '''            if (item.reopened) {
                Spacer(Modifier.height(9.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Column(Modifier.padding(11.dp)) {
                        Text("审核已自动重新打开", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Text(item.reopenReason, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 10.sp)
                        Text(
                            "原状态：${reviewStateLabel(item.previousReviewState)} · 审核后新增 ${item.newEventsSinceReview} 次任务 / ${item.newObservationsSinceReview} 条记录",
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = .75f),
                            fontSize = 9.sp
                        )
                        if (item.reopenedAt > 0L) {
                            Text("重新打开时间：${formatReviewTime(item.reopenedAt)}", color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = .75f), fontSize = 9.sp)
                        }
                    }
                }
            }
            if (item.reviewNote.isNotBlank() || item.reviewedAt > 0L) {
''',
    'activity reopen evidence card',
)

replace_once(
    ACTIVITY,
    '''private fun reviewStateLabel(value: String): String = when (value) {
    "observing" -> "观察中"
''',
    '''private fun reviewStateLabel(value: String): String = when (value) {
    "reopened" -> "重新打开"
    "observing" -> "观察中"
''',
    'activity reopen state label',
)

replace_once(
    CLOSED_LOOP,
    '''grep -Fq 'ruleKey !in validKeys' "$REVIEW_REPO"
''',
    '''grep -Fq 'val currentEvidence = evidence[ruleKey]' "$REVIEW_REPO"
''',
    'closed-loop evidence validation assertion',
)

replace_once(
    CLOSED_LOOP,
    '''grep -Fq 'validKeys' "$AUDIT_REPO"
''',
    '''grep -Fq 'ruleQualityReviewRepository.update(ruleKey, action, note, report)' "$AUDIT_REPO"
''',
    'closed-loop report validation assertion',
)

replace_once(
    WORKFLOW,
    '''      - name: Rule review closed-loop contract
        run: bash v2/tests/test-rule-review-closed-loop-contract.sh
      - name: Concurrent scheduler regression
''',
    '''      - name: Rule review closed-loop contract
        run: bash v2/tests/test-rule-review-closed-loop-contract.sh
      - name: Rule review auto-reopen contract
        run: bash v2/tests/test-rule-review-auto-reopen-contract.sh
      - name: Concurrent scheduler regression
''',
    'permanent auto-reopen contract',
)
