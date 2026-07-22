package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.IRuleIndexService
import io.github.xgl34222220.baize.root.IRulePackService
import io.github.xgl34222220.baize.root.RuleIndexRootService
import io.github.xgl34222220.baize.root.RulePackRootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class RuleUpdateSettings(
    val channel: String = "stable",
    val policy: String = "manual",
    val lastCheckAt: Long = 0L,
    val lastResult: String = "尚未检查在线规则更新"
)

internal data class RuleRelease(
    val channel: String,
    val packId: String,
    val version: String,
    val versionCode: Long,
    val minAppVersionCode: Long,
    val url: String,
    val sha256: String,
    val bytes: Long,
    val publishedAt: Long,
    val mandatory: Boolean,
    val releaseNotes: String
)

internal data class RuleIndexCheck(
    val currentVersion: String,
    val currentVersionCode: Long,
    val currentRules: Int,
    val channel: String,
    val generatedAt: Long,
    val expiresAt: Long,
    val signerSha256: String,
    val release: RuleRelease?
)

internal data class RuleDownloadProgress(
    val downloaded: Long,
    val total: Long,
    val resumed: Boolean
)

internal class RuleRootSession(
    val index: IRuleIndexService,
    val pack: IRulePackService,
    private val indexConnection: ServiceConnection,
    private val packConnection: ServiceConnection
) {
    suspend fun close() = withContext(Dispatchers.Main.immediate) {
        runCatching { RootService.unbind(indexConnection) }
        runCatching { RootService.unbind(packConnection) }
    }
}

internal object RuleUpdateClient {
    private const val PREFS = "rule_update_settings"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_POLICY = "policy"
    private const val KEY_LAST_CHECK = "last_check_at"
    private const val KEY_LAST_RESULT = "last_result"
    private const val ROOT_BIND_TIMEOUT_MS = 20_000L
    private const val MAX_INDEX_BYTES = 2L * 1024L * 1024L
    private const val MAX_PACK_BYTES = 32L * 1024L * 1024L
    private const val MAX_REDIRECTS = 5
    private const val BUFFER_SIZE = 64 * 1024

    private val INDEX_URLS = mapOf(
        "stable" to "https://github.com/xgl34222220-ops/BaiZe/releases/download/rules-index/BaiZe-Rules-Index-stable.jar",
        "beta" to "https://github.com/xgl34222220-ops/BaiZe/releases/download/rules-index/BaiZe-Rules-Index-beta.jar"
    )
    private val ALLOWED_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "raw.githubusercontent.com"
    )

    fun loadSettings(context: Context): RuleUpdateSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return RuleUpdateSettings(
            channel = normalizeChannel(prefs.getString(KEY_CHANNEL, "stable").orEmpty()),
            policy = normalizePolicy(prefs.getString(KEY_POLICY, "manual").orEmpty()),
            lastCheckAt = prefs.getLong(KEY_LAST_CHECK, 0L),
            lastResult = prefs.getString(KEY_LAST_RESULT, "尚未检查在线规则更新")
                ?: "尚未检查在线规则更新"
        )
    }

    fun saveSettings(context: Context, settings: RuleUpdateSettings) {
        val channel = normalizeChannel(settings.channel)
        var policy = normalizePolicy(settings.policy)
        if (channel == "beta" && policy == "install") policy = "download"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CHANNEL, channel)
            .putString(KEY_POLICY, policy)
            .apply()
    }

    fun recordResult(context: Context, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .putString(KEY_LAST_RESULT, text.take(500))
            .apply()
    }

    suspend fun connect(context: Context): RuleRootSession {
        val index = bind(
            context,
            Intent(context, RuleIndexRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE)
        ) { IRuleIndexService.Stub.asInterface(it) }
        return try {
            val pack = bind(
                context,
                Intent(context, RulePackRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE)
            ) { IRulePackService.Stub.asInterface(it) }
            RuleRootSession(index.first, pack.first, index.second, pack.second)
        } catch (error: Throwable) {
            withContext(Dispatchers.Main.immediate) { runCatching { RootService.unbind(index.second) } }
            throw error
        }
    }

    suspend fun check(
        context: Context,
        session: RuleRootSession,
        channel: String,
        onProgress: (RuleDownloadProgress) -> Unit = {}
    ): RuleIndexCheck {
        val normalized = normalizeChannel(channel)
        val current = JSONObject(session.pack.getCurrent())
        if (current.has("error")) error(current.optString("message", current.optString("error")))
        val indexUrl = INDEX_URLS.getValue(normalized)
        val downloaded = download(
            context = context,
            url = indexUrl,
            key = "index-$normalized",
            expectedSha256 = "",
            expectedBytes = 0L,
            maxBytes = MAX_INDEX_BYTES,
            forceFreshFinal = true,
            onProgress = onProgress
        )
        val importDirectory = File(context.cacheDir, "rule-index-imports").apply { mkdirs() }
        val imported = File(importDirectory, "${UUID.randomUUID()}.jar")
        downloaded.copyTo(imported, overwrite = true)
        val verified = JSONObject(
            session.index.verifyIndex(
                imported.absolutePath,
                normalized,
                current.optLong("versionCode", 0L)
            )
        )
        if (verified.has("error")) error(verified.optString("message", verified.optString("error")))
        val release = verified.optJSONObject("release")?.let(::parseRelease)
        return RuleIndexCheck(
            currentVersion = current.optString("version", "bundled"),
            currentVersionCode = current.optLong("versionCode", 0L),
            currentRules = current.optInt("totalRules", 0),
            channel = normalized,
            generatedAt = verified.optLong("generatedAt"),
            expiresAt = verified.optLong("expiresAt"),
            signerSha256 = verified.optString("signerSha256"),
            release = release
        )
    }

    suspend fun downloadRelease(
        context: Context,
        release: RuleRelease,
        onProgress: (RuleDownloadProgress) -> Unit = {}
    ): File {
        require(release.bytes in 1..MAX_PACK_BYTES) { "规则包体积超出限制" }
        val downloaded = download(
            context = context,
            url = release.url,
            key = "pack-${release.sha256}",
            expectedSha256 = release.sha256,
            expectedBytes = release.bytes,
            maxBytes = MAX_PACK_BYTES,
            forceFreshFinal = false,
            onProgress = onProgress
        )
        val readyDirectory = File(context.filesDir, "rule-update-ready").apply { mkdirs() }
        val ready = File(readyDirectory, "${release.sha256}.jar")
        if (!ready.isFile || ready.length() != release.bytes || sha256(ready) != release.sha256) {
            downloaded.copyTo(ready, overwrite = true)
        }
        pruneReady(readyDirectory, keep = ready.name)
        return ready
    }

    suspend fun previewRelease(
        context: Context,
        session: RuleRootSession,
        release: RuleRelease,
        readyFile: File
    ): JSONObject = withContext(Dispatchers.IO) {
        if (!readyFile.isFile || readyFile.length() != release.bytes || sha256(readyFile) != release.sha256) {
            error("已下载规则包校验失败，请重新下载")
        }
        val importDirectory = File(context.cacheDir, "rule-pack-imports").apply { mkdirs() }
        val imported = File(importDirectory, "${UUID.randomUUID()}.jar")
        readyFile.copyTo(imported, overwrite = true)
        val preview = JSONObject(session.pack.previewPackage(imported.absolutePath))
        if (preview.has("error")) error(preview.optString("message", preview.optString("error")))
        val manifest = preview.optJSONObject("manifest") ?: JSONObject()
        if (
            preview.optString("packId") != release.packId ||
            preview.optString("version") != release.version ||
            manifest.optLong("versionCode", 0L) != release.versionCode
        ) {
            error("签名规则包与签名索引描述不一致")
        }
        preview
    }

    fun readyFile(context: Context, release: RuleRelease): File =
        File(context.filesDir, "rule-update-ready/${release.sha256}.jar")

    private suspend fun <T> bind(
        context: Context,
        intent: Intent,
        convert: (IBinder?) -> T?
    ): Pair<T, ServiceConnection> = withTimeout(ROOT_BIND_TIMEOUT_MS) {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val mainHandler = Handler(Looper.getMainLooper())
                lateinit var connection: ServiceConnection
                fun unbind() {
                    val action = Runnable { runCatching { RootService.unbind(connection) } }
                    if (Looper.myLooper() == Looper.getMainLooper()) action.run() else mainHandler.post(action)
                }
                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        val service = convert(binder)
                        if (service == null) {
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Root Binder 为空"))
                            unbind()
                        } else if (continuation.isActive) {
                            continuation.resume(service to connection)
                        } else unbind()
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Root 服务已断开"))
                    }

                    override fun onBindingDied(name: ComponentName?) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Root 服务绑定失效"))
                        unbind()
                    }

                    override fun onNullBinding(name: ComponentName?) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Root 服务未返回 Binder"))
                        unbind()
                    }
                }
                continuation.invokeOnCancellation { unbind() }
                runCatching { RootService.bind(intent, connection) }
                    .onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
            }
        }
    }

    private suspend fun download(
        context: Context,
        url: String,
        key: String,
        expectedSha256: String,
        expectedBytes: Long,
        maxBytes: Long,
        forceFreshFinal: Boolean,
        onProgress: (RuleDownloadProgress) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "rule-update-downloads").apply { mkdirs() }
        val safeKey = key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        val part = File(directory, "$safeKey.part")
        val final = File(directory, "$safeKey.jar")
        val metadataFile = File(directory, "$safeKey.json")
        if (forceFreshFinal) final.delete()
        if (!forceFreshFinal && final.isFile && verifyDownloaded(final, expectedSha256, expectedBytes, maxBytes)) {
            onProgress(RuleDownloadProgress(final.length(), expectedBytes.takeIf { it > 0L } ?: final.length(), false))
            return@withContext final
        }
        if (final.exists()) final.delete()

        var metadata = readJson(metadataFile)
        if (
            metadata.optString("url") != url ||
            metadata.optString("expectedSha256") != expectedSha256 ||
            metadata.optLong("expectedBytes", 0L) != expectedBytes
        ) {
            part.delete()
            metadataFile.delete()
            metadata = JSONObject()
        }
        var offset = part.takeIf { it.isFile }?.length() ?: 0L
        if (offset > maxBytes || (expectedBytes > 0L && offset > expectedBytes)) {
            part.delete()
            offset = 0L
        }

        val connection = openConnection(
            rawUrl = url,
            offset = offset,
            ifRange = metadata.optString("etag").ifBlank { metadata.optString("lastModified") }
        )
        try {
            val code = connection.responseCode
            if (code == 416 &&
                offset > 0L && verifyDownloaded(part, expectedSha256, expectedBytes, maxBytes)
            ) {
                if (!part.renameTo(final)) part.copyTo(final, overwrite = true).also { part.delete() }
                metadataFile.delete()
                return@withContext final
            }
            if (code !in setOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                throw IllegalStateException("下载失败：HTTP $code")
            }
            val append = code == HttpURLConnection.HTTP_PARTIAL && offset > 0L
            if (!append) {
                offset = 0L
                part.delete()
            }
            val responseLength = connection.contentLengthLong.coerceAtLeast(0L)
            val announcedTotal = when {
                expectedBytes > 0L -> expectedBytes
                append -> offset + responseLength
                else -> responseLength
            }
            if (announcedTotal > maxBytes) throw IllegalStateException("下载内容超过允许体积")
            val etag = connection.getHeaderField("ETag").orEmpty()
            val modified = connection.getHeaderField("Last-Modified").orEmpty()
            atomicJson(
                metadataFile,
                JSONObject()
                    .put("url", url)
                    .put("expectedSha256", expectedSha256)
                    .put("expectedBytes", expectedBytes)
                    .put("etag", etag)
                    .put("lastModified", modified)
            )
            FileOutputStream(part, append).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = offset
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > maxBytes || (expectedBytes > 0L && total > expectedBytes)) {
                            throw IllegalStateException("下载内容超过签名索引声明的体积")
                        }
                        output.write(buffer, 0, read)
                        onProgress(RuleDownloadProgress(total, announcedTotal, append))
                    }
                }
                output.fd.sync()
            }
        } finally {
            connection.disconnect()
        }
        if (!verifyDownloaded(part, expectedSha256, expectedBytes, maxBytes)) {
            throw IllegalStateException("下载完成后的体积或 SHA-256 校验失败")
        }
        if (!part.renameTo(final)) {
            part.copyTo(final, overwrite = true)
            part.delete()
        }
        metadataFile.delete()
        final
    }

    private fun openConnection(rawUrl: String, offset: Long, ifRange: String): HttpURLConnection {
        var current = validateUrl(rawUrl)
        repeat(MAX_REDIRECTS + 1) { redirect ->
            val connection = (URL(current.toASCIIString()).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/java-archive, application/zip, application/octet-stream")
                setRequestProperty("User-Agent", "BaiZe/${BuildConfig.VERSION_NAME} rule-updater")
                if (offset > 0L) {
                    setRequestProperty("Range", "bytes=$offset-")
                    if (ifRange.isNotBlank()) setRequestProperty("If-Range", ifRange)
                }
            }
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location").orEmpty()
            connection.disconnect()
            if (redirect >= MAX_REDIRECTS || location.isBlank()) throw IllegalStateException("规则下载重定向过多或缺少地址")
            current = validateUrl(current.resolve(location))
        }
        error("规则下载重定向失败")
    }

    private fun validateUrl(raw: String): URI = validateUrl(
        runCatching { URI(raw.trim()) }.getOrElse { throw IllegalStateException("规则下载地址无效") }
    )

    private fun validateUrl(uri: URI): URI {
        val normalized = uri.normalize()
        val host = normalized.host?.lowercase().orEmpty()
        if (normalized.scheme?.lowercase() != "https" || host !in ALLOWED_HOSTS) {
            throw IllegalStateException("规则下载重定向离开官方 HTTPS 域名")
        }
        if (normalized.userInfo != null || normalized.fragment != null || normalized.port !in setOf(-1, 443)) {
            throw IllegalStateException("规则下载地址包含不允许的组成部分")
        }
        return normalized
    }

    private fun verifyDownloaded(file: File, expectedSha256: String, expectedBytes: Long, maxBytes: Long): Boolean {
        if (!file.isFile || file.length() !in 1..maxBytes) return false
        if (expectedBytes > 0L && file.length() != expectedBytes) return false
        if (expectedSha256.isNotBlank() && sha256(file) != expectedSha256.lowercase()) return false
        return true
    }

    private fun parseRelease(json: JSONObject): RuleRelease = RuleRelease(
        channel = normalizeChannel(json.optString("channel")),
        packId = json.optString("packId"),
        version = json.optString("version"),
        versionCode = json.optLong("versionCode"),
        minAppVersionCode = json.optLong("minAppVersionCode"),
        url = json.optString("url"),
        sha256 = json.optString("sha256").lowercase(),
        bytes = json.optLong("bytes"),
        publishedAt = json.optLong("publishedAt"),
        mandatory = json.optBoolean("mandatory"),
        releaseNotes = json.optString("releaseNotes")
    )

    private fun normalizeChannel(raw: String): String = if (raw.trim().lowercase() == "beta") "beta" else "stable"

    private fun normalizePolicy(raw: String): String = when (raw.trim().lowercase()) {
        "notify", "download", "install" -> raw.trim().lowercase()
        else -> "manual"
    }

    private fun atomicJson(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    private fun readJson(file: File): JSONObject = if (!file.isFile) JSONObject() else
        runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    private fun pruneReady(directory: File, keep: String) {
        directory.listFiles()?.filter { it.isFile && it.name != keep }?.forEach { it.delete() }
    }

    private fun sha256(file: File): String = FileInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
