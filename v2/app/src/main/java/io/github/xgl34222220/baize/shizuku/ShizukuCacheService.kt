package io.github.xgl34222220.baize.shizuku

import android.content.Context
import android.os.Binder
import android.os.Process
import android.os.SystemClock
import io.github.xgl34222220.baize.root.InstantCacheEngine
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Dedicated Shizuku UserService. It never exposes a shell string or any file deletion API. */
class ShizukuCacheService(context: Context) : IShizukuCacheService.Stub() {
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
        return JSONObject().put("uid", Process.myUid()).put("user", ownerUid / 100_000)
            .put("cacheOnly", engine.supportsCacheOnly()).toString()
    }
    override fun clearCaches(packagesJson: String?): String {
        checkCaller()
        require(packagesJson != null && packagesJson.length <= 8192)
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
