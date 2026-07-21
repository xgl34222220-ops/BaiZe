from pathlib import Path

root = Path(__file__).resolve().parents[1]
activity_path = root / "v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt"
text = activity_path.read_text()


def rep(old: str, new: str, count: int = 1) -> None:
    global text
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"missing activity anchor: {old[:140]!r}")
    text = text.replace(old, new, count)


rep(
    "    private var pollJob: Job? = null\n",
    "    private var pollJob: Job? = null\n"
    "    private var recoveryProbeJob: Job? = null\n"
)

rep(
    "class MiuixDashboardActivity : ComponentActivity() {\n",
    "class MiuixDashboardActivity : ComponentActivity() {\n"
    "    private data class RemoteTaskProbe(\n"
    "        val complete: Boolean,\n"
    "        val runningState: JSONObject?\n"
    "    )\n\n"
)

rep(
    """            updateConnectionState()
            refreshAll()
            runPendingActionsIfReady()
""",
    """            updateConnectionState()
            recoverRemoteTaskOrRefresh(runPendingActions = true)
"""
)

rep(
    """            updateConnectionState()
            readServiceStatus()
            runPendingActionsIfReady()
""",
    """            updateConnectionState()
            recoverRemoteTaskOrRefresh(runPendingActions = true)
"""
)

rep(
    """        connectPrimaryService()
    }

    override fun onNewIntent""",
    """        connectServices()
    }

    override fun onNewIntent"""
)

rep(
    """        if (rootService != null) {
            refreshAll()
        } else {
            connectPrimaryService()
        }
""",
    """        if (rootService != null || cacheService != null) {
            recoverRemoteTaskOrRefresh()
        } else {
            connectServices()
        }
"""
)

rep(
    """    private fun refreshAll() {
        updateStorage()
        readServiceStatus()
        loadScheduler()
        refreshModuleState()
        refreshHistory()
        refreshRawLog()
        refreshWhitelist()
    }

    private fun connectPrimaryService() {
""",
    """    private fun refreshAll() {
        updateStorage()
        if (dashboardState.value.running) {
            readServiceStatus()
            recoverRemoteTaskOrRefresh()
            return
        }
        readServiceStatus()
        loadScheduler()
        refreshModuleState()
        refreshHistory()
        refreshRawLog()
        refreshWhitelist()
    }

    private fun recoverRemoteTaskOrRefresh(runPendingActions: Boolean = false) {
        recoveryProbeJob?.cancel()
        recoveryProbeJob = lifecycleScope.launch {
            val probe = probeRemoteTask()
            val running = probe.runningState
            when {
                running != null -> {
                    dashboardState.value = dashboardState.value.copy(
                        running = true,
                        scanCompleted = false,
                        serviceText = "后台 Root 任务仍在执行，已恢复实时进度"
                    )
                    renderTaskState(running)
                    startRecoveredTaskPoll()
                }
                !probe.complete -> {
                    dashboardState.value = dashboardState.value.copy(
                        connected = rootService != null,
                        ready = false,
                        serviceText = "正在连接后台任务状态…",
                        taskPhase = if (dashboardState.value.running) {
                            "后台任务仍在执行，正在恢复 Root 连接…"
                        } else {
                            dashboardState.value.taskPhase
                        }
                    )
                    connectServices()
                }
                dashboardState.value.running -> {
                    // A just-started Binder task may need a brief moment before running.env appears.
                    // Use the recovery poll instead of immediately restoring stale historical state.
                    startRecoveredTaskPoll()
                }
                else -> {
                    refreshAll()
                    if (runPendingActions) runPendingActionsIfReady()
                }
            }
        }
    }

    private suspend fun probeRemoteTask(): RemoteTaskProbe = withContext(Dispatchers.IO) {
        val expectProfile = rootService != null || profileBound
        val expectCache = cacheService != null || cacheBound
        var profileResponded = !expectProfile
        var cacheResponded = !expectCache
        var runningState: JSONObject? = null

        rootService?.let { service ->
            val state = runCatching { JSONObject(service.getTaskState()) }.getOrNull()
            if (state != null) {
                profileResponded = true
                if (state.optBoolean("running")) runningState = state
            }
        }
        cacheService?.let { service ->
            val state = runCatching { JSONObject(service.getTaskState()) }.getOrNull()
            if (state != null) {
                cacheResponded = true
                if (runningState == null && state.optBoolean("running")) runningState = state
            }
        }

        RemoteTaskProbe(
            complete = (expectProfile || expectCache) && profileResponded && cacheResponded,
            runningState = runningState
        )
    }

    private fun startRecoveredTaskPoll() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            var idleConfirmations = 0
            delay(250)
            while (isActive) {
                val probe = probeRemoteTask()
                val state = probe.runningState
                if (state != null) {
                    idleConfirmations = 0
                    renderTaskState(state)
                    delay(420)
                    continue
                }
                if (!probe.complete) {
                    idleConfirmations = 0
                    dashboardState.value = dashboardState.value.copy(
                        running = true,
                        connected = rootService != null,
                        ready = false,
                        serviceText = "后台任务连接中断，正在自动恢复…",
                        taskPhase = "后台任务仍由 Root 执行，正在重新连接进度…"
                    )
                    connectServices()
                    delay(700)
                    continue
                }

                idleConfirmations += 1
                if (idleConfirmations < 2) {
                    delay(320)
                    continue
                }

                dashboardState.value = dashboardState.value.copy(
                    running = false,
                    scanCompleted = false,
                    taskPhase = "后台任务已结束，正在读取最终结果…"
                )
                delay(220)
                refreshAll()
                updateStorage()
                break
            }
        }
    }

    private fun connectPrimaryService() {
"""
)

old_render = """    private fun renderTaskState(json: JSONObject) {
        val current = json.optInt("progress_current", json.optInt("current", 0))
        val total = json.optInt("progress_total", json.optInt("total", 0))
        val target = json.optString("current_path", json.optString("currentPath")).trim()
        val targetText = when {
            looksLikePackageName(target) -> "${appLabel(target)} · $target"
            target.isNotBlank() -> target.takeLast(72)
            else -> ""
        }
        val text = buildString {
            append(json.optString("phase", "任务执行中"))
            if (total > 0) append(" · $current/$total")
            if (targetText.isNotBlank()) append("\n").append(targetText)
            if (json.optBoolean("cancelRequested")) append("\n正在停止…")
        }
        dashboardState.value = dashboardState.value.copy(taskPhase = text)
    }
"""
new_render = """    private fun renderTaskState(json: JSONObject) {
        if (!json.optBoolean("running")) return
        val current = json.optInt("progress_current", json.optInt("current", 0))
        val total = json.optInt("progress_total", json.optInt("total", 0))
        val target = json.optString("current_path", json.optString("currentPath")).trim()
        val targetText = when {
            looksLikePackageName(target) -> "${appLabel(target)} · $target"
            target.isNotBlank() -> target.takeLast(72)
            else -> ""
        }
        val text = buildString {
            append(json.optString("phase", "任务执行中"))
            if (total > 0) append(" · $current/$total")
            if (targetText.isNotBlank()) append("\n").append(targetText)
            if (json.optBoolean("cancelRequested")) append("\n正在停止…")
            append("\n可切到后台，Root 会继续执行")
        }
        dashboardState.value = dashboardState.value.copy(
            running = true,
            scanCompleted = false,
            taskPhase = text
        )
    }
"""
rep(old_render, new_render)

rep(
    """            val latestReleased = if (latestMode.endsWith("scan") || latestMode == "scan") {
                preferences.getLong("last_clean_bytes", dashboardState.value.lastReleased)
            } else {
                latest.optLong("bytes", preferences.getLong("last_clean_bytes", 0L)).coerceAtLeast(0L)
            }
            dashboardState.value = dashboardState.value.copy(
""",
    """            val latestReleased = if (latestMode.endsWith("scan") || latestMode == "scan") {
                preferences.getLong("last_clean_bytes", dashboardState.value.lastReleased)
            } else {
                latest.optLong("bytes", preferences.getLong("last_clean_bytes", 0L)).coerceAtLeast(0L)
            }
            val latestTaskText = buildString {
                val result = latest.optString("result").trim()
                if (result.isNotBlank()) append(result)
                val files = latest.optLong("files", latest.optLong("regular_files", 0L)).coerceAtLeast(0L)
                val errors = latest.optLong("errors", 0L).coerceAtLeast(0L)
                val elapsed = latest.optLong("elapsed", 0L).coerceAtLeast(0L)
                if (files > 0L || errors > 0L || elapsed > 0L) {
                    if (isNotEmpty()) append("\n")
                    append("文件 ").append(files)
                    if (errors > 0L) append(" · 异常 ").append(errors)
                    if (elapsed > 0L) append(" · ").append(formatElapsed(elapsed))
                }
            }.ifBlank { dashboardState.value.taskPhase }
            dashboardState.value = dashboardState.value.copy(
"""
)

rep(
    """                recentApps = if (appDetails.isNotEmpty()) appDetails else dashboardState.value.recentApps,
                recentJunk = if (otherDetails.isNotEmpty()) otherDetails else dashboardState.value.recentJunk,
                scanPerformance = ScanPerformanceUiState(
""",
    """                recentApps = if (appDetails.isNotEmpty()) appDetails else dashboardState.value.recentApps,
                recentJunk = if (otherDetails.isNotEmpty()) otherDetails else dashboardState.value.recentJunk,
                taskPhase = if (dashboardState.value.running) dashboardState.value.taskPhase else latestTaskText,
                scanPerformance = ScanPerformanceUiState(
"""
)

rep(
    """        pollJob?.cancel()
        if (profileBound) runCatching { RootService.unbind(profileConnection) }
""",
    """        pollJob?.cancel()
        recoveryProbeJob?.cancel()
        if (profileBound) runCatching { RootService.unbind(profileConnection) }
"""
)

activity_path.write_text(text)

build = root / "v2/app/build.gradle.kts"
build_text = build.read_text().replace(
    'versionCode = 22603\n        versionName = "2.2.3"',
    'versionCode = 22604\n        versionName = "2.2.4"'
)
if 'versionName = "2.2.4"' not in build_text:
    raise SystemExit("v2.2.4 build version anchor missing")
build.write_text(build_text)

(root / "v2/module/module.prop").write_text(
    "id=baize_v2\n"
    "name=白泽 v2\n"
    "version=v2.2.4\n"
    "versionCode=22604\n"
    "author=惜故里丶\n"
    "description=白泽 v2.2.4 测试版：Root 后台任务持续执行，App 重进自动恢复真实进度与最终结果。\n"
)

package = root / "v2/scripts/package-module.sh"
package_text = package.read_text().replace("v2.2.3", "v2.2.4").replace("22603", "22604")
package.write_text(package_text)

(root / "RELEASE_NOTES_V2.2.4.md").write_text(
    "# 白泽 v2.2.4 后台任务恢复测试版\n\n"
    "- 手动智能清理切到后台后继续由 Root 守护进程执行。\n"
    "- App 被系统回收或重新进入时，先查询 Root 与缓存引擎的真实任务状态。\n"
    "- 正在运行时恢复实时阶段、当前路径和计数，不再显示上一次任务进度。\n"
    "- 后台任务结束后自动读取最新清理结果、记录和存储空间。\n"
    "- Root 连接短暂中断时保持任务运行状态并自动重连，避免误判任务结束。\n"
)

print("v2.2.4 background task recovery applied")
