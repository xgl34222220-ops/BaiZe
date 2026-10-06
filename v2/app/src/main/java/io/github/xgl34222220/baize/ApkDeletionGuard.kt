package io.github.xgl34222220.baize

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.system.Os
import android.system.OsConstants
import io.github.xgl34222220.baize.root.AndroidPathIdentity
import org.json.JSONObject
import java.io.File

/** Identity captured during scanning, never reconstructed from an old plan at deletion time. */
internal data class ApkFileIdentity(
    val canonicalPath: String, val device: Long, val inode: Long, val bytes: Long,
    val modifiedSeconds: Long, val changedSeconds: Long,
    val modifiedNanos: Long, val changedNanos: Long
) {
    fun json() = JSONObject().put("path", canonicalPath).put("device", device).put("inode", inode)
        .put("bytes", bytes).put("modified", modifiedSeconds).put("changed", changedSeconds)
        .put("modifiedNanos", modifiedNanos).put("changedNanos", changedNanos)
    companion object {
        fun read(json: JSONObject?): ApkFileIdentity? = runCatching {
            requireNotNull(json)
            ApkFileIdentity(json.getString("path"), json.getLong("device"), json.getLong("inode"),
                json.getLong("bytes"), json.getLong("modified"), json.getLong("changed"),
                json.getLong("modifiedNanos"), json.getLong("changedNanos"))
        }.getOrNull()
    }
}

internal interface ApkFileAccess {
    fun canonical(path: String): String
    fun isDirectoryWithoutLink(path: String): Boolean
    fun identity(path: String): ApkFileIdentity?
    /** A failed read is not evidence of deletion. Only ENOENT qualifies. */
    fun definitelyMissing(path: String): Boolean = false
}

private object AndroidApkFileAccess : ApkFileAccess {
    override fun definitelyMissing(path: String): Boolean = try { Os.lstat(path); false }
        catch (error: android.system.ErrnoException) { error.errno == OsConstants.ENOENT }
    override fun canonical(path: String): String = File(path).canonicalPath
    override fun isDirectoryWithoutLink(path: String): Boolean = Os.lstat(path).let {
        OsConstants.S_ISDIR(it.st_mode) && !OsConstants.S_ISLNK(it.st_mode)
    }
    override fun identity(path: String): ApkFileIdentity? {
        val stat = Os.lstat(path)
        if (!OsConstants.S_ISREG(stat.st_mode) || OsConstants.S_ISLNK(stat.st_mode)) return null
        return ApkFileIdentity(canonical(path), stat.st_dev, stat.st_ino, stat.st_size,
            stat.st_mtime, stat.st_ctime,
            if (Build.VERSION.SDK_INT >= 27) stat.st_mtim.tv_nsec else -1L,
            if (Build.VERSION.SDK_INT >= 27) stat.st_ctim.tv_nsec else -1L)
    }
}

/** Shared by both APK entry points. Root aliases are comparison identities, not deletion paths. */
internal class ApkDeletionGuard(
    private val roots: Set<String>,
    private val primaryStorageRoot: String?,
    private val files: ApkFileAccess = AndroidApkFileAccess
) {
    fun deletionConfirmed(path: String): Boolean = runCatching {
        val root = roots.filter { path.startsWith("$it/") }.maxByOrNull { it.length } ?: return false
        val relative = path.removePrefix("$root/")
        if (!files.isDirectoryWithoutLink(files.canonical(root))) return false
        if (files.canonical(path) != "${files.canonical(root).trimEnd('/')}/$relative") return false
        var parent = root
        for (part in relative.split('/').dropLast(1)) {
            parent += "/$part"
            if (!files.isDirectoryWithoutLink(parent)) return false
        }
        files.definitelyMissing(path)
    }.getOrDefault(false)

    /** The provider may return before FUSE invalidates its cached dentry. No further mutation. */
    fun awaitDeletionConfirmation(path: String, original: ApkFileIdentity,
        now: () -> Long = android.os.SystemClock::elapsedRealtime,
        pause: (Long) -> Unit = { Thread.sleep(it) }, budgetMs: Long = 500L): Boolean {
        val budget = budgetMs.coerceIn(0L, 1_000L)
        val deadline = now() + budget
        var pollsLeft = (budget / 20L).toInt() + 1
        while (true) {
            if (deletionConfirmed(path)) return true
            val current = capture(path)
            // A replacement, lost directory, or unreadable path cannot prove success.
            if (current != original) return deletionConfirmed(path)
            val remaining = deadline - now()
            if (remaining <= 0 || pollsLeft-- <= 0) return false
            try { pause(minOf(20L, remaining)) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return deletionConfirmed(path)
            }
        }
    }
    private val aliases = AndroidPathIdentity(primaryStorageRoot)
    private val publicVolumeRoots = roots.filter {
        it.matches(Regex("/storage/[A-Za-z0-9-]+")) && it !in setOf("/storage/emulated", "/storage/self")
    }

    private fun protectionIdentities(path: String): Set<String> = buildSet {
        val normal = aliases.of(path)
        add(normal)
        // AOSP VolumeInfo.getInternalPathForUser maps a public volume's /storage path
        // to /mnt/media_rw. Only volumes actually reported by this App's Context qualify.
        publicVolumeRoots.forEach { root ->
            if (normal == root || normal.startsWith("$root/")) {
                add("/mnt/media_rw/" + root.removePrefix("/storage/") + normal.removePrefix(root))
            }
        }
    }

    /** The decision and its explanation use this one matcher, including public-volume aliases. */
    fun protectionDetails(path: String, protection: ApkProtectionState): ApkProtectionDetails {
        if (OrdinaryFileTrash.isPayloadPath(path)) return ApkProtectionDetails(internalTrash = true)
        if (!validPath(path)) return ApkProtectionDetails(unavailableReason = "文件路径无效，无法核对保护范围；文件保留。")
        if (protection is ApkProtectionState.Unknown) return ApkProtectionDetails(unavailableReason = protection.reason)
        val rules = protection.rules ?: return ApkProtectionDetails(unavailableReason = "保护名单尚未核对，文件保留。")
        if (aliases.unresolvedUserAlias(path) || rules.paths.any(aliases::unresolvedUserAlias)) {
            return ApkProtectionDetails(unavailableReason = "无法确认主存储路径别名，保护范围尚未核对；请重连保护服务后重新扫描。")
        }
        val identities = protectionIdentities(path)
        val paths = rules.paths.mapNotNull { rule ->
            val prefix = aliases.of(rule)
            if (prefix == "/" || identities.any { it == prefix || it.startsWith("$prefix/") }) {
                ApkPathProtectionMatch(rule, prefix, identities.none { it == prefix })
            } else null
        }.sortedWith(compareByDescending<ApkPathProtectionMatch> { it.identity.length }.thenBy { it.rule })
        val components = aliases.of(path).split('/')
        val data = components.indexOf("Android")
        val pkg = if (data >= 0 && components.getOrNull(data + 1) == "data") components.getOrNull(data + 2) else null
        return ApkProtectionDetails(paths, setOfNotNull(pkg?.takeIf { it in rules.packages }))
    }

    fun capture(path: String): ApkFileIdentity? = runCatching {
        if (!validPath(path) || aliases.unresolvedUserAlias(path)) return null
        val root = roots.filter { path.startsWith("$it/") }.maxByOrNull { it.length } ?: return null
        val relative = path.removePrefix("$root/")
        val canonicalRoot = files.canonical(root).trimEnd('/')
        // The platform storage root may be an alias; no link below that root is accepted.
        val expectedCanonical = "$canonicalRoot/$relative"
        if (files.canonical(path) != expectedCanonical) return null
        var parent = root
        for (part in relative.split('/').dropLast(1)) {
            parent += "/$part"
            if (!files.isDirectoryWithoutLink(parent)) return null
        }
        files.identity(path)?.takeIf { it.canonicalPath == expectedCanonical && it.bytes > 0L }
    }.getOrNull()

    fun validate(
        uri: String, path: String, bytes: Long, modifiedSeconds: Long,
        original: ApkFileIdentity?, protection: ApkProtectionState
    ): ApkIndexedDeleteResult? {
        if (OrdinaryFileTrash.isPayloadPath(path)) return ApkIndexedDeleteResult.PROTECTED
        if (!validUri(uri) || !validPath(path)) return ApkIndexedDeleteResult.INVALID
        val details = protectionDetails(path, protection)
        if (details.unavailableReason != null) return ApkIndexedDeleteResult.PROTECTION_UNAVAILABLE
        if (details.isProtected) return ApkIndexedDeleteResult.PROTECTED
        if (original == null) return ApkIndexedDeleteResult.UNVERIFIED
        val current = capture(path) ?: return ApkIndexedDeleteResult.CHANGED
        // Provider dates and filesystem dates are separate observations. Android may
        // report different timestamp precision; each must match its own original.
        if (current != original || bytes <= 0L || current.bytes != bytes || modifiedSeconds <= 0L) return ApkIndexedDeleteResult.CHANGED
        return null
    }

    companion object {
        fun forContext(context: Context): ApkDeletionGuard {
            @Suppress("DEPRECATION")
            val primary = runCatching { Environment.getExternalStorageDirectory().canonicalPath }.getOrNull()
            val roots = linkedSetOf<String>()
            @Suppress("DEPRECATION")
            val configured = Environment.getExternalStorageDirectory().absolutePath
            val candidates = listOfNotNull(configured, primary) + context.getExternalFilesDirs(null).mapNotNull { dir ->
                dir?.absolutePath?.substringBefore("/Android/data/")
            }
            candidates.filterTo(roots) { it.matches(Regex("/storage/(?:emulated/[0-9]+|[A-Za-z0-9-]+)")) }
            if (primary?.matches(Regex("/storage/emulated/[0-9]+")) == true) {
                for (alias in listOf("/sdcard", "/storage/self/primary")) {
                    if (runCatching { File(alias).canonicalPath == primary }.getOrDefault(false)) roots += alias
                }
            }
            return ApkDeletionGuard(roots, primary)
        }

        fun validPath(path: String): Boolean = path.startsWith('/') && path.length <= 4_096 &&
            path.none { it == '\u0000' || it == '\n' || it == '\r' } &&
            !path.contains("//") && path.split('/').none { it == "." || it == ".." } && !path.endsWith('/')

        fun validUri(raw: String): Boolean = runCatching {
            val uri = Uri.parse(raw)
            val id = uri.lastPathSegment?.toLongOrNull() ?: return false
            id > 0 && raw == "content://media/external/file/$id" && uri.scheme == "content" &&
                uri.authority == "media" && uri.query == null && uri.fragment == null
        }.getOrDefault(false)
    }
}
