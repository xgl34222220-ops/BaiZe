from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing marker: {label}")
    return text.replace(old, new, 1)

path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/miuix/CleanScreenMiuix.kt")
text = path.read_text()
text = replace_once(text, 'import io.github.xgl34222220.baize.ui.clean.formatMinutes\n', 'import io.github.xgl34222220.baize.ui.clean.formatMinutes\nimport io.github.xgl34222220.baize.ui.clean.scanRateText\nimport io.github.xgl34222220.baize.ui.clean.scanWorkerModeLabel\nimport io.github.xgl34222220.baize.ui.clean.scanWorkerReasonLabel\n', "performance imports")
text = replace_once(text, '''        item {
            MiuixSectionHeader(
                eyebrow = "MANUAL TOOLS",''', '''        item {
            MiuixSectionHeader(
                eyebrow = "SCAN PERFORMANCE",
                title = "扫描性能策略",
                subtitle = "按本机真实吞吐选择串行或双工作进程"
            )
        }
        item {
            MiuixGroupCard(
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                MiuixScanPerformancePanel(state, actions)
            }
        }
        item {
            MiuixSectionHeader(
                eyebrow = "MANUAL TOOLS",''', "performance section")
text = replace_once(text, '@Composable\nprivate fun MiuixQuickActionRow(action: MiuixQuickAction) {\n', '''@Composable
private fun MiuixScanPerformancePanel(
    state: CleanUiState,
    actions: CleanUiActions
) {
    val performance = state.scanPerformance
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiuixIconTile(Icons.Rounded.AutoAwesome)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text("扫描工作进程", fontSize = 16.sp, fontWeight = FontWeight.Black)
                Text(
                    scanWorkerReasonLabel(performance.workerReason),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        }
        Spacer(Modifier.height(13.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            listOf(0, 1, 2).forEach { mode ->
                val active = state.scanRootWorkers == mode
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(15.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary.copy(alpha = .16f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = .05f)
                        )
                        .border(
                            1.dp,
                            if (active) MaterialTheme.colorScheme.primary.copy(alpha = .35f)
                            else Color.Transparent,
                            RoundedCornerShape(15.dp)
                        )
                        .clickable { actions.onScanWorkerModeChanged(mode) }
                        .padding(horizontal = 4.dp, vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        scanWorkerModeLabel(mode),
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
        MiuixPerformanceMetric("本次实际", if (performance.actualWorkers == 2) "双工作进程" else "串行")
        MiuixPerformanceMetric("本机推荐", if (performance.recommendedWorkers == 2) "双工作进程" else "串行")
        MiuixPerformanceMetric("串行吞吐", scanRateText(performance.serialRate))
        MiuixPerformanceMetric("双进程吞吐", scanRateText(performance.parallelRate))
        MiuixPerformanceMetric(
            "并发提升",
            if (performance.serialRate > 0 && performance.parallelRate > 0) {
                val prefix = if (performance.parallelGainPercent > 0) "+" else ""
                "$prefix${performance.parallelGainPercent}%"
            } else "等待对比样本"
        )
        MiuixPerformanceMetric(
            "学习进度",
            if (performance.successfulRuns > 0) {
                "${performance.successfulRuns} 次 · 下次 ${performance.nextProbeRun.takeIf { it > 0 } ?: "待定"}"
            } else "下一次自动扫描开始学习"
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .05f))
                .clickable(enabled = performance.available, onClick = actions.onResetScanPerformance)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (performance.available) "清除基准并重新学习" else "尚未建立性能基准",
                color = if (performance.available) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(
            "修改策略后点击上方保存按钮才会写入模块。固定双进程只影响扫描，不改变快照清理逻辑。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 9.sp,
            lineHeight = 13.sp
        )
    }
}

@Composable
private fun MiuixPerformanceMetric(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MiuixQuickActionRow(action: MiuixQuickAction) {
''', "performance panel")
path.write_text(text)
