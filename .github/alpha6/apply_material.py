from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing marker: {label}")
    return text.replace(old, new, 1)

path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/material/CleanScreenMaterial.kt")
text = path.read_text()
text = replace_once(text, 'import androidx.compose.material3.FilledTonalButton\nimport androidx.compose.material3.HorizontalDivider', 'import androidx.compose.material3.FilledTonalButton\nimport androidx.compose.material3.TextButton\nimport androidx.compose.material3.HorizontalDivider', "text button import")
text = replace_once(text, 'import io.github.xgl34222220.baize.ui.clean.formatMinutes\n', 'import io.github.xgl34222220.baize.ui.clean.formatMinutes\nimport io.github.xgl34222220.baize.ui.clean.scanRateText\nimport io.github.xgl34222220.baize.ui.clean.scanWorkerModeLabel\nimport io.github.xgl34222220.baize.ui.clean.scanWorkerReasonLabel\n', "performance imports")
text = replace_once(text, '''        item {
            MaterialSectionHeader(
                eyebrow = "MANUAL TOOLS",''', '''        item {
            MaterialSectionHeader(
                eyebrow = "SCAN PERFORMANCE",
                title = "扫描性能策略",
                subtitle = "按本机真实吞吐选择串行或双工作进程"
            )
        }
        item { MaterialScanPerformanceCard(state, actions) }
        item {
            MaterialSectionHeader(
                eyebrow = "MANUAL TOOLS",''', "performance section")
text = replace_once(text, '@Composable\nprivate fun MaterialCleanHeader() {\n', '''@Composable
private fun MaterialScanPerformanceCard(
    state: CleanUiState,
    actions: CleanUiActions
) {
    val performance = state.scanPerformance
    Card(
        modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("扫描工作进程", fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text(
                scanWorkerReasonLabel(performance.workerReason),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0, 1, 2).forEach { mode ->
                    if (state.scanRootWorkers == mode) {
                        FilledTonalButton(
                            onClick = { actions.onScanWorkerModeChanged(mode) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text(scanWorkerModeLabel(mode), fontSize = 11.sp, maxLines = 1)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { actions.onScanWorkerModeChanged(mode) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text(scanWorkerModeLabel(mode), fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
            MaterialPerformanceMetric("本次实际", if (performance.actualWorkers == 2) "双工作进程" else "串行")
            MaterialPerformanceMetric("本机推荐", if (performance.recommendedWorkers == 2) "双工作进程" else "串行")
            MaterialPerformanceMetric("串行吞吐", scanRateText(performance.serialRate))
            MaterialPerformanceMetric("双进程吞吐", scanRateText(performance.parallelRate))
            MaterialPerformanceMetric(
                "并发提升",
                if (performance.serialRate > 0 && performance.parallelRate > 0) {
                    val prefix = if (performance.parallelGainPercent > 0) "+" else ""
                    "$prefix${performance.parallelGainPercent}%"
                } else "等待对比样本"
            )
            MaterialPerformanceMetric(
                "学习进度",
                if (performance.successfulRuns > 0) {
                    "${performance.successfulRuns} 次 · 下次复测 ${performance.nextProbeRun.takeIf { it > 0 } ?: "待定"}"
                } else "下一次自动扫描开始学习"
            )
            TextButton(
                onClick = actions.onResetScanPerformance,
                enabled = performance.available,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("清除基准并重新学习")
            }
            Text(
                "修改策略后点击上方“保存自动清理设置”才会写入模块。固定双进程只影响扫描，不改变快照清理逻辑。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun MaterialPerformanceMetric(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun MaterialCleanHeader() {
''', "performance card")
path.write_text(text)
