package io.github.xgl34222220.baize.root

import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Converts MediaStore hits into the same immutable APK snapshot consumed by apk-cleaner.sh.
 * No directory traversal happens here: cost is proportional only to the number of indexed hits.
 */
internal class ApkFastSnapshotRepository(
    private val stateDir: File = File(RootPaths.STATE_DIR),
    private val cancelled: AtomicBoolean = AtomicBoolean(false)
) {
    private val extensions = setOf("apk", "apks", "xapk", "apkm", "aab")

    fun prepare(rawCandidates: String): String {
        val started = SystemClock.elapsedRealtime()
        val source = runCatching { JSONArray(rawCandidates) }.getOrElse {
            return failure("invalid_candidates", "系统文件索引结果格式无效")
        }
        if (source.length() > 10_000) return failure("too_many_candidates", "系统文件索引结果过多，请缩小范围")

        stateDir.mkdirs()
        val reportDir = File(stateDir, "reports").apply { mkdirs() }
        val config = File(stateDir, "config.conf")
        val whitelist = File(stateDir, "whitelist.conf").also { if (!it.exists()) it.createNewFile() }
        val maxBytes = readConfigLong(config, "apk_package_max_mb", 4096L).coerceIn(16L, 16384L) * 1024L * 1024L
        val whitelistPaths = readWhitelist(whitelist)

        val accepted = ArrayList<Candidate>(source.length().coerceAtLeast(32))
        val seenObjects = HashSet<String>()
        var rejected = 0
        var protected = 0
        var supplemented = 0

        fun acceptPath(requestedPath: String, fromSupplement: Boolean = false) {
            if (requestedPath.isBlank()) { rejected += 1; return }
            val file = File(requestedPath)
            val extension = file.name.substringAfterLast('.', "").lowercase()
            if (extension !in extensions || !isAllowedStoragePath(requestedPath)) { rejected += 1; return }
            val stat = runCatching { Os.lstat(requestedPath) }.getOrNull() ?: run { rejected += 1; return }
            if (!OsConstants.S_ISREG(stat.st_mode) || OsConstants.S_ISLNK(stat.st_mode)) { rejected += 1; return }
            if (stat.st_size <= 0L || stat.st_size > maxBytes) { rejected += 1; return }
            val canonical = runCatching { file.canonicalPath }.getOrDefault(requestedPath)
            if (!isAllowedStoragePath(canonical)) { rejected += 1; return }
            if (whitelistPaths.any { pathContains(it, canonical) }) { protected += 1; return }
            val objectKey = "${stat.st_dev}:${stat.st_ino}"
            if (!seenObjects.add(objectKey)) return
            val identity = fastIdentity(stat)
            accepted += Candidate(canonical, file.name, stat.st_size, identity)
            if (fromSupplement) supplemented += 1
        }

        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: run { rejected += 1; continue }
            acceptPath(item.optString("path").trim())
        }

        // MediaStore should be the normal fast path. Only when it is unexpectedly sparse do a
        // bounded scan of high-yield download/receive folders; never recurse over all /data/media.
        if (accepted.size < 10) {
            quickSupplementPaths().forEach { path ->
                if (accepted.size < 10_000) acceptPath(path, fromSupplement = true)
            }
        }

        accepted.sortByDescending { it.bytes }
        val targetsBytes = nulBytes(accepted.map { it.path })
        val identitiesBytes = nulBytes(accepted.map { it.identity })
        val epoch = System.currentTimeMillis() / 1000L
        val snapshotId = "$epoch-${sha256(targetsBytes).take(16)}"
        val totalBytes = accepted.sumOf { it.bytes }
        val targetsFile = File(stateDir, "apk_scan.targets")
        val identitiesFile = File(stateDir, "apk_scan.identities")
        writeBytesAtomic(targetsFile, targetsBytes)
        writeBytesAtomic(identitiesFile, identitiesBytes)

        val detailsText = buildString {
            append("action\trisk\tcategory\titems\tbytes\tpath\n")
            accepted.forEach { candidate ->
                append("candidate\tlow\tAPK安装包\t1\t")
                append(candidate.bytes).append('\t')
                append(candidate.path.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '))
                append('\n')
            }
        }
        val latestReport = File(reportDir, "latest.tsv")
        RootFileStore.writeAtomic(latestReport, detailsText)

        val whitelistSha = if (whitelist.isFile) sha256(whitelist.readBytes()) else "missing"
        val stateText = buildString {
            appendLine("epoch=$epoch")
            appendLine("snapshot_id=$snapshotId")
            appendLine("targets_sha=${sha256(targetsBytes)}")
            appendLine("identities_sha=${sha256(identitiesBytes)}")
            appendLine("whitelist_sha=$whitelistSha")
            appendLine("max_file_bytes=$maxBytes")
            appendLine("package_days=0")
            appendLine("configured_package_days=0")
            appendLine("include_private=0")
            appendLine("direct_index_code=0")
            appendLine("shared_index_code=0")
            appendLine("raw_candidates=${source.length()}")
            appendLine("brute_force_used=0")
            appendLine("quick_supplemented=$supplemented")
            appendLine("path_filtered=$rejected")
            appendLine("whitelist_filtered=$protected")
            appendLine("bytes=$totalBytes")
            appendLine("files=${accepted.size}")
            appendLine("engine=apk-mediastore-fast-v1")
        }
        RootFileStore.writeAtomic(File(stateDir, "apk_scan.env"), stateText)

        val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
        val result = "快速索引完成：发现 ${accepted.size} 个安装包"
        val latest = JSONObject()
            .put("mode", "apk-scan")
            .put("time", System.currentTimeMillis())
            .put("files", accepted.size)
            .put("regular_files", accepted.size)
            .put("bytes", totalBytes)
            .put("skipped", protected)
            .put("errors", rejected)
            .put("protected_items", protected)
            .put("elapsed", elapsed / 1000L)
            .put("engine", "apk-mediastore-fast-v1")
            .put("result", result)
        RootFileStore.writeAtomic(
            File(stateDir, "latest.env"),
            buildString {
                appendLine("mode=apk-scan")
                appendLine("time=${System.currentTimeMillis()}")
                appendLine("files=${accepted.size}")
                appendLine("regular_files=${accepted.size}")
                appendLine("empty_files=0")
                appendLine("empty_dirs=0")
                appendLine("hidden_items=0")
                appendLine("fragment_files=0")
                appendLine("bytes=$totalBytes")
                appendLine("skipped=$protected")
                appendLine("errors=$rejected")
                appendLine("protected_items=$protected")
                appendLine("protected_bytes=0")
                appendLine("risk_low=${accepted.size}")
                appendLine("risk_medium=0")
                appendLine("risk_high=0")
                appendLine("risk_critical=0")
                appendLine("elapsed=${elapsed / 1000L}")
                appendLine("engine=apk-mediastore-fast-v1")
                appendLine("result=$result")
            }
        )

        val coverage = JSONArray().put(
            JSONObject()
                .put("status", "scanned")
                .put("group", "MediaStore.Files 系统索引")
                .put("files", accepted.size)
                .put("bytes", totalBytes)
                .put("path", "content://media/external/file")
                .put("reason", "系统索引 ${source.length()} 条 · 快速补漏 $supplemented 条 · Root 校验 ${accepted.size} 条 · 过滤 $rejected · 白名单 $protected")
        )
        RootFileStore.writeAtomic(
            File(stateDir, "apk-coverage.tsv"),
            "status\tgroup\tuser\tvolume\tfiles\tbytes\tpath\treason\n" +
                "scanned\tMediaStore.Files 系统索引\t-\texternal\t${accepted.size}\t$totalBytes\tcontent://media/external/file\t" +
                "系统索引 ${source.length()} 条 · 快速补漏 $supplemented 条 · Root 校验 ${accepted.size} 条 · 过滤 $rejected · 白名单 $protected\n"
        )

        val details = JSONArray()
        accepted.take(500).forEach { candidate ->
            details.put(
                JSONObject()
                    .put("name", candidate.name)
                    .put("files", 1)
                    .put("bytes", candidate.bytes)
                    .put("errors", 0)
                    .put("samplePath", candidate.path)
            )
        }
        return JSONObject()
            .put("success", true)
            .put("mode", "apk-fast-scan")
            .put("exitCode", 0)
            .put("cancelled", false)
            .put("elapsedMs", elapsed)
            .put("latest", latest)
            .put("otherDetails", details)
            .put("coverage", coverage)
            .put("message", result)
            .put("output", "MediaStore.Files → Root lstat 校验；未执行全盘递归扫描")
            .toString()
    }

    fun clean(): String {
        val started = SystemClock.elapsedRealtime()
        val stateFile = File(stateDir, "apk_scan.env")
        val targetsFile = File(stateDir, "apk_scan.targets")
        val identitiesFile = File(stateDir, "apk_scan.identities")
        val whitelistFile = File(stateDir, "whitelist.conf").also { if (!it.exists()) it.createNewFile() }
        val state = RootFileStore.readEnv(stateFile)
        if (state.optString("engine") != "apk-mediastore-fast-v1") {
            return failure("snapshot_engine_mismatch", "当前不是系统索引快照，请重新扫描")
        }
        val epoch = state.optLong("epoch", 0L)
        val age = System.currentTimeMillis() / 1000L - epoch
        if (epoch <= 0L || age !in 0..1800L) return failure("snapshot_expired", "安装包快照已过期，请重新扫描")
        if (!targetsFile.isFile || !identitiesFile.isFile) return failure("snapshot_missing", "安装包快照缺失，请重新扫描")

        val targetBytes = runCatching { targetsFile.readBytes() }.getOrElse { return failure("snapshot_read_failed", "无法读取安装包快照") }
        val identityBytes = runCatching { identitiesFile.readBytes() }.getOrElse { return failure("snapshot_read_failed", "无法读取安装包身份快照") }
        if (sha256(targetBytes) != state.optString("targets_sha") ||
            sha256(identityBytes) != state.optString("identities_sha")) {
            return failure("snapshot_changed", "安装包快照校验失败，请重新扫描")
        }
        val currentWhitelistSha = if (whitelistFile.isFile) sha256(whitelistFile.readBytes()) else "missing"
        if (currentWhitelistSha != state.optString("whitelist_sha")) {
            return failure("whitelist_changed", "白名单已变化，请重新扫描")
        }

        val targets = readNul(targetBytes)
        val identities = readNul(identityBytes)
        if (targets.size != identities.size) return failure("snapshot_incomplete", "安装包身份快照不完整，请重新扫描")
        val whitelistPaths = readWhitelist(whitelistFile)
        val details = JSONArray()
        val deletedPaths = ArrayList<String>()
        var deletedFiles = 0
        var deletedBytes = 0L
        var skipped = 0
        var errors = 0
        var stopped = false

        targets.indices.forEach { index ->
            if (cancelled.get()) { stopped = true; return@forEach }
            val path = targets[index]
            val expected = identities[index]
            if (!isAllowedStoragePath(path) || whitelistPaths.any { pathContains(it, path) }) {
                skipped += 1
                details.put(resultRow("protected", path, 0L, "路径或白名单保护"))
                return@forEach
            }
            val stat = runCatching { Os.lstat(path) }.getOrNull()
            if (stat == null || !OsConstants.S_ISREG(stat.st_mode) || OsConstants.S_ISLNK(stat.st_mode)) {
                skipped += 1
                details.put(resultRow("protected", path, 0L, "目标已变化或不存在"))
                return@forEach
            }
            if (fastIdentity(stat) != expected) {
                skipped += 1
                details.put(resultRow("protected", path, stat.st_size, "扫描后文件已变化"))
                return@forEach
            }
            val size = stat.st_size.coerceAtLeast(0L)
            val deleted = runCatching { Os.remove(path); true }.getOrDefault(false)
            if (deleted) {
                deletedFiles += 1
                deletedBytes += size
                deletedPaths += path
                details.put(resultRow("cleaned", path, size, "已删除"))
            } else {
                errors += 1
                details.put(resultRow("failed", path, size, "删除失败"))
            }
        }

        if (deletedPaths.isNotEmpty()) RootMediaScanQueue.enqueue(stateDir, deletedPaths)
        if (!stopped) {
            stateFile.delete()
            targetsFile.delete()
            identitiesFile.delete()
        }
        val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
        val success = !stopped && skipped == 0 && errors == 0
        val result = when {
            stopped -> "安装包清理已停止：已删除 $deletedFiles 个"
            skipped > 0 || errors > 0 -> "安装包清理未完全生效：删除 $deletedFiles 个，跳过 $skipped 个，失败 $errors 个"
            else -> "安装包清理完成：删除 $deletedFiles 个，释放 ${humanBytes(deletedBytes)}"
        }
        val latest = JSONObject()
            .put("mode", "apk-clean")
            .put("files", deletedFiles)
            .put("bytes", deletedBytes)
            .put("skipped", skipped)
            .put("errors", errors)
            .put("elapsed", elapsed / 1000L)
            .put("engine", "apk-mediastore-fast-v1")
            .put("result", result)
        RootFileStore.writeAtomic(
            File(stateDir, "latest.env"),
            buildString {
                appendLine("mode=apk-clean")
                appendLine("files=$deletedFiles")
                appendLine("regular_files=$deletedFiles")
                appendLine("bytes=$deletedBytes")
                appendLine("skipped=$skipped")
                appendLine("errors=$errors")
                appendLine("protected_items=$skipped")
                appendLine("elapsed=${elapsed / 1000L}")
                appendLine("engine=apk-mediastore-fast-v1")
                appendLine("result=$result")
            }
        )
        return JSONObject()
            .put("success", success)
            .put("cancelled", stopped)
            .put("exitCode", if (stopped) 9 else if (success) 0 else 8)
            .put("elapsedMs", elapsed)
            .put("latest", latest)
            .put("otherDetails", details)
            .put("message", result)
            .put("output", "RootService lstat/unlink；未启动 shell rm/find 子进程")
            .toString()
    }

    private fun resultRow(action: String, path: String, bytes: Long, reason: String): JSONObject =
        JSONObject()
            .put("action", action)
            .put("name", File(path).name)
            .put("files", 1)
            .put("bytes", bytes)
            .put("errors", if (action == "failed") 1 else 0)
            .put("samplePath", path)
            .put("reason", reason)

    private fun fastIdentity(stat: android.system.StructStat): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            "v1:${stat.st_dev}:${stat.st_ino}:${stat.st_size}:" +
                "${stat.st_mtim.tv_sec}:${stat.st_mtim.tv_nsec}:${stat.st_ctim.tv_sec}:${stat.st_ctim.tv_nsec}"
        } else {
            "v0:${stat.st_dev}:${stat.st_ino}:${stat.st_size}:${stat.st_mtime}:${stat.st_ctime}"
        }

    private fun readNul(bytes: ByteArray): List<String> {
        val result = ArrayList<String>()
        var start = 0
        for (index in bytes.indices) {
            if (bytes[index] != 0.toByte()) continue
            if (index > start) result += String(bytes, start, index - start, Charsets.UTF_8)
            start = index + 1
        }
        if (start < bytes.size) result += String(bytes, start, bytes.size - start, Charsets.UTF_8)
        return result.filter { it.isNotBlank() }
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.2f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
    private fun quickSupplementPaths(): Sequence<String> = sequence {
        val userRoots = LinkedHashSet<File>()
        listOf(File("/data/media"), File("/storage/emulated")).forEach { parent ->
            parent.listFiles()?.filter { it.isDirectory && it.name.all(Char::isDigit) }?.forEach(userRoots::add)
        }
        if (userRoots.isEmpty() && File("/sdcard").isDirectory) userRoots += File("/sdcard")
        val relativeRoots = listOf(
            "Download", "Downloads", "Documents", "Bluetooth",
            "Tencent/QQfile_recv", "Tencent/Timfile_recv",
            "UCDownloads", "Quark/Download", "BaiduNetdisk", "Telegram"
        )
        userRoots.forEach { userRoot ->
            relativeRoots.forEach { relative ->
                val base = File(userRoot, relative)
                if (!base.isDirectory) return@forEach
                base.walkTopDown().maxDepth(12).forEach { file ->
                    if (file.isFile && file.name.substringAfterLast('.', "").lowercase() in extensions) {
                        yield(file.absolutePath)
                    }
                }
            }
            val mediaRoot = File(userRoot, "Android/media")
            mediaRoot.listFiles()?.filter(File::isDirectory)?.forEach { appMedia ->
                appMedia.walkTopDown().maxDepth(8).forEach { file ->
                    if (file.isFile && file.name.substringAfterLast('.', "").lowercase() in extensions) {
                        yield(file.absolutePath)
                    }
                }
            }
        }
        val localTmp = File("/data/local/tmp")
        if (localTmp.isDirectory) localTmp.walkTopDown().maxDepth(4).forEach { file ->
            if (file.isFile && file.name.substringAfterLast('.', "").lowercase() in extensions) yield(file.absolutePath)
        }
    }

    private fun isAllowedStoragePath(path: String): Boolean =
        path.startsWith("/storage/") || path.startsWith("/sdcard/") ||
            path.startsWith("/mnt/media_rw/") || path.startsWith("/data/media/") ||
            path.startsWith("/data/local/tmp/")

    private fun pathContains(parent: String, child: String): Boolean {
        val base = parent.trimEnd('/')
        return child == base || child.startsWith("$base/")
    }

    private fun readWhitelist(file: File): List<String> = runCatching {
        file.readLines().map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && it.startsWith("/") }
            .map { it.trimEnd('/') }
    }.getOrDefault(emptyList())

    private fun readConfigLong(file: File, key: String, fallback: Long): Long = runCatching {
        file.useLines { lines ->
            lines.map { it.trim() }
                .firstOrNull { it.startsWith("$key=") }
                ?.substringAfter('=')?.trim()?.toLongOrNull() ?: fallback
        }
    }.getOrDefault(fallback)

    private fun nulBytes(values: List<String>): ByteArray = ByteArrayOutputStream().use { output ->
        values.forEach { value ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.write(0)
        }
        output.toByteArray()
    }

    private fun writeBytesAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${android.os.Process.myPid()}.tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        temp.setReadable(true, true)
        temp.setWritable(true, true)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IllegalStateException("无法写入 ${target.name}")
        }
        target.setReadable(true, true)
        target.setWritable(true, true)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun failure(code: String, message: String): String =
        JSONObject().put("success", false).put("error", code).put("message", message).toString()

    private data class Candidate(
        val path: String,
        val name: String,
        val bytes: Long,
        val identity: String
    )
}
