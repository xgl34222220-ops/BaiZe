package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class FileOrganizerScheduleSettings(
    val enabled: Boolean = false,
    val intervalMinutes: Int = 1_440,
    val chargingOnly: Boolean = false,
    val screenOffOnly: Boolean = true,
    val idleOnly: Boolean = false,
    val runImmediatelyOnEnable: Boolean = false,
    val conflictPolicy: Int = 1,
    val lastRunEpoch: Long = 0L,
    val lastResult: String = "尚未执行定时归类"
)

/**
 * WorkManager is deliberately only a watchdog. Root Supervisor is the only source of truth for
 * schedule configuration, due-time calculation, task fairness, conditions, locking and recovery.
 */
class FileOrganizerWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val session = runCatching { bindRootServiceWithRetry() }.getOrElse {
            writeResult(applicationContext, "Root 调度器连接失败：${it.message ?: it.javaClass.simpleName}")
            return Result.retry()
        }
        return try {
            val config = runCatching { JSONObject(session.service.getSchedulerConfig()) }.getOrNull()
            if (config == null) {
                writeResult(applicationContext, "Root 计划配置读取失败")
                return Result.retry()
            }
            // Keep the watchdog installed even while disabled. Re-enabling Root schedules then does
            // not depend on App process lifetime or a second WorkManager registration.
            if (config.optInt("enabled", 1) != 1) {
                writeResult(applicationContext, "Root 自动任务已关闭，看门狗保持待命")
                return Result.success()
            }
            val response = runCatching { JSONObject(session.service.runModuleTask("scheduler-wake")) }.getOrElse {
                writeResult(applicationContext, "Root 调度器唤醒失败：${it.message ?: it.javaClass.simpleName}")
                return Result.retry()
            }
            if (!response.optBoolean("success", false)) {
                writeResult(applicationContext, response.optString("message", response.optString("error", "调度器唤醒失败")))
                Result.retry()
            } else {
                val action = when (response.optString("action")) {
                    "signalled" -> "已唤醒 Root 调度器检查公平队列"
                    "supervisor-started" -> "Root Supervisor 已恢复并检查队列"
                    "supervisor-recovery-signalled" -> "已要求 Supervisor 恢复调度进程"
                    else -> "Root 调度器已检查队列"
                }
                writeResult(applicationContext, action)
                Result.success()
            }
        } finally {
            session.close()
        }
    }

    private suspend fun bindRootServiceWithRetry(): RootSession {
        var lastError: Throwable? = null
        repeat(ROOT_BIND_ATTEMPTS) { attempt ->
            val result = runCatching { withTimeout(ROOT_BIND_TIMEOUT_MS) { bindRootService() } }
            result.getOrNull()?.let { return it }
            lastError = result.exceptionOrNull()
            if (attempt + 1 < ROOT_BIND_ATTEMPTS) delay(ROOT_BIND_RETRY_DELAY_MS * (attempt + 1L))
        }
        throw lastError ?: IllegalStateException("Root 服务连接失败")
    }

    private suspend fun bindRootService(): RootSession = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val mainHandler = Handler(Looper.getMainLooper())
            lateinit var connection: ServiceConnection
            fun unbindOnMainThread() {
                val action = Runnable { runCatching { RootService.unbind(connection) } }
                if (Looper.myLooper() == Looper.getMainLooper()) action.run() else mainHandler.post(action)
            }
            fun fail(message: String) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
            }
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val root = IProfileRootService.Stub.asInterface(binder)
                    if (root == null) {
                        fail("Root Binder 为空")
                        unbindOnMainThread()
                        return
                    }
                    if (continuation.isActive) continuation.resume(RootSession(root, connection))
                    else unbindOnMainThread()
                }
                override fun onServiceDisconnected(name: ComponentName?) = fail("Root 服务已断开")
                override fun onBindingDied(name: ComponentName?) { fail("Root 服务绑定失效"); unbindOnMainThread() }
                override fun onNullBinding(name: ComponentName?) { fail("Root 服务未返回 Binder"); unbindOnMainThread() }
            }
            continuation.invokeOnCancellation { unbindOnMainThread() }
            runCatching {
                RootService.bind(
                    Intent(applicationContext, BaiZeProfileRootService::class.java)
                        .addCategory(RootService.CATEGORY_DAEMON_MODE),
                    connection
                )
            }.onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
        }
    }

    private data class RootSession(val service: IProfileRootService, val connection: ServiceConnection) {
        suspend fun close() = withContext(Dispatchers.Main.immediate) { runCatching { RootService.unbind(connection) } }
    }

    companion object {
        private const val PREFS = "file_organizer_schedule"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INTERVAL_MINUTES = "interval_minutes"
        private const val LEGACY_KEY_INTERVAL_HOURS = "interval_hours"
        private const val KEY_CHARGING = "charging_only"
        private const val KEY_SCREEN_OFF = "screen_off_only"
        private const val KEY_IDLE = "idle_only"
        private const val KEY_RUN_IMMEDIATELY = "run_immediately_on_enable"
        private const val KEY_CONFLICT_POLICY = "conflict_policy"
        private const val KEY_LAST_RUN = "last_run_epoch"
        private const val KEY_LAST_RESULT = "last_result"
        private const val UNIQUE_WORK = "baize_root_scheduler_watchdog"
        private const val ROOT_BIND_TIMEOUT_MS = 20_000L
        private const val ROOT_BIND_ATTEMPTS = 3
        private const val ROOT_BIND_RETRY_DELAY_MS = 800L
        private const val WATCHDOG_INTERVAL_MINUTES = 15L
        private const val WATCHDOG_FLEX_MINUTES = 5L
        val ALLOWED_INTERVALS = listOf(30, 60, 360, 720, 1_440, 4_320, 10_080)

        fun loadSettings(context: Context): FileOrganizerScheduleSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val migrated = if (prefs.contains(KEY_INTERVAL_MINUTES)) {
                prefs.getInt(KEY_INTERVAL_MINUTES, 1_440)
            } else prefs.getInt(LEGACY_KEY_INTERVAL_HOURS, 24).coerceAtLeast(1) * 60
            val interval = migrated.let { if (it in ALLOWED_INTERVALS) it else 1_440 }
            return FileOrganizerScheduleSettings(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                intervalMinutes = interval,
                chargingOnly = prefs.getBoolean(KEY_CHARGING, false),
                screenOffOnly = prefs.getBoolean(KEY_SCREEN_OFF, true),
                idleOnly = prefs.getBoolean(KEY_IDLE, false),
                runImmediatelyOnEnable = prefs.getBoolean(KEY_RUN_IMMEDIATELY, false),
                conflictPolicy = prefs.getInt(KEY_CONFLICT_POLICY, 1).coerceIn(0, 2),
                lastRunEpoch = prefs.getLong(KEY_LAST_RUN, 0L),
                lastResult = prefs.getString(KEY_LAST_RESULT, "尚未执行定时归类") ?: "尚未执行定时归类"
            )
        }

        fun fromRootConfig(context: Context, json: JSONObject): FileOrganizerScheduleSettings {
            val local = loadSettings(context)
            val minutes = json.optInt("schedule_organize_minutes", json.optInt("schedule_organize_hours", 24) * 60)
                .let { if (it in ALLOWED_INTERVALS) it else 1_440 }
            return local.copy(
                enabled = json.optInt("schedule_organize_enabled", 0) == 1,
                intervalMinutes = minutes,
                chargingOnly = json.optInt("organize_charging_only", 0) == 1,
                screenOffOnly = json.optInt("organize_screen_off_only", 1) == 1,
                idleOnly = json.optInt("organize_device_idle_only", 0) == 1,
                runImmediatelyOnEnable = json.optInt("organize_run_immediately", 0) == 1,
                conflictPolicy = json.optInt("organizer_conflict_policy", 1).coerceIn(0, 2)
            )
        }

        fun cacheUiSettings(context: Context, settings: FileOrganizerScheduleSettings) {
            val safe = settings.copy(intervalMinutes = settings.intervalMinutes.takeIf { it in ALLOWED_INTERVALS } ?: 1_440)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, safe.enabled)
                .putInt(KEY_INTERVAL_MINUTES, safe.intervalMinutes)
                .remove(LEGACY_KEY_INTERVAL_HOURS)
                .putBoolean(KEY_CHARGING, safe.chargingOnly)
                .putBoolean(KEY_SCREEN_OFF, safe.screenOffOnly)
                .putBoolean(KEY_IDLE, safe.idleOnly)
                .putBoolean(KEY_RUN_IMMEDIATELY, safe.runImmediatelyOnEnable)
                .putInt(KEY_CONFLICT_POLICY, safe.conflictPolicy)
                .apply()
        }

        fun saveAndSchedule(context: Context, settings: FileOrganizerScheduleSettings) {
            cacheUiSettings(context, settings)
            ensureWatchdog(context)
        }

        fun ensureWatchdog(context: Context) {
            val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
            val request = PeriodicWorkRequestBuilder<FileOrganizerWorker>(
                WATCHDOG_INTERVAL_MINUTES, TimeUnit.MINUTES,
                WATCHDOG_FLEX_MINUTES, TimeUnit.MINUTES
            ).setInitialDelay(WATCHDOG_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun lastRunText(context: Context, settings: FileOrganizerScheduleSettings): String {
            if (settings.lastRunEpoch <= 0L) return "尚未执行"
            return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(settings.lastRunEpoch))
        }

        fun intervalLabel(minutes: Int): String = when (minutes) {
            30 -> "30分钟"; 60 -> "1小时"; 360 -> "6小时"; 720 -> "12小时"
            1_440 -> "每天"; 4_320 -> "每3天"; 10_080 -> "每周"; else -> "${minutes}分钟"
        }

        fun conflictPolicyLabel(value: Int): String = when (value) {
            0 -> "同名时跳过"; 2 -> "相同去重，不同重命名"; else -> "自动重命名"
        }

        fun recordResult(context: Context, result: String) = writeResult(context, result)
        private fun writeResult(context: Context, result: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_RUN, System.currentTimeMillis())
                .putString(KEY_LAST_RESULT, result)
                .apply()
        }
    }
}
