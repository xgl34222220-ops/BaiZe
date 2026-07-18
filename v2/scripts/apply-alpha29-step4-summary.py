#!/usr/bin/env python3
from pathlib import Path

path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt")
text = path.read_text(encoding="utf-8")
old_start = '''private fun RecordsPage(state: DashboardUiState, actions: DashboardActions) {
    val context = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item { PageHeader("CLEAN HISTORY", "清理记录", "累计统计永久保存，任务明细保留最近 100 次", actions.refresh) }
        item {
'''
new_start = '''private fun RecordsPage(state: DashboardUiState, actions: DashboardActions) {
    val context = LocalContext.current
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 128.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item { PageHeader("CLEAN HISTORY", "清理记录", "累计统计永久保存，任务明细保留最近 100 次", actions.refresh) }
        if (state.recentApps.isNotEmpty()) {
            item {
                GlassSurface(
                    Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    shadow = 8,
                    contentPadding = PaddingValues(20.dp)
                ) {
                    CurrentCleanupSummaryContent(state.recentApps)
                }
            }
        }
        item {
'''
if new_start in text:
    print("Alpha 29 step 4 records summary already applied")
elif old_start not in text:
    raise SystemExit("RecordsPage start block not found")
else:
    path.write_text(text.replace(old_start, new_start, 1), encoding="utf-8")
    print("Alpha 29 step 4 records summary applied")
