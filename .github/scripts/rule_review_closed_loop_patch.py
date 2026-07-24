from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: {label}: expected one match, found {count}")
    file.write_text(text.replace(old, new, 1))
    print(f"patched {label}")


replace_once(
    'v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt',
    '        override fun clearAuditTimeline(): String = auditRepository.clearTimelineJson()\n        override fun getScanCoverage(): String = diagnostics.scanCoverageJson()\n',
    '        override fun clearAuditTimeline(): String = auditRepository.clearTimelineJson()\n        override fun updateRuleQualityReview(ruleKey: String?, action: String?, note: String?): String =\n            auditRepository.updateRuleQualityReviewJson(ruleKey, action, note)\n        override fun getScanCoverage(): String = diagnostics.scanCoverageJson()\n',
    'binder review update',
)

replace_once(
    'v2/app/src/main/java/io/github/xgl34222220/baize/root/AuditRepository.kt',
    '    private val ruleQualityAnalyzer = RuleQualityAnalyzer()\n',
    '    private val ruleQualityAnalyzer = RuleQualityAnalyzer()\n    private val ruleQualityReviewRepository = RuleQualityReviewRepository(stateDir)\n',
    'review repository field',
)
replace_once(
    'v2/app/src/main/java/io/github/xgl34222220/baize/root/AuditRepository.kt',
    '        val ruleQuality = ruleQualityAnalyzer.analyze(combined)\n',
    '        val ruleQuality = ruleQualityAnalyzer.analyze(combined, ruleQualityReviewRepository.read())\n',
    'review state merge',
)

path = Path('v2/app/src/main/java/io/github/xgl34222220/baize/root/AuditRepository.kt')
text = path.read_text()
anchor = '    @Synchronized\n    fun clearTimelineJson(): String = runCatching {\n'
if text.count(anchor) != 1:
    raise SystemExit(f'AuditRepository.kt: review method anchor: expected one match, found {text.count(anchor)}')
method = '''    @Synchronized
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
path.write_text(text.replace(anchor, method + anchor, 1))
print('patched review update method')

replace_once(
    'v2/app/src/main/java/io/github/xgl34222220/baize/root/AuditRepository.kt',
    '    private fun kindFor(operation: String): String = when {\n        operation.contains("quarantine") || operation.contains("restore") || operation.contains("purge") || operation.contains("expire") -> "safety"\n',
    '    private fun kindFor(operation: String): String = when {\n        operation.contains("rule-review") -> "review"\n        operation.contains("quarantine") || operation.contains("restore") || operation.contains("purge") || operation.contains("expire") -> "safety"\n',
    'audit review kind',
)

replace_once(
    '.github/workflows/v2.5-concurrent-scheduler-ci.yml',
    '      - name: Rule quality center contract\n        run: bash v2/tests/test-rule-quality-contract.sh\n      - name: Concurrent scheduler regression\n',
    '      - name: Rule quality center contract\n        run: bash v2/tests/test-rule-quality-contract.sh\n      - name: Rule review closed-loop contract\n        run: bash v2/tests/test-rule-review-closed-loop-contract.sh\n      - name: Concurrent scheduler regression\n',
    'permanent review contract',
)
