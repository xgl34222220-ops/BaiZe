package io.github.xgl34222220.baize.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Process
import android.os.SystemClock
import io.github.xgl34222220.baize.root.InstantCacheEngine
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Dedicated Shizuku UserService. It never exposes a shell string or any file deletion API. */
internal fun shizukuCachePermission(context: Context, uid: Int = Process.myUid()): Boolean =
    uid == 0 || (uid == 2000 && runCatching {
        context.checkPermission("android.permission.INTERNAL_DELETE_CACHE_FILES", Process.myPid(), uid) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false))

internal const val CACHE_PERMISSION_MESSAGE = "当前系统未向 Shizuku 授予只清缓存权限；请打开系统缓存设置手动清缓存，或使用 Root 清理。"

class ShizukuCacheService(private val context: Context) : IShizukuCacheService.Stub() {
    private val ownerUid = context.applicationInfo.uid
    private val cancelled = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val engine = InstantCacheEngine(cancelled) { }
    private fun checkCaller() {
        require(ownerUid >= 10_000 && Binder.getCallingUid() == ownerUid) { "caller_mismatch" }
        require(Process.myUid() == 2000 || Process.myUid() == 0) { "privilege_unavailable" }
    }
    override fun capabilities(): String {
        checkCaller()
        val permission = shizukuCachePermission(context)
        val supported = permission && engine.supportsCacheOnly()
        return JSONObject().put("uid", Process.myUid()).put("user", ownerUid / 100_000)
            .put("cacheOnly", supported).put("message", when {
                !permission -> CACHE_PERMISSION_MESSAGE
                !supported -> "当前系统不支持只清缓存，未执行清理；请打开系统缓存设置。"
                else -> "选择应用后确认清理当前缓存"
            }).toString()
    }
    override fun clearCaches(packagesJson: String?): String {
        checkCaller()
        require(packagesJson != null && packagesJson.length <= 8192)
        if (!shizukuCachePermission(context)) return JSONObject().put("success", false)
            .put("error", "cache_permission_unavailable").put("message", CACHE_PERMISSION_MESSAGE).toString()
        check(running.compareAndSet(false, true)) { "缓存清理正在运行" }
        cancelled.set(false)
        return try { engine.run(JSONObject().put("packages", JSONArray(packagesJson))
            .put("userId", ownerUid / 100_000).toString(), SystemClock.elapsedRealtime()) }
        finally { running.set(false) }
    }
    override fun cancel() { checkCaller(); cancelled.set(true) }
    override fun destroy() {
        val caller = Binder.getCallingUid()
        require(caller == ownerUid || caller == 0 || caller == 2000)
        cancelled.set(true)
        kotlin.system.exitProcess(0)
    }
}
