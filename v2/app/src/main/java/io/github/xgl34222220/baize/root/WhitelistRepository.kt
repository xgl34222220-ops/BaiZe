package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Typed source of truth for user exclusions; legacy files are compiled outputs only. */
internal class WhitelistRepository {
    fun packagesJson(): String = JSONArray(activeRecords().asSequence()
        .filter { it.optString("type") == "package" }.map { it.optString("value") }
        .filter { it.isNotBlank() }.distinct().sorted().toList()).toString()

    fun pathsJson(): String = JSONArray(activeRecords().asSequence()
        .filter { it.optString("type") == "path" }.map { it.optString("value") }
        .filter { it.isNotBlank() }.distinct().sorted().toList()).toString()

    fun exclusionsJson(): String = JSONObject().put("schema", SCHEMA)
        .put("records", JSONArray(loadRecords())).put("scopes", JSONArray(SCOPES.sorted())).toString()

    @Synchronized fun savePackages(raw: String): String {
        val array = runCatching { JSONArray(raw) }.getOrElse { return error("invalid_json", "白名单格式无效") }
        val packages = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val name = array.optString(index).trim()
            if (!RootValidation.packageName.matches(name)) return error("invalid_package", "无效应用包名", "package", name)
            packages += name
            if (packages.size > MAX_RECORDS) return error("too_many_packages", "应用保护项过多")
        }
        val records = loadRecords().filterNot {
            it.optString("type") == "package" && it.optString("source") == SOURCE_PACKAGE_UI
        }.toMutableList()
        packages.sorted().forEach { records += makeRecord("package", it, SCOPES, -1, "应用保护列表", SOURCE_PACKAGE_UI) }
        saveAndCompile(records)
        return JSONObject().put("success", true).put("count", packages.size).put("message", "应用排除规则已写入清理引擎").toString()
    }

    @Synchronized fun addPath(raw: String?): String {
        val path = normalizeManualPath(raw.orEmpty()) ?: return error("invalid_path", "只能保护非关键根目录下的规范绝对路径")
        val records = loadRecords().toMutableList()
        val duplicate = records.any { it.optString("type") == "path" && it.optString("value") == path && it.optBoolean("enabled", true) }
        if (!duplicate) records += makeRecord("path", path, SCOPES, -1, "用户手动保护", SOURCE_PATH_UI)
        if (records.size > MAX_RECORDS) return error("too_many_paths", "保护路径过多")
        saveAndCompile(records)
        return JSONObject().put("success", true).put("added", !duplicate).put("path", path)
            .put("count", records.count { it.optString("type") == "path" }).toString()
    }

    @Synchronized fun addExclusion(raw: String): String {
        val input = runCatching { JSONObject(raw) }.getOrElse { return error("invalid_json", "排除规则格式无效") }
        val type = input.optString("type").trim()
        val value = when (type) {
            "path" -> normalizeManualPath(input.optString("value"))
            "package" -> input.optString("value").trim().takeIf(RootValidation.packageName::matches)
            else -> null
        } ?: return error("invalid_value", "排除对象无效")
        val scopes = parseScopes(input.optJSONArray("scopes")) ?: return error("invalid_scope", "排除作用域无效")
        val userId = input.optInt("userId", -1).takeIf { it >= -1 } ?: return error("invalid_user", "用户编号无效")
        val requestedMatch = input.optString("matchMode", if (type == "path") "subtree" else "exact")
        if ((type == "path" && requestedMatch != "subtree") || (type == "package" && requestedMatch != "exact")) {
            return error("unsupported_match_mode", "当前对象不支持该匹配方式")
        }
        val expiresAt = input.optLong("expiresAt", 0L).coerceAtLeast(0L)
        if (expiresAt in 1..System.currentTimeMillis()) return error("expired", "过期时间必须在未来")
        val record = makeRecord(type, value, scopes, userId,
            input.optString("reason", "用户自定义保护").take(256),
            input.optString("source", "custom").take(64), expiresAt)
        val records = loadRecords().filterNot { it.optString("id") == record.optString("id") }.toMutableList()
        records += record
        if (records.size > MAX_RECORDS) return error("too_many_records", "排除规则过多")
        saveAndCompile(records)
        return JSONObject().put("success", true).put("record", record).toString()
    }

    @Synchronized fun removeExclusion(rawId: String): String {
        val id = rawId.trim()
        if (!ID_PATTERN.matches(id)) return error("invalid_id", "排除规则编号无效")
        val records = loadRecords().toMutableList()
        val target = records.firstOrNull { it.optString("id") == id } ?: return error("not_found", "排除规则不存在")
        if (target.optBoolean("builtIn")) return error("immutable", "内置安全保护不可删除")
        records.remove(target)
        saveAndCompile(records)
        return JSONObject().put("success", true).put("removed", id).toString()
    }

    private fun saveAndCompile(records: List<JSONObject>) {
        File(RootPaths.STATE_DIR).mkdirs()
        val normalized = records.sortedBy { it.optString("id") }
        RootFileStore.writeAtomic(File(RootPaths.EXCLUSIONS_FILE), JSONObject().put("schema", SCHEMA)
            .put("updatedAt", System.currentTimeMillis()).put("records", JSONArray(normalized)).toString(2) + "\n")
        compileLegacy(normalized)
    }

    private fun compileLegacy(records: List<JSONObject>) {
        val active = records.filter(::isActive)
        val packages = active.filter { it.optString("type") == "package" }.map { it.optString("value") }.distinct().sorted()
        RootFileStore.writeAtomic(File(RootPaths.WHITELIST_PACKAGES_FILE), packages.joinToString("\n", postfix = if (packages.isEmpty()) "" else "\n"), true)
        writeScopeFile(File(RootPaths.WHITELIST_FILE), active)
        SCOPES.forEach { scope ->
            writeScopeFile(File(RootPaths.STATE_DIR, "whitelist.$scope.conf"), active.filter { recordScopes(it).contains(scope) })
        }
    }

    private fun writeScopeFile(file: File, records: List<JSONObject>) {
        val users = discoverUsers()
        val output = buildString {
            append("# Generated from exclusions.json; edit through BaiZe only.\n")
            records.filter { it.optString("type") == "path" }.map { it.optString("value") }.distinct().sorted()
                .forEach { append(it).append('\n') }
            records.filter { it.optString("type") == "package" }.sortedBy { it.optString("value") }.forEach { record ->
                val name = record.optString("value")
                val targets = record.optInt("userId", -1).let { if (it >= 0) setOf(it.toString()) else users }
                targets.forEach { user ->
                    append("/data/user/").append(user).append('/').append(name).append('\n')
                    append("/data/user_de/").append(user).append('/').append(name).append('\n')
                    append("/data/media/").append(user).append("/Android/data/").append(name).append('\n')
                    append("/data/media/").append(user).append("/Android/obb/").append(name).append('\n')
                }
            }
        }
        RootFileStore.writeAtomic(file, output, true)
    }

    private fun loadRecords(): List<JSONObject> {
        val file = File(RootPaths.EXCLUSIONS_FILE)
        if (file.isFile) {
            return runCatching {
                val root = JSONObject(file.readText())
                if (root.optInt("schema") != SCHEMA) emptyList() else {
                    val array = root.optJSONArray("records") ?: JSONArray()
                    (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                        .filter { validateStored(it) }.take(MAX_RECORDS)
                }
            }.getOrDefault(emptyList())
        }
        return migrateLegacy()
    }

    private fun migrateLegacy(): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        File(RootPaths.WHITELIST_PACKAGES_FILE).takeIf(File::isFile)?.readLines()?.map { it.trim() }
            ?.filter(RootValidation.packageName::matches)?.distinct()?.forEach {
                result += makeRecord("package", it, SCOPES, -1, "从旧版应用白名单迁移", SOURCE_PACKAGE_UI)
            }
        var generated = false
        File(RootPaths.WHITELIST_FILE).takeIf(File::isFile)?.forEachLine { raw ->
            val line = raw.trim()
            when (line) {
                APP_WHITELIST_BEGIN -> generated = true
                APP_WHITELIST_END -> generated = false
                else -> if (!generated && line.startsWith("/") && !isGeneratedAppPath(line)) {
                    normalizeManualPath(line)?.let { result += makeRecord("path", it, SCOPES, -1, "从旧版路径白名单迁移", SOURCE_PATH_UI) }
                }
            }
        }
        return result.distinctBy { it.optString("id") }
    }

    private fun activeRecords(): List<JSONObject> = loadRecords().filter(::isActive)
    private fun isActive(record: JSONObject): Boolean = record.optBoolean("enabled", true) &&
        record.optLong("expiresAt", 0L).let { it <= 0L || it > System.currentTimeMillis() }

    private fun makeRecord(type: String, value: String, scopes: Set<String>, userId: Int, reason: String, source: String,
                           expiresAt: Long = 0L): JSONObject {
        // Legacy native/shell consumers implement ancestor protection, so path semantics are
        // deliberately subtree-only until every mutation engine can enforce exact matching.
        val match = if (type == "path") "subtree" else "exact"
        val id = "ex-" + sha256("$type\u0000$value\u0000$userId\u0000${scopes.sorted().joinToString(",")}\u0000$match").take(24)
        return JSONObject().put("id", id).put("type", type).put("value", value)
            .put("scopes", JSONArray(scopes.sorted())).put("matchMode", match).put("userId", userId)
            .put("reason", reason).put("source", source).put("createdAt", System.currentTimeMillis())
            .put("expiresAt", expiresAt).put("builtIn", false).put("enabled", true)
    }

    private fun validateStored(record: JSONObject): Boolean {
        val type = record.optString("type")
        val value = record.optString("value")
        return ID_PATTERN.matches(record.optString("id")) && when (type) {
            "path" -> normalizeManualPath(value) == value && record.optString("matchMode") == "subtree"
            "package" -> RootValidation.packageName.matches(value) && record.optString("matchMode") == "exact"
            else -> false
        } && parseScopes(record.optJSONArray("scopes")) != null && record.optInt("userId", -1) >= -1
    }

    private fun parseScopes(array: JSONArray?): Set<String>? {
        if (array == null || array.length() == 0) return SCOPES
        val result = (0 until array.length()).map { array.optString(it) }.toSet()
        return result.takeIf { it.isNotEmpty() && SCOPES.containsAll(it) }
    }

    private fun recordScopes(record: JSONObject): Set<String> = parseScopes(record.optJSONArray("scopes")) ?: emptySet()
    private fun discoverUsers(): Set<String> = linkedSetOf("0").apply {
        listOf("/data/user", "/data/user_de", "/data/media").forEach { root ->
            File(root).listFiles()?.filter { it.isDirectory && it.name.all(Char::isDigit) }?.mapTo(this) { it.name }
        }
    }

    private fun normalizeManualPath(raw: String): String? {
        val value = raw.trim().replace(Regex("/+"), "/").trimEnd('/').ifBlank { "/" }
        if (!value.startsWith('/') || value.length > 4096 || value.any { it == '\u0000' || it == '\n' || it == '\r' }) return null
        if (value.split('/').any { it == "." || it == ".." } || CRITICAL_ROOTS.contains(value)) return null
        return value
    }

    private fun isGeneratedAppPath(path: String): Boolean = GENERATED_PATH_PATTERNS.any { it.matches(path.trimEnd('/')) }
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun error(code: String, message: String, key: String? = null, value: String? = null): String =
        JSONObject().put("success", false).put("error", code).put("message", message).apply { if (key != null) put(key, value) }.toString()

    companion object {
        private const val SCHEMA = 1
        private const val MAX_RECORDS = 1000
        private const val SOURCE_PACKAGE_UI = "package-ui"
        private const val SOURCE_PATH_UI = "path-ui"
        private const val APP_WHITELIST_BEGIN = "# BEGIN BAIZE APP WHITELIST"
        private const val APP_WHITELIST_END = "# END BAIZE APP WHITELIST"
        private val SCOPES = setOf("cache", "deep", "corpses", "apk", "organizer", "storage")
        private val CRITICAL_ROOTS = setOf("/", "/data", "/data/user", "/data/user_de", "/data/media", "/storage", "/sdcard")
        private val ID_PATTERN = Regex("^ex-[a-f0-9]{24}$")
        private val GENERATED_PATH_PATTERNS = listOf(
            Regex("""^/data/(?:user|user_de)/\d+/([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)$"""),
            Regex("""^/data/media/\d+/Android/(?:data|obb)/([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)$""")
        )
    }
}
