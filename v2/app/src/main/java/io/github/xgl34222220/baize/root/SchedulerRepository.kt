package io.github.xgl34222220.baize.root

import android.os.Process
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

internal class SchedulerRepository(
    private val moduleDir: File = File(RootPaths.MODULE_DIR),
    private val stateDir: File = File(RootPaths.STATE_DIR)
) {
    fun configJson(): String = configJsonObject().toString()

    fun saveConfig(raw: String): String {
        val input = runCatching { JSONObject(raw) }.getOrElse {
            return JSONObject().put("error", "invalid_json").put("message", "计划配置格式无效").toString()
        }
        ensureConfig()
        val previousConfig = configJsonObject()
        val updates = LinkedHashMap<String, String>()
        val keys = input.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val range = ALLOWED_CONFIG[key] ?: continue
            val value = input.optInt(key, Int.MIN_VALUE)
            if (value == Int.MIN_VALUE || value !in range) {
                return JSONObject().put("error", "invalid_value").put("key", key).toString()
            }
            updates[key] = value.toString()
        }
        if (updates.isEmpty()) return JSONObject().put("error", "empty_config").toString()

        val file = File(RootPaths.CONFIG_FILE)
        val lines = if (file.isFile) file.readLines().toMutableList() else mutableListOf()
        val written = HashSet<String>()
        for (index in lines.indices) {
            val line = lines[index]
            val key = line.substringBefore('=', "").trim()
            val replacement = updates[key]
            if (replacement != null && !line.trimStart().startsWith("#")) {
                lines[index] = "$key=$replacement"
                written += key
            }
        }
        for ((key, value) in updates) if (key !in written) lines += "$key=$value"
        RootFileStore.writeAtomic(file, lines.joinToString("\n", postfix = "\n"), worldReadable = true)
        if (updates["schedule_organize_enabled"] == "1" && previousConfig.optInt("schedule_organize_enabled", 0) == 0) {
            val stamp = File(stateDir, "last_organize_run.epoch")
            if (!stamp.isFile) RootFileStore.writeAtomic(stamp, "${System.currentTimeMillis() / 1000L}\n")
        }
        val wake = wakeSupervisor("config-updated")
        return JSONObject()
            .put("success", true)
            .put("config", configJsonObject())
            .put("schedulerWake", JSONObject(wake))
            .toString()
    }

    fun wakeSupervisor(reason: String = "workmanager-fallback"): String = runCatching {
        stateDir.mkdirs()
        val schedulerState = RootFileStore.readEnv(File(stateDir, "scheduler.env"))
        val supervisorState = RootFileStore.readEnv(File(stateDir, "supervisor.env"))
        val schedulerStatePid = schedulerState.optLong("scheduler_pid", 0L)
        val supervisorChildPid = supervisorState.optLong("scheduler_pid", 0L)
        val schedulerPid = if (isAlive(schedulerStatePid)) schedulerStatePid else supervisorChildPid
        val supervisorPid = supervisorState.optLong("pid", 0L)

        if (isAlive(schedulerPid) && signalScheduler(schedulerPid)) {
            return@runCatching JSONObject()
                .put("success", true)
                .put("action", "signalled")
                .put("schedulerPid", schedulerPid)
                .put("reason", reason)
                .toString()
        }

        if (isAlive(supervisorPid)) {
            return@runCatching JSONObject()
                .put("success", true)
                .put("action", "supervisor-alive")
                .put("supervisorPid", supervisorPid)
                .put("reason", reason)
                .toString()
        }

        val supervisor = File(moduleDir, "supervisor.sh")
        require(supervisor.isFile) { "supervisor_missing" }
        val log = File(stateDir, "logs/supervisor-launch.log").apply { parentFile?.mkdirs() }
        val quotedSupervisor = shellQuote(supervisor.absolutePath)
        val quotedLog = shellQuote(log.absolutePath)
        val command = "if command -v setsid >/dev/null 2>&1; then setsid /system/bin/sh $quotedSupervisor </dev/null >>$quotedLog 2>&1 & " +
            "elif command -v nohup >/dev/null 2>&1; then nohup /system/bin/sh $quotedSupervisor </dev/null >>$quotedLog 2>&1 & " +
            "else /system/bin/sh $quotedSupervisor </dev/null >>$quotedLog 2>&1 & fi"
        val launcher = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        launcher.waitFor(5, TimeUnit.SECONDS)
        JSONObject()
            .put("success", true)
            .put("action", "supervisor-started")
            .put("reason", reason)
            .toString()
    }.getOrElse { error ->
        JSONObject()
            .put("success", false)
            .put("error", error.message ?: error.javaClass.simpleName)
            .put("reason", reason)
            .toString()
    }

    private fun configJsonObject(): JSONObject {
        ensureConfig()
        val result = JSONObject()
        File(RootPaths.CONFIG_FILE).takeIf { it.isFile }?.forEachLine { raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#") || !line.contains('=')) return@forEachLine
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim()
            if (key in ALLOWED_CONFIG) result.put(key, value.toIntOrNull() ?: value)
        }
        return result
    }

    private fun ensureConfig() {
        val file = File(RootPaths.CONFIG_FILE)
        if (file.isFile) return
        file.parentFile?.mkdirs()
        val defaults = File(moduleDir, "config/default.conf")
        if (defaults.isFile) defaults.copyTo(file, overwrite = false)
    }

    private fun isAlive(pid: Long): Boolean = pid > 1L && File("/proc/$pid").isDirectory

    private fun signalScheduler(pid: Long): Boolean = runCatching {
        val process = ProcessBuilder("/system/bin/sh", "-c", "kill -USR1 $pid")
            .redirectErrorStream(true)
            .start()
        process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    companion object {
        val ALLOWED_CONFIG: Map<String, IntRange> = mapOf(
            "enabled" to 0..1,
            "screen_off_only" to 0..1,
            "charging_only" to 0..1,
            "device_idle_only" to 0..1,
            "min_battery" to 0..100,
            "max_battery_temp" to 30..60,
            "max_run_minutes" to 5..180,
            "scan_root_workers" to 0..4,
            "scan_parallel_min_items" to 100..10_000_000,
            "scan_parallel_min_gain_percent" to 5..50,
            "scan_parallel_reprobe_runs" to 2..50,
            "scan_parallel_failure_cooldown_hours" to 1..168,
            "schedule_cache_enabled" to 0..1,
            "schedule_cache_hours" to 1..720,
            "schedule_cache_minutes" to 5..43_200,
            "schedule_empty_enabled" to 0..1,
            "schedule_empty_hours" to 1..720,
            "schedule_empty_minutes" to 5..43_200,
            "schedule_rules_enabled" to 0..1,
            "schedule_rules_hours" to 1..720,
            "schedule_rules_minutes" to 5..43_200,
            "schedule_fragment_enabled" to 0..1,
            "schedule_fragment_hours" to 1..720,
            "schedule_fragment_minutes" to 5..43_200,
            "schedule_deep_enabled" to 0..1,
            "schedule_deep_hours" to 1..720,
            "schedule_deep_minutes" to 5..43_200,
            "schedule_organize_enabled" to 0..1,
            "schedule_organize_hours" to 1..720,
            "schedule_organize_minutes" to 15..43_200,
            "organize_screen_off_only" to 0..1,
            "organize_charging_only" to 0..1,
            "organize_device_idle_only" to 0..1,
            "daily_schedule_enabled" to 0..1,
            "daily_schedule_hour" to 0..23,
            "daily_schedule_minute" to 0..59,
            "daily_grace_minutes" to 15..720,
            "clean_app_cache" to 0..1,
            "clean_external_cache" to 0..1,
            "clean_system_logs" to 0..1,
            "clean_oem_logs" to 0..1,
            "clean_empty_files" to 0..1,
            "clean_empty_dirs" to 0..1,
            "clean_root_shells" to 0..1,
            "clean_app_rules" to 0..1,
            "clean_hidden_junk" to 0..1,
            "clean_fragments" to 0..1,
            "clean_custom_rules" to 0..1,
            "clean_installer_temp" to 0..1,
            "clean_apk_packages" to 0..1,
            "notify_on_complete" to 0..1,
            "notify_zero_result" to 0..1,
            "deep_high_risk_enabled" to 0..1,
            "app_cache_days" to 0..365,
            "external_cache_days" to 0..365,
            "system_logs_days" to 0..365,
            "oem_logs_days" to 0..365,
            "empty_file_days" to 0..365,
            "hidden_junk_days" to 0..365,
            "fragment_days" to 0..365,
            "installer_temp_days" to 1..30,
            "apk_package_days" to 0..365,
            "apk_package_max_mb" to 16..16_384,
            "root_shell_days" to 1..90,
            "max_file_mb" to 16..16_384,
            "shared_index_ttl_seconds" to 30..86_400,
            "quarantine_retention_days" to 1..30
        )
    }
}
