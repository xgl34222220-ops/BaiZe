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
    val draft: WhitelistDraft = WhitelistDraft(),
    val message: String = "正在连接 Root 服务…"
)
