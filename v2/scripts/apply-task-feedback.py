#!/usr/bin/env python3
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


def patch_dashboard() -> None:
    path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt")
    text = path.read_text()
    marker = "当前已有扫描或清理任务正在运行，请先停止或等待完成"
    if marker in text:
        return
    text = replace_once(
        text,
        """    private fun runApkScan() {\n        val service = rootService\n""",
        """    private fun showTaskBusy(message: String = \"当前已有扫描或清理任务正在运行，请先停止或等待完成\") {\n        dashboardState.value = dashboardState.value.copy(taskPhase = message)\n        toast(message)\n    }\n\n    private fun runApkScan() {\n        if (dashboardState.value.running) {\n            showTaskBusy()\n            return\n        }\n        val service = rootService\n""",
        "dashboard apk entry",
    )
    text = replace_once(
        text,
        """    private fun runModuleUtilityTask(service: IProfileRootService, mode: String) {\n        if (dashboardState.value.running) return\n""",
        """    private fun runModuleUtilityTask(service: IProfileRootService, mode: String) {\n        if (dashboardState.value.running) {\n            showTaskBusy()\n            return\n        }\n""",
        "dashboard utility guard",
    )
    text = replace_once(
        text,
        """            val json = response.getOrThrow()\n            updateRawLogFromResponse(json)\n            val latest = json.optJSONObject(\"latest\") ?: JSONObject()\n            val success = json.optBoolean(\"success\")\n""",
        """            val json = response.getOrThrow()\n            if (json.optString(\"error\") == \"busy\" || json.optInt(\"exitCode\") == 3) {\n                val message = json.optString(\"message\", \"当前已有扫描或清理任务正在运行\")\n                dashboardState.value = dashboardState.value.copy(running = false, taskPhase = message)\n                toast(message)\n                return@launch\n            }\n            updateRawLogFromResponse(json)\n            val latest = json.optJSONObject(\"latest\") ?: JSONObject()\n            val success = json.optBoolean(\"success\")\n""",
        "dashboard utility busy response",
    )
    text = replace_once(
        text,
        """    private fun runSmartClean() {\n        if (schedulerState.value.notifyOnComplete) requestNotificationPermission()\n""",
        """    private fun runSmartClean() {\n        if (dashboardState.value.running) {\n            showTaskBusy()\n            return\n        }\n        if (schedulerState.value.notifyOnComplete) requestNotificationPermission()\n""",
        "dashboard smart clean guard",
    )
    text = replace_once(
        text,
        """    private fun runModuleClean(service: IProfileRootService) {\n        if (dashboardState.value.running) return\n""",
        """    private fun runModuleClean(service: IProfileRootService) {\n        if (dashboardState.value.running) {\n            showTaskBusy()\n            return\n        }\n""",
        "dashboard module clean guard",
    )
    text = replace_once(
        text,
        """            val json = response.getOrThrow()\n            updateRawLogFromResponse(json)\n            val latest = json.optJSONObject(\"latest\") ?: JSONObject()\n            val success = json.optBoolean(\"success\")\n            val cancelled = json.optBoolean(\"cancelled\")\n            val bytes = latest.optLong(\"bytes\", 0L).coerceAtLeast(0L)\n""",
        """            val json = response.getOrThrow()\n            if (json.optString(\"error\") == \"busy\" || json.optInt(\"exitCode\") == 3) {\n                val message = json.optString(\"message\", \"当前已有扫描或清理任务正在运行\")\n                dashboardState.value = dashboardState.value.copy(running = false, taskPhase = message)\n                toast(message)\n                return@launch\n            }\n            updateRawLogFromResponse(json)\n            val latest = json.optJSONObject(\"latest\") ?: JSONObject()\n            val success = json.optBoolean(\"success\")\n            val cancelled = json.optBoolean(\"cancelled\")\n            val bytes = latest.optLong(\"bytes\", 0L).coerceAtLeast(0L)\n""",
        "dashboard module clean busy response",
    )
    text = replace_once(
        text,
        """        if (dashboardState.value.running) return\n        clearSnapshotHandles()\n        val started = SystemClock.elapsedRealtime()\n""",
        """        if (dashboardState.value.running) {\n            showTaskBusy()\n            return\n        }\n        clearSnapshotHandles()\n        val started = SystemClock.elapsedRealtime()\n""",
        "dashboard scan guard",
    )
    text = replace_once(
        text,
        """            val (cacheJson, safeJson) = pair.getOrThrow()\n            val cacheOk = !cacheJson.has(\"error\") && !cacheJson.optBoolean(\"cancelled\")\n""",
        """            val (cacheJson, safeJson) = pair.getOrThrow()\n            val busy = listOf(cacheJson, safeJson).firstOrNull {\n                it.optString(\"error\") == \"busy\" || it.optInt(\"exitCode\") == 3\n            }\n            if (busy != null) {\n                val message = busy.optString(\"message\", \"当前已有扫描或清理任务正在运行\")\n                dashboardState.value = dashboardState.value.copy(running = false, taskPhase = message)\n                toast(message)\n                return@launch\n            }\n            val cacheOk = !cacheJson.has(\"error\") && !cacheJson.optBoolean(\"cancelled\")\n""",
        "dashboard smart scan busy response",
    )
    text = replace_once(
        text,
        """    private fun cleanNativeSnapshots() {\n        if (dashboardState.value.running) return\n""",
        """    private fun cleanNativeSnapshots() {\n        if (dashboardState.value.running) {\n            showTaskBusy()\n            return\n        }\n""",
        "dashboard snapshot clean guard",
    )
    path.write_text(text)


def patch_profile() -> None:
    path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/ProfileActivity.kt")
    text = path.read_text()
    if "当前任务仍在执行，请先停止或等待完成" in text:
        return
    text = replace_once(
        text,
        """    private fun scan() {\n        if (taskRunning || service == null) return\n        if (requiresModuleAuthorization()) runAuthorizedModuleScan() else runNativeDetailScan()\n    }\n""",
        """    private fun scan() {\n        if (taskRunning) {\n            screenState = screenState.copy(summaryText = \"当前任务仍在执行，请先停止或等待完成\")\n            return\n        }\n        if (service == null) {\n            screenState = screenState.copy(summaryText = \"Root 服务尚未连接，正在重新连接…\")\n            connect()\n            return\n        }\n        if (requiresModuleAuthorization()) runAuthorizedModuleScan() else runNativeDetailScan()\n    }\n""",
        "profile scan feedback",
    )
    text = replace_once(
        text,
        """    private fun stopTask() {\n        if (!taskRunning) return\n        service?.cancelCurrentTask()\n""",
        """    private fun stopTask() {\n        if (!taskRunning) {\n            screenState = screenState.copy(summaryText = \"当前没有正在运行的任务\")\n            return\n        }\n        service?.cancelCurrentTask()\n""",
        "profile stop feedback",
    )
    text = replace_once(
        text,
        """    private fun quickClean() {\n        val root = service ?: return\n        if (taskRunning || !quickCleanReady) return\n        taskRunning = true\n""",
        """    private fun quickClean() {\n        if (taskRunning) {\n            screenState = screenState.copy(summaryText = \"当前任务仍在执行，请先停止或等待完成\")\n            return\n        }\n        if (!quickCleanReady) {\n            screenState = screenState.copy(summaryText = \"没有可用的扫描快照，请先完成扫描\")\n            return\n        }\n        val root = service\n        if (root == null) {\n            screenState = screenState.copy(summaryText = \"Root 服务尚未连接，正在重新连接…\")\n            connect()\n            return\n        }\n        taskRunning = true\n""",
        "profile clean feedback",
    )
    path.write_text(text)


def patch_smart_scan() -> None:
    path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/SmartScanActivity.kt")
    text = path.read_text()
    if "智能扫描任务仍在运行，请先停止或等待完成" in text:
        return
    text = replace_once(
        text,
        """    private fun startSmartScan() {\n        val cache = cacheService ?: return\n        val profiles = profileService ?: return\n        if (running) return\n""",
        """    private fun startSmartScan() {\n        if (running) {\n            binding.summaryText.text = \"智能扫描任务仍在运行，请先停止或等待完成\"\n            return\n        }\n        val cache = cacheService\n        val profiles = profileService\n        if (cache == null || profiles == null) {\n            binding.summaryText.text = \"Root 扫描引擎尚未全部连接，请稍后重试\"\n            bindServices()\n            return\n        }\n""",
        "smart scan entry feedback",
    )
    text = replace_once(
        text,
        """                val (cacheJson, safeJson) = withContext(Dispatchers.IO) {\n                    coroutineScope {\n                        val cacheJob = async { JSONObject(cache.scanCandidates(JSONArray(whitelist.toList()).toString())) }\n                        val safeJob = async { JSONObject(profiles.scanProfile(\"safe\", optionsJson())) }\n                        cacheJob.await() to safeJob.await()\n                    }\n                }\n                if (cacheJson.has(\"error\")) failed++ else {\n""",
        """                val (cacheJson, safeJson) = withContext(Dispatchers.IO) {\n                    coroutineScope {\n                        val cacheJob = async { JSONObject(cache.scanCandidates(JSONArray(whitelist.toList()).toString())) }\n                        val safeJob = async { JSONObject(profiles.scanProfile(\"safe\", optionsJson())) }\n                        cacheJob.await() to safeJob.await()\n                    }\n                }\n                val busy = listOf(cacheJson, safeJson).firstOrNull { it.optString(\"error\") == \"busy\" }\n                if (busy != null) {\n                    binding.summaryText.text = busy.optString(\"message\", \"当前已有扫描或清理任务正在运行\")\n                    return@launch\n                }\n                if (cacheJson.has(\"error\")) failed++ else {\n""",
        "smart scan busy response",
    )
    text = replace_once(
        text,
        """    private fun cleanSnapshots() {\n        val cache = cacheService ?: return\n        val profiles = profileService ?: return\n        if (running) return\n        running = true\n""",
        """    private fun cleanSnapshots() {\n        if (running) {\n            binding.summaryText.text = \"智能扫描任务仍在运行，请先停止或等待完成\"\n            return\n        }\n        if (totalSafe <= 0 || (cacheSnapshotId.isBlank() && safeSnapshotId.isBlank())) {\n            binding.summaryText.text = \"没有可用的扫描快照，请先重新扫描\"\n            return\n        }\n        val cache = cacheService\n        val profiles = profileService\n        if (cache == null || profiles == null) {\n            binding.summaryText.text = \"Root 清理引擎尚未全部连接，请稍后重试\"\n            bindServices()\n            return\n        }\n        running = true\n""",
        "smart clean entry feedback",
    )
    text = replace_once(
        text,
        """                    val json = JSONObject(withContext(Dispatchers.IO) {\n                        cache.cleanSelected(cacheSnapshotId, selectAll, JSONArray(whitelist.toList()).toString())\n                    })\n                    deletedBytes += json.optLong(\"deletedBytes\")\n""",
        """                    val json = JSONObject(withContext(Dispatchers.IO) {\n                        cache.cleanSelected(cacheSnapshotId, selectAll, JSONArray(whitelist.toList()).toString())\n                    })\n                    if (json.has(\"error\")) throw IllegalStateException(json.optString(\"message\", \"缓存快照清理失败\"))\n                    deletedBytes += json.optLong(\"deletedBytes\")\n""",
        "smart cache clean error",
    )
    text = replace_once(
        text,
        """                        val json = JSONObject(withContext(Dispatchers.IO) {\n                            profiles.cleanProfileSelected(safeSnapshotId, selectAll, optionsJson())\n                        })\n                        deletedBytes += json.optLong(\"deletedBytes\")\n""",
        """                        val json = JSONObject(withContext(Dispatchers.IO) {\n                            profiles.cleanProfileSelected(safeSnapshotId, selectAll, optionsJson())\n                        })\n                        if (json.has(\"error\")) throw IllegalStateException(json.optString(\"message\", \"安全项目快照清理失败\"))\n                        deletedBytes += json.optLong(\"deletedBytes\")\n""",
        "smart profile clean error",
    )
    path.write_text(text)


patch_dashboard()
patch_profile()
patch_smart_scan()
