from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: {label}: expected one match, found {count}")
    file.write_text(text.replace(old, new, 1))
    print(f"fixed {label}")


replace_once(
    'v2/app/src/main/java/io/github/xgl34222220/baize/AuditActivity.kt',
    '                        onOpenPolicy = { startActivity(Intent(this, CleanupPolicyActivity::class.java)) }\n                        onOpenEffectiveness = { startActivity(Intent(this, CleanupEffectivenessActivity::class.java)) }\n',
    '                        onOpenPolicy = { startActivity(Intent(this, CleanupPolicyActivity::class.java)) },\n                        onOpenEffectiveness = { startActivity(Intent(this, CleanupEffectivenessActivity::class.java)) }\n',
    'AuditScreen call comma',
)
replace_once(
    'v2/app/src/main/java/io/github/xgl34222220/baize/AuditActivity.kt',
    '    onOpenPolicy: () -> Unit\n    onOpenEffectiveness: () -> Unit\n',
    '    onOpenPolicy: () -> Unit,\n    onOpenEffectiveness: () -> Unit\n',
    'AuditScreen parameter comma',
)
replace_once(
    'v2/app/src/main/java/io/github/xgl34222220/baize/CleanupEffectivenessActivity.kt',
    'import androidx.compose.foundation.layout.weight\n',
    '',
    'invalid weight import',
)
