package io.github.xgl34222220.baize.root

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import io.github.xgl34222220.baize.ReviewRiskPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

/**
 * Persistent native scanner for every non-cache cleaning profile with Alpha 10 rule hardening.
 *
 * The client can only clean candidates stored in an unexpired server-side snapshot. Every target is
 * checked again immediately before mutation. The engine never accepts a new deletion path from UI.
 */
internal class NativeProfileEngine(
    private val context: Context,
    private val cancelled: AtomicBoolean,
    private val quarantineRepository: QuarantineRepository = QuarantineRepository(),
    ruleDirectory: File? = null,
    private val ruleRoots: ReviewRuleCatalog.Roots = ReviewRuleCatalog.Roots(),
    private val sharedRootOverride: List<File>? = null,
    private val packageInventory: () -> InstalledPackageInventory = { InstalledPackageInventory.read(context) },
    private val corpseStorageUser: (String) -> Int? = InstalledPackageInventory::storageUser
) {
    private val appOwnedRules = ruleDirectory == null
    private var ruleDirectory: File = ruleDirectory ?: AppRuleStore.ensure(context)

    data class Progress(
        val phase: String,
        val current: Int,
        val total: Int,
        val path: String = "",
        val bytes: Long = 0L,
        val files: Long = 0L,
        val failures: Int = 0,
        val reason: String = ""
    )

    private data class Options(
        val whitelistPackages: Set<String>,
        val whitelistPaths: Set<String>,
        val maxFileBytes: Long,
        val fragmentDays: Int,
        val allowHighRisk: Boolean,
        val maxAutoRisk: String,
        val highRiskMode: String,
        val includeReviewRules: Boolean = false,
        val coverage: ScanCoverage = ScanCoverage()
    )

    private data class Candidate(
        val id: String,
        val profile: String,
        val category: String,
        val label: String,
        val risk: String,
        val path: String,
        val packageName: String = "",
        val appName: String = "",
        val deleteRoot: Boolean = false,
        var bytes: Long = -1L,
        var files: Long = -1L,
        var directories: Long = -1L,
        var measured: Boolean = false,
        var complete: Boolean = false,
        val note: String = "",
        val blockedReason: String = "",
        val retentionDays: Int = 0,
        val identity: String = "",
        val frozenTree: FrozenReviewTree.Snapshot? = null
    ) {
        fun json(): JSONObject = JSONObject()
            .put("id", id)
            .put("profile", profile)
            .put("category", category)
            .put("categoryLabel", label)
            .put("risk", risk)
            .put("path", path)
            .put("packageName", packageName)
            .put("appName", appName.ifBlank { packageName.ifBlank { label } })
            .put("deleteRoot", deleteRoot)
            .put("bytes", bytes)
            .put("files", files)
            .put("directories", directories)
            .put("measured", measured)
            .put("complete", complete)
            .put("note", note)
            .put("blockedReason", blockedReason)
            .put("retentionDays", retentionDays)
    }

    private data class Snapshot(
        val id: String,
        val profile: String,
        val createdAt: Long,
        val ruleSha: String,
        val options: Options,
        val candidates: MutableList<Candidate>,
        var measureBudgetMs: Long = PAGE_BUDGET_MS
    )

    private data class Stats(
        val bytes: Long,
        val files: Long,
        val directories: Long,
        val complete: Boolean,
        val failures: Int = 0,
        val reason: String = ""
    )

    private data class Node(val file: File, val depth: Int, val post: Boolean = false)

    // Discovery-only observations. Neither snapshots nor mutation validation retain these.
    internal class ScanEntry(val file: File, directory: Boolean? = null) {
        private var directoryValue: Boolean? = directory
        private var fileValue: Boolean? = null
        private var lengthValue = -1L
        private var canonicalPath: String? = null

        val isDirectory: Boolean
            get() = directoryValue ?: file.isDirectory.also { directoryValue = it }
        val isFile: Boolean
            get() = fileValue ?: (!isDirectory && file.isFile).also { fileValue = it }
        val length: Long
            get() {
                if (lengthValue < 0L) lengthValue = file.length()
                return lengthValue
            }
        val path: String
            get() = canonicalPath ?: runCatching { file.canonicalFile.path }
                .getOrDefault(file.absoluteFile.normalize().path).also { canonicalPath = it }
        var emptyDirectory: Boolean = false
            private set

        fun listChildren(): Array<File>? = (try { file.listFiles() } catch (_: SecurityException) { null })
            .also { emptyDirectory = it?.isEmpty() == true }
    }

    private data class ScanNode(val file: File, val depth: Int, val postEntry: ScanEntry? = null)

    /** Counters describe directory checks, not an estimate of remaining junk or free space. */
    internal class ScanCoverage {
        var unreadableDirectories = 0
        var depthLimitedDirectories = 0
        var storageUnavailable = false
        val incomplete: Boolean get() = unreadableDirectories > 0 || depthLimitedDirectories > 0 || storageUnavailable
    }

    internal class RuleExpansionCache {
        val listings = HashMap<String, Array<File>>()
        val patterns = HashMap<String, Regex>()
    }

    private val snapshots = ConcurrentHashMap<String, Snapshot>()
    private val pathIdentity by lazy {
        val primary = runCatching { canonical(Environment.getExternalStorageDirectory()) }.getOrNull()
        AndroidPathIdentity(primary)
    }

    fun catalog(): String = JSONObject()
        .put("profiles", JSONArray().apply {
            put(profile("empty", "空项目", "空文件与空目录", "low"))
            put(profile("rules", "规则垃圾", "隐藏垃圾、系统/OEM 日志与扩展规则", "medium"))
            put(profile("fragments", "残留碎片", "过期临时文件、旋转日志、崩溃转储与中断下载", "medium"))
            put(profile("deep", "深度规则", "4,714 条有效规则分级审计与清理", "high"))
            put(profile("corpses", "卸载残留", "Android/data 与 Android/obb 残留", "high"))
        })
        .toString()

    fun scan(profile: String, optionsJson: String, progress: (Progress) -> Unit): String {
        if (appOwnedRules) ruleDirectory = AppRuleStore.ensure(context)
        pruneSnapshots()
        val id = profile.trim().lowercase()
        val options = parseOptions(optionsJson)
        val started = SystemClock.elapsedRealtime()
        val candidates = LinkedHashMap<String, Candidate>()
        val ruleSha = if (id == "deep") sha256(deepRules()) else ""

        progress(Progress("准备${label(id)}扫描", 0, 0))
        when (id) {
            "safe" -> scanSafe(options, candidates, progress, started)
            "empty" -> scanEmpty(options, candidates, progress, started)
            "rules" -> scanRules(options, candidates, progress, started)
            "fragments" -> scanFragments(options, candidates, progress, started)
            "deep" -> scanDeep(options, candidates, progress, started)
            "corpses" -> try { scanCorpses(options, candidates, progress, started) }
                catch (error: PackageInventoryUnavailable) { return inventoryFailure(error) }
            else -> return JSONObject().put("error", "unsupported_profile").put("profile", id).toString()
        }

        if (cancelled.get()) {
            return JSONObject()
                .put("cancelled", true)
                .put("profile", id)
                .put("totalCandidates", candidates.size)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .toString()
        }

        val snapshotId = UUID.randomUUID().toString()
        var frozenEntries = 0
        val freezeDeadline = started + if (id == "deep") DEEP_SCAN_TOTAL_MS else SCAN_TOTAL_MS
        val list = candidates.values.map { item ->
            if (item.blockedReason.isNotBlank()) item else {
                val remaining = freezeDeadline - SystemClock.elapsedRealtime()
                val tree = if (remaining > 0 && frozenEntries < 100_000) FrozenReviewTree.capture(File(item.path).toPath(), cancelled,
                    min(remaining, 20_000L), 100_000 - frozenEntries) else null
                frozenEntries += tree?.count() ?: 0
                if (tree?.complete == true) item.copy(frozenTree = tree, bytes = tree.bytes, files = tree.files,
                    directories = tree.directories, measured = true, complete = true)
                else item.copy(blockedReason = "逐文件身份快照未完成，未授权删除；请缩小范围后重扫", complete = false)
            }
        }.toMutableList()
        if (cancelled.get()) return JSONObject().put("cancelled", true).put("profile", id)
            .put("totalCandidates", list.size).put("elapsedMs", SystemClock.elapsedRealtime() - started).toString()
        snapshots[snapshotId] = Snapshot(snapshotId, id, System.currentTimeMillis(), ruleSha, options, list)
        progress(Progress("${label(id)}扫描完成", list.size, list.size))
        return JSONObject()
            .put("success", true)
            .put("profile", id)
            .put("profileLabel", label(id))
            .put("snapshotId", snapshotId)
            .put("snapshotExpiresInMs", SNAPSHOT_TTL_MS)
            .put("ruleSha", ruleSha)
            .put("totalCandidates", list.size)
            .put("partial", options.coverage.incomplete || list.any { !it.complete && it.blockedReason.startsWith("逐文件") } || list.size >= MAX_CANDIDATES || SystemClock.elapsedRealtime() - started >= if (id == "deep") DEEP_SCAN_TOTAL_MS else SCAN_TOTAL_MS)
            .put("unreadableDirectories", options.coverage.unreadableDirectories)
            .put("depthLimitedDirectories", options.coverage.depthLimitedDirectories)
            .put("storageUnavailable", options.coverage.storageUnavailable)
            .put("low", list.count { it.risk == "low" })
            .put("medium", list.count { it.risk == "medium" })
            .put("high", list.count { it.risk == "high" })
            .put("critical", list.count { it.risk == "critical" })
            .put("knownBytes", list.filter { it.measured }.sumOf { it.bytes.coerceAtLeast(0L) })
            .put("measuredCandidates", list.count { it.measured })
            .put("unmeasuredCandidates", list.count { !it.measured })
            .put("emptyFiles", list.count { it.category == "empty_file" })
            .put("emptyDirs", list.count { it.category == "empty_dir" })
            .put("fragmentFiles", list.count { it.category == "fragment" })
            .put("ruleTargets", list.count { it.profile == "rules" })
            .put("maxAutoRisk", options.maxAutoRisk)
            .put("highRiskMode", options.highRiskMode)
            .put("elapsedMs", SystemClock.elapsedRealtime() - started)
            .toString()
    }

    /** Root-private persistence includes original entries; UI pages omit large manifests. */
    fun persistentItems(snapshotId: String): JSONArray? = validSnapshot(snapshotId)?.candidates?.let { items ->
        JSONArray().apply { items.forEach { item -> put(item.json().put("identity", item.identity)
            .put("frozenTree", item.frozenTree?.let(FrozenReviewTree::toJson) ?: JSONObject.NULL)) } }
    }

    /**
     * Scans the three ordinary non-cache profiles in one shared-storage traversal. The old UI
     * called empty/rules/fragments separately, which walked the same tree three times before the
     * user could clean anything. A single snapshot also guarantees that the follow-up clean uses
     * exactly this candidate set instead of starting discovery again.
     */
    private fun scanSafe(
        options: Options,
        out: MutableMap<String, Candidate>,
        progress: (Progress) -> Unit,
        started: Long
    ) {
        val rules = ordinaryRules(options.includeReviewRules)
        val listings = RuleExpansionCache()
        for ((index, rule) in rules.withIndex()) {
            if (stop(started, SCAN_TOTAL_MS)) return
            if (index % 32 == 0) progress(Progress("解析安全规则", index, rules.size, rule.pattern))
            collectRule(rule, listings, options, out, started + SCAN_TOTAL_MS)
        }

        val cutoff = System.currentTimeMillis() - options.fragmentDays * 86_400_000L
        val fragmentPatterns = fragmentPatterns()
        val hidden = hiddenRules()
        val roots = storageRoots(options.coverage)
        var visited = 0
        for ((index, root) in roots.withIndex()) {
            if (stop(started, SCAN_TOTAL_MS)) return
            progress(Progress("一次遍历扫描空项目、规则垃圾与碎片", index, roots.size, root.path))
            walk(root, 9, started + SCAN_TOTAL_MS, true, options.coverage) { entry, post ->
                val file = entry.file
                if (!post) {
                    visited += 1
                    if (visited % 512 == 0) progress(Progress("单遍历扫描中 · 已检查 $visited 项", visited, 0, file.path))
                }
                if (post) {
                    if (file != root && entry.emptyDirectory && !protectedDirectoryName(file.name)) {
                        add(out, candidate("empty", "empty_dir", "空目录", "low", file, scanEntry = entry, deleteRoot = true), options, true)
                    }
                    return@walk
                }
                val hiddenMatch = collectHidden(entry, root, hidden, options, out)
                if (entry.isFile && !hiddenMatch) {
                    if (entry.length == 0L && !placeholder(file.name)) {
                        val item = candidate("empty", "empty_file", "空文件", "low", file, scanEntry = entry, deleteRoot = true)
                        item.bytes = 0L
                        item.files = 1L
                        item.directories = 0L
                        item.measured = true
                        item.complete = true
                        add(out, item, options, true)
                    } else if (file.lastModified() <= cutoff && fragmentPatterns.any { it.matcher(file.name).matches() }) {
                        val item = candidate("fragments", "fragment", "残留碎片", risk(file.path), file, scanEntry = entry, deleteRoot = true, note = "保留 ${options.fragmentDays} 天")
                        item.bytes = entry.length
                        item.files = 1L
                        item.directories = 0L
                        item.measured = true
                        item.complete = true
                        add(out, item, options, true)
                    }
                }
            }
        }

        // System/OEM log roots are outside shared storage, so only these small trees need a
        // separate fragment pass.
        val systemLogRoots = logRoots()
        for ((index, root) in systemLogRoots.withIndex()) {
            if (stop(started, SCAN_TOTAL_MS)) return
            progress(Progress("补充扫描系统日志碎片", index, systemLogRoots.size, root.path))
            walk(root, 9, started + SCAN_TOTAL_MS, false, options.coverage) { entry, post ->
                val file = entry.file
                if (!post) {
                    visited += 1
                    if (visited % 512 == 0) progress(Progress("补充扫描中 · 已检查 $visited 项", visited, 0, file.path))
                }
                if (!post && entry.isFile && file.lastModified() <= cutoff && fragmentPatterns.any { it.matcher(file.name).matches() }) {
                    val item = candidate("fragments", "fragment", "残留碎片", risk(file.path), file, scanEntry = entry, deleteRoot = true, note = "保留 ${options.fragmentDays} 天")
                    item.bytes = entry.length
                    item.files = 1L
                    item.directories = 0L
                    item.measured = true
                    item.complete = true
                    add(out, item, options, true)
                }
            }
        }
    }

    fun page(snapshotId: String, offset: Int, limit: Int): String {
        val snapshot = validSnapshot(snapshotId)
            ?: return JSONObject().put("error", "snapshot_expired").put("message", "扫描快照不存在或已过期").put("items", JSONArray()).toString()
        val start = max(0, offset)
        val count = min(MAX_PAGE_SIZE, max(1, limit))
        if (start >= snapshot.candidates.size) {
            return JSONObject().put("success", true).put("snapshotId", snapshotId)
                .put("total", snapshot.candidates.size).put("items", JSONArray()).toString()
        }
        val end = min(snapshot.candidates.size, start + count)
        val pageStarted = SystemClock.elapsedRealtime()
        val pageDeadline = pageStarted + snapshot.measureBudgetMs
        val array = JSONArray()
        for (index in start until end) {
            if (cancelled.get()) break
            val item = snapshot.candidates[index]
            if (!item.measured && item.blockedReason.isBlank() && SystemClock.elapsedRealtime() < pageDeadline) {
                val stat = measure(File(item.path), min(pageDeadline, SystemClock.elapsedRealtime() + ITEM_MEASURE_MS))
                item.bytes = stat.bytes
                item.files = stat.files
                item.directories = stat.directories
                item.measured = true
                item.complete = stat.complete
            }
            array.put(item.json())
        }
        snapshot.measureBudgetMs = (snapshot.measureBudgetMs - (SystemClock.elapsedRealtime() - pageStarted)).coerceAtLeast(0L)
        return JSONObject()
            .put("success", true)
            .put("snapshotId", snapshotId)
            .put("profile", snapshot.profile)
            .put("offset", start)
            .put("limit", count)
            .put("total", snapshot.candidates.size)
            .put("items", array)
            .toString()
    }

    fun clean(
        snapshotId: String,
        selectionJson: String,
        optionsJson: String,
        progress: (Progress) -> Unit
    ): String {
        val snapshot = validSnapshot(snapshotId)
            ?: return JSONObject().put("error", "snapshot_expired").put("message", "扫描快照不存在或已过期").toString()
        if (snapshot.profile == "deep") {
            val current = sha256(deepRules())
            if (snapshot.ruleSha.isBlank() || current != snapshot.ruleSha) {
                snapshots.remove(snapshotId)
                return JSONObject().put("error", "rules_changed").put("message", "深度规则已变化，请重新扫描").toString()
            }
        }

        val options = parseOptions(optionsJson)
        val selection = parseSelection(selectionJson)
        val selectAllSafe = selection["__all_safe__"] == true
        val selected = snapshot.candidates.filter { candidate ->
            val explicit = selection[candidate.id] == true || selection[candidate.path] == true
            explicit || (selectAllSafe && (candidate.risk == "low" || (candidate.risk == "medium" && snapshot.options.maxAutoRisk == "medium")))
        }
        if (selected.isEmpty()) {
            return JSONObject().put("error", "empty_selection").put("message", "没有明确勾选任何项目").toString()
        }

        corpsePreflight(selected)?.let { return it }

        val started = SystemClock.elapsedRealtime()
        val deadline = started + CLEAN_TOTAL_MS
        val mounts = mountPoints()
        val hiddenPolicy = hiddenRules()
        val details = JSONArray()
        var deletedBytes = 0L
        var deletedFiles = 0L
        var deletedDirectories = 0L
        var failures = 0
        var cleaned = 0
        var skipped = 0
        val completedCandidates = hashSetOf<String>()
        var inventoryStopped = false

        for ((index, candidate) in selected.withIndex()) {
            if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) break
            progress(Progress("正在清理${candidate.label}", index, selected.size, candidate.path, deletedBytes, deletedFiles, failures))
            val reason = try { validate(candidate, options, mounts) } catch (error: PackageInventoryUnavailable) {
                failures += 1
                skipped += selected.size - index
                inventoryStopped = true
                details.put(detail(candidate, "protected", error.message.orEmpty(), 0L, 0L, 0L))
                break
            }
            if (reason != null) {
                skipped += 1
                val missing = reason == "目标已不存在" && definitelyMissing(File(candidate.path))
                if (missing) completedCandidates += candidate.id
                details.put(detail(candidate, if (missing) "missing" else "protected", if (missing) "扫描后已不存在，未计入本次释放空间" else reason, 0L, 0L, 0L))
                continue
            }

            val target = File(candidate.path)
            val result = deleteCandidate(candidate, target, options.maxFileBytes, mounts, min(deadline, SystemClock.elapsedRealtime() + ITEM_CLEAN_MS), hiddenPolicy)
            // deleteCandidate already counts successful mutations exactly. Measuring the whole
            // directory before and after deletion made snapshot cleaning look like a second scan
            // and also lost the count for an empty root directory.
            val actualBytes = result.bytes.coerceAtLeast(0L)
            val actualFiles = result.files.coerceAtLeast(0L)
            val actualDirs = result.directories.coerceAtLeast(0L)
            deletedBytes += actualBytes
            deletedFiles += actualFiles
            deletedDirectories += actualDirs
            failures += result.failures

            val complete = result.complete && if (candidate.deleteRoot) !target.exists() else isEmptyDirectory(target)
            if (complete) completedCandidates += candidate.id
            if (complete || actualBytes > 0L || actualFiles > 0L || actualDirs > 0L) cleaned += 1 else skipped += 1
            details.put(detail(candidate, if (complete) "cleaned" else "partial", if (complete) "" else result.reason.ifBlank { "仍有受保护、变化或未删除项目" }, actualBytes, actualFiles, actualDirs))
        }

        snapshot.candidates.removeAll { it.id in completedCandidates }
        if (snapshot.candidates.isEmpty()) snapshots.remove(snapshotId)
        val timedOut = SystemClock.elapsedRealtime() >= deadline
        val wasCancelled = cancelled.get()
        progress(Progress(if (inventoryStopped) "安装状态无法核对，清理已停止" else if (wasCancelled) "清理已停止"
            else if (failures > 0 || timedOut) "清理未完成" else if (completedCandidates.size < selected.size) "清理结束，部分项目已保留" else "清理完成", selected.size, selected.size, bytes = deletedBytes, files = deletedFiles, failures = failures))
        return JSONObject()
            .put("success", failures == 0 && !wasCancelled && !timedOut && !inventoryStopped)
            .put("profile", snapshot.profile)
            .put("selected", selected.size)
            .put("cleanedCandidates", cleaned)
            .put("skippedCandidates", skipped)
            .put("failures", failures)
            .put("deletedBytes", deletedBytes)
            .put("deletedFiles", deletedFiles)
            .put("deletedDirectories", deletedDirectories)
            .put("cancelled", wasCancelled)
            .put("timedOut", timedOut)
            .put("inventoryUnavailable", inventoryStopped)
            .apply { if (inventoryStopped) put("message", "应用安装状态无法核对，清理已停止；未确认的项目继续保留。") }
            .put("elapsedMs", SystemClock.elapsedRealtime() - started)
            .put("details", details)
            .put("remainingSnapshotId", if (snapshot.candidates.isEmpty()) "" else snapshot.id)
            .put("remainingCandidates", snapshot.candidates.size)
            .put("snapshotExpiresInMs", (SNAPSHOT_TTL_MS - (System.currentTimeMillis() - snapshot.createdAt)).coerceAtLeast(0L))
            .toString()
    }

    fun quarantine(
    snapshotId: String,
    selectionJson: String,
    optionsJson: String,
    progress: (Progress) -> Unit
): String {
    val snapshot = validSnapshot(snapshotId)
        ?: return JSONObject().put("error", "snapshot_expired").put("message", "扫描快照不存在或已过期").toString()
    if (snapshot.profile == "deep") {
        val current = sha256(deepRules())
        if (snapshot.ruleSha.isBlank() || current != snapshot.ruleSha) {
            snapshots.remove(snapshotId)
            return JSONObject().put("error", "rules_changed").put("message", "深度规则已变化，请重新扫描").toString()
        }
    }
    if (snapshot.options.highRiskMode == "audit") {
        return JSONObject().put("error", "policy_audit_only").put("message", "当前保守策略仅审计高风险项目，请切换策略并重新扫描").toString()
    }
    val selection = parseSelection(selectionJson)
    val selected = snapshot.candidates.filter { candidate ->
        candidate.risk == "high" && (selection[candidate.id] == true || selection[candidate.path] == true)
    }
    if (selected.isEmpty()) {
        return JSONObject().put("error", "empty_selection").put("message", "没有明确选择高风险隔离项目").toString()
    }

    corpsePreflight(selected)?.let { return it }

    val options = parseOptions(optionsJson).copy(allowHighRisk = true)
    val mounts = mountPoints()
    val details = JSONArray()
    var quarantined = 0
    var quarantinedBytes = 0L
    var quarantinedFiles = 0L
    var quarantinedDirectories = 0L
    var failures = 0
    val completedCandidates = hashSetOf<String>()
    var inventoryStopped = false
    val started = SystemClock.elapsedRealtime()
    val deadline = started + CLEAN_TOTAL_MS
    for ((index, candidate) in selected.withIndex()) {
        if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) break
        progress(Progress("正在隔离${candidate.label}", index, selected.size, candidate.path, quarantinedBytes, quarantinedFiles, failures))
        val reason = try { validate(candidate, options, mounts) } catch (error: PackageInventoryUnavailable) {
            failures += 1
            inventoryStopped = true
            details.put(detail(candidate, "protected", error.message.orEmpty(), 0L, 0L, 0L))
            break
        }
        if (reason != null) {
            failures += 1
            details.put(detail(candidate, "protected", reason, 0L, 0L, 0L))
            continue
        }
        if (!FrozenReviewTree.unchanged(candidate.frozenTree, cancelled, ITEM_CLEAN_MS)) {
            failures += 1
            details.put(detail(candidate, "protected", "目录内容在扫描后变化，未执行整体隔离，请重新扫描", 0L, 0L, 0L))
            continue
        }
        val result = quarantineRepository.quarantine(
            snapshotId = snapshotId,
            candidateId = candidate.id,
            originalPath = candidate.path,
            profile = candidate.profile,
            category = candidate.category,
            label = candidate.label,
            risk = candidate.risk
        )
        if (result.success) {
            completedCandidates += candidate.id
            quarantined += 1
            quarantinedBytes += result.bytes
            quarantinedFiles += result.files
            quarantinedDirectories += result.directories
        } else {
            failures += 1
        }
        details.put(
            result.json()
                .put("candidateId", candidate.id)
                .put("path", candidate.path)
                .put("action", if (result.success) "quarantined" else "failed")
        )
    }
    snapshot.candidates.removeAll { it.id in completedCandidates }
    if (snapshot.candidates.isEmpty()) snapshots.remove(snapshotId)
    val wasCancelled = cancelled.get()
    val timedOut = SystemClock.elapsedRealtime() >= deadline
    progress(Progress(if (inventoryStopped) "安装状态无法核对，隔离已停止" else if (wasCancelled) "隔离已停止" else "隔离完成", selected.size, selected.size, bytes = quarantinedBytes, files = quarantinedFiles, failures = failures))
    return JSONObject()
        .put("success", true)
        .put("profile", snapshot.profile)
        .put("selected", selected.size)
        .put("quarantinedCandidates", quarantined)
        .put("quarantinedBytes", quarantinedBytes)
        .put("quarantinedFiles", quarantinedFiles)
        .put("quarantinedDirectories", quarantinedDirectories)
        .put("failures", failures)
        .put("cancelled", wasCancelled)
        .put("timedOut", timedOut)
        .put("inventoryUnavailable", inventoryStopped)
        .put("elapsedMs", SystemClock.elapsedRealtime() - started)
        .put("message", if (inventoryStopped) "应用安装状态无法核对，已停止；本次已隔离 $quarantined 个项目。" else "已隔离 $quarantined 个高风险项目，可在隔离区恢复")
        .put("details", details)
        .put("remainingSnapshotId", if (snapshot.candidates.isEmpty()) "" else snapshot.id)
        .put("remainingCandidates", snapshot.candidates.size)
        .put("snapshotExpiresInMs", (SNAPSHOT_TTL_MS - (System.currentTimeMillis() - snapshot.createdAt)).coerceAtLeast(0L))
        .toString()
}

    private fun scanEmpty(
        options: Options,
        out: MutableMap<String, Candidate>,
        progress: (Progress) -> Unit,
        started: Long
    ) {
        val roots = storageRoots(options.coverage)
        for ((index, root) in roots.withIndex()) {
            if (stop(started, SCAN_TOTAL_MS)) return
            progress(Progress("扫描空文件与空目录", index, roots.size, root.path))
            walk(root, 8, started + SCAN_TOTAL_MS, true, options.coverage) { entry, post ->
                val file = entry.file
                if (post) {
                    if (file != root && entry.emptyDirectory && !protectedDirectoryName(file.name)) {
                        add(out, candidate("empty", "empty_dir", "空目录", "low", file, scanEntry = entry, deleteRoot = true), options)
                    }
                } else if (entry.isFile && entry.length == 0L && !placeholder(file.name)) {
                    val item = candidate("empty", "empty_file", "空文件", "low", file, scanEntry = entry, deleteRoot = true)
                    item.bytes = 0L
                    item.files = 1L
                    item.directories = 0L
                    item.measured = true
                    item.complete = true
                    add(out, item, options)
                }
            }
        }
    }

    internal fun ordinaryRules(includeReviewRules: Boolean = false): List<ReviewRuleCatalog.Target> {
        val rules = logRoots().map { ReviewRuleCatalog.Target(it.path, "系统诊断日志") }.toMutableList()
        val directory = rulesDirectory()
        rules += ReviewRuleCatalog.packageRules(directory?.resolve("app.rules"), false, ruleRoots)
        rules += ReviewRuleCatalog.packageRules(directory?.resolve("external.rules"), true, ruleRoots)
        rules += ReviewRuleCatalog.webViewRules(ruleRoots)
        rules += ReviewRuleCatalog.customRules(directory?.resolve("custom.rules"))
        rules += ReviewRuleCatalog.customRules(directory?.resolve("custom-preview-files.rules")).map { it.copy(fileOnly = true, label = "自定义文件规则（已试跑）") }
        if (hiddenRules().any { it.directory && it.name == ".thumbnails" && it.days == 0 }) {
            for (root in storageRoots()) for (album in listOf("DCIM", "Pictures")) {
                rules += ReviewRuleCatalog.Target("${root.path}/$album/.thumbnails", "相册缩略图缓存", "medium")
            }
        }
        if (includeReviewRules) rules += reviewRules()
        return rules.distinctBy { it.pattern }
    }

    private fun reviewRules(): List<ReviewRuleCatalog.Target> =
        ReviewRuleCatalog.reviewRules(rulesDirectory()?.resolve("review.rules"))

    private fun collectRule(
        rule: ReviewRuleCatalog.Target,
        listings: RuleExpansionCache,
        options: Options,
        out: MutableMap<String, Candidate>,
        deadline: Long
    ) {
        for (target in expand(rule.pattern, listings)) {
            if (!target.exists() || isSymlink(target)) continue
            if (rule.fileOnly && !target.isFile) continue
            if (rule.packageRelative.isNotBlank()) {
                val base = File(target.path.removeSuffix("/${rule.packageRelative}"))
                if (isSymlink(base) || !canonical(target).startsWith("${canonical(base)}/")) continue
            }
            fun addFile(file: File, entry: ScanEntry? = null) {
                if (placeholder(file.name) || !ReviewRuleCatalog.oldEnough(file, rule.days)) return
                add(out, candidate("rules", "rule_trash", rule.label, rule.risk ?: risk(file.path), file,
                    scanEntry = entry, deleteRoot = file.isFile,
                    note = if (rule.days > 0) "保留 ${rule.days} 天" else "", retentionDays = rule.days), options, true)
            }
            if (rule.days == 0 || target.isFile) addFile(target)
            else walk(target, 32, deadline, false, options.coverage) { entry, post ->
                if (!post && entry.isFile) addFile(entry.file, entry)
            }
        }
    }

    private fun hiddenRules(): List<ReviewRuleCatalog.Hidden> =
        ReviewRuleCatalog.hiddenRules(rulesDirectory()?.resolve("hidden.rules"))

    private fun collectHidden(
        entry: ScanEntry,
        root: File,
        rules: List<ReviewRuleCatalog.Hidden>,
        options: Options,
        out: MutableMap<String, Candidate>
    ): Boolean {
        val file = entry.file
        if (file == root || placeholder(file.name) || !entry.isFile) return false
        // Every file is represented separately, including zero-day cache folders. Grouping an
        // outer .cache would otherwise bypass the age of a nested .Trash (and vice versa).
        val rule = ReviewRuleCatalog.hiddenMatch(file, rules, root) ?: return false
        if (!ReviewRuleCatalog.oldEnough(file, rule.days)) return true
        add(out, candidate("rules", "hidden_trash", "隐藏垃圾", "medium", file,
            scanEntry = entry, deleteRoot = true,
            note = if (rule.days > 0) "保留 ${rule.days} 天" else "", retentionDays = rule.days), options, true)
        return true
    }

    private fun fragmentPatterns(): List<Pattern> = listOf(
        Pattern.compile(".*\\.(tmp|temp|part|partial|download|crdownload)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\.(log\\.[0-9]+|old|bak~)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*(tombstone|minidump|heapdump|crash|trace|dump).*", Pattern.CASE_INSENSITIVE)
    )

    private fun scanRules(
        options: Options,
        out: MutableMap<String, Candidate>,
        progress: (Progress) -> Unit,
        started: Long
    ) {
        val rules = ordinaryRules(options.includeReviewRules)
        val listings = RuleExpansionCache()

        for ((index, rule) in rules.withIndex()) {
            if (stop(started, SCAN_TOTAL_MS)) return
            if (index % 32 == 0) progress(Progress("解析规则垃圾", index, rules.size, rule.pattern))
            collectRule(rule, listings, options, out, started + SCAN_TOTAL_MS)
        }

        val hidden = hiddenRules()
        for (root in storageRoots(options.coverage)) {
            walk(root, 9, started + SCAN_TOTAL_MS, true, options.coverage) { entry, post ->
                if (!post) collectHidden(entry, root, hidden, options, out)
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
        val patterns = fragmentPatterns()
        val roots = ArrayList<File>()
        roots.addAll(storageRoots(options.coverage))
        roots.addAll(logRoots())
        val distinct = roots.distinctBy { canonical(it) }
        for ((index, root) in distinct.withIndex()) {
            if (stop(started, SCAN_TOTAL_MS)) return
            progress(Progress("扫描残留碎片", index, distinct.size, root.path))
            walk(root, 9, started + SCAN_TOTAL_MS, root.path.startsWith("/storage") || root.path.startsWith("/sdcard"), options.coverage) { entry, post ->
                val file = entry.file
                if (!post && entry.isFile && file.lastModified() <= cutoff && patterns.any { it.matcher(file.name).matches() }) {
                    val item = candidate("fragments", "fragment", "残留碎片", risk(file.path), file, scanEntry = entry, deleteRoot = true, note = "保留 ${options.fragmentDays} 天")
                    item.bytes = entry.length
                    item.files = 1L
                    item.directories = 0L
                    item.measured = true
                    item.complete = true
                    add(out, item, options)
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
        val source = deepRules() ?: return
        val rules = source.useLines { lines ->
            lines.map { it.trim() }
                .filter { it.startsWith("/") && !it.startsWith("//") && !it.startsWith("#") }
                .take(MAX_RULE_LINES)
                .toList()
        }
        val listings = RuleExpansionCache()
        for ((index, raw) in rules.withIndex()) {
            if (stop(started, DEEP_SCAN_TOTAL_MS)) return
            if (index % 16 == 0) progress(Progress("解析深度规则", index, rules.size, raw))
            for (target in expand(raw, listings)) {
                if (target.exists() && !isSymlink(target)) {
                    add(out, candidate("deep", "deep_rule", "深度规则", risk(target.path), target, deleteRoot = target.isFile, note = raw), options, true)
                }
            }
        }
        for ((pattern, title) in reviewRules()) {
            if (stop(started, DEEP_SCAN_TOTAL_MS)) return
            for (target in expand(pattern, listings)) {
                if (target.exists() && !isSymlink(target)) {
                    add(out, candidate("deep", "app_diagnostics", title, "medium", target,
                        deleteRoot = target.isFile, note = pattern), options, true)
                }
            }
        }
    }

    private fun scanCorpses(
        options: Options,
        out: MutableMap<String, Candidate>,
        progress: (Progress) -> Unit,
        started: Long
    ) {
        val installed = readPackageInventory()
        val roots = ArrayList<File>()
        for (storage in storageRoots(options.coverage)) {
            roots.add(File(storage, "Android/data"))
            roots.add(File(storage, "Android/obb"))
        }
        val existing = roots.filter { it.isDirectory && !isSymlink(it) }
        existing.forEach { installed.requireUser(corpseStorageUser(canonical(it))) }
        for ((index, root) in existing.withIndex()) {
            if (stop(started, SCAN_TOTAL_MS)) return
            progress(Progress("扫描卸载残留", index, existing.size, root.path))
            val children = root.listFiles() ?: throw PackageInventoryUnavailable("卸载残留目录无法读取，本次扫描已停止，文件保持不变。")
            for (child in children) {
                if (cancelled.get()) return
                val packageName = child.name
                if (!child.isDirectory || isSymlink(child) || !packageName(packageName) || packageName in installed.packages) continue
                val item = candidate(
                    "corpses",
                    "uninstalled_leftover",
                    if (root.name == "obb") "卸载残留 · OBB" else "卸载残留 · Data",
                    "high",
                    child,
                    packageName,
                    packageName,
                    true
                )
                add(out, item, options, true)
            }
        }
    }

    private fun add(
        out: MutableMap<String, Candidate>,
        candidate: Candidate,
        options: Options,
        includeHighInScan: Boolean = false
    ) {
        if (out.size >= MAX_CANDIDATES) return
        val path = candidate.path
        if (!path.startsWith("/") || hardProtected(path)) return
        if (!includeHighInScan && (candidate.risk == "high" || candidate.risk == "critical")) return
        val owner = candidate.packageName.ifBlank { ReviewRiskPolicy.appPackage(path) }
        val blocked = when {
            whitelisted(candidate.copy(path = path, packageName = owner), options) -> "白名单保护；移出白名单后重新扫描才可选择"
            candidate.risk == "critical" -> "系统或应用关键数据，不参与清理"
            else -> ""
        }
        val item = candidate.copy(id = "${candidate.profile}:$path", path = path, packageName = owner,
            blockedReason = blocked, identity = targetIdentity(File(path)))
        val target = File(path)
        if (!item.measured && target.isFile) {
            item.bytes = target.length()
            item.files = 1L
            item.directories = 0L
            item.measured = true
            item.complete = true
        }
        out.putIfAbsent(pathIdentity.of(path), item)
    }

    private fun validate(candidate: Candidate, options: Options, mounts: Set<String>): String? {
        val target = File(candidate.path)
        val path = canonical(target)
        if (path != candidate.path || hardProtected(path)) return "路径超出安全边界"
        if (!target.exists()) return "目标已不存在"
        if (isSymlink(target)) return "符号链接受保护"
        if (mounts.contains(path)) return "挂载点受保护"
        if (whitelisted(candidate, options)) return "白名单保护"
        if (candidate.risk == "critical") return "关键风险只允许审计"
        if (candidate.risk == "high" && !options.allowHighRisk) return "高风险清理未启用"
        if (candidate.profile == "corpses") {
            val inventory = readPackageInventory()
            inventory.requireUser(corpseStorageUser(path))
            if (candidate.packageName in inventory.packages) return "应用已重新安装"
        }
        if (candidate.blockedReason.isNotBlank()) return candidate.blockedReason
        if (candidate.frozenTree?.complete != true) return "逐文件身份快照不可用，请重新扫描"
        if (!stillMatches(candidate, target, options)) return "目标不再符合扫描条件"
        if (candidate.identity.isBlank() || targetIdentity(target) != candidate.identity) return "目标已变化，请重新扫描此项目"
        return null
    }

    private fun stillMatches(candidate: Candidate, target: File, options: Options): Boolean {
        // A file can be rewritten after discovery. Retention is part of the saved rule, not
        // presentation text or the caller's current options, and must hold at deletion time.
        if (!ReviewRuleCatalog.oldEnough(target, candidate.retentionDays)) return false
        return when (candidate.profile) {
            "empty" -> if (candidate.category == "empty_file") target.isFile && target.length() == 0L && !placeholder(target.name) else target.isDirectory && isEmptyDirectory(target)
            "fragments" -> target.isFile &&
                target.lastModified() <= System.currentTimeMillis() - options.fragmentDays * 86_400_000L &&
                fragmentNameMatches(target.name)
            "corpses" -> corpsePath(canonical(target))
            "rules", "deep" -> ruleMutationAllowed(canonical(target), candidate.deleteRoot, target.isDirectory)
            else -> false
        }
    }

    private fun deleteCandidate(
        candidate: Candidate,
        target: File,
        maxFileBytes: Long,
        mounts: Set<String>,
        deadline: Long,
        hiddenPolicy: List<ReviewRuleCatalog.Hidden>
    ): Stats {
        val result = FrozenReviewTree.delete(candidate.frozenTree, candidate.deleteRoot, maxFileBytes, cancelled,
            (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)) { path, directory ->
                val file = File(path)
                !placeholder(file.name) && (path == candidate.path || path !in mounts) &&
                    (directory || ReviewRuleCatalog.oldEnough(file,
                        maxOf(candidate.retentionDays, ReviewRuleCatalog.hiddenMatch(file, hiddenPolicy)?.days ?: 0)))
            }
        val indexed = result.deletedPaths.filter { it.startsWith("/storage/") || it.startsWith("/data/media/") || it.startsWith("/mnt/media_rw/") }
        if (indexed.isNotEmpty()) RootMediaScanQueue.enqueueAsync(context, indexed)
        val reason = when (result.reason) {
            "changed_after_review" -> "扫描后的文件已变化，原文件清单继续保留，请重新扫描"
            "new_or_protected_contents" -> "目录有新增或受保护内容；新增文件未参与本次删除"
            "original_snapshot_unavailable" -> "逐文件身份快照不可用，已停止删除，请重新扫描"
            "stopped" -> "清理已停止，未处理文件继续保留"
            "protected" -> "保留期限、文件大小或路径保护"
            "unreadable_or_delete_failed" -> "文件无法读取或系统删除失败，原清单继续保留"
            "storage_parent_unavailable" -> "存储目录无法核对，未把不可访问误计为文件已不存在"
            "result_recording_failed" -> "实际处理记录保存失败，已停止后续删除"
            else -> ""
        }
        return Stats(result.bytes, result.files, result.directories, result.complete, result.failures, reason)
    }

    private fun measure(root: File, deadline: Long): Stats {
        if (!root.exists() || isSymlink(root)) return Stats(0L, 0L, 0L, true)
        if (root.isFile) return Stats(root.length(), 1L, 0L, true)
        val stack = ArrayDeque<File>()
        stack.add(root)
        var bytes = 0L
        var files = 0L
        var dirs = 0L
        var complete = true
        while (stack.isNotEmpty()) {
            if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) {
                complete = false
                break
            }
            val file = stack.removeLast()
            if (!file.exists() || isSymlink(file)) continue
            if (file.isFile) {
                files += 1L
                bytes += file.length()
            } else if (file.isDirectory) {
                if (file != root) dirs += 1L
                val children = file.listFiles()
                if (children == null) complete = false else for (child in children) stack.add(child)
            }
        }
        return Stats(bytes, files, dirs, complete)
    }

    internal fun walk(root: File, maxDepth: Int, deadline: Long, pruneShared: Boolean,
        coverage: ScanCoverage = ScanCoverage(), visitor: (ScanEntry, Boolean) -> Unit) {
        val rootEntry = ScanEntry(root)
        if (!rootEntry.isDirectory || isSymlink(root)) { coverage.unreadableDirectories++; return }
        val stack = ArrayDeque<ScanNode>()
        stack.add(ScanNode(root, 0))
        while (stack.isNotEmpty()) {
            if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) return
            val node = stack.removeLast()
            val file = node.file
            if (!file.exists() || isSymlink(file)) continue
            val entry = node.postEntry ?: if (file === root) rootEntry else ScanEntry(file)
            if (node.postEntry != null) {
                visitor(entry, true)
                continue
            }
            visitor(entry, false)
            if (!entry.isDirectory || cancelled.get() || SystemClock.elapsedRealtime() >= deadline) continue
            if (file != root && pruneShared && prune(entry)) {
                // Protected roots are retained; only descendant directory shells get post visits.
                if (node.depth >= maxDepth) {
                    val children = entry.listChildren()
                    if (children == null) coverage.unreadableDirectories++
                    else if (children.any { it.isDirectory && !isSymlink(it) }) coverage.depthLimitedDirectories++
                } else walkProtectedEmptyShells(entry, node.depth, maxDepth, deadline, coverage, visitor)
                continue
            }
            stack.add(ScanNode(file, node.depth, entry))
            val children = entry.listChildren()
            if (children == null) { coverage.unreadableDirectories++; continue }
            // Inspect the boundary itself before deciding whether more traversal is allowed.
            // An empty boundary is a valid candidate; non-empty contents remain unvisited.
            if (node.depth >= maxDepth) {
                if (children.isNotEmpty()) coverage.depthLimitedDirectories++
                continue
            }
            for (child in children) stack.add(ScanNode(child, node.depth + 1))
        }
    }

    private fun walkProtectedEmptyShells(
        protectedRoot: ScanEntry,
        currentDepth: Int,
        maxDepth: Int,
        deadline: Long,
        coverage: ScanCoverage,
        visitor: (ScanEntry, Boolean) -> Unit
    ) {
        val stack = ArrayDeque<ScanNode>()
        fun enqueueDirectories(children: Array<File>, depth: Int) {
            for (child in children) {
                if (child.isDirectory) stack.add(ScanNode(child, depth))
            }
        }
        val children = protectedRoot.listChildren()
        if (children == null) { coverage.unreadableDirectories++; return }
        enqueueDirectories(children, currentDepth + 1)
        while (stack.isNotEmpty()) {
            if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) return
            val node = stack.removeLast()
            val file = node.file
            if (!file.exists() || !file.isDirectory || isSymlink(file)) continue
            val entry = node.postEntry ?: ScanEntry(file, directory = true)
            if (node.postEntry != null) {
                visitor(entry, true)
                continue
            }
            stack.add(ScanNode(file, node.depth, entry))
            // Boundary shells still need an emptiness check, but must never be descended into.
            val nested = entry.listChildren()
            if (nested == null) { coverage.unreadableDirectories++; continue }
            if (node.depth < maxDepth) enqueueDirectories(nested, node.depth + 1)
            else if (nested.any { it.isDirectory && !isSymlink(it) }) coverage.depthLimitedDirectories++
        }
    }

    internal fun expand(rawRule: String, cache: RuleExpansionCache = RuleExpansionCache()): List<File> {
        val raw = rawRule.substringBefore('|').substringBefore('#').trim()
        if (!safeRuleSyntax(raw)) return emptyList()
        val segments = raw.split('/').filter { it.isNotEmpty() }
        var current: List<File> = listOf(File("/"))
        for (segment in segments) {
            val next = ArrayList<File>()
            val wildcard = segment.contains('*') || segment.contains('?') || segment.contains('[')
            val regex = if (wildcard) cache.patterns.getOrPut(segment) { glob(segment) } else null
            for (base in current) {
                if (cancelled.get()) return emptyList()
                if (isSymlink(base)) continue
                if (!wildcard) {
                    next.add(File(base, segment))
                } else {
                    val children = cache.listings.getOrPut(base.path) {
                        if (base.isDirectory) base.listFiles() ?: emptyArray() else emptyArray()
                    }
                    for (child in children) {
                        if (cancelled.get()) return emptyList()
                        if (requireNotNull(regex).matches(child.name)) next.add(child)
                    }
                }
            }
            current = next
            if (current.isEmpty()) break
        }
        return current
    }

    private fun safeRuleSyntax(raw: String): Boolean {
        if (!raw.startsWith("/") || raw.length !in 2..4096) return false
        if (raw.contains('\u0000') || raw.contains("\n") || raw.contains("\r")) return false
        if (raw.split('/').any { it == ".." }) return false
        if (raw.startsWith("/data/adb") || raw.startsWith("/metadata") || raw.startsWith("/proc") || raw.startsWith("/sys") || raw.startsWith("/dev")) return false
        val segments = raw.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false
        // Reject rules that wildcard an entire top-level filesystem tree.
        if (segments.take(2).any { it == "*" || it == "**" || it == "?" }) return false
        return true
    }

    private fun glob(segment: String): Regex {
        val result = StringBuilder("^")
        for (char in segment) {
            when (char) {
                '*' -> result.append(".*")
                '?' -> result.append('.')
                '.', '(', ')', '+', '|', '^', '$', '{', '}', '\\' -> result.append('\\').append(char)
                else -> result.append(char)
            }
        }
        result.append('$')
        return Regex(result.toString())
    }

    private fun parseOptions(raw: String): Options {
        val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        return Options(
            strings(json.optJSONArray("whitelistPackages")),
            strings(json.optJSONArray("whitelistPaths")).asSequence().filter { it.startsWith("/") }
                .map { pathIdentity.of(canonical(File(it))) }.toSet(),
            json.optLong("maxFileBytes", DEFAULT_MAX_FILE_BYTES).coerceIn(0L, 16L * 1024 * 1024 * 1024),
            json.optInt("fragmentDays", 7).coerceIn(0, 365),
            json.optBoolean("allowHighRisk", false),
            json.optString("maxAutoRisk", "medium").lowercase().let { if (it == "low") "low" else "medium" },
            json.optString("highRiskMode", "manual_quarantine").lowercase().let {
                if (it in setOf("audit", "manual_quarantine", "recommended_quarantine")) it else "manual_quarantine"
            },
            json.optBoolean("includeReviewRules", false)
        )
    }

    private fun parseSelection(raw: String): Map<String, Boolean> {
        val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val result = HashMap<String, Boolean>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.length <= 4096) result[key] = json.optBoolean(key, false)
        }
        return result
    }

    private fun strings(array: JSONArray?): Set<String> {
        val result = LinkedHashSet<String>()
        if (array == null) return result
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotBlank()) result.add(value)
        }
        return result
    }

    private fun candidate(
        profile: String,
        category: String,
        label: String,
        risk: String,
        file: File,
        packageName: String = "",
        appName: String = "",
        deleteRoot: Boolean = false,
        note: String = "",
        scanEntry: ScanEntry? = null,
        retentionDays: Int = 0
    ): Candidate {
        val path = scanEntry?.path ?: canonical(file)
        return Candidate("$profile:$path", profile, category, label, risk, path, packageName, appName, deleteRoot,
            note = note, retentionDays = retentionDays.coerceIn(0, 365), identity = targetIdentity(file))
    }

    private fun profile(id: String, title: String, subtitle: String, risk: String): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("subtitle", subtitle).put("risk", risk)

    private fun label(id: String): String = when (id) {
        "safe" -> "安全项目"
        "empty" -> "空项目"
        "rules" -> "规则垃圾"
        "fragments" -> "残留碎片"
        "deep" -> "深度规则"
        "corpses" -> "卸载残留"
        else -> id
    }

    private fun storageRoots(coverage: ScanCoverage? = null): List<File> {
        sharedRootOverride?.let {
            if (it.isEmpty()) coverage?.storageUnavailable = true
            return it.distinctBy(::canonical)
        }
        val result = ArrayList<File>()

        fun children(root: File): Array<File>? = (try { root.listFiles() } catch (_: SecurityException) { null }).also {
            if (it == null && root.isDirectory) coverage?.let { state -> state.unreadableDirectories++ }
        }

        fun numericUsers(root: File): List<File> = children(root)
            ?.filter { file ->
                file.isDirectory && !isSymlink(file) &&
                    file.name.isNotBlank() && file.name.all { it.isDigit() }
            }
            .orEmpty()

        // RootService may not share the app process' /storage/emulated mount namespace.
        // Prefer the raw media tree that Root can always see, then fall back to public views.
        val rawUsers = numericUsers(File("/data/media"))
        if (rawUsers.isNotEmpty()) {
            result.addAll(rawUsers)
        } else {
            result.addAll(numericUsers(File("/storage/emulated")))
            if (result.isEmpty() && File("/sdcard").isDirectory) result.add(File("/sdcard"))
        }

        // Removable storage must participate in the same discovery pass.
        children(File("/mnt/media_rw"))
            ?.filter { it.isDirectory && !isSymlink(it) }
            ?.let(result::addAll)
        children(File("/storage"))
            ?.filter { file ->
                file.isDirectory && !isSymlink(file) &&
                    file.name !in setOf("emulated", "self", "enc_emulated", "runtime")
            }
            ?.let(result::addAll)

        if (result.isEmpty()) coverage?.storageUnavailable = true
        return result.distinctBy { canonical(it) }
    }

    private fun logRoots(): List<File> = SYSTEM_LOG_ROOTS.map(::File)
        .filter { it.isDirectory && !isSymlink(it) }

    private fun rulesDirectory(): File? =
        ruleDirectory.takeIf { it.isDirectory }

    private fun deepRules(): File? = rulesDirectory()?.resolve("deep.rules")?.takeIf { it.isFile }

    private fun readPackageInventory(): InstalledPackageInventory = try {
        packageInventory().also { inventory ->
            if (inventory.userId < 0 || "android" !in inventory.packages) throw PackageInventoryUnavailable("应用安装清单不完整，已停止卸载残留处理。")
        }
    } catch (error: PackageInventoryUnavailable) { throw error
    } catch (_: Exception) { throw PackageInventoryUnavailable("应用安装清单读取失败，已停止卸载残留处理。") }

    private fun corpsePreflight(selected: List<Candidate>): String? {
        val corpses = selected.filter { it.profile == "corpses" }
        if (corpses.isEmpty()) return null
        return try {
            val inventory = readPackageInventory()
            corpses.forEach { inventory.requireUser(corpseStorageUser(it.path)) }
            null
        } catch (error: PackageInventoryUnavailable) { inventoryFailure(error) }
    }

    private fun inventoryFailure(error: PackageInventoryUnavailable): String = JSONObject()
        .put("success", false).put("error", "package_inventory_unavailable")
        .put("message", error.message).toString()

    private fun risk(path: String): String {
        val value = path.lowercase()
        val name = File(value).name
        val explicitTrash = name in setOf(".cache", ".thumbnails", ".tmp", ".temp", ".logs", "logs", "mipushlog", "xlog", "app_bugly", ".crashlytics.v3") ||
            name.startsWith(".com.google.firebase.crashlytics.files.") ||
            name.endsWith(".tmp") || name.endsWith(".temp") || name.endsWith(".part") || name.endsWith(".crdownload")
        val personalContent = PERSONAL_CONTENT.any { value.contains(it) }
        return when {
            // Traversal is broad, mutation remains conservative: user-authored content roots
            // are visible to review but never become default-clean candidates just because
            // a filename looks temporary.
            personalContent -> "critical"
            explicitTrash && !value.contains("/databases/") && !value.contains("/shared_prefs/") -> "medium"
            CRITICAL.any { value.contains(it) } -> "critical"
            HIGH.any { value.contains(it) } -> "high"
            MEDIUM.any { value.contains(it) } -> "medium"
            else -> "low"
        }
    }

    private fun hardProtected(path: String): Boolean {
        val normalized = path.trimEnd('/').ifBlank { "/" }
        if (io.github.xgl34222220.baize.OrdinaryFileTrash.isPayloadPath(normalized)) return true
        if (HARD_EXACT.contains(normalized)) return true
        if (READ_ONLY.any { normalized == it || normalized.startsWith("$it/") }) return true
        return normalized == "/data/adb" || normalized.startsWith("/data/adb/") ||
            normalized.contains("/.ssh/") || normalized.contains("/.gnupg/")
    }

    private fun mutationRoot(path: String): Boolean = path.startsWith("/data/user/") ||
        path.startsWith("/data/user_de/") ||
        path.startsWith("/data/data/") || path.startsWith("/data/anr/") ||
        path.startsWith("/data/tombstones/") || path.startsWith("/data/system/dropbox/") ||
        path.startsWith("/data/system/heapdump/") || path.startsWith("/data/misc/logd/") ||
        path.startsWith("/data/vendor/log/") || path.startsWith("/data/log/") ||
        path.startsWith("/storage/emulated/") || path.startsWith("/sdcard/") ||
        Regex("^/data/media/[0-9]+/.+").matches(path)

    private fun ruleMutationAllowed(path: String, deleteRoot: Boolean, directory: Boolean): Boolean =
        mutationRoot(path) || (!deleteRoot && directory && path in SYSTEM_LOG_ROOTS)

    private fun whitelisted(candidate: Candidate, options: Options): Boolean {
        if (candidate.packageName.isNotBlank() && options.whitelistPackages.contains(candidate.packageName)) return true
        val path = pathIdentity.of(candidate.path)
        return options.whitelistPaths.any { protected ->
            protected == "/" || path == protected || path.startsWith("$protected/") || protected.startsWith("$path/")
        }
    }

    private fun prune(entry: ScanEntry): Boolean {
        val name = entry.file.name.lowercase()
        val path = entry.path.lowercase()
        // User-facing folders are protected from root deletion, not from traversal.
        // Skipping Download/DCIM/Pictures/Android entirely made safe discovery nearly empty.
        if (HIDDEN_PROTECTED.contains(name)) return true
        // OBB trees can be huge and are not ordinary junk-search roots.
        return path.endsWith("/android/obb") || path.contains("/android/obb/")
    }

    private fun protectedDirectoryName(name: String): Boolean = HIDDEN_PROTECTED.contains(name.lowercase())

    private fun fragmentNameMatches(name: String): Boolean {
        val value = name.lowercase()
        return value.endsWith(".tmp") || value.endsWith(".temp") || value.endsWith(".part") ||
            value.endsWith(".partial") || value.endsWith(".download") || value.endsWith(".crdownload") ||
            Regex(""".*\.log\.[0-9]+$""").matches(value) || value.endsWith(".old") || value.endsWith(".bak~") ||
            value.contains("tombstone") || value.contains("minidump") || value.contains("heapdump") ||
            value.contains("crash") || value.contains("trace") || value.contains("dump")
    }

    private fun placeholder(name: String): Boolean {
        val lower = name.lowercase()
        return lower == ".nomedia" || lower == ".keep" || lower == ".gitkeep" || lower == ".placeholder" || lower.endsWith(".lock")
    }

    private fun isEmptyDirectory(file: File): Boolean = file.isDirectory && (file.list()?.isEmpty() == true)

    private fun packageName(value: String): Boolean = value.contains('.') && value.length <= 255 && value.all { it.isLetterOrDigit() || it == '.' || it == '_' }

    private fun corpsePath(path: String): Boolean = Regex("^/storage/emulated/[0-9]+/Android/(data|obb)/[^/]+$").matches(path) ||
        Regex("^/sdcard/Android/(data|obb)/[^/]+$").matches(path)

    private fun mountPoints(): Set<String> = runCatching {
        File("/proc/self/mountinfo").useLines { lines ->
            lines.mapNotNull { line -> line.substringBefore(" - ").split(' ').getOrNull(4) }
                .map { it.replace("\\040", " ") }
                .toSet()
        }
    }.getOrDefault(emptySet())

    private fun isSymlink(file: File): Boolean = runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    private fun canonical(file: File): String = runCatching { file.canonicalFile.path }.getOrDefault(file.absoluteFile.normalize().path)

    private fun sha256(file: File?): String {
        if (file == null || !file.isFile) return ""
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }.getOrDefault("")
    }

    private fun detail(candidate: Candidate, action: String, reason: String, bytes: Long, files: Long, directories: Long): JSONObject = JSONObject()
        .put("id", candidate.id)
        .put("action", action)
        .put("reason", reason)
        .put("profile", candidate.profile)
        .put("risk", candidate.risk)
        .put("category", candidate.label)
        .put("path", candidate.path)
        .put("bytes", bytes)
        .put("files", files)
        .put("directories", directories)

    private fun validSnapshot(id: String): Snapshot? {
        val snapshot = snapshots[id] ?: return null
        if (System.currentTimeMillis() - snapshot.createdAt !in 0L..SNAPSHOT_TTL_MS) {
            snapshots.remove(id)
            return null
        }
        return snapshot
    }

    private fun targetIdentity(file: File): String = runCatching {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val key = attrs.fileKey()?.toString().orEmpty()
        if (key.isBlank()) "" else if (attrs.isRegularFile) "$key:${attrs.size()}:${attrs.lastModifiedTime()}" else key
    }.getOrDefault("")

    private fun definitelyMissing(file: File): Boolean = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS); false
    } catch (_: java.nio.file.NoSuchFileException) { true } catch (_: Exception) { false }

    private fun pruneSnapshots() {
        val now = System.currentTimeMillis()
        for ((key, value) in snapshots) if (now - value.createdAt > SNAPSHOT_TTL_MS) snapshots.remove(key)
    }

    private fun stop(started: Long, budget: Long): Boolean = cancelled.get() || SystemClock.elapsedRealtime() - started >= budget

    companion object {
        private val SYSTEM_LOG_ROOTS = setOf(
            "/data/anr", "/data/tombstones", "/data/system/dropbox", "/data/system/heapdump",
            "/data/misc/logd", "/data/vendor/log", "/data/log"
        )
        private const val SNAPSHOT_TTL_MS = 30L * 60_000L
        private const val SCAN_TOTAL_MS = 90_000L
        private const val DEEP_SCAN_TOTAL_MS = 5L * 60_000L
        private const val PAGE_BUDGET_MS = 2_000L
        private const val ITEM_MEASURE_MS = 150L
        private const val ITEM_CLEAN_MS = 20_000L
        private const val CLEAN_TOTAL_MS = 5L * 60_000L
        private const val DEFAULT_MAX_FILE_BYTES = 512L * 1024 * 1024
        private const val MAX_PAGE_SIZE = 60
        private const val MAX_CANDIDATES = 20_000
        private const val MAX_RULE_LINES = 12_000

        private val HARD_EXACT = setOf(
            "/", "/data", "/data/adb", "/data/system", "/data/misc", "/storage", "/storage/emulated", "/sdcard"
        )
        private val READ_ONLY = setOf(
            "/system", "/vendor", "/product", "/odm", "/apex", "/proc", "/sys", "/dev", "/metadata"
        )
        private val HIDDEN_PROTECTED = setOf(
            ".git", ".ssh", ".termux", ".config", ".local", ".obsidian", ".android", ".vscode", ".gnupg", ".baize-quarantine", ".baize-file-trash"
        )
        private val PERSONAL_CONTENT = setOf(
            "/documents/", "/dcim/", "/pictures/", "/movies/", "/music/",
            "/audiobooks/", "/podcasts/"
        )
        private val CRITICAL = setOf(
            "/download", "/documents", "/dcim", "/pictures", "/movies", "/music", "/android/obb",
            "/databases", "/shared_prefs", "backup", "draft", ".db"
        )
        private val HIGH = setOf("/files", "/app_webview", "/webview", "/user_data", "/profile")
        private val MEDIUM = setOf("tombstone", "minidump", "heapdump", "crash", "trace", "dump", "debug")
    }
}
