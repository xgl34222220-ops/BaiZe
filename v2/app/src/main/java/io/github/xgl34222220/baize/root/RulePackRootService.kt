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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.jar.JarFile

/**
 * Signed rule-pack manager.
 *
 * Imported packs are ordinary JAR/ZIP files signed with the same certificate as the installed APK.
 * The service verifies every payload entry, stages a bounded preview, creates a root-only backup and
 * atomically replaces only BaiZe's managed rule files. It never scans or deletes user data.
 */
class RulePackRootService : RootService() {
    private val lock = Any()

    private val binder = object : IRulePackService.Stub() {
        override fun ping(): String = synchronized(lock) {
            pruneState()
            JSONObject()
                .put("uid", Process.myUid())
                .put("root", Process.myUid() == 0)
                .put("engine", "signed-rule-pack-v1")
                .put("trustedSignerSha256", JSONArray(trustedSignerFingerprints().sorted()))
                .put("rollbackAvailable", latestBackup() != null)
                .toString()
        }

        override fun getCurrent(): String = synchronized(lock) {
            pruneState()
            JSONObject(currentInfo().toString())
                .put("success", true)
                .put("rollbackAvailable", latestBackup() != null)
                .toString()
        }

        override fun previewPackage(packagePath: String?): String = synchronized(lock) {
            guarded {
                pruneState()
                val source = validateImportPath(packagePath.orEmpty())
                val verified = inspectSignedPackage(source)
                val previewId = UUID.randomUUID().toString()
                val directory = File(previewRoot(), previewId).apply { mkdirs() }
                val stagedRules = File(directory, "rules").apply { mkdirs() }
                verified.files.forEach { (name, bytes) ->
                    atomicWrite(File(stagedRules, name), bytes)
                }

                val current = currentRuleFiles()
                val incoming = verified.files.mapValues { (_, bytes) -> fileInfo(bytes) }
                val differences = diffFiles(current, incoming)
                val manifest = JSONObject(verified.manifest.toString())
                val preview = JSONObject()
                    .put("success", true)
                    .put("previewId", previewId)
                    .put("createdAt", System.currentTimeMillis())
                    .put("expiresAt", System.currentTimeMillis() + PREVIEW_TTL_MS)
                    .put("trusted", true)
                    .put("signerSha256", verified.signerFingerprint)
                    .put("packId", manifest.optString("packId"))
                    .put("version", manifest.optString("version"))
                    .put("versionCode", manifest.optLong("versionCode", 0L))
                    .put("createdBy", manifest.optString("createdBy", "BaiZe"))
                    .put("releaseNotes", manifest.optString("releaseNotes"))
                    .put("minAppVersionCode", manifest.optLong("minAppVersionCode", 0L))
                    .put("currentVersion", currentInfo().optString("version", "bundled"))
                    .put("currentDigest", digestRuleSet(current))
                    .put("incomingDigest", digestRuleSet(incoming))
                    .put("currentRules", current.values.sumOf { it.rules })
                    .put("incomingRules", incoming.values.sumOf { it.rules })
                    .put("files", differences)
                    .put("manifest", manifest)
                atomicWrite(File(directory, "preview.json"), preview.toString().toByteArray(Charsets.UTF_8))
                runCatching { source.delete() }
                preview.toString()
            }
        }

        override fun applyPreview(previewId: String?): String = synchronized(lock) {
            guarded {
                if (cleanerBusy()) throw PackException("busy", "扫描、清理或归类任务正在运行，暂不能更新规则")
                val directory = validPreview(previewId.orEmpty())
                val preview = readJson(File(directory, "preview.json"))
                if (preview.optBoolean("trusted") != true) {
                    throw PackException("preview_untrusted", "规则包预览未通过签名校验")
                }
                val manifest = preview.optJSONObject("manifest")
                    ?: throw PackException("manifest_missing", "规则包清单丢失")
                val staged = File(directory, "rules")
                val expected = manifestFiles(manifest)
                if (expected.isEmpty()) throw PackException("empty_pack", "规则包没有可安装文件")

                expected.forEach { (name, expectedInfo) ->
                    val file = File(staged, name)
                    if (!file.isFile || sha256(file) != expectedInfo.sha256) {
                        throw PackException("preview_changed", "预览缓存已变化，请重新导入规则包")
                    }
                }
                val backup = backupCurrent("before-update")
                applyRuleSet(staged, expected.keys)
                val installedAt = System.currentTimeMillis()
                val metadata = JSONObject(manifest.toString())
                    .put("installedAt", installedAt)
                    .put("digest", preview.optString("incomingDigest"))
                    .put("signerSha256", preview.optString("signerSha256"))
                    .put("source", "signed-import")
                atomicWrite(currentMetadataFile(), metadata.toString().toByteArray(Charsets.UTF_8))
                appendHistory(
                    JSONObject()
                        .put("time", installedAt)
                        .put("action", "install")
                        .put("version", metadata.optString("version"))
                        .put("digest", metadata.optString("digest"))
                        .put("backupId", backup?.name.orEmpty())
                )
                deleteTree(directory)
                pruneBackups()
                JSONObject(currentInfo().toString())
                    .put("success", true)
                    .put("installed", true)
                    .put("rollbackAvailable", latestBackup() != null)
                    .toString()
            }
        }

        override fun rollback(): String = synchronized(lock) {
            guarded {
                if (cleanerBusy()) throw PackException("busy", "扫描、清理或归类任务正在运行，暂不能回滚规则")
                val backup = latestBackup()
                    ?: throw PackException("backup_missing", "没有可回滚的规则版本")
                val rules = File(backup, "rules")
                MANAGED_RULES.forEach { name ->
                    val source = File(rules, name)
                    val target = File(rulesDirectory(), name)
                    if (source.isFile) atomicCopy(source, target) else target.delete()
                }
                val metadata = File(backup, "current.json")
                if (metadata.isFile) atomicCopy(metadata, currentMetadataFile()) else currentMetadataFile().delete()
                val restored = currentInfo()
                appendHistory(
                    JSONObject()
                        .put("time", System.currentTimeMillis())
                        .put("action", "rollback")
                        .put("version", restored.optString("version"))
                        .put("digest", restored.optString("digest"))
                        .put("backupId", backup.name)
                )
                deleteTree(backup)
                JSONObject(restored.toString())
                    .put("success", true)
                    .put("rolledBack", true)
                    .put("rollbackAvailable", latestBackup() != null)
                    .toString()
            }
        }

        override fun getHistory(limit: Int): String = synchronized(lock) {
            pruneState()
            val entries = historyFile().takeIf { it.isFile }
                ?.readLines()
                .orEmpty()
                .asReversed()
                .asSequence()
                .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
                .take(limit.coerceIn(1, MAX_HISTORY))
                .toList()
            JSONObject()
                .put("success", true)
                .put("items", JSONArray(entries))
                .toString()
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private inline fun guarded(block: () -> String): String = try {
        block()
    } catch (error: PackException) {
        failure(error.code, error.message ?: error.code)
    } catch (error: Throwable) {
        failure("rule_pack_failed", error.message ?: error.javaClass.simpleName)
    }

    private data class PackException(val code: String, override val message: String) : IllegalArgumentException(message)

    private data class RuleInfo(
        val sha256: String,
        val rules: Int,
        val bytes: Long
    )

    private data class VerifiedPack(
        val manifest: JSONObject,
        val files: Map<String, ByteArray>,
        val signerFingerprint: String
    )

    private fun validateImportPath(raw: String): File {
        val base = File(cacheDir, "rule-pack-imports").apply { mkdirs() }.canonicalFile
        val candidate = File(raw).canonicalFile
        if (!candidate.isFile || candidate.length() !in 1..MAX_PACKAGE_BYTES) {
            throw PackException("package_missing", "导入文件不存在或超过 ${MAX_PACKAGE_BYTES / 1024 / 1024}MB")
        }
        if (!candidate.path.startsWith(base.path + File.separator)) {
            throw PackException("package_path_denied", "只能读取应用通过系统选择器导入的规则包")
        }
        return candidate
    }

    private fun inspectSignedPackage(file: File): VerifiedPack {
        val trusted = trustedSignerFingerprints()
        if (trusted.isEmpty()) throw PackException("signer_unavailable", "无法读取当前 APK 签名证书")
        val payloads = LinkedHashMap<String, ByteArray>()
        val signerFingerprints = LinkedHashSet<String>()
        JarFile(file, true).use { jar ->
            val entries = jar.entries()
            var totalBytes = 0L
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name
                if (name.startsWith("META-INF/", ignoreCase = true)) continue
                if (name != MANIFEST_NAME && !isAllowedRuleEntry(name)) {
                    throw PackException("entry_denied", "规则包包含不允许的文件：$name")
                }
                if (payloads.containsKey(name)) throw PackException("duplicate_entry", "规则包包含重复文件：$name")
                val bytes = readLimited(jar.getInputStream(entry), MAX_ENTRY_BYTES)
                totalBytes += bytes.size
                if (totalBytes > MAX_EXPANDED_BYTES) throw PackException("package_too_large", "规则包解压后体积过大")
                val certificates = entry.certificates.orEmpty()
                if (certificates.isEmpty()) throw PackException("entry_unsigned", "规则文件未被 JAR 签名：$name")
                val entryFingerprints = certificates.map { sha256(it.encoded) }.toSet()
                val trustedEntrySigner = entryFingerprints.firstOrNull { it in trusted }
                    ?: throw PackException("signer_mismatch", "规则包签名证书与当前 APK 不一致")
                signerFingerprints += trustedEntrySigner
                payloads[name] = bytes
            }
        }
        if (signerFingerprints.size != 1) throw PackException("mixed_signers", "规则包包含多个不一致的签名者")
        val manifestBytes = payloads[MANIFEST_NAME]
            ?: throw PackException("manifest_missing", "规则包缺少 $MANIFEST_NAME")
        val manifest = runCatching { JSONObject(manifestBytes.toString(Charsets.UTF_8)) }
            .getOrElse { throw PackException("manifest_invalid", "规则包清单不是有效 JSON") }
        validateManifest(manifest, payloads)
        val files = LinkedHashMap<String, ByteArray>()
        manifestFiles(manifest).keys.forEach { name ->
            files[name] = payloads["rules/$name"]
                ?: throw PackException("rule_missing", "规则文件缺失：$name")
        }
        return VerifiedPack(manifest, files, signerFingerprints.first())
    }

    private fun validateManifest(manifest: JSONObject, payloads: Map<String, ByteArray>) {
        if (manifest.optInt("schema", 0) != PACK_SCHEMA) {
            throw PackException("schema_unsupported", "规则包格式版本不受支持")
        }
        if (manifest.optString("mode") != "full") {
            throw PackException("mode_unsupported", "规则包必须是完整包，不能使用增量覆盖")
        }
        val packId = manifest.optString("packId").trim()
        if (!PACK_ID.matches(packId)) throw PackException("pack_id_invalid", "规则包编号无效")
        val version = manifest.optString("version").trim()
        if (version.isBlank() || version.length > 80) throw PackException("version_invalid", "规则包版本无效")
        val versionCode = manifest.optLong("versionCode", 0L)
        if (versionCode <= 0L) throw PackException("version_code_invalid", "规则包缺少单调版本序号")
        val currentVersionCode = readJson(currentMetadataFile()).optLong("versionCode", 0L)
        if (currentVersionCode > 0L && versionCode < currentVersionCode) {
            throw PackException("pack_downgrade", "规则包版本序号低于当前版本；降级请使用受控回滚")
        }
        val minVersion = manifest.optLong("minAppVersionCode", 0L).coerceAtLeast(0L)
        if (minVersion > appVersionCode()) {
            throw PackException("app_too_old", "此规则包需要更高版本的白泽")
        }
        val listed = manifestFiles(manifest)
        if (listed.isEmpty()) throw PackException("empty_pack", "规则包没有托管规则文件")
        val actualEntries = payloads.keys.filter { it.startsWith("rules/") }.toSet()
        val expectedEntries = listed.keys.map { "rules/$it" }.toSet()
        if (actualEntries != expectedEntries) {
            throw PackException("manifest_mismatch", "规则包清单与实际文件不一致")
        }
        listed.forEach { (name, info) ->
            val bytes = payloads["rules/$name"] ?: throw PackException("rule_missing", "规则文件缺失：$name")
            val actual = validateRuleBytes(name, bytes)
            if (actual.sha256 != info.sha256 || actual.rules != info.rules || actual.bytes != info.bytes) {
                throw PackException("rule_hash_mismatch", "规则文件校验失败：$name")
            }
        }
    }

    private fun manifestFiles(manifest: JSONObject): LinkedHashMap<String, RuleInfo> {
        val array = manifest.optJSONArray("files") ?: JSONArray()
        val result = LinkedHashMap<String, RuleInfo>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: throw PackException("manifest_invalid", "规则文件清单格式错误")
            val path = item.optString("path")
            if (!path.startsWith("rules/") || path.count { it == '/' } != 1) {
                throw PackException("rule_path_invalid", "规则文件路径无效：$path")
            }
            val name = path.substringAfter("rules/")
            if (name !in MANAGED_RULES || result.containsKey(name)) {
                throw PackException("rule_file_denied", "不允许管理规则文件：$name")
            }
            val hash = item.optString("sha256").lowercase()
            if (!SHA256.matches(hash)) throw PackException("rule_hash_invalid", "规则文件哈希无效：$name")
            result[name] = RuleInfo(
                sha256 = hash,
                rules = item.optInt("rules", -1),
                bytes = item.optLong("bytes", -1L)
            )
            if (result[name]!!.rules < 0 || result[name]!!.bytes < 0L) {
                throw PackException("rule_metrics_invalid", "规则文件统计无效：$name")
            }
        }
        return result
    }

    private fun validateRuleBytes(name: String, bytes: ByteArray): RuleInfo {
        if (bytes.size > MAX_RULE_FILE_BYTES) throw PackException("rule_file_large", "规则文件过大：$name")
        val text = runCatching { bytes.toString(Charsets.UTF_8) }
            .getOrElse { throw PackException("rule_encoding_invalid", "规则文件不是 UTF-8：$name") }
        if ('\u0000' in text) throw PackException("rule_nul", "规则文件包含非法字符：$name")
        var rules = 0
        text.lineSequence().forEachIndexed { index, raw ->
            if (raw.length > MAX_RULE_LINE_LENGTH) throw PackException("rule_line_large", "$name 第 ${index + 1} 行过长")
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith("//")) return@forEachIndexed
            if (name == "deep.rules" && !line.startsWith("/")) return@forEachIndexed
            if (!safeRuleSyntax(name, line)) throw PackException("rule_syntax_invalid", "$name 第 ${index + 1} 行规则不安全")
            rules += 1
            if (rules > MAX_RULES_PER_FILE) throw PackException("rule_count_large", "$name 规则数量超过限制")
        }
        return RuleInfo(sha256(bytes), rules, bytes.size.toLong())
    }

    private fun safeRuleSyntax(name: String, raw: String): Boolean = when (name) {
        "deep.rules" -> {
            val path = raw.substringBefore('|').substringBefore('#').trim()
            path.startsWith("/") && path.length in 2..MAX_RULE_LINE_LENGTH &&
                path.split('/').none { it == ".." } &&
                FORBIDDEN_RULE_ROOTS.none { path == it || path.startsWith("$it/") } &&
                path.split('/').filter { it.isNotEmpty() }.take(2).none { it == "*" || it == "**" || it == "?" }
        }
        "app.rules", "external.rules" -> {
            val parts = raw.split('|')
            val relative = parts.getOrNull(1)?.trim().orEmpty()
            parts.size == 3 && PACKAGE_NAME.matches(parts[0].trim()) && relative.isNotEmpty() &&
                !relative.startsWith('/') && !relative.startsWith('\\') && '\u0000' !in relative && '\\' !in relative &&
                relative.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
                parts[2].trim().toIntOrNull()?.let { it in 0..3650 } == true
        }
        "hidden.rules" -> {
            val parts = raw.split('|')
            val kind = parts.getOrNull(0)?.trim().orEmpty()
            val pattern = parts.getOrNull(1)?.trim().orEmpty()
            parts.size == 3 && kind in setOf("dir", "file") && pattern.isNotEmpty() && pattern.length <= 128 &&
                '/' !in pattern && '\\' !in pattern && '\u0000' !in pattern && pattern !in setOf(".", "..") &&
                (kind == "file" || pattern.none { it == '*' || it == '?' || it == '[' || it == ']' }) &&
                parts[2].trim().toIntOrNull()?.let { it in 0..3650 } == true
        }
        else -> false
    }

    private fun currentInfo(): JSONObject {
        val metadata = readJson(currentMetadataFile())
        val files = currentRuleFiles()
        val fileArray = JSONArray()
        files.toSortedMap().forEach { (name, info) ->
            fileArray.put(
                JSONObject()
                    .put("name", name)
                    .put("sha256", info.sha256)
                    .put("rules", info.rules)
                    .put("bytes", info.bytes)
            )
        }
        return JSONObject()
            .put("packId", metadata.optString("packId", "baize-bundled"))
            .put("version", metadata.optString("version", "bundled"))
            .put("versionCode", metadata.optLong("versionCode", 0L))
            .put("installedAt", metadata.optLong("installedAt", 0L))
            .put("source", metadata.optString("source", "module"))
            .put("signerSha256", metadata.optString("signerSha256"))
            .put("digest", digestRuleSet(files))
            .put("totalRules", files.values.sumOf { it.rules })
            .put("totalBytes", files.values.sumOf { it.bytes })
            .put("files", fileArray)
    }

    private fun currentRuleFiles(): LinkedHashMap<String, RuleInfo> {
        val result = LinkedHashMap<String, RuleInfo>()
        MANAGED_RULES.sorted().forEach { name ->
            val file = File(rulesDirectory(), name)
            if (file.isFile && file.length() <= MAX_RULE_FILE_BYTES) {
                runCatching { fileInfo(file.readBytes()) }.getOrNull()?.let { result[name] = it }
            }
        }
        return result
    }

    private fun fileInfo(bytes: ByteArray): RuleInfo {
        val rules = bytes.toString(Charsets.UTF_8).lineSequence().count { raw ->
            val line = raw.trim()
            line.isNotBlank() && !line.startsWith("#") && !line.startsWith("//")
        }
        return RuleInfo(sha256(bytes), rules, bytes.size.toLong())
    }

    private fun diffFiles(current: Map<String, RuleInfo>, incoming: Map<String, RuleInfo>): JSONArray {
        val array = JSONArray()
        MANAGED_RULES.sorted().forEach { name ->
            val before = current[name]
            val after = incoming[name]
            val status = when {
                before == null && after != null -> "added"
                before != null && after == null -> "removed"
                before?.sha256 == after?.sha256 -> "unchanged"
                else -> "changed"
            }
            array.put(
                JSONObject()
                    .put("name", name)
                    .put("status", status)
                    .put("currentRules", before?.rules ?: 0)
                    .put("incomingRules", after?.rules ?: 0)
                    .put("ruleDelta", (after?.rules ?: 0) - (before?.rules ?: 0))
                    .put("currentBytes", before?.bytes ?: 0L)
                    .put("incomingBytes", after?.bytes ?: 0L)
                    .put("currentSha256", before?.sha256.orEmpty())
                    .put("incomingSha256", after?.sha256.orEmpty())
            )
        }
        return array
    }

    private fun digestRuleSet(files: Map<String, RuleInfo>): String {
        val canonical = files.toSortedMap().entries.joinToString("\n") { (name, info) ->
            "$name:${info.sha256}:${info.rules}:${info.bytes}"
        }
        return sha256(canonical.toByteArray(Charsets.UTF_8))
    }

    private fun backupCurrent(reason: String): File? {
        val files = currentRuleFiles()
        val metadata = currentMetadataFile()
        if (files.isEmpty() && !metadata.isFile) return null
        val id = "%013d-%s".format(System.currentTimeMillis(), UUID.randomUUID().toString().take(8))
        val directory = File(backupRoot(), id).apply { mkdirs() }
        val rules = File(directory, "rules").apply { mkdirs() }
        files.keys.forEach { name -> atomicCopy(File(rulesDirectory(), name), File(rules, name)) }
        if (metadata.isFile) atomicCopy(metadata, File(directory, "current.json"))
        atomicWrite(
            File(directory, "backup.json"),
            JSONObject()
                .put("createdAt", System.currentTimeMillis())
                .put("reason", reason)
                .put("current", currentInfo())
                .toString()
                .toByteArray(Charsets.UTF_8)
        )
        return directory
    }

    private fun applyRuleSet(stagedRules: File, incomingNames: Set<String>) {
        rulesDirectory().mkdirs()
        MANAGED_RULES.forEach { name ->
            val target = File(rulesDirectory(), name)
            if (name in incomingNames) {
                val source = File(stagedRules, name)
                if (!source.isFile) throw PackException("staged_rule_missing", "待安装规则缺失：$name")
                atomicCopy(source, target)
            } else {
                target.delete()
            }
        }
    }

    private fun validPreview(raw: String): File {
        val id = runCatching { UUID.fromString(raw).toString() }.getOrNull()
            ?: throw PackException("preview_invalid", "规则包预览编号无效")
        val directory = File(previewRoot(), id)
        val preview = readJson(File(directory, "preview.json"))
        val createdAt = preview.optLong("createdAt", 0L)
        if (!directory.isDirectory || createdAt <= 0L || System.currentTimeMillis() - createdAt !in 0..PREVIEW_TTL_MS) {
            deleteTree(directory)
            throw PackException("preview_expired", "规则包预览已过期，请重新导入")
        }
        return directory
    }

    private fun latestBackup(): File? = backupRoot().listFiles()
        ?.filter { it.isDirectory && File(it, "backup.json").isFile }
        ?.maxByOrNull { it.name }

    private fun pruneState() {
        val now = System.currentTimeMillis()
        previewRoot().listFiles()?.filter { it.isDirectory }?.forEach { directory ->
            val created = readJson(File(directory, "preview.json")).optLong("createdAt", 0L)
            if (created <= 0L || now - created > PREVIEW_TTL_MS) deleteTree(directory)
        }
        pruneBackups()
    }

    private fun pruneBackups() {
        backupRoot().listFiles()
            ?.filter { it.isDirectory && File(it, "backup.json").isFile }
            ?.sortedByDescending { it.name }
            ?.drop(MAX_BACKUPS)
            ?.forEach(::deleteTree)
    }

    private fun appendHistory(entry: JSONObject) {
        managerRoot().mkdirs()
        val lines = historyFile().takeIf { it.isFile }?.readLines().orEmpty().takeLast(MAX_HISTORY - 1)
        atomicWrite(
            historyFile(),
            (lines + entry.toString()).joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
        )
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

    private fun cleanerBusy(): Boolean {
        val file = File(RootPaths.STATE_DIR, "running.env")
        return file.isFile && file.length() > 0L && file.readText().isNotBlank()
    }

    private fun isAllowedRuleEntry(name: String): Boolean =
        name.startsWith("rules/") && name.substringAfter("rules/") in MANAGED_RULES && name.count { it == '/' } == 1

    private fun readLimited(input: InputStream, maxBytes: Long): ByteArray = input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) throw PackException("entry_too_large", "规则包文件解压后超过限制")
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }

    private fun atomicCopy(source: File, target: File) {
        if (!source.isFile) throw PackException("copy_source_missing", "规则文件源不存在：${source.name}")
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        FileInputStream(source).use { input ->
            FileOutputStream(temporary).use { output ->
                input.copyTo(output, 64 * 1024)
                output.fd.sync()
            }
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        restrict(target)
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        restrict(target)
    }

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

    private fun readJson(file: File): JSONObject = if (!file.isFile) JSONObject() else
        runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    private fun sha256(file: File): String = FileInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun failure(code: String, message: String): String = JSONObject()
        .put("success", false)
        .put("error", code)
        .put("message", message)
        .toString()

    private fun rulesDirectory(): File = File(RootPaths.MODULE_DIR, "config").apply { mkdirs() }
    private fun managerRoot(): File = File(RootPaths.STATE_DIR, "rule-pack-manager").apply { mkdirs() }
    private fun previewRoot(): File = File(managerRoot(), "previews").apply { mkdirs() }
    private fun backupRoot(): File = File(managerRoot(), "backups").apply { mkdirs() }
    private fun currentMetadataFile(): File = File(managerRoot(), "current.json")
    private fun historyFile(): File = File(managerRoot(), "history.ndjson")

    companion object {
        private const val PACK_SCHEMA = 1
        private const val MANIFEST_NAME = "rule-pack.json"
        private const val PREVIEW_TTL_MS = 30L * 60_000L
        private const val MAX_PACKAGE_BYTES = 32L * 1024L * 1024L
        private const val MAX_EXPANDED_BYTES = 48L * 1024L * 1024L
        private const val MAX_ENTRY_BYTES = 16L * 1024L * 1024L
        private const val MAX_RULE_FILE_BYTES = 16L * 1024L * 1024L
        private const val MAX_RULES_PER_FILE = 20_000
        private const val MAX_RULE_LINE_LENGTH = 4096
        private const val MAX_BACKUPS = 3
        private const val MAX_HISTORY = 30
        private val MANAGED_RULES = setOf("app.rules", "external.rules", "hidden.rules", "deep.rules")
        private val PACK_ID = Regex("^[A-Za-z0-9._-]{1,80}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
        private val FORBIDDEN_RULE_ROOTS = setOf(
            "/", "/data", "/data/adb", "/metadata", "/proc", "/sys", "/dev",
            "/system", "/vendor", "/product", "/odm", "/apex"
        )
    }
}
