from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt"
text = path.read_text()
old = """            return try {
                executeModuleTask(normalized, started)
            } catch (error: Throwable) {
"""
new = """            return try {
                if (normalized == \"clean\") startDetachedModuleTask(normalized, started)
                else executeModuleTask(normalized, started)
            } catch (error: Throwable) {
"""
if new not in text:
    if old not in text:
        raise SystemExit("detached dispatch anchor missing")
    text = text.replace(old, new, 1)

marker = "    private fun executeModuleTask(mode: String, started: Long): String {\n"
if "private fun startDetachedModuleTask" not in text:
    if marker not in text:
        raise SystemExit("executeModuleTask marker missing")
    method = '''    private fun startDetachedModuleTask(mode: String, started: Long): String {
        val worker = File(MODULE_DIR, "task-worker.sh")
        if (!worker.isFile) {
            return JSONObject().put("error", "worker_missing")
                .put("message", "独立 Root Worker 缺失，请重新刷入完整模块").toString()
        }
        val existing = readEnv(File(STATE_DIR, "running.env"))
        if (existing.length() > 0 && existing.optString("mode").isNotBlank()) return busy("module-$mode")
        val stateDir = File(STATE_DIR).apply { mkdirs() }
        val taskId = "${System.currentTimeMillis()}-${Process.myPid()}"
        val launcherLog = File(stateDir, "logs/launcher-$taskId.log").apply { parentFile?.mkdirs() }
        val process = ProcessBuilder("/system/bin/sh", worker.absolutePath, mode, "app", taskId)
            .redirectErrorStream(true).redirectOutput(launcherLog).start()
        val exited = process.waitFor(8, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            return JSONObject().put("error", "worker_launch_timeout")
                .put("message", "独立 Root Worker 启动超时").toString()
        }
        val code = process.exitValue()
        if (code != 0) {
            val output = tailText(launcherLog, 2_000).trim()
            return JSONObject().put("error", if (code == 3) "busy" else "worker_launch_failed")
                .put("exitCode", code)
                .put("message", output.ifBlank { "独立 Root Worker 启动失败（代码 $code）" }).toString()
        }
        taskStateJson = JSONObject().put("running", true).put("operation", "module-$mode")
            .put("phase", "独立 Root Worker 已启动").put("taskId", taskId)
            .put("elapsedMs", SystemClock.elapsedRealtime() - started).toString()
        publishTaskState(true)
        return JSONObject().put("success", true).put("accepted", true).put("running", true)
            .put("mode", mode).put("taskId", taskId)
            .put("message", "清理任务已交给独立 Root Worker，关闭 App 仍会继续").toString()
    }

'''
    text = text.replace(marker, method + marker, 1)
path.write_text(text)
print("v2.2.5 root service patch applied")
