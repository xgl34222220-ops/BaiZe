package io.github.xgl34222220.baize.root

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

/**
 * Native profile engine for the cleaning surfaces that were missing from Alpha 4.
 *
 * The engine never accepts an arbitrary deletion path from the client. A clean request can only
 * select candidates from an unexpired server-side snapshot, and every candidate is validated again
 * immediately before mutation.
 */
internal class NativeProfileEngine(
    private val context: Context,
    private val cancelled: AtomicBoolean
) {
    data class Progress(
        val phase: String,
        val current: Int,
        val total: Int,
        val path: String = "",
        val bytes: Long = 0L,
        val files: Long = 0L,
        val failures: Int = 0
    )

    private data class Options(
        val whitelistPackages: Set<String>,
        val whitelistPaths: Set<String>,
        val maxFileBytes: Long,
        val fragmentDays: Int,
        val allowHighRisk: Boolean
    )

    private data class Candidate(
        val id: String,
        val profile: String,
        val category: String,
        val categoryLabel: String,
        val risk: String,
        val path: String,
        val packageName: String = "",
        val appName: String = "",
        val userId: Int = 0,
        val deleteRoot: Boolean = false,
        val createdAt: Long = System.currentTimeMillis(),
        val bytes: Long = -1L,
        val files: Long = -1L,
        val directories: Long = -1L,
        val measured: Boolean = false,
        val complete: Boolean = false,
        val note: String = ""
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("profile", profile)
            .put("category", category)
            .put("categoryLabel", categoryLabel)
            .put("risk", risk)
            .put("path", path)
            .put("packageName", packageName)
            .put("appName", appName.ifBlank { packageName.ifBlank { categoryLabel } })
            .put("userId", userId)
            .put("deleteRoot", deleteRoot)
            .put("bytes", bytes)
            .put("files", files)
            .put("directories", directories)
            .put("measured", measured)
            .put("complete", complete)
            .put("note", note)
    }

    private data class Snapshot(
        val id: String,
        val profile: String,
        val createdAt: Long,
        val ruleSha: String,
        val options: Options,
        val candidates: MutableList<Candidate>
    )

    private data class Stats(
        val bytes: Long,
        val files: Long,
        val directories: Long,
        val complete: Boolean,
        val failures: Int = 0
    )

    private val snapshots = ConcurrentHashMap<String, Snapshot>()
    private val appNames by lazy { loadAppNames() }
    private val installedPackages by lazy { appNames.keys }

    fun catalog(): String = JSONObject()
        .put("profiles", JSONArray().apply {
            put(profileJson("empty", "空项目", "空文件与空目录", "low"))
            put(profileJson("rules", "规则垃圾", "隐藏垃圾、系统/OEM 日志与规则路径", "medium"))
            put(profileJson("fragments", "残留碎片", "过期临时文件、旋转日志、崩溃转储与中断下载", "medium"))
            put(profileJson("deep", "深度规则", "完整规则库分级扫描", "high"))
            put(profileJson("corpses", "卸载残留", "Android/data 与 Android/obb 的卸载应用残留", "high"))
        })
        .toString()

    private fun profileJson(id: String, title: String, subtitle: String, risk: String) = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("subtitle", subtitle)
        .put("risk", risk)

    fun scan(
        profile: String,
        optionsJson: String,
        progress: (Progress) -> Unit
    ): String {
        pruneSnapshots()
        val normalizedProfile = profile.trim().lowercase()
        val options = parseOptions(optionsJson)
        val started = SystemClock.elapsedRealtime()
        val candidates = linkedMapOf<String, Candidate>()
        val ruleSha = if (normalizedProfile == "deep") sha256Of(deepRulesFile()) else ""

        progress(Progress("准备${profileLabel(normalizedProfile)}扫描", 0, 0))
        when (normalizedProfile) {
            "empty" -> scanEmpty(options, candidates, progress, started)
            "rules" -> scanRuleTrash(options, candidates, progress, started)
            "fragments" -> scanFragments(options, candidates, progress, started)
            "deep" -> scanDeep(options, candidates, progress, started)
            "corpses" -> scanCorpses(options, candidates, progress, started)
            else -> return JSONObject().put("error", "unsupported_profile").put("profile", normalizedProfile).toString()
        }

        if (cancelled.get()) {
            return JSONObject()
                .put("cancelled", true)
                .put("profile", normalizedProfile)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("totalCandidates", candidates.size)
                .toString()
        }

        val snapshotId = UUID.randomUUID().toString()
        val snapshot = Snapshot(
            id = snapshotId,
            profile = normalizedProfile,
            createdAt = System.currentTimeMillis(),
            ruleSha = ruleSha,
            options = options,
            candidates = candidates.values.toMutableList()
        )
        snapshots[snapshotId] = snapshot
        val risks = riskSummary(snapshot.candidates)
        progress(Progress("${profileLabel(normalizedProfile)}扫描完成", candidates.size, candidates.size))
        return JSONObject()
            .put("success", true)
            .put("profile", normalizedProfile)
            .put("profileLabel", profileLabel(normalizedProfile))
            .put("snapshotId", snapshotId)
            .put("snapshotExpiresInMs", SNAPSHOT_TTL_MS)
            .put("ruleSha", ruleSha)
            .put("totalCandidates", candidates.size)
            .put("low", risks.optInt("low"))
            .put("medium", risks.optInt("medium"))
            .put("high", risks.optInt("high"))
            .put("critical", risks.optInt("critical"))
            .put("elapsedMs", SystemClock.elapsedRealtime() - started)
            .toString()
    }

    fun page(snapshotId: String, offset: Int, limit: Int): String {
        val snapshot = validSnapshot(snapshotId)
            ?: return JSONObject().put("error", "snapshot_expired").put("message", "扫描快照不存在或已过期").toString()
        val safeOffset = max(0, offset)
        val safeLimit = min(MAX_PAGE_SIZE, max(1, limit))
        val end = min(snapshot.candidates.size, safeOffset + safeLimit)
        if (safeOffset >= snapshot.candidates.size) {
            return JSONObject().put("snapshotId", snapshot.id).put("total", snapshot.candidates.size).put("items", JSONArray()).toString()
        }

        val pageDeadline = SystemClock.elapsedRealtime() + PAGE_MEASURE_BUDGET_MS
        val items = JSONArray()
        for (index in safeOffset until end) {
            if (cancelled.get()) break
            val original = snapshot.candidates[index]
            val measured = if (!original.measured && SystemClock.elapsedRealtime() < pageDeadline) {
                val stats = measure(Paths.get(original.path), min(pageDeadline, SystemClock.elapsedRealtime() + ITEM_MEASURE_BUDGET_MS))
                original.copy(
                    bytes = stats.bytes,
                    files = stats.files,
                    directories = stats.directories,
                    measured = true,
                    complete = stats.complete
                ).also { snapshot.candidates[index] = it }
            } else original
            items.put(measured.toJson())
        }
        return JSONObject()
            .put("success", true)
            .put("snapshotId", snapshot.id)
            .put("profile", snapshot.profile)
            .put("offset", safeOffset)
            .put("limit", safeLimit)
            .put("total", snapshot.candidates.size)
            .put("items", items)
            .toString()
    }

    fun clean(
        snapshotId: String,
        selectionJson: String,
        optionsJson: String,
        progress: (Progress) -> Unit
    ): String {
        val started = SystemClock.elapsedRealtime()
        val snapshot = validSnapshot(snapshotId)
            ?: return JSONObject().put("error", "snapshot_expired").put("message", "扫描快照不存在或已过期").toString()
        if (snapshot.profile == "deep") {
            val currentSha = sha256Of(deepRulesFile())
            if (snapshot.ruleSha.isBlank() || snapshot.ruleSha != currentSha) {
                snapshots.remove(snapshotId)
                return JSONObject().put("error", "rules_changed").put("message", "深度规则已变化，请重新扫描").toString()
            }
        }

        val requestOptions = parseOptions(optionsJson)
        val selection = parseSelection(selectionJson)
        val selected = snapshot.candidates.filter { selection[it.id] == true || selection[it.path] == true }
        if (selected.isEmpty()) return JSONObject().put("error", "empty_selection").put("message", "没有明确勾选任何项目").toString()

        val mountPoints = loadMountPoints()
        val details = JSONArray()
        var deletedBytes = 0L
        var deletedFiles = 0L
        var deletedDirectories = 0L
        var failures = 0
        var skipped = 0
        var cleaned = 0
        val totalDeadline = started + CLEAN_TOTAL_BUDGET_MS

        selected.forEachIndexed { index, candidate ->
            if (cancelled.get() || SystemClock.elapsedRealtime() >= totalDeadline) return@forEachIndexed
            progress(Progress("正在清理${candidate.categoryLabel}", index, selected.size, candidate.path, deletedBytes, deletedFiles, failures))
            val validation = validateCandidate(candidate, requestOptions, mountPoints)
            if (validation != null) {
                skipped += 1
                details.put(resultDetail(candidate, "protected", validation, 0L, 0L, 0L))
                return@forEachIndexed
            }

            val path = Paths.get(candidate.path)
            val before = measure(path, min(totalDeadline, SystemClock.elapsedRealtime() + ITEM_CLEAN_BUDGET_MS))
            val deleted = deleteCandidate(candidate, path, min(totalDeadline, SystemClock.elapsedRealtime() + ITEM_CLEAN_BUDGET_MS), mountPoints)
            val after = if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                measure(path, min(totalDeadline, SystemClock.elapsedRealtime() + 1_000L))
            } else Stats(0L, 0L, 0L, true)
            val actualBytes = max(0L, before.bytes - after.bytes)
            val actualFiles = max(0L, before.files - after.files)
            val actualDirectories = max(0L, before.directories - after.directories)
            deletedBytes += actualBytes
            deletedFiles += actualFiles
            deletedDirectories += actualDirectories
            failures += deleted.failures

            val completed = deleted.failures == 0 && when {
                candidate.deleteRoot -> !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                else -> after.files == 0L && after.directories == 0L
            }
            if (completed || actualBytes > 0 || actualFiles > 0 || actualDirectories > 0) cleaned += 1 else skipped += 1
            details.put(resultDetail(candidate, if (completed) "cleaned" else "partial", if (completed) "" else "仍有项目未删除", actualBytes, actualFiles, actualDirectories))
        }

        val cancelledNow = cancelled.get()
        val timedOut = SystemClock.elapsedRealtime() >= totalDeadline
        snapshots.remove(snapshotId)
        progress(Progress(if (cancelledNow) "清理已停止" else "清理完成", selected.size, selected.size, bytes = deletedBytes, files = deletedFiles, failures = failures))
        return JSONObject()
            .put("success", true)
            .put("profile", snapshot.profile)
            .put("selected", selected.size)
            .put("cleanedCandidates", cleaned)
            .put("skippedCandidates", skipped)
            .put("failures", failures)
            .put("deletedBytes", deletedBytes)
            .put("deletedFiles", deletedFiles)
            .put("deletedDirectories", deletedDirectories)
            .put("cancelled", cancelledNow)
            .put("timedOut", timedOut)
            .put("elapsedMs", SystemClock.elapsedRealtime() - started)
            .put("details", details)
            .toString()
    }

    private fun scanEmpty(
        options: Options,
        out: MutableMap<String, Candidate>,
        progress: (Progress) -> Unit,
        started: Long
    ) {
        val roots = storageRoots()
        roots.forEachIndexed { rootIndex, root ->
            if (shouldStop(started, SCAN_TOTAL_BUDGET_MS)) return
            progress(Progress("扫描空文件与空目录", rootIndex, roots.size, root.toString()))
            walk(root, 8, started + SCAN_TOTAL_BUDGET_MS, shouldPruneShared = true) { path, attrs, postDirectory ->
                if (postDirectory) {
                    if (path != root && isDirectoryEmpty(path) && !isProtectedPlaceholderDirectory(path)) {
                        addCandidate(out, Candidate(
                            id = candidateId("empty", path), profile = "empty", category = "empty_dir",
                            categoryLabel = "空目录", risk = "low", path = canonical(path), deleteRoot = true
                        ), options)
                    }
                } else if (attrs != null && attrs.isRegularFile && attrs.size() == 0L && !isPlaceholderFile(path)) {
                    addCandidate(out, Candidate(
                        id = candidateId("empty", path), profile = "empty", category = "empty_file",
                        categoryLabel = "空文件", risk = "low", path = canonical(path), deleteRoot = true,
                        bytes = 0L, files = 1L, directories = 0L, measured = true, complete = true
                    ), options)
                }
            }
        }
    }

    private fun scanRuleTrash(
        options: Options,
        out: MutableMap<String, Candidate>,
        progress: (Progress) -> Unit,
        started: Long
    ) {
        val fixed = mutableListOf<Pair<String, String>>()
        fixed += listOf(
            "/data/anr" to "系统 ANR",
            "/data/tombstones" to "Tombstone",
            "/data/system/dropbox" to "系统 Dropbox",
            "/data/system/heapdump" to "系统 Heapdump",
            "/data/misc/logd" to "系统日志",
            "/data/vendor/log" to "厂商日志",
            "/data/log" to "系统日志"
        )
        val ruleFiles = listOf("app.rules", "external.rules", "hidden.rules", "custom.rules")
        val configDir = rulesDirectory()
        ruleFiles.forEach { name ->
            val file = configDir?.resolve(name)?.toFile()
            if (file?.isFile == true) {
                file.useLines { lines ->
                    lines.map(String::trim)
                        .filter { it.startsWith("/") && !it.startsWith("//") && !it.startsWith("#") }
                        .take(MAX_RULES_PER_FILE)
                        .forEach { fixed += it to "扩展规则" }
                }
            }
        }

        fixed.forEachIndexed { index, (raw, label) ->
            if (shouldStop(started, SCAN_TOTAL_BUDGET_MS)) return
            progress(Progress("解析规则垃圾", index, fixed.size, raw))
            expandRule(raw).forEach { path ->
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                    val risk = classifyRisk(path.toString())
                    addCandidate(out, Candidate(
                        id = candidateId("rules", path), profile = "rules", category = "rule_trash",
                        categoryLabel = label, risk = risk, path = canonical(path), deleteRoot = false
                    ), options)
                }
            }
        }

        val hiddenNames = setOf(".cache", ".thumbnails", ".tmp", ".temp", ".logs", ".debug")
        storageRoots().forEach { root ->
            walk(root, 6, started + SCAN_TOTAL_BUDGET_MS, shouldPruneShared = true) { path, attrs, postDirectory ->
                if (!postDirectory && attrs?.isDirectory == true && path.fileName?.toString() in hiddenNames) {
                    addCandidate(out, Candidate(
                        id = candidateId("rules", path), profile = "rules", category = "hidden_trash",
                        categoryLabel = "隐藏垃圾", risk = "low", path = canonical(path), deleteRoot = false
                    ), options)
                }
            }
        }
    }

    private fun scanFragments(
        options: Options,
        out: MutableMap<String, Candidate>,
        progress: (Progress) -> Unit,
        started: Long
    ) {
        val cutoff = System.currentTimeMillis() - options.fragmentDays * 86_400_000L
        val roots = storageRoots() + logRoots()
        val patterns = listOf(
            Pattern.compile(".*\\.(tmp|temp|part|partial|download|crdownload)$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*\\.(log\\.[0-9]+|old|bak~)$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*(tombstone|minidump|heapdump|crash|trace|dump).*", Pattern.CASE_INSENSITIVE)
        )
        roots.distinct().forEachIndexed { rootIndex, root ->
            if (shouldStop(started, SCAN_TOTAL_BUDGET_MS)) return
            progress(Progress("扫描残留碎片", rootIndex, roots.size, root.toString()))
            walk(root, 9, started + SCAN_TOTAL_BUDGET_MS, shouldPruneShared = root.startsWith(Paths.get("/storage"))) { path, attrs, postDirectory ->
                if (postDirectory || attrs == null || !attrs.isRegularFile || attrs.lastModifiedTime().toMillis() > cutoff) return@walk
                val name = path.fileName?.toString().orEmpty()
                if (patterns.any { it.matcher(name).matches() }) {
                    addCandidate(out, Candidate(
                        id = candidateId("fragments", path), profile = "fragments", category = "fragment",
                        categoryLabel = "残留碎片", risk = classifyRisk(path.toString()), path = canonical(path),
                        deleteRoot = true, bytes = attrs.size(), files = 1L, directories = 0L,
                        measured = true, complete = true, note = "保留 ${options.fragmentDays} 天"
                    ), options)
                }
            }
        }
    }

    private fun scanDeep(
        options: Options,
        out: MutableMap<String, Candidate>,
        progress: (Progress) -> Unit,
        started: Long
    ) {
        val rulesFile = deepRulesFile() ?: return
        val rules = rulesFile.useLines { lines ->
            lines.map(String::trim)
                .filter { it.startsWith("/") && !it.startsWith("//") && !it.startsWith("#") }
                .take(MAX_DEEP_RULES)
                .toList()
        }
        rules.forEachIndexed { index, raw ->
            if (shouldStop(started, DEEP_SCAN_TOTAL_BUDGET_MS)) return
            if (index % 16 == 0) progress(Progress("解析深度规则", index, rules.size, raw))
            expandRule(raw).forEach { path ->
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return@forEach
                val risk = classifyRisk(path.toString())
                addCandidate(out, Candidate(
                    id = candidateId("deep", path), profile = "deep", category = "deep_rule",
                    categoryLabel = "深度规则", risk = risk, path = canonical(path), deleteRoot = false,
                    note = raw
                ), options, allowProtectedRiskInScan = true)
            }
        }
    }

    private fun scanCorpses(
        options: Options,
        out: MutableMap<String, Candidate>,
        progress: (Progress) -> Unit,
        started: Long
    ) {
        val roots = storageRoots().flatMap { root ->
            listOf(root.resolve("Android/data"), root.resolve("Android/obb"))
        }.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
        roots.forEachIndexed { rootIndex, root ->
            if (shouldStop(started, SCAN_TOTAL_BUDGET_MS)) return
            progress(Progress("扫描卸载残留", rootIndex, roots.size, root.toString()))
            runCatching {
                Files.newDirectoryStream(root).use { stream ->
                    stream.forEach { child ->
                        if (cancelled.get() || Files.isSymbolicLink(child) || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) return@forEach
                        val packageName = child.fileName.toString()
                        if (!isPackageName(packageName) || packageName in installedPackages) return@forEach
                        addCandidate(out, Candidate(
                            id = candidateId("corpses", child), profile = "corpses", category = "uninstalled_leftover",
                            categoryLabel = if (root.fileName.toString() == "obb") "卸载残留 · OBB" else "卸载残留 · Data",
                            risk = "high", path = canonical(child), packageName = packageName,
                            appName = packageName, userId = userIdFromStorage(root), deleteRoot = true
                        ), options, allowProtectedRiskInScan = true)
                    }
                }
            }
        }
    }

    private fun addCandidate(
        out: MutableMap<String, Candidate>,
        candidate: Candidate,
        options: Options,
        allowProtectedRiskInScan: Boolean = false
    ) {
        if (out.size >= MAX_CANDIDATES) return
        val path = runCatching { Paths.get(candidate.path).normalize() }.getOrNull() ?: return
        val canonical = canonical(path)
        if (!canonical.startsWith("/") || isHardProtected(canonical)) return
        if (isWhitelisted(candidate, options)) return
        if (!allowProtectedRiskInScan && candidate.risk in setOf("high", "critical")) return
        out.putIfAbsent(canonical, candidate.copy(path = canonical, id = candidateId(candidate.profile, Paths.get(canonical))))
    }

    private fun validateCandidate(candidate: Candidate, options: Options, mountPoints: Set<String>): String? {
        val path = runCatching { Paths.get(candidate.path).normalize() }.getOrNull() ?: return "路径无效"
        val canonical = canonical(path)
        if (canonical != candidate.path || isHardProtected(canonical)) return "路径超出安全边界"
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return "目标已不存在"
        if (Files.isSymbolicLink(path)) return "符号链接受保护"
        if (canonical in mountPoints) return "挂载点受保护"
        if (isWhitelisted(candidate, options)) return "白名单保护"
        if (candidate.risk == "critical") return "关键风险只允许审计"
        if (candidate.risk == "high" && !options.allowHighRisk) return "高风险清理未启用"
        if (candidate.profile == "corpses" && candidate.packageName in loadAppNames().keys) return "应用已重新安装"
        if (!profilePathStillValid(candidate, path, options)) return "目标不再符合扫描条件"
        return null
    }

    private fun profilePathStillValid(candidate: Candidate, path: Path, options: Options): Boolean = when (candidate.profile) {
        "empty" -> when (candidate.category) {
            "empty_file" -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && runCatching { Files.size(path) == 0L }.getOrDefault(false) && !isPlaceholderFile(path)
            "empty_dir" -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && isDirectoryEmpty(path)
            else -> false
        }
        "fragments" -> runCatching {
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis() <= System.currentTimeMillis() - options.fragmentDays * 86_400_000L
        }.getOrDefault(false)
        "corpses" -> candidate.packageName !in loadAppNames().keys && isCorpsePath(path)
        "deep", "rules" -> allowedMutationRoot(path)
        else -> false
    }

    private fun deleteCandidate(candidate: Candidate, path: Path, deadline: Long, mountPoints: Set<String>): Stats {
        if (candidate.deleteRoot && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            val size = runCatching { Files.size(path) }.getOrDefault(0L)
            val deleted = runCatching { Files.deleteIfExists(path) }.getOrDefault(false)
            return Stats(if (deleted) size else 0L, if (deleted) 1L else 0L, 0L, true, if (deleted) 0 else 1)
        }
        if (candidate.deleteRoot && candidate.category == "empty_dir") {
            val deleted = runCatching { Files.deleteIfExists(path) }.getOrDefault(false)
            return Stats(0L, 0L, if (deleted) 1L else 0L, true, if (deleted) 0 else 1)
        }

        var bytes = 0L
        var files = 0L
        var dirs = 0L
        var failures = 0
        runCatching {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) return FileVisitResult.TERMINATE
                    if (dir != path && (Files.isSymbolicLink(dir) || canonical(dir) in mountPoints)) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) return FileVisitResult.TERMINATE
                    if (Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE
                    if (attrs.size() > candidateMaxFileBytes(candidate)) return FileVisitResult.CONTINUE
                    val size = attrs.size()
                    if (runCatching { Files.deleteIfExists(file) }.getOrDefault(false)) {
                        bytes += size
                        files += 1
                    } else failures += 1
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    if (dir != path || candidate.deleteRoot) {
                        if (runCatching { Files.deleteIfExists(dir) }.getOrDefault(false)) dirs += 1
                    }
                    return if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                }
            })
        }.onFailure { failures += 1 }
        return Stats(bytes, files, dirs, !cancelled.get() && SystemClock.elapsedRealtime() < deadline, failures)
    }

    private fun candidateMaxFileBytes(candidate: Candidate): Long = when (candidate.risk) {
        "critical" -> 0L
        else -> Long.MAX_VALUE
    }

    private fun measure(path: Path, deadline: Long): Stats {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return Stats(0L, 0L, 0L, true)
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return Stats(runCatching { Files.size(path) }.getOrDefault(0L), 1L, 0L, true)
        }
        var bytes = 0L
        var files = 0L
        var dirs = 0L
        var complete = true
        runCatching {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) {
                        complete = false
                        return FileVisitResult.TERMINATE
                    }
                    if (dir != path && Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE
                    if (dir != path) dirs += 1
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) {
                        complete = false
                        return FileVisitResult.TERMINATE
                    }
                    if (!Files.isSymbolicLink(file) && attrs.isRegularFile) {
                        files += 1
                        bytes += attrs.size()
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        }.onFailure { complete = false }
        return Stats(bytes, files, dirs, complete)
    }

    private fun walk(
        root: Path,
        maxDepth: Int,
        deadline: Long,
        shouldPruneShared: Boolean,
        visitor: (Path, BasicFileAttributes?, Boolean) -> Unit
    ) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return
        runCatching {
            Files.walkFileTree(root, setOf(), maxDepth, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) return FileVisitResult.TERMINATE
                    if (dir != root && Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE
                    if (shouldPruneShared && dir != root && shouldPrune(dir)) return FileVisitResult.SKIP_SUBTREE
                    visitor(dir, attrs, false)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) return FileVisitResult.TERMINATE
                    visitor(file, attrs, false)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    visitor(dir, null, true)
                    return if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                }
            })
        }
    }

    private fun shouldPrune(path: Path): Boolean {
        val name = path.fileName?.toString()?.lowercase().orEmpty()
        return name in PROTECTED_SHARED_DIRS || name in PROTECTED_HIDDEN_DIRS || path.toString().contains("/Android/media/")
    }

    private fun isHardProtected(path: String): Boolean {
        val normalized = path.trimEnd('/')
        if (normalized in HARD_PROTECTED) return true
        return HARD_PROTECTED.any { normalized.startsWith("$it/") } ||
            normalized.contains("/data/adb/") || normalized.contains("/.ssh/") || normalized.contains("/.gnupg/")
    }

    private fun allowedMutationRoot(path: Path): Boolean {
        val value = canonical(path)
        return value.startsWith("/data/user/") ||
            value.startsWith("/data/data/") ||
            value.startsWith("/data/anr/") ||
            value.startsWith("/data/tombstones/") ||
            value.startsWith("/data/system/dropbox/") ||
            value.startsWith("/data/system/heapdump/") ||
            value.startsWith("/data/misc/logd/") ||
            value.startsWith("/data/vendor/log/") ||
            value.startsWith("/data/log/") ||
            value.startsWith("/storage/emulated/") ||
            value.startsWith("/sdcard/")
    }

    private fun isWhitelisted(candidate: Candidate, options: Options): Boolean {
        if (candidate.packageName.isNotBlank() && candidate.packageName in options.whitelistPackages) return true
        val path = candidate.path.trimEnd('/')
        return options.whitelistPaths.any { protected ->
            val p = protected.trimEnd('/')
            path == p || path.startsWith("$p/") || p.startsWith("$path/")
        }
    }

    private fun parseOptions(raw: String): Options {
        val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        return Options(
            whitelistPackages = jsonStringSet(json.optJSONArray("whitelistPackages")),
            whitelistPaths = jsonStringSet(json.optJSONArray("whitelistPaths")).filter { it.startsWith("/") }.toSet(),
            maxFileBytes = json.optLong("maxFileBytes", DEFAULT_MAX_FILE_BYTES).coerceIn(0L, 16L * 1024 * 1024 * 1024),
            fragmentDays = json.optInt("fragmentDays", 7).coerceIn(1, 365),
            allowHighRisk = json.optBoolean("allowHighRisk", false)
        )
    }

    private fun parseSelection(raw: String): Map<String, Boolean> {
        val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        return buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.length <= 4096) put(key, json.optBoolean(key, false))
            }
        }
    }

    private fun jsonStringSet(array: JSONArray?): Set<String> = buildSet {
        if (array == null) return@buildSet
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotBlank()) add(value)
        }
    }

    private fun storageRoots(): List<Path> {
        val roots = mutableListOf<Path>()
        val emulated = File("/storage/emulated")
        emulated.listFiles()?.filter { it.isDirectory && it.name.all(Char::isDigit) }?.forEach { roots += it.toPath() }
        if (roots.isEmpty() && File("/sdcard").isDirectory) roots += Paths.get("/sdcard")
        return roots.distinctBy(::canonical)
    }

    private fun logRoots(): List<Path> = listOf(
        Paths.get("/data/anr"), Paths.get("/data/tombstones"), Paths.get("/data/system/dropbox"),
        Paths.get("/data/system/heapdump"), Paths.get("/data/misc/logd"), Paths.get("/data/vendor/log"), Paths.get("/data/log")
    ).filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }

    private fun rulesDirectory(): Path? = listOf(
        Paths.get("/data/adb/modules/baize_v2/config"),
        Paths.get("/data/adb/modules/safesweep/config")
    ).firstOrNull { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }

    private fun deepRulesFile(): Path? = rulesDirectory()?.resolve("deep.rules")?.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }

    private fun loadAppNames(): Map<String, String> = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA).associate { info ->
            val label = context.packageManager.getApplicationLabel(info).toString().ifBlank { info.packageName }
            info.packageName to label
        }
    }.getOrDefault(emptyMap())

    private fun expandRule(raw: String): List<Path> {
        val normalized = raw.substringBefore('|').substringBefore('#').trim()
        if (!normalized.startsWith("/") || normalized.length > 4096) return emptyList()
        if (!normalized.any { it == '*' || it == '?' || it == '[' }) return listOf(Paths.get(normalized).normalize())
        val segments = normalized.split('/').filter(String::isNotEmpty)
        var current = listOf(Paths.get("/"))
        for (segment in segments) {
            val next = mutableListOf<Path>()
            val wildcard = segment.any { it == '*' || it == '?' || it == '[' }
            current.forEach { base ->
                if (next.size >= MAX_EXPANSIONS_PER_RULE) return@forEach
                if (!wildcard) {
                    next += base.resolve(segment)
                } else if (Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(base)) {
                    val regex = globSegmentRegex(segment)
                    runCatching {
                        Files.newDirectoryStream(base).use { stream ->
                            stream.forEach { child ->
                                if (next.size < MAX_EXPANSIONS_PER_RULE && regex.matches(child.fileName.toString())) next += child
                            }
                        }
                    }
                }
            }
            current = next
            if (current.isEmpty()) break
        }
        return current
    }

    private fun globSegmentRegex(segment: String): Regex {
        val builder = StringBuilder("^")
        segment.forEach { char ->
            when (char) {
                '*' -> builder.append(".*")
                '?' -> builder.append('.')
                '.', '(', ')', '+', '|', '^', '$', '@', '%' , '{', '}', '\\' -> builder.append('\\').append(char)
                else -> builder.append(char)
            }
        }
        builder.append('$')
        return Regex(builder.toString())
    }

    private fun classifyRisk(raw: String): String {
        val value = raw.lowercase()
        return when {
            CRITICAL_TOKENS.any(value::contains) -> "critical"
            HIGH_TOKENS.any(value::contains) -> "high"
            MEDIUM_TOKENS.any(value::contains) -> "medium"
            else -> "low"
        }
    }

    private fun profileLabel(profile: String): String = when (profile) {
        "empty" -> "空项目"
        "rules" -> "规则垃圾"
        "fragments" -> "残留碎片"
        "deep" -> "深度规则"
        "corpses" -> "卸载残留"
        else -> profile
    }

    private fun riskSummary(candidates: List<Candidate>): JSONObject = JSONObject().apply {
        put("low", candidates.count { it.risk == "low" })
        put("medium", candidates.count { it.risk == "medium" })
        put("high", candidates.count { it.risk == "high" })
        put("critical", candidates.count { it.risk == "critical" })
    }

    private fun candidateId(profile: String, path: Path): String = "$profile:${canonical(path)}"

    private fun canonical(path: Path): String = runCatching { path.toAbsolutePath().normalize().toString() }.getOrDefault(path.normalize().toString())

    private fun isDirectoryEmpty(path: Path): Boolean = runCatching {
        Files.newDirectoryStream(path).use { !it.iterator().hasNext() }
    }.getOrDefault(false)

    private fun isProtectedPlaceholderDirectory(path: Path): Boolean = path.fileName?.toString()?.lowercase() in PROTECTED_HIDDEN_DIRS

    private fun isPlaceholderFile(path: Path): Boolean {
        val name = path.fileName?.toString()?.lowercase().orEmpty()
        return name == ".nomedia" || name == ".keep" || name == ".gitkeep" || name == ".placeholder" || name.endsWith(".lock")
    }

    private fun isPackageName(value: String): Boolean = value.contains('.') && value.length <= 255 && value.all { it.isLetterOrDigit() || it == '.' || it == '_' }

    private fun userIdFromStorage(path: Path): Int = path.toString().split('/').firstOrNull { it.all(Char::isDigit) }?.toIntOrNull() ?: 0

    private fun isCorpsePath(path: Path): Boolean {
        val value = canonical(path)
        return Regex("^/storage/emulated/[0-9]+/Android/(data|obb)/[^/]+$").matches(value) ||
            Regex("^/sdcard/Android/(data|obb)/[^/]+$").matches(value)
    }

    private fun loadMountPoints(): Set<String> = runCatching {
        File("/proc/self/mountinfo").useLines { lines ->
            lines.mapNotNull { line ->
                val beforeSeparator = line.substringBefore(" - ")
                beforeSeparator.split(' ').getOrNull(4)?.replace("\\040", " ")
            }.toSet()
        }
    }.getOrDefault(emptySet())

    private fun sha256Of(path: Path?): String {
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return ""
        return runCatching {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
    }

    private fun resultDetail(candidate: Candidate, action: String, reason: String, bytes: Long, files: Long, dirs: Long) = JSONObject()
        .put("id", candidate.id)
        .put("action", action)
        .put("reason", reason)
        .put("profile", candidate.profile)
        .put("risk", candidate.risk)
        .put("category", candidate.categoryLabel)
        .put("path", candidate.path)
        .put("bytes", bytes)
        .put("files", files)
        .put("directories", dirs)

    private fun shouldStop(started: Long, budget: Long): Boolean = cancelled.get() || SystemClock.elapsedRealtime() - started >= budget

    private fun validSnapshot(id: String): Snapshot? {
        val snapshot = snapshots[id] ?: return null
        if (System.currentTimeMillis() - snapshot.createdAt > SNAPSHOT_TTL_MS) {
            snapshots.remove(id)
            return null
        }
        return snapshot
    }

    private fun pruneSnapshots() {
        val now = System.currentTimeMillis()
        snapshots.entries.removeAll { now - it.value.createdAt > SNAPSHOT_TTL_MS }
    }

    companion object {
        private const val SNAPSHOT_TTL_MS = 30L * 60_000L
        private const val SCAN_TOTAL_BUDGET_MS = 90_000L
        private const val DEEP_SCAN_TOTAL_BUDGET_MS = 5L * 60_000L
        private const val PAGE_MEASURE_BUDGET_MS = 8_000L
        private const val ITEM_MEASURE_BUDGET_MS = 1_500L
        private const val ITEM_CLEAN_BUDGET_MS = 20_000L
        private const val CLEAN_TOTAL_BUDGET_MS = 5L * 60_000L
        private const val DEFAULT_MAX_FILE_BYTES = 512L * 1024 * 1024
        private const val MAX_PAGE_SIZE = 60
        private const val MAX_CANDIDATES = 20_000
        private const val MAX_RULES_PER_FILE = 12_000
        private const val MAX_DEEP_RULES = 12_000
        private const val MAX_EXPANSIONS_PER_RULE = 256

        private val HARD_PROTECTED = setOf(
            "/", "/system", "/vendor", "/product", "/odm", "/apex", "/proc", "/sys", "/dev", "/metadata",
            "/data", "/data/adb", "/data/system", "/data/misc", "/storage", "/storage/emulated"
        )
        private val PROTECTED_SHARED_DIRS = setOf(
            "android", "dcim", "pictures", "movies", "music", "download", "downloads", "documents", "audiobooks", "podcasts", "ringtones", "notifications", "alarms"
        )
        private val PROTECTED_HIDDEN_DIRS = setOf(
            ".git", ".ssh", ".termux", ".config", ".local", ".obsidian", ".android", ".vscode", ".gnupg"
        )
        private val CRITICAL_TOKENS = setOf(
            "/download", "/documents", "/dcim", "/pictures", "/movies", "/music", "/android/obb", "/databases", "/shared_prefs", "backup", "draft", ".db"
        )
        private val HIGH_TOKENS = setOf("/files", "/app_webview", "/webview", "/user_data", "/profile")
        private val MEDIUM_TOKENS = setOf("tombstone", "minidump", "heapdump", "crash", "trace", "dump", "debug")
    }
}
