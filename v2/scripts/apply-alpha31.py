#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing patch target in {path}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_text(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing text in {path}: {old!r}")
    path.write_text(text.replace(old, new), encoding="utf-8")


activity = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt"
ui = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt"

replace_once(
    activity,
    """    private var pendingClean = false
    private var pendingModuleTask: String? = null
""",
    """    private var pendingSmartClean = false
    private var pendingSnapshotClean = false
    private var pendingScanAfterConnect: Boolean? = null
    private var pendingModuleTask: String? = null
""",
)

replace_once(
    activity,
    """            updateConnectionState()
            refreshAll()
            runPendingCleanIfReady()
            runPendingModuleTaskIfReady()
""",
    """            updateConnectionState()
            refreshAll()
            runPendingActionsIfReady()
""",
)

replace_once(
    activity,
    """        override fun onServiceDisconnected(name: ComponentName?) {
            rootService = null
            profileBound = false
            pollJob?.cancel()
            updateConnectionState()
        }
""",
    """        override fun onServiceDisconnected(name: ComponentName?) {
            rootService = null
            profileBound = false
            pollJob?.cancel()
            updateConnectionState()
            scheduleServiceRecovery(
                requireCache = pendingScanAfterConnect != null || pendingSnapshotClean || safeSnapshotId.isNotBlank()
            )
        }
""",
)

replace_once(
    activity,
    """            updateConnectionState()
            readServiceStatus()
            runPendingCleanIfReady()
""",
    """            updateConnectionState()
            readServiceStatus()
            runPendingActionsIfReady()
""",
)

replace_once(
    activity,
    """        override fun onServiceDisconnected(name: ComponentName?) {
            cacheService = null
            cacheBound = false
            pollJob?.cancel()
            updateConnectionState()
        }
""",
    """        override fun onServiceDisconnected(name: ComponentName?) {
            cacheService = null
            cacheBound = false
            pollJob?.cancel()
            updateConnectionState()
            if (pendingScanAfterConnect != null || pendingSnapshotClean || cacheSnapshotId.isNotBlank()) {
                scheduleServiceRecovery(requireCache = true)
            }
        }
""",
)

replace_once(
    activity,
    """        pendingClean = intent.getBooleanExtra(EXTRA_RUN_SMART_CLEAN, false)
""",
    """        pendingSmartClean = intent.getBooleanExtra(EXTRA_RUN_SMART_CLEAN, false)
""",
)

replace_once(
    activity,
    """            } else if (rootService == null) {
                pendingClean = true
                connectPrimaryService()
""",
    """            } else if (rootService == null) {
                pendingSmartClean = true
                connectPrimaryService()
""",
)

replace_once(
    activity,
    """    private fun updateConnectionState() {
        val primaryConnected = rootService != null
        dashboardState.value = dashboardState.value.copy(
            connected = primaryConnected,
            ready = if (primaryConnected) dashboardState.value.ready else false,
            running = if (primaryConnected) dashboardState.value.running else false,
            serviceText = if (primaryConnected) {
                "Root 清理服务已连接，正在校验模块组件…"
            } else {
                "正在连接 Root 清理服务…"
            }
        )
    }

    private fun runPendingCleanIfReady() {
        if (!pendingClean || rootService == null) return
        pendingClean = false
        runSmartClean()
    }
""",
    """    private fun updateConnectionState() {
        val primaryConnected = rootService != null
        val scanReady = dashboardState.value.scanCompleted && hasUsableScanSnapshots()
        dashboardState.value = dashboardState.value.copy(
            connected = primaryConnected,
            ready = if (primaryConnected) dashboardState.value.ready else false,
            serviceText = when {
                primaryConnected -> "Root 清理服务已连接，正在校验模块组件…"
                scanReady -> "扫描快照已就绪，清理时会自动恢复 Root 服务"
                profileBound -> "正在连接 Root 清理服务…"
                else -> "Root 清理服务已断开，正在自动恢复…"
            }
        )
    }

    private fun runPendingActionsIfReady() {
        if (dashboardState.value.running) return

        if (pendingSnapshotClean) {
            val needsCache = cacheSnapshotId.isNotBlank() && cacheSnapshotCount > 0
            val needsProfile = safeSnapshotId.isNotBlank() && safeSnapshotCount > 0
            val cacheReady = !needsCache || cacheService != null
            val profileReady = !needsProfile || rootService != null
            if (cacheReady && profileReady) {
                pendingSnapshotClean = false
                cleanNativeSnapshots()
                return
            }
        }

        val cleanAfterScan = pendingScanAfterConnect
        if (cleanAfterScan != null && rootService != null && cacheService != null) {
            pendingScanAfterConnect = null
            runNativeScan(cleanAfterScan)
            return
        }

        if (pendingSmartClean && rootService != null) {
            pendingSmartClean = false
            runSmartClean()
            return
        }

        val mode = pendingModuleTask
        if (mode != null && rootService != null) {
            pendingModuleTask = null
            runModuleUtilityTask(requireNotNull(rootService), mode)
        }
    }

    private fun scheduleServiceRecovery(requireCache: Boolean) {
        lifecycleScope.launch {
            delay(350)
            if (isFinishing || isDestroyed) return@launch
            if (requireCache) connectServices() else connectPrimaryService()
        }
    }
""",
)

replace_once(
    activity,
    """    private fun runPendingModuleTaskIfReady() {
        val mode = pendingModuleTask ?: return
        val service = rootService ?: return
        pendingModuleTask = null
        runModuleUtilityTask(service, mode)
    }

""",
    "",
)

replace_once(
    activity,
    """            pendingClean = true
            dashboardState.value = dashboardState.value.copy(
                connected = false,
                ready = false,
                taskPhase = "正在连接 Root 清理服务，连接成功后继续清理"
            )
""",
    """            pendingSmartClean = true
            dashboardState.value = dashboardState.value.copy(
                connected = false,
                ready = false,
                taskPhase = "正在连接 Root 清理服务，连接成功后继续清理"
            )
""",
)

replace_once(
    activity,
    """        val cache = cacheService ?: run {
            pendingClean = cleanAfterScan
            connectServices()
            return
        }
        val profiles = rootService ?: run {
            pendingClean = cleanAfterScan
            connectServices()
            return
        }
""",
    """        val cache = cacheService
        val profiles = rootService
        if (cache == null || profiles == null) {
            pendingScanAfterConnect = cleanAfterScan
            dashboardState.value = dashboardState.value.copy(
                connected = rootService != null,
                ready = false,
                taskPhase = "正在连接扫描引擎，连接后自动继续垃圾扫描"
            )
            connectServices()
            return
        }
""",
)

replace_once(
    activity,
    """            pendingClean = true
            dashboardState.value = dashboardState.value.copy(
                connected = false,
                ready = false,
                serviceText = "扫描快照仍有效，正在重连缺失的 Root 引擎…",
                taskPhase = "等待引擎重连后继续按扫描结果清理"
            )
""",
    """            pendingSnapshotClean = true
            dashboardState.value = dashboardState.value.copy(
                connected = rootService != null,
                ready = false,
                serviceText = "扫描快照仍有效，正在重连缺失的 Root 引擎…",
                taskPhase = "等待引擎重连后继续按扫描结果清理"
            )
""",
)

replace_once(
    activity,
    """    private fun clearScanResult() {
        clearSnapshotHandles()
        dashboardState.value = dashboardState.value.copy(scanCompleted = false)
    }
""",
    """    private fun clearScanResult() {
        pendingSnapshotClean = false
        clearSnapshotHandles()
        dashboardState.value = dashboardState.value.copy(scanCompleted = false)
    }
""",
)

replace_once(
    ui,
    """    val listState = rememberLazyListState()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LaunchedEffect(state.scanCompleted) {
        if (state.scanCompleted) listState.animateScrollToItem(5)
    }
    LazyColumn(
        state = listState,
""",
    """    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
""",
)

replace_once(
    ui,
    """        item { PageHeader("SMART CLEAN", "白泽", "原生清理引擎 · Alpha 30", actions.refresh) }
""",
    """        item { PageHeader("SMART CLEAN", "白泽", "原生清理引擎 · Alpha 31", actions.refresh) }
""",
)

replace_once(
    ui,
    """                        Box(Modifier.size(11.dp).clip(CircleShape).background(if (state.ready) Color(0xFF83F0C0) else Color(0xFFFFD27D)))
""",
    """                        Box(Modifier.size(11.dp).clip(CircleShape).background(if (state.ready || state.scanCompleted) Color(0xFF83F0C0) else Color(0xFFFFD27D)))
""",
)

replace_once(
    ui,
    """                        when {
                            state.running -> "清理任务执行中"
                            state.ready -> "清理引擎已就绪"
                            state.connected -> "清理引擎已连接"
                            else -> "正在连接清理引擎"
                        },
""",
    """                        when {
                            state.running -> "清理任务执行中"
                            state.scanCompleted -> "扫描结果已就绪"
                            state.ready -> "清理引擎已就绪"
                            state.connected -> "清理引擎已连接"
                            else -> "正在恢复清理引擎"
                        },
""",
)

replace_once(
    ui,
    """                            if (state.ready) Icons.Rounded.CheckCircle else Icons.Rounded.Refresh,
                            null,
                            tint = Color.White.copy(alpha = if (state.ready) 1f else .78f),
""",
    """                            if (state.ready || state.scanCompleted) Icons.Rounded.CheckCircle else Icons.Rounded.Refresh,
                            null,
                            tint = Color.White.copy(alpha = if (state.ready || state.scanCompleted) 1f else .78f),
""",
)

replace_once(
    ui,
    """            StatusPill(state.ready, state.serviceText)
""",
    """            StatusPill(
                ready = state.ready,
                scanReady = state.scanCompleted,
                text = if (state.scanCompleted && !state.ready) {
                    "扫描快照已就绪；点击下方按钮时自动恢复 Root 服务"
                } else {
                    state.serviceText
                }
            )
""",
)

replace_once(
    ui,
    """private fun StatusPill(ready: Boolean, text: String) {
""",
    """private fun StatusPill(ready: Boolean, text: String, scanReady: Boolean = false) {
""",
)

replace_once(
    ui,
    """            Box(Modifier.size(10.dp).clip(CircleShape).background(if (ready) SuccessGreen else Color(0xFFF2A93B)))
            Spacer(Modifier.width(10.dp))
            Text(text, modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (ready) "运行正常" else "未就绪", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
""",
    """            val positive = ready || scanReady
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (positive) SuccessGreen else Color(0xFFF2A93B)))
            Spacer(Modifier.width(10.dp))
            Text(text, modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                when {
                    ready -> "运行正常"
                    scanReady -> "快照就绪"
                    else -> "未就绪"
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary
            )
""",
)

replace_text(ROOT / "v2/app/build.gradle.kts", 'versionCode = 21000', 'versionCode = 21100')
replace_text(ROOT / "v2/app/build.gradle.kts", 'versionName = "2.0.0-alpha30"', 'versionName = "2.0.0-alpha31"')
replace_text(ROOT / "v2/module/module.prop", 'version=v2.0.0-alpha30', 'version=v2.0.0-alpha31')
replace_text(ROOT / "v2/module/module.prop", 'versionCode=21000', 'versionCode=21100')
replace_text(ROOT / "v2/module/customize.sh", 'Alpha 30', 'Alpha 31')
replace_text(ROOT / "v2/scripts/package-module.sh", 'Alpha30', 'Alpha31')
replace_text(ROOT / "v2/scripts/package-module.sh", 'Alpha 30', 'Alpha 31')

readme = ROOT / "v2/README.md"
text = readme.read_text(encoding="utf-8")
text = text.replace("# 白泽 v2 Alpha 30", "# 白泽 v2 Alpha 31", 1)
text = text.replace("当前开发分支：`v2-alpha30`。", "当前开发分支：`v2-alpha31`。", 1)
marker = "当前开发分支：`v2-alpha31`。\n"
section = """

## Alpha 31

- 修复扫描完成后首页被强制滚动、顶部标题只剩半截的问题。
- 普通清理、垃圾扫描和快照清理使用独立待执行状态，不再在 Root 重连后误走完整清理。
- 扫描引擎首次连接完成后自动继续原扫描，无需重复点击。
- 扫描快照有效时首页固定显示“扫描结果已就绪”和“快照就绪”，不再与“正在连接/未就绪”互相矛盾。
- RootService 断开后自动恢复连接，同时保留有效快照和正在执行的任务状态。
"""
if marker in text and "## Alpha 31" not in text:
    text = text.replace(marker, marker + section, 1)
readme.write_text(text, encoding="utf-8")

print("Alpha 31 state-machine hotfix applied")
