from pathlib import Path
import re


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
    '    private val historyFile = File(stateDir, "history.tsv")\n    private val policyAdvisor = PolicyAdvisor()\n',
    '    private val historyFile = File(stateDir, "history.tsv")\n    private val policyAdvisor = PolicyAdvisor()\n    private val effectivenessAnalyzer = CleanupEffectivenessAnalyzer()\n',
    'audit analyzer field',
)
replace_once(
    'v2/app/src/main/java/io/github/xgl34222220/baize/root/AuditRepository.kt',
    '        val advisor = policyAdvisor.evaluate(combined)\n\n        return JSONObject()\n',
    '        val advisor = policyAdvisor.evaluate(combined)\n        val effectiveness = effectivenessAnalyzer.analyze(combined)\n\n        return JSONObject()\n',
    'audit effectiveness evaluation',
)
replace_once(
    'v2/app/src/main/java/io/github/xgl34222220/baize/root/AuditRepository.kt',
    '            .put("advisor", advisor)\n            .put("events", page)\n',
    '            .put("advisor", advisor)\n            .put("effectiveness", effectiveness)\n            .put("events", page)\n',
    'audit effectiveness response',
)

replace_once(
    'v2/app/src/main/AndroidManifest.xml',
    '        <activity android:name=".AuditActivity" android:exported="false" />\n',
    '        <activity android:name=".AuditActivity" android:exported="false" />\n        <activity android:name=".CleanupEffectivenessActivity" android:exported="false" />\n',
    'effectiveness activity manifest',
)

audit = Path('v2/app/src/main/java/io/github/xgl34222220/baize/AuditActivity.kt')
text = audit.read_text()
text, count = re.subn(
    r'import androidx\.compose\.material\.icons\.rounded\.History\n',
    'import androidx.compose.material.icons.rounded.History\nimport androidx.compose.material.icons.rounded.Insights\n',
    text,
    count=1,
)
if count != 1:
    raise SystemExit(f'AuditActivity.kt: insights import: expected one match, found {count}')
text, count = re.subn(
    r'(\s+onOpenPolicy = \{ startActivity\(Intent\(this, CleanupPolicyActivity::class\.java\)\) \}\n)',
    r'\1                        onOpenEffectiveness = { startActivity(Intent(this, CleanupEffectivenessActivity::class.java)) }\n',
    text,
    count=1,
)
if count != 1:
    raise SystemExit(f'AuditActivity.kt: activity callback: expected one match, found {count}')
text, count = re.subn(
    r'(\s+onOpenPolicy: \(\) -> Unit\n)(\))',
    r'\1    onOpenEffectiveness: () -> Unit\n\2',
    text,
    count=1,
)
if count != 1:
    raise SystemExit(f'AuditActivity.kt: screen callback parameter: expected one match, found {count}')
text, count = re.subn(
    r'(\s+item \{ AuditSummary\(state, horizontal, cardShape\) \}\n)',
    r'\1            item { EffectivenessEntryCard(horizontal, cardShape, onOpenEffectiveness) }\n',
    text,
    count=1,
)
if count != 1:
    raise SystemExit(f'AuditActivity.kt: effectiveness entry: expected one match, found {count}')
anchor = '@Composable\nprivate fun AuditPolicyAdviceCard('
if text.count(anchor) != 1:
    raise SystemExit(f'AuditActivity.kt: card anchor: expected one match, found {text.count(anchor)}')
card = '''@Composable
private fun EffectivenessEntryCard(
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
                color = MaterialTheme.colorScheme.secondary.copy(alpha = .14f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("清理效果评分", fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text("查看安全性、收益、耗时、稳定性和规则趋势", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            OutlinedButton(onClick = onOpen) { Text("查看") }
        }
    }
}

'''
text = text.replace(anchor, card + anchor, 1)
audit.write_text(text)
print('patched audit effectiveness entry')
