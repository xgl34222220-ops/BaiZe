package io.github.xgl34222220.baize

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** File management needs read AND write on legacy storage; API 30 uses a special grant. */
internal object SharedStorageAccess {
    val legacyPermissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    fun granted(context: Context): Boolean {
        val granted = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
            else legacyPermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        // Settings grants resume through the access check, without a runtime-result callback.
        if (granted && Build.VERSION.SDK_INT < 30) {
            val prefs = context.getSharedPreferences("storage-permission", Context.MODE_PRIVATE)
            if (prefs.getBoolean("denied", false)) prefs.edit().remove("denied").apply()
        }
        return granted
    }
    val label: String get() = if (Build.VERSION.SDK_INT >= 30) "所有文件访问" else "存储读写权限"
    fun settings(context: Context): Intent = Intent(if (Build.VERSION.SDK_INT >= 30)
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION else Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"))
}

internal class StoragePermissionRequest(private val activity: ComponentActivity, private val onGranted: () -> Unit) {
    private val prefs by lazy { activity.getSharedPreferences("storage-permission", Context.MODE_PRIVATE) }
    private val request = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (SharedStorageAccess.granted(activity)) onGranted() else {
            prefs.edit().putBoolean("denied", true).apply()
            Toast.makeText(activity, "未授予存储读写权限，扫描尚未开始", Toast.LENGTH_LONG).show()
        }
    }
    fun launch() {
        if (SharedStorageAccess.granted(activity)) { onGranted(); return }
        val deniedPermanently = prefs.getBoolean("denied", false) &&
            SharedStorageAccess.legacyPermissions.none(activity::shouldShowRequestPermissionRationale)
        if (Build.VERSION.SDK_INT < 30 && !deniedPermanently) request.launch(SharedStorageAccess.legacyPermissions)
        else runCatching { activity.startActivity(SharedStorageAccess.settings(activity)) }.onFailure {
            if (Build.VERSION.SDK_INT >= 30) runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                .onFailure { unavailable() } else unavailable()
        }
    }
    private fun unavailable() { Toast.makeText(activity, "请在系统设置中开启${SharedStorageAccess.label}", Toast.LENGTH_LONG).show() }
}
