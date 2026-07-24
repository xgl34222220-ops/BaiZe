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
    'v2/app/src/main/java/io/github/xgl34222220/baize/root/AuditRepository.kt',
    '    private val effectivenessAnalyzer = CleanupEffectivenessAnalyzer()\n',
    '    private val effectivenessAnalyzer = CleanupEffectivenessAnalyzer()\n    private val ruleQualityAnalyzer = RuleQualityAnalyzer()\n',
    'audit analyzer field',
)
replace_once(
    'v2/app/src/main/java/io/github/xgl34222220/baize/root/AuditRepository.kt',
    '        val effectiveness = effectivenessAnalyzer.analyze(combined)\n\n        return JSONObject()\n',
    '        val effectiveness = effectivenessAnalyzer.analyze(combined)\n        val ruleQuality = ruleQualityAnalyzer.analyze(combined)\n\n        return JSONObject()\n',
    'audit rule quality evaluation',
)
replace_once(
    'v2/app/src/main/java/io/github/xgl34222220/baize/root/AuditRepository.kt',
    '            .put("effectiveness", effectiveness)\n            .put("events", page)\n',
    '            .put("effectiveness", effectiveness)\n            .put("ruleQuality", ruleQuality)\n            .put("events", page)\n',
    'audit rule quality response',
)

replace_once(
    'v2/app/src/main/AndroidManifest.xml',
    '        <activity android:name=".CleanupEffectivenessActivity" android:exported="false" />\n',
    '        <activity android:name=".CleanupEffectivenessActivity" android:exported="false" />\n        <activity android:name=".RuleQualityActivity" android:exported="false" />\n',
    'rule quality activity manifest',
)

replace_once(
    'v2/app/src/main/java/io/github/xgl34222220/baize/AuditActivity.kt',
    'import androidx.compose.material.icons.rounded.Refresh\n',
    'import androidx.compose.material.icons.rounded.Refresh\nimport androidx.compose.material.icons.rounded.Rule\n',
    'rule icon import',
)
replace_once(
    'v2/app/src/main/java/io/github/xgl34222220/baize/AuditActivity.kt',
    '                        onOpenEffectiveness = { startActivity(Intent(this, CleanupEffectivenessActivity::class.java)) }\n',
    '                        onOpenEffectiveness = { startActivity(Intent(this, CleanupEffectivenessActivity::class.java)) },\n                        onOpenRuleQuality = { startActivity(Intent(this, RuleQualityActivity::class.java)) }\n',
    'activity callback',
)
replace_once(
    'v2/app/src/main/java/io/github/xgl34222220/baize/AuditActivity.kt',
    '    onOpenEffectiveness: () -> Unit\n) {\n',
    '    onOpenEffectiveness: () -> Unit,\n    onOpenRuleQuality: () -> Unit\n) {\n',
    'screen callback parameter',
)
replace_once(
    'v2/app/src/main/java/io/github/xgl34222220/baize/AuditActivity.kt',
    '            item { EffectivenessEntryCard(horizontal, cardShape, onOpenEffectiveness) }\n',
    '            item { EffectivenessEntryCard(horizontal, cardShape, onOpenEffectiveness) }\n            item { RuleQualityEntryCard(horizontal, cardShape, onOpenRuleQuality) }\n',
    'rule quality entry',
)

path = Path('v2/app/src/main/java/io/github/xgl34222220/baize/AuditActivity.kt')
text = path.read_text()
anchor = '@Composable\nprivate fun AuditPolicyAdviceCard('
if text.count(anchor) != 1:
    raise SystemExit(f'AuditActivity.kt: card anchor: expected one match, found {text.count(anchor)}')
card = '''@Composable
private fun RuleQualityEntryCard(
    horizontal: androidx.compose.ui.unit.Dp,
    shape: androidx.compose.ui.graphics.Shape,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier.padding(horizontal = horizontal).fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(45.dp),
                shape = RoundedCornerShape(15.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Rule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("规则质量中心", fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text("集中审核高失败、频繁保护、零命中与低收益规则", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            OutlinedButton(onClick = onOpen) { Text("审核") }
        }
    }
}

'''
path.write_text(text.replace(anchor, card + anchor, 1))
print('patched rule quality entry card')
