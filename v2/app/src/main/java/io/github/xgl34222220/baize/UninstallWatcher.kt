package io.github.xgl34222220.baize

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 卸载残留提醒，参考 SD Maid SE 的 UninstallWatcherReceiver：
 * 只在系统确认应用被完整卸载（非覆盖更新）后发出一条低优先级通知，
 * 接收器内不做任何扫描或文件访问，扫描由用户点通知后在前台工作台完成。
 */
internal object UninstallWatcherPolicy {
    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    fun shouldNotify(action: String?, packageName: String?, replacing: Boolean, enabled: Boolean, selfPackage: String): Boolean {
        if (!enabled || replacing) return false
        if (action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return false
        val name = packageName?.trim().orEmpty()
        return name.length in 3..255 && name.matches(PACKAGE_NAME) && name != selfPackage
    }

    /** 同一应用重复卸载/安装只保留一条通知；不同应用互不覆盖。 */
    fun notificationId(packageName: String): Int = 0x5100 + (packageName.hashCode() and 0x0FFF)
}

internal object UninstallWatcherSettings {
    private const val PREFS = "uninstall-watcher"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    /** 关闭时同时禁用接收器组件，系统不再为卸载事件唤醒白泽。 */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, UninstallWatcherReceiver::class.java),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        if (!enabled) runCatching {
            context.getSystemService(NotificationManager::class.java)?.activeNotifications
                ?.filter { it.tag == UninstallResidueNotifier.TAG }
                ?.forEach { NotificationManagerCompat.from(context).cancel(it.tag, it.id) }
        }
    }
}

internal object UninstallResidueNotifier {
    const val CHANNEL = "baize_uninstall_residue"
    const val TAG = "baize-uninstall-residue"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "卸载残留提醒", NotificationManager.IMPORTANCE_LOW).apply {
                description = "卸载应用后提示扫描它留下的目录，不会自动删除任何内容"
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun show(context: Context, packageName: String) {
        if (!canPost(context)) return
        ensureChannel(context)
        val id = UninstallWatcherPolicy.notificationId(packageName)
        val scan = PendingIntent.getActivity(context, id,
            Intent(context, ScanWorkbenchActivity::class.java)
                .putExtra(ScanWorkbenchActivity.EXTRA_PROFILE, "corpses")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val disable = PendingIntent.getBroadcast(context, 0x50FF,
            Intent(context, UninstallWatcherActionReceiver::class.java).setAction(UninstallWatcherActionReceiver.ACTION_DISABLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_baize_notification)
            .setContentTitle("应用已卸载：$packageName")
            .setContentText("可扫描它留在 Android/data 与 Android/obb 的残留")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$packageName 已卸载。点按扫描卸载残留，结果需你确认后才会清理；重新安装的应用会自动跳过。"))
            .setContentIntent(scan)
            .addAction(0, "扫描残留", scan)
            .addAction(0, "不再提醒", disable)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(TAG, id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }
}

/** Manifest receiver for PACKAGE_FULLY_REMOVED (exempt from implicit-broadcast limits). Light work only. */
class UninstallWatcherReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        val packageName = intent.data?.schemeSpecificPart
        if (!UninstallWatcherPolicy.shouldNotify(intent.action, packageName,
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false),
                UninstallWatcherSettings.isEnabled(context), context.packageName)) return
        UninstallResidueNotifier.show(context, requireNotNull(packageName).trim())
    }
}

/** Private receiver for the notification's “不再提醒” action. */
class UninstallWatcherActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DISABLE) UninstallWatcherSettings.setEnabled(context, false)
    }

    companion object {
        const val ACTION_DISABLE = "io.github.xgl34222220.baize.action.DISABLE_UNINSTALL_WATCHER"
    }
}
