package io.github.xgl34222220.baize.root

/** Compile path shapes once, rather than five regexes for every package/cache directory. */
internal object CachePathPolicy {
    val packageName = Regex("""^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_-]+)+$""")
    val webViewCacheNames = setOf("Cache", "Code Cache", "GPUCache", "GPU Cache")
    private val internal = Regex("""^/data/(?:user/\d+|user_de/\d+|data)/([^/]+)/(.*)$""")
    private val external = Regex("""^/data/media/\d+/Android/data/([^/]+)/cache(?:/.*)?$""")
    private val webViewEngines = setOf("app_webview", "app_hws_webview", "app_x5webview")
    private val profileSuffix = Regex("[_0-9][A-Za-z0-9_.-]*")
    private val backupSuffix = Regex("(?:_|[.-])(?:backup|bak|old)(?:[_.-].*)?$", RegexOption.IGNORE_CASE)

    fun isWebViewEngine(name: String) = !backupSuffix.containsMatchIn(name) && webViewEngines.any { name == it ||
        (name.startsWith(it) && name.removePrefix(it).matches(profileSuffix)) }

    fun allows(path: String, owner: String): Boolean {
        if (!packageName.matches(owner) || '\u0000' in path) return false
        val parts = path.split('/')
        if (parts.any { it == "." || it == ".." }) return false
        external.matchEntire(path.trimEnd('/'))?.let { return it.groupValues[1] == owner }
        val match = internal.matchEntire(path.trimEnd('/')) ?: return false
        if (match.groupValues[1] != owner) return false
        val relative = match.groupValues[2].split('/')
        if (relative.first() in setOf("cache", "code_cache")) return true
        if (!isWebViewEngine(relative.first())) return false
        // Discovery checks at most three levels below the WebView engine directory.
        return relative.drop(1).take(3).any { it in webViewCacheNames }
    }
}
