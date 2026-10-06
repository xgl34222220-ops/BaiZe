package io.github.xgl34222220.baize

/** Keep uninstalled/hidden protected apps too; filtering never changes the saved set. */
internal data class WhitelistDraft(val original: Set<String> = emptySet(), val selected: Set<String> = emptySet()) {
    val added: Set<String> get() = selected - original
    val removed: Set<String> get() = original - selected
    val dirty: Boolean get() = original != selected
    fun toggle(packageName: String): WhitelistDraft = copy(selected =
        if (packageName in selected) selected - packageName else selected + packageName)
    fun rebase(latest: Set<String>): WhitelistDraft = WhitelistDraft(latest, (latest - removed) + added)
}

internal data class WhitelistApp(val packageName: String, val label: String, val system: Boolean = false)
internal data class WhitelistUiState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val packagesLoaded: Boolean = false,
    val pathsLoaded: Boolean = false,
    val apps: List<WhitelistApp> = emptyList(),
    val paths: List<String> = emptyList(),
    val rootPaths: Set<String> = emptySet(),
    val legacyPaths: Set<String> = emptySet(),
    val legacyPackages: Set<String> = emptySet(),
    val pathAliases: Map<String, List<String>> = emptyMap(),
    val addingPath: Boolean = false,
    val pathSaveError: String = "",
    val pathSaveRevision: Int = 0,
    val draft: WhitelistDraft = WhitelistDraft(),
    val focusFile: String? = null,
    val focusDetails: ApkProtectionDetails? = null,
    val message: String = "正在连接 Root 服务…"
)

internal fun WhitelistUiState.withProtection(snapshot: WhitelistProtectionSnapshot,
    guard: ApkDeletionGuard? = null): WhitelistUiState {
    val entries = snapshot.pathEntries
    return copy(focusDetails = focusFile?.let { file ->
            guard?.protectionDetails(file, ApkProtectionState.KnownRoot(snapshot.effective))
                ?: ApkProtectionDetails(unavailableReason = "此文件的保护范围尚未核对，请刷新。")
        }, paths = entries.map { it.path },
        rootPaths = entries.filter { it.rootRecords.isNotEmpty() }.map { it.path }.toSet(),
        legacyPaths = entries.filter { it.localRecords.isNotEmpty() }.map { it.path }.toSet(),
        legacyPackages = snapshot.local.packages,
        pathAliases = entries.associate { it.path to (it.rootRecords + it.localRecords).sorted() })
}

