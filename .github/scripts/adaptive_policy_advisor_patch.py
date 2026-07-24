from pathlib import Path
import re


def patch(path: str, transforms: list[tuple[str, str, str, int]]) -> None:
    file = Path(path)
    text = file.read_text()
    for label, pattern, replacement, flags in transforms:
        text, count = re.subn(pattern, replacement, text, count=1, flags=flags)
        if count != 1:
            raise SystemExit(f"{path}: {label}: expected one match, found {count}")
        print(f"patched {label}")
    file.write_text(text)


patch('v2/app/src/main/java/io/github/xgl34222220/baize/root/AuditRepository.kt', [
    (
        'advisor field',
        r'    private val historyFile = File\(stateDir, "history\.tsv"\)\n',
        '    private val historyFile = File(stateDir, "history.tsv")\n    private val policyAdvisor = PolicyAdvisor()\n',
        0,
    ),
    (
        'advisor response',
        r'        return JSONObject\(\)\n            \.put\("success", true\)(.*?)            \.put\("protectedCount", protectedCount\)\n            \.put\("events", page\)',
        '        val advisor = policyAdvisor.evaluate(combined)\n\n        return JSONObject()\n            .put("success", true)\\1            .put("protectedCount", protectedCount)\n            .put("advisor", advisor)\n            .put("events", page)',
        re.S,
    ),
])


policy_path = 'v2/app/src/main/java/io/github/xgl34222220/baize/CleanupPolicyActivity.kt'
policy_text = Path(policy_path).read_text()

load_pattern = r'''    private fun loadPolicy\(\) \{.*?\n    \}\n\n    private fun applyPolicy'''
load_replacement = '''    private fun loadPolicy() {
        val root = service ?: return
        if (state.loading) return
        state = state.copy(loading = true, message = "正在分析当前策略与设备状态…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val config = JSONObject(root.getSchedulerConfig())
                    val audit = runCatching { JSONObject(root.getAuditTimelinePage(0, 100)) }.getOrDefault(JSONObject())
                    config to audit.optJSONObject("advisor")
                }
            }
            result.onSuccess { (json, advisorJson) ->
                val policy = CleanupPolicy.fromId(json.optInt("cleanup_policy", CleanupPolicy.BALANCED.id))
                state = state.copy(
                    connected = true,
                    loading = false,
                    activePolicy = policy,
                    customized = json.optBoolean("cleanup_policy_customized", false),
                    maxFileMb = json.optInt("max_file_mb", policy.values.getValue("max_file_mb")),
                    fragmentDays = json.optInt("fragment_days", policy.values.getValue("fragment_days")),
                    quarantineDays = json.optInt("quarantine_retention_days", policy.values.getValue("quarantine_retention_days")),
                    advice = parseAdvice(advisorJson),
                    message = if (json.optBoolean("cleanup_policy_customized", false)) {
                        "当前基于${policy.title}档，并包含手动调整"
                    } else {
                        "当前使用${policy.title}档"
                    }
                )
            }.onFailure {
                state = state.copy(loading = false, message = "读取策略失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun parseAdvice(raw: JSONObject?): PolicyAdvice? {
        if (raw == null || !raw.optBoolean("available", false)) return null
        val reasonsJson = raw.optJSONArray("reasons")
        val reasons = buildList {
            if (reasonsJson != null) for (index in 0 until reasonsJson.length()) {
                reasonsJson.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        return PolicyAdvice(
            recommendedPolicy = CleanupPolicy.fromId(raw.optInt("recommendedPolicyId", CleanupPolicy.BALANCED.id)),
            summary = raw.optString("summary", "暂时没有策略建议"),
            confidence = raw.optString("confidence", "low"),
            storageFreePercent = raw.optInt("storageFreePercent", -1),
            failureRate = raw.optInt("failureRate").coerceIn(0, 100),
            restoreRate = raw.optInt("restoreRate").coerceIn(0, 100),
            protectionRate = raw.optInt("protectionRate").coerceIn(0, 100),
            averageScanMs = raw.optLong("averageScanMs").coerceAtLeast(0L),
            sampleCount = raw.optInt("sampleCount").coerceAtLeast(0),
            reasons = reasons,
            automatic = raw.optBoolean("automatic", false),
            scheduleUntouched = raw.optBoolean("scheduleUntouched", true)
        )
    }

    private fun applyPolicy'''
policy_text, count = re.subn(load_pattern, load_replacement, policy_text, count=1, flags=re.S)
if count != 1:
    raise SystemExit(f'{policy_path}: load policy block: {count}')

policy_text = policy_text.replace(
    '    val quarantineDays: Int = 7,\n    val message: String = "等待连接 Root 策略服务"\n)',
    '    val quarantineDays: Int = 7,\n    val advice: PolicyAdvice? = null,\n    val message: String = "等待连接 Root 策略服务"\n)\n\nprivate data class PolicyAdvice(\n    val recommendedPolicy: CleanupPolicy,\n    val summary: String,\n    val confidence: String,\n    val storageFreePercent: Int,\n    val failureRate: Int,\n    val restoreRate: Int,\n    val protectionRate: Int,\n    val averageScanMs: Long,\n    val sampleCount: Int,\n    val reasons: List<String>,\n    val automatic: Boolean,\n    val scheduleUntouched: Boolean\n)',
    1,
)

select_anchor = '''        item {
            Column(Modifier.padding(horizontal = horizontal, vertical = 2.dp)) {
                Text("选择档位", style = MaterialTheme.typography.titleLarge)
'''
select_insert = '''        state.advice?.let { advice ->
            item {
                PolicyAdviceCard(
                    advice = advice,
                    activePolicy = state.activePolicy,
                    customized = state.customized,
                    enabled = state.connected && !state.loading,
                    shape = cardShape,
                    horizontal = horizontal,
                    onApply = { onSelect(advice.recommendedPolicy) }
                )
            }
        }
        item {
            Column(Modifier.padding(horizontal = horizontal, vertical = 2.dp)) {
                Text("选择档位", style = MaterialTheme.typography.titleLarge)
'''
if select_anchor not in policy_text:
    raise SystemExit(f'{policy_path}: selection anchor missing')
policy_text = policy_text.replace(select_anchor, select_insert, 1)

card_anchor = '''@Composable
private fun PolicyCard(
'''
advice_card = '''@Composable
private fun PolicyAdviceCard(
    advice: PolicyAdvice,
    activePolicy: CleanupPolicy,
    customized: Boolean,
    enabled: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
    horizontal: androidx.compose.ui.unit.Dp,
    onApply: () -> Unit
) {
    val matches = advice.recommendedPolicy == activePolicy && !customized
    val confidence = when (advice.confidence) {
        "high" -> "高可信"
        "medium" -> "中等可信"
        else -> "数据较少"
    }
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (matches) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(Modifier.padding(19.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(46.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = .13f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("设备建议：${advice.recommendedPolicy.title}档", fontSize = 18.sp, fontWeight = FontWeight.Black)
                    Text(advice.summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = .55f)) {
                    Text(confidence, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(13.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PolicyMetric("可用空间", if (advice.storageFreePercent < 0) "未知" else "${advice.storageFreePercent}%", Modifier.weight(1f))
                PolicyMetric("任务异常", "${advice.failureRate}%", Modifier.weight(1f))
                PolicyMetric("隔离恢复", "${advice.restoreRate}%", Modifier.weight(1f))
            }
            if (advice.reasons.isNotEmpty()) {
                Spacer(Modifier.height(11.dp))
                advice.reasons.take(4).forEach { reason ->
                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 6.dp).size(5.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(reason, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "基于最近 30 天 ${advice.sampleCount} 条有效记录。仅建议，不会自动切换；定时任务周期保持不变。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
            if (!matches) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onApply, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp)) {
                    Text("采用建议的${advice.recommendedPolicy.title}档", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PolicyCard(
'''
if card_anchor not in policy_text:
    raise SystemExit(f'{policy_path}: policy card anchor missing')
policy_text = policy_text.replace(card_anchor, advice_card, 1)
Path(policy_path).write_text(policy_text)


audit_path = 'v2/app/src/main/java/io/github/xgl34222220/baize/AuditActivity.kt'
audit_text = Path(audit_path).read_text()
audit_text = audit_text.replace(
    'import androidx.compose.material.icons.rounded.Security\n',
    'import androidx.compose.material.icons.rounded.Security\nimport androidx.compose.material.icons.rounded.Tune\n',
    1,
)
audit_text = audit_text.replace(
    '                        onRefresh = ::loadTimeline,\n                        onClear = ::clearTimeline\n',
    '                        onRefresh = ::loadTimeline,\n                        onClear = ::clearTimeline,\n                        onOpenPolicy = { startActivity(Intent(this, CleanupPolicyActivity::class.java)) }\n',
    1,
)
audit_text = audit_text.replace(
    '                    protectedCount = json.optLong("protectedCount").coerceAtLeast(0L),\n                    message = if (events.isEmpty())',
    '                    protectedCount = json.optLong("protectedCount").coerceAtLeast(0L),\n                    advice = parseAdvice(json.optJSONObject("advisor")),\n                    message = if (events.isEmpty())',
    1,
)
parse_anchor = '''        legacy = json.optBoolean("legacy")
    )
}

private data class AuditUiState(
'''
parse_insert = '''        legacy = json.optBoolean("legacy")
    )

    private fun parseAdvice(raw: JSONObject?): AuditPolicyAdvice? {
        if (raw == null || !raw.optBoolean("available", false)) return null
        val reasonsJson = raw.optJSONArray("reasons")
        val reasons = buildList {
            if (reasonsJson != null) for (index in 0 until reasonsJson.length()) {
                reasonsJson.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        return AuditPolicyAdvice(
            recommendedPolicy = CleanupPolicy.fromId(raw.optInt("recommendedPolicyId", CleanupPolicy.BALANCED.id)),
            summary = raw.optString("summary", "暂时没有策略建议"),
            confidence = raw.optString("confidence", "low"),
            storageFreePercent = raw.optInt("storageFreePercent", -1),
            failureRate = raw.optInt("failureRate").coerceIn(0, 100),
            restoreRate = raw.optInt("restoreRate").coerceIn(0, 100),
            sampleCount = raw.optInt("sampleCount").coerceAtLeast(0),
            reasons = reasons
        )
    }
}

private data class AuditUiState(
'''
if parse_anchor not in audit_text:
    raise SystemExit(f'{audit_path}: parse anchor missing')
audit_text = audit_text.replace(parse_anchor, parse_insert, 1)
audit_text = audit_text.replace(
    '    val protectedCount: Long = 0L,\n    val message: String = "等待连接 Root 审计服务"\n)',
    '    val protectedCount: Long = 0L,\n    val advice: AuditPolicyAdvice? = null,\n    val message: String = "等待连接 Root 审计服务"\n)\n\nprivate data class AuditPolicyAdvice(\n    val recommendedPolicy: CleanupPolicy,\n    val summary: String,\n    val confidence: String,\n    val storageFreePercent: Int,\n    val failureRate: Int,\n    val restoreRate: Int,\n    val sampleCount: Int,\n    val reasons: List<String>\n)',
    1,
)
audit_text = audit_text.replace(
    '    onRefresh: () -> Unit,\n    onClear: () -> Unit\n)',
    '    onRefresh: () -> Unit,\n    onClear: () -> Unit,\n    onOpenPolicy: () -> Unit\n)',
    1,
)
audit_text = audit_text.replace(
    '            item { AuditSummary(state, horizontal, cardShape) }\n            item {\n                Row(',
    '            item { AuditSummary(state, horizontal, cardShape) }\n            state.advice?.let { advice ->\n                item { AuditPolicyAdviceCard(advice, horizontal, cardShape, onOpenPolicy) }\n            }\n            item {\n                Row(',
    1,
)
empty_anchor = '''@Composable
private fun AuditEmptyCard(
'''
audit_advice_card = '''@Composable
private fun AuditPolicyAdviceCard(
    advice: AuditPolicyAdvice,
    horizontal: androidx.compose.ui.unit.Dp,
    shape: androidx.compose.ui.graphics.Shape,
    onOpenPolicy: () -> Unit
) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(43.dp),
                    shape = RoundedCornerShape(15.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("设备建议：${advice.recommendedPolicy.title}档", fontWeight = FontWeight.Black, fontSize = 16.sp)
                    Text(advice.summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "可用空间 ${if (advice.storageFreePercent < 0) "未知" else "${advice.storageFreePercent}%"} · 异常率 ${advice.failureRate}% · 恢复率 ${advice.restoreRate}% · 样本 ${advice.sampleCount}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
            advice.reasons.firstOrNull()?.let { reason ->
                Spacer(Modifier.height(7.dp))
                Text(reason, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Spacer(Modifier.height(11.dp))
            OutlinedButton(onClick = onOpenPolicy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Tune, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text("查看并手动采用建议")
            }
            Text(
                "建议不会自动生效，也不会修改定时任务周期。",
                modifier = Modifier.padding(top = 7.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp
            )
        }
    }
}

@Composable
private fun AuditEmptyCard(
'''
if empty_anchor not in audit_text:
    raise SystemExit(f'{audit_path}: empty card anchor missing')
audit_text = audit_text.replace(empty_anchor, audit_advice_card, 1)
Path(audit_path).write_text(audit_text)


workflow_path = '.github/workflows/v2.5-concurrent-scheduler-ci.yml'
workflow_text = Path(workflow_path).read_text()
workflow_anchor = '''      - name: Cleanup policy safety contract
        run: bash v2/tests/test-cleanup-policy-contract.sh
'''
workflow_insert = '''      - name: Cleanup policy safety contract
        run: bash v2/tests/test-cleanup-policy-contract.sh
      - name: Adaptive policy advisor contract
        run: bash v2/tests/test-policy-advisor-contract.sh
'''
if workflow_anchor not in workflow_text:
    raise SystemExit(f'{workflow_path}: contract anchor missing')
Path(workflow_path).write_text(workflow_text.replace(workflow_anchor, workflow_insert, 1))
