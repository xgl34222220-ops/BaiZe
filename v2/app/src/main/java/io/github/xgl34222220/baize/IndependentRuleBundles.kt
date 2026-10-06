package io.github.xgl34222220.baize

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.Base64

internal data class VerifiedRuleBundle(val version: Long, val files: Map<String, ByteArray>, val raw: ByteArray)

/** Offline publisher-authenticated rule packs; integrity alone is not treated as trust. */
internal class IndependentRuleBundles(private val root: File, private val keys: List<PublicKey>, private val appBuild: Int) {
    fun verify(raw: ByteArray): VerifiedRuleBundle {
        require(raw.size in 1..MAX_BYTES) { "规则包为空或超过 8 MiB" }
        val envelope = JSONObject(raw.toString(Charsets.UTF_8))
        val payload = Base64.getDecoder().decode(envelope.getString("payload"))
        val signature = Base64.getDecoder().decode(envelope.getString("signature"))
        check(keys.any { key -> runCatching {
            Signature.getInstance(if (key.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA").run {
                initVerify(key); update(payload); verify(signature)
            }
        }.getOrDefault(false) }) { "签名不是当前白泽发布者，拒绝导入" }
        val json = JSONObject(payload.toString(Charsets.UTF_8))
        require(json.getInt("schema") == 1 && json.getLong("version") > 0) { "规则包版本无效" }
        require(json.optInt("minAppBuild", 0) <= appBuild) { "需要更新白泽后再使用此规则包" }
        val entries = json.getJSONObject("files")
        require(entries.keys().asSequence().toSet() == NAMES.toSet()) { "规则文件集合不完整或含未知路径" }
        val files = NAMES.associateWith { name ->
            val bytes = Base64.getDecoder().decode(entries.getString(name))
            require(bytes.size <= 2 * 1024 * 1024 && !bytes.contains(0.toByte())) { "规则文件过大或包含二进制内容" }
            bytes
        }
        require(files.values.sumOf { it.size } <= 5 * 1024 * 1024) { "规则内容过大" }
        return VerifiedRuleBundle(json.getLong("version"), files, raw)
    }
    fun active(): VerifiedRuleBundle? = readPointer("active")
    fun previous(): VerifiedRuleBundle? = readPointer("previous")
    fun install(bundle: VerifiedRuleBundle) = synchronized(LOCK) {
        val verified = verify(bundle.raw)
        val current = active()
        check(current == null || verified.version > current.version) { "版本必须高于当前版本；降级请使用回滚" }
        root.mkdirs()
        val file = File(root, "version-${verified.version}.json")
        if (file.exists()) check(file.readBytes().contentEquals(bundle.raw)) { "同版本内容不一致" }
        else atomicWrite(file, bundle.raw)
        if (current != null) atomicWrite(File(root, "previous"), current.version.toString().toByteArray())
        atomicWrite(File(root, "active"), verified.version.toString().toByteArray())
    }
    fun rollback() = synchronized(LOCK) {
        val prior = previous() ?: error("没有可验证的上一版规则")
        val current = active()
        atomicWrite(File(root, "active"), prior.version.toString().toByteArray())
        if (current != null) atomicWrite(File(root, "previous"), current.version.toString().toByteArray())
    }
    fun useBundled() { synchronized(LOCK) { File(root, "active").delete() } }
    private fun readPointer(name: String): VerifiedRuleBundle? = runCatching {
        val version = File(root, name).readText().toLong()
        val bundle = File(root, "version-$version.json")
        require(bundle.length() in 1..MAX_BYTES.toLong())
        verify(bundle.readBytes()).takeIf { it.version == version }
    }.getOrNull()
    companion object {
        private val LOCK = Any()
        const val MAX_BYTES = 8 * 1024 * 1024
        val NAMES = listOf("app.rules", "external.rules", "hidden.rules", "review.rules", "custom.rules", "deep.rules", "risk-overrides.conf", "rules.meta.env")
        fun forContext(context: Context): IndependentRuleBundles {
            @Suppress("DEPRECATION") val info = context.packageManager.getPackageInfo(context.packageName,
                if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION") val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners.orEmpty() else info.signatures.orEmpty()
            val keys = certificates.map { CertificateFactory.getInstance("X.509").generateCertificate(it.toByteArray().inputStream()).publicKey }
            return IndependentRuleBundles(File(context.filesDir, "independent-rules"), keys, BuildConfig.VERSION_CODE)
        }
        internal fun atomicWrite(file: File, bytes: ByteArray) {
            file.parentFile?.mkdirs()
            val staging = File(file.parentFile, ".${file.name}-${java.util.UUID.randomUUID()}")
            FileOutputStream(staging).use { it.write(bytes); it.fd.sync() }
            Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

internal data class CustomRulePreview(val rule: String, val matches: List<StorageFileRecord>, val complete: Boolean)
internal object LocalCustomRulePreview {
    fun parse(rule: String): Pair<Regex, Int> {
        val fields = rule.trim().split('|')
        require(fields.size == 2) { "格式应为 完整路径模式|保留天数" }
        val path = fields[0]
        require(path == path.trim() && !path.contains('#')) { "路径不能带首尾空白或 # 注释符" }
        require(StorageMediaRepository.safeSharedFile(path) && path.length <= 4096 && path.none { it.isISOControl() || it in "?[]\\" } && !path.endsWith('/')) { "只支持共享存储路径及 * 通配符" }
        require(!path.substringBeforeLast('/').contains('*')) { "仅文件名可以包含 *；目录须明确" }
        val days = fields[1].toIntOrNull()?.takeIf { it in 0..365 } ?: error("保留天数须为 0–365")
        return Regex(path.split('*').joinToString("[^/]*") { Regex.escape(it) }) to days
    }
    fun preview(rule: String, index: StorageIndexResult, now: Long = System.currentTimeMillis()): CustomRulePreview {
        val (pattern, days) = parse(rule)
        return CustomRulePreview(rule.trim(), index.records.filter { pattern.matches(it.path) && it.modifiedSeconds * 1000 <= now - days * 86_400_000L },
            !index.truncated && !index.presenceIncomplete && index.records.none { it.identity == null })
    }
}
