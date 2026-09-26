package io.github.xgl34222220.baize.root

/** Specialize package slots before glob expansion, avoiding unrelated app directory walks. */
internal object TargetedRulePaths {
    fun select(pattern: String, packages: Set<String>): List<String> {
        if (packages.isEmpty()) return listOf(pattern)
        val segments = pattern.split('/').toMutableList()
        val owner = when {
            segments.size > 4 && segments[1] == "data" && segments[2] in setOf("user", "user_de") -> 4
            segments.size > 3 && segments[1] == "data" && segments[2] == "data" -> 3
            else -> segments.indices.firstOrNull { i ->
                i + 2 < segments.size && segments[i] == "Android" && segments[i + 1] in setOf("data", "media", "obb")
            }?.plus(2) ?: return emptyList()
        }
        val slot = segments[owner]
        // Match a package segment only. Patterns elsewhere (user, WebView profile, filename)
        // retain their normal meaning in the existing expansion and mutation policy.
        val regex = Regex("^" + slot.map { when (it) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(it.toString()) } }.joinToString("") + "$")
        return packages.sorted().filter { regex.matches(it) }.map { pkg ->
            segments.toMutableList().apply { this[owner] = pkg }.joinToString("/")
        }
    }
}
