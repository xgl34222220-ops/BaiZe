from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing marker: {label}")
    return text.replace(old, new, 1)

path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt")
text = path.read_text()
text = replace_once(text, "data class DashboardUiState(\n", '''data class ScanPerformanceUiState(
    val available: Boolean = false,
    val workerPolicy: String = "auto",
    val workerReason: String = "not_measured",
    val actualWorkers: Int = 1,
    val recommendedWorkers: Int = 1,
    val parallelGainPercent: Int = 0,
    val serialRate: Long = 0,
    val parallelRate: Long = 0,
    val successfulRuns: Int = 0,
    val nextProbeRun: Int = 0,
    val parallelBlockedUntil: Long = 0
)

data class DashboardUiState(
''', "scan performance model")
text = replace_once(text, '    val history: List<HistoryUiItem> = emptyList()\n)', '    val history: List<HistoryUiItem> = emptyList(),\n    val scanPerformance: ScanPerformanceUiState = ScanPerformanceUiState()\n)', "dashboard performance state")
text = replace_once(text, '    val apkPackageDays: Int = 30,\n    val saving: Boolean = false', '    val apkPackageDays: Int = 30,\n    val scanRootWorkers: Int = 0,\n    val saving: Boolean = false', "scheduler worker mode")
text = replace_once(text, '        .put("apk_package_days", apkPackageDays.coerceIn(0, 365))', '        .put("apk_package_days", apkPackageDays.coerceIn(0, 365))\n        .put("scan_root_workers", scanRootWorkers.coerceIn(0, 2))', "scheduler json write")
text = replace_once(text, '            apkPackageDays = json.optInt("apk_package_days", 30).coerceIn(0, 365)\n        )', '            apkPackageDays = json.optInt("apk_package_days", 30).coerceIn(0, 365),\n            scanRootWorkers = json.optInt("scan_root_workers", 0).coerceIn(0, 2)\n        )', "scheduler json read")
text = replace_once(text, '    val reconnect: () -> Unit,\n    val crash: () -> Unit', '    val reconnect: () -> Unit,\n    val resetScanPerformance: () -> Unit,\n    val crash: () -> Unit', "dashboard reset action")
path.write_text(text)

path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/CleanContract.kt")
text = path.read_text()
text = replace_once(text, 'import io.github.xgl34222220.baize.SchedulerUiState\n', 'import io.github.xgl34222220.baize.ScanPerformanceUiState\nimport io.github.xgl34222220.baize.SchedulerUiState\n', "contract import")
text = replace_once(text, '    val apkPackageDays: Int,\n    val saving: Boolean', '    val apkPackageDays: Int,\n    val scanRootWorkers: Int,\n    val scanPerformance: ScanPerformanceUiState,\n    val saving: Boolean', "contract performance state")
text = replace_once(text, '    val onApkPackagesChanged: (Boolean) -> Unit,\n    val onSave: () -> Unit,', '    val onApkPackagesChanged: (Boolean) -> Unit,\n    val onScanWorkerModeChanged: (Int) -> Unit,\n    val onResetScanPerformance: () -> Unit,\n    val onSave: () -> Unit,', "contract performance actions")
text = replace_once(text, '    serviceText: String\n): CleanUiState = CleanUiState(', '    serviceText: String,\n    scanPerformance: ScanPerformanceUiState\n): CleanUiState = CleanUiState(', "contract mapper argument")
text = replace_once(text, '    apkPackageDays = apkPackageDays,\n    saving = saving', '    apkPackageDays = apkPackageDays,\n    scanRootWorkers = scanRootWorkers,\n    scanPerformance = scanPerformance,\n    saving = saving', "contract mapper state")
text += '''
internal fun scanWorkerModeLabel(mode: Int): String = when (mode) {
    1 -> "固定串行"
    2 -> "固定双进程"
    else -> "自动推荐"
}

internal fun scanWorkerReasonLabel(reason: String): String = when (reason) {
    "auto_bootstrap_serial" -> "首次建立串行基准"
    "auto_not_eligible" -> "当前设备或目录条件不适合并发"
    "auto_parallel_cooldown" -> "并发异常，暂时回退串行"
    "auto_small_workload" -> "工作量较小，串行更合适"
    "auto_parallel_probe" -> "正在探测双进程表现"
    "auto_serial_reprobe" -> "正在复测串行表现"
    "auto_parallel_reprobe" -> "正在复测双进程表现"
    "auto_parallel_faster" -> "本机双进程明显更快"
    "auto_serial_faster" -> "本机串行更快或差距不足"
    "manual_serial" -> "用户固定串行"
    "manual_parallel" -> "用户固定双进程"
    "manual_parallel_unavailable" -> "双进程条件不足，已使用串行"
    "auto_parallel_failed" -> "双进程失败，已进入冷却"
    else -> "等待建立本机性能基准"
}

internal fun scanRateText(rate: Long): String = if (rate > 0) "$rate 项/秒" else "暂无样本"
'''
path.write_text(text)

path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/CleanRoute.kt")
text = path.read_text()
text = replace_once(text, '        serviceText = dashboard.serviceText\n    )', '        serviceText = dashboard.serviceText,\n        scanPerformance = dashboard.scanPerformance\n    )', "route performance state")
text = replace_once(text, '''        onApkPackagesChanged = { enabled ->
            dashboardActions.updateScheduler(scheduler.copy(apkPackagesEnabled = enabled))
        },''', '''        onApkPackagesChanged = { enabled ->
            dashboardActions.updateScheduler(scheduler.copy(apkPackagesEnabled = enabled))
        },
        onScanWorkerModeChanged = { mode ->
            dashboardActions.updateScheduler(scheduler.copy(scanRootWorkers = mode.coerceIn(0, 2)))
        },
        onResetScanPerformance = dashboardActions.resetScanPerformance,''', "route performance actions")
path.write_text(text)

path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt")
text = path.read_text()
text = replace_once(text, '                    reconnect = { reconnectService() },\n                    crash = { showCrashDialog() }', '                    reconnect = { reconnectService() },\n                    resetScanPerformance = { resetScanPerformance() },\n                    crash = { showCrashDialog() }', "activity reset action")
text = replace_once(text, '    private fun refreshModuleState() {\n', '''    private fun resetScanPerformance() {
        val service = rootService ?: return toast("Root 服务尚未连接")
        lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) {
                runCatching { JSONObject(service.resetScanWorkerProfile()) }
            }
            val json = response.getOrNull()
            val success = json?.optBoolean("success") == true
            toast(
                if (success) json?.optString("message").orEmpty().ifBlank { "性能基准已清除" }
                else "重置失败：${json?.optString("error").orEmpty().ifBlank { response.exceptionOrNull()?.message ?: "未知错误" }}"
            )
            refreshModuleState()
        }
    }

    private fun refreshModuleState() {
''', "activity reset method")
text = replace_once(text, '            val scheduler = json.optJSONObject("scheduler") ?: JSONObject()\n', '            val scheduler = json.optJSONObject("scheduler") ?: JSONObject()\n            val performance = json.optJSONObject("scanPerformance") ?: JSONObject()\n', "activity performance json")
text = replace_once(text, '''                recentJunk = if (otherDetails.isNotEmpty()) otherDetails else dashboardState.value.recentJunk,
                schedulerText = when (scheduler.optString("state", "waiting")) {''', '''                recentJunk = if (otherDetails.isNotEmpty()) otherDetails else dashboardState.value.recentJunk,
                scanPerformance = ScanPerformanceUiState(
                    available = performance.optBoolean("available", false),
                    workerPolicy = performance.optString("workerPolicy", "auto"),
                    workerReason = performance.optString("workerReason", "not_measured"),
                    actualWorkers = performance.optInt("actualWorkers", 1).coerceIn(1, 2),
                    recommendedWorkers = performance.optInt("recommendedWorkers", 1).coerceIn(1, 2),
                    parallelGainPercent = performance.optInt("parallelGainPercent", 0),
                    serialRate = performance.optLong("serialRate", 0L).coerceAtLeast(0L),
                    parallelRate = performance.optLong("parallelRate", 0L).coerceAtLeast(0L),
                    successfulRuns = performance.optInt("successfulRuns", 0).coerceAtLeast(0),
                    nextProbeRun = performance.optInt("nextProbeRun", 0).coerceAtLeast(0),
                    parallelBlockedUntil = performance.optLong("parallelBlockedUntil", 0L).coerceAtLeast(0L)
                ),
                schedulerText = when (scheduler.optString("state", "waiting")) {''', "activity performance state")
path.write_text(text)
