package io.github.xgl34222220.baize.root

import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Transaction coordinator for smart-clean resume support.
 *
 * It never discovers deletion targets. It only backs up the immutable cache/profile snapshots,
 * consumes the result of an already-authorized clean call, removes terminal candidates from the
 * remaining snapshot, and restores a snapshot if the client or Root process dies mid-run.
 */
class CleanPlanResumeRootService : RootService() {
    private val binder = object : ICleanPlanResumeService.Stub() {
        override fun ping(): String {
            pruneTransactions()
            return JSONObject()
                .put("uid", Process.myUid())
                .put("root", Process.myUid() == 0)
                .put("engine", "clean-plan-resume-v1")
                .put("transactions", transactionRoot().listFiles()?.count { it.isDirectory } ?: 0)
                .toString()
        }

        override fun begin(
            planId: String?,
            cacheSnapshotId: String?,
            safeSnapshotId: String?,
            cacheCount: Int,
            safeCount: Int
        ): String = guarded(planId) { id, directory ->
            pruneTransactions()
            val state = readState(directory) ?: newState(
                id = id,
                cacheSnapshotId = cacheSnapshotId.orEmpty(),
                safeSnapshotId = safeSnapshotId.orEmpty(),
                cacheCount = cacheCount,
                safeCount = safeCount
            )
            recoverFiles(state, directory)
            state.put("cacheSnapshotId", cacheSnapshotId.orEmpty())
                .put("safeSnapshotId", safeSnapshotId.orEmpty())
                .put("cacheRemaining", cacheCount.coerceAtLeast(0))
                .put("safeRemaining", safeCount.coerceAtLeast(0))
                .put("cacheComplete", cacheSnapshotId.isNullOrBlank() || cacheCount <= 0)
                .put("safeComplete", safeSnapshotId.isNullOrBlank() || safeCount <= 0)
                .put("runCount", state.optInt("runCount", 0) + 1)
                .put("runStartedAt", System.currentTimeMillis())
                .put("status", "running")
                .put("updatedAt", System.currentTimeMillis())
            backupCurrentSnapshots(state, directory)
            writeState(directory, state)
            response(state)
        }

        override fun checkpointCache(planId: String?, resultJson: String?): String = guarded(planId) { _, directory ->
            val state = readState(directory) ?: return@guarded error("transaction_missing", "清理事务不存在")
            val result = parseJson(resultJson)
            accumulate(state, result)
            restoreCacheIfMissing(state, directory)

            val terminal = cacheTerminalPaths(state)
            val hasFailure = result.has("error") || result.optInt("failures", 0) > 0
            val interrupted = result.optBoolean("cancelled") || result.optBoolean("timedOut")
            val cleanFinished = result.optBoolean("success") && !hasFailure && !interrupted

            val remaining = if (cleanFinished) {
                deleteCacheSnapshot()
                clearCacheBackup(directory)
                0
            } else {
                filterCacheSnapshot(terminal)
            }
            state.put("cacheRemaining", remaining)
                .put("cacheComplete", remaining <= 0)
                .put("cacheLastError", result.optString("message", result.optString("error")))
                .put("status", if (remaining > 0 || state.optInt("safeRemaining", 0) > 0) "partial" else "complete")
                .put("updatedAt", System.currentTimeMillis())
            if (remaining > 0) backupCacheSnapshot(directory) else clearCacheBackup(directory)
            writeState(directory, state)
            response(state)
        }

        override fun checkpointSafe(planId: String?, resultJson: String?): String = guarded(planId) { _, directory ->
            val state = readState(directory) ?: return@guarded error("transaction_missing", "清理事务不存在")
            val result = parseJson(resultJson)
            accumulate(state, result)
            restoreSafeIfMissing(state, directory)

            val remaining = filterSafeSnapshot(state, result, directory)
            state.put("safeRemaining", remaining)
                .put("safeComplete", remaining <= 0)
                .put("safeLastError", result.optString("message", result.optString("error")))
                .put("status", if (remaining > 0 || state.optInt("cacheRemaining", 0) > 0) "partial" else "complete")
                .put("updatedAt", System.currentTimeMillis())
            if (remaining > 0) backupSafeSnapshot(state, directory) else clearSafeBackup(directory)
            writeState(directory, state)
            response(state)
        }

        override fun recover(planId: String?): String = guarded(planId) { _, directory ->
            val state = readState(directory) ?: return@guarded error("transaction_missing", "没有需要恢复的清理事务")
            recoverFiles(state, directory)
            val cacheRemaining = countCacheCandidates().takeIf { !state.optBoolean("cacheComplete") } ?: 0
            val safeRemaining = countSafeCandidates(state.optString("safeSnapshotId"))
                .takeIf { !state.optBoolean("safeComplete") } ?: 0
            state.put("cacheRemaining", cacheRemaining)
                .put("safeRemaining", safeRemaining)
                .put("cacheComplete", cacheRemaining <= 0)
                .put("safeComplete", safeRemaining <= 0)
                .put("status", if (cacheRemaining + safeRemaining > 0) "partial" else "complete")
                .put("recoveredAt", System.currentTimeMillis())
                .put("updatedAt", System.currentTimeMillis())
            writeState(directory, state)
            JSONObject(response(state)).put("recovered", true).toString()
        }

        override fun finish(planId: String?): String = guarded(planId) { _, directory ->
            val removed = deleteTree(directory)
            JSONObject().put("success", removed).put("finished", true).toString()
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private inline fun guarded(
        rawPlanId: String?,
        block: (String, File) -> String
    ): String {
        val id = validPlanId(rawPlanId.orEmpty())
            ?: return error("invalid_plan", "清理计划编号无效")
        return runCatching { block(id, File(transactionRoot(), id).apply { mkdirs() }) }
            .getOrElse { error("resume_transaction_failed", it.message ?: it.javaClass.simpleName) }
    }

    private fun newState(
        id: String,
        cacheSnapshotId: String,
        safeSnapshotId: String,
        cacheCount: Int,
        safeCount: Int
    ): JSONObject = JSONObject()
        .put("version", TRANSACTION_VERSION)
        .put("planId", id)
        .put("createdAt", System.currentTimeMillis())
        .put("updatedAt", System.currentTimeMillis())
        .put("status", "ready")
        .put("runCount", 0)
        .put("cacheSnapshotId", cacheSnapshotId)
        .put("safeSnapshotId", safeSnapshotId)
        .put("cacheOriginal", cacheCount.coerceAtLeast(0))
        .put("safeOriginal", safeCount.coerceAtLeast(0))
        .put("cacheRemaining", cacheCount.coerceAtLeast(0))
        .put("safeRemaining", safeCount.coerceAtLeast(0))
        .put("cacheComplete", cacheSnapshotId.isBlank() || cacheCount <= 0)
        .put("safeComplete", safeSnapshotId.isBlank() || safeCount <= 0)
        .put("deletedBytes", 0L)
        .put("deletedFiles", 0L)
        .put("deletedDirectories", 0L)
        .put("cleanedCandidates", 0)
        .put("failures", 0)

    private fun response(state: JSONObject): String = JSONObject(state.toString())
        .put("success", true)
        .put("remainingCandidates", state.optInt("cacheRemaining") + state.optInt("safeRemaining"))
        .put("resumable", state.optInt("cacheRemaining") + state.optInt("safeRemaining") > 0)
        .toString()

    private fun accumulate(state: JSONObject, result: JSONObject) {
        state.put("deletedBytes", state.optLong("deletedBytes") + result.optLong("deletedBytes"))
            .put("deletedFiles", state.optLong("deletedFiles") + result.optLong("deletedFiles"))
            .put("deletedDirectories", state.optLong("deletedDirectories") + result.optLong("deletedDirectories"))
            .put("cleanedCandidates", state.optInt("cleanedCandidates") + result.optInt("cleanedCandidates"))
            .put("failures", state.optInt("failures") + result.optInt("failures"))
    }

    private fun backupCurrentSnapshots(state: JSONObject, directory: File) {
        if (!state.optBoolean("cacheComplete")) backupCacheSnapshot(directory)
        if (!state.optBoolean("safeComplete")) backupSafeSnapshot(state, directory)
    }

    private fun recoverFiles(state: JSONObject, directory: File) {
        if (!state.optBoolean("cacheComplete")) restoreCacheIfMissing(state, directory)
        if (!state.optBoolean("safeComplete")) restoreSafeIfMissing(state, directory)
    }

    private fun backupCacheSnapshot(directory: File) {
        val backup = File(directory, "cache").apply { mkdirs() }
        CACHE_FILES.forEach { name ->
            val source = File(RootPaths.STATE_DIR, name)
            if (source.isFile) atomicCopy(source, File(backup, name.substringAfterLast('/')))
        }
    }

    private fun restoreCacheIfMissing(state: JSONObject, directory: File) {
        if (state.optString("cacheSnapshotId").isBlank()) return
        val backup = File(directory, "cache")
        CACHE_FILES.forEach { name ->
            val target = File(RootPaths.STATE_DIR, name)
            val source = File(backup, name.substringAfterLast('/'))
            if (!target.isFile && source.isFile) atomicCopy(source, target)
        }
    }

    private fun clearCacheBackup(directory: File) {
        deleteTree(File(directory, "cache"))
    }

    private fun backupSafeSnapshot(state: JSONObject, directory: File) {
        val source = safeSnapshotFile(state.optString("safeSnapshotId")) ?: return
        if (source.isFile) atomicCopy(source, File(directory, "safe.json"))
    }

    private fun restoreSafeIfMissing(state: JSONObject, directory: File) {
        val target = safeSnapshotFile(state.optString("safeSnapshotId")) ?: return
        val backup = File(directory, "safe.json")
        if (!target.isFile && backup.isFile) atomicCopy(backup, target)
    }

    private fun clearSafeBackup(directory: File) {
        File(directory, "safe.json").delete()
    }

    private fun cacheTerminalPaths(state: JSONObject): Set<String> {
        val report = File(RootPaths.STATE_DIR, "reports/latest.tsv")
        val runStarted = state.optLong("runStartedAt", 0L)
        if (!report.isFile || report.lastModified() + REPORT_CLOCK_SLOP_MS < runStarted) return emptySet()
        val terminal = LinkedHashSet<String>()
        val failed = LinkedHashSet<String>()
        report.useLines { lines ->
            lines.drop(1).forEach { line ->
                val fields = line.split('\t')
                if (fields.size < 6) return@forEach
                val action = fields[0].trim().lowercase()
                val path = fields[5].trim()
                if (!path.startsWith("/")) return@forEach
                when (action) {
                    "failed", "partial" -> failed += path
                    "cleaned", "protected", "skipped" -> terminal += path
                }
            }
        }
        terminal.removeAll(failed)
        return terminal
    }

    private fun filterCacheSnapshot(terminal: Set<String>): Int {
        val targetFile = File(RootPaths.STATE_DIR, "cache_scan.targets")
        val itemFile = File(RootPaths.STATE_DIR, "cache_scan.items.tsv")
        val stateFile = File(RootPaths.STATE_DIR, "cache_scan.env")
        if (!targetFile.isFile || !itemFile.isFile || !stateFile.isFile) return 0

        if (terminal.isNotEmpty()) {
            val remainingTargets = targetFile.readLines().filter { it.isNotBlank() && it !in terminal }
            atomicWrite(targetFile, remainingTargets.joinToString("\n", postfix = if (remainingTargets.isEmpty()) "" else "\n"))

            val lines = itemFile.readLines()
            val header = lines.firstOrNull().orEmpty()
            val remainingItems = lines.drop(1).filter { line ->
                val fields = line.split('\t')
                fields.size < 6 || fields[5].trim() !in terminal
            }
            atomicWrite(itemFile, buildString {
                if (header.isNotBlank()) append(header).append('\n')
                remainingItems.forEach { append(it).append('\n') }
            })
        }

        val rows = itemFile.readLines().drop(1).filter { it.isNotBlank() }
        val targets = targetFile.readLines().count { it.isNotBlank() }
        var files = 0L
        var bytes = 0L
        rows.forEach { row ->
            val fields = row.split('\t')
            files += fields.getOrNull(2)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            bytes += fields.getOrNull(3)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        }
        val env = readEnv(stateFile)
        env["targets_sha"] = sha256(targetFile)
        env["items"] = rows.size.toString()
        env["targets"] = targets.toString()
        env["files"] = files.toString()
        env["bytes"] = bytes.toString()
        atomicWrite(stateFile, env.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
        if (rows.isEmpty() || targets <= 0) {
            deleteCacheSnapshot()
            return 0
        }
        return rows.size
    }

    private fun filterSafeSnapshot(state: JSONObject, result: JSONObject, directory: File): Int {
        val snapshotId = state.optString("safeSnapshotId")
        val file = safeSnapshotFile(snapshotId) ?: return 0
        if (!file.isFile) {
            restoreSafeIfMissing(state, directory)
            if (!file.isFile) return 0
        }
        val snapshot = parseJson(file.readText())
        val items = snapshot.optJSONArray("items") ?: JSONArray()
        val details = result.optJSONArray("details") ?: JSONArray()
        val terminalIds = LinkedHashSet<String>()
        val terminalPaths = LinkedHashSet<String>()
        var hasRetryable = result.has("error") || result.optBoolean("cancelled") || result.optBoolean("timedOut")
        for (index in 0 until details.length()) {
            val detail = details.optJSONObject(index) ?: continue
            when (detail.optString("action").lowercase()) {
                "cleaned", "protected", "skipped" -> {
                    detail.optString("id").takeIf { it.isNotBlank() }?.let { terminalIds += it }
                    detail.optString("path").takeIf { it.isNotBlank() }?.let { terminalPaths += it }
                }
                "partial", "failed" -> hasRetryable = true
            }
        }

        val remaining = JSONArray()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            if (item.optString("id") !in terminalIds && item.optString("path") !in terminalPaths) {
                remaining.put(item)
            }
        }

        if (!hasRetryable && result.optBoolean("success") && remaining.length() == 0) {
            file.delete()
            return 0
        }
        if (!hasRetryable && result.optBoolean("success") && details.length() == 0) {
            file.delete()
            return 0
        }
        if (remaining.length() <= 0) {
            file.delete()
            return 0
        }
        snapshot.put("items", remaining)
            .put("resumeUpdatedAt", System.currentTimeMillis())
            .put("remainingCandidates", remaining.length())
        atomicWrite(file, snapshot.toString())
        return remaining.length()
    }

    private fun countCacheCandidates(): Int {
        val file = File(RootPaths.STATE_DIR, "cache_scan.items.tsv")
        if (!file.isFile) return 0
        return file.useLines { lines -> lines.drop(1).count { it.isNotBlank() } }
    }

    private fun countSafeCandidates(snapshotId: String): Int {
        val file = safeSnapshotFile(snapshotId) ?: return 0
        if (!file.isFile) return 0
        return parseJson(file.readText()).optJSONArray("items")?.length() ?: 0
    }

    private fun deleteCacheSnapshot() {
        CACHE_FILES.forEach { File(RootPaths.STATE_DIR, it).delete() }
    }

    private fun safeSnapshotFile(snapshotId: String): File? {
        val id = runCatching { UUID.fromString(snapshotId).toString() }.getOrNull() ?: return null
        return File(RootPaths.STATE_DIR, "profile-snapshots/$id.json")
    }

    private fun transactionRoot(): File = File(RootPaths.STATE_DIR, "clean-plan-transactions").apply { mkdirs() }

    private fun stateFile(directory: File): File = File(directory, "state.json")

    private fun readState(directory: File): JSONObject? {
        val file = stateFile(directory)
        if (!file.isFile) return null
        val state = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        if (state.optInt("version") != TRANSACTION_VERSION) return null
        return state
    }

    private fun writeState(directory: File, state: JSONObject) {
        atomicWrite(stateFile(directory), state.toString())
    }

    private fun readEnv(file: File): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        if (!file.isFile) return result
        file.forEachLine { line ->
            val index = line.indexOf('=')
            if (index > 0) result[line.substring(0, index)] = line.substring(index + 1)
        }
        return result
    }

    private fun atomicCopy(source: File, target: File): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        source.copyTo(temporary, overwrite = true)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        restrict(target)
        true
    }.getOrDefault(false)

    private fun atomicWrite(target: File, content: String): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        restrict(target)
        true
    }.getOrDefault(false)

    private fun restrict(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    private fun deleteTree(file: File): Boolean = runCatching {
        if (!file.exists()) return@runCatching true
        file.walkBottomUp().forEach { it.delete() }
        !file.exists()
    }.getOrDefault(false)

    private fun pruneTransactions() {
        val now = System.currentTimeMillis()
        transactionRoot().listFiles()?.filter { it.isDirectory }?.forEach { directory ->
            val state = readState(directory)
            val updated = state?.optLong("updatedAt", directory.lastModified()) ?: directory.lastModified()
            if (updated <= 0L || now - updated > TRANSACTION_TTL_MS) deleteTree(directory)
        }
    }

    private fun validPlanId(raw: String): String? = runCatching { UUID.fromString(raw).toString() }.getOrNull()

    private fun parseJson(raw: String?): JSONObject = runCatching { JSONObject(raw.orEmpty()) }.getOrDefault(JSONObject())

    private fun sha256(file: File): String {
        if (!file.isFile) return "missing"
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("missing")
    }

    private fun error(code: String, message: String): String = JSONObject()
        .put("error", code)
        .put("message", message)
        .toString()

    companion object {
        private const val TRANSACTION_VERSION = 1
        private const val TRANSACTION_TTL_MS = 2L * 60L * 60_000L
        private const val REPORT_CLOCK_SLOP_MS = 5_000L
        private val CACHE_FILES = listOf(
            "cache_scan.env",
            "cache_scan.targets",
            "cache_scan.items.tsv"
        )
    }
}
