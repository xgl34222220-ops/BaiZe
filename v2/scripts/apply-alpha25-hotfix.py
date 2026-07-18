#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"Patch target not found: {target}\n--- expected ---\n{old}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "v2/app/src/main/AndroidManifest.xml",
    '''        <service
            android:name=".root.BaiZeProfileRootService"
            android:exported="false" />''',
    '''        <service
            android:name=".root.BaiZeRootService"
            android:exported="false" />
        <service
            android:name=".root.BaiZeProfileRootService"
            android:exported="false" />''',
)

activity = "v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt"
replace_once(
    activity,
    '''    private fun runPendingCleanIfReady() {
        if (!pendingClean || rootService == null || cacheService == null) return
        pendingClean = false
        runSmartClean()
    }''',
    '''    private fun runPendingCleanIfReady() {
        if (!pendingClean) return
        val snapshotsReady = hasUsableScanSnapshots()
        val requiredEnginesReady = if (snapshotsReady) {
            (cacheSnapshotId.isBlank() || cacheSnapshotCount <= 0 || cacheService != null) &&
                (safeSnapshotId.isBlank() || safeSnapshotCount <= 0 || rootService != null)
        } else {
            rootService != null && cacheService != null
        }
        if (!requiredEnginesReady) return
        pendingClean = false
        runSmartClean()
    }''',
)
replace_once(
    activity,
    '''    private fun cleanNativeSnapshots() {
        val cache = cacheService ?: return toast("应用缓存引擎尚未连接")
        val profiles = rootService ?: return toast("分类引擎尚未连接")
        if (dashboardState.value.running) return
        if (!hasUsableScanSnapshots()) {
            dashboardState.value = dashboardState.value.copy(
                scanCompleted = false,
                taskPhase = "没有可用扫描快照，请先执行安全扫描"
            )
            toast("扫描结果已失效，请重新扫描")
            return
        }
        if (schedulerState.value.notifyOnComplete) requestNotificationPermission()''',
    '''    private fun cleanNativeSnapshots() {
        if (dashboardState.value.running) return
        if (!hasUsableScanSnapshots()) {
            dashboardState.value = dashboardState.value.copy(
                scanCompleted = false,
                taskPhase = "没有可用扫描快照，请先执行安全扫描"
            )
            toast("扫描结果已失效，请重新扫描")
            return
        }
        val needsCacheEngine = cacheSnapshotId.isNotBlank() && cacheSnapshotCount > 0
        val needsProfileEngine = safeSnapshotId.isNotBlank() && safeSnapshotCount > 0
        val cacheEngine = cacheService
        val profileEngine = rootService
        if ((needsCacheEngine && cacheEngine == null) || (needsProfileEngine && profileEngine == null)) {
            pendingClean = true
            dashboardState.value = dashboardState.value.copy(
                connected = false,
                ready = false,
                serviceText = "扫描快照仍有效，正在重连缺失的 Root 引擎…",
                taskPhase = "等待引擎重连后继续按扫描结果清理"
            )
            connectServices()
            toast("扫描快照仍有效，正在重连缺失引擎")
            return
        }
        if (schedulerState.value.notifyOnComplete) requestNotificationPermission()''',
)
replace_once(
    activity,
    "JSONObject(cache.cleanSelected(cacheSnapshotId, selection, whitelist))",
    "JSONObject(requireNotNull(cacheEngine).cleanSelected(cacheSnapshotId, selection, whitelist))",
)
replace_once(
    activity,
    "JSONObject(profiles.cleanProfileSelected(safeSnapshotId, selection, optionsJson()))",
    "JSONObject(requireNotNull(profileEngine).cleanProfileSelected(safeSnapshotId, selection, optionsJson()))",
)

ui = "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt"
replace_once(
    ui,
    "import androidx.compose.foundation.lazy.items",
    "import androidx.compose.foundation.lazy.items\nimport androidx.compose.foundation.lazy.rememberLazyListState",
)
replace_once(
    ui,
    "import androidx.compose.runtime.Composable",
    "import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.LaunchedEffect",
)
replace_once(
    ui,
    '''    val context = LocalContext.current
    val accentGradient = rememberAccentGradient()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 126.dp),''',
    '''    val context = LocalContext.current
    val accentGradient = rememberAccentGradient()
    val listState = rememberLazyListState()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LaunchedEffect(state.scanCompleted) {
        if (state.scanCompleted) listState.animateScrollToItem(5)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 154.dp),''',
)
replace_once(ui, "原生快照清理引擎 · Alpha 24", "原生快照清理引擎 · Alpha 25")
replace_once(
    ui,
    'Text(if (state.running) "清理任务执行中" else "清理引擎已连接", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Black)',
    '''Text(
                    when {
                        state.running -> "清理任务执行中"
                        state.ready -> "清理引擎已就绪"
                        state.connected -> "清理引擎已连接"
                        else -> "正在连接清理引擎"
                    },
                    color = Color.White,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Black
                )''',
)
replace_once(ui, "enabled = state.connected,", "enabled = state.running || state.ready,")
replace_once(
    ui,
    "Icon(Icons.Rounded.CheckCircle, null, tint = Color.White, modifier = Modifier.size(34.dp))",
    '''Icon(
                        if (state.ready) Icons.Rounded.CheckCircle else Icons.Rounded.Refresh,
                        null,
                        tint = Color.White.copy(alpha = if (state.ready) 1f else .78f),
                        modifier = Modifier.size(34.dp)
                    )''',
)
replace_once(
    ui,
    'Text(if (ready) "运行正常" else "待检查", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)',
    'Text(if (ready) "运行正常" else "未就绪", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)',
)

for path, pairs in {
    "v2/app/build.gradle.kts": [
        ("versionCode = 20400", "versionCode = 20500"),
        ('versionName = "2.0.0-alpha24"', 'versionName = "2.0.0-alpha25"'),
    ],
    "v2/module/module.prop": [
        ("version=v2.0.0-alpha24", "version=v2.0.0-alpha25"),
        ("versionCode=20400", "versionCode=20500"),
    ],
    "v2/module/customize.sh": [("白泽 v2 Alpha 24", "白泽 v2 Alpha 25")],
    "v2/scripts/package-module.sh": [
        ("BaiZe-v2-Alpha24-Module.zip", "BaiZe-v2-Alpha25-Module.zip"),
        ("Alpha 24", "Alpha 25"),
    ],
    "v2/README.md": [
        ("# 白泽 v2 Alpha 24", "# 白泽 v2 Alpha 25"),
        ("## Alpha 24", "## Alpha 25"),
        ("BaiZe-v2-Alpha24-Module.zip", "BaiZe-v2-Alpha25-Module.zip"),
    ],
}.items():
    for old, new in pairs:
        replace_once(path, old, new)

changes = Path("v2/ALPHA25-CHANGES.md")
if not changes.exists():
    changes.write_text(
        """# Alpha 25 改动摘要

- 补全 `BaiZeRootService` 的 Manifest 声明，修复应用缓存引擎无法稳定绑定的问题。
- 扫描结果清理只要求对应快照所属的引擎在线；单引擎快照也可直接清理，不再错误提示另一引擎未连接。
- 必需引擎短暂断开时保留 30 分钟快照，自动重连后继续清理，绝不重新全盘扫描。
- 首页连接标题与真实状态同步；引擎未就绪时不再显示“清理引擎已连接”。
- 扫描完成后自动定位结果卡片，并增加动态底部安全间距，避免悬浮导航遮挡按钮与说明。
""",
        encoding="utf-8",
    )
