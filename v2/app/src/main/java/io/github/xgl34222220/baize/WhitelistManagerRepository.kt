package io.github.xgl34222220.baize

import android.content.Context
import io.github.xgl34222220.baize.root.AndroidPathIdentity

/** Every displayed rule remains owned by its original store until the user edits it. */
internal data class WhitelistProtectionSnapshot(val root: ApkProtectionRules, val local: ApkProtectionRules,
    val primaryStorageRoot: String? = null) {
    val effective: ApkProtectionRules get() = root.plus(local)
    val pathEntries: List<WhitelistPathEntry> get() {
        val aliases = AndroidPathIdentity(primaryStorageRoot)
        return effective.paths.groupBy(aliases::of).map { (path, records) ->
            WhitelistPathEntry(path, records.filter { it in root.paths }.toSet(), records.filter { it in local.paths }.toSet())
        }.sortedBy { it.path }
    }
}

internal data class WhitelistPathEntry(val path: String, val rootRecords: Set<String>, val localRecords: Set<String>)
internal data class WhitelistPathAddition(val snapshot: WhitelistProtectionSnapshot, val alreadyProtected: Boolean)

internal interface WhitelistProtectionAccess {
    fun read(): ApkProtectionRules
    fun updatePackages(added: Set<String>, removed: Set<String>)
    fun addPath(path: String)
    fun removePath(path: String)
}

/** Runs on IO. Exact edits preserve unrelated rules and never migrate or clear either store. */
internal class WhitelistManagerRepository(private val context: Context, private val remote: WhitelistProtectionAccess,
    private val primaryStorageRoot: String? = null) {
    fun read(): WhitelistProtectionSnapshot = synchronized(LOCK) { readUnlocked() }

    private fun readUnlocked(): WhitelistProtectionSnapshot {
        val local = ApkProtectionStore.legacyRules(context)
        val root = remote.read()
        ApkProtectionStore.rememberRoot(context, root)
        return WhitelistProtectionSnapshot(root, local, primaryStorageRoot)
    }

    fun addPath(raw: String): WhitelistPathAddition = synchronized(LOCK) {
        val path = requireNotNull(WhitelistPathInput.parse(raw).path) { WhitelistPathInput.parse(raw).error }
        val before = readUnlocked() // An unreadable store must never become an empty editable list.
        val aliases = AndroidPathIdentity(primaryStorageRoot)
        check(!aliases.unresolvedUserAlias(path)) { "无法确认主存储位置，请使用 /storage/emulated/用户编号 下的完整路径" }
        if (before.pathEntries.any { it.path == aliases.of(path) }) {
            return@synchronized WhitelistPathAddition(before, alreadyProtected = true)
        }
        remote.addPath(path)
        WhitelistPathAddition(readUnlocked().also {
            check(path in it.root.paths) { "新增路径尚未确认，请刷新核对" }
        }, alreadyProtected = false)
    }

    fun removePath(path: String): WhitelistProtectionSnapshot = synchronized(LOCK) {
        val before = readUnlocked()
        val identity = AndroidPathIdentity(primaryStorageRoot).of(path)
        val entry = requireNotNull(before.pathEntries.find { it.path == identity }) { "此记录已变化，请刷新后再操作" }
        if (entry.rootRecords.isNotEmpty()) {
            entry.rootRecords.forEach(remote::removePath)
            val root = remote.read()
            check(root.paths.intersect(entry.rootRecords).isEmpty()) { "清理服务仍保留此路径，旧版保护未移除，请刷新核对" }
            ApkProtectionStore.rememberRoot(context, root)
        }
        ApkProtectionStore.removeLegacyRules(context, paths = entry.localRecords)
        readUnlocked().also { check(it.pathEntries.none { row -> row.path == identity }) { "此路径仍有保护记录，请刷新核对" } }
    }

    fun updatePackages(added: Set<String>, removed: Set<String>): WhitelistProtectionSnapshot = synchronized(LOCK) {
        require(added.intersect(removed).isEmpty()) { "同一应用不能同时添加和移除" }
        val before = readUnlocked()
        val rootRemoved = removed.intersect(before.root.packages)
        if (added.isNotEmpty() || rootRemoved.isNotEmpty()) {
            remote.updatePackages(added, rootRemoved)
            val root = remote.read()
            check(root.packages.containsAll(added) && root.packages.intersect(rootRemoved).isEmpty()) {
                "清理服务尚未确认应用保护修改，旧版保护未移除，请刷新核对"
            }
            ApkProtectionStore.rememberRoot(context, root)
        }
        ApkProtectionStore.removeLegacyRules(context, packages = removed.intersect(before.local.packages))
        readUnlocked().also {
            check(it.effective.packages.containsAll(added) && it.effective.packages.intersect(removed).isEmpty()) {
                "应用保护记录已变化，请刷新核对"
            }
        }
    }

    companion object { private val LOCK = Any() }
}

internal data class WhitelistPathInput(val path: String?, val error: String = "", val broad: Boolean = false) {
    companion object {
        fun parse(raw: String): WhitelistPathInput {
            if (raw.any { it.code < 32 || it.code == 127 }) return WhitelistPathInput(null, "路径不能包含换行或控制字符")
            val path = raw.trim().replace(Regex("/+"), "/").trimEnd('/')
            if (path.isEmpty()) return WhitelistPathInput(null, "请输入具体文件或文件夹的绝对路径")
            if (!path.startsWith('/') || path.length > 4_096 || path.split('/').any { it == "." || it == ".." }) {
                return WhitelistPathInput(null, "请输入以 / 开头的完整路径，不使用 . 或 ..")
            }
            if (path.any { it in "*?[]" }) return WhitelistPathInput(null, "请输入具体路径，不使用通配符")
            if (path in setOf("/data", "/storage", "/storage/emulated", "/data/media", "/mnt", "/storage/self")) {
                return WhitelistPathInput(null, "范围过大，请指定具体的用户目录、文件夹或文件")
            }
            val broad = path == "/sdcard" || path == "/storage/self/primary" ||
                path.matches(Regex("/(?:storage/emulated|data/media)/[0-9]+")) ||
                path.matches(Regex("/storage/[A-Za-z0-9-]+"))
            return WhitelistPathInput(path, broad = broad)
        }
    }
}
