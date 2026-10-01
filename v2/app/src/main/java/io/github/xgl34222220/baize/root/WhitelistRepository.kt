package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

internal class WhitelistRepository(
    private val whitelistFile: File = File(RootPaths.WHITELIST_FILE),
    private val packageFile: File = File(RootPaths.WHITELIST_PACKAGES_FILE),
    private val userRoots: List<File> = listOf("/data/user", "/data/user_de", "/data/media").map(::File)
) {
    fun packagesJson(): String = synchronized(LOCK) { JSONArray(readPackages().sorted()).toString() }

    fun pathsJson(): String = synchronized(LOCK) { JSONArray(readManualPaths().sorted()).toString() }

    fun apkProtectionJson(): String = synchronized(LOCK) {
        JSONObject().put("version", 1).put("packages", JSONArray(readPackages().sorted()))
            .put("paths", JSONArray(readManualPaths().sorted())).toString()
    }

    fun savePackages(raw: String): String = synchronized(LOCK) {
        val array = runCatching { JSONArray(raw) }.getOrElse {
            return@synchronized JSONObject().put("error", "invalid_json").put("message", "白名单格式无效").toString()
        }
        val packages = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val packageName = array.optString(index).trim()
            if (!RootValidation.packageName.matches(packageName)) {
                return@synchronized JSONObject().put("error", "invalid_package").put("package", packageName).toString()
            }
            packages += packageName
            if (packages.size > 500) {
                return@synchronized JSONObject().put("error", "too_many_packages").put("limit", 500).toString()
            }
        }

        val manualPaths = readManualPaths()
        val sidecar = packages.sorted().joinToString("\n", postfix = if (packages.isEmpty()) "" else "\n")
        RootFileStore.writeAtomic(packageFile, sidecar, worldReadable = true)
        writeWhitelistFile(packages, manualPaths)
        return@synchronized JSONObject()
            .put("success", true)
            .put("count", packages.size)
            .put("message", "应用白名单已写入清理引擎")
            .toString()
    }

    fun addPath(raw: String?): String = synchronized(LOCK) {
        val path = normalizeManualPath(raw.orEmpty())
            ?: return@synchronized JSONObject().put("error", "invalid_path").put("message", "只能保护规范的绝对路径").toString()
        val manual = readManualPaths().toMutableSet()
        val added = manual.add(path)
        if (manual.size > MAX_MANUAL_PATHS) {
            return@synchronized JSONObject().put("error", "too_many_paths").put("limit", MAX_MANUAL_PATHS).toString()
        }
        writeWhitelistFile(readPackages(), manual)
        return@synchronized JSONObject()
            .put("success", true)
            .put("added", added)
            .put("path", path)
            .put("count", manual.size)
            .put("message", if (added) "路径已加入保护白名单" else "路径已在保护白名单中")
            .toString()
    }

    /** Apply only the user's additions/removals under the same lock as all other writers. */
    fun updatePackages(addedRaw: String, removedRaw: String): String = synchronized(LOCK) {
        val added = parsePackageDelta(addedRaw) ?: return@synchronized failure("invalid_packages", "新增应用名单无效")
        val removed = parsePackageDelta(removedRaw) ?: return@synchronized failure("invalid_packages", "取消保护名单无效")
        if (added.intersect(removed).isNotEmpty()) return@synchronized failure("conflicting_delta", "同一应用不能同时添加和移除")
        val packages = (readPackages() - removed) + added
        savePackages(JSONArray(packages.sorted()).toString())
    }

    /** Remove this exact whitelist record, never a file, parent path, or another rule. */
    fun removePath(raw: String?): String = synchronized(LOCK) {
        val path = normalizeManualPath(raw.orEmpty()) ?: return@synchronized failure("invalid_path", "路径格式无效")
        val manual = readManualPaths().toMutableSet()
        val removed = manual.remove(path)
        if (removed) writeWhitelistFile(readPackages(), manual)
        JSONObject().put("success", true).put("removed", removed).put("path", path)
            .put("count", manual.size).put("message", if (removed)
                "已取消此路径保护；其它白名单仍生效，重新扫描后可核对。" else "此路径已不在手动白名单中")
            .toString()
    }

    private fun parsePackageDelta(raw: String): Set<String>? = runCatching {
        val array = JSONArray(raw)
        require(array.length() <= 500)
        (0 until array.length()).map { index -> array.getString(index).trim().also {
            require(RootValidation.packageName.matches(it))
        } }.toSet()
    }.getOrNull()

    private fun failure(code: String, message: String): String = JSONObject()
        .put("success", false).put("error", code).put("message", message).toString()

    private fun readPackages(): Set<String> {
        val sidecar = readRulesFile(packageFile)
        if (sidecar != null) {
            return sidecar
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .onEach { require(RootValidation.packageName.matches(it)) { "应用保护名单损坏" } }
                .toSet()
        }

        val inferred = linkedSetOf<String>()
        val lines = readRulesFile(whitelistFile).orEmpty()
        val managed = lines.any { it.trim() == APP_WHITELIST_BEGIN }
        var generated = false
        lines.forEach { raw ->
            val line = raw.trim()
            if (line == APP_WHITELIST_BEGIN) { generated = true; return@forEach }
            if (line == APP_WHITELIST_END) { generated = false; return@forEach }
            if (managed && !generated) return@forEach
            for (pattern in GENERATED_PATH_PATTERNS) {
                val packageName = pattern.matchEntire(line)?.groupValues?.getOrNull(1)
                if (!packageName.isNullOrBlank()) inferred += packageName
            }
        }
        return inferred
    }

    private fun readManualPaths(): Set<String> {
        val result = linkedSetOf<String>()
        var generatedSection = false
        // In a managed file, everything outside the generated block is a manual
        // entry, even if it happens to equal an application's root directory.
        val lines = readRulesFile(whitelistFile).orEmpty()
        val managed = lines.any { it.trim() == APP_WHITELIST_BEGIN }
        lines.forEach { raw ->
            val line = raw.trim()
            when (line) {
                APP_WHITELIST_BEGIN -> { check(!generatedSection) { "保护名单分区损坏" }; generatedSection = true }
                APP_WHITELIST_END -> { check(generatedSection) { "保护名单分区损坏" }; generatedSection = false }
                else -> if (line.startsWith('/')) {
                    val path = requireNotNull(normalizeManualPath(line)) { "路径保护名单损坏" }
                    if (generatedSection) {
                        check(isGeneratedAppPath(path)) { "自动保护分区包含未知路径，请在白名单页核对" }
                    } else if (managed || !isGeneratedAppPath(path)) {
                        result += path
                    }
                } else if (line.isNotBlank() && !line.startsWith('#')) {
                    error("路径保护名单损坏")
                }
            }
        }
        check(!generatedSection) { "保护名单分区未结束" }
        return result
    }

    /** Only confirmed absence means empty. Permission errors, directories and links are not lists. */
    private fun readRulesFile(file: File): List<String>? {
        val attributes = try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) { return null }
        check(attributes.isRegularFile && !attributes.isSymbolicLink && attributes.size() <= 4 * 1024 * 1024) {
            "保护名单不是可读取的普通文件"
        }
        return file.readLines().also { lines ->
            check(lines.size <= 10_000 && lines.none { it.contains('\u0000') }) { "保护名单内容无效" }
        }
    }

    private fun writeWhitelistFile(packages: Set<String>, manualPaths: Set<String>) {
        val users = linkedSetOf("0")
        userRoots.forEach { root ->
            root.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory && it.name.all(Char::isDigit) }
                ?.mapTo(users) { it.name }
        }

        val output = buildString {
            append("# 白泽清理保护白名单。自定义绝对路径可继续逐行添加。\n")
            manualPaths.distinct().sorted().forEach { append(it).append('\n') }
            if (manualPaths.isNotEmpty()) append('\n')
            append(APP_WHITELIST_BEGIN).append('\n')
            for (packageName in packages.sorted()) {
                append("# app:").append(packageName).append('\n')
                for (user in users.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }) {
                    append("/data/user/").append(user).append('/').append(packageName).append('\n')
                    append("/data/user_de/").append(user).append('/').append(packageName).append('\n')
                    append("/data/media/").append(user).append("/Android/data/").append(packageName).append('\n')
                }
            }
            append(APP_WHITELIST_END).append('\n')
        }
        RootFileStore.writeAtomic(whitelistFile, output, worldReadable = true)
    }

    private fun normalizeManualPath(raw: String): String? {
        if (raw.any { it == '\u0000' || it == '\n' || it == '\r' }) return null
        val value = raw.trim().replace(Regex("/+"), "/")
        if (!value.startsWith('/') || value.length > 4_096 || value.contains('\u0000') || value.contains('\n') || value.contains('\r')) return null
        val components = value.split('/').filter { it.isNotEmpty() }
        if (components.any { it == "." || it == ".." }) return null
        return value.trimEnd('/').ifBlank { "/" }
    }

    private fun isGeneratedAppPath(path: String): Boolean =
        GENERATED_PATH_PATTERNS.any { it.matches(path.trimEnd('/')) }

    companion object {
        private val LOCK = Any()
        private const val APP_WHITELIST_BEGIN = "# BEGIN BAIZE APP WHITELIST"
        private const val APP_WHITELIST_END = "# END BAIZE APP WHITELIST"
        private const val MAX_MANUAL_PATHS = 500
        private val GENERATED_PATH_PATTERNS = listOf(
            Regex("""^/data/(?:user|user_de)/\d+/([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)$"""),
            Regex("""^/data/media/\d+/Android/data/([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)$""")
        )
    }
}
