package io.github.xgl34222220.baize.root

import android.os.Process
import io.github.xgl34222220.baize.DirectoryUsageScanner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** app_process entry point; excluded from release. Only disposable emulator fixtures are accepted. */
object ReleaseAccountingDeviceProbe {
    @JvmStatic fun main(args: Array<String>) {
        try {
            check(Process.myUid() == 0)
            check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
            val selected = File("/data/user/0/test.baize.cache.selected")
            val retained = File("/data/user/0/test.baize.cache.retained")
            val personal = File(selected, "files/keep.txt").readBytes()
            val retainedCache = File(retained, "cache/owned.bin").readBytes()
            check(File(selected, "cache/owned.bin").length() == 16384L)
            val request = JSONObject().put("userId", 0).put("packages", JSONArray().put("test.baize.cache.selected"))
            val systemResult = JSONObject(InstantCacheEngine(AtomicBoolean(false), {}).run(request.toString(), android.os.SystemClock.elapsedRealtime()))
            check(systemResult.optBoolean("success") && systemResult.optInt("succeeded") == 1) { systemResult.toString() }
            check(!File(selected, "cache/owned.bin").exists())
            check(personal.contentEquals(File(selected, "files/keep.txt").readBytes()))
            check(retainedCache.contentEquals(File(retained, "cache/owned.bin").readBytes()))

            val root = File("/data/local/tmp/baize-release-${UUID.randomUUID()}").apply { check(mkdirs()) }
            val state = File(root, "audit")
            val repo = AuditRepository(state)
            repo.recordResult("instant-cache", "device", systemResult.toString())
            val content = File(root, "owned-content").apply { mkdirs() }
            File(content, "unindexed.bin").writeBytes(ByteArray(4096) { 7 })
            val usage = DirectoryUsageScanner.scan(mapOf(content to "/synthetic"))
            check(usage.bytes == 4096L && !usage.limited && usage.inaccessible == 0)
            val tree = FrozenReviewTree.capture(content.toPath(), AtomicBoolean(false), 5000, 100)
            val removed = FrozenReviewTree.delete(tree, false, Long.MAX_VALUE, AtomicBoolean(false), 5000) { _, _ -> true }
            check(removed.complete && removed.bytes == 4096L && removed.files == 1L)
            val child = repo.recordResult("profile-clean", "device", JSONObject().put("success", true)
                .put("deletedBytes", removed.bytes).put("deletedFiles", removed.files).toString())
            val task = JSONObject().put("mode", "workbench-clean").put("success", true).put("bytes", removed.bytes)
                .put("files", removed.files).put("includedAuditEventIds", JSONArray().put(child))
            val saved = HistoryRepository(File(root, "module"), state).recordNativeTaskJson(task.toString())
            check(JSONObject(saved).getBoolean("success"))
            repo.recordNativeTask(task.toString(), saved)
            repo.recordResult("cache-clean", "device", JSONObject().put("success", true).put("deletedBytes", 0)
                .put("deletedFiles", 0).put("protectedCandidates", 1).put("skippedCandidates", 1).toString())
            val held = File(root, "owned-retained.bin").apply { writeBytes(ByteArray(2048)) }
            repo.recordResult("ordinary-trash", "device", JSONObject().put("success", true).put("trashed", true).put("trashedBytes", held.length()).toString())
            check(held.exists() && held.length() == 2048L)
            val summary = JSONObject(repo.timelinePageJson(0, 100))
            check(summary.getLong("releasedBytes") == 4096L) { summary.toString() }
            check(summary.getInt("unmeasuredReleaseCount") == 1)
            check(summary.getInt("zeroReleaseCount") == 1 && summary.getInt("retainedCount") == 1)
            println(JSONObject().put("passed", true).put("uid", Process.myUid()).put("androidApi", android.os.Build.VERSION.SDK_INT)
                .put("actualSystemCacheRequest", true).put("systemRequestAmountUnknown", true)
                .put("measuredDeletedBytes", removed.bytes).put("noDoubleCounting", true).put("zeroSeparateFromUnknown", true)
                .put("retainedContentPreserved", held.exists()).put("applicationDataPreserved", true)
                .put("unselectedCachePreserved", true).put("unindexedDirectoryContentCounted", true)
                .put("evidence", summary).toString())
            System.out.flush()
            kotlin.system.exitProcess(0)
        } catch (error: Throwable) {
            println(JSONObject().put("passed", false).put("error", error.javaClass.name).put("message", error.message).toString())
            error.printStackTrace(System.err); System.out.flush(); kotlin.system.exitProcess(1)
        }
    }
}
