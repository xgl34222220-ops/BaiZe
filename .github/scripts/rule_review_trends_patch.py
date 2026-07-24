from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if new in text:
        print(f"{label}: already applied")
        return
    if old not in text:
        raise SystemExit(f"{label}: marker not found")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"{label}: applied")


AUDIT_REPO = "v2/app/src/main/java/io/github/xgl34222220/baize/root/AuditRepository.kt"
AUDIT_UI = "v2/app/src/main/java/io/github/xgl34222220/baize/AuditActivity.kt"
MANIFEST = "v2/app/src/main/AndroidManifest.xml"

replace_once(
    AUDIT_REPO,
    "    private val ruleQualityReviewRepository = RuleQualityReviewRepository(stateDir)\n",
    "    private val ruleQualityReviewRepository = RuleQualityReviewRepository(stateDir)\n"
    "    private val ruleReviewTrendAnalyzer = RuleReviewTrendAnalyzer()\n",
    "audit repository analyzer field",
)

replace_once(
    AUDIT_REPO,
    "        val ruleQuality = if (reconciliation.changed) {\n"
    "            ruleQualityAnalyzer.analyze(combined, reconciliation.reviews)\n"
    "        } else {\n"
    "            preliminaryRuleQuality\n"
    "        }\n\n"
    "        return JSONObject()\n",
    "        val ruleQuality = if (reconciliation.changed) {\n"
    "            ruleQualityAnalyzer.analyze(combined, reconciliation.reviews)\n"
    "        } else {\n"
    "            preliminaryRuleQuality\n"
    "        }\n"
    "        val trendEvents = if (reconciliation.reopened.isNotEmpty()) {\n"
    "            (readAuditEvents(clearEpoch) + readLegacyEvents(clearEpoch))\n"
    "                .distinctBy { it.optString(\"id\") }\n"
    "                .sortedByDescending { it.optLong(\"timeEpoch\") }\n"
    "                .take(MAX_EVENTS)\n"
    "        } else {\n"
    "            combined\n"
    "        }\n"
    "        val ruleReviewTrends = ruleReviewTrendAnalyzer.analyze(trendEvents, ruleQuality)\n\n"
    "        return JSONObject()\n",
    "audit repository trend analysis",
)

replace_once(
    AUDIT_REPO,
    "            .put(\"ruleQuality\", ruleQuality)\n"
    "            .put(\"events\", page)\n",
    "            .put(\"ruleQuality\", ruleQuality)\n"
    "            .put(\"ruleReviewTrends\", ruleReviewTrends)\n"
    "            .put(\"events\", page)\n",
    "audit repository response",
)

replace_once(
    AUDIT_UI,
    "import androidx.compose.material.icons.rounded.Tune\n",
    "import androidx.compose.material.icons.rounded.Timeline\n"
    "import androidx.compose.material.icons.rounded.Tune\n",
    "audit ui icon import",
)

replace_once(
    AUDIT_UI,
    "                        onOpenEffectiveness = { startActivity(Intent(this, CleanupEffectivenessActivity::class.java)) },\n"
    "                        onOpenRuleQuality = { startActivity(Intent(this, RuleQualityActivity::class.java)) }\n",
    "                        onOpenEffectiveness = { startActivity(Intent(this, CleanupEffectivenessActivity::class.java)) },\n"
    "                        onOpenRuleQuality = { startActivity(Intent(this, RuleQualityActivity::class.java)) },\n"
    "                        onOpenReviewTrends = { startActivity(Intent(this, RuleReviewTrendsActivity::class.java)) }\n",
    "audit ui navigation",
)

replace_once(
    AUDIT_UI,
    "    onOpenPolicy: () -> Unit,\n"
    "    onOpenEffectiveness: () -> Unit,\n"
    "    onOpenRuleQuality: () -> Unit\n",
    "    onOpenPolicy: () -> Unit,\n"
    "    onOpenEffectiveness: () -> Unit,\n"
    "    onOpenRuleQuality: () -> Unit,\n"
    "    onOpenReviewTrends: () -> Unit\n",
    "audit screen callback",
)

replace_once(
    AUDIT_UI,
    "            item { EffectivenessEntryCard(horizontal, cardShape, onOpenEffectiveness) }\n"
    "            item { RuleQualityEntryCard(horizontal, cardShape, onOpenRuleQuality) }\n",
    "            item { EffectivenessEntryCard(horizontal, cardShape, onOpenEffectiveness) }\n"
    "            item { RuleQualityEntryCard(horizontal, cardShape, onOpenRuleQuality) }\n"
    "            item { RuleReviewTrendsEntryCard(horizontal, cardShape, onOpenReviewTrends) }\n",
    "audit screen entry",
)

replace_once(
    AUDIT_UI,
    "@Composable\nprivate fun AuditPolicyAdviceCard(\n",
    "@Composable\n"
    "private fun RuleReviewTrendsEntryCard(\n"
    "    horizontal: androidx.compose.ui.unit.Dp,\n"
    "    shape: androidx.compose.ui.graphics.Shape,\n"
    "    onOpen: () -> Unit\n"
    ") {\n"
    "    Card(\n"
    "        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),\n"
    "        shape = shape,\n"
    "        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)\n"
    "    ) {\n"
    "        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {\n"
    "            Surface(\n"
    "                modifier = Modifier.size(45.dp),\n"
    "                shape = RoundedCornerShape(15.dp),\n"
    "                color = MaterialTheme.colorScheme.tertiary.copy(alpha = .13f)\n"
    "            ) {\n"
    "                Box(contentAlignment = Alignment.Center) {\n"
    "                    Icon(Icons.Rounded.Timeline, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)\n"
    "                }\n"
    "            }\n"
    "            Spacer(Modifier.width(12.dp))\n"
    "            Column(Modifier.weight(1f)) {\n"
    "                Text(\"审核历史与趋势\", fontWeight = FontWeight.Black, fontSize = 16.sp)\n"
    "                Text(\"查看反复重开、恶化原因与人工处理周期\", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)\n"
    "            }\n"
    "            OutlinedButton(onClick = onOpen) { Text(\"趋势\") }\n"
    "        }\n"
    "    }\n"
    "}\n\n"
    "@Composable\nprivate fun AuditPolicyAdviceCard(\n",
    "audit trends entry card",
)

replace_once(
    MANIFEST,
    "        <activity android:name=\".RuleQualityActivity\" android:exported=\"false\" />\n",
    "        <activity android:name=\".RuleQualityActivity\" android:exported=\"false\" />\n"
    "        <activity android:name=\".RuleReviewTrendsActivity\" android:exported=\"false\" />\n",
    "manifest activity",
)
