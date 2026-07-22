package io.github.xgl34222220.baize.root

import android.content.Intent
import android.os.IBinder
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
            val native = runCatching {
                JSONObject(
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
            }.getOrNull()

            if (native != null && native.optString("error") != "snapshot_expired") {
                if (!native.has("error") && !native.optBoolean("cancelled") && !native.optBoolean("timedOut")) {
                    deleteSnapshot(id)
                }
                return@runExclusive native.toString()
            }
            cleanPersistedSnapshot(id, selectionJson.orEmpty(), normalizedOptions, started)
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
        val items = JSONArray()
        var offset = 0
        var total = Int.MAX_VALUE
        while (offset < total && items.length() < MAX_CANDIDATES) {
            val page = JSONObject(engine.page(snapshotId, offset, PAGE_SIZE))
            if (page.has("error")) return false
            total = page.optInt("total", 0).coerceAtMost(MAX_CANDIDATES)
            val pageItems = page.optJSONArray("items") ?: JSONArray()
            for (index in 0 until pageItems.length()) {
                items.put(pageItems.getJSONObject(index))
            }
            if (pageItems.length() == 0) break
            offset += pageItems.length()
        }
        if (total > 0 && items.length() == 0) return false

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
        for (index in start until end) page.put(items.getJSONObject(index))
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
            if (stats.bytes > 0L || stats.files > 0L || stats.directories > 0L || stats.complete) cleaned++ else skipped++
            if (details.length() < MAX_DETAILS) {
                details.put(detail(candidate, if (stats.complete) "cleaned" else "partial", "", stats))
            }
        }

        val timedOut = SystemClock.elapsedRealtime() >= deadline
        val wasCancelled = cancelled.get()
        if (!wasCancelled && !timedOut) deleteSnapshot(snapshotId)
        return JSONObject()
            .put("success", true)
            .put("persistedFallback", true)
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
        if (path != rawPath || hardProtected(path) || !mutationRoot(path)) return "路径超出安全边界"
        if (!target.exists()) return "目标已不存在"
        if (isSymlink(target)) return "符号链接受保护"
        if (path in mounts) return "挂载点受保护"
        if (whitelisted(path, candidate.optString("packageName"), options)) return "白名单保护"
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
        if (target.isFile) {
            val size = target.length()
            if (size > maxFileBytes) return DeleteStats(complete = false)
            val deleted = runCatching { target.delete() }.getOrDefault(false)
            return DeleteStats(
                bytes = if (deleted) size else 0L,
                files = if (deleted) 1L else 0L,
                failures = if (deleted) 0 else 1,
                complete = deleted
            )
        }
        if (candidate.optString("category") == "empty_dir") {
            val deleted = runCatching { target.delete() }.getOrDefault(false)
            return DeleteStats(
                directories = if (deleted) 1L else 0L,
                failures = if (deleted) 0 else 1,
                complete = deleted
            )
        }

        val deleteRoot = candidate.optBoolean("deleteRoot", false)
        val stack = ArrayDeque<DeleteNode>()
        stack.add(DeleteNode(target, false))
        var bytes = 0L
        var files = 0L
        var directories = 0L
        var failures = 0
        var complete = true
        while (stack.isNotEmpty()) {
            if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) {
                complete = false
                break
            }
            val node = stack.removeLast()
            val file = node.file
            if (!file.exists() || isSymlink(file)) continue
            val path = canonical(file)
            if (file != target && path in mounts) continue
            if (node.post) {
                if ((file != target || deleteRoot) && runCatching { file.delete() }.getOrDefault(false)) directories++
                continue
            }
            if (file.isFile) {
                val size = file.length()
                if (size > maxFileBytes) continue
                if (runCatching { file.delete() }.getOrDefault(false)) {
                    bytes += size
                    files++
                } else failures++
            } else if (file.isDirectory) {
                stack.add(DeleteNode(file, true))
                val children = file.listFiles()
                if (children == null) failures++ else children.forEach { stack.add(DeleteNode(it, false)) }
            }
        }
        val targetComplete = if (deleteRoot) !target.exists() else target.list()?.isEmpty() == true
        return DeleteStats(bytes, files, directories, failures, complete && targetComplete)
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
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(target)) {
            target.writeText(content)
            temporary.delete()
        }
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
