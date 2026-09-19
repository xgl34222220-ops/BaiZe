package io.github.xgl34222220.baize.root

/** Maps MediaStore/App-visible storage paths to Root-visible backing paths. */
internal object ApkRootPathMapper {
    private val emulated = Regex("""^/storage/emulated/(\d+)(/.*)?$""")
    private val runtimeEmulated = Regex("""^/mnt/runtime/[^/]+/emulated/(\d+)(/.*)?$""")
    private val managedEmulated = Regex("""^/mnt/(?:installer|androidwritable|pass_through)/(\d+)/emulated/(\d+)(/.*)?$""")
    private val userPrimary = Regex("""^/mnt/user/(\d+)/primary(/.*)?$""")
    private val rawMedia = Regex("""^/data/media/(\d+)(/.*)?$""")
    private val rawRemovable = Regex("""^/mnt/media_rw/([^/]+)(/.*)?$""")
    private val publicRemovable = Regex("""^/storage/([^/]+)(/.*)?$""")

    fun rootCandidates(path: String): List<String> {
        val input = normalize(path)
        if (input.isBlank()) return emptyList()
        val result = LinkedHashSet<String>()

        fun add(value: String?) {
            val normalized = value?.let(::normalize).orEmpty()
            if (normalized.isNotBlank()) result += normalized
        }

        emulated.matchEntire(input)?.let { match ->
            add("/data/media/${match.groupValues[1]}${match.groupValues[2]}")
        }
        runtimeEmulated.matchEntire(input)?.let { match ->
            add("/data/media/${match.groupValues[1]}${match.groupValues[2]}")
        }
        managedEmulated.matchEntire(input)?.let { match ->
            val storageUser = match.groupValues[2].ifBlank { match.groupValues[1] }
            add("/data/media/$storageUser${match.groupValues[3]}")
        }
        userPrimary.matchEntire(input)?.let { match ->
            add("/data/media/${match.groupValues[1]}${match.groupValues[2]}")
        }
        if (input == "/storage/self/primary" || input.startsWith("/storage/self/primary/")) {
            val suffix = input.removePrefix("/storage/self/primary")
            add("/data/media/0$suffix")
        }
        if (input == "/sdcard" || input.startsWith("/sdcard/")) {
            val suffix = input.removePrefix("/sdcard")
            add("/data/media/0$suffix")
        }
        publicRemovable.matchEntire(input)?.let { match ->
            val volume = match.groupValues[1]
            if (volume !in setOf("emulated", "self", "enc_emulated", "runtime")) {
                add("/mnt/media_rw/$volume${match.groupValues[2]}")
            }
        }

        add(input)
        return result.toList()
    }

    fun publicPath(path: String): String {
        val input = normalize(path)
        rawMedia.matchEntire(input)?.let { match ->
            return "/storage/emulated/${match.groupValues[1]}${match.groupValues[2]}"
        }
        rawRemovable.matchEntire(input)?.let { match ->
            return "/storage/${match.groupValues[1]}${match.groupValues[2]}"
        }
        return input
    }

    private fun normalize(path: String): String {
        val trimmed = path.trim()
        if (trimmed.length <= 1) return trimmed
        return trimmed.trimEnd('/')
    }
}
