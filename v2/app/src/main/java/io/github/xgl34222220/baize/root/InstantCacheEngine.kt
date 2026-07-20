package io.github.xgl34222220.baize.root

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Runs PackageManager cache-only requests without touching BaiZe scan snapshots. */
internal class InstantCacheEngine(
    private val cancelled: AtomicBoolean,
    private val onProgress: (JSONObject) -> Unit
) {
    fun run(raw: String, started: Long): String {
        val request = runCatching { JSONObject(raw) }.getOrElse {
            return failure("invalid_json", "即时清缓存请求格式无效")
        }
        val userId = request.optInt("userId", 0)
        if (userId !in 0..999) return failure("invalid_user", "Android 用户编号无效")

        val source = request.optJSONArray("packages") ?: JSONArray()
        val packages = linkedSetOf<String>()
        for (index in 0 until source.length()) {
            val packageName = source.optString(index).trim()
            if (!PACKAGE_NAME.matches(packageName)) {
                return failure("invalid_package", "应用包名无效", packageName)
            }
            if (packageName in BLOCKLIST) {
                return failure("protected_package", "该核心应用不允许通过即时工具清缓存", packageName)
            }
            packages += packageName
        }
        if (packages.isEmpty()) return failure("empty_selection", "请至少选择一个应用")
        if (packages.size > MAX_PACKAGES) {
            return JSONObject()
                .put("success", false)
                .put("error", "too_many_packages")
                .put("limit", MAX_PACKAGES)
                .put("message", "单次最多处理 $MAX_PACKAGES 个应用")
                .toString()
        }
        if (!supportsCacheOnly()) {
            return failure(
                "cache_only_unsupported",
                "当前系统 PackageManager 不支持 --cache-only，未执行任何清理"
            )
        }

        val results = JSONArray()
        var succeeded = 0
        var failed = 0
        var stopped = false
        packages.forEachIndexed { index, packageName ->
            if (cancelled.get()) {
                stopped = true
                return@forEachIndexed
            }
            onProgress(
                JSONObject()
                    .put("running", true)
                    .put("operation", "instant-cache")
                    .put("phase", "正在清除应用当前缓存")
                    .put("current", index + 1)
                    .put("total", packages.size)
                    .put("currentPath", packageName)
                    .put("elapsedMs", (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L))
            )
            val result = execute(packageName, userId)
            results.put(result)
            if (result.optBoolean("success")) succeeded += 1 else failed += 1
            if (result.optBoolean("cancelled")) stopped = true
        }

        return JSONObject()
            .put("success", !stopped && failed == 0)
            .put("completed", !stopped)
            .put("cancelled", stopped)
            .put("userId", userId)
            .put("requested", packages.size)
            .put("succeeded", succeeded)
            .put("failed", failed)
            .put("elapsedMs", (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L))
            .put("mode", "package-manager-cache-only")
            .put("snapshotUsed", false)
            .put("results", results)
            .put(
                "message",
                when {
                    stopped -> "任务已停止：成功 $succeeded 个，失败 $failed 个"
                    failed == 0 -> "系统即时清缓存完成：成功 $succeeded 个"
                    else -> "任务完成：成功 $succeeded 个，失败 $failed 个"
                }
            )
            .toString()
    }

    private fun supportsCacheOnly(): Boolean = runCatching {
        val process = ProcessBuilder("/system/bin/cmd", "package", "help")
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        finished && output.contains("--cache-only")
    }.getOrDefault(false)

    private fun execute(packageName: String, userId: Int): JSONObject {
        val process = ProcessBuilder(
            "/system/bin/cmd", "package", "clear", "--cache-only",
            "--user", userId.toString(), packageName
        ).redirectErrorStream(true).start()
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var finished = false
        var wasCancelled = false
        while (!finished) {
            finished = process.waitFor(200, TimeUnit.MILLISECONDS)
            if (finished) break
            if (cancelled.get()) {
                wasCancelled = true
                process.destroy()
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                break
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                process.destroy()
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                break
            }
        }
        val output = runCatching {
            process.inputStream.bufferedReader().use { it.readText().trim().take(1200) }
        }.getOrDefault("")
        val exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
        val success = finished && exitCode == 0 && !output.contains("Failed", ignoreCase = true)
        return JSONObject()
            .put("packageName", packageName)
            .put("success", success)
            .put("cancelled", wasCancelled)
            .put("timeout", !finished && !wasCancelled)
            .put("exitCode", exitCode)
            .put("output", output)
    }

    private fun failure(code: String, message: String, packageName: String = ""): String =
        JSONObject()
            .put("success", false)
            .put("error", code)
            .put("message", message)
            .apply { if (packageName.isNotBlank()) put("packageName", packageName) }
            .toString()

    companion object {
        private const val MAX_PACKAGES = 30
        private const val TIMEOUT_MS = 15_000L
        private val PACKAGE_NAME = Regex("""^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$""")
        private val BLOCKLIST = setOf(
            "android",
            "com.android.systemui",
            "io.github.xgl34222220.baize"
        )
    }
}
