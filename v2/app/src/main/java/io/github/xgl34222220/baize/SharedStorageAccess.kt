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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

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
    private var rootTried = false
    fun launch() {
        if (SharedStorageAccess.granted(activity)) { onGranted(); return }
        // Root 用户：先由 Root 服务开启（只改白泽自身的 appops），失败或超时再打开系统设置。
        if (Build.VERSION.SDK_INT >= 30 && !rootTried && ApkProtectionStore.rootWasUsed(activity)) { rootTried = true; grantViaRoot(); return }
        val deniedPermanently = prefs.getBoolean("denied", false) &&
            SharedStorageAccess.legacyPermissions.none(activity::shouldShowRequestPermissionRationale)
        if (Build.VERSION.SDK_INT < 30 && !deniedPermanently) request.launch(SharedStorageAccess.legacyPermissions)
        else runCatching { activity.startActivity(SharedStorageAccess.settings(activity)) }.onFailure {
            if (Build.VERSION.SDK_INT >= 30) runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                .onFailure { unavailable() } else unavailable()
        }
    }
    private fun grantViaRoot() {
        var done = false
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
                if (done || binder == null) return
                val remote = io.github.xgl34222220.baize.root.RootServiceClients.profile(binder, activity.cacheDir)
                val self = this
                activity.lifecycleScope.launch {
                    val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { RootStorageGrant.request(activity, remote) }.getOrDefault(false)
                    }
                    if (done) return@launch
                    done = true
                    runCatching { com.topjohnwu.superuser.ipc.RootService.unbind(self) }
                    if (ok) onGranted() else this@StoragePermissionRequest.launch()
                }
            }
            override fun onServiceDisconnected(name: android.content.ComponentName?) = Unit
        }
        val bound = runCatching {
            com.topjohnwu.superuser.ipc.RootService.bind(Intent(activity, io.github.xgl34222220.baize.root.BaiZeProfileRootService::class.java)
                .addCategory(com.topjohnwu.superuser.ipc.RootService.CATEGORY_DAEMON_MODE), connection)
        }.isSuccess
        if (!bound) { done = true; this@StoragePermissionRequest.launch(); return }
        activity.lifecycleScope.launch {
            kotlinx.coroutines.delay(5_000)
            if (!done) { done = true; runCatching { com.topjohnwu.superuser.ipc.RootService.unbind(connection) }; this@StoragePermissionRequest.launch() }
        }
    }
    private fun unavailable() { Toast.makeText(activity, "请在系统设置中开启${SharedStorageAccess.label}", Toast.LENGTH_LONG).show() }
}

/**
 * 已授权 Root 的设备上，由 Root 服务执行 `cmd appops set <包名> MANAGE_EXTERNAL_STORAGE allow`
 * 开启「所有文件访问」（安装包页 3.0.0 起已这样做）。只改白泽自己的权限，不碰任何文件；失败时仍显示系统设置按钮。
 */
internal object RootStorageGrant {
    fun request(context: Context, remote: io.github.xgl34222220.baize.root.IProfileRootService,
        pause: (Long) -> Unit = { Thread.sleep(it) }): Boolean {
        if (SharedStorageAccess.granted(context)) return true
        runCatching { io.github.xgl34222220.baize.root.RootServiceClients.profileExchange(remote, context.cacheDir, "ensureAllFilesAccess") }
        repeat(10) {
            if (SharedStorageAccess.granted(context)) return true
            pause(50)
        }
        return SharedStorageAccess.granted(context)
    }
}
