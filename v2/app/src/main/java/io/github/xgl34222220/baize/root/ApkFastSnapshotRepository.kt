package io.github.xgl34222220.baize.root

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Converts MediaStore hits into the same immutable APK snapshot consumed by apk-cleaner.sh.
 * No directory traversal happens here: cost is proportional only to the number of indexed hits.
 */
internal class ApkFastSnapshotRepository(
    private val stateDir: File = File(RootPaths.STATE_DIR)
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

        val accepted = ArrayList<Candidate>(source.length())
        val seenObjects = HashSet<String>()
        var rejected = 0
        var protected = 0
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: run { rejected += 1; continue }
            val requestedPath = item.optString("path").trim()
            if (requestedPath.isBlank()) { rejected += 1; continue }
            val file = File(requestedPath)
            val extension = file.name.substringAfterLast('.', "").lowercase()
            if (extension !in extensions || !isAllowedStoragePath(requestedPath)) { rejected += 1; continue }
            val stat = runCatching { Os.lstat(requestedPath) }.getOrNull() ?: run { rejected += 1; continue }
            if (!OsConstants.S_ISREG(stat.st_mode) || OsConstants.S_ISLNK(stat.st_mode)) { rejected += 1; continue }
            if (stat.st_size <= 0L || stat.st_size > maxBytes) { rejected += 1; continue }
            val canonical = runCatching { file.canonicalPath }.getOrDefault(requestedPath)
            if (!isAllowedStoragePath(canonical)) { rejected += 1; continue }
            if (whitelistPaths.any { pathContains(it, canonical) }) { protected += 1; continue }
            val objectKey = "${stat.st_dev}:${stat.st_ino}"
            if (!seenObjects.add(objectKey)) continue
            val identity = "${stat.st_dev}:${stat.st_ino}:${stat.st_size}:${stat.st_mtime}:${stat.st_ctime}"
            accepted += Candidate(canonical, file.name, stat.st_size, identity)
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
            appendLine("path_filtered=$rejected")
            appendLine("whitelist_filtered=$protected")
            appendLine("bytes=$totalBytes")
            appendLine("files=${accepted.size}")
            appendLine("engine=apk-mediastore-fast-v1")
        }
        RootFileStore.writeAtomic(File(stateDir, "apk_scan.env"), stateText)

        val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
        val result = "系统索引完成：发现 ${accepted.size} 个安装包"
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
                .put("reason", "系统索引 ${source.length()} 条 · Root 校验 ${accepted.size} 条 · 过滤 $rejected · 白名单 $protected")
        )
        RootFileStore.writeAtomic(
            File(stateDir, "apk-coverage.tsv"),
            "status\tgroup\tuser\tvolume\tfiles\tbytes\tpath\treason\n" +
                "scanned\tMediaStore.Files 系统索引\t-\texternal\t${accepted.size}\t$totalBytes\tcontent://media/external/file\t" +
                "系统索引 ${source.length()} 条 · Root 校验 ${accepted.size} 条 · 过滤 $rejected · 白名单 $protected\n"
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
