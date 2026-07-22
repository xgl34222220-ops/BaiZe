package io.github.xgl34222220.baize.root

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.jar.JarFile

/**
 * Verifies the small signed index that points to official rule-pack release assets.
 *
 * The service never opens a network connection. The App downloads an index into its private cache,
 * then this Root process verifies every JAR payload entry with the installed APK certificate,
 * validates all release URLs and stores a monotonic per-channel checkpoint to reject signed replay.
 */
class RuleIndexRootService : RootService() {
    private val lock = Any()

    private val binder = object : IRuleIndexService.Stub() {
        override fun ping(): String = synchronized(lock) {
            pruneImports()
            JSONObject()
                .put("uid", Process.myUid())
                .put("root", Process.myUid() == 0)
                .put("engine", "signed-rule-index-v1")
                .put("trustedSignerSha256", JSONArray(trustedSignerFingerprints().sorted()))
                .put("stableCheckpoint", readCheckpoint("stable"))
                .put("betaCheckpoint", readCheckpoint("beta"))
                .toString()
        }

        override fun verifyIndex(
            indexPath: String?,
            channel: String?,
            currentVersionCode: Long
        ): String = synchronized(lock) {
            guarded {
                pruneImports()
                val requestedChannel = normalizeChannel(channel.orEmpty())
                val source = validateImportPath(indexPath.orEmpty())
                try {
                    val verified = inspectSignedIndex(source)
                    val manifest = verified.manifest
                    val manifestChannel = normalizeChannel(manifest.optString("channel"))
                    if (manifestChannel != requestedChannel) {
                        throw IndexException("channel_mismatch", "索引通道与当前选择不一致")
                    }
                    val now = System.currentTimeMillis()
                    val generatedAt = manifest.optLong("generatedAt", 0L)
                    val expiresAt = manifest.optLong("expiresAt", 0L)
                    if (generatedAt <= 0L || generatedAt > now + MAX_CLOCK_SKEW_MS) {
                        throw IndexException("index_time_invalid", "规则索引生成时间无效")
                    }
                    if (expiresAt <= now || expiresAt > generatedAt + MAX_INDEX_LIFETIME_MS) {
                        throw IndexException("index_expired", "规则索引已过期或有效期异常")
                    }

                    val digest = sha256(verified.manifestBytes)
                    enforceCheckpoint(requestedChannel, generatedAt, digest)
                    val releases = validateReleases(manifest, requestedChannel)
                    val selected = releases
                        .filter { it.optLong("versionCode") > currentVersionCode.coerceAtLeast(0L) }
                        .filter { it.optLong("minAppVersionCode") <= appVersionCode() }
                        .maxByOrNull { it.optLong("versionCode") }
                    val checkpoint = JSONObject()
                        .put("channel", requestedChannel)
                        .put("generatedAt", generatedAt)
                        .put("expiresAt", expiresAt)
                        .put("digest", digest)
                        .put("signerSha256", verified.signerFingerprint)
                        .put("verifiedAt", now)
                    atomicWrite(checkpointFile(requestedChannel), checkpoint.toString())

                    JSONObject()
                        .put("success", true)
                        .put("trusted", true)
                        .put("channel", requestedChannel)
                        .put("generatedAt", generatedAt)
                        .put("expiresAt", expiresAt)
                        .put("indexDigest", digest)
                        .put("signerSha256", verified.signerFingerprint)
                        .put("currentVersionCode", currentVersionCode.coerceAtLeast(0L))
                        .put("updateAvailable", selected != null)
                        .put("release", selected ?: JSONObject.NULL)
                        .put("releaseCount", releases.size)
                        .toString()
                } finally {
                    runCatching { source.delete() }
                }
            }
        }

        override fun getCheckpoint(channel: String?): String = synchronized(lock) {
            guarded {
                val normalized = normalizeChannel(channel.orEmpty())
                JSONObject(readCheckpoint(normalized).toString())
                    .put("success", true)
                    .put("channel", normalized)
                    .toString()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private data class VerifiedIndex(
        val manifest: JSONObject,
        val manifestBytes: ByteArray,
        val signerFingerprint: String
    )

    private data class IndexException(val code: String, override val message: String) :
        IllegalArgumentException(message)

    private inline fun guarded(block: () -> String): String = try {
        block()
    } catch (error: IndexException) {
        failure(error.code, error.message)
    } catch (error: Throwable) {
        failure("rule_index_failed", error.message ?: error.javaClass.simpleName)
    }

    private fun validateImportPath(raw: String): File {
        val base = File(cacheDir, IMPORT_DIRECTORY).apply { mkdirs() }.canonicalFile
        val candidate = File(raw).canonicalFile
        if (!candidate.isFile || candidate.length() !in 1..MAX_INDEX_BYTES) {
            throw IndexException("index_missing", "规则索引不存在或超过 ${MAX_INDEX_BYTES / 1024}KB")
        }
        if (!candidate.path.startsWith(base.path + File.separator)) {
            throw IndexException("index_path_denied", "只能读取应用下载到私有目录的规则索引")
        }
        return candidate
    }

    private fun inspectSignedIndex(file: File): VerifiedIndex {
        val trusted = trustedSignerFingerprints()
        if (trusted.isEmpty()) throw IndexException("signer_unavailable", "无法读取当前 APK 签名证书")
        var manifestBytes: ByteArray? = null
        val signerFingerprints = LinkedHashSet<String>()
        JarFile(file, true).use { jar ->
            val entries = jar.entries()
            var payloadCount = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name
                if (name.startsWith("META-INF/", ignoreCase = true)) continue
                if (name != INDEX_NAME) {
                    throw IndexException("index_entry_denied", "规则索引包含不允许的文件：$name")
                }
                payloadCount += 1
                if (payloadCount > 1 || manifestBytes != null) {
                    throw IndexException("index_duplicate", "规则索引包含重复清单")
                }
                val bytes = readLimited(jar.getInputStream(entry), MAX_INDEX_PAYLOAD_BYTES)
                val certificates = entry.certificates.orEmpty()
                if (certificates.isEmpty()) throw IndexException("index_unsigned", "规则索引未经过 JAR 签名")
                val fingerprints = certificates.map { sha256(it.encoded) }.toSet()
                val trustedSigner = fingerprints.firstOrNull { it in trusted }
                    ?: throw IndexException("index_signer_mismatch", "规则索引签名证书与当前 APK 不一致")
                signerFingerprints += trustedSigner
                manifestBytes = bytes
            }
        }
        if (signerFingerprints.size != 1) {
            throw IndexException("index_mixed_signers", "规则索引包含多个不一致的签名者")
        }
        val bytes = manifestBytes ?: throw IndexException("index_manifest_missing", "规则索引缺少 $INDEX_NAME")
        val manifest = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }
            .getOrElse { throw IndexException("index_manifest_invalid", "规则索引清单不是有效 JSON") }
        if (manifest.optInt("schema", 0) != INDEX_SCHEMA) {
            throw IndexException("index_schema_unsupported", "规则索引格式版本不受支持")
        }
        return VerifiedIndex(manifest, bytes, signerFingerprints.first())
    }

    private fun validateReleases(manifest: JSONObject, channel: String): List<JSONObject> {
        val array = manifest.optJSONArray("releases") ?: JSONArray()
        if (array.length() > MAX_RELEASES) throw IndexException("release_count_large", "规则索引版本数量超过限制")
        val result = ArrayList<JSONObject>(array.length())
        val seenVersionCodes = HashSet<Long>()
        for (index in 0 until array.length()) {
            val source = array.optJSONObject(index)
                ?: throw IndexException("release_invalid", "规则索引版本条目格式错误")
            val releaseChannel = normalizeChannel(source.optString("channel", channel))
            if (releaseChannel != channel) throw IndexException("release_channel_mismatch", "规则版本通道不一致")
            val packId = source.optString("packId").trim()
            if (!PACK_ID.matches(packId)) throw IndexException("release_pack_id_invalid", "规则包编号无效")
            val version = source.optString("version").trim()
            if (version.isBlank() || version.length > 80) throw IndexException("release_version_invalid", "规则包版本无效")
            val versionCode = source.optLong("versionCode", 0L)
            if (versionCode <= 0L || !seenVersionCodes.add(versionCode)) {
                throw IndexException("release_version_code_invalid", "规则包版本序号无效或重复")
            }
            val minAppVersionCode = source.optLong("minAppVersionCode", 0L)
            if (minAppVersionCode < 0L) throw IndexException("release_min_app_invalid", "最低应用版本无效")
            val bytes = source.optLong("bytes", -1L)
            if (bytes !in 1..MAX_RULE_PACKAGE_BYTES) throw IndexException("release_size_invalid", "规则包体积无效")
            val hash = source.optString("sha256").trim().lowercase()
            if (!SHA256.matches(hash)) throw IndexException("release_hash_invalid", "规则包 SHA-256 无效")
            val url = validateDownloadUrl(source.optString("url"))
            val publishedAt = source.optLong("publishedAt", 0L)
            if (publishedAt <= 0L || publishedAt > System.currentTimeMillis() + MAX_CLOCK_SKEW_MS) {
                throw IndexException("release_time_invalid", "规则包发布时间无效")
            }
            result += JSONObject()
                .put("channel", channel)
                .put("packId", packId)
                .put("version", version)
                .put("versionCode", versionCode)
                .put("minAppVersionCode", minAppVersionCode)
                .put("url", url)
                .put("sha256", hash)
                .put("bytes", bytes)
                .put("publishedAt", publishedAt)
                .put("mandatory", source.optBoolean("mandatory", false))
                .put("releaseNotes", source.optString("releaseNotes").take(MAX_RELEASE_NOTES))
        }
        return result
    }

    private fun validateDownloadUrl(raw: String): String {
        val uri = runCatching { URI(raw.trim()) }.getOrNull()
            ?: throw IndexException("release_url_invalid", "规则包下载地址无效")
        val host = uri.host?.lowercase().orEmpty()
        if (uri.scheme?.lowercase() != "https" || host !in ALLOWED_DOWNLOAD_HOSTS) {
            throw IndexException("release_url_denied", "规则包下载地址不在官方 HTTPS 域名范围")
        }
        if (uri.userInfo != null || uri.fragment != null || uri.port !in setOf(-1, 443) || uri.path.isNullOrBlank()) {
            throw IndexException("release_url_invalid", "规则包下载地址包含不允许的组成部分")
        }
        val normalized = uri.normalize()
        if (normalized.path.split('/').any { it == ".." }) {
            throw IndexException("release_url_invalid", "规则包下载地址包含路径穿越")
        }
        return normalized.toASCIIString()
    }

    private fun enforceCheckpoint(channel: String, generatedAt: Long, digest: String) {
        val previous = readCheckpoint(channel)
        val previousTime = previous.optLong("generatedAt", 0L)
        val previousDigest = previous.optString("digest")
        if (previousTime > generatedAt) {
            throw IndexException("index_replay", "检测到旧版签名索引回放，已拒绝")
        }
        if (previousTime == generatedAt && previousDigest.isNotBlank() && previousDigest != digest) {
            throw IndexException("index_equivocation", "同一索引时间出现不同内容，已拒绝")
        }
    }

    private fun normalizeChannel(raw: String): String = when (raw.trim().lowercase()) {
        "stable" -> "stable"
        "beta" -> "beta"
        else -> throw IndexException("channel_invalid", "规则更新通道无效")
    }

    private fun readCheckpoint(channel: String): JSONObject = readJson(checkpointFile(channel))

    private fun pruneImports() {
        val now = System.currentTimeMillis()
        File(cacheDir, IMPORT_DIRECTORY).apply { mkdirs() }.listFiles()?.forEach { file ->
            if (!file.isFile || file.length() > MAX_INDEX_BYTES || now - file.lastModified() > IMPORT_TTL_MS) {
                file.delete()
            }
        }
    }

    private fun trustedSignerFingerprints(): Set<String> = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty()
        }
        signatures.map { sha256(it.toByteArray()) }.toSet()
    }.getOrDefault(emptySet())

    private fun appVersionCode(): Long = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrDefault(0L)

    private fun readLimited(input: InputStream, maxBytes: Long): ByteArray = input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) throw IndexException("index_payload_large", "规则索引解压后超过限制")
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }

    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        target.setReadable(false, false)
        target.setWritable(false, false)
        target.setReadable(true, true)
        target.setWritable(true, true)
    }

    private fun readJson(file: File): JSONObject = if (!file.isFile) JSONObject() else
        runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun failure(code: String, message: String): String = JSONObject()
        .put("success", false)
        .put("error", code)
        .put("message", message)
        .toString()

    private fun checkpointFile(channel: String): File =
        File(RootPaths.STATE_DIR, "rule-index-checkpoints/$channel.json")

    companion object {
        private const val INDEX_SCHEMA = 1
        private const val INDEX_NAME = "rule-index.json"
        private const val IMPORT_DIRECTORY = "rule-index-imports"
        private const val MAX_INDEX_BYTES = 2L * 1024L * 1024L
        private const val MAX_INDEX_PAYLOAD_BYTES = 512L * 1024L
        private const val MAX_RULE_PACKAGE_BYTES = 32L * 1024L * 1024L
        private const val MAX_RELEASES = 50
        private const val MAX_RELEASE_NOTES = 4_000
        private const val IMPORT_TTL_MS = 24L * 60L * 60_000L
        private const val MAX_CLOCK_SKEW_MS = 24L * 60L * 60_000L
        private const val MAX_INDEX_LIFETIME_MS = 45L * 24L * 60L * 60_000L
        private val PACK_ID = Regex("^[A-Za-z0-9._-]{1,80}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val ALLOWED_DOWNLOAD_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "raw.githubusercontent.com"
        )
    }
}
