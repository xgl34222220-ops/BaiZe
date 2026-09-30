package io.github.xgl34222220.baize.root

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import android.os.Process
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Debug-only entry point for a disposable adb-root emulator; never packaged in a release APK. */
object CacheRootDeviceProbe {
    @JvmStatic fun main(args: Array<String>) {
        check(Process.myUid() == 0) { "This test requires the disposable emulator's adb root" }
        check(File("/system/bin/getprop").isFile)
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) {
            "Synthetic root probe is restricted to the CI emulator"
        }
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getDeclaredMethod("systemMain").invoke(null)
        val system = threadClass.getDeclaredMethod("getSystemContext").invoke(thread) as Context
        val context = system.createPackageContext("io.github.xgl34222220.baize", Context.CONTEXT_IGNORE_SECURITY)
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val first = File("/data/user/0/io.github.baize.synthetic.p$suffix.first")
        val second = File("/data/user/0/io.github.baize.synthetic.p$suffix.second")
        check(!first.exists() && !second.exists())
        val firstCache = File(first, "cache").apply { check(mkdirs()) }
        val secondCache = File(second, "cache").apply { check(mkdirs()) }
        val selectedFile = File(firstCache, "selected.tmp").apply { writeBytes(ByteArray(4096) { 7 }) }
        val unselectedFile = File(secondCache, "unselected.tmp").apply { writeBytes(ByteArray(8192) { 9 }) }
        val personalFile = File(first, "files/personal.txt").apply { parentFile!!.mkdirs(); writeText("preserve-personal") }
        val transport = File("/data/local/tmp/baize-probe-$suffix").apply { mkdirs() }
        fun service(): IBaiZeRootService {
            val root = BaiZeRootService()
            // Attach only the Context surface; the production service and FD request dispatcher
            // run unchanged, while the test owns launching app_process as uid 0.
            ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }.set(root, context)
            return RootServiceClients.cache(root.onBind(Intent()), transport)
        }
        try {
            var root = service()
            val scan = JSONObject(root.scanCandidates("[]"))
            check(!scan.has("error") && scan.optString("snapshotId").isNotBlank()) { scan.toString() }
            val token = scan.getString("snapshotId")
            val firstPath = firstCache.canonicalPath
            val secondPath = secondCache.canonicalPath
            val paths = linkedSetOf<String>()
            var offset = 0
            do {
                val page = JSONObject(root.getResultPage(token, offset, 100))
                val items = page.getJSONArray("items")
                for (index in 0 until items.length()) paths += items.getJSONObject(index).getString("path")
                offset += items.length()
            } while (items.length() > 0 && offset < page.getInt("total"))
            check(firstPath in paths && secondPath in paths) { "Synthetic candidates missing: $paths" }
            // The former Workbench route reads the module namespace and deterministically fails.
            val former = JSONObject(CacheSelectionRepository().prepare(token, JSONObject().put(firstPath, true).toString()))
            check(!former.optBoolean("success")) { "Fixture unexpectedly has a matching legacy module snapshot" }
            check(former.optString("error") in setOf("snapshot_missing", "snapshot_changed")) { former.toString() }
            val unauthorized = JSONObject(root.cleanSelected(token, JSONObject().put(personalFile.canonicalPath, true).toString(), "[]"))
            check(!unauthorized.optBoolean("success") && personalFile.readText() == "preserve-personal")
            val result = JSONObject(root.cleanSelected(token, JSONObject().put(firstPath, true).toString(), "[]"))
            check(result.optBoolean("success") && result.optLong("deletedBytes") == 4096L && result.optLong("deletedFiles") == 1L) { result.toString() }
            check(!selectedFile.exists() && unselectedFile.length() == 8192L && personalFile.readText() == "preserve-personal")
            check(firstCache.isDirectory) { "Cache root must survive contents cleaning" }
            root = service()
            val restored = JSONObject(root.getResultPage(token, 0, 100)).getJSONArray("items")
            check((0 until restored.length()).any { restored.getJSONObject(it).optString("path") == secondPath })
            val afterRestart = JSONObject(root.cleanSelected(token, JSONObject().put(secondPath, true).toString(), "[]"))
            check(afterRestart.optBoolean("success") && afterRestart.optLong("deletedBytes") == 8192L && !unselectedFile.exists()) { afterRestart.toString() }
            check(personalFile.readText() == "preserve-personal")
            val rules = File(first, "synthetic-rules").apply { mkdir() }
            val low = File(first, "ordinary").apply { mkdir(); resolve("small.bin").writeBytes(ByteArray(128)) }
            val high = File(first, "user_data").apply { mkdir(); resolve("review.bin").writeBytes(ByteArray(256)) }
            File(rules, "deep.rules").writeText("${low.canonicalPath}\n${high.canonicalPath}\n")
            val engine = NativeProfileEngine(context, java.util.concurrent.atomic.AtomicBoolean(), ruleDirectory = rules)
            val profileScan = JSONObject(engine.scan("deep", "{}") {})
            val profileToken = profileScan.getString("snapshotId")
            val profileItems = JSONObject(engine.page(profileToken, 0, 20)).getJSONArray("items")
            val lowItem = (0 until profileItems.length()).map { profileItems.getJSONObject(it) }.single { it.getString("path") == low.canonicalPath }
            val highItem = (0 until profileItems.length()).map { profileItems.getJSONObject(it) }.single { it.getString("path") == high.canonicalPath }
            check(lowItem.getString("risk") == "low" && highItem.getString("risk") == "high")
            val lowResult = JSONObject(engine.clean(profileToken, JSONObject().put(lowItem.getString("id"), true).toString(), "{}") {})
            check(lowResult.getLong("deletedBytes") == 128L && lowResult.getString("remainingSnapshotId") == profileToken) { lowResult.toString() }
            check(high.resolve("review.bin").length() == 256L)
            val highResult = JSONObject(engine.clean(profileToken, JSONObject().put(highItem.getString("id"), true).toString(), "{\"allowHighRisk\":true}") {})
            check(highResult.getLong("deletedBytes") == 256L && !high.resolve("review.bin").exists()) { highResult.toString() }
            println(JSONObject().put("passed", true).put("uid", Process.myUid()).put("api", android.os.Build.VERSION.SDK_INT)
                .put("formerWorkbenchError", former.optString("error")).put("scanThenSelectedClean", true)
                .put("fdTransport", true).put("unselectedPreserved", true).put("unknownPathRejected", true)
                .put("serviceRecreationSelectedClean", true).put("deletedBytes", 12288)
                .put("lowThenHighSameSnapshot", true).put("profileDeletedBytes", 384).toString())
        } finally {
            // These three random, newly-created namespaces contain only this probe's fixtures.
            first.deleteRecursively(); second.deleteRecursively(); transport.deleteRecursively()
        }
        kotlin.system.exitProcess(0)
    }
}
