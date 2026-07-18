#!/usr/bin/env python3
from pathlib import Path
import re


def replace_method(text: str, name: str, block: str) -> str:
    pattern = re.compile(rf"\n    private fun {re.escape(name)}\([^\n]*\) \{{.*?(?=\n    private fun |\n    override fun |\n    companion object)", re.S)
    match = pattern.search(text)
    if not match:
        raise SystemExit(f"method not found: {name}")
    return text[:match.start()] + "\n" + block.rstrip() + "\n" + text[match.end():]


activity_path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt")
text = activity_path.read_text(encoding="utf-8")

# 首页启动时只连接真正负责一键清理的模块 RootService。缓存快照服务仅在安全扫描时按需连接。
old_create_tail = """        connectServices()\n    }\n\n    override fun onNewIntent"""
new_create_tail = """        connectPrimaryService()\n    }\n\n    override fun onNewIntent"""
if old_create_tail not in text:
    raise SystemExit("onCreate connection target not found")
text = text.replace(old_create_tail, new_create_tail, 1)

old_new_intent = """            } else if (rootService == null || cacheService == null) {\n                pendingClean = true\n                connectServices()\n            } else {"""
new_new_intent = """            } else if (rootService == null) {\n                pendingClean = true\n                connectPrimaryService()\n            } else {"""
if old_new_intent not in text:
    raise SystemExit("onNewIntent target not found")
text = text.replace(old_new_intent, new_new_intent, 1)

primary_connector = r'''    private fun connectPrimaryService() {
        if (rootService != null || profileBound) return
        dashboardState.value = dashboardState.value.copy(
            connected = false,
            ready = false,
            serviceText = "正在连接 Root 清理服务…"
        )
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java)
                    .addCategory(RootService.CATEGORY_DAEMON_MODE),
                profileConnection
            )
            profileBound = true
        }.onFailure {
            profileBound = false
            dashboardState.value = dashboardState.value.copy(
                connected = false,
                ready = false,
                serviceText = "Root 清理服务启动失败：${it.message.orEmpty()}"
            )
        }
    }
'''
marker = "    private fun connectServices() {"
if primary_connector.strip() not in text:
    if marker not in text:
        raise SystemExit("connectServices marker not found")
    text = text.replace(marker, primary_connector + "\n" + marker, 1)

text = replace_method(text, "reconnectService", r'''    private fun reconnectService() {
        if (profileBound) runCatching { RootService.unbind(profileConnection) }
        if (cacheBound) runCatching { RootService.unbind(cacheConnection) }
        rootService = null
        cacheService = null
        profileBound = false
        cacheBound = false
        dashboardState.value = dashboardState.value.copy(
            connected = false,
            ready = false,
            running = false,
            serviceText = "正在重新连接 Root 清理服务…"
        )
        connectPrimaryService()
        toast("正在重新连接 Root 清理服务")
    }''')

text = replace_method(text, "updateConnectionState", r'''    private fun updateConnectionState() {
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
    }''')

text = replace_method(text, "runPendingCleanIfReady", r'''    private fun runPendingCleanIfReady() {
        if (!pendingClean || rootService == null) return
        pendingClean = false
        runSmartClean()
    }''')

text = replace_method(text, "readServiceStatus", r'''    private fun readServiceStatus() {
        val service = rootService ?: return
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { JSONObject(service.ping()) }.getOrNull()
            } ?: return@launch
            val root = json.optBoolean("root")
            val module = json.optBoolean("module")
            val cleaner = json.optBoolean("cleaner")
            val scheduler = json.optBoolean("scheduler")
            val rules = json.optBoolean("deepRules")
            val ready = root && module && cleaner && scheduler && rules
            val status = when {
                !root -> "服务已连接，但未取得完整 Root"
                !module -> "Root 已连接 · 未检测到白泽模块"
                !cleaner -> "模块已连接 · 清理引擎缺失"
                !scheduler -> "清理引擎已连接 · 调度器缺失"
                !rules -> "自动清理可用 · 深度规则库缺失"
                else -> "Root、完整清理引擎、定时任务与规则库均已就绪"
            }
            dashboardState.value = dashboardState.value.copy(
                connected = true,
                ready = ready,
                serviceText = status,
                device = Build.MODEL,
                android = "Android ${Build.VERSION.RELEASE}"
            )
        }
    }''')

text = replace_method(text, "runSmartClean", r'''    private fun runSmartClean() {
        if (schedulerState.value.notifyOnComplete) requestNotificationPermission()
        val service = rootService
        if (service == null) {
            pendingClean = true
            dashboardState.value = dashboardState.value.copy(
                connected = false,
                ready = false,
                taskPhase = "正在连接 Root 清理服务，连接成功后继续清理"
            )
            connectPrimaryService()
            return
        }
        runModuleClean(service)
    }''')

module_clean = r'''    private fun runModuleClean(service: IProfileRootService) {
        if (dashboardState.value.running) return
        clearSnapshotHandles()
        dashboardState.value = dashboardState.value.copy(
            running = true,
            scanCompleted = false,
            taskPhase = "正在调用模块完整清理引擎…"
        )
        startNativePoll()
        lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) {
                runCatching { JSONObject(service.runModuleTask("clean")) }
            }
            pollJob?.cancel()
            if (response.isFailure) {
                rootService = null
                profileBound = false
                dashboardState.value = dashboardState.value.copy(
                    connected = false,
                    ready = false,
                    running = false,
                    serviceText = "Root 清理服务已断开，正在重新连接…",
                    taskPhase = "清理启动失败：${response.exceptionOrNull()?.message ?: "Root 服务异常"}"
                )
                connectPrimaryService()
                return@launch
            }

            val json = response.getOrThrow()
            val latest = json.optJSONObject("latest") ?: JSONObject()
            val success = json.optBoolean("success")
            val cancelled = json.optBoolean("cancelled")
            val bytes = latest.optLong("bytes", 0L).coerceAtLeast(0L)
            val files = latest.optLong("files", 0L).coerceAtLeast(0L)
            val emptyFiles = latest.optLong("empty_files", 0L).coerceAtLeast(0L)
            val emptyDirs = latest.optLong("empty_dirs", 0L).coerceAtLeast(0L)
            val fragments = latest.optLong("fragment_files", 0L).coerceAtLeast(0L)
            val errors = latest.optLong("errors", if (success) 0L else 1L).coerceAtLeast(0L)
            val elapsed = latest.optLong("elapsed", json.optLong("elapsedMs", 0L) / 1000L).coerceAtLeast(0L)
            val resultLine = latest.optString("result").ifBlank {
                json.optString("message", if (success) "清理完成" else "清理失败")
            }
            val detailLine = "文件 $files · 空文件 $emptyFiles · 空目录 $emptyDirs · 碎片 $fragments · 异常 $errors · ${formatElapsed(elapsed)}"
            val title = when {
                cancelled -> "白泽清理已停止"
                success -> "白泽智能清理完成"
                else -> "白泽智能清理失败"
            }

            dashboardState.value = dashboardState.value.copy(
                running = false,
                lastReleased = bytes,
                taskPhase = "$resultLine\n$detailLine"
            )
            preferences.edit()
                .putLong("last_clean_bytes", bytes)
                .putString("last_report_text", "$resultLine\n$detailLine")
                .apply()
            notifyCleanResult(title, resultLine, detailLine, bytes)
            refreshHistory()
            refreshModuleState()
            updateStorage()
            readServiceStatus()
        }
    }
'''
marker = "    private fun runNativeScan(cleanAfterScan: Boolean) {"
if module_clean.strip() not in text:
    if marker not in text:
        raise SystemExit("runNativeScan marker not found")
    text = text.replace(marker, module_clean + "\n" + marker, 1)

activity_path.write_text(text, encoding="utf-8")

# 保留 Alpha 25 的 UI 布局，仅更新版本标识。
ui_path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt")
ui = ui_path.read_text(encoding="utf-8").replace("原生快照清理引擎 · Alpha 25", "原生清理引擎 · Alpha 27")
ui_path.write_text(ui, encoding="utf-8")

replacements = {
    "v2/app/build.gradle.kts": [
        ("versionCode = 20500", "versionCode = 20700"),
        ('versionName = "2.0.0-alpha25"', 'versionName = "2.0.0-alpha27"'),
    ],
    "v2/module/module.prop": [
        ("version=v2.0.0-alpha25", "version=v2.0.0-alpha27"),
        ("versionCode=20500", "versionCode=20700"),
    ],
    "v2/module/customize.sh": [("白泽 v2 Alpha 25", "白泽 v2 Alpha 27")],
    "v2/scripts/package-module.sh": [
        ("BaiZe-v2-Alpha25-Module.zip", "BaiZe-v2-Alpha27-Module.zip"),
        ("Alpha 25", "Alpha 27"),
    ],
}
for filename, pairs in replacements.items():
    path = Path(filename)
    data = path.read_text(encoding="utf-8")
    for old, new in pairs:
        if old not in data and new not in data:
            raise SystemExit(f"version target missing in {filename}: {old}")
        data = data.replace(old, new)
    path.write_text(data, encoding="utf-8")

Path("v2/ALPHA27-CHANGES.md").write_text(
    """# Alpha 27 改动摘要

- 完整回退 Alpha 26 对首页布局、底栏、按钮文案和滚动行为的改动，恢复 Alpha 25 界面。
- 首页“立即智能清理”不再依赖双 Root 快照引擎，直接调用成熟的模块 `cleaner.sh clean app` 清理链路。
- App 启动时只连接主模块 RootService；应用缓存快照服务仅供安全扫描使用，不再阻塞一键清理。
- 主按钮就绪状态只校验 Root、模块、cleaner、scheduler 与规则库，不再因缓存快照服务未连接而失效。
- Root 尚未连接时点击清理会先连接主服务并自动继续，不再直接进入双引擎异常分支。
""",
    encoding="utf-8",
)
