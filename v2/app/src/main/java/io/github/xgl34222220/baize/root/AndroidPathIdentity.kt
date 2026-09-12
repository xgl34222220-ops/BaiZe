package io.github.xgl34222220.baize.root

import java.io.File

/**
 * Comparison identities for Android's shared-storage and owner CE aliases. These identities are
 * never used as deletion paths: FUSE and bind mounts can name the same files without being symlinks.
 * The device's resolved primary storage root is required before mapping user-relative aliases.
 */
internal class AndroidPathIdentity(primaryStorageRoot: String?) {
    private val primary = primaryStorageRoot?.let(::normalize)?.let { path ->
        shared.matchEntire(path)?.takeIf { it.groupValues[2].isEmpty() }
            ?.let { "/storage/emulated/${it.groupValues[1]}" }
    }

    fun of(raw: String): String {
        val path = normalize(raw)
        shared.matchEntire(path)?.let { match ->
            return "/storage/emulated/${match.groupValues[1]}${match.groupValues[2]}"
        }
        if (path == "/data/data" || path.startsWith("/data/data/")) {
            return "/data/user/0${path.removePrefix("/data/data")}"
        }
        if (primary != null) {
            for (alias in listOf("/sdcard", "/storage/self/primary")) {
                if (path == alias || path.startsWith("$alias/")) return primary + path.removePrefix(alias)
            }
        }
        return path
    }

    companion object {
        private val shared = Regex("^/(?:storage/emulated|data/media)/([0-9]+)(/.*)?$")
        private fun normalize(path: String): String = File(path).normalize().path.trimEnd('/').ifBlank { "/" }
    }
}
