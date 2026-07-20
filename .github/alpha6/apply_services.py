from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing marker: {label}")
    return text.replace(old, new, 1)

path = Path("v2/app/src/main/aidl/io/github/xgl34222220/baize/root/IProfileRootService.aidl")
text = path.read_text()
text = replace_once(text, '    String saveSchedulerConfig(String configJson);\n', '    String saveSchedulerConfig(String configJson);\n    String resetScanWorkerProfile();\n', "aidl reset method")
path.write_text(text)

path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt")
text = path.read_text()
text = replace_once(text, '        override fun saveSchedulerConfig(configJson: String?): String = saveConfig(configJson.orEmpty())\n', '        override fun saveSchedulerConfig(configJson: String?): String = saveConfig(configJson.orEmpty())\n\n        override fun resetScanWorkerProfile(): String = resetScanWorkerProfileJson()\n', "service reset binder")
text = replace_once(text, '            .put("totals", totals)\n            .put("latest", latest)\n            .put("latestReport",', '            .put("totals", totals)\n            .put("latest", latest)\n            .put("scanPerformance", scanPerformanceJson())\n            .put("latestReport",', "task result performance")
text = replace_once(text, '''            .put("totals", totals)
            .put("latest", latest)
            .put(
                "appDetails",''', '''            .put("totals", totals)
            .put("latest", latest)
            .put("scanPerformance", scanPerformanceJson())
            .put(
                "appDetails",''', "module state performance")
text = replace_once(text, '    private fun taskHistoryJson(requestedLimit: Int): String {\n', '''    private fun scanPerformanceJson(): JSONObject {
        val stateDir = File(STATE_DIR)
        val cache = readEnv(File(stateDir, "cache_scan.env"))
        val profile = readEnv(File(stateDir, "root-worker-profile.env"))
        val config = configJsonObject()
        val requestedMode = config.optInt("scan_root_workers", 0).coerceIn(0, 2)
        val actualWorkers = cache.optInt("root_workers", profile.optInt("last_workers", 1)).coerceIn(1, 2)
        val recommendedWorkers = profile.optInt("recommended_workers", 1).coerceIn(1, 2)
        val hasProfile = profile.length() > 0
        val reason = if (hasProfile) {
            cache.optString("worker_reason").ifBlank {
                profile.optString("last_decision", "not_measured")
            }
        } else {
            "not_measured"
        }
        return JSONObject()
            .put("available", hasProfile)
            .put("requestedMode", requestedMode)
            .put("workerPolicy", if (requestedMode == 0) "auto" else "manual")
            .put("workerReason", reason)
            .put("actualWorkers", actualWorkers)
            .put("recommendedWorkers", recommendedWorkers)
            .put("parallelGainPercent", profile.optInt("parallel_gain_percent", 0))
            .put("serialRate", profile.optLong("serial_rate", 0L).coerceAtLeast(0L))
            .put("parallelRate", profile.optLong("parallel_rate", 0L).coerceAtLeast(0L))
            .put("successfulRuns", profile.optInt("successful_runs", 0).coerceAtLeast(0))
            .put("nextProbeRun", profile.optInt("next_probe_run", 0).coerceAtLeast(0))
            .put("parallelBlockedUntil", profile.optLong("parallel_blocked_until", 0L).coerceAtLeast(0L))
            .put("lastUpdatedEpoch", profile.optLong("updated_epoch", 0L).coerceAtLeast(0L))
    }

    private fun resetScanWorkerProfileJson(): String = runCatching {
        val profile = File(STATE_DIR, "root-worker-profile.env")
        val deleted = !profile.exists() || profile.delete()
        if (!deleted) error("无法删除本机性能基准")
        JSONObject()
            .put("success", true)
            .put("message", "性能基准已清除，下次自动扫描将从串行重新学习")
            .toString()
    }.getOrElse { error ->
        JSONObject()
            .put("success", false)
            .put("error", error.message ?: error.javaClass.simpleName)
            .toString()
    }

    private fun taskHistoryJson(requestedLimit: Int): String {
''', "performance helpers")
text = replace_once(text, '            "max_run_minutes" to 5..180,\n', '''            "max_run_minutes" to 5..180,
            "scan_root_workers" to 0..2,
            "scan_parallel_min_items" to 100..10_000_000,
            "scan_parallel_min_gain_percent" to 5..50,
            "scan_parallel_reprobe_runs" to 2..50,
            "scan_parallel_failure_cooldown_hours" to 1..168,
''', "allowed performance config")
path.write_text(text)
