package io.github.xgl34222220.baize.root

import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class SchedulerRepository(
    private val moduleDir: File = File(RootPaths.MODULE_DIR),
    private val stateDir: File = File(RootPaths.STATE_DIR)
) {
    fun configJson(): String = configJsonObject()
        .put("runtime", runtimeJsonObject())
        .toString()

    fun saveConfig(raw: String): String {
        val input = runCatching { JSONObject(raw) }.getOrElse {
            return error("invalid_json", "计划配置格式无效")
        }
        ensureConfig()
        val previous = configJsonObject()
        val updates = LinkedHashMap<String, String>()
        val keys = input.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val range = ALLOWED_CONFIG[key] ?: continue
            val value = input.optInt(key, Int.MIN_VALUE)
            if (value == Int.MIN_VALUE || value !in range) {
                return error("invalid_value", "配置值超出范围", key)
            }
            updates[key] = value.toString()
        }
        if (updates.isEmpty()) return error("empty_config", "没有可保存的计划配置")

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

        val organizeJustEnabled = updates["schedule_organize_enabled"] == "1" &&
            previous.optInt("schedule_organize_enabled", 0) == 0
        var queued: JSONObject? = null
        if (organizeJustEnabled) {
            if (input.optInt("organize_run_immediately", previous.optInt("organize_run_immediately", 0)) == 1) {
                queued = requestNow(listOf("organize"), "enabled-from-app")
            } else {
                val stamp = File(stateDir, "last_organize_run.epoch")
                if (!stamp.isFile) RootFileStore.writeAtomic(stamp, "${System.currentTimeMillis() / 1000L}\n")
            }
        }
        val wake = wakeSupervisor("config-updated")
        return JSONObject()
            .put("success", true)
            .put("config", configJsonObject().put("runtime", runtimeJsonObject()))
            .put("queued", queued)
            .put("schedulerWake", JSONObject(wake))
            .toString()
    }

    fun control(command: String): String {
        val normalized = command.trim().lowercase(Locale.ROOT)
        return when {
            normalized == "scheduler-wake" -> wakeSupervisor("app-watchdog")
            normalized == "scheduler-run-now:all" -> {
                val config = configJsonObject()
                val groups = GROUPS.filter { config.optInt("schedule_${it}_enabled", 0) == 1 }
                requestNow(groups, "manual-all").toString()
            }
            normalized.startsWith("scheduler-run-now:") -> {
                val group = normalized.substringAfter(':')
                if (group !in GROUPS) error("unsupported_group", "不支持的任务组", group)
                else requestNow(listOf(group), "manual").toString()
            }
            normalized.startsWith("scheduler-skip:") -> {
                val group = normalized.substringAfter(':')
                if (group !in GROUPS) error("unsupported_group", "不支持的任务组", group)
                else skipOnce(group).toString()
            }
            normalized == "scheduler-stop-current" -> {
                stateDir.mkdirs()
                RootFileStore.writeAtomic(File(stateDir, "stop"), "1\n")
                JSONObject().put("success", true).put("action", "stop-requested").toString()
            }
            else -> error("unsupported_scheduler_command", "不支持的调度命令", normalized)
        }
    }

    private fun requestNow(groups: List<String>, reason: String): JSONObject {
        stateDir.mkdirs()
        val requestDir = File(stateDir, "scheduler-requests").apply { mkdirs() }
        val now = System.currentTimeMillis() / 1000L
        val queued = JSONArray()
        groups.distinct().filter { it in GROUPS }.forEachIndexed { index, group ->
            val id = "$now-${Process.myPid()}-$index-${UUID.randomUUID().toString().take(8)}"
            val request = File(requestDir, "$id-$group.env")
            RootFileStore.writeAtomic(
                request,
                buildString {
                    append("group=").append(group).append('\n')
                    append("created=").append(now).append('\n')
                    append("request_id=").append(id).append('\n')
                    append("reason=").append(reason).append('\n')
                }
            )
            queued.put(group)
        }
        val wake = JSONObject(wakeSupervisor("queue-updated"))
        return JSONObject()
            .put("success", queued.length() > 0)
            .put("action", "queued")
            .put("groups", queued)
            .put("queueCount", queued.length())
            .put("schedulerWake", wake)
    }

    private fun skipOnce(group: String): JSONObject {
        val skipDir = File(stateDir, "scheduler-skips").apply { mkdirs() }
        RootFileStore.writeAtomic(File(skipDir, "$group.request"), "${System.currentTimeMillis() / 1000L}\n")
        val wake = JSONObject(wakeSupervisor("skip-$group"))
        return JSONObject()
            .put("success", true)
            .put("action", "skip-requested")
            .put("group", group)
            .put("schedulerWake", wake)
    }

    fun wakeSupervisor(reason: String = "workmanager-fallback"): String = runCatching {
        stateDir.mkdirs()
        val schedulerState = RootFileStore.readEnv(File(stateDir, "scheduler.env"))
        val supervisorState = RootFileStore.readEnv(File(stateDir, "supervisor.env"))
        val schedulerPid = schedulerState.optLong("scheduler_pid", 0L)
        val schedulerTicks = schedulerState.optLong("scheduler_start_ticks", 0L)
        val supervisorPid = supervisorState.optLong("pid", 0L)
        val supervisorTicks = supervisorState.optLong("pid_start_ticks", 0L)
        val supervisorInstance = supervisorState.optString("instance_id")
        val schedulerInstance = schedulerState.optString("instance_id")

        val schedulerAlive = processMatches(
            schedulerPid,
            schedulerTicks,
            listOf("scheduler.sh", "service.sh"),
            schedulerState.optLong("heartbeat_epoch", 0L),
            expectedInstance = supervisorInstance.takeIf { it.isNotBlank() },
            actualInstance = schedulerInstance
        )
        if (schedulerAlive && signal(schedulerPid, "USR1")) {
            return@runCatching JSONObject()
                .put("success", true)
                .put("action", "signalled")
                .put("schedulerPid", schedulerPid)
                .put("reason", reason)
                .toString()
        }

        val supervisorAlive = processMatches(
            supervisorPid,
            supervisorTicks,
            listOf("supervisor.sh"),
            supervisorState.optLong("heartbeat_epoch", supervisorState.optLong("updated", 0L))
        )
        if (supervisorAlive) {
            signal(supervisorPid, "HUP")
            return@runCatching JSONObject()
                .put("success", true)
                .put("action", "supervisor-recovery-signalled")
                .put("supervisorPid", supervisorPid)
                .put("reason", reason)
                .toString()
        }

        val supervisor = File(moduleDir, "supervisor.sh")
        require(supervisor.isFile) { "supervisor_missing" }
        val log = File(stateDir, "logs/supervisor-launch.log").apply { parentFile?.mkdirs() }
        val command = "if command -v setsid >/dev/null 2>&1; then setsid /system/bin/sh ${shellQuote(supervisor.path)} </dev/null >>${shellQuote(log.path)} 2>&1 & " +
            "elif command -v nohup >/dev/null 2>&1; then nohup /system/bin/sh ${shellQuote(supervisor.path)} </dev/null >>${shellQuote(log.path)} 2>&1 & " +
            "else /system/bin/sh ${shellQuote(supervisor.path)} </dev/null >>${shellQuote(log.path)} 2>&1 & fi"
        val launcher = ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start()
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

    private fun runtimeJsonObject(): JSONObject {
        val scheduler = RootFileStore.readEnv(File(stateDir, "scheduler.env"))
        val supervisor = RootFileStore.readEnv(File(stateDir, "supervisor.env"))
        val queue = JSONArray()
        val queueFile = File(stateDir, "scheduler-queue.tsv")
        runCatching {
            if (queueFile.isFile) queueFile.useLines { lines ->
                lines.take(MAX_QUEUE_ITEMS).forEach { raw ->
                    val fields = raw.split('\t', limit = 7)
                    if (fields.size < 5) return@forEach
                    queue.put(
                        JSONObject()
                            .put("priority", fields[0].toIntOrNull() ?: 1)
                            .put("dueEpoch", fields[1].toLongOrNull() ?: 0L)
                            .put("group", fields[2])
                            .put("mode", fields[3])
                            .put("kind", fields[4])
                    )
                }
            }
        }
        val schedulerPid = scheduler.optLong("scheduler_pid", 0L)
        val supervisorPid = supervisor.optLong("pid", 0L)
        val supervisorInstance = supervisor.optString("instance_id")
        val schedulerHealthy = processMatches(
            schedulerPid,
            scheduler.optLong("scheduler_start_ticks", 0L),
            listOf("scheduler.sh", "service.sh"),
            scheduler.optLong("heartbeat_epoch", 0L),
            expectedInstance = supervisorInstance.takeIf { it.isNotBlank() },
            actualInstance = scheduler.optString("instance_id")
        )
        val supervisorHeartbeat = supervisor.optLong("heartbeat_epoch", supervisor.optLong("updated", 0L))
        val supervisorHealthy = processMatches(
            supervisorPid,
            supervisor.optLong("pid_start_ticks", 0L),
            listOf("supervisor.sh"),
            supervisorHeartbeat
        )
        val now = System.currentTimeMillis() / 1000L
        val heartbeatAge = if (supervisorHeartbeat > 0L) (now - supervisorHeartbeat).coerceAtLeast(0L) else -1L
        val config = configJsonObject()
        val publicState = when {
            config.optInt("enabled", 1) != 1 -> "disabled"
            scheduler.optString("state") == "running" -> "running"
            else -> "waiting"
        }
        return JSONObject()
            .put("state", publicState)
            .put("group", scheduler.optString("group"))
            .put("reason", publicSchedulerReason(publicState, scheduler.optString("reason")))
            .put("nextCheckEpoch", scheduler.optLong("next_check_epoch", 0L))
            .put("heartbeatEpoch", scheduler.optLong("heartbeat_epoch", 0L))
            .put("queueCount", scheduler.optInt("queue_count", queue.length()))
            .put("queueGroups", scheduler.optString("queue_groups"))
            .put("nextTask", scheduler.optString("next_task"))
            .put("blockedGroups", "")
            .put("nextRuns", nextRunsJsonObject(config, now))
            .put("queue", queue)
            .put("schedulerHealthy", schedulerHealthy)
            .put("supervisorHealthy", supervisorHealthy)
            .put("supervisorStatus", supervisor.optString("status", "unknown"))
            .put("supervisorRestartCount", supervisor.optInt("restart_count", 0))
            .put("supervisorHeartbeatEpoch", supervisorHeartbeat)
            .put("supervisorHeartbeatAge", heartbeatAge)
            .put("stale", !schedulerHealthy || !supervisorHealthy)
    }


    private fun publicSchedulerReason(state: String, raw: String): String = when {
        state == "disabled" -> "自动任务已关闭"
        state == "running" -> "执行中"
        raw.contains("息屏") -> "等待息屏后执行"
        raw.contains("充电") -> "等待充电后执行"
        raw.contains("电量") -> "等待电量满足条件"
        raw.contains("空闲") -> "等待系统空闲后执行"
        raw.contains("已有") || raw.contains("当前任务") -> "等待当前任务完成"
        raw.contains("恢复") || raw.contains("重新拉起") -> "后台正在自动恢复"
        raw.contains("重试") -> "等待自动重试"
        raw.contains("没有到期") || raw.contains("下次") -> "等待下次执行"
        else -> "等待自动执行"
    }

    private fun nextRunsJsonObject(config: JSONObject, now: Long): JSONObject {
        val result = JSONObject()
        val dailyEnabled = config.optInt("daily_schedule_enabled", 0) == 1
        for (group in GROUPS) {
            val enabled = config.optInt("schedule_${group}_enabled", 0) == 1
            if (!enabled || config.optInt("enabled", 1) != 1) {
                result.put(group, 0L)
                continue
            }
            val retryUntil = readEpoch(File(stateDir, "scheduler-retry-$group.until"))
            if (retryUntil > now) {
                result.put(group, retryUntil)
                continue
            }
            if (group != "organize" && dailyEnabled) {
                result.put(group, nextDailyEpoch(group, config, now))
                continue
            }
            val fallbackMinutes = when (group) {
                "rules" -> 360
                "fragment" -> 720
                "deep" -> 10_080
                "organize" -> 1_440
                else -> 60
            }
            val minutes = config.optInt(
                "schedule_${group}_minutes",
                config.optInt("schedule_${group}_hours", (fallbackMinutes + 59) / 60) * 60
            ).coerceIn(if (group == "organize") 15 else 5, 43_200)
            val last = readEpoch(File(stateDir, "last_${group}_run.epoch"))
            val due = if (last <= 0L) now else last + minutes * 60L
            result.put(group, due.coerceAtLeast(now))
        }
        return result
    }

    private fun nextDailyEpoch(group: String, config: JSONObject, now: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now * 1000L
            set(Calendar.HOUR_OF_DAY, config.optInt("daily_schedule_hour", 3).coerceIn(0, 23))
            set(Calendar.MINUTE, config.optInt("daily_schedule_minute", 30).coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val targetToday = calendar.timeInMillis / 1000L
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(now * 1000L))
        val lastCycle = runCatching { File(stateDir, "last_${group}_daily.date").readText().trim() }.getOrDefault("")
        if (lastCycle == today) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            return calendar.timeInMillis / 1000L
        }
        if (now < targetToday) return targetToday
        val graceSeconds = config.optInt("daily_grace_minutes", 240).coerceIn(15, 720) * 60L
        if (now <= targetToday + graceSeconds) return now
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        return calendar.timeInMillis / 1000L
    }

    private fun readEpoch(file: File): Long = runCatching {
        file.useLines { lines -> lines.firstOrNull()?.trim()?.toLongOrNull() ?: 0L }
    }.getOrDefault(0L)

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

    private fun processMatches(
        pid: Long,
        expectedStartTicks: Long,
        commandMarkers: List<String>,
        heartbeatEpoch: Long = 0L,
        expectedInstance: String? = null,
        actualInstance: String? = null
    ): Boolean {
        if (pid <= 1L) return false
        val processDir = File("/proc/$pid")
        if (!processDir.isDirectory) return false
        val actualTicks = processStartTicks(pid) ?: return false
        if (expectedStartTicks > 0L && actualTicks != expectedStartTicks) return false
        val cmdline = runCatching { File(processDir, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ') }
            .getOrDefault("")
        if (commandMarkers.none { cmdline.contains(it) }) return false
        if (heartbeatEpoch > 0L && System.currentTimeMillis() / 1000L - heartbeatEpoch > HEARTBEAT_STALE_SECONDS) return false
        if (!expectedInstance.isNullOrBlank() && actualInstance != expectedInstance) return false
        return true
    }

    private fun processStartTicks(pid: Long): Long? = runCatching {
        val raw = File("/proc/$pid/stat").readText()
        val tail = raw.substring(raw.lastIndexOf(')') + 1).trim().split(Regex("\\s+"))
        tail.getOrNull(19)?.toLongOrNull()
    }.getOrNull()

    private fun signal(pid: Long, signal: String): Boolean = runCatching {
        val process = ProcessBuilder("/system/bin/sh", "-c", "kill -$signal $pid")
            .redirectErrorStream(true)
            .start()
        process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun error(code: String, message: String, key: String = ""): String = JSONObject()
        .put("success", false)
        .put("error", code)
        .put("message", message)
        .apply { if (key.isNotBlank()) put("key", key) }
        .toString()

    companion object {
        private const val HEARTBEAT_STALE_SECONDS = 20L * 60L
        private const val MAX_QUEUE_ITEMS = 50
        private val GROUPS = listOf("cache", "empty", "rules", "fragment", "deep", "organize")
        val ALLOWED_CONFIG: Map<String, IntRange> = mapOf(
            "enabled" to 0..1,
            "screen_off_only" to 0..1,
            "charging_only" to 0..1,
            "device_idle_only" to 0..1,
            "min_battery" to 0..100,
            "max_battery_temp" to 30..60,
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
            "organize_run_immediately" to 0..1,
            "organizer_conflict_policy" to 0..2,
            "organizer_undo_retention" to 1..20,
            "organizer_media_scan" to 0..1,
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
