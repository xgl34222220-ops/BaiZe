package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.PowerManager
import android.text.format.Formatter
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class FileOrganizerScheduleSettings(
    val enabled: Boolean = false,
    val intervalHours: Int = 24,
    val chargingOnly: Boolean = false,
    val screenOffOnly: Boolean = true,
    val lastRunEpoch: Long = 0L,
    val lastResult: String = "尚未执行定时归类"
)

class FileOrganizerWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = loadSettings(applicationContext)
        if (!settings.enabled) return Result.success()

        val power = applicationContext.getSystemService(PowerManager::class.java)
        if (settings.screenOffOnly && power?.isInteractive == true) {
            writeResult(applicationContext, "等待息屏后执行")
            return Result.retry()
        }

        val session = runCatching { withTimeout(ROOT_BIND_TIMEOUT_MS) { bindRootService() } }.getOrElse {
            writeResult(applicationContext, "Root 服务连接失败：${it.message ?: it.javaClass.simpleName}")
            return Result.retry()
        }

        return try {
            val scan = runCatching { JSONObject(session.service.scanFileOrganizer()) }.getOrElse {
                writeResult(applicationContext, "扫描失败：${it.message ?: it.javaClass.simpleName}")
                return Result.retry()
            }
            if (scan.has("error")) {
                writeResult(applicationContext, scan.optString("message", "文件归类扫描失败"))
                return Result.retry()
            }
            if (scan.optBoolean("cancelled")) {
                writeResult(applicationContext, "定时归类已停止")
                return Result.success()
            }

            val snapshotId = scan.optString("snapshotId")
            val total = scan.optInt("total")
            if (snapshotId.isBlank() || total == 0) {
                writeResult(applicationContext, "扫描完成，没有需要归类的新文件")
                return Result.success()
            }

            val request = JSONObject().put("all", true).toString()
            val applied = runCatching { JSONObject(session.service.applyFileOrganizer(snapshotId, request)) }.getOrElse {
                writeResult(applicationContext, "归类失败：${it.message ?: it.javaClass.simpleName}")
                return Result.retry()
            }
            if (applied.has("error")) {
                writeResult(applicationContext, applied.optString("message", "定时归类失败"))
                return Result.retry()
            }

            val moved = applied.optInt("moved")
            val skipped = applied.optInt("skipped")
            val failed = applied.optInt("failed")
            val size = Formatter.formatFileSize(applicationContext, applied.optLong("bytes"))
            writeResult(applicationContext, "已归类 $moved/$total 个文件 · $size；跳过 $skipped 个，失败 $failed 个")
            if (failed == 0) Result.success() else Result.retry()
        } finally {
            session.close()
        }
    }

    private suspend fun bindRootService(): RootSession = suspendCancellableCoroutine { continuation ->
        lateinit var connection: ServiceConnection
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val root = IProfileRootService.Stub.asInterface(binder)
                if (root == null) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Root Binder 为空"))
                    return
                }
                if (continuation.isActive) continuation.resume(RootSession(root, connection))
                else runCatching { RootService.unbind(connection) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Root 服务已断开"))
            }
        }
        continuation.invokeOnCancellation { runCatching { RootService.unbind(connection) } }
        runCatching {
            RootService.bind(
                Intent(applicationContext, BaiZeProfileRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
        }.onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
    }

    private data class RootSession(val service: IProfileRootService, val connection: ServiceConnection) {
        fun close() { runCatching { RootService.unbind(connection) } }
    }

    companion object {
        private const val PREFS = "file_organizer_schedule"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INTERVAL = "interval_hours"
        private const val KEY_CHARGING = "charging_only"
        private const val KEY_SCREEN_OFF = "screen_off_only"
        private const val KEY_LAST_RUN = "last_run_epoch"
        private const val KEY_LAST_RESULT = "last_result"
        private const val UNIQUE_WORK = "baize_file_organizer_periodic"
        private const val ROOT_BIND_TIMEOUT_MS = 20_000L
        private val ALLOWED_INTERVALS = setOf(6, 12, 24, 72, 168)

        fun loadSettings(context: Context): FileOrganizerScheduleSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val interval = prefs.getInt(KEY_INTERVAL, 24).let { if (it in ALLOWED_INTERVALS) it else 24 }
            return FileOrganizerScheduleSettings(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                intervalHours = interval,
                chargingOnly = prefs.getBoolean(KEY_CHARGING, false),
                screenOffOnly = prefs.getBoolean(KEY_SCREEN_OFF, true),
                lastRunEpoch = prefs.getLong(KEY_LAST_RUN, 0L),
                lastResult = prefs.getString(KEY_LAST_RESULT, "尚未执行定时归类") ?: "尚未执行定时归类"
            )
        }

        fun saveAndSchedule(context: Context, settings: FileOrganizerScheduleSettings) {
            val safe = settings.copy(intervalHours = if (settings.intervalHours in ALLOWED_INTERVALS) settings.intervalHours else 24)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, safe.enabled)
                .putInt(KEY_INTERVAL, safe.intervalHours)
                .putBoolean(KEY_CHARGING, safe.chargingOnly)
                .putBoolean(KEY_SCREEN_OFF, safe.screenOffOnly)
                .apply()

            val workManager = WorkManager.getInstance(context)
            if (!safe.enabled) {
                workManager.cancelUniqueWork(UNIQUE_WORK)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiresCharging(safe.chargingOnly)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<FileOrganizerWorker>(
                safe.intervalHours.toLong(), TimeUnit.HOURS, 15, TimeUnit.MINUTES
            )
                .setInitialDelay(safe.intervalHours.toLong(), TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun lastRunText(context: Context, settings: FileOrganizerScheduleSettings): String {
            if (settings.lastRunEpoch <= 0L) return "尚未执行"
            return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(settings.lastRunEpoch))
        }

        private fun writeResult(context: Context, result: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_RUN, System.currentTimeMillis())
                .putString(KEY_LAST_RESULT, result)
                .apply()
        }
    }
}
