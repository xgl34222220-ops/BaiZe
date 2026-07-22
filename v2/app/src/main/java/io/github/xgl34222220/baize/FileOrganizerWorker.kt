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
    val runImmediatelyOnEnable: Boolean = false,
    val lastRunEpoch: Long = 0L,
    val lastResult: String = "尚未执行定时归类"
)

/**
 * WorkManager is only a watchdog. Root Supervisor owns due-time calculation, conditions,
 * task locking, execution, recovery and history. This worker merely wakes that scheduler.
 */
class FileOrganizerWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = loadSettings(applicationContext)
        if (!settings.enabled) return Result.success()

        val session = runCatching { bindRootServiceWithRetry() }.getOrElse {
            writeResult(applicationContext, "Root 调度器唤醒失败：${it.message ?: it.javaClass.simpleName}")
            return Result.retry()
        }

        return try {
            val response = runCatching { JSONObject(session.service.runModuleTask("scheduler-wake")) }.getOrElse {
                writeResult(applicationContext, "Root 调度器唤醒失败：${it.message ?: it.javaClass.simpleName}")
                return Result.retry()
            }
            if (!response.optBoolean("success", false)) {
                writeResult(applicationContext, response.optString("message", response.optString("error", "调度器唤醒失败")))
                Result.retry()
            } else {
                val action = when (response.optString("action")) {
                    "signalled" -> "已唤醒 Root 调度器检查计划"
                    "supervisor-started" -> "Root Supervisor 已恢复并检查计划"
                    "supervisor-alive" -> "Root Supervisor 正常运行"
                    else -> "Root 调度器已检查计划"
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
                override fun onBindingDied(name: ComponentName?) {
                    fail("Root 服务绑定失效")
                    unbindOnMainThread()
                }
                override fun onNullBinding(name: ComponentName?) {
                    fail("Root 服务未返回 Binder")
                    unbindOnMainThread()
                }
            }

            continuation.invokeOnCancellation { unbindOnMainThread() }
            runCatching {
                RootService.bind(
                    Intent(applicationContext, BaiZeProfileRootService::class.java)
                        .addCategory(RootService.CATEGORY_DAEMON_MODE),
                    connection
                )
            }.onFailure {
                if (continuation.isActive) continuation.resumeWithException(it)
            }
        }
    }

    private data class RootSession(val service: IProfileRootService, val connection: ServiceConnection) {
        suspend fun close() = withContext(Dispatchers.Main.immediate) {
            runCatching { RootService.unbind(connection) }
        }
    }

    companion object {
        private const val PREFS = "file_organizer_schedule"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INTERVAL_MINUTES = "interval_minutes"
        private const val LEGACY_KEY_INTERVAL_HOURS = "interval_hours"
        private const val KEY_CHARGING = "charging_only"
        private const val KEY_SCREEN_OFF = "screen_off_only"
        private const val KEY_RUN_IMMEDIATELY = "run_immediately_on_enable"
        private const val KEY_LAST_RUN = "last_run_epoch"
        private const val KEY_LAST_RESULT = "last_result"
        private const val UNIQUE_WORK = "baize_root_scheduler_watchdog"
        private const val ROOT_BIND_TIMEOUT_MS = 20_000L
        private const val ROOT_BIND_ATTEMPTS = 3
        private const val ROOT_BIND_RETRY_DELAY_MS = 800L
        val ALLOWED_INTERVALS = listOf(30, 60, 360, 720, 1_440, 4_320, 10_080)

        fun loadSettings(context: Context): FileOrganizerScheduleSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val migrated = if (prefs.contains(KEY_INTERVAL_MINUTES)) {
                prefs.getInt(KEY_INTERVAL_MINUTES, 1_440)
            } else {
                prefs.getInt(LEGACY_KEY_INTERVAL_HOURS, 24).coerceAtLeast(1) * 60
            }
            val interval = migrated.let { if (it in ALLOWED_INTERVALS) it else 1_440 }
            return FileOrganizerScheduleSettings(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                intervalMinutes = interval,
                chargingOnly = prefs.getBoolean(KEY_CHARGING, false),
                screenOffOnly = prefs.getBoolean(KEY_SCREEN_OFF, true),
                runImmediatelyOnEnable = prefs.getBoolean(KEY_RUN_IMMEDIATELY, false),
                lastRunEpoch = prefs.getLong(KEY_LAST_RUN, 0L),
                lastResult = prefs.getString(KEY_LAST_RESULT, "尚未执行定时归类") ?: "尚未执行定时归类"
            )
        }

        fun saveAndSchedule(context: Context, settings: FileOrganizerScheduleSettings) {
            val safe = settings.copy(
                intervalMinutes = if (settings.intervalMinutes in ALLOWED_INTERVALS) settings.intervalMinutes else 1_440
            )
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, safe.enabled)
                .putInt(KEY_INTERVAL_MINUTES, safe.intervalMinutes)
                .remove(LEGACY_KEY_INTERVAL_HOURS)
                .putBoolean(KEY_CHARGING, safe.chargingOnly)
                .putBoolean(KEY_SCREEN_OFF, safe.screenOffOnly)
                .putBoolean(KEY_RUN_IMMEDIATELY, safe.runImmediatelyOnEnable)
                .apply()

            val workManager = WorkManager.getInstance(context)
            if (!safe.enabled) {
                workManager.cancelUniqueWork(UNIQUE_WORK)
                return
            }
            val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
            val request = PeriodicWorkRequestBuilder<FileOrganizerWorker>(
                WATCHDOG_INTERVAL_MINUTES, TimeUnit.MINUTES,
                WATCHDOG_FLEX_MINUTES, TimeUnit.MINUTES
            )
                .setInitialDelay(WATCHDOG_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun lastRunText(context: Context, settings: FileOrganizerScheduleSettings): String {
            if (settings.lastRunEpoch <= 0L) return "尚未执行"
            return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(settings.lastRunEpoch))
        }

        fun intervalLabel(minutes: Int): String = when (minutes) {
            30 -> "30分钟"
            60 -> "1小时"
            360 -> "6小时"
            720 -> "12小时"
            1_440 -> "每天"
            4_320 -> "每3天"
            10_080 -> "每周"
            else -> "${minutes}分钟"
        }

        fun recordResult(context: Context, result: String) = writeResult(context, result)

        private fun writeResult(context: Context, result: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_RUN, System.currentTimeMillis())
                .putString(KEY_LAST_RESULT, result)
                .apply()
        }

        private const val WATCHDOG_INTERVAL_MINUTES = 15L
        private const val WATCHDOG_FLEX_MINUTES = 5L
    }
}
