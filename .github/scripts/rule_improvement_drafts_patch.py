from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"missing integration marker for {label}: {path}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


AUDIT_REPO = "v2/app/src/main/java/io/github/xgl34222220/baize/root/AuditRepository.kt"
AUDIT_UI = "v2/app/src/main/java/io/github/xgl34222220/baize/AuditActivity.kt"
MANIFEST = "v2/app/src/main/AndroidManifest.xml"

replace_once(
    AUDIT_REPO,
    "    private val ruleReviewTrendAnalyzer = RuleReviewTrendAnalyzer()\n",
    "    private val ruleReviewTrendAnalyzer = RuleReviewTrendAnalyzer()\n"
    "    private val ruleImprovementDraftAnalyzer = RuleImprovementDraftAnalyzer()\n",
    "draft analyzer field",
)

replace_once(
    AUDIT_REPO,
    "        val ruleReviewTrends = ruleReviewTrendAnalyzer.analyze(trendEvents, ruleQuality)\n\n"
    "        return JSONObject()\n",
    "        val ruleReviewTrends = ruleReviewTrendAnalyzer.analyze(trendEvents, ruleQuality)\n"
    "        val ruleImprovementDrafts = ruleImprovementDraftAnalyzer.analyze(ruleQuality, ruleReviewTrends)\n\n"
    "        return JSONObject()\n",
    "draft report generation",
)

replace_once(
    AUDIT_REPO,
    "            .put(\"ruleReviewTrends\", ruleReviewTrends)\n"
    "            .put(\"events\", page)\n",
    "            .put(\"ruleReviewTrends\", ruleReviewTrends)\n"
    "            .put(\"ruleImprovementDrafts\", ruleImprovementDrafts)\n"
    "            .put(\"events\", page)\n",
    "draft report response",
)

replace_once(
    AUDIT_UI,
    "                        onOpenReviewTrends = { startActivity(Intent(this, RuleReviewTrendsActivity::class.java)) }\n",
    "                        onOpenReviewTrends = { startActivity(Intent(this, RuleReviewTrendsActivity::class.java)) },\n"
    "                        onOpenImprovementDrafts = { startActivity(Intent(this, RuleImprovementDraftsActivity::class.java)) }\n",
    "audit activity callback",
)

replace_once(
    AUDIT_UI,
    "    onOpenRuleQuality: () -> Unit,\n"
    "    onOpenReviewTrends: () -> Unit\n",
    "    onOpenRuleQuality: () -> Unit,\n"
    "    onOpenReviewTrends: () -> Unit,\n"
    "    onOpenImprovementDrafts: () -> Unit\n",
    "audit screen callback",
)

replace_once(
    AUDIT_UI,
    "            item { RuleReviewTrendsEntryCard(horizontal, cardShape, onOpenReviewTrends) }\n",
    "            item { RuleReviewTrendsEntryCard(horizontal, cardShape, onOpenReviewTrends) }\n"
    "            item { RuleImprovementDraftsEntryCard(horizontal, cardShape, onOpenImprovementDrafts) }\n",
    "audit screen entry",
)

entry_card = '''
@Composable
private fun RuleImprovementDraftsEntryCard(
    horizontal: androidx.compose.ui.unit.Dp,
    shape: androidx.compose.ui.graphics.Shape,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(45.dp),
                shape = RoundedCornerShape(15.dp),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = .13f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Rule, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("规则改进建议草案", fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text("查看缩小范围、增强保护、观察或停用评估草案", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            OutlinedButton(onClick = onOpen) { Text("草案") }
        }
    }
}

'''
replace_once(
    AUDIT_UI,
    "@Composable\nprivate fun AuditPolicyAdviceCard(\n",
    entry_card + "@Composable\nprivate fun AuditPolicyAdviceCard(\n",
    "audit entry card",
)

replace_once(
    MANIFEST,
    '        <activity android:name=".RuleReviewTrendsActivity" android:exported="false" />\n',
    '        <activity android:name=".RuleReviewTrendsActivity" android:exported="false" />\n'
    '        <activity android:name=".RuleImprovementDraftsActivity" android:exported="false" />\n',
    "manifest activity",
)
