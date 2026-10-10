package io.github.xgl34222220.baize.root

import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Root-side owner for ordinary smart-clean snapshots.
 *
 * NativeProfileEngine remains the source of truth while the daemon is alive. After every safe scan,
 * this bridge serializes the immutable candidate set under BaiZe's root-only state directory. A
 * restarted RootService can therefore validate, page and clean the exact same snapshot without
 * launching discovery again.
 */
class PersistentCleanPlanRootService : RootService() {
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val engine by lazy { NativeProfileEngine(this, cancelled) }

    @Volatile
    private var stateJson: String = idleState()

    private val binder = object : IPersistentCleanPlanService.Stub() {

        override fun exchangeJson(operation: String?, request: ParcelFileDescriptor?): ParcelFileDescriptor =
            JsonFileTransport.serve(File(RootPaths.STATE_DIR, "ipc"), request) { dispatchJson(operation, it) }

        override fun exchangeJsonInto(operation: String?, request: ParcelFileDescriptor?, response: ParcelFileDescriptor?): Int =
            JsonFileTransport.serveInto(request, response) { dispatchJson(operation, it) }

        private fun dispatchJson(operation: String?, arguments: JSONArray): String {
            return when (operation) {
                "scanSafe" -> {
                    require(arguments.length() == 1)
                    scanSafe(arguments.getString(0))
                }
                "getPage" -> {
                    require(arguments.length() == 3)
                    getPage(arguments.getString(0), arguments.getInt(1), arguments.getInt(2))
                }
                "cleanSafe" -> {
                    require(arguments.length() == 3)
                    cleanSafe(arguments.getString(0), arguments.getString(1), arguments.getString(2))
                }
                else -> throw IllegalArgumentException("不支持的服务请求")
            }
        }

        override fun ping(): String = JSONObject()
            .put("uid", Process.myUid())
            .put("root", Process.myUid() == 0)
            .put("engine", "persistent-safe-plan-v1")
            .put("snapshots", snapshotDirectory().listFiles()?.count { it.extension == "json" } ?: 0)
            .toString()

        override fun scanSafe(optionsJson: String?): String = runExclusive(
            operation = "safe-scan",
            initialPhase = "正在扫描空项目、规则垃圾与残留碎片"
        ) { started ->
            val normalizedOptions = normalizeOptions(optionsJson.orEmpty())
            val result = JSONObject(
                engine.scan("safe", normalizedOptions) { progress ->
                    publish(
                        operation = "safe-scan",
                        phase = progress.phase,
                        current = progress.current,
                        total = progress.total,
                        path = progress.path,
                        started = started
                    )
                }
            )
            if (!result.has("error") && !result.optBoolean("cancelled")) {
                val snapshotId = result.optString("snapshotId")
                if (snapshotId.isNotBlank()) {
                    val persisted = persistNativeSnapshot(snapshotId, result, normalizedOptions)
                    result.put("persisted", persisted)
                    if (!persisted) {
                        result.put("error", "snapshot_persist_failed")
                        result.put("message", "扫描完成但清理计划保存失败，请重新扫描")
                    }
                }
            }
            result.toString()
        }

        override fun getPage(snapshotId: String?, offset: Int, limit: Int): String {
            val id = snapshotId.orEmpty()
            val native = runCatching { JSONObject(engine.page(id, offset, limit)) }.getOrNull()
            if (native != null && !native.has("error")) return native.toString()
            return persistedPage(id, offset, limit)
        }

        override fun cleanSafe(
            snapshotId: String?,
            selectionJson: String?,
            optionsJson: String?
        ): String = runExclusive(
            operation = "safe-clean",
            initialPhase = "正在清理已保存的安全项目计划"
        ) { started ->
            val id = snapshotId.orEmpty()
            val normalizedOptions = normalizeOptions(optionsJson.orEmpty())
            PersistentCleanFallback.execute(
                native = {
                    val result = JSONObject(
                        engine.clean(id, selectionJson.orEmpty(), normalizedOptions) { progress ->
                            publish(
                                operation = "safe-clean",
                                phase = progress.phase,
                                current = progress.current,
                                total = progress.total,
                                path = progress.path,
                                started = started,
                                bytes = progress.bytes,
                                files = progress.files,
                                failures = progress.failures
                            )
                        }
                    )
                    if (!result.has("error")) {
                        val remaining = engine.persistentItems(id)
                        if (remaining == null && result.optInt("remainingCandidates", -1) == 0) deleteSnapshot(id)
                        else if (remaining != null && !saveRemaining(id, remaining)) {
                            result.put("persistenceWarning", "剩余清理计划保存失败，重连后需要重新扫描")
                        }
                    }
                    result
                },
                persisted = { cleanPersistedSnapshot(id, selectionJson.orEmpty(), normalizedOptions, started) }
            )
        }

        override fun getTaskState(): String = runCatching {
            JSONObject(stateJson)
                .put("running", running.get())
                .put("cancelRequested", cancelled.get())
                .toString()
        }.getOrDefault(stateJson)

        override fun cancelCurrentTask() {
            cancelled.set(true)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun runExclusive(
        operation: String,
        initialPhase: String,
        block: (Long) -> String
    ): String {
        if (!running.compareAndSet(false, true)) {
            return JSONObject()
                .put("error", "busy")
                .put("message", "当前已有扫描或清理任务正在运行")
                .toString()
        }
        val lease = try {
            RootOperationLease.acquire(this, shared = operation.endsWith("-scan")) ?: return JSONObject()
                .put("success", false).put("error", "busy")
                .put("message", "已有扫描、清理或归类任务正在运行").toString().also { running.set(false) }
        } catch (error: Exception) {
            running.set(false)
            return JSONObject().put("success", false).put("error", "operation_lock_unavailable")
                .put("message", error.message ?: "无法确认清理互斥状态").toString()
        }
        cancelled.set(false)
        val started = SystemClock.elapsedRealtime()
        publish(operation, initialPhase, 0, 0, "", started)
        return try {
            block(started)
        } catch (error: Throwable) {
            JSONObject()
                .put("error", "persistent_plan_failed")
                .put("message", error.message ?: error.javaClass.simpleName)
                .toString()
        } finally {
            runCatching { lease.close() }
            running.set(false)
            stateJson = idleState()
        }
    }

    private fun publish(
        operation: String,
        phase: String,
        current: Int,
        total: Int,
        path: String,
        started: Long,
        bytes: Long = 0L,
        files: Long = 0L,
        failures: Int = 0
    ) {
        stateJson = JSONObject()
            .put("running", true)
            .put("operation", operation)
            .put("phase", phase)
            .put("current", current.coerceAtLeast(0))
            .put("total", total.coerceAtLeast(0))
            .put("currentPath", path.takeLast(512))
            .put("elapsedMs", (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L))
            .put("deletedBytes", bytes.coerceAtLeast(0L))
            .put("deletedFiles", files.coerceAtLeast(0L))
            .put("failures", failures.coerceAtLeast(0))
            .put("cancelRequested", cancelled.get())
            .toString()
    }

    private fun persistNativeSnapshot(
        snapshotId: String,
        scanResult: JSONObject,
        normalizedOptions: String
    ): Boolean {
        val items = engine.persistentItems(snapshotId) ?: return false
        if (items.length() > MAX_CANDIDATES) return false

        val payload = JSONObject()
            .put("version", SNAPSHOT_VERSION)
            .put("snapshotId", snapshotId)
            .put("createdAt", System.currentTimeMillis())
            .put("expiresAt", System.currentTimeMillis() + SNAPSHOT_TTL_MS)
            .put("optionsSha", sha256(normalizedOptions))
            .put("summary", scanResult)
            .put("items", items)
            .toString()
        return atomicWrite(snapshotFile(snapshotId) ?: return false, payload)
    }

    private fun persistedPage(snapshotId: String, offset: Int, limit: Int): String {
        val snapshot = readSnapshot(snapshotId) ?: return JSONObject()
            .put("error", "snapshot_expired")
            .put("message", "清理计划不存在或已过期，不会自动重新扫描")
            .put("items", JSONArray())
            .toString()
        val items = snapshot.optJSONArray("items") ?: JSONArray()
        val start = offset.coerceAtLeast(0).coerceAtMost(items.length())
        val count = limit.coerceIn(1, PAGE_SIZE)
        val end = min(items.length(), start + count)
        val page = JSONArray()
        for (index in start until end) {
            val item = items.getJSONObject(index)
            val visible = JSONObject()
            item.keys().forEach { key -> if (key != "frozenTree" && key != "identity") visible.put(key, item.get(key)) }
            page.put(visible)
        }
        return JSONObject()
            .put("success", true)
            .put("persisted", true)
            .put("snapshotId", snapshotId)
            .put("offset", start)
            .put("limit", count)
            .put("total", items.length())
            .put("items", page)
            .toString()
    }

    private fun cleanPersistedSnapshot(
        snapshotId: String,
        selectionJson: String,
        normalizedOptions: String,
        started: Long
    ): String {
        val snapshot = readSnapshot(snapshotId) ?: return JSONObject()
            .put("error", "snapshot_expired")
            .put("message", "清理计划不存在或已过期，不会自动重新扫描")
            .toString()
        if (snapshot.optString("optionsSha") != sha256(normalizedOptions)) {
            return JSONObject()
                .put("error", "settings_changed")
                .put("message", "白名单或清理设置已变化，请重新扫描")
                .toString()
        }

        val options = parseOptions(normalizedOptions)
        val selection = parseSelection(selectionJson)
        val selectAll = selection.optBoolean("__all_safe__", false)
        val source = snapshot.optJSONArray("items") ?: JSONArray()
        val candidates = ArrayList<JSONObject>()
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            val selected = selection.optBoolean(item.optString("id"), false) ||
                selection.optBoolean(item.optString("path"), false) ||
                (selectAll && item.optString("risk") in SAFE_RISKS)
            if (selected) candidates += item
        }
        if (candidates.isEmpty()) {
            return JSONObject().put("error", "empty_selection").put("message", "没有明确授权任何项目").toString()
        }

        val deadline = SystemClock.elapsedRealtime() + CLEAN_BUDGET_MS
        val mounts = mountPoints()
        val details = JSONArray()
        var deletedBytes = 0L
        var deletedFiles = 0L
        var deletedDirectories = 0L
        var cleaned = 0
        var skipped = 0
        var failures = 0
        val completed = hashSetOf<String>()

        for ((index, candidate) in candidates.withIndex()) {
            if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) break
            val path = candidate.optString("path")
            publish(
                operation = "safe-clean",
                phase = "正在清理${candidate.optString("categoryLabel", "安全项目")}",
                current = index,
                total = candidates.size,
                path = path,
                started = started,
                bytes = deletedBytes,
                files = deletedFiles,
                failures = failures
            )
            val reason = validateCandidate(candidate, options, mounts)
            if (reason != null) {
                skipped += 1
                if (details.length() < MAX_DETAILS) details.put(detail(candidate, "protected", reason, DeleteStats()))
                continue
            }
            val stats = deleteCandidate(candidate, File(path), options.maxFileBytes, mounts, deadline)
            deletedBytes += stats.bytes
            deletedFiles += stats.files
            deletedDirectories += stats.directories
            failures += stats.failures
            if (stats.complete) completed += candidate.optString("id")
            if (stats.bytes > 0L || stats.files > 0L || stats.directories > 0L || stats.complete) cleaned++ else skipped++
            if (details.length() < MAX_DETAILS) {
                details.put(detail(candidate, if (stats.complete) "cleaned" else "partial", "", stats))
            }
        }

        val timedOut = SystemClock.elapsedRealtime() >= deadline
        val wasCancelled = cancelled.get()
        val remaining = JSONArray()
        for (index in 0 until source.length()) {
            val item = source.getJSONObject(index)
            if (item.optString("id") !in completed) remaining.put(item)
        }
        val saved = if (remaining.length() == 0) { deleteSnapshot(snapshotId); true } else saveRemaining(snapshotId, remaining)
        return JSONObject()
            .put("success", failures == 0 && !wasCancelled && !timedOut && saved)
            .put("persistedFallback", true)
            .put("remainingSnapshotId", if (remaining.length() == 0) "" else snapshotId)
            .put("remainingCandidates", remaining.length())
            .apply { if (!saved) put("persistenceWarning", "剩余清理计划保存失败，重连后需要重新扫描") }
            .put("selected", candidates.size)
            .put("cleanedCandidates", cleaned)
            .put("skippedCandidates", skipped)
            .put("failures", failures)
            .put("deletedBytes", deletedBytes)
            .put("deletedFiles", deletedFiles)
            .put("deletedDirectories", deletedDirectories)
            .put("cancelled", wasCancelled)
            .put("timedOut", timedOut)
            .put("elapsedMs", (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L))
            .put("details", details)
            .toString()
    }

    private data class CleanOptions(
        val whitelistPackages: Set<String>,
        val whitelistPaths: Set<String>,
        val maxFileBytes: Long,
        val fragmentDays: Int
    )

    private data class DeleteStats(
        val bytes: Long = 0L,
        val files: Long = 0L,
        val directories: Long = 0L,
        val failures: Int = 0,
        val complete: Boolean = false
    )

    private data class DeleteNode(val file: File, val post: Boolean)

    private fun parseOptions(raw: String): CleanOptions {
        val json = JSONObject(raw)
        return CleanOptions(
            whitelistPackages = jsonStrings(json.optJSONArray("whitelistPackages")),
            whitelistPaths = jsonStrings(json.optJSONArray("whitelistPaths")).filter { it.startsWith("/") }.toSet(),
            maxFileBytes = json.optLong("maxFileBytes", DEFAULT_MAX_FILE_BYTES)
                .coerceIn(0L, 16L * 1024L * 1024L * 1024L),
            fragmentDays = json.optInt("fragmentDays", 7).coerceIn(0, 365)
        )
    }

    private fun normalizeOptions(raw: String): String {
        val options = parseOptions(runCatching { JSONObject(raw).toString() }.getOrDefault("{}"))
        return JSONObject()
            .put("whitelistPackages", JSONArray().apply { options.whitelistPackages.sorted().forEach { put(it) } })
            .put("whitelistPaths", JSONArray().apply { options.whitelistPaths.sorted().forEach { put(it) } })
            .put("maxFileBytes", options.maxFileBytes)
            .put("fragmentDays", options.fragmentDays)
            .put("allowHighRisk", false)
            .toString()
    }

    private fun parseSelection(raw: String): JSONObject = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())

    private fun validateCandidate(candidate: JSONObject, options: CleanOptions, mounts: Set<String>): String? {
        val risk = candidate.optString("risk")
        if (risk !in SAFE_RISKS) return "风险等级不允许自动清理"
        val rawPath = candidate.optString("path").trim()
        if (!rawPath.startsWith("/") || rawPath.length > 4096 || rawPath.contains('\u0000')) return "路径格式无效"
        val target = File(rawPath)
        val path = canonical(target)
        // 聊天收到的安装包由 Root 在 /data/media 下发现；只为这一类放开该根，且路径必须仍在聊天“接收文件”目录。
        val chatApk = candidate.optString("profile") == "apk" && ChatAppPaths.isReceivedApk(path)
        if (path != rawPath || hardProtected(path) || !(mutationRoot(path) || chatApk)) return "路径超出安全边界"
        if (!target.exists()) return "目标已不存在"
        if (isSymlink(target)) return "符号链接受保护"
        if (path in mounts) return "挂载点受保护"
        if (whitelisted(path, candidate.optString("packageName"), options)) return "白名单保护"
        val frozen = FrozenReviewTree.fromJson(candidate.optJSONObject("frozenTree"))
        if (frozen == null || frozen.root != path) return "旧计划没有完整文件身份，请重新扫描；未执行删除"
        return when (candidate.optString("profile")) {
            "empty" -> when (candidate.optString("category")) {
                "empty_file" -> if (target.isFile && target.length() == 0L && !placeholder(target.name)) null else "目标不再是空文件"
                "empty_dir" -> if (target.isDirectory && target.list()?.isEmpty() == true) null else "目录已发生变化"
                else -> "空项目类型无效"
            }
            "fragments" -> if (target.isFile && fragmentMatches(target.name) &&
                target.lastModified() <= System.currentTimeMillis() - options.fragmentDays * DAY_MS
            ) null else "目标不再符合碎片规则"
            "rules" -> null
            // 聊天软件“接收文件”目录里的安装包（含 .apk.1），删除前再次确认路径与文件名。
            "apk" -> if (target.isFile && ChatAppPaths.isReceivedApk(path)) null else "目标不再是聊天软件收到的安装包"
            else -> "计划类型不允许清理"
        }
    }

    private fun deleteCandidate(
        candidate: JSONObject,
        target: File,
        maxFileBytes: Long,
        mounts: Set<String>,
        deadline: Long
    ): DeleteStats {
        val frozen = FrozenReviewTree.fromJson(candidate.optJSONObject("frozenTree"))
        val result = FrozenReviewTree.delete(frozen, candidate.optBoolean("deleteRoot", false), maxFileBytes, cancelled,
            (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)) { path, directory ->
                val file = File(path)
                !placeholder(file.name) && (path == target.path || path !in mounts) &&
                    (directory || ReviewRuleCatalog.oldEnough(file, candidate.optInt("retentionDays", 0)))
            }
        val indexed = result.deletedPaths.filter { it.startsWith("/storage/") || it.startsWith("/data/media/") || it.startsWith("/mnt/media_rw/") }
        if (indexed.isNotEmpty()) RootMediaScanQueue.enqueueAsync(this, indexed)
        return DeleteStats(result.bytes, result.files, result.directories, result.failures, result.complete)
    }

    private fun saveRemaining(id: String, items: JSONArray): Boolean {
        val before = readSnapshot(id) ?: return false
        before.put("items", items)
        return atomicWrite(snapshotFile(id) ?: return false, before.toString())
    }

    private fun detail(candidate: JSONObject, action: String, reason: String, stats: DeleteStats): JSONObject = JSONObject()
        .put("id", candidate.optString("id"))
        .put("action", action)
        .put("reason", reason)
        .put("profile", candidate.optString("profile"))
        .put("category", candidate.optString("categoryLabel"))
        .put("risk", candidate.optString("risk"))
        .put("path", candidate.optString("path"))
        .put("bytes", stats.bytes)
        .put("files", stats.files)
        .put("directories", stats.directories)

    private fun readSnapshot(snapshotId: String): JSONObject? {
        val file = snapshotFile(snapshotId) ?: return null
        if (!file.isFile) return null
        if (file.length() > 64L * 1024L * 1024L) return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: run {
            file.delete()
            return null
        }
        val createdAt = json.optLong("createdAt", 0L)
        if (json.optInt("version", 0) != SNAPSHOT_VERSION ||
            json.optString("snapshotId") != snapshotId ||
            createdAt <= 0L || System.currentTimeMillis() - createdAt !in 0..SNAPSHOT_TTL_MS
        ) {
            file.delete()
            return null
        }
        return json
    }

    private fun snapshotDirectory(): File = File(RootPaths.STATE_DIR, "profile-snapshots").apply { mkdirs() }

    private fun snapshotFile(snapshotId: String): File? {
        val normalized = runCatching { UUID.fromString(snapshotId).toString() }.getOrNull() ?: return null
        return File(snapshotDirectory(), "$normalized.json")
    }

    private fun deleteSnapshot(snapshotId: String) {
        runCatching { snapshotFile(snapshotId)?.delete() }
    }

    private fun atomicWrite(target: File, content: String): Boolean = runCatching {
        target.parentFile?.mkdirs()
        require(content.toByteArray(Charsets.UTF_8).size <= 64 * 1024 * 1024)
        val temporary = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
        temporary.setReadable(false, false); temporary.setWritable(false, false)
        temporary.setReadable(true, true); temporary.setWritable(true, true)
        try {
            java.io.FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8)); output.fd.sync()
            }
            check(temporary.renameTo(target)) { "无法原子保存清理计划" }
        } finally { temporary.delete() }
        target.setReadable(false, false)
        target.setWritable(false, false)
        target.setExecutable(false, false)
        target.setReadable(true, true)
        target.setWritable(true, true)
        true
    }.getOrDefault(false)

    private fun jsonStrings(array: JSONArray?): Set<String> {
        val result = LinkedHashSet<String>()
        if (array == null) return result
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotBlank()) result += value
        }
        return result
    }

    private fun whitelisted(path: String, packageName: String, options: CleanOptions): Boolean {
        if (packageName.isNotBlank() && packageName in options.whitelistPackages) return true
        val normalized = path.trimEnd('/')
        return options.whitelistPaths.any { raw ->
            val protected = raw.trimEnd('/')
            normalized == protected || normalized.startsWith("$protected/") || protected.startsWith("$normalized/")
        }
    }

    private fun hardProtected(path: String): Boolean {
        val normalized = path.trimEnd('/').ifBlank { "/" }
        return normalized in HARD_EXACT || READ_ONLY.any { normalized == it || normalized.startsWith("$it/") } ||
            normalized == "/data/adb" || normalized.startsWith("/data/adb/") ||
            normalized.contains("/.ssh/") || normalized.contains("/.gnupg/")
    }

    private fun mutationRoot(path: String): Boolean = path.startsWith("/data/user/") ||
        path.startsWith("/data/data/") || path.startsWith("/data/anr/") ||
        path.startsWith("/data/tombstones/") || path.startsWith("/data/system/dropbox/") ||
        path.startsWith("/data/system/heapdump/") || path.startsWith("/data/misc/logd/") ||
        path.startsWith("/data/vendor/log/") || path.startsWith("/data/log/") ||
        path.startsWith("/storage/emulated/") || path.startsWith("/sdcard/")

    private fun fragmentMatches(name: String): Boolean {
        val value = name.lowercase()
        return value.endsWith(".tmp") || value.endsWith(".temp") || value.endsWith(".part") ||
            value.endsWith(".partial") || value.endsWith(".download") || value.endsWith(".crdownload") ||
            Regex(""".*\.log\.[0-9]+$""").matches(value) || value.endsWith(".old") || value.endsWith(".bak~") ||
            value.contains("tombstone") || value.contains("minidump") || value.contains("heapdump") ||
            value.contains("crash") || value.contains("trace") || value.contains("dump")
    }

    private fun placeholder(name: String): Boolean {
        val value = name.lowercase()
        return value == ".nomedia" || value == ".keep" || value == ".gitkeep" ||
            value == ".placeholder" || value.endsWith(".lock")
    }

    private fun mountPoints(): Set<String> = runCatching {
        File("/proc/self/mountinfo").useLines { lines ->
            lines.mapNotNull { it.substringBefore(" - ").split(' ').getOrNull(4) }
                .map { it.replace("\\040", " ") }
                .toSet()
        }
    }.getOrDefault(emptySet())

    private fun isSymlink(file: File): Boolean = runCatching {
        java.nio.file.Files.isSymbolicLink(file.toPath())
    }.getOrDefault(false)

    private fun canonical(file: File): String = runCatching {
        file.canonicalFile.path
    }.getOrDefault(file.absoluteFile.normalize().path)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun idleState(): String = JSONObject()
        .put("running", false)
        .put("operation", "idle")
        .put("phase", "等待任务")
        .put("current", 0)
        .put("total", 0)
        .toString()

    companion object {
        private const val SNAPSHOT_VERSION = 1
        private const val SNAPSHOT_TTL_MS = 30L * 60_000L
        private const val CLEAN_BUDGET_MS = 5L * 60_000L
        private const val DAY_MS = 86_400_000L
        private const val PAGE_SIZE = 60
        private const val MAX_CANDIDATES = 20_000
        private const val MAX_DETAILS = 200
        private const val DEFAULT_MAX_FILE_BYTES = 512L * 1024L * 1024L
        private val SAFE_RISKS = setOf("low", "medium")
        private val HARD_EXACT = setOf(
            "/", "/data", "/data/adb", "/data/system", "/data/misc", "/storage", "/storage/emulated", "/sdcard"
        )
        private val READ_ONLY = setOf(
            "/system", "/vendor", "/product", "/odm", "/apex", "/proc", "/sys", "/dev", "/metadata"
        )
    }
}
