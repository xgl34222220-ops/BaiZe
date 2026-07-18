#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"Patch target not found in {path}:\n{old[:500]}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_between(path: str, start: str, end: str, replacement: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    start_index = text.find(start)
    if start_index < 0:
        if replacement in text:
            return
        raise SystemExit(f"Start marker not found in {path}: {start}")
    end_index = text.find(end, start_index)
    if end_index < 0:
        raise SystemExit(f"End marker not found in {path}: {end}")
    target.write_text(text[:start_index] + replacement + text[end_index:], encoding="utf-8")


activity = "v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt"

replace_once(
    activity,
    '''    private var profileBound = false
    private var cacheBound = false
    private var pendingClean = false
    private var pollJob: Job? = null
    private var cacheSnapshotId = ""
    private var safeSnapshotId = ""
    private var cacheSnapshotCount = 0
    private var safeSnapshotCount = 0''',
    '''    private var profileBound = false
    private var cacheBound = false
    private var profileBinding = false
    private var cacheBinding = false
    private var destroyed = false
    private var reconnectAttempt = 0
    private var pendingClean = false
    private var pollJob: Job? = null
    private var reconnectJob: Job? = null
    private var bindWatchdogJob: Job? = null
    private var pendingActionJob: Job? = null
    private var cacheSnapshotId = ""
    private var safeSnapshotId = ""
    private var cacheSnapshotCount = 0
    private var safeSnapshotCount = 0''',
)

replace_between(
    activity,
    "    private val profileConnection = object : ServiceConnection {",
    "    private val cacheConnection = object : ServiceConnection {",
    '''    private val profileConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            profileBinding = false
            profileBound = true
            reconnectAttempt = 0
            rootService = IProfileRootService.Stub.asInterface(binder)
            updateConnectionState()
            refreshAll()
            runPendingCleanIfReady()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handleServiceLoss(profile = true, reason = "分类 Root 引擎已断开")
        }

        override fun onBindingDied(name: ComponentName?) {
            handleServiceLoss(profile = true, reason = "分类 Root 引擎绑定失效")
        }

        override fun onNullBinding(name: ComponentName?) {
            handleServiceLoss(profile = true, reason = "分类 Root 引擎没有返回 Binder")
        }
    }

''',
)

replace_between(
    activity,
    "    private val cacheConnection = object : ServiceConnection {",
    "    override fun onCreate(savedInstanceState: Bundle?) {",
    '''    private val cacheConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            cacheBinding = false
            cacheBound = true
            reconnectAttempt = 0
            cacheService = IBaiZeRootService.Stub.asInterface(binder)
            updateConnectionState()
            readServiceStatus()
            runPendingCleanIfReady()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handleServiceLoss(profile = false, reason = "应用缓存 Root 引擎已断开")
        }

        override fun onBindingDied(name: ComponentName?) {
            handleServiceLoss(profile = false, reason = "应用缓存 Root 引擎绑定失效")
        }

        override fun onNullBinding(name: ComponentName?) {
            handleServiceLoss(profile = false, reason = "应用缓存 Root 引擎没有返回 Binder")
        }
    }

''',
)

replace_once(
    activity,
    '''    override fun onResume() {
        super.onResume()
        updateStorage()
        if (rootService != null) refreshAll()
    }''',
    '''    override fun onResume() {
        super.onResume()
        updateStorage()
        if (rootService != null) refreshAll()
        if (rootService == null || cacheService == null) scheduleReconnect(immediate = true)
    }''',
)

replace_between(
    activity,
    "    private fun connectServices() {",
    "    private fun runPendingCleanIfReady() {",
    '''    private fun connectServices(force: Boolean = false) {
        if (destroyed) return
        if (force) releaseConnections()

        val anyConnected = rootService != null || cacheService != null
        dashboardState.value = dashboardState.value.copy(
            connected = anyConnected,
            ready = false,
            serviceText = when {
                rootService != null && cacheService == null -> "分类引擎已连接，正在恢复应用缓存引擎…"
                cacheService != null && rootService == null -> "应用缓存引擎已连接，正在恢复分类引擎…"
                else -> "正在连接 Root 清理引擎…"
            }
        )

        bindProfileIfNeeded()
        bindCacheIfNeeded()
        startBindWatchdog()
    }

    private fun bindProfileIfNeeded() {
        if (destroyed || rootService != null || profileBinding) return
        profileBinding = true
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java)
                    .addCategory(RootService.CATEGORY_DAEMON_MODE),
                profileConnection
            )
        }.onFailure {
            profileBinding = false
            profileBound = false
            dashboardState.value = dashboardState.value.copy(
                connected = cacheService != null,
                ready = false,
                serviceText = "分类引擎启动失败，正在重试：${it.message.orEmpty()}"
            )
            scheduleReconnect()
        }
    }

    private fun bindCacheIfNeeded() {
        if (destroyed || cacheService != null || cacheBinding) return
        cacheBinding = true
        runCatching {
            RootService.bind(
                Intent(this, BaiZeRootService::class.java)
                    .addCategory(RootService.CATEGORY_DAEMON_MODE),
                cacheConnection
            )
        }.onFailure {
            cacheBinding = false
            cacheBound = false
            dashboardState.value = dashboardState.value.copy(
                connected = rootService != null,
                ready = false,
                serviceText = "应用缓存引擎启动失败，正在重试：${it.message.orEmpty()}"
            )
            scheduleReconnect()
        }
    }

    private fun startBindWatchdog() {
        bindWatchdogJob?.cancel()
        bindWatchdogJob = lifecycleScope.launch {
            delay(7_000)
            var timedOut = false
            if (rootService == null && profileBinding) {
                runCatching { RootService.unbind(profileConnection) }
                profileBinding = false
                profileBound = false
                timedOut = true
            }
            if (cacheService == null && cacheBinding) {
                runCatching { RootService.unbind(cacheConnection) }
                cacheBinding = false
                cacheBound = false
                timedOut = true
            }
            if (timedOut && !destroyed) {
                dashboardState.value = dashboardState.value.copy(
                    connected = rootService != null || cacheService != null,
                    ready = false,
                    serviceText = "Root 引擎连接超时，正在自动重试…"
                )
                scheduleReconnect(immediate = true)
            }
        }
    }

    private fun handleServiceLoss(profile: Boolean, reason: String) {
        if (profile) {
            rootService = null
            profileBound = false
            profileBinding = false
        } else {
            cacheService = null
            cacheBound = false
            cacheBinding = false
        }
        val wasRunning = dashboardState.value.running
        pollJob?.cancel()
        dashboardState.value = dashboardState.value.copy(
            connected = rootService != null || cacheService != null,
            ready = false,
            running = false,
            serviceText = "$reason，正在自动恢复…",
            taskPhase = if (wasRunning) "任务连接中断，正在恢复 Root 服务；已有扫描快照不会重新扫描" else dashboardState.value.taskPhase
        )
        if (!destroyed) scheduleReconnect()
    }

    private fun scheduleReconnect(immediate: Boolean = false) {
        if (destroyed || (rootService != null && cacheService != null)) return
        reconnectJob?.cancel()
        val shift = reconnectAttempt.coerceIn(0, 3)
        val delayMs = if (immediate) 0L else (600L * (1 shl shift)).coerceAtMost(4_800L)
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(8)
        reconnectJob = lifecycleScope.launch {
            delay(delayMs)
            if (!destroyed && (rootService == null || cacheService == null)) connectServices()
        }
    }

    private fun releaseConnections() {
        bindWatchdogJob?.cancel()
        pendingActionJob?.cancel()
        if (profileBound || profileBinding) runCatching { RootService.unbind(profileConnection) }
        if (cacheBound || cacheBinding) runCatching { RootService.unbind(cacheConnection) }
        rootService = null
        cacheService = null
        profileBound = false
        cacheBound = false
        profileBinding = false
        cacheBinding = false
    }

    private fun reconnectService() {
        reconnectJob?.cancel()
        reconnectAttempt = 0
        releaseConnections()
        dashboardState.value = dashboardState.value.copy(
            connected = false,
            ready = false,
            running = false,
            serviceText = "正在重新连接 Root 清理引擎…"
        )
        connectServices()
        toast("正在重新连接 Root 清理引擎")
    }

    private fun updateConnectionState() {
        val profileReady = rootService != null
        val cacheReady = cacheService != null
        val both = profileReady && cacheReady
        if (both) {
            bindWatchdogJob?.cancel()
            reconnectJob?.cancel()
            reconnectAttempt = 0
        } else if (!destroyed) {
            scheduleReconnect()
        }
        dashboardState.value = dashboardState.value.copy(
            connected = profileReady || cacheReady,
            ready = if (both) dashboardState.value.ready else false,
            serviceText = when {
                both -> "双 Root 快照引擎已连接，正在校验模块组件…"
                profileReady -> "分类引擎已连接，正在恢复应用缓存引擎…"
                cacheReady -> "应用缓存引擎已连接，正在恢复分类引擎…"
                else -> "正在连接 Root 清理引擎…"
            }
        )
    }

''',
)

replace_between(
    activity,
    "    private fun runPendingCleanIfReady() {",
    "    private fun readServiceStatus() {",
    '''    private fun runPendingCleanIfReady() {
        if (!pendingClean) return
        val snapshotsReady = hasUsableScanSnapshots()
        if (snapshotsReady) {
            val requiredEnginesReady =
                (cacheSnapshotId.isBlank() || cacheSnapshotCount <= 0 || cacheService != null) &&
                    (safeSnapshotId.isBlank() || safeSnapshotCount <= 0 || rootService != null)
            if (!requiredEnginesReady) return
            pendingActionJob?.cancel()
            pendingClean = false
            runSmartClean()
            return
        }

        val both = rootService != null && cacheService != null
        val any = rootService != null || cacheService != null
        if (both) {
            pendingActionJob?.cancel()
            pendingClean = false
            runSmartClean()
        } else if (any) {
            pendingActionJob?.cancel()
            pendingActionJob = lifecycleScope.launch {
                delay(1_800)
                if (pendingClean && (rootService != null || cacheService != null)) {
                    pendingClean = false
                    runSmartClean()
                }
            }
            scheduleReconnect(immediate = true)
        }
    }

''',
)

replace_once(
    activity,
    '''            dashboardState.value = dashboardState.value.copy(
                connected = cacheReady,
                ready = ready,''',
    '''            dashboardState.value = dashboardState.value.copy(
                connected = rootService != null || cacheReady,
                ready = ready,''',
)

replace_once(
    activity,
    '''    private fun runSmartClean() {
        if (schedulerState.value.notifyOnComplete) requestNotificationPermission()
        if (hasUsableScanSnapshots()) cleanNativeSnapshots() else runNativeScan(cleanAfterScan = true)
    }''',
    '''    private fun runSmartClean() {
        if (schedulerState.value.notifyOnComplete) requestNotificationPermission()
        if (rootService == null && cacheService == null) {
            pendingClean = true
            dashboardState.value = dashboardState.value.copy(
                connected = false,
                ready = false,
                taskPhase = "正在连接 Root 引擎，连接成功后继续清理"
            )
            connectServices()
            toast("正在连接 Root 引擎，稍后会自动继续")
            return
        }
        if (hasUsableScanSnapshots()) cleanNativeSnapshots() else runNativeScan(cleanAfterScan = true)
    }''',
)

replace_between(
    activity,
    "    private fun runNativeScan(cleanAfterScan: Boolean) {",
    "    private fun cleanNativeSnapshots() {",
    '''    private fun runNativeScan(cleanAfterScan: Boolean) {
        if (schedulerState.value.notifyOnComplete) requestNotificationPermission()
        val cache = cacheService
        val profiles = rootService
        if (cache == null && profiles == null) {
            pendingClean = cleanAfterScan
            dashboardState.value = dashboardState.value.copy(
                connected = false,
                ready = false,
                taskPhase = "正在连接 Root 引擎，连接成功后继续扫描"
            )
            connectServices()
            return
        }
        if (dashboardState.value.running) return
        pendingClean = false
        pendingActionJob?.cancel()
        clearSnapshotHandles()
        val started = SystemClock.elapsedRealtime()
        dashboardState.value = dashboardState.value.copy(
            running = true,
            scanCompleted = false,
            taskPhase = when {
                cache != null && profiles != null -> "正在并行发现应用缓存与安全项目…"
                cache != null -> "分类引擎恢复中，先扫描应用缓存…"
                else -> "应用缓存引擎恢复中，先扫描安全项目…"
            }
        )
        startNativePoll()
        lifecycleScope.launch {
            val pair = runCatching {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val whitelist = JSONArray(packageWhitelist().toList()).toString()
                        val cacheJob = cache?.let { engine -> async { JSONObject(engine.scanCandidates(whitelist)) } }
                        val safeJob = profiles?.let { engine -> async { JSONObject(engine.scanProfile("safe", optionsJson())) } }
                        val cacheJson = cacheJob?.await() ?: JSONObject()
                            .put("error", "cache_engine_unavailable")
                            .put("message", "应用缓存引擎未连接")
                        val safeJson = safeJob?.await() ?: JSONObject()
                            .put("error", "profile_engine_unavailable")
                            .put("message", "分类引擎未连接")
                        cacheJson to safeJson
                    }
                }
            }
            pollJob?.cancel()
            if (pair.isFailure) {
                dashboardState.value = dashboardState.value.copy(
                    running = false,
                    taskPhase = "安全扫描失败：${pair.exceptionOrNull()?.message ?: "Root 服务异常"}"
                )
                scheduleReconnect()
                return@launch
            }

            val (cacheJson, safeJson) = pair.getOrThrow()
            val cacheOk = !cacheJson.has("error") && !cacheJson.optBoolean("cancelled")
            val safeOk = safeJson.optBoolean("success") && !safeJson.optBoolean("cancelled")
            if (cacheOk) {
                cacheSnapshotId = cacheJson.optString("snapshotId")
                cacheSnapshotCount = (cacheJson.optInt("totalCandidates") - cacheJson.optInt("whitelisted")).coerceAtLeast(0)
            }
            if (safeOk) {
                safeSnapshotId = safeJson.optString("snapshotId")
                safeSnapshotCount = (safeJson.optInt("low") + safeJson.optInt("medium")).coerceAtLeast(0)
            }
            val total = cacheSnapshotCount + safeSnapshotCount
            val knownBytes = safeJson.optLong("knownBytes", 0L).coerceAtLeast(0L)
            val emptyFiles = safeJson.optLong("emptyFiles", 0L).coerceAtLeast(0L)
            val emptyDirs = safeJson.optLong("emptyDirs", 0L).coerceAtLeast(0L)
            val fragments = safeJson.optLong("fragmentFiles", 0L).coerceAtLeast(0L)
            val failures = listOf(cacheOk, safeOk).count { !it }.toLong()
            val cancelled = cacheJson.optBoolean("cancelled") || safeJson.optBoolean("cancelled")
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
            val successfulScan = (cacheOk || safeOk) && !cancelled
            dashboardState.value = dashboardState.value.copy(
                running = false,
                scanCompleted = successfulScan,
                scanBytes = knownBytes,
                scanFiles = total.toLong(),
                scanEmptyFiles = emptyFiles,
                scanEmptyDirs = emptyDirs,
                scanFragments = fragments,
                scanErrors = failures,
                scanElapsed = elapsed / 1000L,
                taskPhase = when {
                    cancelled -> "安全扫描已停止"
                    !successfulScan -> "安全扫描失败：${safeJson.optString("message", cacheJson.optString("message", "引擎没有返回有效快照"))}"
                    failures > 0 && total > 0 -> "部分扫描完成，发现 $total 项；缺失引擎恢复后可再次扫描"
                    failures > 0 -> "部分扫描完成，当前在线引擎未发现可清理项目"
                    total == 0 -> "扫描完成，没有发现可安全清理的项目"
                    else -> "扫描完成，发现 $total 项；快照 30 分钟内有效"
                }
            )
            if (!cleanAfterScan) notifyScanResult(successfulScan, cancelled, total, knownBytes, emptyFiles, emptyDirs, fragments, elapsed)
            if (cleanAfterScan && successfulScan && total > 0) {
                cleanNativeSnapshots()
            } else if (cleanAfterScan && successfulScan) {
                notifyCleanResult(
                    "白泽智能清理完成",
                    if (failures > 0) "部分引擎在线，当前未发现可清理项目" else "没有发现可安全清理的项目",
                    "扫描一次完成 · 未执行删除",
                    0L
                )
            }
            if (rootService == null || cacheService == null) scheduleReconnect()
        }
    }

''',
)

replace_once(
    activity,
    '''    override fun onDestroy() {
        pollJob?.cancel()
        if (profileBound) runCatching { RootService.unbind(profileConnection) }
        if (cacheBound) runCatching { RootService.unbind(cacheConnection) }
        super.onDestroy()
    }''',
    '''    override fun onDestroy() {
        destroyed = true
        pollJob?.cancel()
        reconnectJob?.cancel()
        bindWatchdogJob?.cancel()
        pendingActionJob?.cancel()
        releaseConnections()
        super.onDestroy()
    }''',
)

ui = "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt"
replace_once(
    ui,
    '''    MaterialTheme(colorScheme = colors) {
        var page by rememberSaveable { mutableStateOf(BaiZePage.Home) }
        Box(modifier = Modifier.fillMaxSize()) {
            MiuiXBackdrop(dark)
            when (page) {
                BaiZePage.Home -> HomePage(state, actions)
                BaiZePage.Plan -> PlanPage(scheduler, actions)
                BaiZePage.Records -> RecordsPage(state, actions)
                BaiZePage.Settings -> SettingsPage(state, scheduler, actions)
            }
            FloatingDock(
                selected = page,
                onSelected = { page = it },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }''',
    '''    MaterialTheme(colorScheme = colors) {
        var page by rememberSaveable { mutableStateOf(BaiZePage.Home) }
        Box(modifier = Modifier.fillMaxSize()) {
            MiuiXBackdrop(dark)
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when (page) {
                        BaiZePage.Home -> HomePage(state, actions)
                        BaiZePage.Plan -> PlanPage(scheduler, actions)
                        BaiZePage.Records -> RecordsPage(state, actions)
                        BaiZePage.Settings -> SettingsPage(state, scheduler, actions)
                    }
                }
                FloatingDock(selected = page, onSelected = { page = it })
            }
        }
    }''',
)
replace_once(
    ui,
    '''    LaunchedEffect(state.scanCompleted) {
        if (state.scanCompleted) listState.animateScrollToItem(5)
    }''',
    '''    LaunchedEffect(state.scanCompleted) {
        if (state.scanCompleted) {
            listState.animateScrollToItem(3)
        } else if (listState.firstVisibleItemIndex > 0) {
            listState.animateScrollToItem(0)
        }
    }''',
)
replace_once(ui, "contentPadding = PaddingValues(bottom = bottomInset + 154.dp)", "contentPadding = PaddingValues(bottom = 24.dp)")
replace_once(ui, "原生快照清理引擎 · Alpha 25", "原生快照清理引擎 · Alpha 26")
replace_once(ui, "enabled = state.running || state.ready,", "enabled = true,")
replace_once(
    ui,
    '''                    Icon(if (state.running) Icons.Rounded.Stop else Icons.Rounded.AutoAwesome, null, tint = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text(if (state.running) "安全停止任务" else "立即智能清理", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)''',
    '''                    Icon(
                        when {
                            state.running -> Icons.Rounded.Stop
                            state.ready -> Icons.Rounded.AutoAwesome
                            else -> Icons.Rounded.Refresh
                        },
                        null,
                        tint = Color.White
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            state.running -> "安全停止任务"
                            state.ready -> "立即智能清理"
                            state.connected -> "恢复连接并清理"
                            else -> "连接 Root 并清理"
                        },
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )''',
)
replace_once(ui, "StatusPill(state.ready, state.serviceText)", "StatusPill(state.ready, state.serviceText, actions.reconnect)")
replace_once(
    ui,
    '''private fun StatusPill(ready: Boolean, text: String) {
    GlassSurface(
        Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        shadow = 5,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (ready) SuccessGreen else Color(0xFFF2A93B)))
            Spacer(Modifier.width(10.dp))
            Text(text, modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (ready) "运行正常" else "未就绪", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}''',
    '''private fun StatusPill(ready: Boolean, text: String, onReconnect: () -> Unit) {
    GlassSurface(
        Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        shadow = 5,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = if (ready) Modifier else Modifier.clickable(onClick = onReconnect),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (ready) SuccessGreen else Color(0xFFF2A93B)))
            Spacer(Modifier.width(10.dp))
            Text(text, modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (ready) "运行正常" else "点击重连", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}''',
)
replace_once(ui, "contentPadding = PaddingValues(bottom = 132.dp)", "contentPadding = PaddingValues(bottom = 24.dp)")
replace_once(ui, "contentPadding = PaddingValues(bottom = 130.dp)", "contentPadding = PaddingValues(bottom = 24.dp)")
replace_once(ui, "Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 132.dp)", "Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)")
replace_once(
    ui,
    '''    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    GlassSurface(
        modifier = modifier.padding(horizontal = 18.dp).padding(bottom = bottom + 10.dp).fillMaxWidth(),''',
    '''    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    GlassSurface(
        modifier = modifier.padding(horizontal = 18.dp).padding(bottom = bottom + 8.dp).fillMaxWidth(),''',
)

for path, replacements in {
    "v2/app/build.gradle.kts": [
        ("versionCode = 20500", "versionCode = 20600"),
        ('versionName = "2.0.0-alpha25"', 'versionName = "2.0.0-alpha26"'),
    ],
    "v2/module/module.prop": [
        ("version=v2.0.0-alpha25", "version=v2.0.0-alpha26"),
        ("versionCode=20500", "versionCode=20600"),
    ],
    "v2/module/customize.sh": [("白泽 v2 Alpha 25", "白泽 v2 Alpha 26")],
    "v2/scripts/package-module.sh": [
        ("BaiZe-v2-Alpha25-Module.zip", "BaiZe-v2-Alpha26-Module.zip"),
        ("Alpha 25", "Alpha 26"),
    ],
}.items():
    for old, new in replacements:
        replace_once(path, old, new)

readme = Path("v2/README.md")
readme_text = readme.read_text(encoding="utf-8")
readme_text = readme_text.replace("# 白泽 v2 Alpha 25", "# 白泽 v2 Alpha 26", 1)
readme_text = readme_text.replace("当前开发分支：`v2-alpha25`。", "当前开发分支：`v2-alpha26`。", 1)
readme_text = readme_text.replace(
    "## Alpha 25\n",
    """## Alpha 26

- RootService 断开、绑定死亡、空 Binder 和连接超时都会自动退避重连，不再永久卡在“正在连接”。
- 主按钮不再出现“看起来可点、实际被禁用”的假按钮；未就绪时点击会连接 Root 并自动继续清理。
- 双引擎支持降级运行：单个引擎在线时仍可扫描和清理对应快照，另一个引擎在后台恢复。
- 扫描结果自动定位不再把首页顶到状态栏下面；结果消费或关闭后自动回到顶部。
- 底部导航改为正常布局流，不再覆盖“更多清理”和扫描结果按钮。

## Alpha 25
""",
    1,
)
readme_text = readme_text.replace("BaiZe-v2-Alpha25-Module.zip", "BaiZe-v2-Alpha26-Module.zip")
readme.write_text(readme_text, encoding="utf-8")

Path("v2/ALPHA26-CHANGES.md").write_text(
    """# Alpha 26 改动摘要

- 修复 RootService 断开后只改状态、不再重连，导致 App 永久停在“正在连接”的核心故障。
- 增加连接中、已绑定、绑定超时、绑定死亡和空 Binder 的完整状态机及自动退避重试。
- 未就绪时主按钮仍可操作：点击会申请/恢复 Root，连接成功后自动继续任务。
- 新扫描允许单引擎降级执行；已有快照仍只消费对应引擎，不会重新全盘扫描。
- 扫描结果页面滚动恢复：首页不再被推到状态栏下方，清理结束自动回顶。
- 悬浮底栏改为正常布局流，彻底避免遮挡清理入口和说明文字。
- 版本升级为 `2.0.0-alpha26` / `20600`。
""",
    encoding="utf-8",
)
