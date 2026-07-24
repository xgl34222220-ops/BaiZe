from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: {label}: expected 1, found {count}")
    file.write_text(text.replace(old, new, 1))
    print(f"repaired {label}")


replace_once(
    'v2/app/src/main/java/io/github/xgl34222220/baize/root/NativeProfileEngine.kt',
    '''    if (snapshot.options.highRiskMode == "audit") {
        return JSONObject().put("error", "policy_audit_only").put("message", "当前保守策略仅审计高风险项目，请切换策略并重新扫描").toString()
    }
\u0001
        candidate.risk == "high" && (selection[candidate.id] == true || selection[candidate.path] == true)
    }
''',
    '''    if (snapshot.options.highRiskMode == "audit") {
        return JSONObject().put("error", "policy_audit_only").put("message", "当前保守策略仅审计高风险项目，请切换策略并重新扫描").toString()
    }
    val selection = parseSelection(selectionJson)
    val selected = snapshot.candidates.filter { candidate ->
        candidate.risk == "high" && (selection[candidate.id] == true || selection[candidate.path] == true)
    }
''',
    'quarantine selection block'
)

replace_once(
    'v2/app/src/main/java/io/github/xgl34222220/baize/ScanWorkbenchActivity.kt',
    '''private fun WorkbenchStatusCard(
    state: WorkbenchUiState,
    shape: Shape,
    horizontal: androidx.compose.ui.unit.Dp,
    highRiskMode: String,
    onStop: () -> Unit
)''',
    '''private fun WorkbenchStatusCard(
    state: WorkbenchUiState,
    shape: Shape,
    horizontal: androidx.compose.ui.unit.Dp,
    onStop: () -> Unit
)''',
    'status card signature'
)

replace_once(
    'v2/app/src/main/java/io/github/xgl34222220/baize/ScanWorkbenchActivity.kt',
    '''private fun WorkbenchCandidateRow(
    item: WorkbenchItem,
    selected: Boolean,
    horizontal: androidx.compose.ui.unit.Dp,
    onToggle: () -> Unit,''',
    '''private fun WorkbenchCandidateRow(
    item: WorkbenchItem,
    selected: Boolean,
    horizontal: androidx.compose.ui.unit.Dp,
    highRiskMode: String,
    onToggle: () -> Unit,''',
    'candidate row signature'
)
