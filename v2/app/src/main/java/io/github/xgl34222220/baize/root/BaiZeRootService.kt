package io.github.xgl34222220.baize.root

import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Alpha 1 只验证 App <-> Root Binder 链路与非递归候选扫描。
 * 此版本不执行删除，避免在扫描引擎完成前引入误删风险。
 */
class BaiZeRootService : RootService() {
    private val cancelled = AtomicBoolean(false)

    private val binder = object : IBaiZeRootService.Stub() {
        override fun ping(): String = JSONObject()
            .put("uid", Process.myUid())
            .put("root", Process.myUid() == 0)
            .put("moduleV1", File("/data/adb/modules/safesweep/module.prop").isFile)
            .put("moduleV2", File("/data/adb/modules/baize_v2_alpha/module.prop").isFile)
            .toString()

        override fun scanPreview(): String {
            cancelled.set(false)
            return previewScanner()
        }

        override fun cancelCurrentTask() {
            cancelled.set(true)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun previewScanner(): String {
        val started = SystemClock.elapsedRealtime()
        var internalCache = 0
        var internalCodeCache = 0
        var externalCache = 0
        var packagesVisited = 0
        var usersVisited = 0
        val samples = JSONArray()

        fun addSample(file: File) {
            if (samples.length() < 12) samples.put(file.absolutePath)
        }

        fun scanAppRoot(root: File) {
            val users = root.listFiles()?.filter { dir ->
                dir.isDirectory && dir.name.isNotEmpty() && dir.name.all(Char::isDigit)
            }.orEmpty()

            for (user in users) {
                if (cancelled.get()) return
                usersVisited++
                val packages = user.listFiles()?.filter(File::isDirectory).orEmpty()
                for (pkg in packages) {
                    if (cancelled.get()) return
                    packagesVisited++
                    val cache = File(pkg, "cache")
                    if (cache.isDirectory) {
                        internalCache++
                        addSample(cache)
                    }
                    val codeCache = File(pkg, "code_cache")
                    if (codeCache.isDirectory) {
                        internalCodeCache++
                        addSample(codeCache)
                    }
                }
            }
        }

        scanAppRoot(File("/data/user"))
        if (!cancelled.get()) scanAppRoot(File("/data/user_de"))

        val mediaUsers = File("/data/media").listFiles()?.filter { dir ->
            dir.isDirectory && dir.name.isNotEmpty() && dir.name.all(Char::isDigit)
        }.orEmpty()
        for (user in mediaUsers) {
            if (cancelled.get()) break
            val androidData = File(user, "Android/data")
            val packages = androidData.listFiles()?.filter(File::isDirectory).orEmpty()
            for (pkg in packages) {
                if (cancelled.get()) break
                val cache = File(pkg, "cache")
                if (cache.isDirectory) {
                    externalCache++
                    addSample(cache)
                }
            }
        }

        return JSONObject()
            .put("cancelled", cancelled.get())
            .put("elapsedMs", SystemClock.elapsedRealtime() - started)
            .put("usersVisited", usersVisited)
            .put("packagesVisited", packagesVisited)
            .put("internalCache", internalCache)
            .put("internalCodeCache", internalCodeCache)
            .put("externalCache", externalCache)
            .put("totalCandidates", internalCache + internalCodeCache + externalCache)
            .put("samples", samples)
            .toString()
    }
}
