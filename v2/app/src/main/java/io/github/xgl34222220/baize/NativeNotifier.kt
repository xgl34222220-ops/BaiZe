package io.github.xgl34222220.baize

import android.annotation.SuppressLint
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NativeNotifier {
    private const val CHANNEL_TASKS = "baize_clean_tasks"
    private const val CHANNEL_RULES = "baize_rule_updates"
    private const val NOTIFICATION_ID = 2101
    private const val RULE_NOTIFICATION_ID = 2102

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TASKS, "白泽清理任务", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "显示手动清理与扫描任务的完成结果"
                enableVibration(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RULES, "白泽规则更新", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "显示已通过签名验证的规则版本和自动更新结果"
                enableVibration(false)
            }
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun showTaskResult(context: Context, title: String, summary: String, details: String) {
        if (!canPost(context)) return
        ensureChannel(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            2101,
            Intent(context, MiuixDashboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_TASKS)
            .setSmallIcon(R.drawable.ic_baize)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify("baize-app-task", NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission or OEM policy changed after the explicit permission check.
        }
    }

    @SuppressLint("MissingPermission")
    fun showRuleUpdate(context: Context, title: String, summary: String, details: String) {
        if (!canPost(context)) return
        ensureChannel(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            RULE_NOTIFICATION_ID,
            Intent(context, RuleUpdateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_RULES)
            .setSmallIcon(R.drawable.ic_baize)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            NotificationManagerCompat.from(context)
                .notify("baize-rule-update", RULE_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission or OEM policy changed after the explicit permission check.
        }
    }
}
