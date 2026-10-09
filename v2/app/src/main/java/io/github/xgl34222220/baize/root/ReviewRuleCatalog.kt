package io.github.xgl34222220.baize.root

import java.io.File

/** Shared interpretation of the module's rule files for the selectable scanner. */
internal object ReviewRuleCatalog {
    data class Roots(
        val data: String = "/data",
        val externalData: List<String> = listOf("/storage/emulated/*/Android/data", "/data/media/*/Android/data")
    )

    data class Target(val pattern: String, val label: String, val risk: String? = null, val days: Int = 0, val packageRelative: String = "", val fileOnly: Boolean = false)
    data class Hidden(val directory: Boolean, val name: String, val days: Int) {
        fun matches(value: String): Boolean = if (name == "._*") value.startsWith("._") else value == name
    }

    private val packageName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")
    private val metadataNames = setOf(".DS_Store", "._*", "Thumbs.db", "desktop.ini", ".directory")
    private val protectedNames = setOf(".git", ".ssh", ".gnupg", ".termux", ".config", ".local", ".obsidian", ".android", ".vscode", ".baize-quarantine", ".baize-file-trash")

    fun packageRules(file: File?, external: Boolean, roots: Roots = Roots()): List<Target> = lines(file).flatMap { line ->
        val fields = line.split('|').map(String::trim)
        if (fields.size != 3) return@flatMap emptyList()
        val (pkg, relative, age) = fields
        val days = age.toIntOrNull()?.takeIf { it in 0..365 } ?: return@flatMap emptyList()
        if (!packageName.matches(pkg) || !safeRelative(relative)) return@flatMap emptyList()
        val bases = if (external) roots.externalData.map { "$it/$pkg" }
            else listOf("${roots.data}/user/*/$pkg", "${roots.data}/user_de/*/$pkg", "${roots.data}/data/$pkg")
        bases.map { Target("$it/$relative", if (external) "外部应用缓存与日志" else "应用缓存与日志", "medium", days, relative) }
    }.distinctBy { it.pattern }

    /** Per-app profile selection read from the module configuration (module defaults when absent). */
    data class AppProfile(val enabled: Boolean = true, val tier: Int = 1, val userMedia: Boolean = false) {
        val effectiveTier: Int get() = tier.coerceIn(0, 2)
        val effectiveUserMedia: Boolean get() = userMedia && effectiveTier == 2

        companion object {
            fun read(config: File?): AppProfile {
                val values = HashMap<String, String>()
                runCatching {
                    config?.takeIf { it.isFile && it.length() <= 256 * 1024 }?.forEachLine { raw ->
                        val line = raw.trim()
                        if (line.startsWith("#") || !line.contains('=')) return@forEachLine
                        values[line.substringBefore('=').trim()] = line.substringAfter('=').trim()
                    }
                }
                return AppProfile(
                    enabled = values["app_profile_enabled"] != "0",
                    tier = values["app_profile_tier"]?.toIntOrNull()?.coerceIn(0, 2) ?: 1,
                    userMedia = values["app_profile_user_media"] == "1"
                )
            }
        }
    }

    private val profileRank = mapOf("conservative" to 0, "standard" to 1, "enhanced" to 2, "enhanced-media" to 3)
    private val profileDenied = setOf(
        "databases", "shared_prefs", "no_backup", "mmkv", "datastore", "download", "downloads", "weixin",
        "qqfile_recv", "filerecv", "chatpic", "shortvideo", "ptt", "voice2", "image2", "video2", "emoji",
        "favorite", "favorites", "draft", "drafts", "documents", "dcim", "pictures", "movies", "music"
    )
    private val accountDirectory = Regex("[0-9a-f]{32}")
    private val databaseFile = Regex(".*\\.db(-wal|-shm|-journal)?")

    /** Same guard as app-profile-rules.sh: only enhanced-media lines may touch chat or user data. */
    fun profileTouchesUserData(relative: String): Boolean {
        val parts = relative.lowercase().split('/')
        return parts.withIndex().any { (index, part) ->
            part in profileDenied || databaseFile.matches(part) || accountDirectory.matches(part) ||
                (index + 1 < parts.size && "$part/${parts[index + 1]}" == "files/mmkv")
        }
    }

    /**
     * Per-app tiered rules (config/app-profiles.rules). Tiers are cumulative; enhanced-media
     * entries are used only with tier 2 *and* the explicit user-media switch, never shorter than
     * 7 days, and are marked high risk so the workbench never pre-selects them.
     */
    fun profileRules(file: File?, profile: AppProfile, roots: Roots = Roots()): List<Target> {
        if (!profile.enabled) return emptyList()
        val tier = profile.effectiveTier
        val chosen = LinkedHashMap<Triple<String, String, String>, Pair<Int, Boolean>>()
        for (line in lines(file)) {
            val fields = line.split('|').map(String::trim)
            if (fields.size != 5) continue
            val rank = profileRank[fields[0]] ?: continue
            val scope = fields[1]
            val pkg = fields[2]
            val relative = fields[3]
            val days = fields[4].toIntOrNull()?.takeIf { it in 0..365 } ?: continue
            if (scope != "data" && scope != "ext") continue
            if (!packageName.matches(pkg) || !safeRelative(relative)) continue
            val media = rank == 3
            if (media) {
                if (!profile.effectiveUserMedia || days < 7) continue
            } else if (rank > tier || profileTouchesUserData(relative)) continue
            val key = Triple(scope, pkg, relative)
            val previous = chosen[key]
            chosen[key] = if (previous == null) days to media else minOf(previous.first, days) to (previous.second || media)
        }
        return chosen.flatMap { (key, value) ->
            val (scope, pkg, relative) = key
            val (days, media) = value
            val external = scope == "ext"
            val bases = if (external) roots.externalData.map { "$it/$pkg" }
                else listOf("${roots.data}/user/*/$pkg", "${roots.data}/user_de/*/$pkg", "${roots.data}/data/$pkg")
            val label = if (media) "应用专项媒体（增强）" else "应用专项缓存"
            bases.map { Target("$it/$relative", label, if (media) "high" else "medium", days, relative) }
        }.distinctBy { it.pattern }
    }

    fun hiddenRules(file: File?): List<Hidden> = lines(file).mapNotNull { line ->
        val fields = line.split('|').map(String::trim)
        if (fields.size != 3) return@mapNotNull null
        val (kind, name, age) = fields
        val days = age.toIntOrNull()?.takeIf { it in 0..365 } ?: return@mapNotNull null
        if (name.isBlank() || name.contains('/') || name in setOf(".", "..") || name.any(Char::isISOControl)) return@mapNotNull null
        when (kind) {
            "dir" -> if (name.startsWith('.') && name.lowercase() !in protectedNames && !name.any { it in "*?[]\\" }) Hidden(true, name, days) else null
            "file" -> if (name in metadataNames) Hidden(false, name, days) else null
            else -> null
        }
    }.distinctBy { it.directory to it.name }

    fun reviewRules(file: File?): List<Target> = lines(file).flatMap { line ->
        val fields = line.split('|').map(String::trim)
        if (fields.size != 2 || !safeAbsolute(fields[0]) || fields[1].isBlank()) return@flatMap emptyList()
        val patterns = mutableListOf(fields[0])
        // Older/vendor Android layouts expose user/0 as a system symlink. Scan the known
        // legacy CE root directly instead of permitting arbitrary application symlinks.
        if (fields[0].startsWith("/data/user/*/")) patterns += "/data/data/" + fields[0].removePrefix("/data/user/*/")
        patterns.map { Target(it, fields[1], "medium") }
    }.distinctBy { it.pattern }

    fun customRules(file: File?): List<Target> = lines(file).mapNotNull { line ->
        val fields = line.split('|').map(String::trim)
        if (fields.size != 2) return@mapNotNull null
        val path = fields[0].trimEnd('/')
        if (!safeAbsolute(path)) return@mapNotNull null
        val days = fields[1].toIntOrNull()?.takeIf { it in 0..365 } ?: return@mapNotNull null
        Target(path, "自定义规则", days = days)
    }.distinctBy { it.pattern }

    fun webViewRules(roots: Roots = Roots()): List<Target> = buildList {
        for (store in listOf("${roots.data}/user/*", "${roots.data}/user_de/*", "${roots.data}/data")) {
            for (engine in listOf("app_webview*", "app_hws_webview*", "app_x5webview*")) {
                for (leaf in listOf("Cache", "GPUCache", "GPU Cache", "Code Cache", "Default/Cache", "Default/GPUCache", "Default/GPU Cache", "Default/Code Cache", "Crashpad/completed")) {
                    add(Target("$store/*/$engine/$leaf", "WebView 缓存", "medium"))
                }
            }
        }
    }

    fun oldEnough(file: File, days: Int, now: Long = System.currentTimeMillis()): Boolean =
        days == 0 || file.lastModified() <= now - days * 86_400_000L

    /** Resolve every ancestor, so .Trash/.cache and .cache/.Trash keep the same retention. */
    fun hiddenMatch(file: File, rules: List<Hidden>, boundary: File? = null): Hidden? {
        var result = rules.firstOrNull { !it.directory && it.matches(file.name) }
        var parent = file.parentFile
        while (parent != null && parent != boundary) {
            val name = parent.name
            val match = rules.firstOrNull { it.directory && it.matches(name) }
            if (match != null && (result == null || match.days > result.days)) result = match
            parent = parent.parentFile
        }
        return result
    }

    private fun lines(file: File?): List<String> = file?.takeIf { it.isFile }?.useLines { lines ->
        lines.map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.take(12_000).toList()
    }.orEmpty()

    private fun safeRelative(value: String): Boolean = value.isNotBlank() && !value.startsWith('/') &&
        value.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
        value.none { it.isISOControl() || it in "*?[]|\\" }

    private fun safeAbsolute(value: String): Boolean = value.startsWith('/') && value.length in 2..4096 &&
        value.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." } &&
        value.none { it.isISOControl() || it == '|' || it == '\\' }
}
