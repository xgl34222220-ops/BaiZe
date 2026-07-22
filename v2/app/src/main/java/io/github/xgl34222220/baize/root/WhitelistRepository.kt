package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class WhitelistRepository {
    fun packagesJson(): String = JSONArray(readPackages().sorted()).toString()

    fun savePackages(raw: String): String {
        val array = runCatching { JSONArray(raw) }.getOrElse {
            return JSONObject().put("error", "invalid_json").put("message", "白名单格式无效").toString()
        }
        val packages = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val packageName = array.optString(index).trim()
            if (!RootValidation.packageName.matches(packageName)) {
                return JSONObject().put("error", "invalid_package").put("package", packageName).toString()
            }
            packages += packageName
            if (packages.size > 500) {
                return JSONObject().put("error", "too_many_packages").put("limit", 500).toString()
            }
        }

        File(RootPaths.STATE_DIR).mkdirs()
        val sidecar = packages.sorted().joinToString("\n", postfix = if (packages.isEmpty()) "" else "\n")
        RootFileStore.writeAtomic(File(RootPaths.WHITELIST_PACKAGES_FILE), sidecar, worldReadable = true)
        rebuildWhitelistFile(packages)
        return JSONObject()
            .put("success", true)
            .put("count", packages.size)
            .put("message", "应用白名单已写入清理引擎")
            .toString()
    }

    private fun readPackages(): Set<String> {
        val sidecar = File(RootPaths.WHITELIST_PACKAGES_FILE)
        if (sidecar.isFile) {
            return sidecar.readLines()
                .asSequence()
                .map { it.trim() }
                .filter { RootValidation.packageName.matches(it) }
                .toSet()
        }

        val inferred = linkedSetOf<String>()
        File(RootPaths.WHITELIST_FILE).takeIf { it.isFile }?.forEachLine { raw ->
            val line = raw.trim()
            for (pattern in GENERATED_PATH_PATTERNS) {
                val packageName = pattern.matchEntire(line)?.groupValues?.getOrNull(1)
                if (!packageName.isNullOrBlank()) inferred += packageName
            }
        }
        return inferred
    }

    private fun rebuildWhitelistFile(packages: Set<String>) {
        val file = File(RootPaths.WHITELIST_FILE)
        val manual = mutableListOf<String>()
        var generatedSection = false
        if (file.isFile) {
            file.forEachLine { raw ->
                val line = raw.trim()
                when (line) {
                    APP_WHITELIST_BEGIN -> generatedSection = true
                    APP_WHITELIST_END -> generatedSection = false
                    else -> if (!generatedSection && line.startsWith("/") && !isGeneratedAppPath(line)) {
                        manual += line
                    }
                }
            }
        }

        val users = linkedSetOf("0")
        listOf("/data/user", "/data/user_de", "/data/media").forEach { root ->
            File(root).listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory && it.name.all(Char::isDigit) }
                ?.mapTo(users) { it.name }
        }

        val output = buildString {
            append("# 白泽清理保护白名单。自定义绝对路径可继续逐行添加。\n")
            manual.distinct().sorted().forEach { append(it).append('\n') }
            if (manual.isNotEmpty()) append('\n')
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
        RootFileStore.writeAtomic(file, output, worldReadable = true)
    }

    private fun isGeneratedAppPath(path: String): Boolean =
        GENERATED_PATH_PATTERNS.any { it.matches(path.trimEnd('/')) }

    companion object {
        private const val APP_WHITELIST_BEGIN = "# BEGIN BAIZE APP WHITELIST"
        private const val APP_WHITELIST_END = "# END BAIZE APP WHITELIST"
        private val GENERATED_PATH_PATTERNS = listOf(
            Regex("""^/data/(?:user|user_de)/\d+/([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)$"""),
            Regex("""^/data/media/\d+/Android/data/([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)$""")
        )
    }
}
